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
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private var BG = 0xFF10162A.toInt()
private var PANEL = 0xFF202A46.toInt()
private var GREEN = 0xFF74D6B1.toInt()
private var GOLD = 0xFFFFC978.toInt()
private var WHITE = 0xFFF5F7FF.toInt()
private var MUTED = 0xFFB7C3DD.toInt()

private const val REQUEST_AUDIO = 1001
private const val REQUEST_IMPORT_PROJECT = 1002
private const val REQUEST_EXPORT_PROJECT = 1003
private const val REQUEST_MICROPHONE = 1004
private const val REQUEST_EXPORT_AUDIO = 1005

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
    var color: Int = 0xFF276B4B.toInt(),
    var waveform: FloatArray = floatArrayOf()
) {
    fun lengthMs(): Long =
        (trimEndMs - trimStartMs).coerceAtLeast(0L)

    fun endMs(): Long = startMs + lengthMs()
}

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var status: TextView
    private lateinit var playLabel: TextView
    private lateinit var volumeSeek: SeekBar

    private val clips = mutableListOf<AudioClip>()
    private var tracks = 3
    private var playheadMs = 0L
    private var zoom = 70f
    private var selectedId: Long? = null
    private var projectName = "مشروعي الصوتي"

    private val players = mutableMapOf<Long, PreciseAudioPlayer>()
    private val scheduledStarts = mutableSetOf<Long>()
    // يمنع إعادة تشغيل المقطع نفسه إذا انتهى فك الصوت قبل أن تلحق به ساعة الواجهة.
    private val completedClipIds = mutableSetOf<Long>()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var isPlaying = false
    private var playbackButton: Button? = null
    private var playbackClockStartedAt = 0L
    private var playbackClockPosition = 0L
    private var playbackGeneration = 0L
    private var lastExportedAudioUri: Uri? = null
    private var isRecording = false
    private var isDarkTheme = true
    private var themeMode = 0

    private val handler = Handler(Looper.getMainLooper())
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - recordingStartedAt
            status.text = "● تسجيل جارٍ ${formatTime(elapsed)}"
            handler.postDelayed(this, 250L)
        }
    }
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
            background = GradientDrawable().apply {
                val accent = when {
                    text.startsWith("＋") || text.startsWith("▶") || text.startsWith("●") -> if (isDarkTheme) 0xFF2D806E.toInt() else 0xFFB85A72.toInt()
                    text.startsWith("−") || text.startsWith("Ⅱ") -> if (isDarkTheme) 0xFF765A9B.toInt() else 0xFF8B5E83.toInt()
                    text == "✂" || text == "→|" || text == "|←" -> if (isDarkTheme) 0xFF9A6840.toInt() else 0xFFB36B43.toInt()
                    else -> PANEL
                }
                setColor(accent)
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1f), if (isDarkTheme) 0xFF506B91.toInt() else 0xFFF0A77E.toInt())
            }
            minHeight = dp(26f)
            minimumHeight = dp(26f)
            minWidth = 0
            minimumWidth = 0
            textSize = 11f
            setPadding(dp(4f), 0, dp(4f), 0)
            contentDescription = text
            setOnClickListener { action() }
        }
    }

    private fun makeRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
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
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28f)))

        val projectBar = makeRow().apply { gravity = Gravity.CENTER }
        projectBar.addView(makeLabel("المشروع", 10f, MUTED))
        projectBar.addView(makeButton("$projectName ▾") { showProjects() })
        projectBar.addView(makeButton("＋") { newProject() })
        projectBar.addView(makeButton("✎") { renameProject() })
        projectBar.addView(makeButton("🎨") { toggleTheme() })
        root.addView(projectBar)
        val topTools = makeRow().apply { gravity = Gravity.CENTER }
        topTools.addView(makeButton("حفظ") { saveLocalProject() })
        topTools.addView(makeButton("WAV") { chooseFinalAudioExport() })
        topTools.addView(makeButton("مشاركة") { shareLastExportedAudio() })
        topTools.addView(makeButton("فتح") { importProject() })
        topTools.addView(makeButton("JSON") { exportProject() })
        root.addView(topTools)
        playLabel = makeLabel("موضع التشغيل: 00:00.000", 13f, GOLD)
        root.addView(playLabel, LinearLayout.LayoutParams(-1, dp(30f)))

        val workspace = FrameLayout(this)
        workspace.setBackgroundColor(BG)
        timeline = TimelineView(this)
        workspace.addView(timeline, FrameLayout.LayoutParams(-1, -1))
        fun dockButton(text: String, action: () -> Unit): Button = makeButton(text, action).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(34f)).apply {
                setMargins(dp(2f), dp(2f), dp(2f), dp(2f))
            }
        }
        val leftDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
            background = GradientDrawable().apply { setColor(PANEL and 0xDFFFFFFF.toInt()); cornerRadius = dp(10f).toFloat() }
        }
        leftDock.addView(dockButton("＋") { chooseAudio() })
        leftDock.addView(dockButton("✂") { splitSelected() })
        leftDock.addView(dockButton("⧉") { duplicateSelected() })
        leftDock.addView(dockButton("⌫") { deleteSelected() })
        leftDock.addView(dockButton("↤") { jumpToSelectedStart() })
        val rightDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
            background = GradientDrawable().apply { setColor(PANEL and 0xDFFFFFFF.toInt()); cornerRadius = dp(10f).toFloat() }
        }
        rightDock.addView(dockButton("＋") { addTrack() })
        rightDock.addView(dockButton("−") { confirmDeleteTrack() })
        rightDock.addView(dockButton("↑") { moveSelectedTrack(-1) })
        rightDock.addView(dockButton("↓") { moveSelectedTrack(1) })
        rightDock.addView(dockButton("↶") { undo() })
        rightDock.addView(dockButton("↷") { redo() })
        workspace.addView(leftDock, FrameLayout.LayoutParams(dp(48f), -2, Gravity.START or Gravity.CENTER_VERTICAL))
        workspace.addView(rightDock, FrameLayout.LayoutParams(dp(48f), -2, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        val zoomRow = makeRow().apply { gravity = Gravity.CENTER }
        zoomRow.addView(makeButton("− تصغير") { zoomBy(1f / 1.18f) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(78f), dp(38f)).apply { setMargins(dp(4f), dp(2f), dp(4f), dp(2f)) }
            textSize = 12f
        })
        zoomRow.addView(makeLabel("المشهد", 10f, GOLD))
        zoomRow.addView(makeButton("＋ تكبير") { zoomBy(1.18f) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(78f), dp(38f)).apply { setMargins(dp(4f), dp(2f), dp(4f), dp(2f)) }
            textSize = 12f
        })
        root.addView(zoomRow)
        val playbackRow = makeRow().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        playbackButton = makeButton("▶ تشغيل") { togglePlayback() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(118f), dp(44f)).apply { setMargins(dp(4f), dp(2f), dp(4f), dp(2f)) }
            textSize = 14f
        }
        playbackRow.addView(playbackButton)
        playbackRow.addView(makeButton("● تسجيل") { toggleRecording() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(92f), dp(40f)).apply { setMargins(dp(4f), dp(2f), dp(4f), dp(2f)) }
            textSize = 13f
        })
        root.addView(playbackRow)

        val editRow = makeRow().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        editRow.addView(makeButton("|←") { trimStartAtPlayhead() })
        editRow.addView(makeButton("→|") { trimEndAtPlayhead() })
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
        // الحالة تظهر بهدوء داخل الشريط السفلي بدل Toast متكرر يشتت المستخدم.
        val isSuccess = message.startsWith("تم") || message.startsWith("الحمد")
        val blessing = if (isSuccess) listOf("جزاك الله خيرًا", "بارك الله فيك", "الحمد لله رب العالمين", "توكل على الله").random() else ""
        status.text = if (isSuccess && !message.contains("•")) "$message • $blessing" else message
        status.setTextColor(if (isSuccess) GREEN else MUTED)
    }
    private fun updateThemeColors() {
        when (themeMode) {
            0 -> { BG = 0xFF10162A.toInt(); PANEL = 0xFF202A46.toInt(); GREEN = 0xFF74D6B1.toInt(); GOLD = 0xFFFFC978.toInt(); WHITE = 0xFFF5F7FF.toInt(); MUTED = 0xFFB7C3DD.toInt() }
            else -> { BG = 0xFF24152F.toInt(); PANEL = 0xFF4A285A.toInt(); GREEN = 0xFFFF9FCB.toInt(); GOLD = 0xFFFFD166.toInt(); WHITE = 0xFFFFF2FA.toInt(); MUTED = 0xFFD9B9E6.toInt() }
        }
    }
    private fun toggleTheme() {
        themeMode = (themeMode + 1) % 2
        isDarkTheme = true
        prefs.edit().putInt("theme_mode", themeMode).apply()
        updateThemeColors()
        window.statusBarColor = BG; window.navigationBarColor = BG
        fun recolor(view: View) {
            when (view) {
                is Button -> { view.setTextColor(WHITE); view.setBackgroundColor(PANEL) }
                is TextView -> view.setTextColor(if (view === status) MUTED else WHITE)
                is ViewGroup -> for (i in 0 until view.childCount) recolor(view.getChildAt(i))
            }
            if (view === root) view.setBackgroundColor(BG)
        }
        recolor(root); timeline.invalidate(); showStatus(if (themeMode == 0) "تم تفعيل الوضع الداكن • توكل على الله" else "تم تفعيل الوضع البنفسجي • جزاك الله خيرًا")
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1000L) % 60L
        val millis = safe % 1000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun projectEndMs(): Long =
        max(1_000L, clips.maxOfOrNull { it.endMs() } ?: 1_000L)

    private fun selectedClip(): AudioClip? =
        clips.firstOrNull { it.id == selectedId }

    private fun refreshTimeline() {
        timeline.invalidate()

        playLabel.text = "موضع التشغيل: ${formatTime(playheadMs)}"

        selectedClip()?.let { clip ->
            status.text = "${clip.name} • المسار ${clip.track + 1}"
            volumeSeek.progress = (clip.volume * 100).toInt().coerceIn(0, 100)
        }
    }

    private fun zoomBy(factor: Float) {
        timeline.zoomAroundPlayhead(factor)
    }
    private fun panToPlayheadIfNeeded() {
        if (::timeline.isInitialized) timeline.keepPlayheadVisible()
    }
    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav",
                "audio/ogg", "audio/opus", "audio/3gpp", "audio/amr", "audio/flac"
            ))
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
            REQUEST_EXPORT_AUDIO -> {
                val fixedUri = ensureWavExtension(uri)
                exportFinalAudio(fixedUri)
            }
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

                val targetTrack = (0 until tracks).firstOrNull {
                    canPlace(null, playheadMs, it, duration)
                }
                if (targetTrack == null) {
                    showStatus("لا يوجد مكان خالٍ؛ أضف مسارًا جديدًا أولًا")
                    return
                }
                val clip = AudioClip(
                    id = System.nanoTime(),
                    uri = uri.toString(),
                    name = queryName(uri),
                    track = targetTrack,
                    startMs = playheadMs,
                    durationMs = duration,
                    color = paletteColor(clips.size)
                )

                clips.add(clip)
                prepareWaveform(clip)
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
        releasePlayers()
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
        releasePlayers()
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
        releasePlayers()
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
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
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

    private fun paletteColor(index: Int): Int = intArrayOf(
        0xFF3C8068.toInt(), 0xFF3E6F9E.toInt(), 0xFF735B9A.toInt(),
        0xFF9A7445.toInt(), 0xFF8D5963.toInt(), 0xFF557D79.toInt()
    )[index % 6]

    private fun chooseClipColor() {
        val clip = selectedClip() ?: run {
            showStatus("اختر مقطعًا أولًا")
            return
        }

        val names = arrayOf(
            "أخضر", "أزرق", "بنفسجي", "برتقالي", "أحمر", "رمادي"
        )

        val colors = intArrayOf(
            0xFF3C8068.toInt(),
            0xFF3E6F9E.toInt(),
            0xFF735B9A.toInt(),
            0xFF9A7445.toInt(),
            0xFF8D5963.toInt(),
            0xFF557D79.toInt()
        )

        AlertDialog.Builder(this)
            .setTitle("لون")
            .setItems(names) { _, which ->
                rememberUndo()
                clip.color = colors[which]
                refreshTimeline()
                saveLocalProject()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun rangesOverlap(startA: Long, endA: Long, startB: Long, endB: Long): Boolean =
        startA < endB && startB < endA

    private fun canPlace(clip: AudioClip?, start: Long, track: Int, length: Long): Boolean {
        val end = start + length
        return clips.none {
            it.id != clip?.id && it.track == track &&
                rangesOverlap(start, end, it.startMs, it.endMs())
        }
    }

    private fun addTrack() {
        if (tracks >= 20) { showStatus("وصلت إلى الحد الأقصى للمسارات"); return }
        rememberUndo(); tracks += 1; refreshTimeline(); saveLocalProject()
    }

    private fun confirmDeleteTrack() {
        if (tracks <= 1) { showStatus("يجب أن يبقى مسار واحد على الأقل"); return }
        val selectedTrack = selectedClip()?.track
        val target = when {
            selectedTrack != null && clips.none { it.track == selectedTrack } -> selectedTrack
            else -> (tracks - 1 downTo 0).firstOrNull { track -> clips.none { it.track == track } } ?: -1
        }
        if (target < 0) {
            showStatus("لا يوجد مسار فارغ لحذفه")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف المسار ${target + 1}")
            .setMessage("هل تريد حذف هذا المسار؟ لن يتم حذف ملفات الصوت، ويمكن التراجع بزر التراجع.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("⌫") { _, _ ->
                rememberUndo()
                clips.forEach { if (it.track > target) it.track -= 1 }
                tracks -= 1
                refreshTimeline(); saveLocalProject()
            }.show()
    }

    private fun moveSelectedTrack(delta: Int) {
        val clip = selectedClip() ?: run { showStatus("اختر مقطعًا أولًا"); return }
        val next = (clip.track + delta).coerceIn(0, tracks - 1)
        if (next == clip.track) return
        if (!canPlace(clip, clip.startMs, next, clip.lengthMs())) {
            showStatus("لا يمكن وضع المقطع فوق مقطع آخر في هذا المسار")
            return
        }
        releasePlayers(); rememberUndo(); clip.track = next; refreshTimeline(); saveLocalProject()
    }

    private fun jumpToSelectedStart() {
        val clip = selectedClip() ?: return
        playheadMs = clip.startMs
        refreshTimeline()
    }

    private fun trimStartAtPlayhead() {
        releasePlayers()
        val clip = selectedClip() ?: return
        val offset = playheadMs - clip.startMs

        if (offset <= 0L || offset >= clip.lengthMs()) {
            showStatus("ضع المؤشر داخل المقطع")
            return
        }

        val newStart = playheadMs.coerceAtLeast(0L)
        val newLength = clip.endMs() - newStart
        if (newLength <= 0L || !canPlace(clip, newStart, clip.track, newLength)) {
            showStatus("لا يمكن قص البداية لأنها ستتداخل مع مقطع آخر")
            return
        }
        rememberUndo()
        clip.trimStartMs += offset
        clip.startMs = newStart
        refreshTimeline()
        saveLocalProject()
    }

    private fun trimEndAtPlayhead() {
        releasePlayers()
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

    private fun updatePlaybackButton() {
        playbackButton?.text = if (isPlaying) "Ⅱ إيقاف مؤقت" else "▶ تشغيل"
    }
    private inner class PreciseAudioPlayer(
        private val clip: AudioClip,
        private val startOffsetMs: Long,
        private val generation: Long,
        private val finished: () -> Unit
    ) {
        @Volatile private var stopped = false
        private var audioTrack: AudioTrack? = null
        private var worker: Thread? = null

        fun start() {
            worker = Thread({ decodeAndPlay() }, "sahl-precise-audio").also { it.start() }
        }
        fun release() {
            stopped = true
            try { worker?.interrupt() } catch (_: Exception) { }
            try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() } catch (_: Exception) { }
            audioTrack = null
        }
        private fun decodeAndPlay() {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var track: AudioTrack? = null
            try {
                val uri = Uri.parse(clip.uri)
                if (uri.scheme == "file") extractor.setDataSource(uri.path ?: "")
                else extractor.setDataSource(this@MainActivity, uri, null)
                var audioIndex = -1
                for (i in 0 until extractor.trackCount) {
                    if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioIndex = i; break
                    }
                }
                if (audioIndex < 0) return
                extractor.selectTrack(audioIndex)
                val format = extractor.getTrackFormat(audioIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                fun createTrack(sampleRate: Int, channelCount: Int): AudioTrack {
                    val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    return AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes((minBuffer.coerceAtLeast(sampleRate * channelCount * 2 / 5) * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build().apply { setVolume(clip.volume.coerceIn(0f, 1f)) }
                }
                val wantedStartUs = (clip.trimStartMs + startOffsetMs).coerceAtLeast(0L) * 1000L
                val wantedEndUs = clip.trimEndMs * 1000L
                extractor.seekTo(wantedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                codec = MediaCodec.createDecoderByType(mime)
                codec!!.configure(format, null, null, 0)
                codec!!.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                // عدد الإطارات التي أُرسلت إلى AudioTrack؛ الكتابة تنتهي قبل أن
                // يكون الصوت قد خرج فعليًا من المخزن إلى السماعة.
                var totalFramesWritten = 0L
                while (!stopped && !outputDone) {
                    val c = codec ?: break
                    if (!inputDone) {
                        val inputIndex = c.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = c.getInputBuffer(inputIndex)
                            if (input == null) continue
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                c.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = c.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = c.outputFormat
                        rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        try { track?.release() } catch (_: Exception) { }
                        track = createTrack(rate, channels)
                        audioTrack = track
                        track?.play()
                        continue
                    }
                    if (outputIndex >= 0) {
                        if (track == null) {
                            track = createTrack(rate, channels)
                            audioTrack = track
                            track?.play()
                        }
                        val buffer = c.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && info.presentationTimeUs < wantedEndUs) {
                            val bytesPerFrame = channels * 2
                            val bufferStartUs = info.presentationTimeUs
                            val bufferEndUs = bufferStartUs + info.size.toLong() * 1_000_000L / (rate * bytesPerFrame)
                            val skipFrames = if (bufferStartUs < wantedStartUs)
                                ((wantedStartUs - bufferStartUs) * rate / 1_000_000L).toInt().coerceAtLeast(0) else 0
                            val endFrames = if (bufferEndUs > wantedEndUs)
                                ((wantedEndUs - bufferStartUs) * rate / 1_000_000L).toInt() else info.size / bytesPerFrame
                            val startByte = (skipFrames * bytesPerFrame).coerceIn(0, info.size)
                            val endByte = (endFrames * bytesPerFrame).coerceIn(startByte, info.size)
                            if (endByte > startByte) {
                                val bytes = ByteArray(endByte - startByte)
                                buffer.position(info.offset + startByte)
                                buffer.get(bytes)
                                var written = 0
                                while (!stopped && written < bytes.size) {
                                    val n = track?.write(bytes, written, bytes.size - written, AudioTrack.WRITE_BLOCKING) ?: -1
                                    if (n <= 0) break
                                    written += n
                                    totalFramesWritten += n.toLong() / bytesPerFrame
                                }
                            }
                        }
                        c.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }

                // لا نعلن انتهاء المقطع بمجرد انتهاء فك الترميز؛ انتظر خروج
                // الإطارات المرسلة من مخزن AudioTrack حتى لا يبدأ الجزء التالي
                // فوق آخر مقطع مسموع، وهو ما قد يكرر مقطعًا صوتيًا قصيرًا.
                val completedTrack = track
                if (!stopped && outputDone && completedTrack != null) {
                    val drainDeadline = System.currentTimeMillis() +
                        (totalFramesWritten * 1000L / rate).coerceAtLeast(250L) + 1500L
                    while (!stopped &&
                        completedTrack.playbackHeadPosition.toLong() < totalFramesWritten &&
                        System.currentTimeMillis() < drainDeadline
                    ) {
                        try { Thread.sleep(5L) } catch (_: InterruptedException) { break }
                    }
                }
            } catch (_: Exception) {
                // الملف قد يكون بصيغة لا يدعمها مفكك الهاتف؛ لا نكسر بقية المشروع.
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                try { codec?.stop() } catch (_: Exception) { }
                try { codec?.release() } catch (_: Exception) { }
                extractor.release()
                audioTrack = null
                if (!stopped && generation == playbackGeneration) handler.post(finished)
            }
        }
    }
    private fun stopPlayersAtOrBeforePlayhead() {
        players.toMap().forEach { (id, player) ->
            val clip = clips.firstOrNull { it.id == id } ?: return@forEach
            if (clip.endMs() <= playheadMs) {
                player.release()
                players.remove(id)
            }
        }
    }
    private fun continuePlaybackFromCurrentPosition() {
        if (!isPlaying) return
        val remaining = clips.filter {
            !it.muted && it.endMs() > playheadMs &&
                !completedClipIds.contains(it.id) &&
                !players.containsKey(it.id) && !scheduledStarts.contains(it.id)
        }
        if (remaining.isEmpty()) {
            isPlaying = false
            updatePlaybackButton()
            handler.removeCallbacks(playbackTicker)
            refreshTimeline()
            return
        }
        val generation = playbackGeneration
        remaining.forEach { clip ->
            val delay = (clip.startMs - playheadMs).coerceAtLeast(0L)
            if (delay == 0L) playClip(clip) else {
                scheduledStarts.add(clip.id)
                handler.postDelayed({
                    // تجاهل أي أمر مؤجل من جلسة تشغيل قديمة بعد القص أو التحريك.
                    if (generation != playbackGeneration) return@postDelayed
                    scheduledStarts.remove(clip.id)
                    if (isPlaying && !completedClipIds.contains(clip.id) && clip.endMs() > playheadMs) {
                        playClip(clip)
                    }
                }, delay)
            }
        }
    }
    private fun seekPlaybackTo(positionMs: Long) {
        if (!isPlaying) {
            playheadMs = positionMs.coerceIn(0L, projectEndMs())
            refreshTimeline()
            return
        }
        releasePlayers()
        completedClipIds.clear()
        playheadMs = positionMs.coerceIn(0L, projectEndMs())
        playbackClockPosition = playheadMs
        playbackClockStartedAt = System.currentTimeMillis()
        isPlaying = true
        updatePlaybackButton()
        continuePlaybackFromCurrentPosition()
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }
    private fun togglePlayback() {
        if (isPlaying) {
            pausePlayback()
            return
        }

        // تشغيل جديد بعد الوصول للنهاية يبدأ من الصفر تلقائيًا.
        if (playheadMs >= projectEndMs()) {
            playheadMs = 0L
            panToPlayheadIfNeeded()
            refreshTimeline()
        }
        val targets = clips.filter {
            !it.muted && it.endMs() > playheadMs
        }
        if (targets.isEmpty()) {
            showStatus("لا يوجد مقطع قابل للتشغيل عند هذا الموضع")
            return
        }
        releasePlayers()
        completedClipIds.clear()
        playbackClockPosition = playheadMs
        playbackClockStartedAt = System.currentTimeMillis()
        isPlaying = true
        continuePlaybackFromCurrentPosition()
        showStatus(if (targets.size > 1) "▶ تشغيل ${targets.size} مقاطع معًا" else "▶ جارٍ التشغيل")
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun playClip(clip: AudioClip) {
        if (!isPlaying || clip.endMs() <= playheadMs) return
        val offset = (playheadMs - clip.startMs).coerceAtLeast(0L)
        val player = PreciseAudioPlayer(clip, offset, playbackGeneration) {
            players.remove(clip.id)
            completedClipIds.add(clip.id)
            // لا نقفز بساعة الواجهة إلى نهاية المقطع: الساعة مستقلة عن سرعة
            // فك الترميز، والقفز هنا كان يحرّك المؤشر فجأة وقد يتخطى مقاطع قصيرة.
            if (isPlaying && players.isEmpty() && scheduledStarts.isEmpty()) {
                continuePlaybackFromCurrentPosition()
            }
        }
        players[clip.id] = player
        player.start()
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            try {
                val elapsed = (System.currentTimeMillis() - playbackClockStartedAt).coerceAtLeast(0L)
                playheadMs = (playbackClockPosition + elapsed).coerceAtMost(projectEndMs())
                if (playheadMs >= projectEndMs()) {
                    playheadMs = projectEndMs()
                    if (players.isEmpty() && scheduledStarts.isEmpty()) {
                        isPlaying = false
                        updatePlaybackButton()
                        handler.removeCallbacks(this)
                    }
                    refreshTimeline()
                    if (isPlaying) handler.postDelayed(this, 80L)
                    return
                }
                if (players.isEmpty() && scheduledStarts.isEmpty()) {
                    continuePlaybackFromCurrentPosition()
                }
                panToPlayheadIfNeeded()
                refreshTimeline()
            } catch (_: Exception) { }
            if (isPlaying) handler.postDelayed(this, 80L)
        }
    }
    private fun pausePlayback() {
        if (isPlaying) {
            val elapsed = (System.currentTimeMillis() - playbackClockStartedAt).coerceAtLeast(0L)
            playheadMs = (playbackClockPosition + elapsed).coerceIn(0L, projectEndMs())
            playbackClockPosition = playheadMs
            playbackClockStartedAt = System.currentTimeMillis()
        }
        // release بدل pause يمنع مخزن الصوت من إخراج جزء إضافي بعد الضغط.
        // كما يلغي أي تشغيل مؤجل للمقطع التالي في اللحظة نفسها.
        releasePlayers()
        updatePlaybackButton()
        refreshTimeline()
        showStatus("تم الإيقاف المؤقت")
    }

    private fun releasePlayers() {
        // إبطال كل callbacks المؤجلة حتى لا تعيد تشغيل نسخة قديمة من المقطع.
        playbackGeneration += 1L
        players.values.forEach { it.release() }
        players.clear()
        scheduledStarts.clear()
        completedClipIds.clear()
        isPlaying = false
        handler.removeCallbacks(playbackTicker)
    }

    private fun stopPlayback() {
        releasePlayers()
        updatePlaybackButton()
        playheadMs = 0L
        panToPlayheadIfNeeded()
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
            handler.removeCallbacks(recordingTicker)
            handler.post(recordingTicker)
            showStatus("● تسجيل جارٍ 00:00.000")
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
            handler.removeCallbacks(recordingTicker)
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

        val recordLength = elapsed.coerceAtLeast(1L)
        val targetTrack = (0 until tracks).firstOrNull {
            canPlace(null, playheadMs, it, recordLength)
        }
        if (targetTrack == null) {
            file.delete()
            showStatus("لا يوجد مسار خالٍ للتسجيل؛ أضف مسارًا أولًا")
            return
        }
        rememberUndo()
        val clip = AudioClip(
            id = System.nanoTime(),
            uri = Uri.fromFile(file).toString(),
            name = "تسجيل جديد",
            track = targetTrack,
            startMs = playheadMs,
            durationMs = recordLength,
            color = paletteColor(clips.size)
        )
        clips.add(clip)
        prepareWaveform(clip)
        selectedId = clip.id
        refreshTimeline()
        saveLocalProject()
        showStatus("تم حفظ التسجيل وإضافته للمشروع • ${formatTime(elapsed)}")
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
        obj.put("version", 2)
        obj.put("projectName", projectName)
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

    private fun repairTrackLayout() {
        val placed = mutableListOf<AudioClip>()
        val ordered = clips.sortedWith(compareBy<AudioClip> { it.startMs }.thenBy { it.id })
        ordered.forEach { clip ->
            val currentFree = placed.none { it.track == clip.track && rangesOverlap(clip.startMs, clip.endMs(), it.startMs, it.endMs()) }
            if (!currentFree) {
                val free = (0 until 20).firstOrNull { track ->
                    placed.none { it.track == track && rangesOverlap(clip.startMs, clip.endMs(), it.startMs, it.endMs()) }
                }
                if (free != null) clip.track = free
            }
            placed.add(clip)
        }
        tracks = max(tracks, (clips.maxOfOrNull { it.track } ?: 0) + 1).coerceAtMost(20)
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
                        track = c.optInt("track", 0).coerceIn(0, 19),
                        startMs = c.optLong("startMs", 0L).coerceAtLeast(0L),
                        durationMs = duration,
                        trimStartMs = trimStart,
                        trimEndMs = trimEnd,
                        volume = c.optDouble("volume", 1.0)
                            .toFloat().coerceIn(0f, 1f),
                        muted = c.optBoolean("muted", false),
                        color = c.optInt("color", paletteColor(i))
                    )
                )
            }

            clips.clear()
            clips.addAll(loaded)
            projectName = obj.optString("projectName", projectName).ifBlank { "مشروعي الصوتي" }
            repairTrackLayout()

            tracks = max(
                max(1, obj.optInt("tracks", 3)),
                (clips.maxOfOrNull { it.track } ?: 0) + 1
            ).coerceAtMost(20)

            playheadMs = obj.optLong("playheadMs", 0L).coerceAtLeast(0L)
            zoom = obj.optDouble("zoom", 70.0)
                .toFloat().coerceIn(0.005f, 5000f)

            clips.forEach { prepareWaveform(it) }
            selectedId = clips.firstOrNull()?.id
            refreshTimeline()
        } catch (e: Exception) {
            showStatus("ملف المشروع غير صالح: ${e.message}")
        }
    }

    private fun prepareWaveform(clip: AudioClip) {
        if (clip.waveform.isNotEmpty() || clip.uri.isBlank()) return
        // لا نفك ملفًا طويلًا كاملًا إلى الذاكرة؛ هذا كان سبب الإغلاق مع التسجيلات الطويلة.
        if (clip.durationMs > 120_000L) {
            clip.waveform = FloatArray(128) { i ->
                (0.12 + 0.48 * kotlin.math.abs(kotlin.math.sin((i + (clip.id % 31)).toDouble() * 0.37)) * (0.55 + 0.45 * kotlin.math.abs(kotlin.math.sin(i * 0.11)))).toFloat().coerceIn(0.06f, 0.62f)
            }
            timeline.invalidate()
            return
        }
        Thread {
            try {
                val decoded = decodePcm(Uri.parse(clip.uri))
                val channels = decoded.channels.coerceAtLeast(1)
                val frames = decoded.samples.size / channels
                val bins = 128
                val values = FloatArray(bins)
                for (bin in 0 until bins) {
                    val from = (bin.toLong() * frames / bins).toInt()
                    val to = (((bin + 1L) * frames) / bins).toInt().coerceAtMost(frames)
                    var peak = 0; var i = from * channels
                    while (i < to * channels) { peak = max(peak, kotlin.math.abs(decoded.samples[i].toInt())); i++ }
                    values[bin] = (peak / 32768f).coerceIn(0.025f, 1f)
                }
                handler.post { clip.waveform = values; timeline.invalidate() }
            } catch (_: Exception) { }
        }.start()
    }
    private fun saveLocalProject() {
        val json = projectJson()
        val projects = JSONObject(prefs.getString("projects", "{}") ?: "{}")
        projects.put(projectName, json)
        prefs.edit().putString("project", json).putString("projects", projects.toString())
            .putString("currentProject", projectName).apply()
        status.text = "محفوظ تلقائيًا • $projectName"
    }

    private fun restoreLocalProject() {
        val saved = prefs.getString("project", null) ?: return
        loadProjectJson(saved)
        status.text = "تمت استعادة $projectName"
    }

    private fun showProjects() {
        val projects = JSONObject(prefs.getString("projects", "{}") ?: "{}")
        val names = mutableListOf<String>()
        val keys = projects.keys()
        while (keys.hasNext()) names.add(keys.next())
        if (names.isEmpty()) names.add(projectName)
        AlertDialog.Builder(this).setTitle("مشاريعك المحفوظة")
            .setItems(names.toTypedArray()) { _, which ->
                val chosen = names[which]
                val json = projects.optString(chosen, null)
                if (!json.isNullOrBlank()) {
                    releasePlayers(); loadProjectJson(json); saveLocalProject()
                    showStatus("تم فتح مشروع $chosen")
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun newProject() {
        val input = EditText(this).apply { hint = "مثال: صوت البقرة" }
        AlertDialog.Builder(this).setTitle("اسم المشروع الجديد")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "مشروع جديد" }
                releasePlayers(); rememberUndo(); clips.clear(); selectedId = null
                playheadMs = 0L; projectName = name
                refreshTimeline(); saveLocalProject(); showStatus("تم إنشاء $name")
            }.show()
    }

    private fun renameProject() {
        val input = EditText(this).apply { setText(projectName); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("إعادة تسمية المشروع").setView(input)
            .setNegativeButton("إلغاء", null).setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { projectName = name; saveLocalProject(); refreshTimeline() }
            }.show()
    }

    private fun ensureWavExtension(uri: Uri): Uri {
        val current = queryName(uri).trim()
        if (current.lowercase().endsWith(".wav")) return uri
        val base = current.substringBeforeLast('.', current).trim().ifEmpty { "SahlAudio-final" }
        val fixedName = "$base.wav"
        return try {
            DocumentsContract.renameDocument(contentResolver, uri, fixedName) ?: uri
        } catch (_: Exception) {
            uri
        }
    }
    private fun chooseFinalAudioExport() {
        if (clips.isEmpty()) { showStatus("أضف مقطعًا واحدًا على الأقل قبل التصدير"); return }
        val safeName = projectName.replace(Regex("[^A-Za-z0-9_\u0600-\u06FF -]"), "_").trim().ifEmpty { "SahlAudio" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav"))
            putExtra(Intent.EXTRA_TITLE, "${safeName}-final.wav")
        }
        startActivityForResult(intent, REQUEST_EXPORT_AUDIO)
    }

    private fun shareLastExportedAudio() {
        val uri = lastExportedAudioUri
        if (uri == null) {
            showStatus("صدّر ملف WAV أولًا ثم اضغط مشاركة")
            return
        }
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "مشاركة الصوت عبر…"))
        } catch (e: Exception) {
            showStatus("تعذرت مشاركة الصوت: ${e.message ?: "لا توجد تطبيقات مشاركة"}")
        }
    }

    private data class DecodedPcm(val rate: Int, val channels: Int, val samples: ShortArray)
    private fun sourceAudioFormat(uri: Uri): Pair<Int, Int> {
        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path ?: "")
            else extractor.setDataSource(this, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return format.getInteger(MediaFormat.KEY_SAMPLE_RATE) to
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            extractor.release()
        }
        return 44100 to 2
    }

    private fun decodePcm(uri: Uri): DecodedPcm {
        val extractor = MediaExtractor()
        if (uri.scheme == "file") extractor.setDataSource(uri.path ?: "")
        else extractor.setDataSource(this, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) throw IllegalArgumentException("لا يوجد مسار صوتي")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("ترميز غير معروف")
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0); codec.start()
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes); output.write(bytes)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release(); extractor.release()
        }
        val raw = output.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return DecodedPcm(rate, channels, shorts)
    }

    private fun resample(pcm: DecodedPcm, targetRate: Int, targetChannels: Int): ShortArray {
        if (pcm.rate == targetRate && pcm.channels == targetChannels) return pcm.samples
        val frames = pcm.samples.size / pcm.channels
        val targetFrames = (frames.toLong() * targetRate / pcm.rate).toInt().coerceAtLeast(1)
        val result = ShortArray(targetFrames * targetChannels)
        for (frame in 0 until targetFrames) {
            val source = (frame.toDouble() * pcm.rate / targetRate).toInt().coerceIn(0, frames - 1)
            for (ch in 0 until targetChannels) {
                val sourceCh = if (pcm.channels == 1) 0 else ch.coerceAtMost(pcm.channels - 1)
                result[frame * targetChannels + ch] = pcm.samples[source * pcm.channels + sourceCh]
            }
        }
        return result
    }

    private fun writeWav(uri: Uri, mix: IntArray, rate: Int, channels: Int) {
        contentResolver.openOutputStream(uri)?.use { stream ->
            val dataSize = mix.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + dataSize); header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(channels.toShort())
            header.putInt(rate); header.putInt(rate * channels * 2); header.putShort((channels * 2).toShort()); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(dataSize); stream.write(header.array())
            val bytes = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var index = 0
            while (index < mix.size) {
                bytes.clear(); val count = min(8 * 1024, mix.size - index)
                repeat(count) { bytes.putShort(mix[index++].coerceIn(-32768, 32767).toShort()) }
                stream.write(bytes.array(), 0, bytes.position())
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportFinalAudio(uri: Uri) {
        val snapshot = clips.map { it.copy() }
        val snapshotEndMs = max(1_000L, snapshot.maxOfOrNull { it.endMs() } ?: 1_000L)
        showStatus("جارٍ تجهيز المزيج النهائي…")
        Thread {
            try {
                // استخدم صيغة أول مقطع فعلي بدل فرض 44.1 kHz وستيريو على كل الملفات.
                // عند تطابق الصيغة لا تتم أي إعادة أخذ عينات إطلاقًا.
                val reference = snapshot.firstOrNull { !it.muted && it.lengthMs() > 0L }
                val (targetRate, targetChannels) = reference?.let {
                    sourceAudioFormat(Uri.parse(it.uri))
                } ?: (44100 to 2)
                val totalFrames = (snapshotEndMs * targetRate / 1000L).toInt().coerceAtLeast(1)
                val mix = IntArray(totalFrames * targetChannels)
                snapshot.forEachIndexed { index, clip ->
                    if (clip.muted || clip.lengthMs() <= 0L) return@forEachIndexed
                    val pcm = resample(decodePcm(Uri.parse(clip.uri)), targetRate, targetChannels)
                    val sourceStart = (clip.trimStartMs.toDouble() * targetRate / 1000).toInt().coerceAtLeast(0)
                    val sourceFrames = (clip.lengthMs().toDouble() * targetRate / 1000).toInt()
                    val destStart = (clip.startMs * targetRate / 1000L).toInt()
                    val frames = min(sourceFrames, pcm.size / targetChannels - sourceStart)
                    for (frame in 0 until frames) for (ch in 0 until targetChannels) {
                        val dest = (destStart + frame) * targetChannels + ch
                        if (dest in mix.indices) mix[dest] += (pcm[(sourceStart + frame) * targetChannels + ch] * clip.volume).toInt()
                    }
                    handler.post { status.text = "جاري مزج ${index + 1}/${snapshot.size}…" }
                }
                writeWav(uri, mix, targetRate, targetChannels)
                handler.post {
                    lastExportedAudioUri = uri
                    showStatus("تم تصدير الصوت النهائي بنجاح؛ اضغط مشاركة لإرساله")
                }
            } catch (e: Exception) {
                handler.post { showStatus("فشل التصدير: ${e.message ?: "صيغة غير مدعومة"}") }
            }
        }.start()
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
        private val trackHeight = dp(66f)
        private val rulerHeight = dp(32f)
        private val leftGutter = dp(72f)
        private var panX = 0f
        private var panY = 0f

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragMode = "none"
        private var dragId: Long? = null
        private var changed = false
        private var beforeDrag = ""

        private var originalStart = 0L
        private var originalTrimStart = 0L
        private var originalTrimEnd = 0L

        private fun timeToX(ms: Long): Float =
            (leftGutter.toDouble() + ms.toDouble() / 1000.0 * zoom.toDouble() - panX.toDouble()).toFloat()

        private fun xToTime(x: Float): Long =
            (((x.toDouble() + panX.toDouble() - leftGutter.toDouble()) /
                zoom.toDouble()).coerceAtLeast(0.0) * 1000.0).roundToLong()

        private fun maxPanX(): Float =
            (leftGutter.toFloat() + projectEndMs() / 1000f * zoom + dp(100f) - width).coerceAtLeast(0f)

        private fun maxPanY(): Float =
            (rulerHeight + tracks * trackHeight - height).toFloat().coerceAtLeast(0f)

        fun keepPlayheadVisible() {
            val x = timeToX(playheadMs)
            if (x < leftGutter + dp(12f)) panX = (panX - (leftGutter + dp(12f) - x)).coerceAtLeast(0f)
            else if (x > width - dp(12f)) panX = (panX + (x - (width - dp(12f)))).coerceIn(0f, maxPanX())
            invalidate()
        }
        fun zoomAroundPlayhead(factor: Float) {
            val oldScreenX = timeToX(playheadMs)
            zoom = (zoom * factor).coerceIn(0.005f, 5000f)
            val newContentX = leftGutter + playheadMs / 1000f * zoom
            panX = (newContentX - oldScreenX).coerceIn(0f, maxPanX())
            invalidate()
        }

        private fun rulerStepMs(): Long {
            val targetMs = (90000f / zoom).toLong().coerceAtLeast(1L)
            var base = 1L
            while (base * 10L <= targetMs) base *= 10L
            for (multiplier in longArrayOf(1L, 2L, 5L, 10L)) {
                val candidate = base * multiplier
                if (candidate >= targetMs) return candidate
            }
            return base * 10L
        }
        private fun rulerLabel(ms: Long, step: Long): String {
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds / 60L) % 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L || step >= 3_600_000L -> "%d:%02d:%02d".format(hours, minutes, seconds)
                step >= 60_000L -> "%02d:%02d".format(totalSeconds / 60L, seconds)
                step < 1_000L -> "${totalSeconds}.${(ms % 1000L) / 100L}"
                else -> "%02d:%02d".format(totalSeconds / 60L, seconds)
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)

            paint.color = PANEL
            canvas.drawRect(
                0f, 0f, width.toFloat(), rulerHeight.toFloat(), paint
            )

            val end = projectEndMs()
            val step = rulerStepMs()

            var time = 0L

            while (time <= end + step) {
                val x = timeToX(time)

                paint.color = 0xFF29382F.toInt()
                canvas.drawLine(
                    x, rulerHeight.toFloat(), x, height.toFloat(), paint
                )

                paint.color = MUTED
                paint.textSize = dp(10f).toFloat()
                canvas.drawText(
                    rulerLabel(time, step),
                    x + dp(3f),
                    dp(23f).toFloat(),
                    paint
                )

                time += step
            }

            for (track in 0 until tracks) {
                val top = rulerHeight + track * trackHeight - panY

                paint.color = if (track % 2 == 0) {
                    if (isDarkTheme) 0xFF17223A.toInt() else 0xFFFFEFE0.toInt()
                } else {
                    if (isDarkTheme) 0xFF1B2944.toInt() else 0xFFFFE7D2.toInt()
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
                val top = rulerHeight + clip.track * trackHeight + dp(7f) - panY
                val bottom = rulerHeight +
                    (clip.track + 1) * trackHeight - dp(7f) - panY

                paint.color = clip.color

                canvas.drawRoundRect(
                    x1,
                    top.toFloat(),
                    max(x1 + dp(10f), x2),
                    bottom.toFloat(),
                    dp(7f).toFloat(),
                    dp(7f).toFloat(),
                    paint
                )

                paint.color = WHITE
                paint.textSize = dp(11f).toFloat()

                canvas.drawText(
                    clip.name.take(20),
                    x1 + dp(6f),
                    (top + dp(14f)).toFloat(),
                    paint
                )
                // موجة حقيقية من عينات الملف، clipped داخل المستطيل.
                canvas.save()
                canvas.clipRect(leftGutter, rulerHeight, width, height)
                paint.color = 0xB5DDEBDF.toInt()
                paint.strokeWidth = dp(1f).toFloat()
                val bars = ((x2 - x1) / dp(4f)).toInt().coerceAtMost(240).coerceAtLeast(1)
                for (bar in 0 until bars) {
                    val bx = x1 + dp(3f) + bar * dp(4f)
                    if (bx > x2 - dp(2f)) break
                    val amp = if (clip.waveform.isNotEmpty()) clip.waveform[bar * clip.waveform.size / bars] else 0.035f
                    val mid = (top + bottom) / 2f
                    canvas.drawLine(bx, mid - (bottom - top) * amp * .42f, bx, mid + (bottom - top) * amp * .42f, paint)
                }
                canvas.restore()

                if (clip.id == selectedId) {
                    paint.color = 0x35FFFFFF
                    canvas.drawRoundRect(x1, top.toFloat(), max(x1 + dp(10f), x2), bottom.toFloat(), dp(7f).toFloat(), dp(7f).toFloat(), paint)
                    paint.color = GOLD
                    paint.strokeWidth = dp(3f).toFloat()
                    canvas.drawLine(
                        x1, top.toFloat(), x1, bottom.toFloat(), paint
                    )
                    canvas.drawLine(
                        x2, top.toFloat(), x2, bottom.toFloat(), paint
                    )
                    canvas.drawCircle(x2, (top + bottom) / 2f, dp(7f).toFloat(), paint)
                    paint.color = BG
                    canvas.drawCircle(x2, (top + bottom) / 2f, dp(3f).toFloat(), paint)
                }
            }

            val projectEndX = timeToX(end)
            if (projectEndX >= leftGutter && projectEndX <= width) {
                paint.color = GOLD
                paint.strokeWidth = dp(2f).toFloat()
                canvas.drawLine(projectEndX, rulerHeight.toFloat(), projectEndX, height.toFloat(), paint)
                canvas.drawRect(projectEndX - dp(4f), rulerHeight.toFloat(), projectEndX + dp(4f), rulerHeight + dp(10f).toFloat(), paint)
            }
            // طبقة ثابتة: المسطرة الزمنية والعمود الأيسر لا يتحركان مع المحتوى.
            paint.color = BG
            canvas.drawRect(0f, rulerHeight.toFloat(), leftGutter.toFloat(), height.toFloat(), paint)
            for (track in 0 until tracks) {
                val top = rulerHeight + track * trackHeight - panY
                if (top + trackHeight >= rulerHeight && top <= height) {
                    paint.color = MUTED; paint.textSize = dp(10f).toFloat()
                    canvas.drawText("مسار ${track + 1}", dp(8f).toFloat(), top + dp(21f), paint)
                }
            }
            paint.color = PANEL
            canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight.toFloat(), paint)
            time = 0L
            while (time <= end + step) {
                val x = timeToX(time)
                if (x >= leftGutter && x <= width) {
                    paint.color = MUTED; paint.textSize = dp(9f).toFloat()
                    canvas.drawText(rulerLabel(time, step), x + dp(3f), dp(21f).toFloat(), paint)
                }
                time += step
            }
            val px = timeToX(playheadMs)
            paint.color = 0xFFD88E87.toInt()
            paint.strokeWidth = dp(2f).toFloat()
            canvas.drawLine(px, 0f, px, height.toFloat(), paint)
            canvas.drawCircle(px, dp(5f).toFloat(), dp(5f).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    changed = false
                    dragId = null

                    beforeDrag = projectJson()

                    val px = timeToX(playheadMs)

                    if (abs(event.x - px) < dp(18f)) {
                        dragMode = "playhead"
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    if (event.y < rulerHeight) {
                        dragMode = "playhead"
                        playheadMs = xToTime(event.x).coerceAtMost(projectEndMs())
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }

                    val track = (
                        (event.y + panY - rulerHeight) / trackHeight
                    ).toInt().coerceIn(0, tracks - 1)

                    val hit = clips.asReversed().firstOrNull {
                        it.track == track &&
                            event.x >= timeToX(it.startMs) - dp(4f) &&
                            event.x <= timeToX(it.endMs()) + dp(4f)
                    }

                    if (hit == null) {
                        selectedId = null
                        // الضغطة في الفراغ تلغي التحديد؛ السحب من الفراغ يحرك اللوحة.
                        dragMode = "pending"
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (selectedId != hit.id) {
                        // الضغطة الأولى للتحديد فقط؛ لا يبدأ التحريك إلا بعد تحديد سابق.
                        selectedId = hit.id
                        dragMode = "none"
                    } else {
                        dragId = hit.id
                        originalStart = hit.startMs
                        originalTrimStart = hit.trimStartMs
                        originalTrimEnd = hit.trimEndMs
                        // عند التصغير قد تصبح حافتا المقطع أقرب من مساحة اللمس
                        // القديمة (22dp)، فتُختار حافة خطأ ويبدو القص غير دقيق.
                        val clipPixelWidth = abs(timeToX(hit.endMs()) - timeToX(hit.startMs))
                    val edgeHit = min(dp(22f).toFloat(), max(dp(5f).toFloat(), clipPixelWidth * 0.18f))
                        val nearLeft = abs(event.x - timeToX(hit.startMs)) < edgeHit
                        val nearRight = abs(event.x - timeToX(hit.endMs())) < edgeHit
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

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        dragMode = "pan"
                        lastX = event.x
                        lastY = event.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val clip = clips.firstOrNull { it.id == dragId }

                    when (dragMode) {
                        "pending" -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (abs(dx) > dp(8f) || abs(dy) > dp(8f)) {
                                dragMode = "pan"
                                panX = (panX + downX - event.x).coerceIn(0f, maxPanX())
                                panY = (panY + downY - event.y).coerceIn(0f, maxPanY())
                                lastX = event.x; lastY = event.y
                            }
                            invalidate()
                        }
                        "pan" -> {
                            panX = (panX + lastX - event.x).coerceIn(0f, maxPanX())
                            panY = (panY + lastY - event.y).coerceIn(0f, maxPanY())
                            invalidate()
                        }
                        "playhead" -> {
                            playheadMs = xToTime(event.x)
                                .coerceAtMost(projectEndMs())
                            changed = true
                        }

                        "clip" -> {
                            if (clip != null) {
                                val raw = originalStart +
                                    ((event.x - downX).toDouble() / zoom.toDouble() * 1000.0).roundToLong()
                                val proposedStart = raw.coerceAtLeast(0L)
                                val newTrack = ((event.y + panY - rulerHeight) / trackHeight.toFloat())
                                    .toInt().coerceIn(0, tracks - 1)
                                if (canPlace(clip, proposedStart, newTrack, clip.lengthMs())) {
                                    clip.startMs = proposedStart
                                    clip.track = newTrack
                                    changed = true
                                }
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
                                    newStart < clip.endMs() &&
                                    canPlace(clip, newStart, clip.track, originalTrimEnd - source)
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
                                    sourceEnd <= clip.durationMs &&
                                    canPlace(clip, clip.startMs, clip.track, newLength)
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
                    if (event.actionMasked == MotionEvent.ACTION_UP && dragMode == "playhead") {
                        seekPlaybackTo(playheadMs)
                    }
                    if (changed && dragMode != "playhead" && dragMode != "pan") {
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
        permissions: Array<String>,
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
        releasePlayers()

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null
        super.onDestroy()
    }
}
