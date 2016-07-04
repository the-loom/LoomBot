package io.wecode.loom

import groovy.io.FileType
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.apache.commons.io.FileUtils

def jsonSlurper = new JsonSlurper()

// 0. en un while eterno, o ejecutandose cada X minutos

// por cada tarea en el json de tareas

def sql = Sql.newInstance('jdbc:sqlite:loom.sqlite', 'org.sqlite.JDBC')

sql.eachRow("select * from assignments") { repo ->

    // docs: https://developer.github.com/v3/repos/forks/
    def mazinger = new URL("https://api.github.com/repos/${repo.full_name}/forks").getText()
    def forks = jsonSlurper.parseText(mazinger)

    forks.each { fork ->

        def forksData = sql.dataSet("forks")
        if (fork.size < 512) {

            // si no existe
            if (sql.rows("select * from forks where full_name = ?", [fork.full_name]).size == 0) {
                forksData.add(
                        assignment_full_name: repo.full_name,
                        user: fork.owner.login,
                        full_name: fork.full_name,
                        repo_url: fork.git_url,
                        updated_at: new Date().parse("YYYY-MM-dd'T'hh:mm:ss'Z'", fork.updated_at),
                        tested_at: null
                )
            } else {
                println "YA EXISTE!"
                sql.executeUpdate("update forks set updated_at = ? where full_name = ?",
                        [
                                new Date().parse("YYYY-MM-dd'T'hh:mm:ss'Z'", fork.updated_at),
                                fork.full_name,
                        ])
            }

            // si es mas nuevo, o si tiene una actualizacion, correr tests
            def forkInDB = sql.firstRow("select * from forks where full_name = ?", [fork.full_name])
            if (true || !forkInDB.tested_at || forkInDB.updated_at > forkInDB.tested_at) {
                println "HAY QUE CORRER TESTS"

                def reporte = test(repo, forkInDB)

                // pegarle por POST a la app, para guardar el resultado del test
                // Enviar TODOS los datos (repo, fork y reporte)

                // se marca la hora de la ejecucion
                sql.executeUpdate("update forks set tested_at = ? where full_name = ?",
                        [new Date(), fork.full_name])
            } else {
                println "NO HAY QUE CORRER NADA. FIN!"
            }

        } else {

            println "TOO BIG REPO!"

        }
    }

}

def test(def repo, def fork) {

    def deletables = []

    def canonicalCloneDir = "${repo.user}-${repo.repo}"
    def canonicalClone = "git clone --depth 1 -b resolucion ${repo.tests_url} ${canonicalCloneDir}".execute()
    canonicalClone.waitFor()
    deletables << new File("./${repo.user}-${repo.repo}")

    // clonamos
    def solutionCloneDir = "${fork.user}-${repo.repo}-${new Date().time}"
    def solutionClone = "git clone --depth 1 ${fork.repo_url} ${solutionCloneDir}".execute()
    solutionClone.waitFor()
    deletables << new File("./${solutionCloneDir}")

    if (solutionClone.exitValue() == 1) {
        println "FALLA CLONE DE LA SOLUCION"
        System.exit(1)
    }

    // mergeamos con los tests

    try {
        // reemplazamos el codigo del repo de referencia con el codigo del fork
        (new File("./${canonicalCloneDir}/src/main/java/")).deleteDir()
        FileUtils.copyDirectory(new File("./${solutionCloneDir}/src/main/java/"), new File("${canonicalCloneDir}/src/main/java/"))
    } catch (FileNotFoundException fe) {
        // Se envió un repo no válido
        println "Se envió un repo no válido"
        System.exit(1)
        //completeMapWithQualifications(correctionsMap, new Score(0, 10), 'El repositorio suministrado no es válido')
        //return jsonify(correctionsMap)
    }

    // compilamos
    def compileMerge = "gradle -p ${canonicalCloneDir} compileJava".execute()
    compileMerge.waitFor()

    // No compila el código
    if (compileMerge.exitValue() == 1) {
        println "No compila el código"
        System.exit(1)
        // completeMapWithQualifications(correctionsMap, new Score(0, 10), p.in.text)
        // return jsonify(correctionsMap)
    }

    // Ejecutamos la tarea mvn test
    def testsExecution = "gradle -p ${canonicalCloneDir}".execute()
    testsExecution.waitFor()

    // Tomamos el reporte y lo mostramos
    try {

        def junitParser = new JUnitParser()
        def checkstyleParser = new CheckstyleParser()

        def junitIssues

        def myTestsFile = new File("${canonicalCloneDir}/build/test-results")
        myTestsFile.eachFile(FileType.FILES) {
            junitIssues = junitParser.parse(it)
            // por el momento todos los tests se encuentran en el mismo archivo
            // pero no sabemos el nombre exacto
        }
        //def notaFuncional = junitIssuesMap["score"]

        println "junit"
        println new Score(value: junitIssues["tests"] - junitIssues["issues"].size(),
                total: junitIssues["tests"]).normalize(10)

        def myCheckstyleFile = new File("${canonicalCloneDir}/build/reports/checkstyle/main.xml")
        def checkstyleIssues = checkstyleParser.parse(myCheckstyleFile)

        println "checkstyle"
        println new Score(value: Math.max(20 - checkstyleIssues.size(), 0), total: 20).normalize(10)
        //println checkstyleIssues


        // armar reporte

        // devolverlo

        //def notaEstilo = new Score((20 - checkstyleIssuesList.size())>0?((20 - checkstyleIssuesList.size())/10):0, 2)

        //def nota = notaFuncional + notaEstilo

/*        def reporte = '=== Reporte completo ===\n'
        reporte += "Nota general: ${nota.normalize()}\n"
        reporte += "\tNota funcional: ${notaFuncional}\n"
        reporte += "\tNota de estilo: ${notaEstilo}\n"
        reporte += '\n\n******************'
        reporte += '\nAnálisis funcional\n'
        reporte += '******************\n'
        reporte += reportarIssuesJUnit(junitIssuesMap)
        reporte += '\n\n******************'
        reporte += "\nAnálisis de estilo\n"
        reporte += '******************\n'
        reporte += reportarIssuesCheckstyle(checkstyleIssuesList)

        completeMapWithQualifications(correctionsMap, nota, reporte)*/

    } catch (FileNotFoundException e) {
        e.printStackTrace()
        println 'Se ha enviado una tarea que no corresponde con la interfaz provista'
    }

    deletables.each { deletable ->
        deletable.deleteDir()
    }

}