package org.bibletranslationtools.audioscripts

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.usfmtools.USFMParser
import org.wycliffeassociates.usfmtools.models.markers.CMarker
import org.wycliffeassociates.usfmtools.models.markers.VMarker
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels

fun downloadFile(url: URL, outputFileName: String) {
    url.openStream().use {
        Channels.newChannel(it).use { rbc ->
            FileOutputStream(outputFileName).use { fos ->
                fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
            }
        }
    }
}

fun findBridgesInRepos() {
    val parser = FindBridges()
    val results = mutableListOf<Result>()
    val mapper = ObjectMapper(JsonFactory()).registerModule(KotlinModule())
    val urls = listOf<String>(
        "https://content.bibletranslationtools.org/WA-Catalog/gu_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/hi_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/id_ayt",
        "https://content.bibletranslationtools.org/WA-Catalog/ilo_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/ne_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/or_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/tl_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/ta_ulb",
        "https://content.bibletranslationtools.org/WA-Catalog/vi_ulb"
    )
    urls.forEach {
        val url = URL("$it/archive/master.zip")
        val file = File("${it.split("/").last()}.zip")
        downloadFile(url, file.name)
        results.addAll(parser.execute(file))
    }
    val output = mapper.writeValueAsString(results)
    println(output)
}

data class Result(
    val languageCode: String,
    val bibleId: String,
    val bookSlug: String,
    val chapterNumber: Int,
    val verses: MutableList<String>
)

class FindBridges {

    val parser = USFMParser(arrayListOf("s5"))

    fun execute(rcFile: File): List<Result> {
        val result = mutableListOf<Result>()
        ResourceContainer.load(rcFile).use { rc ->
            rc.manifest.projects.forEach { book ->
                val bookPath = book.path
                if (rc.accessor.fileExists(bookPath.removePrefix("./"))) {
                    rc.accessor.getReader(bookPath.removePrefix("./")).use { reader ->
                        val usfm = reader.readText()
                        val parsed = parser.parseFromString(usfm)
                        val chapters = parsed.getChildMarkers(CMarker::class.java)
                        chapters.forEach { chapter ->
                            val bridges = mutableListOf<String>()
                            val verses = chapter.getChildMarkers(VMarker::class.java)
                            verses.filter { verse ->
                                verse.startingVerse != verse.endingVerse
                            }.forEach { bridges.add("${it.startingVerse}-${it.endingVerse}") }
                            if (bridges.isNotEmpty()) {
                                result.add(
                                    Result(
                                        rc.manifest.dublinCore.language.identifier,
                                        rc.manifest.dublinCore.identifier,
                                        book.identifier,
                                        chapter.number,
                                        bridges
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return result
    }
}