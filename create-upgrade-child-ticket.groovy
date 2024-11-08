// ------------------------------------------------------------------------------------------------------------------------
// Title: Generate new Upgrade patch child tickets
// Author: Ricardo Nevarez Jr - 2024/10/31
// Revision: 2024/11/01
// Description: Creates child tickets for live/test environment Upgrade patches
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

import java.time.*
import java.text.*
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;
import com.fasterxml.jackson.databind.node.*;

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

def weeksToDays(x) {
    x * 7
}

def plusDate(x,y) {
        x = Integer.parseInt(x)
        x = weeksToDays(x)
        Date date = y.plus(x)
}

class Customer {
    String customerId;
    String name;
    String code;
}

def issueKey = issue.key

//Obtaining issue field data
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields

if(!issue.labels.contains("cmd-run-upgrade")) {
    println "Application upgrade script has already been ran!"
    return;
} else {
    String labelData = """{"update": {"labels": [{"remove": "cmd-run-upgrade"}]}}"""
    def labels = put("/rest/api/2/issue/${issueKey}").header('Content-Type', 'application/json').body(labelData).asString()
    if (labels.status == 201) {println "Success"} else {println "${labels.status}: ${labels.body}"}
}

def softwareList = issue.customfield_10117.objectId.toString().replace("[","").replace("]","")
def appVersion = issue.customfield_10162.objectId.get(0)
appVersion = """[${assetObject(appVersion)}]"""

//Obtaining customer data
Customer c = new Customer();
c.customerId = issue.customfield_10078.objectId.get(0)
def customerAsset = insQuery("""Key = OMM-${c.customerId}""").values.get(0)
c.name = customerAsset.label
c.code = customerAsset.attributes.find {it.objectTypeAttributeId == '2320'}.objectAttributeValues.value.get(0)

//Obtaining new application deployment tasks
def query = """ objectType = "Task" AND "Task Type" = "PMM-2714392" ORDER BY Order ASC """
def tasks = insQuery(query).values

//use this code to update due date for each change
//def duedate = new Date()
//println duedate

//println """Issue Date: ${duedate}"""

for(t in tasks) {
    
    println """------------------------"""
    println """*****DEBUG: ${t.id}*****"""
    println """------------------------"""

    def summary = null
    def description = ""
    def assignee = null
    def teamResponsibleId = null
    def softwareId = []
    def envId = []
    def taskId = """[${assetObject(t.id)}]"""

    if((t.attributes.find {it.objectTypeAttributeId == '4029'}) != null) {
        summary = t.attributes.find {it.objectTypeAttributeId == '4029'}.objectAttributeValues.value.get(0)
        summary = summary.replace("{{Customers.Code}}",c.code)
    }
    
    if((t.attributes.find {it.objectTypeAttributeId == '4028'}) != null) {
        description = t.attributes.find {it.objectTypeAttributeId == '4028'}.objectAttributeValues.value.get(0).replace("\n","\\n")
        description = description.replace("{{Customers.Code}}",c.code).replace("{{Customers.Name}}",c.name)
    }    

    if((t.attributes.find {it.objectTypeAttributeId == '4032'}) != null) {
        assignee = t.attributes.find {it.objectTypeAttributeId == '4032'}.objectAttributeValues.searchValue.get(0)
        assignee = """{"id": "${assignee}"}"""
    } else {
        if((t.attributes.find {it.objectTypeAttributeId == '4030'}.objectAttributeValues.referencedObject.id.get(0)) == '118515') {
            assignee = """{"id": "${issue.assignee.accountId}"}"""
        }
    }
    
    if((t.attributes.find {it.objectTypeAttributeId == '4030'}) != null) {
        teamResponsibleId = t.attributes.find {it.objectTypeAttributeId == '4030'}.objectAttributeValues.referencedObject.id.get(0)
        teamResponsibleId = """[${assetObject(teamResponsibleId)}]"""
    }

    if((t.attributes.find {it.objectTypeAttributeId == '4034'}) != null) {
        def software = t.attributes.find {it.objectTypeAttributeId == '4034'}.objectAttributeValues.referencedObject.id
        for(s in software) {
            softwareId.add("""${assetObject(s)}""") 
        }
    }

    if((t.attributes.find {it.objectTypeAttributeId == '4160'}) != null) {
        def environments = t.attributes.find {it.objectTypeAttributeId == '4160'}.objectAttributeValues.referencedObject.id
        for(e in environments) {
            envId.add("""${assetObject(e)}""") 
        }
    }
    
    String bodyData = """ {
        "fields": {
            "project": {"id": "10096"},
            "issuetype": {"id": "10112"},
            "customfield_10010": "565",
            "assignee": ${assignee},
            "summary":"${summary}",
            "description":"${description}",
            "customfield_10078": [${assetObject(c.customerId)}],
            "customfield_10101": ${teamResponsibleId},
            "customfield_10117": ${softwareId},
            "customfield_10161": ${envId},
            "customfield_10162": ${appVersion},
            "customfield_10221": ${taskId}
        }
    }"""
    
    println bodyData
    
    def change = post("/rest/api/2/issue").header('Content-Type','application/json').body(bodyData).asObject(Map)
    if (change.status == 201) {println "Success: ${change.status}: ${change.body}"} else {println "${change.status}: ${change.body}"}
    
    //------------------------------------------------------------
    // Link change requests to parent ticket
    //------------------------------------------------------------
        
    bodyData = """{"inwardIssue": {"key": "${change.body.key}"},"outwardIssue": {"key": "${issueKey}"},"type": {"name": "Children"}}"""
           
    def linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
    if (linkIssues.status == 201) {println "Success"} else {println "${linkIssues.status}: ${linkIssues.body}"}
    

    if((t.attributes.find {it.objectTypeAttributeId == '4031'}) != null) {
        for(b in t.attributes.find {it.objectTypeAttributeId == '4031'}.objectAttributeValues.referencedObject.objectKey) {
            def relatedchanges = get("/rest/api/3/search").header("Accept", "application/json").queryString("jql", """parent = "${issueKey}" AND "Task Type" in iqlFunction("Key = ${b}")""").asObject(Map);
            for(i in relatedchanges.body.issues.key) {
                bodyData = """{"inwardIssue": {"key": "${i}"},"outwardIssue": {"key": "${change.body.key}"},"type": {"name": "Blocks"}}"""
                linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
                println "${linkIssues.status}: ${linkIssues.body}"
            }    
        }
    }
    
    if((t.attributes.find {it.objectTypeAttributeId == '4035'}) != null) {
        def status = t.attributes.find {it.objectTypeAttributeId == '4035'}.objectAttributeValues.displayValue.get(0)
        if(status == "New") {
            continue;
        }
        def transition = get("/rest/api/2/issue/${change.body.key}/transitions").header('Content-Type','application/json').asObject(Map).getBody().transitions.find {it.to.name == status}.id
        String transitionBody = """ {"transition": {"id": "${transition}"}} """
        def transitionIssue = post("/rest/api/2/issue/${change.body.key}/transitions").header('Content-Type', 'application/json').body(transitionBody).asString()
        println "${transitionIssue.status}: ${transitionIssue.body}"
    }

    if((t.attributes.find {it.objectTypeAttributeId == '4033'}) != null) {
        def dueDateCalc = t.attributes.find {it.objectTypeAttributeId == '4033'}.objectAttributeValues.value.get(0)
        dueDateCalc = plusDate(dueDateCalc,duedate)
        
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        def changeDueDate = f.format(dueDateCalc).toString()
        
        String dueDateBody = """ {"fields": {"duedate": "${changeDueDate}"}} """

        def setDueDate = put("/rest/api/2/issue/${change.body.key}").header('Content-Type', 'application/json').body(dueDateBody).asString()
        println "${setDueDate.status}: ${setDueDate.body}"
    }
}

try {
    issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields
    if(issue.issuelinks.find {it.type.name == "Developer Escalations"}.inwardIssue.fields.status.name == "New") {
        def liveTicket = issue.issuelinks.find {it.inwardIssue.fields.summary =~ "Live"}.inwardIssue.key
        def escalation = issue.issuelinks.find {it.type.name == "Developer Escalations"}.inwardIssue.key
        
        def pending = get("/rest/api/2/issue/${liveTicket}/transitions").header('Content-Type','application/json').asObject(Map).getBody().transitions.find {it.to.name == "Pending"}.id
        String pendingBody = """ {"transition": {"id": "${pending}"},"fields": {"customfield_10032": {"id": "13187"}}} """
        def pendingIssue = post("/rest/api/2/issue/${liveTicket}/transitions").header('Content-Type', 'application/json').body(pendingBody).asString()
        println "${pendingIssue.status}: ${pendingIssue.body}"

        bodyData = """{"inwardIssue": {"key": "${escalation}"},"outwardIssue": {"key": "${liveTicket}"},"type": {"name": "Blocks"}}"""
        linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
        println "${linkIssues.status}: ${linkIssues.body}"

    }
} catch(Exception e) {
    logger.error("Could not set ticket to Pending.", e)
}
