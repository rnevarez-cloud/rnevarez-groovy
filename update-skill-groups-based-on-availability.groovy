//Title: Update Skill Groups based on On-call Agent User Group
//Created By: Ricardo Nevarez 2022-12-02
//Description: Updates skill group membership based on agents that are currently on-call

import java.net.URLEncoder
import groovy.json.JsonSlurper

def slurper = new groovy.json.JsonSlurper()

def insQuery(x) { 
    get("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/iql/objects")
                .header("Accept", "application/json")
                .header("Authorization", "Basic ${INSIGHT}")
                .queryString('iql', x)
                .asObject(Map)
                .getBody()
}

def jiraUserQuery(x) {
    get("/rest/api/3/user/groups?accountId=${x}")
            .header("Accept", "*/*")
            .header("Authorization", "Basic ${INSIGHT}")
            .asString()
            .getBody()
}

def insUpdate(x,y) { 
    put("https://api.atlassian.com/jsm/insight/workspace/0d01a5a9-28e7-4069-b8be-0bbccfd5cd1d/v1/object/${x}")
        .header("Accept", "application/json")
        .header("Content-Type","application/json")
        .header("Authorization", "Basic ${INSIGHT}")
        .body(y)
        .asObject(Map)
        .getBody()
}

def currentOnCall = get("https://api.opsgenie.com/v2/schedules/CO-GOLD/on-calls?scheduleIdentifierType=name&flat=true").header("Accept", "application/json").header("Authorization", "GenieKey ${GENIEKEY}").asObject(Map).getBody()

def onCallRotations = get("https://api.opsgenie.com/v2/schedules/CO-GOLD?identifierType=name").header("Accept", "application/json").header("Authorization", "GenieKey ${GENIEKEY}").asObject(Map).getBody()

def agentArray = []

for (r in onCallRotations.data.rotations) {
    if(r.name == "Weekend-On-Call") {
        continue;
    } else {
        agentArray.add(r.participants.username.get(0))
    }
}

agentArray = agentArray.unique()

def onCallAgents = currentOnCall.data.onCallRecipients.toString().replace("[","").replace("]","")

def offCallAgents = []

for(a in agentArray) {
    if(!(onCallAgents.contains(a))) {
        offCallAgents.add(a)
    }
}

offCallAgents = offCallAgents.toString().replace("[","").replace("]","")

def iql = """ "Email" IN (${offCallAgents}) """

def associates = insQuery(iql)

def jiraUserArray = []

for(a in associates.objectEntries) {
    println "********************************************"
    println "***Checking ${a.label}'s resource record.***"

    def jiraUser = a.attributes.find {it.objectTypeAttributeId == "2444"}.objectAttributeValues.user.key.get(0)
    
    iql = """ "objectType" = "Resource" AND "Name" = "${a.label}" AND "Skills" IS NOT EMPTY """
    
    def resource = insQuery(iql)
    
    if(resource.objectEntries.isEmpty()) {
        println "***Resource not found. Either no skills are associated or this user is inactive/out of office. Skipping.***"
        continue;
    }
    
    def assetId = resource.objectEntries.id.get(0)
    
    def markActive = """ {
      "attributes": [
        {
          "objectTypeAttributeId": "2169",
          "objectAttributeValues": [
            {
              "value": "Out of office"
            }
          ]
        }
      ]
    } """

    println "********************************************"
    println """***Marking ${a.label} as "Out of office".***"""

    
    insUpdate(assetId,markActive)
    
    continue;
}

println "********************************************"
println "***ADDING ONLINE USERS TO SKILL GROUPS***"
println "********************************************"


iql = """ "Email" IN (${onCallAgents}) """
associates = insQuery(iql)
jiraUserArray = []

for(a in associates.objectEntries) {
    println "********************************************"
    println "***Checking ${a.label}'s resource record.***"

    def jiraUser = a.attributes.find {it.objectTypeAttributeId == "2444"}.objectAttributeValues.user.key.get(0)
    
    iql = """ "objectType" = "Resource" AND "Name" = "${a.label}" AND "Skills" IS NOT EMPTY """
    
    def resource = insQuery(iql)
    
    if(resource.objectEntries.isEmpty()) {
        println "***Resource not found. Either no skills are associated or this user is active. Skipping.***"
        continue;
    }
    
    def assetId = resource.objectEntries.id.get(0)
    
    def markActive = """ {
      "attributes": [
        {
          "objectTypeAttributeId": "2169",
          "objectAttributeValues": [
            {
              "value": "Active"
            }
          ]
        }
      ]
    } """

    println "********************************************"
    println """***Marking ${a.label} as "Active".***"""

    
    insUpdate(assetId,markActive)
    
    continue;
}
