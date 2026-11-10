package com.pocketcctv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pocketcctv.app.databinding.ActivityMonitorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorActivity : AppCompatActivity() {

    private lateinit var b: ActivityMonitorBinding
    private lateinit var prefs: Prefs
    private var client: MjpegClient? = null
    private var finder: NsdFinder? = null
    private var statusJob: Job? = null
    private val cameras = linkedMapOf<String, CameraEndpoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.etPin.setText(prefs.monitorPin)
        b.etHost.setText(prefs.lastHost)
        b.swGreen.isChecked = prefs.greenView
        applyTint()
        b.swGreen.setOnCheckedChangeListener { _, on ->
            prefs.greenView = on
            applyTint()
        }
        b.btnConnect.setOnClickListener { connectFromFields() }
        b.btnRecordings.setOnClickListener { startActivity(Intent(this, PlaybackActivity::class.java)) }
        rebuildList()
    }

    override fun onStart() {
        super.onStart()
        finder = NsdFinder(
            this,
            onFound = { cam -> runOnUiThread { cameras[cam.name] = cam; rebuildList() } },
            onLost = { name -> runOnUiThread { cameras.remove(name); rebuildList() } }
        ).also { it.start() }

        if (client == null && prefs.lastHost.isNotBlank() && prefs.monitorPin.isNotBlank()) connectFromFields()
    }

    override fun onStop() {
        super.onStop()
        client?.stop()
        client = null
        statusJob?.cancel()
        finder?.stop()
        finder = null
    }

    private fun applyTint() {
        b.ivLive.colorFilter = if (prefs.greenView) NightVision.greenFilter() else null
    }

    private fun connectFromFields() {
        val raw = b.etHost.text.toString().trim()
        val pin = b.etPin.text.toString().trim()
        if (raw.isEmpty()) {
            toast("Pick a camera from the list, or type its address.")
            return
        }
        if (pin.length != 4) {
            toast("Type the 4-digit PIN shown on the camera.")
            return
        }
        val host = raw.substringBefore(':')
        val port = raw.substringAfter(':', "").toIntOrNull() ?: MjpegServer.PORT
        prefs.monitorPin = pin
        prefs.lastHost = raw
        connect(host, port, pin)
    }

    private fun connect(host: String, port: Int, pin: String) {
        client?.stop()
        statusJob?.cancel()
        b.tvState.visibility = View.VISIBLE
        client = MjpegClient(
            "http://$host:$port/stream?pin=$pin",
            onFrame = { bmp -> runOnUiThread { b.ivLive.setImageBitmap(bmp) } },
            onState = { s ->
                runOnUiThread {
                    b.tvState.text = s
                    b.tvState.visibility = if (s == "Live") View.GONE else View.VISIBLE
                }
            }
        ).also { it.start() }

        statusJob = lifecycleScope.launch {
            while (true) {
                val s = withContext(Dispatchers.IO) { MjpegClient.fetchStatus(host, port, pin) }
                b.tvInfo.text = s ?: "Camera not reachable"
                delay(5000)
            }
        }
    }

    private fun rebuildList() {
        b.cameraList.removeAllViews()
        if (cameras.isEmpty()) {
            b.cameraList.addView(TextView(this).apply {
                text = "Looking for cameras on your Wi-Fi…"
                setTextColor(getColor(R.color.text_soft))
            })
            return
        }
        for (cam in cameras.values) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📷  ${cam.name}"
                setOnClickListener {
                    b.etHost.setText("${cam.host}:${cam.port}")
                    connectFromFields()
                }
            }
            b.cameraList.addView(
                btn,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
