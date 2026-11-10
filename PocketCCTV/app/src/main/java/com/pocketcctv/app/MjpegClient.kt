package com.pocketcctv.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Monitor side: pulls the live picture from the camera and keeps reconnecting if Wi-Fi drops. */
class MjpegClient(
    private val url: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null

    fun start() {
        running = true
        thread(name = "cctv-viewer", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    private fun loop() {
        while (running) {
            try {
                onState("Connecting…")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 10000
                conn = c
                if (c.responseCode == 403) {
                    onState("Wrong PIN – check the number shown on the camera")
                    return
                }
                val input = BufferedInputStream(c.inputStream, 64 * 1024)
                onState("Live")
                while (running) {
                    val len = nextFrameLength(input) ?: break
                    val buf = ByteArray(len)
                    readFully(input, buf)
                    BitmapFactory.decodeByteArray(buf, 0, len)?.let(onFrame)
                }
            } catch (e: Exception) {
                if (running) onState("Connection lost – retrying…")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (running) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun nextFrameLength(i: InputStream): Int? {
        var len = -1
        while (true) {
            val line = readLine(i) ?: return null
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                len = line.substringAfter(':').trim().toInt()
            } else if (line.isEmpty() && len >= 0) {
                return len
            }
        }
    }

    private fun readFully(i: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = i.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    companion object {
        /** One-line summary like "📷 Hall · 🔴 Recording · 🔋 87%" (null if the camera can't be reached). */
        fun fetchStatus(host: String, port: Int, pin: String): String? = try {
            val c = URL("http://$host:$port/status?pin=$pin").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            try {
                if (c.responseCode != 200) {
                    null
                } else {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    buildString {
                        append("📷 ").append(j.optString("name"))
                        append(if (j.optBoolean("recording")) "  ·  🔴 Recording" else "  ·  ⚪ Not recording")
                        val bat = j.optInt("battery", -1)
                        if (bat >= 0) append("  ·  🔋 ").append(bat).append("%")
                    }
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
