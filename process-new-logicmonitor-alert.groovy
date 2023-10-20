// ------------------------------------------------------------------------------------------------------------------------
// Title: Process new LogicMonitor Alert	
// Author: Ricardo Nevarez Jr - 2023/10/17
// Description: Obtains LogicMonitor alert information and updates corresponding Jira ticket with relevant information
// ------------------------------------------------------------------------------------------------------------------------
// Condition: issue.issueType.id == 10066
// ------------------------------------------------------------------------------------------------------------------------
// Fields updated:
// * Request Type (customfield_10010) - LogicMonitor Alert (450)
// * Customer (customfield_10078)
// * Device (customfield_10083)
// * Team Responsible (customfield_10101) - Gold Team (58158)
// * Priority (priority)
// ------------------------------------------------------------------------------------------------------------------------

import java.time.*
import java.text.*
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;

def assetObject(x) {
    """{
            "workspaceId": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d",
            "id": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d:${x}",
            "objectId": "${x}"
    }"""
}

def checkVar(x) {
    //------------------------------
    // Function to check if variable is null
    //------------------------------
    if(x == null) {
        throw new Exception("No value for variable was found.")
    }
}

def insQuery(x) { 
    //------------------------------
    // Function to query Jira Assets
    //------------------------------
    get("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/iql/objects")
                .header("Accept", "application/json")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString('iql', x)
                .queryString('resultPerPage','1000')
                .asObject(Map)
                .getBody()
}

def getOGAlert(x) { 
    //------------------------------------------------------------
    // Function to obtain Opsgenie alert information
    //------------------------------------------------------------
    get("https://api.opsgenie.com/v2/alerts?query=IssueKey%3A${x}")
                .header("Accept", "application/json")
                .header("Authorization", "GenieKey ${GENIEKEY}")
                .asObject(Map)
                .getBody()
}

def getLMAlert(x) {
    //--------------------------------------------------
    // Function to obtain LogicMonitor alert information
    //--------------------------------------------------

    def accessId = ACCESSID;
    def accessKey = ACCESSKEY;
    def account = 'CONTOSO';

    resourcePath = "/alert/alerts"
    url = "https://" + account + ".logicmonitor.com" + "/santaba/rest" + resourcePath;

    epoch = System.currentTimeMillis();

    //Calculate signature
    requestVars = "GET" + epoch + resourcePath;
    hmac = Mac.getInstance("HmacSHA256");
    secret = new SecretKeySpec(accessKey.getBytes(), "HmacSHA256");
    hmac.init(secret);
    hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
    signature = hmac_signed.bytes.encodeBase64();

    lmAuth = "LMv1 " + accessId + ":" + signature + ":" + epoch

    result = get(url)
        .header("Content-Type", "application/json")
        .header("Authorization", "${lmAuth}")
        .queryString("filter","internalId:${x}")
        .asObject(Map)
        .getBody()
}

//Priority mapping Opsgenie and Jira priorities
def priorityMap = [
    [opsgeniePriority: "P3", jiraPriority: "3"],
    [opsgeniePriority: "P2", jiraPriority: "2"],
    [opsgeniePriority: "P1", jiraPriority: "1"],
    ]

int waitFor = 10;
Thread.sleep(waitFor * 1000);

def issueKey = issue.key

//------------------------------
// Obtaining Jira issue information
//------------------------------
def issue = get("/rest/api/2/issue/${issueKey}").header('Content-Type','application/json').asObject(Map).getBody().fields

//------------------------------------------------------------------------------------------------------------------------
// Obtaining alert information (Opsgenie alert, LogicMonitor LMD number, LogicMonitor alert information)
//------------------------------------------------------------------------------------------------------------------------

def opsgenieAlert = getOGAlert(issueKey).data
checkVar(opsgenieAlert)

// Priority from Opsgenie - referencing priorityMap
def priorityId = priorityMap.find {it.opsgeniePriority == opsgenieAlert.priority[0]}.jiraPriority

// Posting Request Type and Priority data to Jira issue
String issueData = """ {
        "fields": {
            "customfield_10010":"450",
			      "priority":{"id":"${priorityId}"}
        }
    } """

def result = put("/rest/api/2/issue/${issueKey}") 
    .header('Content-Type', 'application/json')
    .body(issueData)
    .asString()
    
if (result.status == 204) { 
    println 'Success'
} else {
    println "${result.status}: ${result.body}"
}

def alertID = opsgenieAlert.alias[0]
checkVar(alertID)

def logicmonitorAlert = getLMAlert(alertID).data.items
checkVar(logicmonitorAlert)

//------------------------------------------------------------
// Obtaining Jira asset/field information
//------------------------------------------------------------

// Virtual Guest/Device asset object
def serverAsset = null

// If the alert is for a VM snapshot, then the script will grab the VM with the snapshot instead of the VCenter host
if(logicmonitorAlert.resourceTemplateName[0] == "VMware VM Snapshots") {
    def pattern = ~/VMSnapshots-(.*)/
    def snapshotServer = (logicmonitorAlert.instanceName[0].toString() =~ pattern)[0][1].toString()
    serverAsset = insQuery("""Name = ${snapshotServer}""").objectEntries[0]
} else {
    serverAsset = insQuery("""Hostname = ${logicmonitorAlert.monitorObjectName[0]}""").objectEntries[0]
}

checkVar(serverAsset)

def serverAssetId = serverAsset.id

// Customer asset object - obtained from serverAsset variable
def customerAssetId = null

try {
    customerAssetId = serverAsset.attributes.find {it.objectTypeAttributeId == "2793"}.objectAttributeValues.referencedObject.id[0]
} catch (Exception){
    def clientPattern = ~/CO(.*)-/
    def CONTOSOPattern = ~/contoso.local/
    def serverName = (serverAsset.attributes.find {it.objectTypeAttributeId == "2637"}.objectAttributeValues.value[0]).toString()
    
    if (serverName =~ clientPattern) {
        def customerCode = (serverName =~ clientPattern)[0][1].toString()
        customerAssetId = insQuery("objectType = Organization AND Code = ${customerCode}").objectEntries.id[0]
    } else if (serverName =~ CONTOSOPattern){
        customerAssetId = insQuery("objectType = Organization AND Name = CONTOSO").objectEntries.id[0]
    }
}

//------------------------------------------------------------
// Building and posting Jira field data JSON
//------------------------------------------------------------

String bodyData = """ {
        "fields": {
			"customfield_10078": [${assetObject(customerAssetId)}],
			"customfield_10083": [${assetObject(serverAssetId)}],
			"customfield_10101": [${assetObject(58158)}]
        }
    } """

result = put("/rest/api/2/issue/${issueKey}") 
    .header('Content-Type', 'application/json')
    .body(bodyData)
    .asString()
    
if (result.status == 204) { 
    println 'Success'
} else {
    println "${result.status}: ${result.body}"
}

//------------------------------------------------------------
// Transition issue to "Waiting for Agent"
//------------------------------------------------------------

def transition = """{
    "transition": { 
        "id": "981"
    }
}"""

result = post("/rest/api/2/issue/${issueKey}/transitions")
        .header('Content-Type', 'application/json')
        .body(transition)
        .asString()
    
if (result.status == 204) { 
    println 'Success'
} else {
    println "${result.status}: ${result.body}"
}
