package io.wecode.loom

import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method

class GithubAPI {

    // https://code.google.com/archive/p/jdbm2/ <-- might be useful
    static cache = [:]
    static etags = [:]

    static forks(def repo) {
        // docs: https://developer.github.com/v3/repos/forks/
        getJson("/repos/${repo}/forks")
    }

    static getJson(def path) {

        def http = new HTTPBuilder()
        http.request('https://api.github.com', Method.GET, ContentType.TEXT) { req ->
            uri.path = path
            uri.query = [
                    client_id    : System.getenv('GITHUB_ID'),
                    client_secret: System.getenv('GITHUB_SECRET')
            ]
            headers.'User-Agent' = 'Loom Grader'
            headers.Accept = 'application/json'
            headers.'If-None-Match' = etags[path]

            response.success = { resp, reader ->

                // cached!
                if (resp.statusLine.statusCode != 304) {
                    cache[path] = reader.text
                    etags[path] = resp.headers.'ETag'
                }

                new JsonSlurper().parseText(cache[path])
            }
        }
    }

}
