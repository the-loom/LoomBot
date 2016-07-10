package io.wecode.loom

import groovy.io.FileType
import groovy.sql.Sql
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import org.apache.commons.io.FileUtils

// 0. en un while eterno, o ejecutandose cada X minutos

// por cada tarea en el json de tareas

while (true) {

    def sql = Sql.newInstance('jdbc:sqlite:loom.sqlite', 'org.sqlite.JDBC')

    sql.eachRow("select * from assignments") { repoInDB ->

        def repo = GithubAPI.info(repoInDB.full_name)

        GithubAPI.forks(repoInDB.full_name).each { fork ->

            def forksData = sql.dataSet("forks")
            if (fork.size < 512) {

                // si no existe
                if (sql.rows("select * from forks where full_name = ?", [fork.full_name]).size == 0) {
                    forksData.add(
                            assignment_full_name: repoInDB.full_name,
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

                    def reporte = test(repoInDB, forkInDB, repo, fork)

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

    println 'Durmamos 10 segunditos...'
    sleep(10000)

}

def test(def repoInDB, def forkInDB, def repoo, def forko) {

    def deletables = []

    def canonicalCloneDir = "${repoInDB.user}-${repoInDB.repo}"
    def canonicalClone = "git clone --depth 1 -b resolucion ${repoInDB.tests_url} ${canonicalCloneDir}".execute()
    canonicalClone.waitFor()
    deletables << new File("./${repoInDB.user}-${repoInDB.repo}")

    // clonamos
    def solutionCloneDir = "${forkInDB.user}-${repoInDB.repo}-${new Date().time}"
    def solutionClone = "git clone --depth 1 ${forkInDB.repo_url} ${solutionCloneDir}".execute()
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

        def junitScore = new Score(value: junitIssues["tests"] - junitIssues["issues"].size(),
                total: junitIssues["tests"]).normalize(10)

        def myCheckstyleFile = new File("${canonicalCloneDir}/build/reports/checkstyle/main.xml")
        def checkstyleIssues = checkstyleParser.parse(myCheckstyleFile)

        def checkstyleScore = new Score(value: Math.max(20 - checkstyleIssues.size(), 0), total: 20).normalize(10)

        def finalScore = junitScore + checkstyleScore

        def report = [
                parent  : new RepoDTO(repo: repoo).toMap(),
                fork    : new RepoDTO(repo: forko).toMap(),
                test_run: [
                        score  : finalScore,
                        results: [
                                [
                                        type      : 'junit',
                                        score     : junitScore,
                                        issues    : junitIssues.issues.collect { it.toMap() }
                                ],
                                [
                                        type      : 'checkstyle',
                                        score     : checkstyleScore,
                                        issues    : checkstyleIssues.collect { it.toMap() }
                                ]

                        ]
                ]
        ]

        // TODO: post de prueba!
        def http = new HTTPBuilder("http://localhost:3000/")

        //try {


            http.request(Method.POST, ContentType.JSON) {
                uri.path = repoInDB.full_name
                // TODO: agregar el AUTH_TOKEN en el header
                body = report

                response.success = { resp, json ->
                    println "Success! ${resp.status}"
                }

                response.failure = { resp, json ->
                    println "Request failed with status ${resp.status}"
                }
            }

        /*} catch(Exception e) {
            println e.message
            println "recuperemos esto..."
            sleep(30000)
        }*/


        // TODO: falta contemplar los casos que podrian tener error

    } catch (FileNotFoundException e) {
        e.printStackTrace()
        println 'Se ha enviado una tarea que no corresponde con la interfaz provista'
    }

    deletables.each { deletable ->
        deletable.deleteDir()
    }

}
