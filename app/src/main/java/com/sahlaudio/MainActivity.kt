package com.sahlaudio

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val BG = 0xFF202020.toInt()
private const val PANEL = 0xFF2D2D2D.toInt()
private const val PURPLE = 0xFF9A70B7.toInt()
private const val WAVE = 0xFFD8C3EA.toInt()
private const val GOLD = 0xFFE2C77B.toInt()

class AudioClip(
    val id: Long,
    val uri: Uri,
    var name: String,
    var startMs: Long,
    var sourceDurationMs: Long,
    var trimStartMs: Long = 0,
    var trimEndMs: Long = sourceDurationMs
) {
    val durationMs: Long get() = max(100L, trimEndMs - trimStartMs)
    val endMs: Long get() = startMs + durationMs
}

class MainActivity : ComponentActivity() {
    private val clips = mutableListOf<AudioClip>()
    private val players = mutableMapOf<Long, MediaPlayer>()
    private val started = mutableSetOf<Long>()
    private var nextId = 1L
    private var selectedId: Long? = null
    private var playheadMs = 0L
    private var projectEndMs = 30_000L
    private var playing = false
    private var playbackStartedAt = 0L
    private var playbackStartPosition = 0L
    private lateinit var timeline: TimelineView
    private lateinit var timeLabel: TextView
    private lateinit var playButton: Button
    private lateinit var statusLabel: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uri = data.data ?: return@registerForActivityResult
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            addAudio(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        buildUi()
        refreshTimeline()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val title = TextView(this).apply { text = "سهل الصوت"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val subtitle = TextView(this).apply { text = "محرر صوت متعدد المسارات"; textSize = 13f; setTextColor(0xFFBDBDBD.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))

        val tools1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        tools1.addView(actionButton("＋ إضافة صوت") { openAudioPicker() }, weighted())
        tools1.addView(actionButton("✂ قص عند المؤشر") { splitSelected() }, weighted())
        tools1.addView(actionButton("حذف المقطع") { deleteSelected() }, weighted())
        root.addView(tools1)

        val tools2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        playButton = actionButton("▶ تشغيل") { togglePlayback() }
        tools2.addView(playButton, weighted(1.2f))
        tools2.addView(actionButton("■ إيقاف") { stopPlayback(reset = true) }, weighted())
        tools2.addView(actionButton("↤ للبداية") { setPlayhead(0) }, weighted())
        root.addView(tools2)

        timeLabel = TextView(this).apply { text = "00:00.0 / 00:30.0"; textSize = 14f; setTextColor(GOLD); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(7)) }
        root.addView(timeLabel)

        val horizontal = HorizontalScrollView(this).apply { isFillViewport = false; setBackgroundColor(BG); clipToPadding = false }
        val vertical = ScrollView(this).apply { isFillViewport = false; setBackgroundColor(BG) }
        timeline = TimelineView(this)
        vertical.addView(timeline, ScrollView.LayoutParams(-2, -2))
        horizontal.addView(vertical, HorizontalScrollView.LayoutParams(-2, -1))
        root.addView(horizontal, LinearLayout.LayoutParams(-1, 0, 1f))

        statusLabel = TextView(this).apply {
            text = "أضف ملفًا صوتيًا للبدء. اسحب المستطيل لتحريك المقطع، والمس المسطرة لنقل المؤشر."
            textSize = 12f; setTextColor(0xFFBDBDBD.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, dp(44), weight).apply { setMargins(dp(3), 0, dp(3), 0) }
    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4A3B54.toInt())); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        pickAudio.launch(intent)
    }

    private fun addAudio(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(500L) ?: 10_000L
            val name = queryDisplayName(uri) ?: "تسجيل ${clips.size + 1}"
            retriever.release()
            val clip = AudioClip(nextId++, uri, name, playheadMs, duration)
            clips.add(clip)
            selectedId = clip.id
            projectEndMs = max(projectEndMs, clip.endMs + 5_000)
            statusLabel.text = "أضيف: $name — ${formatTime(duration)}"
            refreshTimeline()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الملف الصوتي: ${e.message ?: "صيغة غير مدعومة"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    private fun splitSelected() {
        val clip = clips.firstOrNull { it.id == selectedId } ?: run { toast("اختر مقطعًا أولًا"); return }
        val local = playheadMs - clip.startMs
        if (local < 300 || local > clip.durationMs - 300) { toast("ضع المؤشر داخل المقطع، بعيدًا عن طرفيه"); return }
        val sourceCut = clip.trimStartMs + local
        val right = AudioClip(nextId++, clip.uri, clip.name + " (الجزء 2)", playheadMs, clip.sourceDurationMs, sourceCut, clip.trimEndMs)
        clip.trimEndMs = sourceCut
        clips.add(right)
        selectedId = right.id
        statusLabel.text = "تم تقسيم المقطع إلى جزأين (دون تعديل الملف الأصلي)."
        refreshTimeline()
    }

    private fun deleteSelected() {
        val id = selectedId ?: run { toast("اختر مقطعًا أولًا"); return }
        clips.removeAll { it.id == id }
        players.remove(id)?.release(); started.remove(id); selectedId = null
        recalculateEnd(); refreshTimeline(); statusLabel.text = "حُذف المقطع من المشروع فقط. لم يُحذف الملف الأصلي."
    }

    private fun recalculateEnd() { projectEndMs = max(30_000L, (clips.maxOfOrNull { it.endMs } ?: 0L) + 5_000) }
    private fun setPlayhead(ms: Long) { playheadMs = ms.coerceIn(0, projectEndMs); refreshTimeline() }
    private fun refreshTimeline() { if (::timeline.isInitialized) timeline.invalidate(); if (::timeLabel.isInitialized) timeLabel.text = "${formatTime(playheadMs)} / ${formatTime(projectEndMs)}" }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun formatTime(ms: Long): String { val tenths = max(0L, ms) / 100; return String.format(java.util.Locale.US, "%02d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10) }

    private fun togglePlayback() { if (playing) pausePlayback() else startPlayback() }
    private fun startPlayback() {
        if (clips.isEmpty()) { toast("أضف ملفًا صوتيًا أولًا"); return }
        if (playheadMs >= projectEndMs) playheadMs = 0
        playing = true; playbackStartPosition = playheadMs; playbackStartedAt = android.os.SystemClock.elapsedRealtime()
        playButton.text = "Ⅱ إيقاف مؤقت"
        handler.post(playbackTick)
    }
    private fun pausePlayback() {
        if (playing) playheadMs = min(projectEndMs, playbackStartPosition + android.os.SystemClock.elapsedRealtime() - playbackStartedAt)
        playing = false; handler.removeCallbacks(playbackTick); players.values.forEach { try { it.release() } catch (_: Exception) {} }; players.clear(); started.clear(); playButton.text = "▶ تشغيل"; refreshTimeline()
    }
    private fun stopPlayback(reset: Boolean) {
        playing = false; handler.removeCallbacks(playbackTick)
        players.values.forEach { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        players.clear(); started.clear(); playButton.text = "▶ تشغيل"
        if (reset) playheadMs = 0
        refreshTimeline()
    }

    private val playbackTick = object : Runnable {
        override fun run() {
            if (!playing) return
            playheadMs = playbackStartPosition + android.os.SystemClock.elapsedRealtime() - playbackStartedAt
            for (clip in clips) {
                if (playheadMs >= clip.startMs && playheadMs < clip.endMs && clip.id !in started) {
                    try {
                        val p = MediaPlayer()
                        p.setDataSource(this@MainActivity, clip.uri)
                        p.setOnPreparedListener { mp ->
                            if (!playing) { mp.release(); return@setOnPreparedListener }
                            try {
                                mp.setVolume(0.85f, 0.85f)
                                mp.seekTo((clip.trimStartMs + max(0L, playheadMs - clip.startMs)).toInt())
                                mp.start()
                            } catch (_: Exception) { }
                        }
                        p.setOnCompletionListener { mp -> try { mp.release() } catch (_: Exception) {}; players.remove(clip.id); started.remove(clip.id) }
                        players[clip.id] = p; started.add(clip.id); p.prepareAsync()
                    } catch (_: Exception) { started.add(clip.id) }
                }
                val p = players[clip.id]
                if (p != null && playheadMs >= clip.endMs) { try { p.stop() } catch (_: Exception) {}; try { p.release() } catch (_: Exception) {}; players.remove(clip.id); started.remove(clip.id) }
            }
            refreshTimeline()
            if (playheadMs >= projectEndMs) { stopPlayback(reset = false); return }
            handler.postDelayed(this, 40)
        }
    }

    override fun onDestroy() { stopPlayback(reset = false); super.onDestroy() }

    inner class TimelineView(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val pxPerSecond = 42f * density
        private val leftPad = 12f * density
        private val rulerHeight = 42f * density
        private val trackHeight = 76f * density
        private val gap = 8f * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var dragClip: AudioClip? = null
        private var dragOffsetMs = 0L
        private var moved = false
        private var touchClip: AudioClip? = null

        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = max(1600f * density, leftPad + projectEndMs / 1000f * pxPerSecond + 40f * density).toInt()
            val h = (rulerHeight + max(1, clips.size) * (trackHeight + gap) + 20f * density).toInt()
            setMeasuredDimension(w, h)
        }
        private fun xFor(ms: Long) = leftPad + ms / 1000f * pxPerSecond
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)
            paint.color = 0xFF353535.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, paint)
            val seconds = (projectEndMs / 1000).toInt() + 1
            for (s in 0..seconds) {
                val x = xFor(s * 1000L)
                paint.color = 0xFF777777.toInt(); paint.strokeWidth = 1f; canvas.drawLine(x, rulerHeight - 9f * density, x, height.toFloat(), paint)
                paint.color = 0xFFBBBBBB.toInt(); paint.textSize = 10f * density
                canvas.drawText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60), x + 3f, 15f * density, paint)
            }
            val rows = max(1, clips.size)
            for (i in 0 until rows) {
                val top = rulerHeight + i * (trackHeight + gap)
                paint.color = PANEL; canvas.drawRoundRect(RectF(4f, top, width - 4f, top + trackHeight), 8f * density, 8f * density, paint)
                val clip = clips.getOrNull(i) ?: continue
                val x1 = xFor(clip.startMs); val x2 = max(x1 + 48f * density, xFor(clip.endMs))
                val rect = RectF(x1, top + 9f * density, x2, top + trackHeight - 9f * density)
                paint.color = if (clip.id == selectedId) 0xFFB58AD0.toInt() else PURPLE
                canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
                // Small, subtle waveform bars inside the clip rectangle.
                val seed = clip.id * 73L + clip.durationMs / 100
                val random = Random(seed)
                val centerY = (rect.top + rect.bottom) / 2f
                val usableWidth = max(0f, rect.width() - 10f * density)
                val barStep = 5f * density
                var bx = rect.left + 5f * density
                while (bx < rect.left + 5f * density + usableWidth) {
                    val barHeight = (3 + random.nextInt(2, 13)) * density
                    paint.color = WAVE; paint.strokeWidth = 1.3f * density
                    canvas.drawLine(bx, centerY - barHeight / 2f, bx, centerY + barHeight / 2f, paint)
                    bx += barStep
                }
                paint.color = 0xFF211B26.toInt(); paint.textSize = 12f * density
                val label = clip.name.take(22)
                canvas.drawText(label, rect.left + 8f * density, rect.top + 17f * density, paint)
            }
            val playX = xFor(playheadMs)
            paint.color = Color.WHITE; paint.strokeWidth = 2f * density; canvas.drawLine(playX, 0f, playX, height.toFloat(), paint)
            paint.color = GOLD
            val p = floatArrayOf(playX - 5f * density, rulerHeight, playX + 5f * density, rulerHeight, playX, rulerHeight + 7f * density)
            val path = android.graphics.Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); close() }
            canvas.drawPath(path, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x; val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; moved = false; touchClip = findClipAt(x, y); dragClip = touchClip
                    if (dragClip != null) { selectedId = dragClip!!.id; dragOffsetMs = ((x - xFor(dragClip!!.startMs)) / pxPerSecond * 1000).toLong().coerceAtLeast(0) }
                    parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(x - downX) > 6f * density) moved = true
                    val clip = dragClip
                    if (clip != null && moved) {
                        clip.startMs = max(0L, (((x - leftPad) / pxPerSecond) * 1000).toLong() - dragOffsetMs)
                        playheadMs = clip.startMs; projectEndMs = max(projectEndMs, clip.endMs + 5_000); invalidate(); refreshTimeOnly()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (y < rulerHeight) setPlayhead((((x - leftPad) / pxPerSecond) * 1000).toLong())
                        else { val clip = findClipAt(x, y); if (clip != null) { selectedId = clip.id; statusLabel.text = "المحدد: ${clip.name}"; invalidate() } }
                    } else { statusLabel.text = "تم تحريك المقطع إلى ${formatTime(dragClip?.startMs ?: 0)}"; recalculateEnd(); requestLayout(); invalidate() }
                    dragClip = null; parent?.requestDisallowInterceptTouchEvent(false); refreshTimeline(); return true
                }
            }
            return true
        }
        private fun findClipAt(x: Float, y: Float): AudioClip? {
            if (y < rulerHeight) return null
            val row = ((y - rulerHeight) / (trackHeight + gap)).toInt()
            val clip = clips.getOrNull(row) ?: return null
            return if (x >= xFor(clip.startMs) && x <= xFor(clip.endMs)) clip else null
        }
        private fun refreshTimeOnly() { if (::timeLabel.isInitialized) timeLabel.text = "${formatTime(playheadMs)} / ${formatTime(projectEndMs)}" }
    }
}
