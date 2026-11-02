package com.quranayat.split

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
import com.naman14.androidlame.LameBuilder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.AssetFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
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

private var BG = 0xFF1B1028.toInt()
private var PANEL = 0xFF352044.toInt()
private var GREEN = 0xFFF0A66A.toInt()
private var GOLD = 0xFF6FE7D4.toInt()
private var WHITE = 0xFFFFF7F0.toInt()
private var MUTED = 0xFFCDBBD7.toInt()

private const val REQUEST_AUDIO = 1001
private const val REQUEST_IMPORT_PROJECT = 1002
private const val REQUEST_EXPORT_PROJECT = 1003
private const val REQUEST_MICROPHONE = 1004
private const val REQUEST_EXPORT_AUDIO = 1005
private const val REQUEST_EXPORT_FOLDER = 1006

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
    var waveform: FloatArray = floatArrayOf(),
    var exportFolder: String? = null,
    var waveformLoading: Boolean = false
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
    private val waveformReadyCallbacks = mutableMapOf<Long, MutableList<() -> Unit>>()
    private var tracks = 3
    private var playheadMs = 0L
    private var zoom = 70f
    private var selectedId: Long? = null
    private var visibleSourceId: Long? = null
    private var projectName = "مشروعي الصوتي"
    private var projectId = java.util.UUID.randomUUID().toString()
    private var projectTitleView: TextView? = null

    private val players = mutableMapOf<Long, MediaPlayer>()
    private val scheduledStarts = mutableSetOf<Long>()
    // كل جلسة تشغيل لها رقم مستقل؛ يمنع callbacks القديمة من إحياء تشغيل سابق.
    private val scheduledStartCallbacks = mutableMapOf<Long, Runnable>()
    private var playbackGeneration = 0L
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var isPlaying = false
    private var playbackButton: Button? = null
    private var isRecording = false
    private var isDarkTheme = true
    private var themeMode = 0
    private var startNumber = 1
    private val cutMarks = mutableListOf<Long>()
    private val pauseMarks = mutableListOf<Pair<Int, Long>>()
    private var matrixLabel: TextView? = null
    private var pendingFolderClipId: Long? = null
    private var lastErrorText = ""
    @Volatile private var isExporting = false
    private var previewSpeed = 1.0f
    private var speedLabel: TextView? = null
    private var selectedCutIndex: Int? = null
    private var selectedPauseIndex: Int? = null

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
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val incoming = mutableListOf<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { incoming.add(it) }
        intent.clipData?.let { for (i in 0 until it.itemCount) incoming.add(it.getItemAt(i).uri) }
        if (incoming.isEmpty()) return
        incoming.distinct().forEach { addSharedAudio(it) }
    }

    private fun addSharedAudio(uri: Uri) {
        try {
            val duration = getDurationFlexible(uri)
            if (duration <= 0L) {
                lastErrorText = "تعذرت قراءة مدة الملف المشارك\nالمصدر: $uri"
                showStatus("تعذرت قراءة الملف المشارك؛ اضغط نسخ آخر خطأ")
                return
            }
            rememberUndo()
            val track = (0 until tracks).firstOrNull { canPlace(null, 0L, it, duration) }
                ?: run { showStatus("لا يوجد مسار خالٍ للملف المشارك"); return }
            val clip = AudioClip(System.nanoTime(), uri.toString(), queryName(uri), track, 0L, duration, color = paletteColor(clips.size))
            clips.add(clip)
            if (visibleSourceId == null) visibleSourceId = clip.id
            selectedId = clip.id
            prepareWaveform(clip)
            refreshTimeline(); saveLocalProject()
            showStatus("تم استقبال الملف المشارك: ${clip.name}")
        } catch (e: Exception) {
            lastErrorText = "فشل استقبال الملف المشارك\n${e::class.java.name}: ${e.message}\nالمصدر: $uri"
            showStatus("فشل استقبال الملف؛ اضغط نسخ آخر خطأ")
        }
    }

    private fun scrollRow(row: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(row, ViewGroup.LayoutParams(-1, -2))
    }

    private fun buildUi() {
        // واجهة جديدة بالكامل: محرر داكن بطابع استوديو صوتي حديث.
        val page = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BG)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(10f), dp(7f), dp(10f), dp(92f))
        }
        page.addView(root, ViewGroup.LayoutParams(-1, -2))

        fun rounded(color: Int, stroke: Int = 0xFF35415F.toInt(), radius: Float = 18f): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != 0) setStroke(dp(1f), stroke)
            }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = rounded(0xFF202B43.toInt(), 0xFF34415F.toInt(), 15f)
        }

        fun addCard(view: View, top: Float = 8f) {
            root.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(top)
            })
        }

        fun label(text: String, size: Float = 13f, color: Int = WHITE, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            }

        fun action(text: String, accent: Int? = null, compact: Boolean = false, run: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = if (compact) 11f else 12f
                setTextColor(WHITE)
                minWidth = 0; minimumWidth = 0
                minHeight = dp(if (compact) 38f else 44f); minimumHeight = minHeight
                setPadding(dp(if (compact) 8f else 12f), 0, dp(if (compact) 8f else 12f), 0)
                background = rounded(accent ?: 0xFF293653.toInt(), 0xFF435477.toInt(), 12f)
                setOnClickListener { run() }
            }

        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun horizontal(items: List<View>, gap: Int = 6) {
            val r = row()
            items.forEachIndexed { index, item ->
                r.addView(item, LinearLayout.LayoutParams(-2, -2).apply {
                    if (index > 0) marginStart = dp(gap.toFloat())
                })
            }
            root.addView(scrollRow(r), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5f) })
        }

        // رأس التطبيق
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(4f), dp(2f), dp(8f))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(label("SAHL AUDIO", 10f, 0xFF7D91B8.toInt(), true))
            addView(label("استوديو تقسيم الآيات", 20f, WHITE, true))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("حفظ", 0xFF287D70.toInt(), true) { saveLocalProject() })
        header.addView(action("⋮", 0xFF303D5B.toInt(), true) { showProjects() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(42f)).apply { marginStart = dp(5f) }
        })
        root.addView(header)

        // بطاقة المشروع
        val projectCard = card()
        projectCard.addView(label("المشروع الحالي", 11f, MUTED))
        val projectRow = row()
        projectTitleView = label(projectName, 17f, WHITE, true)
        projectRow.addView(projectTitleView, LinearLayout.LayoutParams(0, dp(42f), 1f))
        projectRow.addView(action("تغيير ▾", 0xFF344666.toInt(), true) { showProjects() })
        projectCard.addView(projectRow)
        horizontal(listOf(
            action("＋ مشروع جديد", 0xFF287D70.toInt(), true) { newProject() },
            action("إعادة تسمية", null, true) { renameProject() },
            action("فتح مشروع", null, true) { importProject() },
            action("تصدير JSON", null, true) { exportProject() },
            action("المظهر", null, true) { toggleTheme() }
        ))
        addCard(projectCard, 2f)

        // مساحة العمل والموجات
        val timelineCard = card()
        val timelineHead = row()
        timelineHead.addView(label("مساحة العمل", 16f, WHITE, true), LinearLayout.LayoutParams(0, dp(34f), 1f))
        timelineHead.addView(label("MULTI TRACK", 9f, 0xFF8FA5D1.toInt(), true))
        timelineCard.addView(timelineHead)
        timelineCard.addView(label("المسطر للانتقال • إصبعان للتكبير • العلامة المحددة تُسحب مرة أخرى للتحريك", 9f, MUTED))
        val workspace = FrameLayout(this).apply {
            background = rounded(0xFF111A2B.toInt(), 0xFF3C4A68.toInt(), 14f)
            clipToOutline = true
        }
        timeline = TimelineView(this)
        workspace.addView(timeline, FrameLayout.LayoutParams(-1, -1))
        // مساحة موجات عملية ومضغوطة؛ ثلاثة مسارات تظل ظاهرة دون استهلاك الشاشة.
        val timelineHeight = dp(236f)
        timelineCard.addView(workspace, LinearLayout.LayoutParams(-1, timelineHeight).apply {
            topMargin = dp(9f)
        })
        val zoomRow = row().apply { gravity = Gravity.CENTER }
        zoomRow.addView(action("−", 0xFF34415F.toInt(), false) { zoomBy(1f / 1.18f) })
        zoomRow.addView(label("التكبير", 11f, MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, dp(42f), 1f))
        zoomRow.addView(action("＋", 0xFF34415F.toInt(), false) { zoomBy(1.18f) })
        timelineCard.addView(zoomRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7f) })
        val editDock = row().apply { gravity = Gravity.CENTER }
        editDock.addView(action("＋ إضافة صوت", 0xFF287D70.toInt(), true) { chooseAudio() })
        editDock.addView(action("حذف المقطع", 0xFF70415A.toInt(), true) { deleteSelected() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        editDock.addView(action("بداية المقطع", null, true) { jumpToSelectedStart() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        timelineCard.addView(editDock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7f) })
        val trackDock = row().apply { gravity = Gravity.CENTER }
        trackDock.addView(action("＋ مسار", null, true) { addTrack() })
        trackDock.addView(action("− مسار", null, true) { confirmDeleteTrack() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        trackDock.addView(action("↑", null, true) { moveSelectedTrack(-1) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        trackDock.addView(action("↓", null, true) { moveSelectedTrack(1) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        trackDock.addView(action("↶ تراجع", null, true) { undo() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        trackDock.addView(action("↷", null, true) { redo() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        timelineCard.addView(trackDock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        addCard(timelineCard, 10f)

        // التشغيل والتسجيل
        val transport = card()
        transport.addView(label("التشغيل والتسجيل والسرعة", 14f, WHITE, true))
        playLabel = label("موضع التشغيل  00:00.000", 11f, GOLD)
        transport.addView(playLabel)
        val transportRow = row().apply { gravity = Gravity.CENTER }
        transportRow.addView(action("● تسجيل", 0xFF9B4058.toInt(), false) { toggleRecording() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(46f))
            textSize = 13f
        })
        transport.addView(transportRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        val speedTitle = row()
        speedLabel = label("سرعة المعاينة  1.0×", 12f, GOLD)
        speedTitle.addView(speedLabel)
        transport.addView(speedTitle, LinearLayout.LayoutParams(-1, dp(30f)).apply { topMargin = dp(6f) })
        val speedSeek = SeekBar(this).apply {
            max = 19
            progress = 9
            isClickable = true
            isFocusable = true
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF72D8B5.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFC978.toInt())
            // امنع ScrollView الأب من خطف حركة الإصبع أثناء سحب مؤشر السرعة.
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // اترك SeekBar نفسه يعالج السحب والنقر.
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    previewSpeed = ((progress + 1) / 10f).coerceIn(0.1f, 2.0f)
                    speedLabel?.text = "سرعة المعاينة  %.1f×".format(java.util.Locale.US, previewSpeed)
                    // لا نلمس MediaPlayer المتوقف أو المجهز؛ تغيير PlaybackParams
                    // على مشغل متوقف قد يوقظه في بعض إصدارات أندرويد.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isPlaying) {
                        players.values.toList().forEach { media ->
                            try {
                                if (media.isPlaying) {
                                    media.playbackParams = PlaybackParams().setSpeed(previewSpeed)
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.parent?.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.parent?.requestDisallowInterceptTouchEvent(false)
                }
            })
        }
        transport.addView(speedSeek, LinearLayout.LayoutParams(-1, dp(52f)))
        transport.addView(label("تغيير السرعة للمعاينة فقط؛ التصدير يحتفظ بالسرعة الأصلية", 10f, MUTED))
        addCard(transport, 9f)

        // أدوات تقسيم الآيات
        val ayatCard = card()
        ayatCard.addView(label("تقسيم الآيات والتصدير", 15f, WHITE, true))
        ayatCard.addView(label("أزمنة السكتات في المصفوفة تُحسب من بداية كل آية", 10f, MUTED))
        horizontal(listOf(
            action("اكتشاف الآيات المحتملة", 0xFF345F78.toInt(), true) { detectPotentialAyat() },
            action("تصدير الآيات", 0xFF287D70.toInt(), true) { exportMarkedSegments() }
        ), 6)
        val numberRow = row()
        numberRow.addView(label("رقم أول تسجيل", 11f, MUTED))
        val numberInput = EditText(this).apply {
            setText("1"); setTextColor(WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
            setSingleLine(); minimumWidth = dp(56f)
            background = rounded(0xFF111A2B.toInt(), 0xFF52617F.toInt(), 10f)
        }
        numberInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) startNumber = numberInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        }
        numberRow.addView(numberInput, LinearLayout.LayoutParams(dp(70f), dp(42f)).apply { marginStart = dp(8f) })
        numberRow.addView(action("مجلد الحفظ", null, true) { chooseExportFolder() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        numberRow.addView(action("نسخ المصفوفة", null, true) { copyPauseMatrix() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        ayatCard.addView(numberRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        matrixLabel = label("السكتات: لا توجد علامات بعد", 11f, MUTED)
        ayatCard.addView(matrixLabel, LinearLayout.LayoutParams(-1, dp(30f)))
        horizontal(listOf(
            action("السابق ◀", 0xFF344666.toInt(), true) { navigateBetweenMarkers(false) },
            action("التالي ▶", 0xFF287D70.toInt(), true) { navigateBetweenMarkers(true) }
        ), 2)
        horizontal(listOf(
            action("حذف العلامة", null, true) { deleteSelectedCutMark() },
            action("حذف السكتة", null, true) { deleteSelectedPauseMark() },
            action("إلغاء التحديد", null, true) { selectedCutIndex = null; selectedPauseIndex = null; timeline.invalidate(); updateMarkerStatus() },
            action("نسخ الخطأ", null, true) { copyLastError() }
        ), 5)
        addCard(ayatCard, 9f)

        // المصدر والتحكم في مستوى الصوت
        val sourceCard = card()
        sourceCard.addView(label("المصدر والصوت", 15f, WHITE, true))
        sourceCard.addView(label("اختر التسجيل الذي تريد إظهاره وتحريره في مساحة العمل", 10f, MUTED))
        sourceCard.addView(action("اختيار التسجيل الظاهر للتحرير", 0xFF344666.toInt(), false) { chooseVisibleSource() },
            LinearLayout.LayoutParams(-1, dp(44f)).apply { topMargin = dp(8f) })
        val volumeRow = row()
        volumeRow.addView(label("مستوى الصوت", 11f, MUTED))
        volumeSeek = SeekBar(this).apply {
            max = 100; progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF72D8B5.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFC978.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val clip = selectedClip() ?: return
                    clip.volume = progress / 100f
                    refreshTimeline()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { rememberUndo() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { saveLocalProject() }
            })
        }
        volumeRow.addView(volumeSeek, LinearLayout.LayoutParams(0, dp(42f), 1f))
        volumeRow.addView(action("كتم / إلغاء", null, true) { toggleMute() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        sourceCard.addView(volumeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        addCard(sourceCard, 9f)

        status = label("جاهز • أضف ملفًا صوتيًا لبدء العمل", 11f, MUTED)
        status.gravity = Gravity.CENTER
        status.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        status.background = rounded(0xFF151F33.toInt(), 0xFF2D3A56.toInt(), 12f)
        addCard(status, 10f)

        // شريط أوامر ثابت أسفل الشاشة: التشغيل، علامة آية، سكتة، وتحويل العلامة.
        // تبقى الأوامر الأساسية ظاهرة حتى عند التمرير داخل بقية الأدوات.
        val shell = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }
        shell.addView(page, FrameLayout.LayoutParams(-1, -1))

        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            background = rounded(0xFF172238.toInt(), 0xFF536487.toInt(), 18f)
            elevation = dp(10f).toFloat()
        }
        fun dockButton(symbol: String, description: String, color: Int, run: () -> Unit): Button =
            action(symbol, color, false, run).apply {
                textSize = if (symbol == "↦Ⅱ") 17f else 20f
                minHeight = dp(48f); minimumHeight = dp(48f)
                setPadding(dp(1f), 0, dp(1f), 0)
                contentDescription = description
                isAllCaps = false
            }
        val dockButtons = listOf(
            dockButton("◀", "تحريك الموجة إلى اليسار وإظهار الجزء التالي", 0xFF344666.toInt()) { timeline.panByPage(1) },
            dockButton("▶", "تحريك الموجة إلى اليمين وإظهار الجزء السابق", 0xFF344666.toInt()) { timeline.panByPage(-1) },
            dockButton("⏵", "تشغيل أو إيقاف مؤقت", 0xFF237C6A.toInt()) { togglePlayback() },
            dockButton("✂", "إضافة علامة بداية آية عند المؤشر", 0xFF91683D.toInt()) { addCutMark() },
            dockButton("Ⅱ", "إضافة سكتة عند المؤشر", 0xFF66538F.toInt()) { addPauseMark() },
            dockButton("↦Ⅱ", "تحويل علامة الآية المحددة إلى سكتة", 0xFF7955A5.toInt()) { convertSelectedCutToPause() }
        )
        playbackButton = dockButtons.getOrNull(2)
        dockButtons.forEachIndexed { index, button ->
            bottomDock.addView(button, LinearLayout.LayoutParams(0, dp(50f), 1f).apply {
                if (index > 0) marginStart = dp(3f)
            })
        }
        shell.addView(bottomDock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(10f); rightMargin = dp(10f)
            bottomMargin = dp(8f)
        })

        setContentView(shell)
        updateMarkerStatus()
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

    private fun visibleSourceClip(): AudioClip? =
        clips.firstOrNull { it.id == visibleSourceId }

    private fun refreshTimeline() {
        timeline.invalidate()
        timeline.requestLayout()
        updateMarkerStatus()

        playLabel.text = "موضع التشغيل: ${formatTime(playheadMs)}"

        projectTitleView?.text = projectName
        selectedClip()?.let { clip ->
            status.text = "${clip.name} • المسار ${clip.track + 1}"
            volumeSeek.progress = (clip.volume * 100).toInt().coerceIn(0, 100)
        }
    }

    private fun zoomBy(factor: Float) {
        // اجعل التكبير مرتبطًا بمؤشر التشغيل المتحرك، لا بمنتصف مساحة الموجة.
        timeline.zoomAroundPlayhead(factor)
    }
    private fun chooseVisibleSource() {
        if (clips.isEmpty()) { showStatus("أضف التسجيلات أولًا"); return }
        val names = clips.mapIndexed { index, clip -> "${index + 1}. ${clip.name}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اختر التسجيل الذي سيظهر للتحرير والسماع")
            .setSingleChoiceItems(names, clips.indexOfFirst { it.id == visibleSourceId }.coerceAtLeast(0)) { dialog, which ->
                visibleSourceId = clips[which].id
                selectedId = clips[which].id
                releasePlayers(); refreshTimeline(); dialog.dismiss()
                showStatus("التسجيل الظاهر: ${clips[which].name} — سيتم تصدير الجميع")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun chooseExportFolder() {
        val clip = selectedClip() ?: run { showStatus("اختر تسجيلًا من الشريط الزمني أولًا"); return }
        pendingFolderClipId = clip.id
        showStatus("اختر مجلد حفظ التسجيل: ${clip.name}")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQUEST_EXPORT_FOLDER)
    }

    private fun requestNextMissingFolder() {
        val clip = clips.firstOrNull { it.exportFolder == null } ?: return
        selectedId = clip.id
        pendingFolderClipId = clip.id
        refreshTimeline()
        showStatus("اختر مجلد حفظ التسجيل: ${clip.name}")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQUEST_EXPORT_FOLDER)
    }

    private fun recalculatePauseAyahs() {
        for (i in pauseMarks.indices) {
            val time = pauseMarks[i].second
            pauseMarks[i] = ayahNumberAt(time) to time
        }
        pauseMarks.sortWith(compareBy({ it.first }, { it.second }))
    }

    private fun deleteSelectedCutMark() {
        val index = selectedCutIndex ?: run { showStatus("حدد علامة على الشريط أولًا"); return }
        if (index !in cutMarks.indices) return
        rememberUndo()
        cutMarks.removeAt(index)
        recalculatePauseAyahs()
        selectedCutIndex = null; selectedPauseIndex = null
        refreshTimeline(); saveLocalProject(); showStatus("تم حذف العلامة المحددة")
    }

    private fun deleteSelectedPauseMark() {
        val index = selectedPauseIndex ?: run { showStatus("حدد سكتة على الشريط أولًا"); return }
        if (index !in pauseMarks.indices) return
        rememberUndo()
        pauseMarks.removeAt(index)
        selectedPauseIndex = null
        refreshTimeline(); saveLocalProject(); showStatus("تم حذف السكتة المحددة")
    }

    private fun cutMoveBounds(index: Int): Pair<Long, Long> {
        val previous = cutMarks.getOrNull(index - 1) ?: 0L
        val next = cutMarks.getOrNull(index + 1) ?: projectEndMs()
        val ayahLeft = startNumber + index
        val ayahRight = ayahLeft + 1
        val leftPauses = pauseMarks.filter { it.first == ayahLeft }.maxOfOrNull { it.second } ?: Long.MIN_VALUE
        val rightPauses = pauseMarks.filter { it.first == ayahRight }.minOfOrNull { it.second } ?: Long.MAX_VALUE
        return max(previous + 20L, if (leftPauses == Long.MIN_VALUE) previous + 20L else leftPauses + 20L) to
            min(next - 20L, if (rightPauses == Long.MAX_VALUE) next - 20L else rightPauses - 20L)
    }

    private fun pauseMoveBounds(index: Int): Pair<Long, Long> {
        val ayah = pauseMarks.getOrNull(index)?.first ?: startNumber
        val segment = (ayah - startNumber).coerceAtLeast(0)
        return (cutMarks.getOrNull(segment - 1)?.plus(20L) ?: 0L) to
            (cutMarks.getOrNull(segment)?.minus(20L) ?: projectEndMs())
    }

    private fun syncPlayheadFromPlayer(): Long {
        // جميع مواضع المشغلات تُحوّل إلى ساعة المشروع نفسها، ثم نأخذ
        // أحدث موضع فعلي من المشغلات التي تصدر الصوت الآن.
        var latestPosition: Long? = null
        clips.forEach { clip ->
            val media = players[clip.id] ?: return@forEach
            try {
                if (media.isPlaying) {
                    val sourcePosition = media.currentPosition.toLong()
                    val projectPosition = (clip.startMs + sourcePosition - clip.trimStartMs)
                        .coerceIn(clip.startMs, clip.endMs())
                    latestPosition = max(latestPosition ?: Long.MIN_VALUE, projectPosition)
                }
            } catch (_: Exception) { }
        }
        latestPosition?.let { playheadMs = it.coerceIn(0L, projectEndMs()) }
        playLabel?.let { it.text = "موضع التشغيل: ${formatTime(playheadMs)}" }
        return playheadMs
    }

    /**
     * يبحث عن فترات الصمت الواضحة داخل التسجيل الظاهر ويقترحها كعلامات قطع.
     * هذا تحليل صوتي تقريبي؛ لا يثبت أن كل موضع هو نهاية آية.
     */
    private fun detectPotentialAyat() {
        if (clips.isEmpty()) { showStatus("أضف تسجيلًا صوتيًا أولًا"); return }
        val clip = visibleSourceClip() ?: selectedClip() ?: run {
            showStatus("اختر التسجيل الذي تريد تحليل مواضع الآيات فيه")
            return
        }
        if (clip.waveform.isEmpty()) {
            showStatus("جار تحليل الموجة الصوتية لاكتشاف فترات الصمت…")
            prepareWaveform(clip) { detectPotentialAyatForClip(clip) }
            return
        }
        detectPotentialAyatForClip(clip)
    }

    private fun detectPotentialAyatForClip(clip: AudioClip) {
        val wave = clip.waveform
        if (wave.isEmpty()) { showStatus("لم تكتمل قراءة الموجة الصوتية؛ حاول مرة أخرى"); return }
        val duration = clip.durationMs.coerceAtLeast(1L)
        val trimStart = clip.trimStartMs.coerceIn(0L, duration)
        val trimEnd = clip.trimEndMs.coerceIn(trimStart, duration)
        val binMs = duration.toDouble() / wave.size.toDouble()
        val silenceThreshold = 0.04f
        val minimumGapMs = 220.0
        val maximumGapMs = 5000.0
        val edgeGuardMs = 450.0
        val candidates = mutableListOf<Long>()
        var i = 0
        while (i < wave.size) {
            if (wave[i] > silenceThreshold) { i++; continue }
            val runStart = i
            while (i < wave.size && wave[i] <= silenceThreshold) i++
            val runEnd = i
            val gapStartMs = runStart * binMs
            val gapEndMs = runEnd * binMs
            val gapDuration = gapEndMs - gapStartMs
            val centerMs = (gapStartMs + gapEndMs) / 2.0
            if (gapDuration < minimumGapMs || gapDuration > maximumGapMs) continue
            if (centerMs < trimStart + edgeGuardMs || centerMs > trimEnd - edgeGuardMs) continue

            // اشترط وجود صوت قبل الصمت وبعده حتى لا نضع علامة داخل صمت طويل.
            val sideBins = (650.0 / binMs).toInt().coerceAtLeast(1)
            val beforeStart = (runStart - sideBins).coerceAtLeast(0)
            val afterEnd = (runEnd + sideBins).coerceAtMost(wave.size)
            val hasVoiceBefore = (beforeStart until runStart).any { wave[it] > 0.08f }
            val hasVoiceAfter = (runEnd until afterEnd).any { wave[it] > 0.08f }
            if (!hasVoiceBefore || !hasVoiceAfter) continue

            val mediaTime = centerMs.toLong()
            if (mediaTime !in trimStart..trimEnd) continue
            val projectTime = (clip.startMs + mediaTime - trimStart).coerceIn(0L, projectEndMs())
            if (cutMarks.none { abs(it - projectTime) < 350L } &&
                candidates.none { abs(it - projectTime) < 500L }) {
                candidates.add(projectTime)
            }
        }

        if (candidates.isEmpty()) {
            showStatus("لم أجد فترات صمت واضحة تصلح كمواضع محتملة؛ يمكنك إضافة العلامة يدويًا")
            return
        }
        val sorted = candidates.sorted()
        val preview = sorted.take(12).joinToString("\n") { "• ${formatTime(it)}" }
        val more = if (sorted.size > 12) "\n… و${sorted.size - 12} مواضع أخرى" else ""
        AlertDialog.Builder(this)
            .setTitle("مواضع آيات محتملة")
            .setMessage("عُثر على ${sorted.size} فترة صمت محتملة في «${clip.name}».\nهذه اقتراحات آلية وليست تحديدًا مؤكدًا لنهايات الآيات.\n\n$preview$more")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة العلامات") { _, _ ->
                val fresh = sorted.filter { candidate -> cutMarks.none { abs(it - candidate) < 350L } }
                if (fresh.isNotEmpty()) {
                    rememberUndo()
                    cutMarks.addAll(fresh)
                    cutMarks.sort()
                    recalculatePauseAyahs()
                    selectedCutIndex = null
                    selectedPauseIndex = null
                    refreshTimeline()
                    saveLocalProject()
                    showStatus("أُضيفت ${fresh.size} علامة مقترحة؛ راجع مواضعها قبل التصدير")
                } else showStatus("العلامات المقترحة موجودة بالفعل")
            }
            .show()
    }

    private fun convertSelectedCutToPause() {
        val index = selectedCutIndex ?: run {
            showStatus("حدد علامة آية على الشريط أولًا لتحويلها إلى سكتة")
            return
        }
        if (index !in cutMarks.indices) { selectedCutIndex = null; return }
        val time = cutMarks[index]
        rememberUndo()
        cutMarks.removeAt(index)
        // بعد إزالة حد الآية، أعد ترقيم السكتات اللاحقة ثم اربط العلامة بالآية السابقة لها.
        recalculatePauseAyahs()
        val ayah = ayahNumberAt(time)
        pauseMarks.add(ayah to time)
        pauseMarks.sortWith(compareBy({ it.first }, { it.second }))
        selectedCutIndex = null
        selectedPauseIndex = pauseMarks.indexOfFirst { it.first == ayah && it.second == time }.takeIf { it >= 0 }
        refreshTimeline()
        saveLocalProject()
        showStatus("تحولت العلامة إلى سكتة للآية $ayah عند +${formatOffsetSeconds(pauseOffsetMs(ayah, time))} ث من بدايتها")
    }

    private fun addCutMark() {
        if (clips.isEmpty()) { showStatus("أضف تسجيلًا أولًا"); return }
        syncPlayheadFromPlayer()
        if (playheadMs <= 0L || playheadMs >= projectEndMs()) { showStatus("ضع المؤشر داخل التسجيل عند موضع الآية"); return }
        if (cutMarks.none { kotlin.math.abs(it - playheadMs) < 20L }) cutMarks.add(playheadMs)
        cutMarks.sort()
        // إضافة حد آية قد تغيّر تبعية السكتات اللاحقة؛ أعد ربط كل سكتة بآيتها.
        recalculatePauseAyahs()
        timeline.invalidate(); updateMarkerStatus(); saveLocalProject(); showStatus("تمت إضافة علامة عند ${formatTime(playheadMs)}")
    }

    private fun ayahNumberAt(timeMs: Long): Int {
        val segment = cutMarks.count { it <= timeMs }
        return startNumber + segment
    }

    /** بداية الآية على خط الزمن العام للمشروع. */
    private fun ayahStartMs(ayah: Int): Long {
        val segment = (ayah - startNumber).coerceAtLeast(0)
        return if (segment == 0) 0L else cutMarks.getOrNull(segment - 1) ?: 0L
    }

    /** زمن السكتة النسبي من بداية آيتها، لا من بداية التسجيل. */
    private fun pauseOffsetMs(ayah: Int, absoluteTimeMs: Long): Long =
        (absoluteTimeMs - ayahStartMs(ayah)).coerceAtLeast(0L)

    private fun formatOffsetSeconds(offsetMs: Long): String {
        val value = offsetMs / 1000.0
        return java.lang.String.format(java.util.Locale.US, "%.3f", value)
            .trimEnd('0').trimEnd('.')
    }

    private fun addPauseMark() {
        if (clips.isEmpty()) { showStatus("أضف تسجيلًا أولًا"); return }
        syncPlayheadFromPlayer()
        val ayah = ayahNumberAt(playheadMs)
        pauseMarks.add(ayah to playheadMs)
        pauseMarks.sortWith(compareBy({ it.first }, { it.second }))
        timeline.invalidate(); updateMarkerStatus(); saveLocalProject()
        showStatus("تمت إضافة سكتة للآية $ayah عند +${formatOffsetSeconds(pauseOffsetMs(ayah, playheadMs))} ث من بدايتها")
    }

    /** ينتقل إلى أقرب علامة آية أو سكتة في الاتجاه المطلوب، مع إظهارها داخل نافذة الشريط. */
    private fun navigateBetweenMarkers(forward: Boolean) {
        if (cutMarks.isEmpty() && pauseMarks.isEmpty()) {
            showStatus("لا توجد علامات آيات أو سكتات للتنقل بينها")
            return
        }

        // التقط الموضع الحقيقي قبل إيقاف التشغيل، ثم اجعل الانتقال إلى العلامة يدويًا.
        if (isPlaying) pausePlayback() else syncPlayheadFromPlayer()

        val markerTimes = (cutMarks + pauseMarks.map { it.second })
            .distinct()
            .sorted()
        val target = if (forward) {
            markerTimes.firstOrNull { it > playheadMs + 25L }
        } else {
            markerTimes.lastOrNull { it < playheadMs - 25L }
        }

        if (target == null) {
            showStatus(if (forward) "لا توجد علامة تالية" else "لا توجد علامة سابقة")
            return
        }

        playheadMs = target.coerceIn(0L, projectEndMs())
        selectedCutIndex = cutMarks.indexOfFirst { kotlin.math.abs(it - target) <= 25L }
            .takeIf { it >= 0 }
        selectedPauseIndex = pauseMarks.indexOfFirst { kotlin.math.abs(it.second - target) <= 25L }
            .takeIf { it >= 0 }
        timeline.focusTime(target)
        refreshTimeline()
        saveLocalProject()

        val kind = when {
            selectedCutIndex != null && selectedPauseIndex != null -> "علامة آية وسكتة"
            selectedPauseIndex != null -> "سكتة الآية ${pauseMarks[selectedPauseIndex!!].first}"
            else -> "علامة الآية ${startNumber + cutMarks.indexOfFirst { kotlin.math.abs(it - target) <= 25L }}"
        }
        showStatus("تم الانتقال إلى $kind عند ${formatTime(target)}")
    }

    private fun pauseMatrix(): String {
        val array = JSONArray()
        pauseMarks.groupBy { it.first }.toSortedMap().forEach { (ayah, marks) ->
            val item = JSONObject()
            item.put("ayah", ayah)
            val times = JSONArray()
            marks.forEach { (_, timeMs) ->
                // نخزّن في المصفوفة فرق الزمن عن بداية الآية، مع إبقاء
                // الوقت المطلق داخليًا حتى تظل العلامة مرسومة في مكانها الصحيح.
                val offsetMs = pauseOffsetMs(ayah, timeMs)
                val seconds = offsetMs / 1000.0
                times.put(if (seconds == seconds.toLong().toDouble()) seconds.toLong() else seconds)
            }
            item.put("pauses", times)
            array.put(item)
        }
        return array.toString(2)
    }
    private fun copyPauseMatrix() { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("مصفوفة السكتات", pauseMatrix())); showStatus("تم نسخ مصفوفة السكتات") }
    private fun copyLastError() {
        val text = lastErrorText.ifBlank { "لا يوجد خطأ محفوظ حاليًا" }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("آخر خطأ", text))
        showStatus(if (lastErrorText.isBlank()) "لا يوجد خطأ لنسخه" else "تم نسخ آخر خطأ")
    }

    private fun updateMarkerStatus() {
        matrixLabel?.text = if (cutMarks.isEmpty() && pauseMarks.isEmpty()) {
            "لا توجد علامات بعد"
        } else {
            "علامات الآيات: ${cutMarks.size}   •   السكتات: ${pauseMarks.size}   •   التوقيت من بداية الآية"
        }
    }

    /** نافذة تقدم حقيقية: لا يزيد العداد إلا بعد اكتمال كتابة الملف بنجاح. */
    private fun createSegmentExportProgress(total: Int): Pair<AlertDialog, (Int, String) -> Unit> {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(10f))
            setBackgroundColor(PANEL)
        }
        val headline = TextView(this).apply {
            text = "تم تصدير 0 من $total ملفات"
            textSize = 16f; setTextColor(WHITE); gravity = Gravity.CENTER
        }
        val details = TextView(this).apply {
            text = "المتبقي: $total ملفًا\nالنسبة: 0%\nجارٍ التحضير…"
            textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(12f))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total.coerceAtLeast(1); progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
        }
        panel.addView(headline, LinearLayout.LayoutParams(-1, -2))
        panel.addView(bar, LinearLayout.LayoutParams(-1, dp(12f)))
        panel.addView(details, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this)
            .setTitle("تقدم تصدير الملفات")
            .setView(panel)
            .setCancelable(false)
            .setPositiveButton("إغلاق", null)
            .create()
        dialog.show()
        val update: (Int, String) -> Unit = { completed, message ->
            val done = completed.coerceIn(0, total)
            val remaining = (total - done).coerceAtLeast(0)
            val percent = if (total <= 0) 100 else (done * 100 / total)
            headline.text = "تم تصدير $done من $total ملفات"
            bar.progress = done
            details.text = "المتبقي: $remaining ملفًا\nالنسبة: $percent%\n$message"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = done >= total || message.contains("فشل")
        }
        return dialog to update
    }

    private fun exportMarkedSegments() {
        if (isExporting) { showStatus("هناك عملية تصدير جارية بالفعل؛ انتظر اكتمالها أولًا"); return }
        if (clips.isEmpty()) { showStatus("أضف تسجيلًا واحدًا على الأقل"); return }
        if (cutMarks.isEmpty()) { showStatus("أضف علامات قطع الآيات أولًا"); return }
        val snapshot = clips.map { it.copy() }
        if (snapshot.any { it.exportFolder == null }) { requestNextMissingFolder(); return }
        val referenceDuration = snapshot.first().durationMs
        // نقبل فروقًا صغيرة في بيانات المدة، ونستخدم أقصر مدة مشتركة.
        val allowedDriftMs = 500L
        val mismatched = snapshot.any { kotlin.math.abs(it.durationMs - referenceDuration) > allowedDriftMs }
        if (mismatched) {
            showStatus("لا يمكن التصدير: فرق المدة كبير؛ يجب أن تكون التسجيلات متقاربة جدًا")
            return
        }
        val end = snapshot.minOf { it.durationMs }
        val boundaries = (listOf(0L) + cutMarks.filter { it in 0L..end }.distinct().sorted() + end).distinct()
        if (boundaries.size < 2) { showStatus("لا توجد مقاطع صالحة للتصدير"); return }
        val segments = boundaries.zipWithNext()
        val total = snapshot.size * segments.size
        isExporting = true
        val (progressDialog, updateProgress) = createSegmentExportProgress(total)
        updateProgress(0, "جارٍ بدء التصدير…")
        Thread {
            var completed = 0
            try {
                snapshot.forEachIndexed { clipIndex, clip ->
                    segments.forEachIndexed { segmentIndex, pair ->
                        val fileNumber = startNumber + segmentIndex
                        val beforeFile = completed
                        handler.post { updateProgress(beforeFile, "جارٍ تصدير الملف ${beforeFile + 1} من $total — ${clip.name}") }
                        exportOneSegment(clip, pair.first, pair.second, fileNumber)
                        completed++
                        val doneNow = completed
                        handler.post { updateProgress(doneNow, "اكتمل الملف ${fileNumber}.mp3 من تسجيل ${clipIndex + 1}") }
                    }
                }
                val finalCompleted = completed
                handler.post {
                    isExporting = false
                    updateProgress(finalCompleted, "اكتمل تصدير جميع الملفات بنجاح")
                    showStatus("اكتمل التصدير بنجاح: $finalCompleted من $total ملفًا (100%)")
                }
            } catch (e: Exception) {
                val details = "${e::class.java.name}: ${e.message ?: "بدون رسالة"}"
                lastErrorText = "فشل التصدير بعد $completed من $total ملفًا\n$details\nالمصدر: ${snapshot.joinToString(" | ") { it.name + " -> " + it.uri }}"
                val failedAt = completed
                handler.post {
                    isExporting = false
                    updateProgress(failedAt, "فشل الملف ${failedAt + 1}. نجح $failedAt وبقي ${total - failedAt}. السبب: ${e.message ?: "خطأ غير معروف"}")
                    showStatus("توقف التصدير بعد $failedAt من $total ملفًا — اضغط نسخ آخر خطأ")
                }
            }
        }.start()
    }

    private fun createExportDocument(clip: AudioClip, number: Int): Uri {
        val treeUri = Uri.parse(clip.exportFolder!!)
        val parentUri = if (DocumentsContract.isTreeUri(treeUri)) {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                ?: error("رابط مجلد الحفظ غير صالح: $treeUri")
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        } else treeUri
        return DocumentsContract.createDocument(contentResolver, parentUri, "audio/mpeg", "$number.mp3")
            ?: error("تعذر إنشاء الملف $number داخل $parentUri")
    }

    private fun exportExactWavSegment(clip: AudioClip, startMs: Long, endMs: Long, number: Int): Boolean {
        if (clip.volume != 1.0f || clip.muted) return false
        val source = contentResolver.openInputStream(Uri.parse(clip.uri))?.use { it.readBytes() } ?: return false
        if (source.size < 44 || String(source, 0, 4, Charsets.US_ASCII) != "RIFF" || String(source, 8, 4, Charsets.US_ASCII) != "WAVE") return false
        var p = 12; var channels = 0; var rate = 0; var blockAlign = 0; var dataPos = -1; var dataSize = 0
        while (p + 8 <= source.size) {
            val id = String(source, p, 4, Charsets.US_ASCII)
            val n = readLe32(source, p + 4).coerceAtMost((source.size - p - 8).toLong()).toInt()
            val body = p + 8
            if (id == "fmt " && n >= 16) { channels = readLe16(source, body + 2); rate = readLe32(source, body + 4).toInt(); blockAlign = readLe16(source, body + 12) }
            if (id == "data") { dataPos = body; dataSize = n; break }
            p = body + n + (n and 1)
        }
        if (dataPos < 0 || channels < 1 || rate < 1 || blockAlign < 1) return false
        val from = ((startMs.coerceAtLeast(0L) * rate) / 1000L * blockAlign).toInt().coerceIn(0, dataSize)
        val to = ((endMs.coerceAtLeast(startMs) * rate) / 1000L * blockAlign).toInt().coerceIn(from, dataSize)
        val alignedFrom = from - (from % blockAlign); val alignedTo = to - (to % blockAlign)
        val kept = source.copyOfRange(dataPos + alignedFrom, dataPos + alignedTo)
        val output = source.copyOfRange(0, dataPos + kept.size)
        java.nio.ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, output.size - 8)
        val sizePos = dataPos - 4
        java.nio.ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sizePos, kept.size)
        kept.copyInto(output, dataPos)
        val doc = createExportDocument(clip, number)
        contentResolver.openOutputStream(doc)?.use { it.write(output) } ?: error("تعذر فتح ملف التصدير")
        return true
    }

    private fun exportOneSegment(clip: AudioClip, startMs: Long, endMs: Long, number: Int) {
        // جميع المقاطع تُحوّل إلى MP3 حقيقي حتى لو كان المصدر WAV.
        val decoded = decodePcm(Uri.parse(clip.uri))
        val pcm = resample(decoded, 44100, 2)
        val totalFrames = pcm.size / 2
        val from = ((startMs.coerceAtLeast(0L) * 44100L) / 1000L).toInt().coerceIn(0, totalFrames)
        val to = ((endMs.coerceAtLeast(startMs) * 44100L) / 1000L).toInt().coerceIn(from, totalFrames)
        val mix = IntArray((to - from) * 2)
        for (i in mix.indices) mix[i] = (pcm[from * 2 + i] * clip.volume).toInt()
        val doc = createExportDocument(clip, number)
        try {
            writeMp3(doc, mix, 44100, 2)
        } catch (e: Exception) {
            // لا نترك ملف MP3 ناقصًا يوهم المستخدم بأن التصدير اكتمل.
            runCatching { DocumentsContract.deleteDocument(contentResolver, doc) }
            throw e
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // لا نضع قائمة MIME هنا؛ بعض مديري الملفات يصفون WAV/BIN بنوع غير صوتي.
            // الفحص الحقيقي للصيغة يتم بعد اختيار الملف داخل التطبيق.
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

    private fun getDurationFlexible(uri: Uri): Long {
        if (looksLikeWave(uri)) {
            return try {
                val pcm = decodeWavePcm(uri)
                (pcm.samples.size.toLong() * 1000L / pcm.channels / pcm.rate).coerceAtLeast(1L)
            } catch (_: Exception) { 0L }
        }
        val metadata = getDuration(uri)
        if (metadata > 0L) return metadata
        return try {
            val extractor = MediaExtractor()
            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            else throw IllegalArgumentException("تعذر فتح الملف: $uri")
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    durationUs = f.getLong(MediaFormat.KEY_DURATION); break
                }
            }
            extractor.release(); afd.close()
            durationUs / 1000L
        } catch (_: Exception) { 0L }
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
        if (requestCode == REQUEST_EXPORT_FOLDER) {
            val folder = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            val target = pendingFolderClipId?.let { id -> clips.firstOrNull { it.id == id } } ?: selectedClip()
            target?.exportFolder = folder.toString()
            pendingFolderClipId = null
            saveLocalProject()
            val nextMissing = clips.firstOrNull { it.exportFolder == null }
            if (nextMissing != null) {
                handler.postDelayed({ requestNextMissingFolder() }, 250L)
            } else {
                showStatus("تم تعيين مجلدات الحفظ لكل التسجيلات")
            }
            return
        }
        val uris = mutableListOf<Uri>(); data?.data?.let { uris.add(it) }; data?.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
        if (uris.isEmpty()) return
        val uri = uris.first()

        when (requestCode) {
            REQUEST_EXPORT_AUDIO -> exportFinalAudio(uri)
            REQUEST_AUDIO -> {
                uris.forEach { audioUri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            audioUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) { }
                }

                val duration = getDurationFlexible(uri)
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
                    startMs = 0L,
                    durationMs = duration,
                    color = paletteColor(clips.size)
                )

                clips.add(clip)
                uris.drop(1).forEachIndexed { extraIndex, extraUri ->
                    val extraDuration = getDuration(extraUri)
                    if (extraDuration > 0L) clips.add(AudioClip(System.nanoTime(), extraUri.toString(), queryName(extraUri), (targetTrack + extraIndex + 1).coerceAtMost(19), 0L, extraDuration, color = paletteColor(clips.size)))
                }
                prepareWaveform(clip)
                selectedId = clip.id
                if (visibleSourceId == null) visibleSourceId = clip.id
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
                        saveLocalProject()
                        releasePlayers()
                        undoStack.clear(); redoStack.clear()
                        loadProjectJson(json)
                        // الاستيراد ينشئ مشروعًا مستقلًا ولا يستبدل مشروعًا محفوظًا له نفس المعرّف.
                        projectId = java.util.UUID.randomUUID().toString()
                        saveLocalProject()
                        refreshTimeline()
                        showStatus("تم استيراد المشروع كمساحة عمل مستقلة")
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

        rememberUndo()
        clip.trimStartMs += offset
        clip.startMs = playheadMs
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
        playbackButton?.text = if (isPlaying) "Ⅱ" else "▶"
        playbackButton?.contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل"
    }
    private fun continuePlaybackFromCurrentPosition() {
        if (!isPlaying) return
        val generation = playbackGeneration
        val remaining = clips.filter {
            it.id == visibleSourceId && !it.muted && it.endMs() > playheadMs &&
                !players.containsKey(it.id) && !scheduledStarts.contains(it.id)
        }

        // قد لا توجد مقاطع جديدة لأن المقطع الحالي ما زال يعمل أو لأن
        // مقطعًا آخر ينتظر موعده؛ لا ننهِ الجلسة في هذه الحالة.
        if (remaining.isEmpty()) {
            if (players.isEmpty() && scheduledStarts.isEmpty()) {
                isPlaying = false
                updatePlaybackButton()
                handler.removeCallbacks(playbackTicker)
                refreshTimeline()
            }
            return
        }

        remaining.forEach { clip ->
            val delay = (clip.startMs - playheadMs).coerceAtLeast(0L)
            if (delay == 0L) {
                playClip(clip, playheadMs, generation)
            } else {
                scheduledStarts.add(clip.id)
                val callback = Runnable {
                    scheduledStarts.remove(clip.id)
                    scheduledStartCallbacks.remove(clip.id)
                    if (isPlaying && generation == playbackGeneration &&
                        clips.any { it.id == clip.id } && clip.endMs() > playheadMs) {
                        playClip(clip, max(playheadMs, clip.startMs), generation)
                    } else if (isPlaying && players.isEmpty() && scheduledStarts.isEmpty()) {
                        continuePlaybackFromCurrentPosition()
                    }
                }
                scheduledStartCallbacks[clip.id] = callback
                handler.postDelayed(callback, delay)
            }
        }
    }

    private fun seekPlaybackTo(positionMs: Long) {
        val wasPlaying = isPlaying
        releasePlayers()
        playheadMs = positionMs.coerceIn(0L, projectEndMs())
        if (!wasPlaying) {
            refreshTimeline()
            return
        }
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

        val targets = clips.filter {
            it.id == visibleSourceId && !it.muted && it.endMs() > playheadMs
        }
        if (targets.isEmpty()) {
            showStatus("لا يوجد مقطع قابل للتشغيل عند هذا الموضع")
            return
        }
        releasePlayers()
        isPlaying = true
        updatePlaybackButton()
        continuePlaybackFromCurrentPosition()
        showStatus(if (targets.size > 1) "▶ تشغيل ${targets.size} مقاطع معًا" else "▶ جارٍ التشغيل")
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun playClip(
        clip: AudioClip,
        projectStartMs: Long = playheadMs,
        generation: Long = playbackGeneration
    ) {
        if (!isPlaying || generation != playbackGeneration || players.containsKey(clip.id)) return
        val requestedProjectPosition = projectStartMs.coerceIn(clip.startMs, clip.endMs())
        val requestedSourcePosition = (clip.trimStartMs + requestedProjectPosition - clip.startMs)
            .coerceIn(clip.trimStartMs, clip.trimEndMs)
        try {
            val media = MediaPlayer()
            players[clip.id] = media
            media.setDataSource(this, Uri.parse(clip.uri))
            media.setOnPreparedListener { prepared ->
                if (generation != playbackGeneration || !isPlaying || players[clip.id] !== prepared) {
                    if (players[clip.id] === prepared) players.remove(clip.id)
                    try { prepared.release() } catch (_: Exception) { }
                    return@setOnPreparedListener
                }
                try {
                    prepared.setVolume(if (clip.muted) 0f else clip.volume, if (clip.muted) 0f else clip.volume)
                    prepared.setOnSeekCompleteListener { ready ->
                        if (generation != playbackGeneration || !isPlaying || players[clip.id] !== ready) return@setOnSeekCompleteListener
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                ready.playbackParams = PlaybackParams().setSpeed(previewSpeed)
                            }
                            ready.start()
                        } catch (_: Exception) {
                            players.remove(clip.id)?.release()
                            playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
                            if (players.isEmpty() && scheduledStarts.isEmpty()) continuePlaybackFromCurrentPosition()
                        }
                    }
                    prepared.seekTo(requestedSourcePosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } catch (_: Exception) {
                    players.remove(clip.id)?.release()
                    playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
                    if (players.isEmpty() && scheduledStarts.isEmpty()) continuePlaybackFromCurrentPosition()
                }
            }
            media.setOnCompletionListener { completed ->
                if (generation != playbackGeneration) {
                    try { completed.release() } catch (_: Exception) { }
                    return@setOnCompletionListener
                }
                if (players[clip.id] === completed) players.remove(clip.id)
                try { completed.release() } catch (_: Exception) { }
                playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
                if (players.isEmpty() && scheduledStarts.isEmpty()) continuePlaybackFromCurrentPosition()
            }
            media.setOnErrorListener { failed, _, _ ->
                if (generation == playbackGeneration && players[clip.id] === failed) {
                    players.remove(clip.id)
                    try { failed.release() } catch (_: Exception) { }
                    showStatus("تعذر تشغيل أحد المقاطع الصوتية؛ تم تجاوز هذا المقطع")
                    playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
                    if (players.isEmpty() && scheduledStarts.isEmpty()) continuePlaybackFromCurrentPosition()
                }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            players.remove(clip.id)?.let { try { it.release() } catch (_: Exception) { } }
            playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
            if (generation == playbackGeneration && isPlaying && players.isEmpty() && scheduledStarts.isEmpty()) {
                continuePlaybackFromCurrentPosition()
            }
        }
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            var changed = false
            var reachedEnd = false
            clips.toList().forEach { clip ->
                val current = players[clip.id] ?: return@forEach
                try {
                    if (!current.isPlaying) return@forEach
                    val sourcePosition = current.currentPosition.toLong()
                    if (sourcePosition >= clip.trimEndMs) {
                        players.remove(clip.id)
                        try { current.setOnCompletionListener(null); current.stop(); current.release() } catch (_: Exception) { }
                        playheadMs = max(playheadMs, clip.endMs()).coerceAtMost(projectEndMs())
                        reachedEnd = true
                    } else {
                        val position = (clip.startMs + sourcePosition - clip.trimStartMs)
                            .coerceIn(clip.startMs, clip.endMs())
                        if (clip.id == visibleSourceId || players.size == 1) {
                            playheadMs = position.coerceAtMost(projectEndMs())
                            changed = true
                        }
                    }
                } catch (_: Exception) { }
            }
            if (changed || reachedEnd) refreshTimeline()
            if (players.isEmpty() && scheduledStarts.isEmpty()) {
                continuePlaybackFromCurrentPosition()
                if (!isPlaying) return
            }
            handler.postDelayed(this, 50L)
        }
    }

    private fun pausePlayback() {
        // التقط موضع التشغيل الحقيقي قبل تحرير المشغلات، حتى تكون الاستئنافة
        // من نفس المللي ثانية تقريبًا، لا من بداية المقطع.
        syncPlayheadFromPlayer()
        playheadMs = playheadMs.coerceIn(0L, projectEndMs())

        // لا نُبقِ MediaPlayer متوقفًا داخل الخريطة؛ إعادة إنشائه عند التشغيل
        // التالي تمنع setPlaybackParams أو حالة pause القديمة من إيقاظه/إرجاعه.
        releasePlayers()
        updatePlaybackButton()
        refreshTimeline()
        showStatus("تم الإيقاف المؤقت — يمكن الاستئناف من الموضع نفسه")
    }

    private fun releasePlayers() {
        // أبطل الجلسة أولًا حتى لا ينفذ أي callback قديم أثناء التحرير.
        playbackGeneration++
        scheduledStartCallbacks.values.forEach { handler.removeCallbacks(it) }
        scheduledStartCallbacks.clear()
        scheduledStarts.clear()
        players.values.toList().forEach { media ->
            try {
                media.setOnPreparedListener(null)
                media.setOnCompletionListener(null)
                media.setOnErrorListener(null)
                media.setOnSeekCompleteListener(null)
                media.release()
            } catch (_: Exception) { }
        }
        players.clear()
        isPlaying = false
        handler.removeCallbacks(playbackTicker)
    }

    private fun stopPlayback() {
        releasePlayers()
        updatePlaybackButton()
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
            startMs = 0L,
            durationMs = recordLength,
            color = paletteColor(clips.size)
        )
        clips.add(clip)
        prepareWaveform(clip)
        selectedId = clip.id
        if (visibleSourceId == null) visibleSourceId = clip.id
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
        obj.put("version", 3)
        obj.put("projectId", projectId)
        obj.put("projectName", projectName)
        obj.put("updatedAt", System.currentTimeMillis())
        obj.put("tracks", tracks)
        obj.put("playheadMs", playheadMs)
        obj.put("zoom", zoom.toDouble())
        obj.put("startNumber", startNumber)
        obj.put("cutMarks", JSONArray(cutMarks))
        obj.put("pauseMarks", JSONArray(pauseMarks.map { JSONObject().put("verse", it.first).put("time", it.second) }))

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
                    put("exportFolder", clip.exportFolder ?: "")
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
                        color = c.optInt("color", paletteColor(i)),
                        exportFolder = c.optString("exportFolder").ifBlank { null }
                    )
                )
            }

            clips.clear()
            clips.addAll(loaded)
            projectId = obj.optString("projectId", projectId).ifBlank { java.util.UUID.randomUUID().toString() }
            projectName = obj.optString("projectName", projectName).ifBlank { "مشروعي الصوتي" }
            repairTrackLayout()

            tracks = max(
                max(1, obj.optInt("tracks", 3)),
                (clips.maxOfOrNull { it.track } ?: 0) + 1
            ).coerceAtMost(20)

            startNumber = obj.optInt("startNumber", 1).coerceAtLeast(1)
            cutMarks.clear(); obj.optJSONArray("cutMarks")?.let { a -> for (i in 0 until a.length()) cutMarks.add(a.optLong(i)) }
            pauseMarks.clear(); obj.optJSONArray("pauseMarks")?.let { a -> for (i in 0 until a.length()) { val x = a.optJSONObject(i); if (x != null) pauseMarks.add(x.optInt("verse") to x.optLong("time")) } }
            playheadMs = obj.optLong("playheadMs", 0L).coerceAtLeast(0L)
            selectedCutIndex = null
            selectedPauseIndex = null
            zoom = obj.optDouble("zoom", 70.0)
                .toFloat().coerceIn(0.05f, 500f)

            clips.forEach { prepareWaveform(it) }
            selectedId = clips.firstOrNull()?.id
            visibleSourceId = selectedId
            refreshTimeline()
        } catch (e: Exception) {
            showStatus("ملف المشروع غير صالح: ${e.message}")
        }
    }

    private fun prepareWaveform(clip: AudioClip, onReady: (() -> Unit)? = null) {
        if (clip.waveform.isNotEmpty()) {
            onReady?.invoke()
            return
        }
        if (clip.uri.isBlank()) { onReady?.invoke(); return }
        if (onReady != null) waveformReadyCallbacks.getOrPut(clip.id) { mutableListOf() }.add(onReady)
        if (clip.waveformLoading) return
        clip.waveformLoading = true
        Thread {
            try {
                val values = buildWaveformFromMedia(Uri.parse(clip.uri), clip.durationMs)
                handler.post {
                    clip.waveformLoading = false
                    if (clips.any { it.id == clip.id }) {
                        clip.waveform = values
                        timeline.invalidate()
                    }
                    val callbacks = waveformReadyCallbacks.remove(clip.id)?.toList().orEmpty()
                    if (clips.any { it.id == clip.id }) {
                        callbacks.forEach { callback -> try { callback() } catch (_: Exception) { } }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    clip.waveformLoading = false
                    val callbacks = waveformReadyCallbacks.remove(clip.id)?.toList().orEmpty()
                    if (callbacks.isNotEmpty() && clips.any { it.id == clip.id }) {
                        showStatus("تعذر تحليل الصوت لاكتشاف المواضع: ${e.message ?: "خطأ غير معروف"}")
                        callbacks.forEach { callback -> try { callback() } catch (_: Exception) { } }
                    }
                    timeline.invalidate()
                }
            }
        }.start()
    }

    /**
     * يبني 4096 عينة موجية من فك ترميز متدفق عبر MediaCodec.
     * لا يحتفظ بالصوت PCM كاملًا في الذاكرة، ولذلك يعمل أيضًا مع التسجيلات الطويلة.
     */
    private fun buildWaveformFromMedia(uri: Uri, durationMs: Long): FloatArray {
        val bins = 4096
        val peaks = FloatArray(bins)
        val extractor = MediaExtractor()
        var asset: AssetFileDescriptor? = null
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") {
                extractor.setDataSource(uri.path ?: throw IllegalArgumentException("مسار الصوت غير صالح"))
            } else {
                asset = contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: throw IllegalArgumentException("تعذر فتح ملف الصوت")
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) throw IllegalArgumentException("لا يوجد مسار صوتي")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("ترميز صوت غير معروف")
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputEncoding = 2 // PCM 16-bit هو الافتراضي في معظم مفككات Android.
            val safeDuration = durationMs.coerceAtLeast(1L)
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        if (input == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                        } else {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPerSample = if (outputEncoding == 4) 4 else 2 // PCM_FLOAT o PCM_16BIT
                            val frameBytes = channels * bytesPerSample
                            val frames = pcm.remaining() / frameBytes
                            for (frame in 0 until frames) {
                                val timeUs = info.presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate.coerceAtLeast(1)
                                val bin = ((timeUs.coerceAtLeast(0L) * bins) / (safeDuration * 1000L)).toInt().coerceIn(0, bins - 1)
                                var peak = 0f
                                for (ch in 0 until channels) {
                                    val sample = if (bytesPerSample == 4) {
                                        abs(pcm.getFloat())
                                    } else {
                                        abs(pcm.getShort().toInt() / 32768f)
                                    }
                                    if (sample > peak) peak = sample
                                }
                                if (peak > peaks[bin]) peaks[bin] = peak
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
            try { asset?.close() } catch (_: Exception) { }
        }
        val maximum = peaks.maxOrNull() ?: 0f
        if (maximum <= 0.0001f) return FloatArray(bins) { 0.006f }
        // إزالة أرضية الضجيج الخفيف حتى تظهر فترات السكوت بوضوح.
        val noiseFloor = max(0.008f, maximum * 0.035f)
        return FloatArray(bins) { i ->
            val value = peaks[i]
            if (value <= noiseFloor) 0.006f
            else (kotlin.math.sqrt((value / maximum).coerceIn(0f, 1f)) * 0.96f).coerceIn(0.015f, 1f)
        }
    }
    private fun readProjectsStore(): JSONObject {
        val projects = try { JSONObject(prefs.getString("projects", "{}") ?: "{}") }
        catch (_: Exception) { JSONObject() }
        // ترحيل المشاريع القديمة التي كانت تُحفظ باسم المشروع بدل معرّف مستقل.
        val migrated = JSONObject()
        val keys = projects.keys()
        while (keys.hasNext()) {
            val oldKey = keys.next()
            val raw = projects.optString(oldKey, "")
            if (raw.isBlank()) continue
            try {
                val item = JSONObject(raw)
                val id = item.optString("projectId", "").ifBlank { java.util.UUID.randomUUID().toString() }
                item.put("projectId", id)
                if (!item.has("projectName") || item.optString("projectName").isBlank()) item.put("projectName", oldKey)
                migrated.put(id, item.toString())
            } catch (_: Exception) { }
        }
        return migrated
    }

    private fun saveLocalProject() {
        val json = projectJson()
        val projects = readProjectsStore()
        projects.put(projectId, json)
        prefs.edit()
            .putString("project", json) // نسخة توافقية للإصدارات السابقة
            .putString("projects", projects.toString())
            .putString("currentProject", projectId)
            .apply()
        status.text = "محفوظ تلقائيًا • $projectName"
        projectTitleView?.text = projectName
    }

    private fun restoreLocalProject() {
        val projects = readProjectsStore()
        val currentId = prefs.getString("currentProject", "") ?: ""
        var saved = projects.optString(currentId, null)
        if (saved.isNullOrBlank()) {
            // استعادة نسخة المشروع المفردة القديمة أو العثور على مشروع بالاسم القديم.
            saved = prefs.getString("project", null)
            if (!saved.isNullOrBlank()) {
                try {
                    val old = JSONObject(saved)
                    val id = old.optString("projectId", "").ifBlank { java.util.UUID.randomUUID().toString() }
                    old.put("projectId", id)
                    saved = old.toString()
                    projectId = id
                } catch (_: Exception) { }
            }
        }
        if (!saved.isNullOrBlank()) {
            loadProjectJson(saved)
            saveLocalProject()
            status.text = "تمت استعادة مشروع $projectName"
        } else {
            // إنشاء أول مشروع تلقائيًا، دون ترك محرر بلا مساحة عمل محددة.
            projectName = "مشروعي الأول"
            projectId = java.util.UUID.randomUUID().toString()
            saveLocalProject()
        }
    }

    private fun showProjects() {
        saveLocalProject()
        val projects = readProjectsStore()
        val entries = mutableListOf<Pair<String, JSONObject>>()
        val keys = projects.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            try {
                val obj = JSONObject(projects.optString(id))
                entries.add(id to obj)
            } catch (_: Exception) { }
        }
        entries.sortByDescending { it.second.optLong("updatedAt", 0L) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) {
            list.addView(makeLabel("لا توجد مشاريع محفوظة بعد", 14f, MUTED))
        }
        entries.forEach { (id, obj) ->
            val name = obj.optString("projectName", "مشروع صوتي")
            val clipCount = try { JSONObject(projects.optString(id)).optJSONArray("clips")?.length() ?: 0 } catch (_: Exception) { 0 }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
                background = GradientDrawable().apply {
                    setColor(if (id == projectId) 0xFF263650.toInt() else PANEL)
                    cornerRadius = dp(12f).toFloat()
                    setStroke(dp(1f), if (id == projectId) 0xFF4F8D9B.toInt() else 0xFF35415F.toInt())
                }
            }
            val open = Button(this).apply {
                text = "$name\n$clipCount مقطع${if (id == projectId) " • مفتوح" else ""}"
                isAllCaps = false; textSize = 12f; setTextColor(WHITE)
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    if (id != projectId) {
                        saveLocalProject()
                        releasePlayers()
                        undoStack.clear(); redoStack.clear()
                        loadProjectJson(projects.optString(id))
                        saveLocalProject()
                        refreshTimeline()
                        showStatus("تم الانتقال إلى مشروع $projectName")
                    }
                    // إغلاق نافذة قائمة المشاريع بعد الفتح.
                    (panel.getTag() as? AlertDialog)?.dismiss()
                }
            }
            row.addView(open, LinearLayout.LayoutParams(0, dp(58f), 1f))
            val delete = Button(this).apply {
                text = "حذف"; isAllCaps = false; textSize = 11f; setTextColor(0xFFFF9A9A.toInt())
                background = GradientDrawable().apply { setColor(0xFF442D3A.toInt()); cornerRadius = dp(9f).toFloat() }
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حذف المشروع؟")
                        .setMessage("سيُحذف مشروع «$name» من ذاكرة التطبيق، ولا يمكن التراجع عن ذلك.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("حذف") { _, _ ->
                            val latest = readProjectsStore()
                            latest.remove(id)
                            prefs.edit().putString("projects", latest.toString()).apply()
                            if (id == projectId) {
                                val nextKey = latest.keys().asSequence().firstOrNull()
                                if (nextKey != null) {
                                    releasePlayers(); undoStack.clear(); redoStack.clear()
                                    loadProjectJson(latest.optString(nextKey)); saveLocalProject(); refreshTimeline()
                                } else {
                                    releasePlayers(); clips.clear(); cutMarks.clear(); pauseMarks.clear()
                                    selectedId = null; visibleSourceId = null; playheadMs = 0L
                                    projectId = java.util.UUID.randomUUID().toString(); projectName = "مشروع جديد"
                                    saveLocalProject(); refreshTimeline()
                                }
                            } else {
                                prefs.edit().putString("projects", latest.toString()).apply()
                            }
                            (panel.getTag() as? AlertDialog)?.dismiss()
                            showProjects()
                        }.show()
                }
            }
            row.addView(delete, LinearLayout.LayoutParams(dp(58f), dp(42f)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7f) })
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(300f)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val create = makeButton("＋ مشروع جديد") { (panel.getTag() as? AlertDialog)?.dismiss(); newProject() }
        val close = makeButton("إغلاق") { (panel.getTag() as? AlertDialog)?.dismiss() }
        buttons.addView(create, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginEnd = dp(6f) })
        buttons.addView(close, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginStart = dp(6f) })
        panel.addView(buttons)
        val dialog = AlertDialog.Builder(this).setTitle("مساحات العمل • مشاريعي").setView(panel).create()
        panel.setTag(dialog)
        dialog.show()
    }

    private fun newProject() {
        val input = EditText(this).apply { hint = "مثال: سورة البقرة"; setTextColor(WHITE); setHintTextColor(MUTED) }
        AlertDialog.Builder(this).setTitle("إنشاء مساحة عمل جديدة")
            .setMessage("سيُحفظ المشروع الحالي تلقائيًا قبل الانتقال.")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "مشروع جديد" }
                saveLocalProject()
                releasePlayers()
                clips.clear(); cutMarks.clear(); pauseMarks.clear()
                undoStack.clear(); redoStack.clear()
                selectedId = null; visibleSourceId = null
                playheadMs = 0L; zoom = 70f; tracks = 3; startNumber = 1
                selectedCutIndex = null; selectedPauseIndex = null
                projectId = java.util.UUID.randomUUID().toString()
                projectName = name
                refreshTimeline(); saveLocalProject(); showStatus("تم إنشاء مساحة عمل مستقلة: $name")
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

    private fun chooseFinalAudioExport() {
        if (isExporting) { showStatus("انتظر انتهاء عملية التصدير الحالية أولًا"); return }
        if (clips.isEmpty()) { showStatus("أضف مقطعًا واحدًا على الأقل قبل التصدير"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_TITLE, "$projectName-final.mp3")
        }
        startActivityForResult(intent, REQUEST_EXPORT_AUDIO)
    }

    private data class DecodedPcm(val rate: Int, val channels: Int, val samples: ShortArray)

    private fun decodePcmWithExtractor(uri: Uri): DecodedPcm {
        val extractor = MediaExtractor()
        var asset: AssetFileDescriptor? = null
        if (uri.scheme == "file") {
            extractor.setDataSource(uri.path ?: "")
        } else {
            asset = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("تعذر فتح رابط الصوت: $uri")
            extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
        }
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) throw IllegalArgumentException("لا يوجد مسار صوتي")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("ترميز غير معروف")
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0); codec.start()
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        try {
            // بعض أجهزة أندرويد تُخرج PCM_FLOAT بدل PCM_16BIT؛ يجب قراءة نوع الخرج
            // الفعلي وإلا تتحول قيم float إلى عينات 16-bit مشوهة ويظهر صوت زنّة.
            try {
                val outFormat = codec.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (Build.VERSION.SDK_INT >= 24 && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
            } catch (_: Exception) { }
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
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (Build.VERSION.SDK_INT >= 24 && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes); output.write(bytes)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release(); extractor.release(); asset?.close()
        }
        val raw = output.toByteArray()
        val shorts = when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = raw.size / 4
                val floatBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ShortArray(count) { i ->
                    val sample = floatBuffer.get(i).let { if (it.isFinite()) it else 0f }.coerceIn(-1f, 1f)
                    (sample * 32767f).toInt().toShort()
                }
            }
            android.media.AudioFormat.ENCODING_PCM_8BIT -> ShortArray(raw.size) { i ->
                (((raw[i].toInt() and 255) - 128) shl 8).toShort()
            }
            else -> {
                val count = raw.size / 2
                ShortArray(count) { i ->
                    ((raw[i * 2].toInt() and 255) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
                }
            }
        }
        require(rate > 0 && channels > 0 && shorts.isNotEmpty()) { "تعذر فك ترميز الصوت إلى PCM صالح" }
        return DecodedPcm(rate, channels, shorts)
    }

    private fun readLe16(b: ByteArray, p: Int): Int = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun readLe32(b: ByteArray, p: Int): Long = (readLe16(b, p).toLong() and 65535L) or ((readLe16(b, p + 2).toLong() and 65535L) shl 16)
    private fun looksLikeWave(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val h = ByteArray(12)
            input.read(h) == 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        } ?: false
    } catch (_: Exception) { false }
    private fun decodeWavePcm(uri: Uri): DecodedPcm {
        val b = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("تعذر فتح WAV")
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") error("ملف WAV غير صالح")
        var p = 12; var format = 0; var channels = 0; var rate = 0; var bits = 0; var data = -1; var size = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII); val n = readLe32(b, p + 4).coerceAtMost((b.size - p - 8).toLong()).toInt(); val body = p + 8
            if (id == "fmt " && n >= 16) { format = readLe16(b, body); channels = readLe16(b, body + 2); rate = readLe32(b, body + 4).toInt(); bits = readLe16(b, body + 14) }
            if (id == "data") { data = body; size = n; break }
            p = body + n + (n and 1)
        }
        if (data < 0 || channels < 1 || rate < 1 || format !in listOf(1, 3)) error("صيغة WAV غير مدعومة")
        val bytesPer = if (format == 3 && bits == 32) 4 else if (format == 1 && bits in listOf(8, 16, 24, 32)) bits / 8 else 0
        if (bytesPer == 0) error("عمق WAV غير مدعوم: $bits")
        val out = ShortArray((size / (channels * bytesPer)) * channels); var q = data
        for (i in out.indices) out[i] = when {
            format == 3 -> (java.lang.Float.intBitsToFloat(readLe32(b, q).toInt()).coerceIn(-1f, 1f) * 32767f).toInt().toShort().also { q += 4 }
            bits == 8 -> (((b[q++].toInt() and 255) - 128) shl 8).toShort()
            bits == 16 -> readLe16(b, q).toShort().also { q += 2 }
            bits == 24 -> ((b[q++].toInt() or (b[q++].toInt() shl 8) or (b[q++].toInt() shl 16)) shr 8).toShort()
            else -> readLe32(b, q).toInt().shr(16).toShort().also { q += 4 }
        }
        return DecodedPcm(rate, channels, out)
    }
    private fun decodePcm(uri: Uri): DecodedPcm = if (looksLikeWave(uri)) decodeWavePcm(uri) else decodePcmWithExtractor(uri)

    private fun resample(pcm: DecodedPcm, targetRate: Int, targetChannels: Int): ShortArray {
        val frames = pcm.samples.size / pcm.channels
        if (frames <= 0 || pcm.rate <= 0 || targetRate <= 0) return ShortArray(0)
        val targetFrames = (frames.toLong() * targetRate / pcm.rate).toInt().coerceAtLeast(1)
        val result = ShortArray(targetFrames * targetChannels)
        for (frame in 0 until targetFrames) {
            // استيفاء خطي بدل أخذ أقرب عينة فقط؛ يقلل التشوه عند تحويل 48kHz إلى 44.1kHz.
            val position = frame.toDouble() * pcm.rate / targetRate
            val left = position.toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat().coerceIn(0f, 1f)
            for (ch in 0 until targetChannels) {
                val sourceCh = if (pcm.channels == 1) 0 else ch.coerceAtMost(pcm.channels - 1)
                val a = pcm.samples[left * pcm.channels + sourceCh].toFloat()
                val b = pcm.samples[right * pcm.channels + sourceCh].toFloat()
                result[frame * targetChannels + ch] = (a + (b - a) * fraction).toInt().coerceIn(-32768, 32767).toShort()
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

    /** Encode mixed 16-bit PCM into a real MPEG Layer III (MP3) stream. */
    private fun writeMp3(uri: Uri, mix: IntArray, rate: Int, channels: Int, onProgress: ((Int) -> Unit)? = null) {
        require(channels == 2) { "تصدير MP3 يتطلب قناتين صوتيتين" }
        val encoder = LameBuilder()
            .setInSampleRate(rate)
            .setOutChannels(channels)
            .setOutBitrate(128)
            .setOutSampleRate(rate)
            .setQuality(5)
            .build()
        // عند تداخل تسجيلين قد يتجاوز مجموع العينات مجال 16-bit؛ القصّ المباشر
        // يسبب تشويشًا/زنّة. نخفض مستوى المزيج كله مع الحفاظ على التوازن.
        val peak = mix.maxOfOrNull { kotlin.math.abs(it.toLong()) } ?: 0L
        val safeGain = if (peak > 29490L) 29490.0 / peak.toDouble() else 1.0
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                val framesPerChunk = 8192
                val pcm = ShortArray(framesPerChunk * channels)
                val mp3 = ByteArray(7200 + framesPerChunk * channels * 2)
                var frame = 0
                var lastReportedPercent = -1
                val totalFrames = mix.size / channels
                while (frame < totalFrames) {
                    val frames = min(framesPerChunk, totalFrames - frame)
                    for (i in 0 until frames * channels) {
                        pcm[i] = (mix[frame * channels + i] * safeGain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    val encoded = encoder.encodeBufferInterLeaved(pcm, frames, mp3)
                    if (encoded > 0) stream.write(mp3, 0, encoded)
                    frame += frames
                    val percent = if (totalFrames <= 0) 100 else (frame * 100L / totalFrames).toInt()
                    if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress?.invoke(percent) }
                }
                val flushed = encoder.flush(mp3)
                if (flushed > 0) stream.write(mp3, 0, flushed)
            } ?: error("تعذر فتح ملف MP3 للتصدير")
        } finally {
            encoder.close()
        }
    }

    /** تقدم مرحلي للتصدير النهائي: مزج التسجيلات ثم ترميز MP3 وحفظه. */
    private fun createFinalExportProgress(): Pair<AlertDialog, (Int, String) -> Unit> {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(10f))
            setBackgroundColor(PANEL)
        }
        val headline = TextView(this).apply {
            text = "تجهيز الملف النهائي — 0%"
            textSize = 16f; setTextColor(WHITE); gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
        }
        val details = TextView(this).apply {
            text = "لم يبدأ العمل بعد"
            textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(4f))
        }
        panel.addView(headline, LinearLayout.LayoutParams(-1, -2))
        panel.addView(bar, LinearLayout.LayoutParams(-1, dp(12f)))
        panel.addView(details, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this)
            .setTitle("تصدير MP3 النهائي")
            .setView(panel)
            .setCancelable(false)
            .setPositiveButton("إغلاق", null)
            .create()
        dialog.show()
        val update: (Int, String) -> Unit = { value, message ->
            val percent = value.coerceIn(0, 100)
            headline.text = "تجهيز الملف النهائي — $percent%"
            bar.progress = percent
            details.text = message
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = percent >= 100 || message.contains("فشل")
        }
        return dialog to update
    }

    private fun exportFinalAudio(uri: Uri) {
        if (isExporting) { showStatus("هناك عملية تصدير جارية بالفعل؛ انتظر اكتمالها أولًا"); return }
        val snapshot = clips.map { it.copy() }
        if (snapshot.isEmpty()) { showStatus("لا توجد تسجيلات لتصديرها"); return }
        val projectDurationMs = projectEndMs()
        isExporting = true
        val (progressDialog, updateProgress) = createFinalExportProgress()
        updateProgress(0, "بدء تجهيز ${snapshot.size} تسجيلات…")
        Thread {
            try {
                val targetRate = 44100; val targetChannels = 2
                val totalFrames = (projectDurationMs * targetRate / 1000L).toInt().coerceAtLeast(1)
                val mix = IntArray(totalFrames * targetChannels)
                val activeClips = snapshot.filter { !it.muted && it.lengthMs() > 0L }
                if (activeClips.isEmpty()) error("لا توجد تسجيلات صوتية مفعّلة في المشروع")
                activeClips.forEachIndexed { index, clip ->
                    handler.post { updateProgress((index * 65 / activeClips.size).coerceAtMost(64), "قراءة ومزج التسجيل ${index + 1} من ${activeClips.size}: ${clip.name}") }
                    val pcm = resample(decodePcm(Uri.parse(clip.uri)), targetRate, targetChannels)
                    val sourceStart = (clip.trimStartMs.toDouble() * targetRate / 1000).toInt().coerceAtLeast(0)
                    val sourceFrames = (clip.lengthMs().toDouble() * targetRate / 1000).toInt()
                    val destStart = (clip.startMs * targetRate / 1000L).toInt()
                    val frames = min(sourceFrames, pcm.size / targetChannels - sourceStart).coerceAtLeast(0)
                    for (frame in 0 until frames) for (ch in 0 until targetChannels) {
                        val dest = (destStart + frame) * targetChannels + ch
                        if (dest in mix.indices) mix[dest] += (pcm[(sourceStart + frame) * targetChannels + ch] * clip.volume).toInt()
                    }
                    val mixedPercent = ((index + 1) * 65 / activeClips.size).coerceAtMost(65)
                    handler.post { updateProgress(mixedPercent, "اكتمل مزج ${index + 1} من ${activeClips.size} تسجيلات") }
                }
                handler.post { updateProgress(66, "تم المزج؛ جارٍ ترميز الصوت إلى MP3…") }
                writeMp3(uri, mix, targetRate, targetChannels) { encodePercent ->
                    val overall = 66 + (encodePercent * 33 / 100)
                    handler.post { updateProgress(overall, "ترميز MP3 وحفظه: $encodePercent%") }
                }
                handler.post {
                    isExporting = false
                    updateProgress(100, "اكتمل إنشاء ملف MP3 النهائي وحفظه بنجاح")
                    showStatus("اكتمل تصدير MP3 النهائي بنجاح (100%)")
                }
            } catch (e: Exception) {
                // احذف الملف غير المكتمل إن أمكن، حتى لا يظهر ضمن النتائج كأنه MP3 سليم.
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                val details = "${e::class.java.name}: ${e.message ?: "خطأ غير معروف"}"
                lastErrorText = "فشل تصدير MP3 النهائي\n$details\nالمشروع: $projectName\nالتسجيلات: ${snapshot.joinToString(" | ") { it.name + " -> " + it.uri }}"
                handler.post {
                    isExporting = false
                    updateProgress(0, "فشل التصدير: ${e.message ?: "صيغة غير مدعومة"}. اضغط نسخ آخر خطأ لنسخ التفاصيل")
                    showStatus("فشل التصدير النهائي — اضغط نسخ آخر خطأ لمعرفة السبب")
                }
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
            leftGutter + ms / 1000f * zoom - panX

        private fun xToTime(x: Float): Long =
            (((x + panX - leftGutter) / zoom).coerceAtLeast(0f) * 1000f).toLong()

        private fun maxPanX(): Float =
            (leftGutter.toFloat() + projectEndMs() / 1000f * zoom + dp(100f) - width).coerceAtLeast(0f)

        /** تحريك نافذة العرض أفقيًا بمقدار يقارب ثلاثة أرباع الشاشة. */
        fun panByPage(direction: Int) {
            if (width <= 0) return
            val page = (width - leftGutter).coerceAtLeast(dp(100f)).toFloat() * 0.55f
            // الاتجاه الموجب يعرض زمنًا أحدث، والسالب يعيد إلى زمن أسبق.
            panX = (panX + direction * page).coerceIn(0f, maxPanX())
            invalidate()
        }

        private fun maxPanY(): Float =
            (rulerHeight + tracks * trackHeight - height).toFloat().coerceAtLeast(0f)

        fun zoomAroundX(factor: Float, anchorX: Float = width / 2f) {
            if (width <= 0) return
            val safeAnchor = anchorX.coerceIn(leftGutter.toFloat(), width.toFloat())
            val anchorTimeMs = xToTime(safeAnchor)
            zoom = (zoom * factor).coerceIn(6f, 600f)
            panX = (leftGutter + anchorTimeMs / 1000f * zoom - safeAnchor)
                .coerceIn(0f, maxPanX())
            invalidate()
        }

        // يثبت التكبير على مؤشر التشغيل العمودي المتحرك (Playhead)، وليس على
        // المستطيل أو الموجات أو منتصف المسافة بين الإصبعين.
        fun zoomAroundPlayhead(factor: Float) {
            zoomAroundX(factor, timeToX(playheadMs))
        }


        // ضع العلامة المطلوبة قرب منتصف مساحة العرض حتى تكون واضحة، لا خارج الشاشة.
        fun focusTime(timeMs: Long) {
            if (width <= 0) { invalidate(); return }
            val targetX = (leftGutter + (width - leftGutter) * 0.48f)
                .coerceIn(leftGutter.toFloat(), width.toFloat())
            panX = (leftGutter + timeMs.coerceAtLeast(0L) / 1000f * zoom - targetX)
                .coerceIn(0f, maxPanX())
            invalidate()
        }

        // تكبير/تصغير طبيعي بإصبعين مع إبقاء موضع التشغيل هو نقطة الارتكاز.
        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    dragMode = "pinch"
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomAroundPlayhead(detector.scaleFactor)
                    dragMode = "pinch"
                    return true
                }
            })

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

            clips.filter { it.id == visibleSourceId }.forEach { clip ->
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
                // موجة دقيقة مرتبطة بالزمن الفعلي للمقطع؛ لا تختفي عند التكبير.
                canvas.save()
                canvas.clipRect(max(leftGutter.toFloat(), x1), max(rulerHeight.toFloat(), top), width.toFloat(), min(height.toFloat(), bottom))
                val mid = (top + bottom) / 2f
                val halfHeight = (bottom - top) * 0.43f
                // خط المنتصف يجعل الصمت الحقيقي واضحًا بدل أن يبدو كأن الموجة مفقودة.
                paint.color = 0x557A8C91.toInt()
                paint.strokeWidth = dp(1f).toFloat()
                canvas.drawLine(x1, mid, x2, mid, paint)
                if (clip.waveform.isNotEmpty()) {
                    val total = clip.waveform.size
                    val duration = clip.durationMs.coerceAtLeast(1L).toFloat()
                    val first = ((clip.trimStartMs / duration) * total).toInt().coerceIn(0, total - 1)
                    val last = ((clip.trimEndMs / duration) * total).toInt().coerceIn(first + 1, total)
                    val count = (last - first).coerceAtLeast(1)
                    for (i in first until last) {
                        val fraction = (i - first + 0.5f) / count.toFloat()
                        val bx = x1 + fraction * (x2 - x1)
                        if (bx < leftGutter || bx > width) continue
                        val amp = clip.waveform[i].coerceIn(0.004f, 1f)
                        val loud = amp > 0.035f
                        paint.color = if (loud) 0xFFE2FFF2.toInt() else 0xFF71858A.toInt()
                        paint.strokeWidth = max(dp(1f).toFloat(), min(dp(2.6f).toFloat(), (x2 - x1) / count.toFloat() * 0.72f))
                        val h = max(dp(1f).toFloat(), halfHeight * amp)
                        canvas.drawLine(bx, mid - h, bx, mid + h, paint)
                    }
                } else {
                    // حالة انتظار التحليل فقط؛ لا نعرض موجة وهمية.
                    paint.color = 0x887A8C91.toInt()
                    paint.strokeWidth = dp(1f).toFloat()
                    canvas.drawLine(x1, mid, x2, mid, paint)
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
            cutMarks.forEachIndexed { index, mark ->
                val mx = timeToX(mark)
                val selected = selectedCutIndex == index
                paint.color = if (selected) WHITE else GOLD
                paint.strokeWidth = dp(if (selected) 5f else 2f).toFloat()
                canvas.drawLine(mx, rulerHeight.toFloat(), mx, height.toFloat(), paint)
                canvas.drawCircle(mx, dp(if (selected) 9f else 8f).toFloat(), dp(if (selected) 7f else 5f).toFloat(), paint)
                if (selected) { paint.color = BG; paint.textSize = dp(9f).toFloat(); canvas.drawText("محددة", mx - dp(17f), dp(23f).toFloat(), paint) }
            }
            pauseMarks.forEachIndexed { index, mark ->
                val mx = timeToX(mark.second)
                val selected = selectedPauseIndex == index
                paint.color = if (selected) WHITE else GREEN
                paint.strokeWidth = dp(if (selected) 5f else 2f).toFloat()
                canvas.drawLine(mx, rulerHeight.toFloat(), mx, height.toFloat(), paint)
                paint.textSize = dp(9f).toFloat()
                val relativeLabel = "+${formatOffsetSeconds(pauseOffsetMs(mark.first, mark.second))}"
                canvas.drawText(relativeLabel, mx + dp(3f), dp(12f).toFloat(), paint)
                if (selected) { paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f).toFloat(); canvas.drawCircle(mx, dp(11f).toFloat(), dp(10f).toFloat(), paint); paint.style = Paint.Style.FILL }
            }
            val px = timeToX(playheadMs)
            paint.color = 0xFFD88E87.toInt()
            paint.strokeWidth = dp(2f).toFloat()
            canvas.drawLine(px, 0f, px, height.toFloat(), paint)
            canvas.drawCircle(px, dp(5f).toFloat(), dp(5f).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // مرّر كل أحداث اللمس إلى كاشف الإيماءة أولًا؛ فهو يتابع المسافة بين الإصبعين
            // بسلاسة حتى مع تحريك الإصبعين أفقيًا أو رأسيًا.
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    changed = false
                    dragId = null

                    beforeDrag = projectJson()

                    val markerCut = cutMarks.withIndex().minByOrNull { abs(event.x - timeToX(it.value)) }
                    val markerPause = pauseMarks.withIndex().minByOrNull { abs(event.x - timeToX(it.value.second)) }
                    if (markerCut != null && abs(event.x - timeToX(markerCut.value)) < dp(22f)) {
                        val wasSelected = selectedCutIndex == markerCut.index
                        selectedCutIndex = markerCut.index; selectedPauseIndex = null
                        // إذا كانت العلامة غير محددة، فاللمسة القصيرة تحددها، أما السحب
                        // فيحوّل الحركة إلى تحريك نافذة العرض حتى لا تتعطل عند العلامة.
                        dragMode = if (wasSelected) "cutMark" else "markerTapPending"
                        changed = false
                        parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
                    }
                    if (markerPause != null && abs(event.x - timeToX(markerPause.value.second)) < dp(22f)) {
                        val wasSelected = selectedPauseIndex == markerPause.index
                        selectedPauseIndex = markerPause.index; selectedCutIndex = null
                        dragMode = if (wasSelected) "pauseMark" else "markerTapPending"
                        changed = false
                        parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
                    }

                    val px = timeToX(playheadMs)

                    if (abs(event.x - px) < dp(18f)) {
                        dragMode = "playhead"
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    if (event.y < rulerHeight) {
                        selectedId = null
                        selectedCutIndex = null
                        selectedPauseIndex = null
                        dragMode = "rulerPending"
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
                        // إلغاء التحديد فقط؛ مصدر العرض يبقى كما اختاره المستخدم من الزر السفلي.
                        selectedId = null
                        selectedCutIndex = null
                        selectedPauseIndex = null
                        dragMode = "pending"
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (selectedId != hit.id) {
                        // الضغطة الأولى للتحديد فقط؛ لا يبدأ التحريك إلا بعد تحديد سابق.
                        selectedId = hit.id
                        dragMode = "none"
                    } else {
                        // التسجيلات المرجعية ثابتة: يسمح بالنقر للتحديد فقط، ولا يسمح بتحريكها أو قصها.
                        selectedId = hit.id
                        dragId = null
                        dragMode = "none"
                        showStatus("هذا التسجيل ثابت؛ استخدم التكبير فقط، وسيتم تطبيق التحرير على جميع التسجيلات عند التصدير")
                    }
                    refreshTimeline()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    dragMode = "pinch"
                    changed = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 || scaleDetector.isInProgress || dragMode == "pinch") {
                        dragMode = "pinch"
                        invalidate()
                        return true
                    }
                    val clip = clips.firstOrNull { it.id == dragId }

                    when (dragMode) {
                        "markerTapPending", "rulerPending" -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (abs(dx) > dp(7f) || abs(dy) > dp(10f)) {
                                dragMode = "pan"
                                panX = (panX + lastX - event.x).coerceIn(0f, maxPanX())
                                panY = (panY + lastY - event.y).coerceIn(0f, maxPanY())
                            }
                            invalidate()
                        }
                        "cutMark" -> {
                            val index = selectedCutIndex
                            if (index != null && index in cutMarks.indices) {
                                val bounds = cutMoveBounds(index)
                                val proposed = xToTime(event.x).coerceIn(bounds.first, bounds.second)
                                if (proposed != cutMarks[index]) { cutMarks[index] = proposed; changed = true }
                            }
                            invalidate()
                        }
                        "pauseMark" -> {
                            val index = selectedPauseIndex
                            if (index != null && index in pauseMarks.indices) {
                                val bounds = pauseMoveBounds(index)
                                val proposed = xToTime(event.x).coerceIn(bounds.first, bounds.second)
                                val ayah = pauseMarks[index].first
                                if (proposed != pauseMarks[index].second) { pauseMarks[index] = ayah to proposed; changed = true }
                            }
                            invalidate()
                        }
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

                        "clip", "trimStart", "trimEnd" -> {
                            // التسجيلات ثابتة عند الثانية صفر؛ لا يوجد تحريك أو قص.
                        }
                    }

                    lastX = event.x
                    lastY = event.y
                    refreshTimeline()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // بعد رفع أحد الإصبعين لا نبدأ سحبًا أحادي الإصبع من نفس اللمسة.
                    dragMode = "none"
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && dragMode == "playhead") {
                        seekPlaybackTo(playheadMs)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP && dragMode == "rulerPending") {
                        playheadMs = xToTime(event.x).coerceAtMost(projectEndMs())
                        seekPlaybackTo(playheadMs)
                    }
                    if (changed && dragMode != "playhead" && dragMode != "pan") {
                        if (dragMode == "cutMark") recalculatePauseAyahs()
                        pauseMarks.sortWith(compareBy({ it.first }, { it.second }))
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
