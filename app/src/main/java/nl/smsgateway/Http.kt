package nl.smsgateway

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object Http {
    fun postJson(url: String, payload: String, headerName: String, headerValue: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "SmsGateway/1.1")
            if (headerName.isNotBlank()) conn.setRequestProperty(headerName, headerValue)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    fun isValidUrl(u: String): Boolean {
        val uri = Uri.parse(u.trim())
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }

    fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ts))

    fun now(): String =
        SimpleDateFormat("dd-MM HH:mm:ss", Locale.getDefault()).format(Date())
}
