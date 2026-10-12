
package com.sahlaudio

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.RectF
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    data class AudioClip(
        val id: Int,
        val uri: Uri,
        val name: String,
        var duration: Int = 0,
        var start: Int = 0,
        var end: Int = 0,
        var position: Int = 0
    )

    private val clips = mutableListOf<AudioClip>()
    private var selectedId: Int? = null
    private var nextId = 1
    private var player: MediaPlayer? = null

    private lateinit var tracks: LinearLayout
    private lateinit var status: TextView
    private lateinit var timelineScroll: HorizontalScrollView

    private val background = Color.rgb(32, 32, 35)
    private val purple = Color.rgb(167, 121, 196)
    private val white = Color.WHITE
    private val scale = 70

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        buildInterface()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float = 14f
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(white)
        gravity = Gravity.CENTER
    }

    private fun button(
        value: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }

        root.addView(
            text("🎧  SahlAudio | محرر الصوت", 21f),
            LinearLayout.LayoutParams(-1, dp(60))
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        controls.addView(button("＋ إضافة صوت") { pickAudio() })
        controls.addView(button("▶ تشغيل") { playSelected() })
        controls.addView(button("■ إيقاف") { stopPlayback() })

        root.addView(
            controls,
            LinearLayout.LayoutParams(-1, dp(58))
        )

        status = text("أضف تسجيلاتك للبدء", 13f)
        root.addView(
            status,
            LinearLayout.LayoutParams(-1, dp(38))
        )

        timelineScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(Color.rgb(36, 36, 40))
        }

        tracks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        timelineScroll.addView(
            tracks,
            ViewGroup.LayoutParams(-2, -2)
        )

        root.addView(
            timelineScroll,
            LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        )

        val editControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        editControls.addView(
            button("◀ تحريك") { moveSelected(-1) }
        )
        editControls.addView(
            button("تحريك ▶") { moveSelected(1) }
        )
        editControls.addView(
            button("🗑 حذف") { deleteSelected() }
        )

        root.addView(
            editControls,
            LinearLayout.LayoutParams(-1, dp(60))
        )

        val note = text(
            "اختر مقطعًا من المسارات لتحديده",
            12f
        )
        note.setTextColor(Color.LTGRAY)
        root.addView(
            note,
            LinearLayout.LayoutParams(-1, dp(36))
        )

        setContentView(root)
        refreshTracks()
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 100)
    }

    @Deprecated("Handled for compatibility with the simple starter app")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != 100 ||
            resultCode != RESULT_OK ||
            data == null
        ) return

        val uris = mutableListOf<Uri>()

        data.clipData?.let { multi ->
            for (i in 0 until multi.itemCount) {
                uris.add(multi.getItemAt(i).uri)
            }
        }

        if (uris.isEmpty() && data.data != null) {
            uris.add(data.data!!)
        }

        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // بعض مزودي الملفات لا يسمحون بحفظ الإذن.
            }

            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?: "تسجيل ${nextId}"

            val clip = AudioClip(
                id = nextId++,
                uri = uri,
                name = name
            )

            try {
                val probe = MediaPlayer()
                probe.setDataSource(this, uri)
                probe.setOnPreparedListener { mp ->
                    clip.duration = mp.duration
                    clip.end = mp.duration
                    mp.release()
                    refreshTracks()
                }
                probe.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    true
                }
                probe.prepareAsync()
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "تعذرت قراءة مدة أحد الملفات",
                    Toast.LENGTH_SHORT
                ).show()
            }

            clip.position = clips.maxOfOrNull {
                it.position + it.duration
            } ?: 0

            clips.add(clip)
            selectedId = clip.id
        }

        refreshTracks()
        status.text = "عدد التسجيلات: ${clips.size}"
    }

    private fun refreshTracks() {
        if (!::tracks.isInitialized) return

        tracks.removeAllViews()

        val totalDuration = maxOf(
            12,
            clips.maxOfOrNull {
                it.position + it.duration
            } ?: 12
        )

        val ruler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(42, 42, 46))
        }

        for (second in 0..totalDuration step 2) {
            ruler.addView(
                text(
                    String.format(
                        Locale.US, "%02d:%02d",
                        second / 60, second % 60
                    ),
                    11f
                ),
                LinearLayout.LayoutParams(dp(scale * 2), dp(35))
            )
        }

        tracks.addView(ruler)

        clips.forEachIndexed { index, clip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(3), dp(4), dp(3), dp(4))
                setBackgroundColor(
                    if (clip.id == selectedId)
                        Color.rgb(64, 53, 70)
                    else
                        Color.rgb(39, 39, 43)
                )
            }

            row.addView(
                text("${index + 1}. ${clip.name}", 12f),
                LinearLayout.LayoutParams(-1, dp(25))
            )

            val track = ClipView(clip).apply {
                setOnClickListener {
                    selectedId = clip.id
                    status.text = "المحدد: ${clip.name}"
                    refreshTracks()
                }
            }

            val width = dp(
                maxOf(110, clip.duration / 1000 * scale)
            )

            row.addView(
                track,
                LinearLayout.LayoutParams(
                    width, dp(52)
                )
            )

            tracks.addView(
                row,
                LinearLayout.LayoutParams(
                    -2, dp(88)
                )
            )
        }

        tracks.minimumWidth = dp(totalDuration * scale)
    }

    private inner class ClipView(
        private val clip: AudioClip
    ) : View(this@MainActivity) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            paint.color = purple
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                dp(8).toFloat(),
                dp(8).toFloat(),
                paint
            )

            paint.color = Color.rgb(224, 194, 241)
            val center = height / 2f
            val bars = (width / dp(5)).coerceAtLeast(1)

            for (i in 0 until bars) {
                val fraction = ((i * 37 + clip.id * 19) % 83) / 100f
                val barHeight = dp(5) + fraction * height * 0.65f
                val x = i * dp(5).toFloat() + dp(2)

                canvas.drawRect(
                    x,
                    center - barHeight / 2,
                    x + dp(2),
                    center + barHeight / 2,
                    paint
                )
            }

            if (clip.id == selectedId) {
                paint.color = white
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawRoundRect(
                    RectF(1f, 1f, width - 1f, height - 1f),
                    dp(8).toFloat(),
                    dp(8).toFloat(),
                    paint
                )
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun selectedClip(): AudioClip? =
        clips.find { it.id == selectedId }

    private fun playSelected() {
        val clip = selectedClip()
        if (clip == null) {
            status.text = "اختر مقطعًا أولًا"
            return
        }

        stopPlayback()

        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, clip.uri)

                setOnPreparedListener { mp ->
                    val start = clip.start.coerceIn(
                        0, maxOf(0, mp.duration - 1)
                    )
                    mp.seekTo(start)
                    mp.start()
                    status.text = "يعمل الآن: ${clip.name}"
                }

                setOnCompletionListener {
                    status.text = "انتهى التشغيل"
                    it.release()
                    player = null
                }

                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    player = null
                    status.text = "تعذر تشغيل هذا الملف"
                    true
                }

                prepareAsync()
            }
        } catch (_: Exception) {
            status.text = "تعذر فتح الملف الصوتي"
        }
    }

    private fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        status.text = "تم إيقاف التشغيل"
    }

    private fun moveSelected(direction: Int) {
        val clip = selectedClip() ?: return

        clip.position = (
            clip.position + direction * 1000
        ).coerceAtLeast(0)

        refreshTracks()
        status.text = "تم تغيير موضع المقطع"
    }

    private fun deleteSelected() {
        val clip = selectedClip() ?: return

        stopPlayback()
        clips.remove(clip)
        selectedId = clips.lastOrNull()?.id

        refreshTracks()
        status.text = "تم حذف المقطع من المشروع"
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
