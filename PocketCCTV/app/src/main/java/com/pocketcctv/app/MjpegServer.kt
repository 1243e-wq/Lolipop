package com.pocketcctv.app

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object NetUtil {
    /** The phone's Wi-Fi address, e.g. 192.168.1.20 */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}

/**
 * Tiny built-in web server on the camera phone.
 *   /stream        live video (MJPEG)
 *   /snapshot.jpg  one picture
 *   /status        camera name, recording state, battery
 * Every request must carry ?pin=NNNN
 */
class MjpegServer(private val prefs: Prefs, private val status: () -> String) {

    companion object {
        const val PORT = 8765
    }

    var port: Int = PORT
        private set

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()

    fun hasViewers() = clients.isNotEmpty()

    fun publish(jpeg: ByteArray) {
        for (q in clients) {
            q.clear()
            q.offer(jpeg)
        }
    }

    fun start() {
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: IOException) {
            ServerSocket(0)
        }
        server = ss
        port = ss.localPort
        running = true
        thread(name = "cctv-http", isDaemon = true) {
            while (running) {
                try {
                    val s = ss.accept()
                    thread(isDaemon = true) {
                        try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} }
                    }
                } catch (e: IOException) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        clients.clear()
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        while (true) {
            val l = reader.readLine() ?: break
            if (l.isEmpty()) break
        }
        val target = request.split(" ").getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val pin = target.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("pin=") }?.substringAfter("pin=")
        val out = BufferedOutputStream(sock.getOutputStream())

        if (pin != prefs.pin) {
            Thread.sleep(1000) // slows down PIN guessing
            respond(out, "403 Forbidden", "text/plain", "Wrong PIN".toByteArray())
            return
        }
        when (path) {
            "/status" -> respond(out, "200 OK", "application/json", status().toByteArray())
            "/snapshot.jpg" -> {
                val f = grabFrame()
                if (f != null) respond(out, "200 OK", "image/jpeg", f)
                else respond(out, "503 Service Unavailable", "text/plain", "No picture yet".toByteArray())
            }
            "/stream" -> stream(sock, out)
            else -> respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    private fun respond(out: OutputStream, code: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                .toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun grabFrame(): ByteArray? {
        val q = ArrayBlockingQueue<ByteArray>(1)
        clients.add(q)
        try {
            return q.poll(4, TimeUnit.SECONDS)
        } finally {
            clients.remove(q)
        }
    }

    private fun stream(sock: Socket, out: OutputStream) {
        sock.soTimeout = 0
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        val q = ArrayBlockingQueue<ByteArray>(1)
        clients.add(q)
        CctvState.viewers.value = clients.size
        try {
            while (running) {
                val f = q.poll(5, TimeUnit.SECONDS) ?: continue
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
            // viewer left
        } finally {
            clients.remove(q)
            CctvState.viewers.value = clients.size
        }
    }
}
