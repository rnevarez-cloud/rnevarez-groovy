import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;

// LOGICMONTIOR VARIABLES //

def accessId = ACCESSID;
def accessKey = ACCESSKEY;
def account = 'contoso';

//define SDT url
def resourcePath = "/sdt/sdts"
def url = "https://" + account + ".logicmonitor.com" + "/santaba/rest" + resourcePath;
def deviceName = []
def customers = []
def allServers = null
String bodyData = null

// JIRA VARIABLES //

def issueKey = issue.key

def issue = get("/rest/api/2/issue/${issueKey}")
    .header('Content-Type', 'application/json')
    .asObject(Map)
    .getBody()

def issueDesc = issue.fields.summary
def issueReporter = issue.fields.reporter.displayName

println issueKey
println issueDesc

// DOWNTIME SETUP //

///SDT Affected Device
def customFieldDevice = "customfield_10083"
def cfDevices = get("/rest/api/2/issue/${issueKey}?fields=${customFieldDevice}")
        .header('Content-Type', 'application/json')
        .asObject(Map)

def deviceId = cfDevices.body.fields[customFieldDevice].objectId
println deviceId

if (deviceId.isEmpty()) {
    allServers = 1
}

def customerId = issue.fields.customfield_10078.objectId

println customerId

for(customer in customerId) {

    insCustomer = get("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/object/${customer}")
        .header("Accept", "application/json")
        .header("Authorization", "Basic ${INSIGHT}")
        .asObject(Map)
        .getBody()
    
    for(atr in insCustomer.attributes) {
        if(atr.objectTypeAttribute.name == "Code") {
            customers.add(atr.objectAttributeValues.value.get(0))
        }
    }
}

/// SDT Start
def customFieldStart = "customfield_10043"
def cfStart = get("/rest/api/2/issue/${issueKey}?fields=${customFieldStart}")
        .header('Content-Type', 'application/json')
        .asObject(Map)

def sdtStart = cfStart.body.fields[customFieldStart]

/// SDT End
def customFieldEnd = "customfield_10044"
def cfEnd = get("/rest/api/2/issue/${issueKey}?fields=${customFieldEnd}")
        .header('Content-Type', 'application/json')
        .asObject(Map)

def sdtEnd = cfEnd.body.fields[customFieldEnd]

println "${sdtStart} - ${sdtEnd}"

// Convert Time to Epoch Time

def dateStrStart = "${sdtStart}"
def dateStart = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", dateStrStart)
println dateStart
def epochStart = dateStart.getTime()

def dateStrEnd = "${sdtEnd}"
def dateEnd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", dateStrEnd)
println dateEnd
def epochEnd = dateEnd.getTime()

println "allServers = ${allServers}"

if(allServers == 1) {

    for(client in customers) {
        resourcePath = "/device/groups"
        url = "https://" + account + ".logicmonitor.com" + "/santaba/rest" + resourcePath;

        epoch = System.currentTimeMillis();

        //calculate signature
        requestVars = "GET" + epoch + resourcePath;
        hmac = Mac.getInstance("HmacSHA256");
        secret = new SecretKeySpec(accessKey.getBytes(), "HmacSHA256");
        hmac.init(secret);
        hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
        signature = hmac_signed.bytes.encodeBase64();
        
        // HTTP Post

        lmAuth = "LMv1 " + accessId + ":" + signature + ":" + epoch

        result = get(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "${lmAuth}")
            .queryString("filter","name:Client-${client}")
            .asObject(Map)
            .getBody()

        def groupId = result.data.items.subGroups.get(0).id.get(0)

        resourcePath = "/device/groups/${groupId}/devices"
        url = "https://" + account + ".logicmonitor.com" + "/santaba/rest" + resourcePath;

        epoch = System.currentTimeMillis();

        //calculate signature
        requestVars = "GET" + epoch + resourcePath;
        hmac = Mac.getInstance("HmacSHA256");
        secret = new SecretKeySpec(accessKey.getBytes(), "HmacSHA256");
        hmac.init(secret);
        hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
        signature = hmac_signed.bytes.encodeBase64();
        
        // HTTP Post

        lmAuth = "LMv1 " + accessId + ":" + signature + ":" + epoch

        result = get(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "${lmAuth}")
            .asObject(Map)
            .getBody()

        def allServersList = result.data.items.displayName

        String sdtDetails = """{
                "sdtType":"1",
                "type":"DeviceGroupSDT",
                "deviceGroupId":"${groupId}",
                "startDateTime":"${epochStart}",
                "endDateTime":"${epochEnd}",
                "comment":"${issueKey} - ${issueDesc} - ${issueReporter} - https://contosocloud.atlassian.net/browse/${issueKey}"
            }"""

        println sdtDetails;

        // HTTP Post

        epoch = System.currentTimeMillis();

        //calculate signature
        resourcePath = "/sdt/sdts"
        url = "https://" + account + ".logicmonitor.com" + "/santaba/rest" + resourcePath;
        
        println url
        
        requestVars = "POST" + epoch + sdtDetails + resourcePath;
        hmac = Mac.getInstance("HmacSHA256");
        secret = new SecretKeySpec(accessKey.getBytes(), "HmacSHA256");
        hmac.init(secret);
        hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
        signature = hmac_signed.bytes.encodeBase64();
        
        // HTTP Post

        lmAuth = "LMv1 " + accessId + ":" + signature + ":" + epoch

        result = post(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "${lmAuth}")
            .body(sdtDetails)
            .asString()
        
        if (result.status == 204) { 
            println 'Success'
        } else {
            println "${result.status}: ${result.body}"
        }    
        
        bodyData = """{
            "body": "LogicMonitor downtime has been configured for all Client-${client} devices: ${allServersList}."
        }"""
    
        
        result = post("/rest/api/2/issue/${issueKey}/comment")
            .header('Content-Type', 'application/json')
            .body(bodyData)
            .asString()
        
        if (result.status == 204) { 
            println 'Success'
        } else {
            println "${result.status}: ${result.body}"
        }

    }

} else {

    for(dev in deviceId) {
        def device = get("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/object/${dev}")
            .header('Content-Type', 'application/json')
            .header('Accept', 'application/json')
            .header("Authorization", "Basic ${INSIGHT}")
            .asObject(Map)
            .getBody()
    
        for(atr in device.attributes) {
            if(atr.objectTypeAttributeId == "2637") {
                deviceName.add(atr.objectAttributeValues.value.get(0))
            }
        }
    }
    
    for (dev in deviceName) {
        String sdtDetails = """{
            "sdtType":"1",
            "type":"DeviceSDT",
            "deviceDisplayName":"${dev}",
            "startDateTime":"${epochStart}",
            "endDateTime":"${epochEnd}",
            "comment":"${issueKey} - ${issueDesc} - ${issueReporter} - https://contosocloud.atlassian.net/browse/${issueKey}"
        }"""

        //get current time
        epoch = System.currentTimeMillis();

        //calculate signature
        requestVars = "POST" + epoch + sdtDetails + resourcePath;
        hmac = Mac.getInstance("HmacSHA256");
        secret = new SecretKeySpec(accessKey.getBytes(), "HmacSHA256");
        hmac.init(secret);    
        hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
        signature = hmac_signed.bytes.encodeBase64();
    
        // HTTP Post

        lmAuth = "LMv1 " + accessId + ":" + signature + ":" + epoch

        result = post(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "${lmAuth}")
            .body(sdtDetails)
            .asString()
    
        if (result.status == 204) { 
            println 'Success'
        } else {
            println "${result.status}: ${result.body}"
        }    
    }

    bodyData = """{
        "body": "LogicMonitor downtime has been configured for the following devices: ${deviceName}."
    }"""

    result = post("/rest/api/2/issue/${issueKey}/comment")
        .header('Content-Type', 'application/json')
        .body(bodyData)
        .asString()
    
    if (result.status == 204) { 
        println 'Success'
    } else {
        println "${result.status}: ${result.body}"
    }

}

//Transition Issue

bodyData = """{
    "transition": { 
        "id": "871"
    }
}"""

result = post("/rest/api/2/issue/${issueKey}/transitions")
        .header('Content-Type', 'application/json')
        .body(bodyData)
        .asString()
    
if (result.status == 204) { 
    println 'Success'
} else {
    println "${result.status}: ${result.body}"
}

bodyData = """ {
        "fields": {
            "customfield_10032": {
                "id": "10134"
            }
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
