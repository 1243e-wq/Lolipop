package com.pocketcctv.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.pocketcctv.app.databinding.ActivityCameraBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private lateinit var prefs: Prefs

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                RecorderService.start(this)
            } else {
                toast("Camera permission is needed to record.")
            }
        }

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
                toast("Signed in ✓")
                UploadScheduler.kick(this)
            } catch (e: ApiException) {
                toast(
                    when (e.statusCode) {
                        10 -> "Google setup is not finished yet (error 10). See README step 2."
                        12501 -> "Sign-in cancelled."
                        else -> "Sign-in failed (code ${e.statusCode})."
                    }
                )
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.etName.setText(prefs.cameraName)
        b.etName.doAfterTextChanged { prefs.cameraName = it?.toString()?.trim().orEmpty().ifEmpty { "Camera" } }

        b.swNight.isChecked = prefs.nightVision
        b.swNight.setOnCheckedChangeListener { _, on ->
            prefs.nightVision = on
            restartHint()
        }
        b.swLandscape.isChecked = prefs.landscape
        b.swLandscape.setOnCheckedChangeListener { _, on ->
            prefs.landscape = on
            restartHint()
        }
        b.swWifiOnly.isChecked = prefs.wifiOnlyUpload
        b.swWifiOnly.setOnCheckedChangeListener { _, on ->
            prefs.wifiOnlyUpload = on
            UploadScheduler.schedulePeriodic(this)
        }

        b.sliderDays.value = prefs.retentionDays.toFloat().coerceIn(1f, 14f)
        b.tvDays.text = daysText(prefs.retentionDays)
        b.sliderDays.addOnChangeListener { _, v, _ ->
            prefs.retentionDays = v.toInt()
            b.tvDays.text = daysText(v.toInt())
        }

        b.btnRecord.setOnClickListener {
            if (CctvState.running.value) RecorderService.stop(this) else requestAndStart()
        }
        b.btnSignIn.setOnClickListener {
            if (DriveAuth.account(this) == null) {
                signInLauncher.launch(DriveAuth.client(this).signInIntent)
            } else {
                DriveAuth.client(this).signOut().addOnCompleteListener { refresh() }
            }
        }
        b.btnBackupNow.setOnClickListener {
            if (DriveAuth.account(this) == null) {
                toast("Sign in with Google first.")
            } else {
                UploadScheduler.kick(this, replace = true)
                toast("Backup started")
            }
        }
        b.btnBattery.setOnClickListener { requestBatteryExemption() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (needed.isEmpty()) RecorderService.start(this) else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun restartHint() {
        if (CctvState.running.value) toast("Stop and start the camera to apply this change.")
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed ✓")
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun daysText(d: Int) = "Keep recordings for $d day" + if (d == 1) "" else "s"

    private fun refresh() {
        val running = CctvState.running.value
        val recording = CctvState.recording.value

        b.btnRecord.text = if (running) "⏹  Stop camera" else "▶  Start camera"
        b.tvStatus.text = when {
            recording -> "🔴 Recording"
            running -> "🟡 Starting…"
            else -> "⚪ Camera is off"
        }

        val msg = CctvState.message.value
        b.tvClip.text = when {
            msg.isNotBlank() -> msg
            recording -> {
                val s = ((System.currentTimeMillis() - CctvState.segmentStartMillis.value) / 1000).coerceAtLeast(0)
                "Current 2-hour clip: %02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
            }
            else -> "Tap Start camera to begin. Clips are saved every 2 hours."
        }
        b.tvViewers.text = "Watching right now: ${CctvState.viewers.value}"

        b.tvPin.text = prefs.pin
        b.tvAddress.text = "${NetUtil.localIp() ?: "no Wi-Fi"}:${CctvState.port.value}"

        val acc = DriveAuth.account(this)
        b.tvDriveAccount.text = if (acc != null) "Signed in as ${acc.email}" else "Not signed in"
        b.btnSignIn.text = if (acc != null) "Sign out" else "Sign in with Google"

        val pending = Storage.pending(this).size
        b.tvDriveStatus.text = buildString {
            append(if (pending == 0) "Nothing waiting for backup" else "$pending clip(s) waiting for backup")
            val st = CctvState.uploadStatus.value
            if (st.isNotBlank()) append("\n").append(st)
        }
        val last = prefs.lastUploadMillis
        b.tvLastBackup.text = if (last == 0L) "Last backup: none yet"
        else "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
