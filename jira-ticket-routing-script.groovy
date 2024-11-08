// Title: Skill-Based Routing Script
// Author: Ricardo Nevarez Jr - 2024/04/29
// Description: When a ticket is transferred to another team, this script will automatically assign an agent to work on the ticket based on their availability in Opsgenie.
// Version 2024-10-28

int waitFor = 15;
Thread.sleep(waitFor * 1000);

import java.time.*;
import java.text.*;
import groovy.time.TimeDuration;
import groovy.time.TimeCategory;
import com.fasterxml.jackson.databind.node.*;

def insQuery(x) { 
    JsonNodeFactory jnf = JsonNodeFactory.instance;
    ObjectNode payload = jnf.objectNode();
    {payload.put("qlQuery", "${x}");}
    println payload
    
    //------------------------------
    // Function to query Jira Assets
    //------------------------------
    post("https://api.atlassian.com/jsm/assets/workspace/${WORKSPACEID}/v1/object/aql")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString('maxResults','100')
                .body(payload)
                .asObject(Map)
                .getBody()
}

class Agent {
    String agentEmail;
    String agentId;
    Boolean equalDistribution;
    Integer issueCount;
    Object resourceObject;
    Date lastAssigned;
}

//------------------------------
//Obtaining current date
//------------------------------

def date = new Date()
SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
def jqlDate = f.format(date).toString()

def issueKey = issue.key

//------------------------------
// Obtaining Jira issue information
//------------------------------
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields

def customFieldSkill = "customfield_10170"
def customFieldTeam = "customfield_10101"

//------------------------------
// Check "Resource Group" field - if not empty, save Asset Object ID to customFieldSkill variable
//------------------------------

if(!(issue[customFieldSkill].isEmpty())) {
    customFieldSkill = issue[customFieldSkill].objectId.get(0)
} else {
    println "------------------------------"
    println "No skill found. Cannot assign request to agent."
    println "------------------------------"
    return
}

//------------------------------
// Obtain list of on-call users from Jira Operations
//------------------------------

def onCallUsers = []

def teams = get("https://api.atlassian.com/jsm/ops/api/${CLOUDID}/v1/schedules/")
                .header("Accept", "*/*")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString("expand","rotation")
                .asObject(Map)

String status = teams.status.toString()
println status

while(status =~ "(429|50.)") {
    teams = get("https://api.atlassian.com/jsm/ops/api/${CLOUDID}/v1/schedules/")
            .header("Accept", "*/*")
            .header("Authorization", "Basic ${INSIGHT}")
            .queryString("expand","rotation")
            .asObject(Map)

    status = teams.status.toString()
    println status

}

for(t in teams.body.values) {
    println t
    def oncall = get("https://api.atlassian.com/jsm/ops/api/${CLOUDID}/v1/schedules/${t.id}/on-calls")
                .header("Accept", "*/*")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString("flat","true")
                .asObject(Map)

    status = oncall.status.toString()
    println status

    while(status =~ "(429|50.)") {
        waitFor = 5;
        Thread.sleep(waitFor * 1000);

        oncall = get("https://api.atlassian.com/jsm/ops/api/${CLOUDID}/v1/schedules/${t.id}/on-calls")
                .header("Accept", "*/*")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString("flat","true")
                .asObject(Map)

        status = oncall.status.toString()
        println status
    }
    
    onCallUsers.addAll(oncall.body.onCallUsers)
}

println "------------------------------"
println """On-Call Agents: ${onCallUsers}"""
println "------------------------------"

def query = """ objectType = Person AND object having inR("Skills" = "${customFieldSkill}" AND Status = "Active") AND "Jira User" IN ${onCallUsers.toString().replace("[","(").replace("]",")")} """
def agent = insQuery(query)

def agentArray = []
def finalAgentArray = []

for(a in agent.values) {

    try {
        //------------------------------        
        // Check the user's resource record to see if they have "Equal Distribution" enabled
        //------------------------------
        Boolean equalDistribution = false

        if((a.attributes.find {it.objectTypeAttributeId == '4131'}) != null) {
            equalDistribution = a.attributes.find {it.objectTypeAttributeId == '4131'}.objectAttributeValues.value.get(0).toBoolean()
            println """Equal Distribution: ${a.attributes.find {it.objectTypeAttributeId == '4131'}.objectAttributeValues.value.get(0).toBoolean()}"""
        }    
    
        //------------------------------
        // Obtain the user's Jira user ID from their Person record in Jira Assets
        //------------------------------

        def email = a.attributes.find { it.objectTypeAttributeId == "2442" }.objectAttributeValues.value.get(0)
        
        //------------------------------
        // Obtain the user's last assignment time from their resource record
        //------------------------------

        Date lastAssigned = null

        if((a.attributes.find {it.objectTypeAttributeId == '4132'}) != null) {
            lastAssigned = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",a.attributes.find {it.objectTypeAttributeId == '4132'}.objectAttributeValues.value.get(0))
        } else {
            lastAssigned = date
        }  

        //------------------------------
        // Add agent to the pool of available agents
        //------------------------------

        Agent newAgent = new Agent()
        newAgent.agentEmail = email
        newAgent.agentId = a.attributes.find {it.objectTypeAttributeId == '2444'}.objectAttributeValues.user.key.get(0)
        newAgent.equalDistribution = equalDistribution
        newAgent.resourceObject = a
        newAgent.lastAssigned = lastAssigned
        println "------------------------------"
        println """Adding ${email} to available agent array."""
        println "------------------------------"
        agentArray.add(newAgent)
        
    } catch(Exception) {
        println "------------------------------"
        println "Agent ${a.label} could not be added to array. Agent may not have the skills required. Please check their Resource/Person record."
        println "------------------------------"
        continue;
    }

}

if(agentArray.isEmpty()) {

    //------------------------------
    // If no agents are available to complete the request, post a comment in the ticket.
    //------------------------------

    def commentBody = """{
        "body": "No available agents were found. Please assign this request manually.",
        "properties":[{
                "key": "sd.public.comment",
                "value": {
                    "internal": true
                    }
                }]
            }
        }"""
    
    def comment = post("/rest/api/2/issue/${issueKey}/comment").header('Content-Type', 'application/json').body(commentBody).asString()
    if (comment.status == 204) {println 'Success'} else {println "${comment.status}: ${comment.body}"}
} else {
    println "------------------------------"
    println agentArray
    println "------------------------------"

    for(a in agentArray) {
        if(a.equalDistribution == true && (a.resourceObject.attributes.find {it.objectTypeAttributeId == '4001'}) != null) {
            //------------------------------
            // If "Equal Distribution" is set to true, and the resource has a daily ticket cap, then check to see if this ticket would cause a skill imbalance
            //------------------------------
            Integer skillCount = a.resourceObject.attributes.find {it.objectTypeAttributeId == '3958'}.objectAttributeValues.size()
            Integer skillLimit = (a.resourceObject.attributes.find {it.objectTypeAttributeId == '4001'}.objectAttributeValues.value.get(0).toInteger()) / skillCount
            
            println "------------------------------"
            println """Skill Limit: ${skillLimit}"""
            println "------------------------------"

            String jql = """assignee CHANGED ON ${jqlDate} TO ${a.agentId} AND assignee = ${a.agentId} AND "Resource Group" in aqlFunction("Key LIKE ${customFieldSkill}")"""
            println "------------------------------"
            println jql
            println "------------------------------"

            def issueQuery = get("/rest/api/2/search").header('Content-Type','application/json').queryString("jql",jql).queryString("fields","key").asObject(Map).getBody()
            if(issueQuery.total.toInteger() >= skillLimit) {
                println "------------------------------"
                println "Agent ${a.agentEmail} has ${issueQuery.total} issue(s) with this skill. Removing them from pool of available agents to fulfill equal skill distribution."
                println "------------------------------"
                continue;
            }
        }

        //------------------------------
        // Query agent's currently assigned issues for the day
        //------------------------------

        String jql = """assignee CHANGED ON ${jqlDate} TO ${a.agentId} AND assignee = ${a.agentId} order by cf[10238] DESC"""
        println "------------------------------"
        println jql
        println "------------------------------"

        def issueQuery = get("/rest/api/2/search").header('Content-Type','application/json').queryString("jql",jql).queryString("fields","key,customfield_10238").asObject(Map).getBody()

        a.issueCount = issueQuery.total

        println "------------------------------"
        println """Agent: ${a.agentEmail}"""
        println """Issue Count: ${a.issueCount}"""
        println """Equal Distribution: ${a.equalDistribution}"""
        println "------------------------------"

        finalAgentArray.add(a)
    }

    println "------------------------------"
    println finalAgentArray
    println "------------------------------"

    //------------------------------
    // Select the agent from the pool with the lowest number of assigned cases and assign them to the ticket
    //------------------------------

    Collections.sort(finalAgentArray, Comparator.comparing(Agent::getLastAssigned))
    Collections.sort(finalAgentArray, Comparator.comparing(Agent::getIssueCount))
    
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm z")
    df.setTimeZone(TimeZone.getTimeZone("America/New_York"))

    for(a in finalAgentArray) {
        println "------------------------------"
        println """${a.agentEmail} -- ${a.issueCount} -- ${df.format(a.lastAssigned).toString()}"""
        println "------------------------------"

    }

    Agent nextAvailable = finalAgentArray.get(0)

    println "------------------------------"
    println "Next Agent Available: ${nextAvailable.agentEmail}"
    println """Issue Count: ${nextAvailable.issueCount}"""
    println """Equal Distribution: ${nextAvailable.equalDistribution}"""
    println "------------------------------"

    def assignBody = """ {"accountId": "${nextAvailable.agentId}"} """
    
    println assignBody
    
    def assignIssue = put("/rest/api/2/issue/${issueKey}/assignee").header('Content-Type','application/json').body(assignBody).asObject(Map)
    if (assignIssue.status == 204) {println 'Success'} else {println "${assignIssue.status}: ${assignIssue.body}"}
}

Date stop = new Date()
TimeDuration td = TimeCategory.minus( stop, date )
println td.toMilliseconds()
