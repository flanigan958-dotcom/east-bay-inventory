package com.rayneo.spatial.media

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rayneo.spatial.R

/**
 * Local-file SBS player for the Air 4 Pro external display.
 * It preserves a full side-by-side source frame instead of attempting to manufacture 3D.
 * The glasses' own 3D mode, when required by firmware, remains a hardware control.
 */
class SbsPlayerActivity : AppCompatActivity(), DisplayManager.DisplayListener {
    private lateinit var displayManager: DisplayManager
    private lateinit var status: TextView
    private lateinit var playPause: Button
    private var selectedUri: Uri? = null
    private var outputMode = SbsOutputMode.NATIVE_SBS
    private var presentation: SbsPlaybackPresentation? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedUri = uri
        status.text = "Selected: ${uri.lastPathSegment ?: "video"}"
        startPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, null)
        setContentView(buildUi())
        updateDisplayStatus()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        updateDisplayStatus()
        if (selectedUri != null) startPlayback()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (presentation?.display?.displayId == displayId) {
            presentation?.dismiss()
            presentation = null
        }
        updateDisplayStatus()
    }

    override fun onDisplayChanged(displayId: Int) = updateDisplayStatus()

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 28, 28, 28)
        setBackgroundColor(Color.rgb(14, 17, 23))

        addView(TextView(context).apply {
            text = "3D / SBS Player"
            setTextColor(Color.WHITE)
            textSize = 26f
        }, match())

        addView(TextView(context).apply {
            text = "For full side-by-side video. This app preserves the SBS frame and sends it directly to the glasses display. It does not fake 3D from ordinary 2D video."
            setTextColor(Color.LTGRAY)
            textSize = 14f
        }, match(top = 10, bottom = 18))

        status = TextView(context).apply {
            setTextColor(Color.rgb(160, 210, 255))
            textSize = 14f
        }
        addView(status, match(bottom = 14))

        addView(Button(context).apply {
            text = "Choose SBS Video"
            setOnClickListener { picker.launch(arrayOf("video/*")) }
        }, match(bottom = 10))

        playPause = Button(context).apply {
            text = "Play / Pause"
            isEnabled = false
            setOnClickListener { presentation?.togglePlayback() }
        }
        addView(playPause, match(bottom = 14))

        addView(TextView(context).apply {
            text = "Output"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, match(bottom = 6))

        addView(outputButton("Native SBS", SbsOutputMode.NATIVE_SBS), match(bottom = 6))
        addView(outputButton("Left Eye 2D", SbsOutputMode.LEFT_EYE_2D), match(bottom = 6))
        addView(outputButton("Right Eye 2D", SbsOutputMode.RIGHT_EYE_2D), match(bottom = 14))

        addView(Button(context).apply {
            text = "Re-scan Glasses"
            setOnClickListener {
                updateDisplayStatus()
                startPlayback()
            }
        }, match())
    }

    private fun outputButton(label: String, mode: SbsOutputMode) = Button(this).apply {
        text = label
        setOnClickListener {
            outputMode = mode
            presentation?.setOutputMode(mode)
            Toast.makeText(this@SbsPlayerActivity, "$label selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun match(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun preferredExternalDisplay(): Display? {
        val candidates = displayManager.displays.filter { display ->
            display.displayId != Display.DEFAULT_DISPLAY && display.isValid
        }
        return candidates.maxByOrNull { display ->
            val mode = display.mode
            mode.physicalWidth.toLong() * mode.physicalHeight.toLong()
        }
    }

    private fun updateDisplayStatus() {
        val display = preferredExternalDisplay()
        status.text = if (display == null) {
            "Glasses display not detected. Connect the Air 4 Pro, then re-scan."
        } else {
            val mode = display.mode
            "Glasses detected: ${mode.physicalWidth}×${mode.physicalHeight} @ ${"%.1f".format(mode.refreshRate)} Hz"
        }
        playPause.isEnabled = selectedUri != null && display != null
    }

    private fun startPlayback() {
        val uri = selectedUri ?: return
        val display = preferredExternalDisplay() ?: run {
            updateDisplayStatus()
            return
        }
        presentation?.dismiss()
        presentation = SbsPlaybackPresentation(this, display, uri, outputMode).also { p ->
            p.show()
            requestBestDisplayMode(p, display)
        }
        playPause.isEnabled = true
    }

    private fun requestBestDisplayMode(presentation: Presentation, display: Display) {
        val best = display.supportedModes.maxWithOrNull(
            compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
                .thenBy { it.refreshRate }
        ) ?: return
        presentation.window?.attributes = presentation.window?.attributes?.apply {
            preferredDisplayModeId = best.modeId
        }
    }
}

private enum class SbsOutputMode {
    NATIVE_SBS,
    LEFT_EYE_2D,
    RIGHT_EYE_2D
}

private class SbsPlaybackPresentation(
    context: Context,
    display: Display,
    private val uri: Uri,
    initialMode: SbsOutputMode
) : Presentation(context, display), TextureView.SurfaceTextureListener, MediaPlayer.OnVideoSizeChangedListener {
    private lateinit var texture: TextureView
    private lateinit var overlay: TextView
    private var player: MediaPlayer? = null
    private var mode = initialMode
    private var videoWidth = 0
    private var videoHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        texture = TextureView(context).apply {
            surfaceTextureListener = this@SbsPlaybackPresentation
        }
        overlay = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        root.addView(texture, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlay, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 16 })
        setContentView(root)
        updateOverlay()
    }

    fun setOutputMode(newMode: SbsOutputMode) {
        mode = newMode
        updateTransform()
        updateOverlay()
    }

    fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        createPlayer(uri)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onVideoSizeChanged(mp: MediaPlayer, width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        updateTransform()
    }

    private fun createPlayer(uri: Uri) {
        releasePlayer()
        val surfaceTexture = texture.surfaceTexture ?: return
        val surface = Surface(surfaceTexture)
        player = MediaPlayer().apply {
            setSurface(surface)
            surface.release()
            setDataSource(context, uri)
            setOnVideoSizeChangedListener(this@SbsPlaybackPresentation)
            setOnPreparedListener {
                this@SbsPlaybackPresentation.videoWidth = it.videoWidth
                this@SbsPlaybackPresentation.videoHeight = it.videoHeight
                updateTransform()
                it.start()
            }
            setOnCompletionListener { completed -> completed.seekTo(0) }
            prepareAsync()
        }
    }

    private fun updateTransform() {
        if (!::texture.isInitialized || texture.width <= 0 || texture.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val viewW = texture.width.toFloat()
        val viewH = texture.height.toFloat()
        val sourceW = videoWidth.toFloat()
        val sourceH = videoHeight.toFloat()
        val matrix = Matrix()

        when (mode) {
            SbsOutputMode.NATIVE_SBS -> {
                val scale = minOf(viewW / sourceW, viewH / sourceH)
                val scaledW = sourceW * scale
                val scaledH = sourceH * scale
                matrix.setScale(scale, scale)
                matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
            }
            SbsOutputMode.LEFT_EYE_2D, SbsOutputMode.RIGHT_EYE_2D -> {
                val eyeW = sourceW / 2f
                val scale = minOf(viewW / eyeW, viewH / sourceH)
                val scaledEyeW = eyeW * scale
                val scaledH = sourceH * scale
                matrix.setScale(scale, scale)
                val sourceOffset = if (mode == SbsOutputMode.RIGHT_EYE_2D) -eyeW * scale else 0f
                matrix.postTranslate(sourceOffset + (viewW - scaledEyeW) / 2f, (viewH - scaledH) / 2f)
            }
        }
        texture.setTransform(matrix)
    }

    private fun updateOverlay() {
        overlay.text = when (mode) {
            SbsOutputMode.NATIVE_SBS -> "Native SBS"
            SbsOutputMode.LEFT_EYE_2D -> "Left eye preview"
            SbsOutputMode.RIGHT_EYE_2D -> "Right eye preview"
        }
    }

    private fun releasePlayer() {
        player?.runCatching {
            stop()
            reset()
            release()
        }
        player = null
    }

    override fun dismiss() {
        releasePlayer()
        super.dismiss()
    }
}
