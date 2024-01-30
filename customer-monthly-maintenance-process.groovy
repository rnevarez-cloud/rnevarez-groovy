import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.text.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;

def assetObject(x) {
    """{"workspaceId": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d","id": "0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d:${x}","objectId": "${x}"}"""
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

//------------------------------------------------------------
// Create parent ticket for the month
//------------------------------------------------------------

def now = LocalDateTime.now().format("MMMM yyyy").toString()

String bodyData = """ {
    "fields": {
        "project": {"id": "10023"},
        "issuetype": {"id": "10045"},
        "customfield_10010": "335",
        "assignee": {"id": "607dc830e3b5980068627a13"},
        "summary":"${now} - Customer Maintenance Window",
        "customfield_10101": [${assetObject(58158)}]
    }
} """

def parentIssue = post("/rest/api/2/issue").header('Content-Type','application/json').body(bodyData).asObject(Map)
if (parentIssue.status == 201) {println "Success: ${parentIssue.status}: ${parentIssue.body}"} else {println "${parentIssue.status}: ${parentIssue.body}"}

def issueKey = parentIssue.body.key

//------------------------------------------------------------
// Transition parent issue to "Review"
//------------------------------------------------------------

def transition = """{"transition": {"id": "9382"}}"""
def result = post("/rest/api/2/issue/${issueKey}/transitions").header('Content-Type', 'application/json').body(transition).asString()
if (result.status == 204) {println 'Success'} else {println "${result.status}: ${result.body}"}

//------------------------------------------------------------
// Query Jira Assets for all Customer Maintenance Windows
//------------------------------------------------------------

def aql = """ objectType = "Tasks" AND "Task Type" = "Customer Monthly Maintenance" ORDER BY Order ASC """

def maintenanceWindows = insQuery(aql).objectEntries

for(m in maintenanceWindows) {
    String dayOfWeek = null
    def day = null
    def hour = null
    def minute = null
    def timing = null
    def length = null
    LocalDateTime startDateTime = null
    
    if((m.attributes.find {it.objectTypeAttributeId == '4058'}) != null) {
        //------------------------------------------------------------
        // Identify maintenance window timing
        //------------------------------------------------------------
        
        timing = m.attributes.find {it.objectTypeAttributeId == '4058'}.objectAttributeValues.value.get(0)
        
        def dayOfWeekPattern = ~/((Mon|Tues|Wed(nes)?|Thur(s)?|Fri|Sat(ur)?|Sun)(day)?)/
        def timingPattern = ~/..:../
        def dayPattern = ~/(.*\d)(?:st|[nr]d|th)/
        
        try {
            dayOfWeek = (timing =~ dayOfWeekPattern)[0][1].toString()
            dayOfWeek = dayOfWeek.toUpperCase()
        }  catch(Exception) {
            dayOfWeek = null
        }
        
        day = (timing =~ dayPattern)[0][1].toString()
        timing = (timing =~ timingPattern)[0].toString()
        hour = timing.split(":").getAt(0).toString()
        minute = timing.split(":").getAt(1).toString()
    }
    
    if((m.attributes.find {it.objectTypeAttributeId == '4059'}) != null) {
        length = m.attributes.find {it.objectTypeAttributeId == '4059'}.objectAttributeValues.value.get(0)
        
        def lengthPattern = ~/^[1-9]/
        length = (length =~ lengthPattern)[0].toString()
    }    

    day = Integer.parseInt(day)
    hour = Integer.parseInt(hour)
    minute = Integer.parseInt(minute)
    length = Integer.parseInt(length)
    
    if(dayOfWeek == null) {
        startDateTime = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withDayOfMonth(day);
    } else {
        startDateTime = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).with(TemporalAdjusters.dayOfWeekInMonth(day, DayOfWeek.(dayOfWeek)));
    }
    
    LocalDateTime endDateTime = startDateTime.plusHours(length)

    ZonedDateTime startDateTimeEST = startDateTime.atZone(ZoneId.of("America/New_York"))
    ZonedDateTime endDateTimeEST = endDateTime.atZone(ZoneId.of("America/New_York"))
    
    String startDateTimeString = startDateTimeEST.format("yyyy-MM-dd'T'HH:mm:ss.SSSXX")
    String endDateTimeString = endDateTimeEST.format("yyyy-MM-dd'T'HH:mm:ss.SSSXX")
    
    println startDateTimeString
    println endDateTimeString
    
    //------------------------------------------------------------
    // Identify the rest of the issue fields (summary, description, etc.)
    //------------------------------------------------------------
    
    def summary = null
    def description = null
    def assignee = null
    def teamResponsibleId = null
    def organizationId = null
    def devicesList = []

    if((m.attributes.find {it.objectTypeAttributeId == '4029'}) != null) {
        summary = m.attributes.find {it.objectTypeAttributeId == '4029'}.objectAttributeValues.value.get(0)
    }
    
    if((m.attributes.find {it.objectTypeAttributeId == '4028'}) != null) {
        description = m.attributes.find {it.objectTypeAttributeId == '4028'}.objectAttributeValues.value.get(0)
    }    

    if((m.attributes.find {it.objectTypeAttributeId == '4032'}) != null) {
        assignee = m.attributes.find {it.objectTypeAttributeId == '4032'}.objectAttributeValues.searchValue.get(0)
        assignee = """{"id": "${assignee}"}"""
    }
    
    if((m.attributes.find {it.objectTypeAttributeId == '4030'}) != null) {
        teamResponsibleId = m.attributes.find {it.objectTypeAttributeId == '4030'}.objectAttributeValues.referencedObject.id.get(0)
        teamResponsibleId = """[${assetObject(teamResponsibleId)}]"""
    }

    if((m.attributes.find {it.objectTypeAttributeId == '4057'}) != null) {
        organizationId = m.attributes.find {it.objectTypeAttributeId == '4057'}.objectAttributeValues.referencedObject.id.get(0)
        organizationId = """[${assetObject(organizationId)}]"""
    }

    if((m.attributes.find {it.objectTypeAttributeId == '4057'}) != null) {
        def devices = m.attributes.find {it.objectTypeAttributeId == '4060'}.objectAttributeValues
        for(d in devices) {
            devicesList.add(assetObject(d.referencedObject.id))
        }
    } else {
        devicesList = null
    }

    bodyData = """ {
        "fields": {
            "project": {"id": "10023"},
            "issuetype": {"id": "10045"},
            "customfield_10010":"335",
            "assignee": ${assignee},
            "summary":"${summary}",
            "description":"${description}",
            "customfield_10101": ${teamResponsibleId},
            "customfield_10078": ${organizationId},
            "customfield_10083": ${devicesList},
            "customfield_10043": "${startDateTimeString}",
            "customfield_10044": "${endDateTimeString}"
        }
    } """
    
    println bodyData

    def issue = post("/rest/api/2/issue").header('Content-Type','application/json').body(bodyData).asObject(Map)
    if (issue.status == 201) {println "Success: ${issue.status}: ${issue.body}"} else {println "${issue.status}: ${issue.body}"}
    
    //------------------------------------------------------------
    // Link scheduled maintenance ticket to parent ticket
    //------------------------------------------------------------
        
    bodyData = """{
                "inwardIssue": {
                    "key": "${issueKey}"
                    },
                "outwardIssue": {
                    "key": "${issue.body.key}"
                    },
                "type": {
                    "name": "Relates"
                    }
                }"""
           
    def linkIssues = post("/rest/api/3/issueLink").header('Content-Type', 'application/json').body(bodyData).asString()
    if (linkIssues.status == 201) {println "Success"} else {println "${linkIssues.status}: ${linkIssues.body}"}
    
    //------------------------------------------------------------
    // Transition scheduled maintenance issue to "Review"
    //------------------------------------------------------------
    
    transition = """{"transition": {"id": "9382"}}"""
    
    result = post("/rest/api/2/issue/${issue.body.key}/transitions").header('Content-Type', 'application/json').body(transition).asString()
    if (result.status == 204) {println 'Success'} else {println "${result.status}: ${result.body}"}
    
    //------------------------------------------------------------
    // Add watchers to maintenance ticket
    //------------------------------------------------------------
        
    if((m.attributes.find {it.objectTypeAttributeId == '4061'}) != null) {
        def watchers = m.attributes.find {it.objectTypeAttributeId == '4061'}.objectAttributeValues.searchValue
        for(w in watchers) {
            def watcher = """{"id": "${w}"}"""
            result = post("/rest/api/2/issue/${issue.body.key}/watchers").header('Content-Type', 'application/json').body(watcher).asString()
            if (result.status == 204) {println 'Success'} else {println "${result.status}: ${result.body}"}
            }
    }
    
    devicesList.clear()
}
