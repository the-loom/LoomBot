package io.wecode.loom

class CheckstyleParser {

    List parse(final def originalXML) {

        def xml = new XmlParser().parse(originalXML)

        def incidencias = [] as List
        xml.file.each { aFile ->
            aFile.error.each {
                incidencias << new CheckstyleIssue(
                        file: aFile.attributes()['name'][aFile.attributes()['name'].lastIndexOf('src/')..-1],
                        line: it.attributes()['line'] as Integer,
                        column: it.attributes()['column'] as Integer,
                        severity: it.attributes()['severity'],
                        message: it.attributes()['message'])
            }
        }

        return incidencias
    }

}