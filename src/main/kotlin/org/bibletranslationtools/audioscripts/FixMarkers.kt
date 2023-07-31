package org.bibletranslationtools.audioscripts

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.bibletranslationtools.audioscripts.audio.ChunkMarker
import org.bibletranslationtools.audioscripts.audio.OratureAudioFile
import org.bibletranslationtools.audioscripts.audio.UnknownMarker
import org.bibletranslationtools.audioscripts.audio.VerseMarker
import org.wycliffeassociates.otter.common.audio.AudioCue
import org.wycliffeassociates.otter.common.audio.AudioFile
import org.wycliffeassociates.otter.common.audio.mp3.MP3FileReader
import java.io.File
import java.lang.Exception
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.moveTo

const val FRAME_SIZE = 2

fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }

data class StandardizeMarkersResult(
    var file: String,
    var cues: List<AudioCue> = listOf(),
    var audioMd5: String = "",
    var status: String = "error"
)

class FixMarkers : CliktCommand(name = "fix") {

    private val format by option(help = "The format of files to process.").choice("wav", "mp3")

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

        val results = mutableListOf<StandardizeMarkersResult>()
        files.forEach {
            results.add(
                StandardizeMarkers().execute(it)
            )
        }
        val output = ObjectMapper(JsonFactory()).registerModule(KotlinModule()).writeValueAsString(results)

        writeOutput(outputDir, outputName, output)
    }

}

class StandardizeMarkers {
    fun execute(file: File): StandardizeMarkersResult {
        var initialHex = ""
        var finalHex = ""

        val result = StandardizeMarkersResult(file.absolutePath)

        val ext = file.extension

        val temp = File("temp.${ext}")
        var tempCue: File? = null
        temp.delete()
        try {
            if (ext == "mp3") {
                val (cuePath, resultOk) = copyCue(file, temp)
                tempCue = cuePath
                if (!resultOk) {
                    temp.delete()
                    tempCue.delete()
                    println("copying cue failed")
                    throw Exception("Cue for ${file.absolutePath} could not be copied")
                }
            }

            temp.createNewFile()
            file.inputStream().use {
                it.transferTo(
                    temp.outputStream()
                )
            }
            println("Copied ${file.name} to temp file")

            if (ext == "wav") {
                AudioFile(temp).reader().use {
                    it.open()
                    val fileLength = it.totalFrames / FRAME_SIZE
                    println("Total frames is ${fileLength}")
                    val bytes = ByteArray(fileLength)
                    it.getPcmBuffer(bytes)
                    val md = MessageDigest.getInstance("MD5")
                    initialHex = md.digest(bytes).toHex()
                }
                println("Computed md5 of AudioFile")
            }

            println("okay")
            val oratureFile = OratureAudioFile(temp)
            result.cues = oratureFile.getCues()
            println("here")
            oratureFile.clearMarkersOfType<UnknownMarker>()
            oratureFile.update()

            if (ext == "wav") {
                oratureFile.reader().use {
                    it.open()
                    val fileLength = it.totalFrames / FRAME_SIZE
                    val bytes = ByteArray(fileLength)
                    it.getPcmBuffer(bytes)
                    val md = MessageDigest.getInstance("MD5")
                    finalHex = md.digest(bytes).toHex()
                }
                println("Computed md5 of updated file")
            }

            if ((finalHex == initialHex && finalHex != "") && ext == "wav") {
                temp.toPath().moveTo(file.toPath(), overwrite = true)
                result.audioMd5 = finalHex
                result.status = "OK"
                println("OK!")
            } else if (ext == "mp3") {
                if (tempCue != null) {
                    replaceCue(tempCue, file)
                    result.status = "OK"
                    println("OK!")
                } else {
                    println("temp cue is null!")
                }
            }
            else {
                println("ERROR! md5 did not match.")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        finally {
            temp.delete()
            tempCue?.delete()
            return result
        }
    }
}

fun copyCue(source: File, target: File): Pair<File, Boolean> {
    val absoluteSource = source.absolutePath
        .replace("/mp3/hi/", "/mp3/")
        .replace("hi/chapter", "chapter")
        .replace("low/chapter", "chapter")
        .replace("/mp3/low/", "/mp3/")
        .replace("mp3", "cue")
    val absoluteTarget = target.absolutePath.replace("mp3", "cue")

    val newSource = File(absoluteSource)
    val newTarget = File(absoluteTarget)

    println("Copying cue from ${newSource.absolutePath}")
    println("Cue exitsts: ${newSource.exists()}")

    newTarget.delete()
    try {
        Files.copy(newSource.toPath(), newTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return Pair(newTarget, true)
    } catch (e: Exception) {
        println("Failed to copy cue from ${newSource.absolutePath} to ${newTarget.absolutePath}")
        e.printStackTrace()
        return Pair(newTarget, false)
    }
}

fun replaceCue(from: File, toMp3: File) {
    val absoluteCue = toMp3.absolutePath
        .replace("/mp3/hi/", "/mp3/")
        .replace("hi/chapter", "chapter")
        .replace("low/chapter", "chapter")
        .replace("/mp3/low/", "/mp3/")
        .replace("mp3", "cue")

    val to = File(absoluteCue)

    println("Updating cue at ${to.absolutePath}")

    from.toPath().moveTo(to.toPath(), overwrite = true)
}