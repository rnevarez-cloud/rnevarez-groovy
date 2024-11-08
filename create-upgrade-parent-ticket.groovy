// ------------------------------------------------------------------------------------------------------------------------
// Title: Generate new upgrade patch tickets
// Author: Ricardo Nevarez Jr - 2024/10/31
// Revision: 2024/11/05
// Description: Queries Jira Assets for all Organization objects with "Batch Ticket Creation" set to "TRUE" and generates tickets for 
// each object based on the parent ticket.
// ------------------------------------------------------------------------------------------------------------------------
// Fields set:
// * Project (project) - Company (COM)
// * Issue Type (issuetype) - Change (10112)
// * Summary (summary)
// * Description (description)
// * Request Type (customfield_10010) - Application Upgrade (565)
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
    """{"workspaceId": "${WORKSPACEID}","id": "${WORKSPACEID}:${x}","objectId": "${x}"}"""
}

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

def issueKey = issue.key
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').asObject(Map).getBody() 

if(!issue.fields.labels.contains("Upgrade-management-script")) {
    println "Upgrade management script has already been ran!"
    return;
} else {
    String labelData = """{"update": {"labels": [{"remove": "Upgrade-management-script"}]}}"""
    def labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
    if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
}

def requestType = "611"

def summary = issue.fields.summary
def description = ""

try{
    description = issue.fields.description.replaceAll("\\n","\\\\n")
} catch(Exception) {
    logger.error("No description found.")
}

def teamResponsibleId = issue.fields.customfield_10101.objectId.get(0)
teamResponsibleId = """[${assetObject(teamResponsibleId)}]"""
def software = issue.fields.customfield_10117.objectId.get(0)
software = """[${assetObject(software)}]"""
def appVersion = issue.fields.customfield_10162.objectId.get(0)
appVersion = """[${assetObject(appVersion)}]"""

//------------------------------------------------------------
// Query Jira Assets for all Organization objects with the "Batch Ticket Creation" flag set to "TRUE"
//------------------------------------------------------------

def aql = """ objectType = "Organization" AND "Batch Ticket Creation" = "TRUE" """

def customers = insQuery(aql).values

for(a in customers) {    

    //---------------------------------------------------------------------------
    // Query for customs and add them to the ticket creation body
    //---------------------------------------------------------------------------
    
    def customsArray = []

    def customsAql = """
    objectType = "Customer Assets" AND 
    ("NAME" LIKE "INT" OR "NAME" LIKE "CUST" OR "Description" LIKE "platform integration" OR "Description" LIKE "platform connector" OR "Description" LIKE "Platform Third Party Connector") AND 
    Organization = ${a.id} AND
    "Show on portal" = "TRUE"
    """
    println customsAql

    def customs = insQuery(customsAql)
    if(!(customs.values.isEmpty) || (customs.values != null)) {
        for(c in customs.values) {
            customsArray.add(assetObject(c.id))
        }
    }
   
    def organizationId = """[${assetObject(a.id)}]"""
    def participantList = []
    def participantBody = []

    //---------------------------------------------------------------------------
    // Identify the PM and CSM and add them to the ticket as request participants
    //---------------------------------------------------------------------------

    if((a.attributes.find {it.objectTypeAttributeId == '2350'}) != null) {
        participantList.add(a.attributes.find {it.objectTypeAttributeId == '2350'}.objectAttributeValues.referencedObject.objectKey.get(0))
    }

    if((a.attributes.find {it.objectTypeAttributeId == '4009'}) != null) {
        participantList.add(a.attributes.find {it.objectTypeAttributeId == '4009'}.objectAttributeValues.referencedObject.objectKey.get(0))
    }

    for(p in participantList) {
        try{
            def participant = insQuery("Key = ${p}").values.attributes[0].find {it.objectTypeAttributeId == '2444'}.objectAttributeValues.user.key.get(0)
            participant = """{"id": "${participant}"}"""
            participantBody.add(participant)
        } catch(Exception) {
            logger.error("No account found for ${p}. Please add their Jira user to their Person record.")
            continue;
        }
    }

    //---------------------------------------------------------------------------
    // List of environments
    // * Test (86210)
    // * Production (86211)
    //---------------------------------------------------------------------------

    def envList = [
        "86210",
        "86211"
    ]

    def envIds = []

    for(e in envList) {
        envIds.add(assetObject(e))
    }

    String bodyData = """ {
        "fields": {
            "project": {"id": "10096"},
            "issuetype": {"id": "10108"},
            "customfield_10010": "${requestType}",
            "summary":"${summary}",
            "description":"${description}",
            "customfield_10101": ${teamResponsibleId},
            "customfield_10078": ${organizationId},
            "customfield_10278": ${customsArray},
            "customfield_10117": ${software},
            "customfield_10162": ${appVersion},
            "customfield_10161": ${envIds},
            "customfield_11282": {"value":"No"},
            "customfield_10026": ${participantBody}
        }
    } """

    def change = post("/rest/api/2/issue").header('Content-Type','application/json').body(bodyData).asObject(Map)
    
    if (change.status == 201) {
        println "Success: ${change.status}: ${change.body}"
    } else {
        println "${change.status}: ${change.body}"
        return
    }
    
    //------------------------------------------------------------
    // Link change requests to parent ticket
    //------------------------------------------------------------
        
    bodyData = """{"inwardIssue": {"key": "${issueKey}"},"outwardIssue": {"key": "${change.body.key}"},"type": {"name": "Relates"}}"""
           
    def linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
    if (linkIssues.status == 201) {println "Success"} else {println "${linkIssues.status}: ${linkIssues.body}"}

    //------------------------------
    // Set "Batch Ticket Creation" to "FALSE"
    //------------------------------

    bodyData = """ {"attributes": [{"objectTypeAttributeId": "4010","objectAttributeValues": [{"value": "false"}]}],"objectTypeId": "224"} """

    def update = put("https://api.atlassian.com/jsm/assets/workspace/${WORKSPACEID}/v1/object/${a.id}").header("Accept", "application/json").header("Content-Type", "application/json").header("Authorization", "Basic ${INSIGHT}").body(bodyData).asObject(Map)
    if (update.status == 201) {println "Success"} else {println "${update.status}: ${update.body}"}

    labelData = """{"update": {"labels": [{"add": "cmd-run-upgrade"}]}}"""
    labels = put("/rest/api/2/issue/${change.body.key}").header('Content-Type', 'application/json').body(labelData).asString()
    if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}

    Date stop = new Date()
    TimeDuration td = TimeCategory.minus( stop, start )
    println td.toMilliseconds()

    //------------------------------
    // Check to see if script is about to hit the execution limit. If it is about to hit the limit, 
    // stop the script from processing more objects
    //------------------------------

    if(td.toMilliseconds() >= 200000) {
        logger.error("About to hit ScriptRunner execution limit of 240 seconds")
        labelData = """{"update": {"labels": [{"add": "Upgrade-management-script"}]}}"""
        labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
        if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
        return;
    } else {
        continue;
    }
}
