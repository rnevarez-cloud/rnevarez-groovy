def jqlQuery = """ filter = open-issues order by created ASC """

def result = get('/rest/api/2/search')
    .queryString('jql', jqlQuery)
    .queryString('maxResults',"1000")
    .queryString('fields','key')
    .header('Content-Type', 'application/json')
    .asObject(Map)
    .getBody()

if(!(result.issues.isEmpty())) {
    for(r in result.issues) {
        int waitFor = 10;
        Thread.sleep(waitFor * 1000);

        def transition = get("/rest/api/2/issue/${r.key}/transitions").header('Content-Type','application/json').asObject(Map).getBody().transitions.find {it.to.name == "Transferred"}.id
        String transitionBody = """ {"transition": {"id": "${transition}"}} """
        def transitionIssue = post("/rest/api/2/issue/${r.key}/transitions").header('Content-Type', 'application/json').body(transitionBody).asString()
        println "${transitionIssue.status}: ${transitionIssue.body}"
    }
}
