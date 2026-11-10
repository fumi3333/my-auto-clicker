package dev.fumiproject.smarttap.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * assets/taps.json に入れた記録を、スマホの中だけで順番に再生する。
 * 画面の端に ▶ / ■ / ✕ の小さなバーを出す。
 */
class ReplayOverlayService : Service() {

    data class Tap(val time: Double, val x: Float, val y: Float)

    private lateinit var windowManager: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnPlay: Button
    private val main = Handler(Looper.getMainLooper())

    private var taps: List<Tap> = emptyList()

    @Volatile
    private var isPlaying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        taps = loadTaps()
        initBar()
    }

    private fun loadTaps(): List<Tap> {
        return try {
            val text = assets.open("taps.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Tap(o.getDouble("time"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            }
        } catch (e: Exception) {
            Toast.makeText(this, "タップの記録を読めませんでした", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun initBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1A1A1A"))
            setPadding(10, 10, 10, 10)
        }

        status = TextView(this).apply {
            text = "${taps.size}回分"
            textSize = 12f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }

        btnPlay = Button(this).apply {
            text = "▶ 再生"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#22AA22"))
            setOnClickListener { toggle() }
        }

        val btnClose = Button(this).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { stopSelf() }
        }

        bar.addView(status)
        bar.addView(btnPlay)
        bar.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        // 数字の部分をつかんで動かせる
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (e.rawX - tx).toInt()
                    params.y = iy + (e.rawY - ty).toInt()
                    windowManager.updateViewLayout(bar, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bar, params)
    }

    private fun toggle() {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助でこのアプリをONにしてください", Toast.LENGTH_LONG).show()
            return
        }
        if (taps.isEmpty()) {
            Toast.makeText(this, "タップの記録がありません", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = !isPlaying
        if (!isPlaying) {
            btnPlay.text = "▶ 再生"
            btnPlay.setBackgroundColor(Color.parseColor("#22AA22"))
            status.text = "${taps.size}回分"
            return
        }

        btnPlay.text = "■ 停止"
        btnPlay.setBackgroundColor(Color.parseColor("#DD2222"))

        Thread {
            var round = 1
            while (isPlaying) {
                var prev = 0.0
                for ((i, t) in taps.withIndex()) {
                    if (!isPlaying) break
                    sleepWhilePlaying(((t.time - prev) * 1000).toLong())
                    prev = t.time
                    if (!isPlaying) break
                    service.tap(t.x, t.y, 50L)
                    main.post { status.text = "${round}周目 ${i + 1}/${taps.size}" }
                }
                sleepWhilePlaying(1500)
                round++
            }
        }.start()
    }

    /** 停止ボタンがすぐ効くよう、100ミリ秒ずつ待つ */
    private fun sleepWhilePlaying(ms: Long) {
        var left = ms
        while (left > 0 && isPlaying) {
            val step = if (left > 100) 100 else left
            Thread.sleep(step)
            left -= step
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        if (::bar.isInitialized) windowManager.removeView(bar)
    }
}
