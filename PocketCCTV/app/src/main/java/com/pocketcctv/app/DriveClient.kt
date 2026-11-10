package com.pocketcctv.app

import android.content.Context
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Google sign-in. Only the "drive.file" permission is used: the app can see ONLY files it created itself. */
object DriveAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun client(ctx: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE))
            .build()
        return GoogleSignIn.getClient(ctx, gso)
    }

    fun account(ctx: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(ctx)?.takeIf { GoogleSignIn.hasPermissions(it, Scope(SCOPE)) }

    /** Blocking – call from a background thread. */
    fun token(ctx: Context): String {
        val acc = account(ctx)?.account ?: throw IOException("Not signed in to Google")
        try {
            return GoogleAuthUtil.getToken(ctx, acc, "oauth2:$SCOPE")
        } catch (e: GoogleAuthException) {
            throw IOException("Google authorization failed: ${e.message}", e)
        }
    }

    fun invalidate(ctx: Context, token: String) {
        try { GoogleAuthUtil.clearToken(ctx, token) } catch (_: Exception) {}
    }
}

/** Talks to Google Drive over plain HTTPS (no heavy libraries). */
class DriveClient(context: Context, private val prefs: Prefs) {

    private val ctx = context.applicationContext
    private var token = ""

    class Resp(val code: Int, val body: String, private val headers: Map<String?, List<String>>) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    class RemoteFile(val id: String, val name: String, val size: Long, val createdTime: String)

    private class Probe(val valid: Boolean, val offset: Long = 0, val doneJson: String? = null)

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_NAME = "PocketCCTV"
        private const val CHUNK = 8 * 1024 * 1024 // must be a multiple of 256 KB
        private val JSON_HEADER = mapOf("Content-Type" to "application/json; charset=UTF-8")
    }

    fun accessToken(force: Boolean = false): String = auth(force)

    private fun auth(force: Boolean = false): String {
        if (force && token.isNotEmpty()) {
            DriveAuth.invalidate(ctx, token)
            token = ""
        }
        if (token.isEmpty()) token = DriveAuth.token(ctx)
        return token
    }

    private fun call(method: String, url: String, body: ByteArray?, headers: Map<String, String>): Resp {
        var attempt = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Authorization", "Bearer ${auth(attempt > 0)}")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 401 && attempt == 0) {
                    attempt++
                    continue
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return Resp(code, text, c.headerFields)
            } finally {
                c.disconnect()
            }
        }
    }

    // ---------- folder ----------

    fun findFolder(): String? {
        prefs.driveFolderId?.let { id ->
            val r = call("GET", "$API/files/$id?fields=id,trashed", null, emptyMap())
            if (r.code == 200 && !JSONObject(r.body).optBoolean("trashed", false)) return id
        }
        val q = URLEncoder.encode(
            "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8"
        )
        val r = call("GET", "$API/files?q=$q&fields=files(id,name)&pageSize=1", null, emptyMap())
        if (r.code != 200) throw IOException("Drive search failed (${r.code}): ${r.body.take(200)}")
        val arr = JSONObject(r.body).getJSONArray("files")
        if (arr.length() == 0) return null
        val id = arr.getJSONObject(0).getString("id")
        prefs.driveFolderId = id
        return id
    }

    fun ensureFolder(): String {
        findFolder()?.let { return it }
        val body = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString().toByteArray()
        val r = call("POST", "$API/files?fields=id", body, JSON_HEADER)
        if (r.code !in 200..299) throw IOException("Could not create Drive folder (${r.code}): ${r.body.take(200)}")
        val id = JSONObject(r.body).getString("id")
        prefs.driveFolderId = id
        return id
    }

    // ---------- listing / deleting ----------

    fun listRecordings(): List<RemoteFile> {
        val folder = findFolder() ?: return emptyList()
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val q = URLEncoder.encode("'$folder' in parents and trashed=false", "UTF-8")
            var url = "$API/files?q=$q&fields=nextPageToken,files(id,name,size,createdTime)" +
                "&orderBy=createdTime%20desc&pageSize=200"
            if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
            val r = call("GET", url, null, emptyMap())
            if (r.code != 200) throw IOException("Could not list recordings (${r.code}): ${r.body.take(200)}")
            val j = JSONObject(r.body)
            val arr = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out.add(
                    RemoteFile(
                        f.getString("id"), f.getString("name"),
                        f.optString("size", "0").toLongOrNull() ?: 0L,
                        f.optString("createdTime", "")
                    )
                )
            }
            page = j.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun deleteFile(id: String) {
        val r = call("DELETE", "$API/files/$id", null, emptyMap())
        if (r.code !in listOf(200, 204, 404)) throw IOException("Could not delete from Drive (${r.code})")
    }

    /** Deletes Drive clips older than [days]. */
    fun cleanupOld(days: Int): Int {
        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        var n = 0
        for (f in listRecordings()) {
            val created = try { Instant.parse(f.createdTime) } catch (_: Exception) { continue }
            if (created.isBefore(cutoff)) {
                deleteFile(f.id)
                n++
            }
        }
        return n
    }

    // ---------- upload + verify ----------

    /**
     * Uploads [file] (resumable, in 8 MB pieces) and then CHECKS that Google really holds an identical copy
     * (same size and same MD5). Returns the Drive file id only when everything matches, otherwise null.
     * The caller deletes the local file only after a non-null result.
     */
    fun uploadVerified(file: File, folderId: String, onProgress: (Int) -> Unit): String? {
        val total = file.length()

        // Already there from an earlier attempt? Then just verify it.
        findRemote(file.name, folderId)?.let { existing ->
            if (matches(existing, file, total)) {
                prefs.setSession(file.name, null)
                return existing.getString("id")
            }
        }

        var sessionUrl = prefs.session(file.name)
        var offset = 0L
        var fileId: String? = null
        if (sessionUrl != null) {
            val p = probe(sessionUrl, total)
            when {
                !p.valid -> { prefs.setSession(file.name, null); sessionUrl = null }
                p.doneJson != null -> fileId = JSONObject(p.doneJson).getString("id")
                else -> offset = p.offset
            }
        }
        if (sessionUrl == null) {
            sessionUrl = startSession(file, folderId)
            prefs.setSession(file.name, sessionUrl)
            offset = 0L
        }
        if (fileId == null) fileId = sendChunks(file, sessionUrl, offset, total, onProgress)

        prefs.setSession(file.name, null)

        // Verify what Google stored.
        var meta = getMeta(fileId)
        if (meta.optString("md5Checksum").isEmpty()) {
            Thread.sleep(2000)
            meta = getMeta(fileId)
        }
        if (!matches(meta, file, total)) {
            try { deleteFile(fileId) } catch (_: Exception) {}
            return null
        }
        return fileId
    }

    private fun getMeta(id: String): JSONObject {
        val r = call("GET", "$API/files/$id?fields=id,size,md5Checksum", null, emptyMap())
        if (r.code != 200) throw IOException("Could not verify upload (${r.code})")
        return JSONObject(r.body)
    }

    private fun findRemote(name: String, folderId: String): JSONObject? {
        val q = URLEncoder.encode("name='$name' and '$folderId' in parents and trashed=false", "UTF-8")
        val r = call("GET", "$API/files?q=$q&fields=files(id,size,md5Checksum)&pageSize=1", null, emptyMap())
        if (r.code != 200) return null
        val arr = JSONObject(r.body).optJSONArray("files") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    private fun matches(meta: JSONObject, file: File, total: Long): Boolean {
        val size = meta.optString("size", "-1").toLongOrNull() ?: -1L
        if (size != total) return false
        val md5 = meta.optString("md5Checksum", "")
        return md5.isEmpty() || md5.equals(md5(file), ignoreCase = true)
    }

    private fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun startSession(file: File, folderId: String): String {
        val meta = JSONObject()
            .put("name", file.name)
            .put("parents", JSONArray().put(folderId))
            .put("mimeType", "video/mp4")
            .toString().toByteArray()
        val r = call(
            "POST", "$UPLOAD/files?uploadType=resumable&fields=id,size", meta,
            JSON_HEADER + mapOf(
                "X-Upload-Content-Type" to "video/mp4",
                "X-Upload-Content-Length" to file.length().toString()
            )
        )
        if (r.code != 200) throw IOException("Could not start upload (${r.code}): ${r.body.take(200)}")
        return r.header("Location") ?: throw IOException("Drive did not return an upload address")
    }

    private fun nextOffset(r: Resp): Long =
        r.header("Range")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L

    private fun probe(session: String, total: Long): Probe {
        val r = call("PUT", session, ByteArray(0), mapOf("Content-Range" to "bytes */$total"))
        return when (r.code) {
            200, 201 -> Probe(true, total, r.body)
            308 -> Probe(true, nextOffset(r))
            404, 410 -> Probe(false)
            else -> throw IOException("Upload status check failed (${r.code})")
        }
    }

    private fun sendChunks(file: File, session: String, start: Long, total: Long, onProgress: (Int) -> Unit): String {
        var offset = start
        var failures = 0
        val buf = ByteArray(CHUNK)
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                val len = minOf(CHUNK.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, len)
                val chunk = if (len == buf.size) buf else buf.copyOf(len)
                try {
                    val r = call("PUT", session, chunk, mapOf("Content-Range" to "bytes $offset-${offset + len - 1}/$total"))
                    when (r.code) {
                        200, 201 -> return JSONObject(r.body).getString("id")
                        308 -> {
                            offset = nextOffset(r)
                            failures = 0
                            onProgress(((offset * 100) / total).toInt())
                        }
                        else -> throw IOException("Upload failed (${r.code}): ${r.body.take(200)}")
                    }
                } catch (e: IOException) {
                    failures++
                    if (failures >= 6) throw e
                    Thread.sleep(3000L * failures)
                    val p = try { probe(session, total) } catch (_: IOException) { continue }
                    if (!p.valid) {
                        prefs.setSession(file.name, null)
                        throw IOException("Upload session expired, will restart")
                    }
                    if (p.doneJson != null) return JSONObject(p.doneJson).getString("id")
                    offset = p.offset
                }
            }
        }
        throw IOException("Upload ended unexpectedly")
    }
}
