package io.wecode.loom

import groovy.json.JsonSlurper
import groovy.sql.Sql

def jsonSlurper = new JsonSlurper()

// 0. en un while eterno, o ejecutandose cada X minutos

// por cada tarea en el json de tareas

def sql = Sql.newInstance('jdbc:sqlite:loom.sqlite', 'org.sqlite.JDBC')

sql.eachRow("select * from assignments") { repo ->

    // docs: https://developer.github.com/v3/repos/forks/
    def mazinger = new URL("https://api.github.com/repos/${repo.full_name}/forks").getText()
    def forks = jsonSlurper.parseText(mazinger)

    forks.each { fork ->
        println fork.git_url
        println fork.updated_at

        // 1. guardarla en la base

        // si es mas nuevo, o si tiene una actualizacion, correr tests

        // clone
        // compile
        // si ok
        // merge con tests
        // ejecutar tooodo
        // recolectar resultados
        // generar reporte
        // publicar, o guardar en la base por ahora

    }

}