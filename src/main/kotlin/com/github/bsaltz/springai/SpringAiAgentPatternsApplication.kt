package com.github.bsaltz.springai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.shell.command.annotation.CommandScan
import org.springframework.shell.command.annotation.EnableCommand

@SpringBootApplication
@EnableCommand
@CommandScan
class SpringAiAgentPatternsApplication

fun main(args: Array<String>) {
    runApplication<SpringAiAgentPatternsApplication>(*args)
}
