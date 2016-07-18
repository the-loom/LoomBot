package io.wecode.loom

import groovy.io.FileType
import groovy.sql.Sql
import groovy.util.logging.Log4j
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import org.apache.commons.io.FileUtils

// 0. en un while eterno, o ejecutandose cada X minutos

// por cada tarea en el json de tareas

@Log4j
class GraderScript {

    def execute() {
        while (true) {

            def sql = Sql.newInstance('jdbc:sqlite:loom.sqlite', 'org.sqlite.JDBC')

            sql.eachRow("select * from assignments") { repoInDB ->

                log.info "procesando repo: ${repoInDB.full_name}"
                def repo = GithubAPI.info(repoInDB.full_name)

                GithubAPI.forks(repoInDB.full_name).each { fork ->
                    log.info "procesando fork: ${fork.full_name}"

                    def forksData = sql.dataSet("forks")
                    if (fork.size < 512) {

                        // si no existe
                        if (sql.rows("select * from forks where full_name = ?", [fork.full_name]).size == 0) {
                            log.info "dando de alta el fork"
                            forksData.add(
                                    assignment_full_name: repoInDB.full_name,
                                    user: fork.owner.login,
                                    full_name: fork.full_name,
                                    repo_url: fork.git_url,
                                    updated_at: new Date().parse("YYYY-MM-dd'T'hh:mm:ss'Z'", fork.updated_at),
                                    tested_at: null
                            )
                        } else {
                            log.info "se vuelve a corregir fork existente"
                            sql.executeUpdate("update forks set updated_at = ? where full_name = ?",
                                    [
                                            new Date().parse("YYYY-MM-dd'T'hh:mm:ss'Z'", fork.updated_at),
                                            fork.full_name,
                                    ])
                        }

                        // si es mas nuevo, o si tiene una actualizacion, correr tests
                        def forkInDB = sql.firstRow("select * from forks where full_name = ?", [fork.full_name])
                        if (true || !forkInDB.tested_at || forkInDB.updated_at > forkInDB.tested_at) {
                            log.info "ejecutando tests..."

                            test(repoInDB, forkInDB, repo, fork)

                            sql.executeUpdate("update forks set tested_at = ? where full_name = ?",
                                    [new Date(), fork.full_name])
                            log.info "ejecucion terminada en ${new Date()}"
                        } else {
                            log.info "no es necesario correr los tests"
                        }

                    } else {
                        log.info "el fork [${fork.full_name}] excede el tamaño maximo permitido"
                    }
                }

            }

            def pause = 10000
            log.info "en pausa por ${pause / 1000} segundos... zZzZ"
            sleep(pause)

        }


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
            log.info 'No se ha podido hacer clone de la solución'
            def report = armarReporteFallido(repoo, forko, new Score(value: 0, total: 10), 'No se ha podido hacer clone de la solución')
            enviarReporte(repoInDB, report)
            borrarArchivos(deletables)
            return
        }

        // mergeamos con los tests

        try {
            // reemplazamos el codigo del repo de referencia con el codigo del fork
            (new File("./${canonicalCloneDir}/src/main/java/")).deleteDir()
            FileUtils.copyDirectory(new File("./${solutionCloneDir}/src/main/java/"), new File("${canonicalCloneDir}/src/main/java/"))
        } catch (FileNotFoundException fe) {
            log.info 'Se envió un repo no válido'
            def report = armarReporteFallido(repoo, forko, new Score(value: 0, total: 10), 'Se envió un repo no válido')
            enviarReporte(repoInDB, report)
            borrarArchivos(deletables)
            return
        }

        // compilamos
        def compileMerge = "gradle -p ${canonicalCloneDir} compileJava".execute()
        compileMerge.waitFor()

        // No compila el código
        if (compileMerge.exitValue() == 1) {
            def report = armarReporteFallido(repoo, forko, new Score(value: 0, total: 10), compileMerge.in.text)
            enviarReporte(repoInDB, report)
            borrarArchivos(deletables)
            return
        }

        // Ejecutamos la tarea mvn test
        def testsExecution = "gradle -p ${canonicalCloneDir}".execute()
        testsExecution.waitFor()

        // Tomamos el reporte y lo mostramos
        try {

            def junitParser = new JUnitParser()
            def junitIssues

            def myTestsFile = new File("${canonicalCloneDir}/build/test-results")
            myTestsFile.eachFile(FileType.FILES) {
                junitIssues = junitParser.parse(it)
                // por el momento todos los tests se encuentran en el mismo archivo
                // pero no sabemos el nombre exacto
            }
            def junitScore = new Score(value: junitIssues["tests"] - junitIssues["issues"].size(),
                    total: junitIssues["tests"]).normalize(10)


            def checkstyleParser = new CheckstyleParser()
            def myCheckstyleFile = new File("${canonicalCloneDir}/build/reports/checkstyle/main.xml")
            def checkstyleIssues = checkstyleParser.parse(myCheckstyleFile)

            def checkstyleScore = new Score(value: Math.max(20 - checkstyleIssues.size(), 0), total: 20).normalize(10)

            def finalScore = junitScore + checkstyleScore

            def resultados = [
                    [
                            type  : 'junit',
                            score : junitScore,
                            issues: junitIssues.issues.collect { it.toMap() }
                    ],
                    [
                            type  : 'checkstyle',
                            score : checkstyleScore,
                            issues: checkstyleIssues.collect { it.toMap() }
                    ]

            ]

            def report = armarReporte(repoo, forko, finalScore, resultados)
            enviarReporte(repoInDB, report)
            borrarArchivos(deletables)

        } catch (FileNotFoundException e) {
            log.info 'Se ha enviado una tarea que no corresponde con la interfaz provista'
            def report = armarReporteFallido(repoo, forko, new Score(value: 0, total: 10), 'Se ha enviado una tarea que no corresponde con la interfaz provista')
            enviarReporte(repoInDB, report)
            borrarArchivos(deletables)
            return
        } catch (Exception e) {
            log.info e.stackTrace
            borrarArchivos(deletables)
        }

        borrarArchivos(deletables)

    }

    private armarReporteFallido(repoo, forko, finalScore, texto) {
        def report = [
                parent  : new RepoDTO(repo: repoo).toMap(),
                fork    : new RepoDTO(repo: forko).toMap(),
                test_run: [
                        score  : finalScore,
                        details: texto
                ]
        ]
        report
    }

    private armarReporte(repoo, forko, finalScore, resultados) {
        def report = [
                parent  : new RepoDTO(repo: repoo).toMap(),
                fork    : new RepoDTO(repo: forko).toMap(),
                test_run: [
                        score  : finalScore,
                        results: resultados
                ]
        ]
        report
    }

    private void enviarReporte(repoInDB, report) {
        def http = new HTTPBuilder("http://localhost:3000/") // TODO: cambiar URL

        http.request(Method.POST, ContentType.JSON) {
            uri.path = repoInDB.full_name
            headers.'x-api-key' = 'un token bonito' // TODO: tomarlo del entorno
            body = report

            response.success = { resp, json ->
                log.info "Transferencia de correccion exitosa: ${resp.status}"
            }

            response.failure = { resp, json ->
                log.info "Transferencia de correccion fallida: ${resp.status}"
            }
        }
    }

    private void borrarArchivos(deletables) {
        log.info "Eliminando archivos temporales"
        deletables.each { deletable ->
            deletable.deleteDir()
        }
    }
}

GraderScript g = new GraderScript()
g.execute()