package com.dozycoffee.auth.server

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<DozyAuthApplication>().with(TestcontainersConfiguration::class).run(*args)
}
