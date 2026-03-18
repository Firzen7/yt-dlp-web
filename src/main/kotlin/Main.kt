package net.firzen.web

import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

fun main() {
//    startServer()

    downloadVideo("https://www.youtube.com/watch?v=FAyKDaXEAgc")
//    downloadVideo("https://www.youtube.com/playlist?list=PLmXxqSJJq-yXrCPGIT2gn8b34JjOrl4Xf")
}

fun downloadVideo(rawUrl: String) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, false)
}

fun downloadAudio(rawUrl: String) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, true)
}

fun downloadMedia(rawUrl: String, outputDir: String, audioOnly: Boolean = false) {
    val dir = File(outputDir)

    if((dir.isDirectory || dir.mkdirs()) && dir.canWrite() && dir.canRead()) {
        downloadMedia(Url(rawUrl), dir, audioOnly)
    }
    else {
        println("Error! $dir is not usable directory!")
    }
}

fun downloadMedia(url: Url, outputDir: File, audioOnly: Boolean) : Int {
    fun BufferedReader.consumeLines(tag: String) = CoroutineScope(Dispatchers.IO).launch {
        forEachLine { println("$tag: $it") }
    }

    return runBlocking {
        val command = if(audioOnly) {
            "yt-dlp --no-playlist --extract-audio --audio-format mp3 $url"
        }
        else {
            "yt-dlp --no-playlist $url"
        }.split(" ")

        val process = ProcessBuilder(command)
            .directory(outputDir)
            .start()

        val outJob = process.inputStream.bufferedReader().consumeLines("OUT")
        val errJob = process.errorStream.bufferedReader().consumeLines("ERR")

        val exitCode = withContext(Dispatchers.IO) {
            process.waitFor()
        }

        outJob.join()
        errJob.join()

        return@runBlocking exitCode
    }
}
