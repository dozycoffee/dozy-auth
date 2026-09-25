package com.dozycoffee.auth.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DozyAuthApplication

fun main(args: Array<String>) {
    runApplication<DozyAuthApplication>(*args)
}
