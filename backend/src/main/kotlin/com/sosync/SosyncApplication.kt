package com.sosync

import com.sosync.config.SosyncProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(SosyncProperties::class)
class SosyncApplication

fun main(args: Array<String>) {
    runApplication<SosyncApplication>(*args)
}
