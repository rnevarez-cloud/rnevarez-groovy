// Title: Enforce Ticket Assignment Limits
// Author: Ricardo Nevarez Jr - 2024/05/01
// Description: When a ticket is assigned, this script checks to see if the agent has reached their daily/total ticket limits.
// Version 2024-11-07

import java.time.*
import java.text.*
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

def issueQuery(x) {
    get("/rest/api/2/search").header('Content-Type','application/json').queryString("jql",x).queryString("fields","key").asObject(Map).getBody()
}

class Agent {
    String agentName;
    String agentId;
}

//------------------------------
//Obtaining current date
//------------------------------

def date = new Date()
SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
def jqlDate = f.format(date).toString()

DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXX")
df.setTimeZone(TimeZone.getTimeZone("America/New_York"))
String assignmentDateESTString = df.format(date)
println assignmentDateESTString

DateFormat dfa = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
//dfa.setTimeZone(TimeZone.getTimeZone("America/New_York"))
String assetDateESTString = dfa.format(date)
println assetDateESTString

def issueKey = issue.key

def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields

if(!issue.labels.contains("cmd-assigned")) {
    return;
} else {
    String labelData = """{"update": {"labels": [{"remove": "cmd-assigned"}]}}"""
    def labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
    if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
}

//------------------------------
// Obtaining Jira issue assignee information
//------------------------------

Agent issueAssignee = new Agent()
issueAssignee.agentName = issue.assignee.displayName
issueAssignee.agentId = issue.assignee.accountId

def query = """ "objectType" = "Resource" AND object HAVING outboundReferences("Jira User" = ${issueAssignee.agentId}) """
def agent = insQuery(query).values.get(0)
println agent
println agent.id

Integer dailyTicketLimit = null
Integer totalTicketLimit = null

if((agent.attributes.find {it.objectTypeAttributeId == '4001'}) != null) {
    dailyTicketLimit = agent.attributes.find {it.objectTypeAttributeId == '4001'}.objectAttributeValues.value.get(0).toInteger()
}    

if((agent.attributes.find {it.objectTypeAttributeId == '4130'}) != null) {
    totalTicketLimit = agent.attributes.find {it.objectTypeAttributeId == '4130'}.objectAttributeValues.value.get(0).toInteger()
}

if((dailyTicketLimit == 0) && (totalTicketLimit == 0)) {
    println "Ticket limits not found for ${issueAssignee.agentName}. Skipping."
    return
}

println "------------------------------"
println "Daily Ticket Limit: ${dailyTicketLimit}"
println "Total Ticket Limit: ${totalTicketLimit}"
println "------------------------------"

String jql = """project = CON AND assignee CHANGED ON ${jqlDate} TO ${issueAssignee.agentId} AND assignee = ${issueAssignee.agentId}"""
println "------------------------------"
println jql
println "------------------------------"

Integer dailyTotal = issueQuery(jql).total.toInteger()

jql = """project = CON AND assignee = ${issueAssignee.agentId} AND statusCategory != Done"""
println "------------------------------"
println jql
println "------------------------------"

Integer allTimeTotal = issueQuery(jql).total.toInteger()

println "------------------------------"
println "Checking ${issueAssignee.agentName} ticket counts:"
println "${dailyTicketLimit} <= ${dailyTotal} && ${dailyTicketLimit} != null"
println "${totalTicketLimit} <= ${allTimeTotal} && ${totalTicketLimit} != null"
println "------------------------------"

if(((dailyTicketLimit <= dailyTotal) && dailyTicketLimit != null) || ((totalTicketLimit <= allTimeTotal) && totalTicketLimit != null)) {
    println "------------------------------"
    println "${issueAssignee.agentName} has reached their ticket limits."
    println "------------------------------"

    def bodyData = """ {"attributes": [{"objectTypeAttributeId": "2169","objectAttributeValues": [{"value": "Capped"}]}],"objectTypeId": "201"} """
    def update = put("https://api.atlassian.com/jsm/assets/workspace/${WORKSPACEID}/v1/object/${agent.id}").header("Accept", "application/json").header("Content-Type", "application/json").header("Authorization", "Basic ${INSIGHT}").body(bodyData).asObject(Map)
    if (update.status == 201) {println "Success"} else {println "${update.status}: ${update.body}"}

} else {
    println "------------------------------"
    println "${issueAssignee.agentName} has not reached their ticket limit."
    println "------------------------------"

    def bodyData = """ {"attributes": [{"objectTypeAttributeId": "2169","objectAttributeValues": [{"value": "Active"}]}],"objectTypeId": "201"} """
    def update = put("https://api.atlassian.com/jsm/assets/workspace/${WORKSPACEID}/v1/object/${agent.id}").header("Accept", "application/json").header("Content-Type", "application/json").header("Authorization", "Basic ${INSIGHT}").body(bodyData).asObject(Map)
    if (update.status == 201) {println "Success"} else {println "${update.status}: ${update.body}"}
}

println "------------------------------"
println """${issueAssignee.agentName} ticket counts:
Daily Tickets: ${dailyTotal}
Total Tickets: ${allTimeTotal}"""
println "------------------------------"

//------------------------------
// Setting last assigned date for ticket/agent
//------------------------------

def assignedBody = """ {"fields": {"customfield_10238": "${assignmentDateESTString}"}} """

println "------------------------------"
println """Assignment Date: ${assignedBody}"""
println "------------------------------"

def assigned = put("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').body(assignedBody).asObject(Map)
if (assigned.status == 201) {println "Success: ${assigned.status}: ${assigned.body}"} else {println "${assigned.status}: ${assigned.body}"}

bodyData = """ {"attributes": [{"objectTypeAttributeId": "4132","objectAttributeValues": [{"value": "${assetDateESTString}"}]}],"objectTypeId": "201"} """
update = put("https://api.atlassian.com/jsm/assets/workspace/${WORKSPACEID}/v1/object/${agent.id}").header("Accept", "application/json").header("Content-Type", "application/json").header("Authorization", "Basic ${INSIGHT}").body(bodyData).asObject(Map)
if (update.status == 201) {println "Success"} else {println "${update.status}: ${update.body}"}
