package org.bibletranslationtools.audioscripts

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.digitalmediaserver.cuelib.CueParser
import org.wycliffeassociates.otter.common.audio.AudioCue
import org.wycliffeassociates.otter.common.audio.wav.CueChunk
import org.wycliffeassociates.otter.common.audio.wav.WavFile
import org.wycliffeassociates.otter.common.audio.wav.WavMetadata
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.regex.Pattern

const val DEFAULT_SAMPLE_RATE = 44100
const val CUE_FRAME_SIZE = 75.0

val languageRegex = Pattern.compile("^([a-zA-Z]+(?:-[a-zA-Z]+-[a-zA-Z]+)?)_.*$")
val bookRegex = Pattern.compile(".*_[a-z]{3}_([a-z1-3]{3})_.*")
val chapterRegex = Pattern.compile(".*_c(\\d+).*")

data class Cues(
    val list: MutableList<AudioCue> = mutableListOf()
)

typealias LanguageCode = String
typealias ResultJson = MutableMap<LanguageCode, LanguageResults>
typealias BookCode = String
typealias LanguageResults = MutableMap<BookCode, BookResult>
typealias ChapterNumber = Int
typealias BookResult = MutableMap<ChapterNumber, ChapterResult>

class AudioScripts : CliktCommand() {
    private val format by option(help = "The format of files to process.").choice("wav", "cue")

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
        val listOfFiles = files.toFile().readLines()

        when (format) {
            "wav" -> {
                val result = analyzeWavFiles(listOfFiles)
                writeOutput(outputDir, outputName, result)
            }

            "cue" -> {
                val result = analyzeCueSheets(listOfFiles)
                writeOutput(outputDir, outputName, result)
            }
        }
    }
}

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

fun main(args: Array<String>) {
    AudioScripts().main(args)
}

fun analyzeWavFiles(wavPaths: List<String>): String {
    val result: ResultJson = mutableMapOf()
    val failedFiles = mutableListOf<File>()
    val files = wavPaths
        .map { File(it) }
        .filter { it.exists() }
        .filter { it.extension == "wav" }

    val emptyFiles = files.filter { it.length() > 0L }
    failedFiles.addAll(emptyFiles)

    for (file in files) {
        val content = getContent(file)
        content?.let {
            processAudioFile(content, file, result)
        } ?: failedFiles.add(file)
    }

    val mapper = ObjectMapper(JsonFactory())
        .registerModule(KotlinModule())

    data class Output(
        val result: ResultJson,
        val failedFiles: List<File>
    )

    val output = Output(result, failedFiles)

    val thing = mapper.writeValueAsString(output)
    return thing
}

fun processAudioFile(content: CueContent, file: File, resultJson: ResultJson) {
    val (language, book, chapter) = content

    if (!resultJson.containsKey(language)) resultJson[language] = mutableMapOf()
    if (!resultJson[language]!!.containsKey(book)) resultJson[language]!![book] = mutableMapOf()

    val cues = readAudioMetadata(file)

    resultJson[language]!![book]!![chapter] = ChapterResult(chapter, file, cues)
}

fun readAudioMetadata(file: File): List<AudioCue> {
    val wav = WavFile(file, WavMetadata(listOf(CueChunk())))
    return wav.getCues()
}

fun analyzeCueSheets(cuePaths: List<String>): String {
    val result: ResultJson = mutableMapOf()
    val failedFiles = mutableListOf<File>()
    val files = cuePaths
        .map { File(it) }
        .filter { it.exists() }
        .filter { it.extension == "cue" }

    val emptyFiles = files.filter { it.length() > 0L }
    failedFiles.addAll(emptyFiles)

    for (file in files) {
        val content = getContent(file)
        content?.let {
            processCue(content, file, result)
        } ?: failedFiles.add(file)
    }

    val mapper = ObjectMapper(JsonFactory())
        .registerModule(KotlinModule())

    data class Output(
        val result: ResultJson,
        val failedFiles: List<File>
    )

    val output = Output(result, failedFiles)

    val thing = mapper.writeValueAsString(output)
    return thing
}

fun processCue(content: CueContent, file: File, resultJson: ResultJson) {
    val (language, book, chapter) = content

    if (!resultJson.containsKey(language)) resultJson[language] = mutableMapOf()
    if (!resultJson[language]!!.containsKey(book)) resultJson[language]!![book] = mutableMapOf()

    val cues = readCueSheet(file)

    resultJson[language]!![book]!![chapter] = ChapterResult(chapter, file, cues)
}

fun readCueSheet(file: File): List<AudioCue> {
    val cuesheet = CueParser.parse(Path.of(file.absolutePath), Charset.defaultCharset())

    val cues = Cues()
    cuesheet.allTrackData.forEach { track ->
        val pos = track.firstIndex.position.totalFrames / CUE_FRAME_SIZE * DEFAULT_SAMPLE_RATE
        cues.list.add(AudioCue(pos.toInt(), track.title))
    }
    return cues.list
}

data class ChapterResult(
    val number: Int,
    val file: File,
    val cues: List<AudioCue>
)

data class CueContent(
    val languageCode: String,
    val bookCode: String,
    val chapterNumber: Int
)

fun getContent(file: File): CueContent? {
    val lang = getLanguageName(file)
    val book = getBookCode(file)
    val chap = getChapterNumber(file)

    if (listOf(lang, book, chap).any { it == null }) return null

    return CueContent(lang!!, book!!, chap!!)
}

fun getLanguageName(file: File): String? {
    val name = file.name

    val matcher = languageRegex.matcher(name)
    return if (matcher.matches()) {
        matcher.group(1)
    } else {
        null
    }
}

fun getChapterNumber(file: File): Int? {
    val name = file.name

    val matcher = chapterRegex.matcher(name)
    return if (matcher.matches()) {
        matcher.group(1).toInt()
    } else {
        null
    }
}

fun getBookCode(file: File): String? {
    val name = file.name

    val matcher = bookRegex.matcher(name)
    return if (matcher.matches()) {
        matcher.group(1)
    } else {
        null
    }
}