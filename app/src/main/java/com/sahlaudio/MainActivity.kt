package com.sahlaudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

private const val BG = 0xFF101714.toInt()
private const val PANEL = 0xFF1B2620.toInt()
private const val GREEN = 0xFF63D69A.toInt()
private const val GOLD = 0xFFE8C879.toInt()
private const val WHITE = 0xFFF2F5F2.toInt()
private const val MUTED = 0xFF9BAAA0.toInt()

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
    fun lengthMs(): Long = max(0L, trimEndMs - trimStartMs)
    fun endMs(): Long = startMs + lengthMs()
}

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var horizontal: HorizontalScrollView
    private lateinit var vertical: ScrollView
    private lateinit var playLabel: TextView
    private lateinit var status: TextView

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
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private val pickAudio = 1001
    private val exportProject = 1002
    private val importProject = 1003
    private val exportWav = 1004

    private val prefs by lazy {
        getSharedPreferences("sahl_audio_project", Context.MODE_PRIVATE)
    }

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun button(
        text: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(WHITE)
            setBackgroundColor(PANEL)
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun label(
        text: String,
        size: Float = 14f,
        color: Int = WHITE
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
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
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }

        val heading = label("بسم الله الرحمن الرحيم", 18f, GOLD)
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(30f)))

        val appHeading = label(
            "سهل الصوت  •  محرر صوت متعدد المسارات",
            19f,
            GREEN
        )
        root.addView(appHeading, LinearLayout.LayoutParams(-1, dp(42f)))

        status = label("أضف ملفًا صوتيًا لبدء المشروع", 12f, MUTED)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(32f)))

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        firstRow.addView(button("＋ إضافة صوت") { chooseAudio() })
        firstRow.addView(button("▶ تشغيل") { togglePlayback() })
        firstRow.addView(button("■ إيقاف") { stopPlayback() })
        root.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondRow.addView(button("✂ تقسيم") { splitSelected() })
        secondRow.addView(button("⧉ تكرار") { duplicateSelected() })
        secondRow.addView(button("حذف") { deleteSelected() })
        root.addView(secondRow)

        val clipToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        clipToolsRow.addView(button("✎ إعادة تسمية") { renameSelected() })
        clipToolsRow.addView(button("🎨 لون المقطع") { chooseClipColor() })
        clipToolsRow.addView(button("⏮ بداية المقطع") { jumpToSelectedStart() })
        root.addView(clipToolsRow)

        val thirdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        thirdRow.addView(button("＋ مسار") {
            rememberUndo()
            tracks++
            refreshTimeline()
        })
        thirdRow.addView(button("↶ تراجع") { undo() })
        thirdRow.addView(button("↷ إعادة") { redo() })
        root.addView(thirdRow)

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        zoomRow.addView(button("− تصغير") {
            zoom = (zoom / 1.25f).coerceAtLeast(8f)
            refreshTimeline()
        })
        zoomRow.addView(label("التكبير", 13f, GOLD))
        zoomRow.addView(button("＋ تكبير") {
            zoom = (zoom * 1.25f).coerceAtMost(240f)
            refreshTimeline()
        })
        root.addView(zoomRow)

        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fileRow.addView(button("حفظ المشروع") { saveLocalProject() })
        fileRow.addView(button("تصدير المشروع") { createProjectFile() })
        fileRow.addView(button("استيراد مشروع") { openProjectFile() })
        root.addView(fileRow)

        val exportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        exportRow.addView(button("تصدير WAV") { startWavExport() })
        exportRow.addView(button("🎙 تسجيل") { toggleRecording() })
        root.addView(exportRow)

        playLabel = label("موضع التشغيل: 00:00.000", 13f, GOLD)
        root.addView(playLabel, LinearLayout.LayoutParams(-1, dp(32f)))

        vertical = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(0xFF0C120F.toInt())
        }

        horizontal = HorizontalScrollView(this).apply {
            isFillViewport = false
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

        vertical.addView(
            horizontal,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(vertical, LinearLayout.LayoutParams(0, 0, 1f))

        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        editRow.addView(button("قص البداية عند المؤشر") {
            trimStartAtPlayhead()
        })
        editRow.addView(button("قص النهاية عند المؤشر") {
            trimEndAtPlayhead()
        })
        root.addView(editRow)

        val volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeRow.addView(label("صوت المقطع", 12f))

        val volume = SeekBar(this).apply {
            max = 100
            progress = 100

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) {
                            clips.find { it.id == selectedId }?.let {
                                rememberUndo()
                                it.volume = progress / 100f
                                refreshTimeline()
                            }
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        volumeRow.addView(volume, LinearLayout.LayoutParams(0, dp(42f), 1f))
        volumeRow.addView(button("كتم/إلغاء الكتم") { toggleMute() })
        root.addView(volumeRow)

        setContentView(root)
    }
    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickAudio)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            pickAudio -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                val duration = getDuration(uri)
                if (duration <= 0L) {
                    showStatus("تعذر قراءة مدة الملف الصوتي")
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
                tracks = max(tracks, clip.track + 1)
                refreshTimeline()
                showStatus("تمت إضافة: ${clip.name}")
                saveLocalProject()
            }

            importProject -> {
                try {
                    val json = contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (json != null) loadProjectJson(json)
                } catch (e: Exception) {
                    showStatus("تعذر فتح المشروع: ${e.message}")
                }
            }

            exportProject -> {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(projectJson())
                    }
                    showStatus("تم تصدير ملف المشروع")
                } catch (e: Exception) {
                    showStatus("فشل تصدير المشروع: ${e.message}")
                }
            }

            exportWav -> {
                Thread {
                    try {
                        val out = contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("تعذر فتح ملف الحفظ")
                        out.use { exportMixedWav(it) }
                        runOnUiThread { showStatus("اكتمل تصدير WAV") }
                    } catch (e: Exception) {
                        runOnUiThread {
                            showStatus("فشل التصدير: ${e.message}")
                        }
                    }
                }.start()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )
                if (cursor.moveToFirst() && index >= 0) {
                    cursor.getString(index)
                } else {
                    "مقطع صوتي"
                }
            } ?: "مقطع صوتي"
        } catch (_: Exception) {
            "مقطع صوتي"
        }
    }

    private fun getDuration(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (_: Exception) {
            0L
        }
    }

    private fun projectEndMs(): Long {
        return max(
            10_000L,
            clips.maxOfOrNull { it.endMs() } ?: 10_000L
        )
    }

    private fun refreshTimeline() {
        timeline.invalidate()
        timeline.requestLayout()
        playLabel.text = "موضع التشغيل: ${formatTime(playheadMs)}"
        val selected = clips.find { it.id == selectedId }
        if (selected != null) {
            status.text = "${selected.name} • المسار ${selected.track + 1}"
        }
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe / 1000) % 60
        val millis = safe % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun splitSelected() {
        val clip = clips.find { it.id == selectedId } ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }
        val local = playheadMs - clip.startMs
        if (local <= 0L || local >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع لتقسيمه")
            return
        }

        rememberUndo()
        val splitSource = clip.trimStartMs + local
        val right = clip.copy(
            id = System.nanoTime(),
            name = clip.name + " (2)",
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
        val clip = clips.find { it.id == selectedId } ?: return
        rememberUndo()
        val copy = clip.copy(
            id = System.nanoTime(),
            name = clip.name + " - نسخة",
            startMs = clip.endMs()
        )
        clips.add(copy)
        selectedId = copy.id
        tracks = max(tracks, copy.track + 1)
        refreshTimeline()
        saveLocalProject()
    }

    private fun renameSelected() {
        val clip = clips.find { it.id == selectedId } ?: run {
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
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    rememberUndo()
                    clip.name = newName
                    refreshTimeline()
                    saveLocalProject()
                }
            }
            .show()
    }

    private fun chooseClipColor() {
        val clip = clips.find { it.id == selectedId } ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }
        val names = arrayOf("أخضر", "أزرق", "بنفسجي", "برتقالي", "أحمر", "أبيض")
        val colors = intArrayOf(
            0xFF276B4B.toInt(), 0xFF245A9B.toInt(), 0xFF7046A8.toInt(),
            0xFFB86A20.toInt(), 0xFF9D3434.toInt(), 0xFF69756E.toInt()
        )
        AlertDialog.Builder(this)
            .setTitle("اختر لون المقطع")
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
        val clip = clips.find { it.id == selectedId } ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }
        playheadMs = clip.startMs
        refreshTimeline()
        showStatus("انتقل المؤشر إلى بداية المقطع")
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        rememberUndo()
        clips.removeAll { it.id == id }
        selectedId = null
        refreshTimeline()
        saveLocalProject()
    }

    private fun trimStartAtPlayhead() {
        val clip = clips.find { it.id == selectedId } ?: return
        val offset = playheadMs - clip.startMs
        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع")
            return
        }
        rememberUndo()
        clip.trimStartMs += offset
        clip.startMs = playheadMs
        refreshTimeline()
    }

    private fun trimEndAtPlayhead() {
        val clip = clips.find { it.id == selectedId } ?: return
        val offset = playheadMs - clip.startMs
        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع")
            return
        }
        rememberUndo()
        clip.trimEndMs = clip.trimStartMs + offset
        refreshTimeline()
    }

    private fun toggleMute() {
        val clip = clips.find { it.id == selectedId } ?: return
        rememberUndo()
        clip.muted = !clip.muted
        refreshTimeline()
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
            showStatus("لا يوجد مقطع عند موضع التشغيل")
            return
        }

        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(
                    this@MainActivity,
                    Uri.parse(clip.uri)
                )
                setVolume(clip.volume, clip.volume)
                setOnPreparedListener {
                    val offset = (playheadMs - clip.startMs).coerceAtLeast(0L)
                    val sourcePosition = clip.trimStartMs + offset
                    seekTo(
                        sourcePosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    )
                    start()
                    isPlaying = true
                    showStatus("جارٍ التشغيل: ${clip.name}")
                    handler.post(playbackTicker)
                }
                setOnCompletionListener {
                    isPlaying = false
                    playheadMs = clip.endMs()
                    refreshTimeline()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            showStatus("تعذر التشغيل: ${e.message}")
        }
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val p = player
            if (p != null) {
                try {
                    val clip = clips.firstOrNull {
                        playheadMs >= it.startMs && playheadMs < it.endMs()
                    }
                    if (clip != null) {
                        playheadMs = clip.startMs +
                            (p.currentPosition - clip.trimStartMs).coerceAtLeast(0)
                    }
                    refreshTimeline()
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 40)
        }
    }

    private fun pausePlayback() {
        try {
            player?.pause()
        } catch (_: Exception) {}
        isPlaying = false
        handler.removeCallbacks(playbackTicker)
        showStatus("متوقف مؤقتًا")
    }

    private fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
        isPlaying = false
        handler.removeCallbacks(playbackTicker)
        playheadMs = 0L
        refreshTimeline()
        showStatus("تم إيقاف التشغيل")
    }
    private fun toggleRecording() {
        if (isRecording) {
            finishRecording()
            return
        }

        try {
            val file = File(cacheDir, "recording_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
            isRecording = true
            showStatus("جارٍ التسجيل… اضغط زر التسجيل مرة أخرى لإنهائه")
        } catch (e: Exception) {
            showStatus("تعذر بدء التسجيل: ${e.message}")
        }
    }

    private fun finishRecording() {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false
            val file = recordingFile
            if (file != null && file.exists() && file.length() > 0) {
                val duration = System.currentTimeMillis() - recordingStartedAt
                rememberUndo()
                val clip = AudioClip(
                    id = System.nanoTime(),
                    uri = Uri.fromFile(file).toString(),
                    name = "تسجيل جديد",
                    track = 0,
                    startMs = playheadMs,
                    durationMs = duration
                )
                clips.add(clip)
                selectedId = clip.id
                refreshTimeline()
                showStatus("تمت إضافة التسجيل إلى المسار الأول")
                saveLocalProject()
            }
        } catch (e: Exception) {
            showStatus("تعذر إنهاء التسجيل: ${e.message}")
        }
    }

    private fun rememberUndo() {
        undoStack.addLast(projectJson())
        while (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            showStatus("لا توجد خطوة للتراجع")
            return
        }
        redoStack.addLast(projectJson())
        loadProjectJson(undoStack.removeLast())
        showStatus("تم التراجع")
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            showStatus("لا توجد خطوة لإعادتها")
            return
        }
        undoStack.addLast(projectJson())
        loadProjectJson(redoStack.removeLast())
        showStatus("تمت إعادة الخطوة")
    }

    private fun projectJson(): String {
        val rootJson = JSONObject()
        rootJson.put("version", 1)
        rootJson.put("tracks", tracks)
        rootJson.put("playheadMs", playheadMs)
        rootJson.put("zoom", zoom.toDouble())
        val array = JSONArray()

        clips.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("uri", c.uri)
                put("name", c.name)
                put("track", c.track)
                put("startMs", c.startMs)
                put("durationMs", c.durationMs)
                put("trimStartMs", c.trimStartMs)
                put("trimEndMs", c.trimEndMs)
                put("volume", c.volume.toDouble())
                put("muted", c.muted)
                put("color", c.color)
            })
        }

        rootJson.put("clips", array)
        return rootJson.toString()
    }

    private fun loadProjectJson(text: String) {
        try {
            val obj = JSONObject(text)
            clips.clear()
            val array = obj.getJSONArray("clips")

            for (i in 0 until array.length()) {
                val c = array.getJSONObject(i)
                clips.add(
                    AudioClip(
                        id = c.optLong("id", System.nanoTime()),
                        uri = c.getString("uri"),
                        name = c.optString("name", "مقطع صوتي"),
                        track = c.optInt("track", 0),
                        startMs = c.optLong("startMs", 0),
                        durationMs = c.optLong("durationMs", 0),
                        trimStartMs = c.optLong("trimStartMs", 0),
                        trimEndMs = c.optLong(
                            "trimEndMs",
                            c.optLong("durationMs", 0)
                        ),
                        volume = c.optDouble("volume", 1.0).toFloat(),
                        muted = c.optBoolean("muted", false),
                        color = c.optInt("color", 0xFF276B4B.toInt())
                    )
                )
            }

            tracks = max(1, obj.optInt("tracks", 3))
            playheadMs = obj.optLong("playheadMs", 0)
            zoom = obj.optDouble("zoom", 70.0)
                .toFloat()
                .coerceIn(8f, 240f)
            selectedId = clips.firstOrNull()?.id
            refreshTimeline()
        } catch (e: Exception) {
            showStatus("ملف المشروع غير صالح: ${e.message}")
        }
    }

    private fun saveLocalProject() {
        prefs.edit().putString("project", projectJson()).apply()
        showStatus("تم حفظ المشروع على هذا الجهاز")
    }

    private fun restoreLocalProject() {
        val saved = prefs.getString("project", null) ?: return
        loadProjectJson(saved)
        showStatus("تمت استعادة المشروع المحفوظ")
    }

    private fun createProjectFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "SahlAudio-project.json")
        }
        startActivityForResult(intent, exportProject)
    }

    private fun openProjectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, importProject)
    }

    private fun startWavExport() {
        if (clips.isEmpty()) {
            showStatus("أضف مقطعًا قبل التصدير")
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "SahlAudio-mix.wav")
        }
        startActivityForResult(intent, exportWav)
    }

    /*
     * تصدير WAV محدود بدقيقتين لتجنب استهلاك ذاكرة الهاتف.
     * النسخة الحالية تدعم ملفات WAV PCM غير المضغوطة فقط.
     */
    private fun exportMixedWav(output: java.io.OutputStream) {
        val sampleRate = 44100
        val totalMs = projectEndMs()

        if (totalMs > 120_000L) {
            throw IllegalStateException(
                "التصدير الحالي محدود بدقيقتين لتجنب استهلاك ذاكرة الهاتف"
            )
        }

        val totalFrames = (totalMs * sampleRate / 1000L).toInt()
        val mix = FloatArray(totalFrames)

        for (clip in clips) {
            if (clip.muted || clip.lengthMs() <= 0) continue

            val uri = Uri.parse(clip.uri)
            val pcm = decodePcmMono(uri, sampleRate)

            val sourceStart = (clip.trimStartMs * sampleRate / 1000L)
                .toInt().coerceIn(0, pcm.size)
            val sourceEnd = (clip.trimEndMs * sampleRate / 1000L)
                .toInt().coerceIn(sourceStart, pcm.size)
            val targetStart = (clip.startMs * sampleRate / 1000L)
                .toInt().coerceIn(0, totalFrames)
            val count = min(sourceEnd - sourceStart, totalFrames - targetStart)

            for (i in 0 until count) {
                mix[targetStart + i] =
                    (mix[targetStart + i] + pcm[sourceStart + i] * clip.volume)
                        .coerceIn(-1f, 1f)
            }
        }

        val dataBytes = totalFrames * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)

        output.write(header.array())

        val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

        for (sample in mix) {
            val pcm16 = (sample * 32767f).toInt()
                .coerceIn(-32768, 32767).toShort()

            if (buffer.remaining() < 2) {
                output.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            buffer.putShort(pcm16)
        }

        if (buffer.position() > 0) {
            output.write(buffer.array(), 0, buffer.position())
        }

        output.flush()
    }
    private fun decodePcmMono(uri: Uri, targetRate: Int): FloatArray {
        /*
         * يدعم التصدير الحالي ملفات WAV غير المضغوطة فقط.
         * يجب تنفيذ وفحص فك ترميز الصيغ المضغوطة بصورة منفصلة.
         */
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف الصوتي")
        val bytes = input.use { it.readBytes() }

        if (
            bytes.size < 44 ||
            String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            throw IllegalStateException(
                "التصدير الحالي يقبل ملفات WAV غير المضغوطة فقط"
            )
        }

        var offset = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val body = offset + 8

            if (size < 0 || body + size > bytes.size) break

            when (id) {
                "fmt " -> {
                    format = ByteBuffer.wrap(bytes, body, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF

                    channels = ByteBuffer.wrap(bytes, body + 2, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF

                    rate = ByteBuffer.wrap(bytes, body + 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int

                    bits = ByteBuffer.wrap(bytes, body + 14, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF
                }

                "data" -> {
                    dataOffset = body
                    dataSize = size
                    break
                }
            }

            offset = body + size + (size and 1)
        }

        if (
            format != 1 ||
            (bits != 16 && bits != 8) ||
            channels !in 1..2 ||
            rate <= 0 ||
            dataOffset < 0
        ) {
            throw IllegalStateException(
                "نوع WAV غير مدعوم؛ المطلوب PCM أحادي أو ثنائي القناة بدقة 8 أو 16 بت"
            )
        }

        val bytesPerSample = bits / 8
        val frames = dataSize / (bytesPerSample * channels)
        val mono = FloatArray(frames)

        for (frame in 0 until frames) {
            var sum = 0f

            for (ch in 0 until channels) {
                val p = dataOffset +
                    (frame * channels + ch) * bytesPerSample

                val sample = if (bits == 16) {
                    ByteBuffer.wrap(bytes, p, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toFloat() / 32768f
                } else {
                    ((bytes[p].toInt() and 0xFF) - 128) / 128f
                }

                sum += sample
            }

            mono[frame] = sum / channels
        }

        if (rate == targetRate) return mono

        val outSize = (
            mono.size.toDouble() * targetRate / rate
        ).toInt().coerceAtLeast(1)

        val resampled = FloatArray(outSize)

        for (i in resampled.indices) {
            val src = i.toDouble() * rate / targetRate
            val left = src.toInt().coerceIn(0, mono.lastIndex)
            val right = min(left + 1, mono.lastIndex)
            val fraction = (src - left).toFloat()

            resampled[i] =
                mono[left] * (1f - fraction) +
                mono[right] * fraction
        }

        return resampled
    }

    inner class TimelineView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackHeight = dp(86f)
        private val rulerHeight = dp(36f)
        private val leftGutter = dp(62f)

        private var lastX = 0f
        private var lastY = 0f
        private var dragMode = "none"
        private var dragClipId: Long? = null

        private var originalStart = 0L
        private var originalTrack = 0
        private var originalTrimStart = 0L
        private var originalTrimEnd = 0L

        private var changed = false

        private fun timeToX(ms: Long): Float =
            leftGutter + ms / 1000f * zoom

        private fun xToTime(x: Float): Long =
            (((x - leftGutter) / zoom).coerceAtLeast(0f) * 1000f).toLong()

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val contentWidth = (
                leftGutter +
                    projectEndMs() / 1000f * zoom +
                    dp(120f)
            ).toInt().coerceAtLeast(dp(360f))

            val contentHeight =
                rulerHeight + tracks * trackHeight + dp(12f)

            setMeasuredDimension(
                resolveSize(contentWidth, widthMeasureSpec),
                resolveSize(contentHeight, heightMeasureSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF0C120F.toInt())

            paint.color = 0xFF17221B.toInt()
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                rulerHeight.toFloat(),
                paint
            )

            val end = projectEndMs()

            val stepMs = when {
                zoom >= 120f -> 1000L
                zoom >= 45f -> 2000L
                zoom >= 20f -> 5000L
                else -> 10000L
            }

            var t = 0L

            while (t <= end + stepMs) {
                val x = timeToX(t)

                paint.color = 0xFF35443A.toInt()
                paint.strokeWidth = 1f

                canvas.drawLine(
                    x,
                    rulerHeight.toFloat(),
                    x,
                    height.toFloat(),
                    paint
                )

                paint.color = MUTED
                paint.textSize = dp(10f).toFloat()

                canvas.drawText(
                    formatTime(t).substring(0, 5),
                    x + dp(3f),
                    dp(23f).toFloat(),
                    paint
                )

                t += stepMs
            }

            for (track in 0 until tracks) {
                val top = rulerHeight + track * trackHeight

                paint.color = if (track % 2 == 0) {
                    0xFF111A15.toInt()
                } else {
                    0xFF151F19.toInt()
                }

                canvas.drawRect(
                    0f,
                    top.toFloat(),
                    width.toFloat(),
                    (top + trackHeight).toFloat(),
                    paint
                )

                paint.color = MUTED
                paint.textSize = dp(11f).toFloat()

                canvas.drawText(
                    "مسار ${track + 1}",
                    dp(5f).toFloat(),
                    (top + dp(18f)).toFloat(),
                    paint
                )

                paint.color = 0xFF29382E.toInt()

                canvas.drawLine(
                    leftGutter,
                    (top + trackHeight).toFloat(),
                    width.toFloat(),
                    (top + trackHeight).toFloat(),
                    paint
                )
            }

            clips.forEach { clip ->
                val x1 = timeToX(clip.startMs)
                val x2 = timeToX(clip.endMs())

                val top =
                    rulerHeight + clip.track * trackHeight + dp(8f)

                val bottom =
                    rulerHeight +
                        (clip.track + 1) * trackHeight -
                        dp(8f)

                paint.color = clip.color

                canvas.drawRoundRect(
                    x1,
                    top.toFloat(),
                    max(x1 + dp(8f), x2),
                    bottom.toFloat(),
                    dp(7f).toFloat(),
                    dp(7f).toFloat(),
                    paint
                )

                paint.color = if (clip.muted) MUTED else clip.color
                paint.strokeWidth = dp(1f).toFloat()

                val middle = (top + bottom) / 2f
                val barWidth = max(2f, zoom / 14f)

                var x = x1 + dp(5f)

                while (x < x2 - dp(4f)) {
                    val seed =
                        ((x.toInt() * 31 + clip.id.toInt()) ushr 3) and 31

                    val heightFactor = 0.18f + (seed % 13) / 18f
                    val half = (bottom - top) * heightFactor / 2f

                    canvas.drawLine(
                        x,
                        middle - half,
                        x,
                        middle + half,
                        paint
                    )

                    x += barWidth
                }

                paint.color = WHITE
                paint.textSize = dp(11f).toFloat()

                canvas.drawText(
                    clip.name.take(22),
                    x1 + dp(6f),
                    top + dp(14f),
                    paint
                )

                if (clip.id == selectedId) {
                    paint.color = GOLD
                    paint.strokeWidth = dp(3f).toFloat()

                    canvas.drawLine(
                        x1,
                        top.toFloat(),
                        x1,
                        bottom.toFloat(),
                        paint
                    )

                    canvas.drawLine(
                        x2,
                        top.toFloat(),
                        x2,
                        bottom.toFloat(),
                        paint
                    )
                }
            }

            val px = timeToX(playheadMs)

            paint.color = 0xFFFF625D.toInt()
            paint.strokeWidth = dp(2f).toFloat()

            canvas.drawLine(
                px,
                0f,
                px,
                height.toFloat(),
                paint
            )

            canvas.drawCircle(
                px,
                dp(5f).toFloat(),
                dp(5f).toFloat(),
                paint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    changed = false
                    dragClipId = null

                    val px = timeToX(playheadMs)

                    if (kotlin.math.abs(event.x - px) < dp(18f)) {
                        dragMode = "playhead"
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    val track = (
                        (event.y - rulerHeight) / trackHeight
                    ).toInt().coerceIn(0, tracks - 1)

                    val hit = clips.asReversed().firstOrNull {
                        it.track == track &&
                            event.x >= timeToX(it.startMs) - dp(4f) &&
                            event.x <= timeToX(it.endMs()) + dp(4f) &&
                            event.y >= rulerHeight + track * trackHeight &&
                            event.y < rulerHeight + (track + 1) * trackHeight
                    }

                    if (hit != null) {
                        selectedId = hit.id
                        dragClipId = hit.id

                        originalStart = hit.startMs
                        originalTrack = hit.track
                        originalTrimStart = hit.trimStartMs
                        originalTrimEnd = hit.trimEndMs

                        val nearLeft =
                            kotlin.math.abs(
                                event.x - timeToX(hit.startMs)
                            ) < dp(14f)

                        val nearRight =
                            kotlin.math.abs(
                                event.x - timeToX(hit.endMs())
                            ) < dp(14f)

                        dragMode = when {
                            nearLeft -> "trimStart"
                            nearRight -> "trimEnd"
                            else -> "clip"
                        }
                    } else {
                        dragMode = "playhead"
                        playheadMs =
                            xToTime(event.x).coerceAtMost(projectEndMs())
                    }

                    refreshTimeline()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX

                    when (dragMode) {
                        "playhead" -> {
                            playheadMs =
                                xToTime(event.x).coerceAtMost(projectEndMs())
                            changed = true
                        }

                        "clip" -> {
                            val clip = clips.find {
                                it.id == dragClipId
                            }

                            if (clip != null) {
                                val delta = (dx / zoom * 1000f).toLong()

                                clip.startMs =
                                    (clip.startMs + delta).coerceAtLeast(0L)

                                val newTrack = (
                                    (event.y - rulerHeight) / trackHeight
                                ).toInt().coerceIn(0, tracks - 1)

                                clip.track = newTrack
                                changed = true
                            }
                        }

                        "trimStart" -> {
                            val clip = clips.find {
                                it.id == dragClipId
                            }

                            if (clip != null) {
                                val proposed = xToTime(event.x)
                                val offset = proposed - clip.startMs
                                val source = originalTrimStart + offset

                                if (
                                    source >= 0 &&
                                    source < clip.trimEndMs
                                ) {
                                    clip.trimStartMs = source
                                    clip.startMs = proposed.coerceAtLeast(0)
                                    changed = true
                                }
                            }
                        }

                        "trimEnd" -> {
                            val clip = clips.find {
                                it.id == dragClipId
                            }

                            if (clip != null) {
                                val proposedEnd = xToTime(event.x)
                                val newLength = proposedEnd - clip.startMs
                                val sourceEnd =
                                    originalTrimStart + newLength

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
                    lastY = event.y

                    refreshTimeline()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (changed && dragMode != "playhead") {
                        undoStack.addLast(
                            snapshotFromTouch(
                                dragClipId,
                                originalStart,
                                originalTrack,
                                originalTrimStart,
                                originalTrimEnd
                            )
                        )
                    }

                    dragMode = "none"
                    parent?.requestDisallowInterceptTouchEvent(false)

                    refreshTimeline()
                    saveLocalProject()
                    return true
                }
            }

            return true
        }

        private fun snapshotFromTouch(
            id: Long?,
            start: Long,
            track: Int,
            trimStart: Long,
            trimEnd: Long
        ): String {
            /*
             * تعديلات السحب تكون قد طُبقت بالفعل.
             * سجل التراجع الخاص بالسحب محدود في هذه النسخة.
             */
            return projectJson()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        try {
            player?.release()
            recorder?.release()
        } catch (_: Exception) {}

        player = null
        recorder = null

        super.onDestroy()
    }
}
