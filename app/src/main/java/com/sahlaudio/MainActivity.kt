package com.sahlaudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val BG = 0xFF101714.toInt()
private const val PANEL = 0xFF1B2620.toInt()
private const val GREEN = 0xFF63D69A.toInt()
private const val GOLD = 0xFFE8C879.toInt()
private const val WHITE = 0xFFF2F5F2.toInt()
private const val MUTED = 0xFF9BAAA0.toInt()

private const val REQUEST_AUDIO = 1001
private const val REQUEST_IMPORT_PROJECT = 1002
private const val REQUEST_EXPORT_PROJECT = 1003
private const val REQUEST_MICROPHONE = 1004

data class AudioClip(
    val id: Long,
    val uri: String,
    var name: String,
    var track: Int,
    var startMs: Long,
    var durationMs: Long,
    var trimStartMs: Long = 0L,
    var trimEndMs: Long = durationMs,
    var volume: Float = 1f,
    var muted: Boolean = false,
    var color: Int = 0xFF276B4B.toInt()
) {
    fun lengthMs(): Long =
        (trimEndMs - trimStartMs).coerceAtLeast(0L)

    fun endMs(): Long = startMs + lengthMs()
}

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var horizontal: HorizontalScrollView
    private lateinit var status: TextView
    private lateinit var playLabel: TextView
    private lateinit var volumeSeek: SeekBar

    private val clips = mutableListOf<AudioClip>()
    private var tracks = 3
    private var playheadMs = 0L
    private var zoom = 70f
    private var selectedId: Long? = null

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var isPlaying = false
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private val undoStack = java.util.ArrayDeque<String>()
    private val redoStack = java.util.ArrayDeque<String>()

    private val prefs by lazy {
        getSharedPreferences("sahl_audio_project", Context.MODE_PRIVATE)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeLabel(
        text: String,
        size: Float = 14f,
        color: Int = WHITE
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
        }
    }

    private fun makeButton(
        text: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(WHITE)
            setBackgroundColor(PANEL)
            setOnClickListener { action() }
        }
    }

    private fun makeRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        buildUi()
        restoreLocalProject()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }

        root.addView(
            makeLabel("بسم الله الرحمن الرحيم", 17f, GOLD),
            LinearLayout.LayoutParams(-1, dp(30f))
        )

        root.addView(
            makeLabel("سهل الصوت • محرر صوت متعدد المسارات", 18f, GREEN),
            LinearLayout.LayoutParams(-1, dp(40f))
        )

        status = makeLabel("أضف ملفًا صوتيًا لبدء المشروع", 12f, MUTED)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(32f)))

        val row1 = makeRow()
        row1.addView(makeButton("＋ إضافة صوت") { chooseAudio() })
        row1.addView(makeButton("▶ تشغيل") { togglePlayback() })
        row1.addView(makeButton("■ إيقاف") { stopPlayback() })
        root.addView(row1)

        val row2 = makeRow()
        row2.addView(makeButton("✂ تقسيم") { splitSelected() })
        row2.addView(makeButton("⧉ تكرار") { duplicateSelected() })
        row2.addView(makeButton("حذف") { deleteSelected() })
        root.addView(row2)

        val row3 = makeRow()
        row3.addView(makeButton("إعادة تسمية") { renameSelected() })
        row3.addView(makeButton("لون المقطع") { chooseClipColor() })
        row3.addView(makeButton("بداية المقطع") { jumpToSelectedStart() })
        root.addView(row3)

        val row4 = makeRow()
        row4.addView(makeButton("＋ مسار") {
            rememberUndo()
            tracks = (tracks + 1).coerceAtMost(20)
            refreshTimeline()
            saveLocalProject()
        })
        row4.addView(makeButton("↶ تراجع") { undo() })
        row4.addView(makeButton("↷ إعادة") { redo() })
        root.addView(row4)

        val zoomRow = makeRow()
        zoomRow.addView(makeButton("−") {
            zoom = (zoom / 1.25f).coerceAtLeast(8f)
            refreshTimeline()
        })
        zoomRow.addView(makeLabel("التكبير", 13f, GOLD))
        zoomRow.addView(makeButton("＋") {
            zoom = (zoom * 1.25f).coerceAtMost(240f)
            refreshTimeline()
        })
        root.addView(zoomRow)

        val projectRow = makeRow()
        projectRow.addView(makeButton("حفظ") { saveLocalProject() })
        projectRow.addView(makeButton("تصدير المشروع") { exportProject() })
        projectRow.addView(makeButton("استيراد مشروع") { importProject() })
        root.addView(projectRow)

        val recordingRow = makeRow()
        recordingRow.addView(makeButton("🎙 تسجيل") { toggleRecording() })
        root.addView(recordingRow)

        playLabel = makeLabel("موضع التشغيل: 00:00.000", 13f, GOLD)
        root.addView(playLabel, LinearLayout.LayoutParams(-1, dp(30f)))

        horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(0xFF0C120F.toInt())
        }

        timeline = TimelineView(this)
        horizontal.addView(
            timeline,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val vertical = ScrollView(this).apply {
            setBackgroundColor(0xFF0C120F.toInt())
            isFillViewport = false
            addView(
                horizontal,
                ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(vertical, LinearLayout.LayoutParams(0, 0, 1f))

        val editRow = makeRow()
        editRow.addView(makeButton("قص البداية") { trimStartAtPlayhead() })
        editRow.addView(makeButton("قص النهاية") { trimEndAtPlayhead() })
        root.addView(editRow)

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        volumeRow.addView(makeLabel("مستوى الصوت", 12f))

        volumeSeek = SeekBar(this).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return

                        val clip = selectedClip() ?: return
                        clip.volume = progress / 100f
                        refreshTimeline()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        rememberUndo()
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        saveLocalProject()
                    }
                }
            )
        }

        volumeRow.addView(
            volumeSeek,
            LinearLayout.LayoutParams(0, dp(42f), 1f)
        )

        volumeRow.addView(makeButton("كتم/إلغاء") { toggleMute() })
        root.addView(volumeRow)

        setContentView(root)
    }

    private fun showStatus(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1000L) % 60L
        val millis = safe % 1000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun projectEndMs(): Long =
        max(10_000L, clips.maxOfOrNull { it.endMs() } ?: 10_000L)

    private fun selectedClip(): AudioClip? =
        clips.firstOrNull { it.id == selectedId }

    private fun refreshTimeline() {
        timeline.invalidate()
        timeline.requestLayout()

        playLabel.text = "موضع التشغيل: ${formatTime(playheadMs)}"

        selectedClip()?.let { clip ->
            status.text = "${clip.name} • المسار ${clip.track + 1}"
            volumeSeek.progress = (clip.volume * 100).toInt().coerceIn(0, 100)
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        startActivityForResult(intent, REQUEST_AUDIO)
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) {
                    cursor.getString(index) ?: "مقطع صوتي"
                } else {
                    "مقطع صوتي"
                }
            } ?: "مقطع صوتي"
        } catch (_: Exception) {
            "مقطع صوتي"
        }
    }

    private fun getDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_AUDIO -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                val duration = getDuration(uri)
                if (duration <= 0L) {
                    showStatus("تعذرت قراءة مدة الملف الصوتي")
                    return
                }

                rememberUndo()

                val clip = AudioClip(
                    id = System.nanoTime(),
                    uri = uri.toString(),
                    name = queryName(uri),
                    track = 0,
                    startMs = playheadMs,
                    durationMs = duration
                )

                clips.add(clip)
                selectedId = clip.id
                refreshTimeline()
                saveLocalProject()
                showStatus("تمت إضافة ${clip.name}")
            }

            REQUEST_IMPORT_PROJECT -> {
                try {
                    val json = contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }

                    if (json.isNullOrBlank()) {
                        showStatus("ملف المشروع فارغ")
                    } else {
                        rememberUndo()
                        loadProjectJson(json)
                        saveLocalProject()
                        showStatus("تم استيراد المشروع")
                    }
                } catch (e: Exception) {
                    showStatus("تعذر استيراد المشروع: ${e.message}")
                }
            }

            REQUEST_EXPORT_PROJECT -> {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(projectJson())
                    } ?: throw IllegalStateException("تعذر فتح الملف")

                    showStatus("تم تصدير المشروع")
                } catch (e: Exception) {
                    showStatus("فشل التصدير: ${e.message}")
                }
            }
        }
    }

    private fun splitSelected() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        val offset = playheadMs - clip.startMs

        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع لتقسيمه")
            return
        }

        rememberUndo()

        val splitSource = clip.trimStartMs + offset
        val right = clip.copy(
            id = System.nanoTime(),
            name = "${clip.name} (2)",
            startMs = playheadMs,
            trimStartMs = splitSource,
            trimEndMs = clip.trimEndMs
        )

        clip.trimEndMs = splitSource
        clips.add(right)
        selectedId = right.id

        refreshTimeline()
        saveLocalProject()
    }

    private fun duplicateSelected() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        rememberUndo()

        val copy = clip.copy(
            id = System.nanoTime(),
            name = "${clip.name} - نسخة",
            startMs = clip.endMs()
        )

        clips.add(copy)
        selectedId = copy.id
        refreshTimeline()
        saveLocalProject()
    }

    private fun deleteSelected() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        rememberUndo()
        clips.removeAll { it.id == clip.id }
        selectedId = null
        refreshTimeline()
        saveLocalProject()
        showStatus("تم حذف المقطع")
    }

    private fun renameSelected() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        val input = EditText(this).apply {
            setText(clip.name)
            setSelection(text.length)
            singleLine = true
        }

        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية المقطع")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isNotEmpty()) {
                    rememberUndo()
                    clip.name = name
                    refreshTimeline()
                    saveLocalProject()
                }
            }
            .show()
    }

    private fun chooseClipColor() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        val names = arrayOf(
            "أخضر", "أزرق", "بنفسجي", "برتقالي", "أحمر", "رمادي"
        )

        val colors = intArrayOf(
            0xFF276B4B.toInt(),
            0xFF245A9B.toInt(),
            0xFF7046A8.toInt(),
            0xFFB86A20.toInt(),
            0xFF9D3434.toInt(),
            0xFF69756E.toInt()
        )

        AlertDialog.Builder(this)
            .setTitle("لون المقطع")
            .setItems(names) { _, which ->
                rememberUndo()
                clip.color = colors[which]
                refreshTimeline()
                saveLocalProject()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun jumpToSelectedStart() {
        val clip = selectedClip() ?: return
        playheadMs = clip.startMs
        refreshTimeline()
    }

    private fun trimStartAtPlayhead() {
        val clip = selectedClip() ?: return
        val offset = playheadMs - clip.startMs

        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع")
            return
        }

        rememberUndo()
        clip.trimStartMs += offset
        clip.startMs = playheadMs
        refreshTimeline()
        saveLocalProject()
    }

    private fun trimEndAtPlayhead() {
        val clip = selectedClip() ?: return
        val offset = playheadMs - clip.startMs

        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع")
            return
        }

        rememberUndo()
        clip.trimEndMs = clip.trimStartMs + offset
        refreshTimeline()
        saveLocalProject()
    }

    private fun toggleMute() {
        val clip = selectedClip() ?: return
        rememberUndo()
        clip.muted = !clip.muted
        refreshTimeline()
        saveLocalProject()
    }

    private fun togglePlayback() {
        if (isPlaying) {
            pausePlayback()
            return
        }

        val clip = clips.firstOrNull {
            !it.muted &&
                playheadMs >= it.startMs &&
                playheadMs < it.endMs()
        } ?: clips.firstOrNull {
            !it.muted && it.startMs >= playheadMs
        }

        if (clip == null) {
            showStatus("لا يوجد مقطع قابل للتشغيل عند هذا الموضع")
            return
        }

        releasePlayer()

        try {
            val newPlayer = MediaPlayer()
            player = newPlayer

            newPlayer.setDataSource(this, Uri.parse(clip.uri))

            newPlayer.setOnPreparedListener { prepared ->
                if (player !== prepared) {
                    prepared.release()
                    return@setOnPreparedListener
                }

                val offset = (playheadMs - clip.startMs).coerceAtLeast(0L)
                val sourcePosition = clip.trimStartMs + offset

                prepared.setVolume(
                    if (clip.muted) 0f else clip.volume,
                    if (clip.muted) 0f else clip.volume
                )

                prepared.seekTo(
                    sourcePosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )

                prepared.start()
                isPlaying = true
                showStatus("جارٍ تشغيل: ${clip.name}")
                handler.removeCallbacks(playbackTicker)
                handler.post(playbackTicker)
            }

            newPlayer.setOnCompletionListener {
                isPlaying = false
                playheadMs = clip.endMs()
                handler.removeCallbacks(playbackTicker)
                refreshTimeline()
                releasePlayer()
            }

            newPlayer.setOnErrorListener { _, _, _ ->
                isPlaying = false
                handler.removeCallbacks(playbackTicker)
                showStatus("تعذر تشغيل هذا الملف الصوتي")
                releasePlayer()
                true
            }

            newPlayer.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            showStatus("تعذر التشغيل: ${e.message}")
        }
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return

            try {
                val current = player
                if (current != null && current.isPlaying) {
                    val clip = selectedClip()
                    if (clip != null) {
                        playheadMs = clip.startMs +
                            (current.currentPosition - clip.trimStartMs)
                                .coerceAtLeast(0L)
                    }
                    refreshTimeline()
                }
            } catch (_: Exception) {
            }

            handler.postDelayed(this, 100L)
        }
    }

    private fun pausePlayback() {
        try {
            player?.pause()
        } catch (_: Exception) {
        }

        isPlaying = false
        handler.removeCallbacks(playbackTicker)
        showStatus("تم الإيقاف المؤقت")
    }

    private fun releasePlayer() {
        try {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.release()
        } catch (_: Exception) {
        }

        player = null
        isPlaying = false
        handler.removeCallbacks(playbackTicker)
    }

    private fun stopPlayback() {
        releasePlayer()
        playheadMs = 0L
        refreshTimeline()
        showStatus("تم إيقاف التشغيل")
    }

    private fun toggleRecording() {
        if (isRecording) {
            finishRecording()
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MICROPHONE
            )
            return
        }

        startRecording()
    }

    private fun startRecording() {
        val file = File(
            filesDir,
            "recording_${System.currentTimeMillis()}.m4a"
        )

        var newRecorder: MediaRecorder? = null

        try {
            newRecorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()

            recorder = newRecorder
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
            isRecording = true

            showStatus("جارٍ التسجيل؛ اضغط الزر لإنهائه")
        } catch (e: Exception) {
            try {
                newRecorder?.release()
            } catch (_: Exception) {
            }

            recorder = null
            isRecording = false
            showStatus("تعذر بدء التسجيل: ${e.message}")
        }
    }

    private fun finishRecording() {
        val elapsed = System.currentTimeMillis() - recordingStartedAt
        var success = false

        try {
            recorder?.stop()
            success = true
        } catch (_: Exception) {
            showStatus("كان التسجيل قصيرًا جدًا أو تعذر حفظه")
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            recorder = null
            isRecording = false
        }

        val file = recordingFile
        recordingFile = null

        if (!success || file == null || !file.exists() || file.length() == 0L) {
            return
        }

        rememberUndo()

        val clip = AudioClip(
            id = System.nanoTime(),
            uri = Uri.fromFile(file).toString(),
            name = "تسجيل جديد",
            track = 0,
            startMs = playheadMs,
            durationMs = elapsed.coerceAtLeast(1L)
        )

        clips.add(clip)
        selectedId = clip.id
        refreshTimeline()
        saveLocalProject()
        showStatus("تم حفظ التسجيل وإضافته للمشروع")
    }

    private fun rememberUndo() {
        undoStack.addLast(projectJson())

        while (undoStack.size > 30) {
            undoStack.removeFirst()
        }

        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            showStatus("لا توجد خطوة للتراجع")
            return
        }

        redoStack.addLast(projectJson())
        loadProjectJson(undoStack.removeLast())
        saveLocalProject()
        showStatus("تم التراجع")
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            showStatus("لا توجد خطوة للإعادة")
            return
        }

        undoStack.addLast(projectJson())
        loadProjectJson(redoStack.removeLast())
        saveLocalProject()
        showStatus("تمت إعادة الخطوة")
    }

    private fun projectJson(): String {
        val obj = JSONObject()
        obj.put("version", 1)
        obj.put("tracks", tracks)
        obj.put("playheadMs", playheadMs)
        obj.put("zoom", zoom.toDouble())

        val array = JSONArray()

        clips.forEach { clip ->
            array.put(
                JSONObject().apply {
                    put("id", clip.id)
                    put("uri", clip.uri)
                    put("name", clip.name)
                    put("track", clip.track)
                    put("startMs", clip.startMs)
                    put("durationMs", clip.durationMs)
                    put("trimStartMs", clip.trimStartMs)
                    put("trimEndMs", clip.trimEndMs)
                    put("volume", clip.volume.toDouble())
                    put("muted", clip.muted)
                    put("color", clip.color)
                }
            )
        }

        obj.put("clips", array)
        return obj.toString()
    }

    private fun loadProjectJson(json: String) {
        try {
            val obj = JSONObject(json)
            val array = obj.optJSONArray("clips") ?: JSONArray()
            val loaded = mutableListOf<AudioClip>()

            for (i in 0 until array.length()) {
                val c = array.optJSONObject(i) ?: continue

                val duration = c.optLong("durationMs", 0L)
                    .coerceAtLeast(0L)

                val trimStart = c.optLong("trimStartMs", 0L)
                    .coerceIn(0L, duration)

                val trimEnd = c.optLong("trimEndMs", duration)
                    .coerceIn(trimStart, duration)

                loaded.add(
                    AudioClip(
                        id = c.optLong("id", System.nanoTime()),
                        uri = c.optString("uri", ""),
                        name = c.optString("name", "مقطع صوتي"),
                        track = c.optInt("track", 0).coerceAtLeast(0),
                        startMs = c.optLong("startMs", 0L).coerceAtLeast(0L),
                        durationMs = duration,
                        trimStartMs = trimStart,
                        trimEndMs = trimEnd,
                        volume = c.optDouble("volume", 1.0)
                            .toFloat().coerceIn(0f, 1f),
                        muted = c.optBoolean("muted", false),
                        color = c.optInt("color", 0xFF276B4B.toInt())
                    )
                )
            }

            clips.clear()
            clips.addAll(loaded)

            tracks = max(
                max(1, obj.optInt("tracks", 3)),
                (clips.maxOfOrNull { it.track } ?: 0) + 1
            ).coerceAtMost(20)

            playheadMs = obj.optLong("playheadMs", 0L).coerceAtLeast(0L)
            zoom = obj.optDouble("zoom", 70.0)
                .toFloat().coerceIn(8f, 240f)

            selectedId = clips.firstOrNull()?.id
            refreshTimeline()
        } catch (e: Exception) {
            showStatus("ملف المشروع غير صالح: ${e.message}")
        }
    }

    private fun saveLocalProject() {
        prefs.edit().putString("project", projectJson()).apply()
        showStatus("تم حفظ المشروع على الجهاز")
    }

    private fun restoreLocalProject() {
        val saved = prefs.getString("project", null) ?: return
        loadProjectJson(saved)
        showStatus("تمت استعادة المشروع المحفوظ")
    }

    private fun exportProject() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "SahlAudio-project.json")
        }

        startActivityForResult(intent, REQUEST_EXPORT_PROJECT)
    }

    private fun importProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }

        startActivityForResult(intent, REQUEST_IMPORT_PROJECT)
    }

    inner class TimelineView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackHeight = dp(86f)
        private val rulerHeight = dp(36f)
        private val leftGutter = dp(62f)

        private var downX = 0f
        private var lastX = 0f
        private var dragMode = "none"
        private var dragId: Long? = null
        private var changed = false
        private var beforeDrag = ""

        private var originalStart = 0L
        private var originalTrimStart = 0L
        private var originalTrimEnd = 0L

        private fun timeToX(ms: Long): Float =
            leftGutter + ms / 1000f * zoom

        private fun xToTime(x: Float): Long =
            (((x - leftGutter) / zoom).coerceAtLeast(0f) * 1000f).toLong()

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val contentWidth = (
                leftGutter + projectEndMs() / 1000f * zoom + dp(120f)
            ).toInt().coerceAtLeast(dp(360f))

            val contentHeight = rulerHeight + tracks * trackHeight + dp(12f)

            setMeasuredDimension(contentWidth, contentHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF0C120F.toInt())

            paint.color = 0xFF17221B.toInt()
            canvas.drawRect(
                0f, 0f, width.toFloat(), rulerHeight.toFloat(), paint
            )

            val end = projectEndMs()
            val step = when {
                zoom >= 120f -> 1000L
                zoom >= 45f -> 2000L
                zoom >= 20f -> 5000L
                else -> 10000L
            }

            var time = 0L

            while (time <= end + step) {
                val x = timeToX(time)

                paint.color = 0xFF35443A.toInt()
                canvas.drawLine(
                    x, rulerHeight.toFloat(), x, height.toFloat(), paint
                )

                paint.color = MUTED
                paint.textSize = dp(10f).toFloat()
                canvas.drawText(
                    formatTime(time).substring(0, 5),
                    x + dp(3f),
                    dp(23f).toFloat(),
                    paint
                )

                time += step
            }

            for (track in 0 until tracks) {
                val top = rulerHeight + track * trackHeight

                paint.color = if (track % 2 == 0) {
                    0xFF111A15.toInt()
                } else {
                    0xFF151F19.toInt()
                }

                canvas.drawRect(
                    0f, top.toFloat(), width.toFloat(),
                    (top + trackHeight).toFloat(), paint
                )

                paint.color = MUTED
                paint.textSize = dp(11f).toFloat()
                canvas.drawText(
                    "مسار ${track + 1}",
                    dp(5f).toFloat(),
                    (top + dp(18f)).toFloat(),
                    paint
                )
            }

            clips.forEach { clip ->
                if (clip.track !in 0 until tracks) return@forEach

                val x1 = timeToX(clip.startMs)
                val x2 = timeToX(clip.endMs())
                val top = rulerHeight + clip.track * trackHeight + dp(8f)
                val bottom = rulerHeight +
                    (clip.track + 1) * trackHeight - dp(8f)

                paint.color = clip.color

                canvas.drawRoundRect(
                    x1, top.toFloat(),
                    max(x1 + dp(10f), x2), bottom.toFloat(),
                    dp(7f).toFloat(), dp(7f).toFloat(), paint
                )

                paint.color = WHITE
                paint.textSize = dp(11f).toFloat()

                canvas.drawText(
                    clip.name.take(20),
                    x1 + dp(6f),
                    top + dp(14f),
                    paint
                )

                if (clip.id == selectedId) {
                    paint.color = GOLD
                    paint.strokeWidth = dp(3f).toFloat()
                    canvas.drawLine(
                        x1, top.toFloat(), x1, bottom.toFloat(), paint
                    )
                    canvas.drawLine(
                        x2, top.toFloat(), x2, bottom.toFloat(), paint
                    )
                }
            }

            val px = timeToX(playheadMs)
            paint.color = 0xFFFF625D.toInt()
            paint.strokeWidth = dp(2f).toFloat()
            canvas.drawLine(px, 0f, px, height.toFloat(), paint)
            canvas.drawCircle(px, dp(5f).toFloat(), dp(5f).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    lastX = event.x
                    changed = false
                    dragId = null

                    beforeDrag = projectJson()

                    val px = timeToX(playheadMs)

                    if (kotlin.math.abs(event.x - px) < dp(18f)) {
                        dragMode = "playhead"
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    if (event.y < rulerHeight) {
                        dragMode = "playhead"
                        playheadMs = xToTime(event.x)
                            .coerceAtMost(projectEndMs())
                        invalidate()
                        return true
                    }

                    val track = (
                        (event.y - rulerHeight) / trackHeight
                    ).toInt().coerceIn(0, tracks - 1)

                    val hit = clips.asReversed().firstOrNull {
                        it.track == track &&
                            event.x >= timeToX(it.startMs) - dp(4f) &&
                            event.x <= timeToX(it.endMs()) + dp(4f)
                    }

                    if (hit == null) {
                        dragMode = "playhead"
                        playheadMs = xToTime(event.x)
                            .coerceAtMost(projectEndMs())
                    } else {
                        selectedId = hit.id
                        dragId = hit.id
                        originalStart = hit.startMs
                        originalTrimStart = hit.trimStartMs
                        originalTrimEnd = hit.trimEndMs

                        val nearLeft =
                            kotlin.math.abs(event.x - timeToX(hit.startMs)) < dp(14f)

                        val nearRight =
                            kotlin.math.abs(event.x - timeToX(hit.endMs())) < dp(14f)

                        dragMode = when {
                            nearLeft -> "trimStart"
                            nearRight -> "trimEnd"
                            else -> "clip"
                        }
                    }

                    refreshTimeline()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val clip = clips.firstOrNull { it.id == dragId }

                    when (dragMode) {
                        "playhead" -> {
                            playheadMs = xToTime(event.x)
                                .coerceAtMost(projectEndMs())
                            changed = true
                        }

                        "clip" -> {
                            if (clip != null) {
                                val deltaMs = ((event.x - lastX) / zoom * 1000f).toLong()

                                clip.startMs = (clip.startMs + deltaMs).coerceAtLeast(0L)

                                val newTrack = (
                                    (event.y - rulerHeight) / trackHeight
                                ).toInt().coerceIn(0, tracks - 1)

                                clip.track = newTrack
                                changed = true
                            }
                        }

                        "trimStart" -> {
                            if (clip != null) {
                                val proposed = xToTime(event.x)
                                val delta = proposed - originalStart
                                val source = originalTrimStart + delta
                                val newStart = proposed.coerceAtLeast(0L)

                                if (
                                    source >= 0L &&
                                    source < originalTrimEnd &&
                                    newStart < clip.endMs()
                                ) {
                                    clip.trimStartMs = source
                                    clip.startMs = newStart
                                    changed = true
                                }
                            }
                        }

                        "trimEnd" -> {
                            if (clip != null) {
                                val proposedEnd = xToTime(event.x)
                                val newLength = proposedEnd - clip.startMs
                                val sourceEnd = clip.trimStartMs + newLength

                                if (
                                    sourceEnd > clip.trimStartMs &&
                                    sourceEnd <= clip.durationMs
                                ) {
                                    clip.trimEndMs = sourceEnd
                                    changed = true
                                }
                            }
                        }
                    }

                    lastX = event.x
                    refreshTimeline()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (changed && dragMode != "playhead") {
                        undoStack.addLast(beforeDrag)
                        while (undoStack.size > 30) undoStack.removeFirst()
                        redoStack.clear()
                        saveLocalProject()
                    }

                    dragMode = "none"
                    parent?.requestDisallowInterceptTouchEvent(false)
                    refreshTimeline()
                    return true
                }
            }

            return true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_MICROPHONE) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                showStatus("يلزم السماح باستخدام الميكروفون للتسجيل")
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null
        super.onDestroy()
    }
}
