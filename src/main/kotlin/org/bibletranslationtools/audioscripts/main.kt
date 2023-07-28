package org.bibletranslationtools.audioscripts

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class AudioScripts(): CliktCommand() {
    override fun run() = Unit

}

fun main(args: Array<String>) = AudioScripts()
    .subcommands(AnalyzeMarkers(), FixMarkers())
    .main(args)