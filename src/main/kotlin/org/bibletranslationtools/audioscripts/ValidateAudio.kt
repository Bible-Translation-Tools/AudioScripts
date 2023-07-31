package org.bibletranslationtools.audioscripts

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.bibletranslationtools.scriptureaudiovalidator.common.data.FileStatus
import org.bibletranslationtools.scriptureaudiovalidator.common.data.SerializableFileResult
import org.bibletranslationtools.scriptureaudiovalidator.common.usecases.FileProcessingRouter
import java.io.File
import java.nio.file.Path

class ValidateAudio : CliktCommand(name = "validate") {

    val files: Path by option(
        help = "A file containing the absolute paths of files to be processed separated by linebreaks.)"
    )
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .default(File("files.txt").toPath())

    val outputDir: Path by option(
        help = "The directory to write results to"
    ).path(mustExist = true, canBeFile = false, mustBeWritable = true)
        .default(File(".").toPath())

    val outputName: String by option(help = "The name of the file to write results to").default("results.json")

    override fun run() {
        val files = files.toFile().readLines().map { File(it) }

        val results = mutableListOf<SerializableFileResult>()
        files.forEach {
            results.addAll(processFile(it))
        }
        val output = ObjectMapper(JsonFactory()).registerModule(KotlinModule()).writeValueAsString(results)

        writeOutput(outputDir, outputName, output)
    }

    val fileProcessor = FileProcessingRouter.build()
    /**
     * Processes the uploaded file individually to avoid having an error
     * blocking the entire batch.
     */
    private fun processFile(file: File): List<SerializableFileResult> {
        return try {
            fileProcessor.handleFiles(listOf(file))
                .map { result ->
                    SerializableFileResult(result) // hide internal storage path
                }
        } catch (e: Exception) {
            listOf(
                SerializableFileResult(FileStatus.REJECTED, file.name, "Error")
            )
        }
    }

}