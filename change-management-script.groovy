// ------------------------------------------------------------------------------------------------------------------------
// Title: Change Management Script
// Author: Ricardo Nevarez Jr - 2024/04/12
// Revision: 2024/11/07
// Description: Queries Jira Assets for all objects with "Batch Ticket Creation" set to "TRUE" and generates tickets for 
// each object based on the parent ticket.
// ------------------------------------------------------------------------------------------------------------------------
// Fields set:
// * Project (project) - Company (COM)
// * Issue Type (issuetype) - Change (10112)
// * Summary (summary)
// * Description (description)
// * Request Type (customfield_10010) - Request a Change (1665) // Application Upgrade (565)
// * Customer (customfield_10078)
// * Device (customfield_10083)
// * Team Responsible (customfield_10101)
// * Software (customfield_10117)
// * Application Environment (customfield_10161)
// * Application Version (customfield_10162)
// ------------------------------------------------------------------------------------------------------------------------

import groovy.time.TimeCategory 
import groovy.time.TimeDuration
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.text.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;
import com.fasterxml.jackson.databind.node.*;

Date start = new Date()

def assetObject(x) {
    """{"workspaceId": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d","id": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d:${x}","objectId": "${x}"}"""
}

def insQuery(x) { 
    JsonNodeFactory jnf = JsonNodeFactory.instance;
    ObjectNode payload = jnf.objectNode();
    {payload.put("qlQuery", "${x}");}
    println payload
    
    //------------------------------
    // Function to query Jira Assets
    //------------------------------
    post("https://api.atlassian.com/jsm/assets/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/object/aql")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString('maxResults','100')
                .body(payload)
                .asObject(Map)
                .getBody()
}

def issueKey = issue.key
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').asObject(Map).getBody() 

if(!issue.fields.labels.contains("change-management-script")) {
    println "Change management script has already been ran!"
    return;
} else {
    String labelData = """{"update": {"labels": [{"remove": "change-management-script"}]}}"""
    def labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
    if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
}

def requestType = null

if(issue.fields.customfield_10010.requestType.id == "1666") {
    requestType = "565"
} else {
    requestType = "1665"
}

println requestType

def summary = issue.fields.summary
def description = issue.fields.description.replaceAll("\\n","\\\\n")
def teamResponsibleId = issue.fields.customfield_10101.objectId.get(0)
teamResponsibleId = """[${assetObject(teamResponsibleId)}]"""
def software = issue.fields.customfield_10117.objectId.get(0)
software = """[${assetObject(software)}]"""

//------------------------------------------------------------
// Query Jira Assets for all Customer Application objects with the "Batch Ticket Creation" flag set to "TRUE"
//------------------------------------------------------------

def aql = """ objectType = "Customer Application" AND "Batch Ticket Creation" = "TRUE" """

def customerApps = insQuery(aql).values

for(a in customerApps) {
    //------------------------------------------------------------
    // Identify the issue fields (summary, description, etc.)
    //------------------------------------------------------------
    def changeSummary = null
    def organizationId = null
    def device = null
    def environment = null
    def appVersion = null
    def appObject = """[${assetObject(a.id)}]"""

    if((a.attributes.find {it.objectTypeAttributeId == '3727'}) != null) {
        def name = a.attributes.find {it.objectTypeAttributeId == '3727'}.objectAttributeValues.value.get(0)
        changeSummary = """${name} - ${summary}"""
    }
    
    if((a.attributes.find {it.objectTypeAttributeId == '3738'}) != null) {
        organizationId = a.attributes.find {it.objectTypeAttributeId == '3738'}.objectAttributeValues.referencedObject.id.get(0)
        organizationId = """[${assetObject(organizationId)}]"""
    }

    if((a.attributes.find {it.objectTypeAttributeId == '3755'}) != null) {
        device = a.attributes.find {it.objectTypeAttributeId == '3755'}.objectAttributeValues.referencedObject.id.get(0)
        device = """[${assetObject(device)}]"""
    }

    if((a.attributes.find {it.objectTypeAttributeId == '3754'}) != null) {
        environment = a.attributes.find {it.objectTypeAttributeId == '3754'}.objectAttributeValues.referencedObject.id.get(0)
        environment = """[${assetObject(environment)}]"""
    }
    if(issue.fields.customfield_10010.requestType.id == "1666") {
        appVersion = issue.fields.customfield_10162.objectId.get(0)
        appVersion = """[${assetObject(appVersion)}]"""
    } else {
        if((a.attributes.find {it.objectTypeAttributeId == '3739'}) != null) {
            appVersion = a.attributes.find {it.objectTypeAttributeId == '3739'}.objectAttributeValues.referencedObject.id.get(0)
            appVersion = """[${assetObject(appVersion)}]"""
        }
    }

    String bodyData = """ {
        "fields": {
            "project": {"id": "10096"},
            "issuetype": {"id": "10112"},
            "customfield_10010": "${requestType}",
            "summary":"${changeSummary}",
            "description":"${description}",
            "customfield_10101": ${teamResponsibleId},
            "customfield_10078": ${organizationId},
            "customfield_10083": ${device},
            "customfield_10117": ${software},
            "customfield_10082": ${appObject},
            "customfield_10162": ${appVersion},
            "customfield_10161": ${environment}
        }
    } """

    def change = post("/rest/api/2/issue").header('Content-Type','application/json').body(bodyData).asObject(Map)
    if (change.status == 201) {println "Success: ${change.status}: ${change.body}"} else {println "${change.status}: ${change.body}"}
    
    //------------------------------------------------------------
    // Link change requests to parent ticket
    //------------------------------------------------------------
        
    bodyData = """{"inwardIssue": {"key": "${issueKey}"},"outwardIssue": {"key": "${change.body.key}"},"type": {"name": "Relates"}}"""
           
    def linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
    if (linkIssues.status == 201) {println "Success"} else {println "${linkIssues.status}: ${linkIssues.body}"}
    
    //------------------------------------------------------------
    // Transition change request to "Backlog"
    //------------------------------------------------------------
    
    def transition = get("/rest/api/2/issue/${change.body.key}/transitions").header('Content-Type','application/json').asObject(Map).getBody().transitions.find {it.to.name == "Backlog"}.id
    String transitionBody = """ {"transition": {"id": "${transition}"}} """
    def transitionIssue = post("/rest/api/2/issue/${change.body.key}/transitions").header('Content-Type', 'application/json').body(transitionBody).asString()
    println "${transitionIssue.status}: ${transitionIssue.body}"

    //------------------------------
    // Set "Batch Ticket Creation" to "FALSE"
    //------------------------------

    bodyData = """ {"attributes": [{"objectTypeAttributeId": "3931","objectAttributeValues": [{"value": "false"}]}],"objectTypeId": "295"} """

    def update = put("https://api.atlassian.com/jsm/assets/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/object/${a.id}").header("Accept", "application/json").header("Content-Type", "application/json").header("Authorization", "Basic ${INSIGHT}").body(bodyData).asObject(Map)
    if (update.status == 201) {println "Success"} else {println "${update.status}: ${update.body}"}

    Date stop = new Date()
    TimeDuration td = TimeCategory.minus( stop, start )
    println td.toMilliseconds()

    //------------------------------
    // Check to see if script is about to hit the execution limit. If it is about to hit the limit, 
    // stop the script from processing more objects
    //------------------------------

    if(td.toMilliseconds() >= 200000) {
        println "About to hit ScriptRunner execution limit of 240 seconds"
        String labelData = """{"update": {"labels": [{"add": "change-management-script"}]}}"""
        def labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
        if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
        return;
    } else {
        continue;
    }
}
