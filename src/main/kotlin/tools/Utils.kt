package net.firzen.web.tools

import kotlinx.io.IOException
import net.firzen.web.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads a URL synchronously and returns its response body as text.
 */
@Throws(IOException::class)
fun downloadFile(urlString: String, client: OkHttpClient): String? {
    return try {
        Logger.d("Downloading file from $urlString")
        val request = Request.Builder()
            .url(urlString)
            .build()

        // this is a synchronous call
        var response: Response? = null
        try {
            response = client.newCall(request).execute()

            val rsp = response.body.string()
            Logger.d("++ Downloading file complete")

            rsp
        } finally {
            response?.close()
        }
    } catch (e: Exception) {
        Logger.e("Downloading file failed!", e)
        throw e
    }
}
