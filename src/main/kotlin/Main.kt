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

//    downloadVideo("https://www.youtube.com/watch?v=FAyKDaXEAgc", { println(it) })
    downloadVideo("https://www.youtube.com/watch?v=k7Us-7fBtPU", { println(it) })
//    downloadVideo("https://www.youtube.com/playlist?list=PLmXxqSJJq-yXrCPGIT2gn8b34JjOrl4Xf")
}

fun downloadVideo(rawUrl: String, progressCallback : (Double?) -> Unit = {}) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, false, progressCallback)
}

fun downloadAudio(rawUrl: String, progressCallback : (Double?) -> Unit = {}) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, true, progressCallback)
}

fun downloadMedia(rawUrl: String, outputDir: String, audioOnly: Boolean = false,
                  progressCallback : (Double?) -> Unit) {

    val dir = File(outputDir)

    if(!rawUrl.isValidUrl()) {
        println("Error! Invalid url: $rawUrl")
        return
    }

    if(rawUrl.contains("playlist")) {
        println("Error! Cannot download playlists!")
        return
    }

    if((dir.isDirectory || dir.mkdirs()) && dir.canWrite() && dir.canRead()) {
        downloadMedia(Url(rawUrl), dir, audioOnly, progressCallback)
    }
    else {
        println("Error! $dir is not usable directory!")
    }
}

fun downloadMedia(url: Url, outputDir: File, audioOnly: Boolean, progressCallback : (Double?) -> Unit) : Int {
    val outTag = "OUT"
    val errorTag = "ERR"

    fun BufferedReader.consumeLines(tag: String) = CoroutineScope(Dispatchers.IO).launch {
        forEachLine { line ->
            val percent = Regex("""\d+(\.\d+)?%""")
                .find(line)
                ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

            if(tag == outTag) {
                progressCallback(percent)
            }
        }
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

        val outJob = process.inputStream.bufferedReader().consumeLines(outTag)
        val errJob = process.errorStream.bufferedReader().consumeLines(errorTag)

        val exitCode = withContext(Dispatchers.IO) {
            process.waitFor()
        }

        outJob.join()
        errJob.join()

        return@runBlocking exitCode
    }
}
