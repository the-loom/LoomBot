package io.wecode.loom

//import braid.grader.Score

class JUnitParser {

    def parse(def xml) {

        def myXml = new XmlParser().parse(xml)

        def issues = []

        myXml.testcase.each { test ->

            test.error.each {
                issues << new JUnitIssue(
                        classname: test.attributes()['classname'],
                        test: test.attributes()['name'],
                        type: 'error',
                        message: it.attributes()['message'],
                        details: it.value()[0]
                )
            }

            test.failure.each {
                issues << new JUnitIssue(
                        classname: test.attributes()['classname'],
                        test: test.attributes()['name'],
                        type: 'failure',
                        message: it.attributes()['message'],
                        details: it.value()[0]
                )
            }

        }

        //def totalTests = myXml.attributes()["tests"] as Integer
        //def totalFailures = myXml.attributes()["failures"] as Integer
        //def totalErrors = myXml.attributes()["errors"] as Integer

        //def score = new Score(totalTests - (totalFailures+totalErrors), totalTests)
        //issues.put('score', score.normalize(8))


        return issues
    }
}