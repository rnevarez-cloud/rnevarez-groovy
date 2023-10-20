// Title: Schedule work based on Opsgenie schedule	
// Author: Ricardo Nevarez Jr - 2023/09/08
// Description: When a ticket is transferred to another team and the "Planned Start" field is not empty, this script will automatically
// assign an agent to work on the ticket based on their availability in Opsgenie.

import java.time.*
import java.text.*

//------------------------------
// Function to query Jira Assets
//------------------------------

def insQuery(x) { 
    get("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/iql/objects")
                .header("Accept", "application/json")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString('iql', x)
                .queryString('resultPerPage','1000')
                .asObject(Map)
                .getBody()
}

def issueKey = issue.key

//------------------------------
// Obtaining Jira issue information
//------------------------------
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields
def customFieldStart = "customfield_10043"
def customFieldSkill = "customfield_10170"
def customFieldTeam = "customfield_10101"

if(issue.labels.contains("Scheduled")) {

    String labelData = """{
        "update": {
                "labels": [{
                    "remove": "Scheduled"
                }]
        }
    }"""

    def labels = put("/rest/api/2/issue/${issueKey}")
            .header('Content-Type', 'application/json')
            .body(labelData)
            .asString()
            .getBody()

    //------------------------------
    // Check "Resource Group" field - if not empty, save Asset Object ID to customFieldSkill variable
    //------------------------------

    if(!(issue[customFieldSkill].isEmpty())) {
        customFieldSkill = issue[customFieldSkill].objectId.get(0)
    } else {
        println "No skill found. Cannot assign request to agent."
        return "No skill found. Cannot assign request to agent."
    }
    
    //------------------------------
    // Check "Team Responsible" field - if not empty, query Jira Assets for Team object
    //------------------------------

    if(!(issue[customFieldTeam].isEmpty())) {
        customFieldTeam = issue[customFieldTeam].objectId.get(0)
        
        def query = """ "objectType" = "Team" AND "Key" = "OMM-${customFieldTeam}" """
        customFieldTeam = insQuery(query)
        
        try {
            customFieldTeam = customFieldTeam.objectEntries[0].attributes.find { it.objectTypeAttributeId == "4008" }.objectAttributeValues.value.get(0)
            println "------------------------------"
            println "Team: ${customFieldTeam}"
            println "------------------------------"
        } catch(Exception) {
            println "Team not found. Cannot assign request to team."
        }
        
    } else {
        println "No team found. Cannot assign request to agent."
        return
    }
    
    String scheduledStart = issue[customFieldStart]
    
    //------------------------------
    // Convert "Planned Start" date in Jira to ISO format
    //------------------------------

    Date date = Date.parse ("yyyy-MM-dd'T'HH:mm:ss.SSSZ",scheduledStart)
    SimpleDateFormat sdf;
    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    scheduledStart = sdf.format(date);
    
    //------------------------------
    // Convert "Planned Start" date in Jira to "yyyy-MM-dd" format for JQL
    //------------------------------

    SimpleDateFormat sdfJql;
    sdfJql = new SimpleDateFormat("yyyy-MM-dd");
    sdfJql.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    scheduledStartJql = sdfJql.format(date)
    
    println "------------------------------"
    println scheduledStart
    println "------------------------------"
    
    def onCall = get("https://api.opsgenie.com/v2/schedules/${customFieldTeam}/on-calls?scheduleIdentifierType=name&flat=true&date=${scheduledStart}").header("Accept", "application/json").header("Authorization", "GenieKey ${GENIEKEY}").asObject(Map).getBody().data.onCallRecipients
    
    println "------------------------------"
    println """On-Call Agents: ${onCall}"""
    println "------------------------------"

    def agentArray = []
    
    for(a in onCall) {

        //------------------------------
        // Check each available agent's skills to determine whether they can complete the request. 
        //------------------------------

        def query = """ objectType = Person AND object having inR("Skills" = "${customFieldSkill}") AND Email = ${a} """
        def agent = insQuery(query)
        
        if(agent.objectEntries.isEmpty()) {
                //------------------------------
                // If the agent is not in the ticket's "Resource Group", skip the agent and continue the loop.
                //------------------------------
            continue;
        }
        
        try {
            //------------------------------
            // Obtain the user's Jira user ID from their Person record in Jira Assets
            //------------------------------

            def jiraUser = agent.objectEntries[0].attributes.find { it.objectTypeAttributeId == "2444" }.objectAttributeValues.user.key.get(0)
            
            //------------------------------
            // Query open Jira issues to determine if the agent already has a similar request scheduled at the same time
            //------------------------------

            String jql = """ 'cf[10170]' = 'ari:cloud:cmdb::object/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/${customFieldSkill}' AND 'assignee' = '${jiraUser}' AND 'cf[10043]' >= '${scheduledStartJql}' AND 'cf[10043]' <= '${scheduledStartJql} 23:59' AND 'resolution' is EMPTY """
            println "------------------------------"
            println jql
            println "------------------------------"

            def issueQuery = get("/rest/api/2/search").header('Content-Type','application/json').queryString("jql",jql).queryString("fields","key").asObject(Map).getBody()
            
            if(issueQuery.total == 0) {
                //------------------------------
                // If the agent does not have a request scheduled at the same time, then add them to the pool of available agents
                //------------------------------
                agentArray.add(jiraUser)
            } else {
                println "------------------------------"
                println "Agent ${a} is assigned to ${issueQuery.issues.key}. Skipping."
                println "------------------------------"
            }
            
        } catch(Exception) {
            println "Agent ${a} could not be added to array. Agent may not have the skills required. Please check their Resource/Person record."
            continue;
        }
    
    }
    
    if(agentArray.isEmpty()) {

        //------------------------------
        // If no agents are available to complete the request, post a comment in the ticket.
        //------------------------------

        def commentBody = """{
            "body": "No available agents were found. Please schedule this request manually.",
            "properties":[{
                    "key": "sd.public.comment",
                    "value": {
                        "internal": true
                        }
                    }]
                }
            }"""
        
        def comment = post("/rest/api/2/issue/${issueKey}/comment")
            .header('Content-Type', 'application/json')
            .body(commentBody)
            .asString()

        if (comment.status == 204) { 
            println 'Success'
        } else {
            println "${comment.status}: ${comment.body}"
        }
    } else {
        
        println agentArray

        //------------------------------
        // Randomly select an agent from the pool of available agents and assign them to the ticket
        //------------------------------

        Random randomizer = new Random();
        String randomAgent = agentArray.get(randomizer.nextInt(agentArray.size()));
        
        def assignBody = """ {
            "accountId": "${randomAgent}"
        } """
        
        println assignBody
        
        def assignIssue = put("/rest/api/2/issue/${issueKey}/assignee").header('Content-Type','application/json').body(assignBody).asObject(Map)
        
        if (assignIssue.status == 204) { 
            println 'Success'
        } else {
            println "${assignIssue.status}: ${assignIssue.body}"
        }
    }
}
