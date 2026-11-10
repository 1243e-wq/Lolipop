package com.pocketcctv.app

import android.content.Context
import android.os.Build
import kotlin.random.Random

/** Small settings store shared by every screen. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pocketcctv", Context.MODE_PRIVATE)

    var cameraName: String
        get() = sp.getString("cameraName", null) ?: (Build.MODEL ?: "Camera")
        set(v) { sp.edit().putString("cameraName", v).apply() }

    /** Green night vision (default ON). */
    var nightVision: Boolean
        get() = sp.getBoolean("night", true)
        set(v) { sp.edit().putBoolean("night", v).apply() }

    var landscape: Boolean
        get() = sp.getBoolean("landscape", true)
        set(v) { sp.edit().putBoolean("landscape", v).apply() }

    var wifiOnlyUpload: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) { sp.edit().putBoolean("wifiOnly", v).apply() }

    /** How many days recordings are kept (default 3). */
    var retentionDays: Int
        get() = sp.getInt("days", 3)
        set(v) { sp.edit().putInt("days", v).apply() }

    var lastUploadMillis: Long
        get() = sp.getLong("lastUpload", 0L)
        set(v) { sp.edit().putLong("lastUpload", v).apply() }

    var driveFolderId: String?
        get() = sp.getString("driveFolder", null)
        set(v) { sp.edit().putString("driveFolder", v).apply() }

    // Monitor side
    var greenView: Boolean
        get() = sp.getBoolean("greenView", true)
        set(v) { sp.edit().putBoolean("greenView", v).apply() }

    var monitorPin: String
        get() = sp.getString("monitorPin", "") ?: ""
        set(v) { sp.edit().putString("monitorPin", v).apply() }

    var lastHost: String
        get() = sp.getString("lastHost", "") ?: ""
        set(v) { sp.edit().putString("lastHost", v).apply() }

    /** 4-digit pairing PIN the monitor must know. Generated once. */
    val pin: String
        get() {
            var p = sp.getString("pin", null)
            if (p == null) {
                p = "%04d".format(Random.nextInt(10000))
                sp.edit().putString("pin", p).apply()
            }
            return p
        }

    // Resumable-upload session addresses, so a dropped connection continues where it stopped.
    fun session(fileName: String): String? = sp.getString("sess_$fileName", null)
    fun setSession(fileName: String, uri: String?) {
        val e = sp.edit()
        if (uri == null) e.remove("sess_$fileName") else e.putString("sess_$fileName", uri)
        e.apply()
    }

    companion object {
        /** Each recording file is exactly 2 hours long. */
        const val SEGMENT_MS = 2L * 60 * 60 * 1000
    }
}
