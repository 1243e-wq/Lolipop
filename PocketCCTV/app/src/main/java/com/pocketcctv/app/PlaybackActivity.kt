package com.pocketcctv.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.pocketcctv.app.databinding.ActivityPlaybackBinding
import com.pocketcctv.app.databinding.ItemRecordingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlaybackBinding
    private lateinit var prefs: Prefs
    private lateinit var drive: DriveClient
    private var player: ExoPlayer? = null
    private var current: DriveClient.RemoteFile? = null
    private var retries = 0
    private val adapter = RecAdapter { play(it, 0L, false) }

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
                toast("Signed in ✓")
            } catch (e: ApiException) {
                toast(
                    if (e.statusCode == 10) "Google setup is not finished yet (error 10). See README step 2."
                    else "Sign-in failed (code ${e.statusCode})."
                )
            }
            updateAccount()
            load()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        drive = DriveClient(this, prefs)

        b.rvRecordings.layoutManager = LinearLayoutManager(this)
        b.rvRecordings.adapter = adapter

        b.swGreen.isChecked = prefs.greenView
        b.swGreen.setOnCheckedChangeListener { _, on ->
            prefs.greenView = on
            applyTint()
        }
        b.btnSignIn.setOnClickListener {
            if (DriveAuth.account(this) == null) {
                signInLauncher.launch(DriveAuth.client(this).signInIntent)
            } else {
                DriveAuth.client(this).signOut().addOnCompleteListener { updateAccount(); load() }
            }
        }
        b.btnRefresh.setOnClickListener { load() }

        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Most common cause: the Google access token expired mid-video. Get a fresh one and resume.
                val f = current
                if (f != null && retries < 4) {
                    retries++
                    play(f, p.currentPosition, true)
                } else {
                    toast("Could not play this clip.")
                }
            }
        })
        b.playerView.player = p
        player = p
        applyTint()
        updateAccount()
        load()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun applyTint() {
        val tv = b.playerView.videoSurfaceView as? TextureView ?: return
        if (prefs.greenView) tv.setLayerType(View.LAYER_TYPE_HARDWARE, NightVision.greenPaint())
        else tv.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun updateAccount() {
        val acc = DriveAuth.account(this)
        b.tvAccount.text = if (acc != null) "Google Drive: ${acc.email}" else "Not signed in to Google"
        b.btnSignIn.text = if (acc != null) "Sign out" else "Sign in with Google"
    }

    private fun load() {
        if (DriveAuth.account(this) == null) {
            adapter.items = emptyList()
            b.tvEmpty.visibility = View.VISIBLE
            b.tvEmpty.text = "Sign in with the same Google account you used on the camera phone."
            return
        }
        b.tvEmpty.visibility = View.VISIBLE
        b.tvEmpty.text = "Loading…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { drive.listRecordings() } }
            result.onSuccess { list ->
                adapter.items = list
                b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) {
                    b.tvEmpty.text = "No recordings yet. Each 2-hour clip shows up here after it is backed up."
                }
            }.onFailure {
                b.tvEmpty.visibility = View.VISIBLE
                b.tvEmpty.text = "Could not load recordings: ${it.message}"
            }
        }
    }

    private fun play(f: DriveClient.RemoteFile, startMs: Long, refreshToken: Boolean) {
        if (!refreshToken) retries = 0
        current = f
        lifecycleScope.launch {
            val token = try {
                withContext(Dispatchers.IO) { drive.accessToken(force = refreshToken) }
            } catch (e: Exception) {
                toast("Sign-in problem: ${e.message}")
                return@launch
            }
            val factory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            val item = MediaItem.fromUri("https://www.googleapis.com/drive/v3/files/${f.id}?alt=media")
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(item)
            player?.apply {
                setMediaSource(source, startMs)
                prepare()
                playWhenReady = true
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- list ----------

    private class RecAdapter(val onClick: (DriveClient.RemoteFile) -> Unit) :
        RecyclerView.Adapter<RecAdapter.VH>() {

        var items: List<DriveClient.RemoteFile> = emptyList()
            @SuppressLint("NotifyDataSetChanged")
            set(v) {
                field = v
                notifyDataSetChanged()
            }

        class VH(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            val (title, sub) = describe(f)
            h.b.tvTitle.text = title
            h.b.tvSub.text = sub
            h.b.root.setOnClickListener { onClick(f) }
        }

        private val nameRe = Regex("^(.*)_(\\d{8})_(\\d{6})\\.mp4$")

        private fun describe(f: DriveClient.RemoteFile): Pair<String, String> {
            val mb = f.size / (1024 * 1024)
            val m = nameRe.find(f.name) ?: return f.name to "$mb MB"
            val cam = m.groupValues[1].replace('-', ' ')
            val date = try {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.groupValues[2] + "_" + m.groupValues[3])
            } catch (e: Exception) {
                null
            }
            val pretty = date?.let { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(it) }
                ?: m.groupValues[2]
            return pretty to "📷 $cam  ·  $mb MB"
        }
    }
}
