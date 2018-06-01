package io.wecode.loom

import groovy.io.FileType
import groovy.util.logging.Log4j
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import org.apache.commons.io.FileUtils
import org.apache.http.conn.HttpHostConnectException
import groovy.json.JsonSlurper
import groovy.json.StringEscapeUtils

@Log4j
class GraderScript {

    def execute() {

        // esto corta la ejecución si ya hay un grader funcionando en el sistema
        prevenirDobleEjecucion()
        PropertiesHolder.instance.load()

        def pause = 0;
        while (true) {

            log.info "Buscando repositorios pendientes"
            def pendingRepos = new JsonSlurper().parseText("${PropertiesHolder.instance.properties.DASHBOARD_HOST}/api/v1/repos/pending".toURL().
            getText(requestProperties: ['x-api-key': PropertiesHolder.instance.properties.GRADER_TOKEN]))

            if (pendingRepos.size() == 0) {
                pause = Math.min(5, (++pause))
                log.info "No se encontraron repos pendientes. En pausa por ${pause} minuto(s)... zZzZ"
                sleep(pause * 60 * 1000)
            } else {
                pause = 0
                def fork = pendingRepos[0]
                def repo = fork.parent

                log.info "Se corregirá el repo ${fork.git_url}."

                test(repo, fork)
            }
        }
    }

    private void prevenirDobleEjecucion() {
        final int PORT = 29432
        ServerSocket s
        try {
            s = new ServerSocket(PORT, 10, InetAddress.getLocalHost());
        } catch (UnknownHostException e) {
            // no debería suceder para localhost
        } catch (IOException e) {
            // si el demonio está ejecutándose, no se vuelve a ejecutar
            println "El demonio ya está en ejecución. Cerrando Grader..."
            System.exit(0);
        }
    }

    def test(def repo, def fork) {

        def deletables = []
        // TODO: poner todos los clones en un directorio aparte, asi no quedan sueltos en el proyecto

        log.info "Clonando repositorio ${repo.git_url}"
        def canonicalCloneDir = "${repo.user}-${repo.name}"
        log.info "git clone --depth 1 -b resolucion ${repo.git_url} ${canonicalCloneDir}".toString()
        def canonicalClone = "git clone --depth 1 -b resolucion ${repo.git_url} ${canonicalCloneDir}".execute()
        canonicalClone.waitFor()
        log.info "Repo clonado correctamente"
        deletables << new File("./${canonicalCloneDir}")

        // clonamos
        log.info "Clonando repositorio ${fork.git_url}"
        def solutionCloneDir = "${fork.user}-${repo.name}-${new Date().time}"
        def solutionClone = "git clone --depth 1 ${fork.git_url} ${solutionCloneDir}".execute()
        solutionClone.waitFor()
        deletables << new File("./${solutionCloneDir}")

        def sha = fork['sha'] = ['git', '--git-dir', "./${solutionCloneDir}/.git", 'rev-parse', 'HEAD'].execute().text
        log.info "Se corregirá el commit ${sha}"


        if (solutionClone.exitValue() == 1) {
            log.info 'No se ha podido hacer clone de la solución'
            def report = armarReporteFallido(fork, new Score(value: 0, total: 10), 'No se ha podido hacer clone de la solución')
            enviarReporte(fork, report)
            borrarArchivos(deletables)
            return
        }
        log.info "Repo clonado correctamente"

        // mergeamos con los tests
        try {
            // reemplazamos el codigo del repo de referencia con el codigo del fork
            (new File("./${canonicalCloneDir}/src/main/java/")).deleteDir()
            FileUtils.copyDirectory(new File("./${solutionCloneDir}/src/main/java/"), new File("${canonicalCloneDir}/src/main/java/"))
        } catch (FileNotFoundException fe) {
            log.info 'Se envió un repo no válido'
            def report = armarReporteFallido(fork, new Score(value: 0, total: 10), 'Se envió un repo no válido')
            enviarReporte(fork, report)
            borrarArchivos(deletables)
            return
        }
        log.info "Se reemplazó el código del fork dentro del repo"

        // compilamos

        def compileMerge = ['docker', 'run', '--rm', '-v', (System.getProperty("user.dir") + '/' + canonicalCloneDir + ':/home/gradle/project'), '-w', '/home/gradle/project', 'gradle:alpine', 'gradle', 'compileJava'].execute()
        compileMerge.waitFor()

        // Si no compila el código
        if (compileMerge.exitValue() == 1) {
            def report = armarReporteFallido(fork, new Score(value: 0, total: 10), compileMerge.text)
            enviarReporte(fork, report)
            borrarArchivos(deletables)
            return
        }

        // Ejecutamos las pruebas
        def testsExecution = ['docker', 'run', '--rm', '-v', (System.getProperty("user.dir") + '/' + canonicalCloneDir + ':/home/gradle/project'), '-w', '/home/gradle/project', 'gradle:alpine', 'gradle'].execute()
        testsExecution.waitFor()

        try {

            def junitParser = new JUnitParser()
            def junitIssues

            def myTestsFile = new File("${canonicalCloneDir}/build/test-results/test")

            myTestsFile.eachFile(FileType.FILES) {
                junitIssues = junitParser.parse(it)
                // por el momento todos los tests se encuentran en el mismo archivo
                // pero no sabemos el nombre exacto
            }
            def junitScore = new Score(value: junitIssues["tests"] - junitIssues["issues"].size(),
                    total: junitIssues["tests"]).normalize(8)


            def checkstyleParser = new CheckstyleParser()
            def myCheckstyleFile = new File("${canonicalCloneDir}/build/reports/checkstyle/main.xml")
            def checkstyleIssues = checkstyleParser.parse(myCheckstyleFile)

            def checkstyleScore = new Score(value: Math.max(20 - checkstyleIssues.size(), 0), total: 20).normalize(2)

            def finalScore = junitScore + checkstyleScore

            def resultados = [
                    [
                            type  : 'junit',
                            score : junitScore.value,
                            issues: junitIssues.issues.collect { it.toMap() }
                    ],
                    [
                            type  : 'checkstyle',
                            score : checkstyleScore.value,
                            issues: checkstyleIssues.collect { it.toMap() }
                    ]

            ]

            def report = armarReporte(fork, finalScore.normalize(10).value, resultados)
            enviarReporte(fork, report)
        } catch (FileNotFoundException e) {
            log.info 'Se ha enviado una tarea que no corresponde con la interfaz provista'
            def report = armarReporteFallido(fork, new Score(value: 0, total: 10), 'Se ha enviado una tarea que no corresponde con la interfaz provista')
            enviarReporte(fork, report)
        } catch (Exception e) {
            log.error("Falla la corrección!", e)
        } finally {
            borrarArchivos(deletables)
        }
    }

    private armarReporteFallido(fork, finalScore, texto) {
        def report = [
                test_run: [
                        score  : finalScore,
                        sha: fork.sha,
                        details: texto
                ]
        ]
        report
    }

    private armarReporte(fork, finalScore, resultados) {
        def report = [
                test_run: [
                        score  : finalScore,
                        sha: fork.sha,
                        results: resultados
                ]
        ]
        report
    }

    private void enviarReporte(fork, report) {

        def http = new HTTPBuilder(PropertiesHolder.instance.properties.DASHBOARD_HOST)

        try {
            http.request(Method.POST, ContentType.JSON) {
                uri.path = "/api/v1/repos/${fork.id}/grade"
                headers.'x-api-key' = PropertiesHolder.instance.properties.GRADER_TOKEN
                body = report

                response.success = { resp, json ->
                    log.info "Transferencia de correccion exitosa: ${resp.status}"
                }

                response.failure = { resp, json ->
                    log.info "Transferencia de correccion fallida: ${resp.status}"
                }
            }
        } catch (HttpHostConnectException e) {
            log.error("Fallo en la conexión", e)
            log.info "Reintentando en 5 minutos..."
            sleep(5 * 60 * 1000)
            enviarReporte(fork, report)
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
