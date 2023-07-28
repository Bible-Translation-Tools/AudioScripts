package org.bibletranslationtools.audioscripts

import java.io.File
import java.nio.file.Path

fun writeOutput(outputDir: Path, outputName: String, result: String) {
    val outputDir = outputDir.toFile()
    val outFile = if (outputDir.exists()) {
        File(outputDir, outputName)
    } else {
        outputDir.mkdirs()
        File(outputDir, outputName)
    }
    outFile.writer(Charsets.UTF_8).use { it.write(result) }
}