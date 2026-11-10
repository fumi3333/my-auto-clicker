package dev.fumiproject.smarttap.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * スマホの画面上だけでタップ・長押し・スワイプを録画し、スマホ内に保存して無限再生するサービス。
 *
 * 録画中も下のゲーム画面（にゃんこ大戦争など）のボタンがそのまま押せるよう、
 * 指を離した瞬間に透明シートの当たり判定を一時的に外して同じ操作を下の画面へ流し、
 * 画面遷移しながら何ページでも連続して記録できる。
 */
class ReplayOverlayService : Service() {

    data class Step(
        val time: Double,
        val x: Float, val y: Float,
        val x2: Float, val y2: Float,
        val dur: Long
    )

    private lateinit var windowManager: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var btnRec: Button
    private lateinit var btnPlay: Button
    private val main = Handler(Looper.getMainLooper())

    private var touchSheet: View? = null
    private var sheetParams: WindowManager.LayoutParams? = null

    private val steps = mutableListOf<Step>()
    private var sourceLabel = ""

    @Volatile
    private var isRecording = false

    @Volatile
    private var isPlaying = false

    @Volatile
    private var isPassingThrough = false

    private var recStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initBar()
        reload()
    }

    private fun saveFile(): File = File(getExternalFilesDir(null), "taps.json")

    /** スマホ内の保存ファイルに記録を書き込む（アプリを閉じても消えない） */
    private fun saveToPhone() {
        try {
            val arr = JSONArray()
            for (s in steps) {
                val o = JSONObject()
                o.put("time", s.time)
                o.put("x", s.x.toDouble())
                o.put("y", s.y.toDouble())
                o.put("x2", s.x2.toDouble())
                o.put("y2", s.y2.toDouble())
                o.put("dur", s.dur)
                arr.put(o)
            }
            saveFile().writeText(arr.toString(2), Charsets.UTF_8)
            sourceLabel = "保存済み"
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 保存された記録を読み込む */
    private fun reload() {
        val file = saveFile()
        val fromPhone = file.exists()
        val text = try {
            if (fromPhone) file.readText(Charsets.UTF_8)
            else assets.open("taps.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }

        steps.clear()
        try {
            val arr = JSONArray(text ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val x = o.getDouble("x")
                val y = o.getDouble("y")
                steps.add(
                    Step(
                        time = o.getDouble("time"),
                        x = x.toFloat(), y = y.toFloat(),
                        x2 = o.optDouble("x2", x).toFloat(),
                        y2 = o.optDouble("y2", y).toFloat(),
                        dur = o.optLong("dur", 50L)
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }

        sourceLabel = if (fromPhone) "保存済み" else "初期データ"
        updateStatusText()
    }

    private fun updateStatusText() {
        status.text = "$sourceLabel\n${steps.size}回分"
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
            setPadding(12, 12, 12, 12)
        }

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 10)
        }

        btnRec = Button(this).apply {
            text = "● 録画"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC3333"))
            setOnClickListener { toggleRecord() }
        }

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 10)
        }

        btnPlay = Button(this).apply {
            text = "▶ 再生"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#22AA22"))
            setOnClickListener { togglePlay() }
        }

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 10)
        }

        val btnClose = Button(this).apply {
            text = "✕ 閉じる"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { stopSelf() }
        }

        bar.addView(status)
        bar.addView(btnRec)
        bar.addView(spacer1)
        bar.addView(btnPlay)
        bar.addView(spacer2)
        bar.addView(btnClose)

        barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }

        // 上の文字部分をつかんで好きな場所へ移動できる
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = barParams.x; iy = barParams.y; tx = e.rawX; ty = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = ix + (e.rawX - tx).toInt()
                    barParams.y = iy + (e.rawY - ty).toInt()
                    windowManager.updateViewLayout(bar, barParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bar, barParams)
    }

    private fun toggleRecord() {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助でこのアプリをONにしてください", Toast.LENGTH_LONG).show()
            return
        }
        if (isPlaying) {
            Toast.makeText(this, "先に再生を止めてください", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        isPassingThrough = false
        steps.clear()
        recStartMs = SystemClock.uptimeMillis()

        btnRec.text = "■ 録画終了"
        btnRec.setBackgroundColor(Color.parseColor("#FF8800"))
        btnPlay.isEnabled = false
        status.text = "録画中\n0回"

        val sheet = View(this).apply {
            // ほんのわずかに色をつけて録画中だとわかるようにしつつ、画面はそのまま見える透明シート
            setBackgroundColor(Color.argb(12, 255, 0, 0))
        }

        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        var downX = 0f
        var downY = 0f
        var downTime = 0L

        sheet.setOnTouchListener { _, e ->
            if (!isRecording || isPassingThrough) return@setOnTouchListener true

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downTime = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val upX = e.rawX
                    val upY = e.rawY
                    val now = SystemClock.uptimeMillis()
                    val dur = (now - downTime).coerceIn(45L, 5000L)
                    val timeSec = (downTime - recStartMs) / 1000.0

                    // わずかな指のブレは同じ座標へのタップとして扱う
                    val dist = hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                    val endX = if (dist < 18.0) downX else upX
                    val endY = if (dist < 18.0) downY else upY

                    val step = Step(
                        time = timeSec,
                        x = downX, y = downY,
                        x2 = endX, y2 = endY,
                        dur = dur
                    )
                    steps.add(step)
                    saveToPhone()
                    status.text = "録画中\n${steps.size}回 保存"

                    // 今のタッチを下のゲーム画面へそのまま通す（これでボタンを押して次の画面へ進める）
                    passTouchToGame(downX, downY, endX, endY, dur)
                }
            }
            true
        }

        touchSheet = sheet
        sheetParams = sp
        windowManager.addView(sheet, sp)

        // 操作バーを必ず一番手前に持ってくる（録画終了ボタンがいつでも押せるようにする）
        windowManager.removeView(bar)
        windowManager.addView(bar, barParams)

        Toast.makeText(this, "録画スタート！普通に画面をタップしてください", Toast.LENGTH_SHORT).show()
    }

    /**
     * 透明シートの当たり判定を一瞬だけ外し、今指で触ったのと全く同じタップ／スワイプを
     * 下のゲーム画面に届けてから、透明シートを元に戻す。
     */
    private fun passTouchToGame(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val sheet = touchSheet ?: return
        val sp = sheetParams ?: return
        val service = ClickerAccessibilityService.instance ?: return

        isPassingThrough = true
        sp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        sheet.visibility = View.INVISIBLE
        try {
            windowManager.updateViewLayout(sheet, sp)
        } catch (e: Exception) {
            isPassingThrough = false
            return
        }

        // シートがタッチ透過になるのを60ミリ秒待ってから、下のゲーム画面をタップする
        main.postDelayed({
            if (!isRecording) {
                isPassingThrough = false
                return@postDelayed
            }
            service.stroke(x1, y1, x2, y2, dur) {
                main.postDelayed({
                    if (isRecording && touchSheet != null && sheetParams != null) {
                        sheetParams!!.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        touchSheet!!.visibility = View.VISIBLE
                        try {
                            windowManager.updateViewLayout(touchSheet, sheetParams)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                    isPassingThrough = false
                }, 70L)
            }
        }, 60L)
    }

    private fun stopRecording() {
        isRecording = false
        isPassingThrough = false

        touchSheet?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        touchSheet = null
        sheetParams = null

        btnRec.text = "● 録画"
        btnRec.setBackgroundColor(Color.parseColor("#CC3333"))
        btnPlay.isEnabled = true

        saveToPhone()
        updateStatusText()
        Toast.makeText(this, "${steps.size}回の操作をスマホに保存しました", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlay() {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助でこのアプリをONにしてください", Toast.LENGTH_LONG).show()
            return
        }
        if (isRecording) {
            Toast.makeText(this, "先に録画を終了してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPlaying) {
            isPlaying = false
            btnPlay.text = "▶ 再生"
            btnPlay.setBackgroundColor(Color.parseColor("#22AA22"))
            btnRec.isEnabled = true
            reload()
            return
        }

        reload()
        if (steps.isEmpty()) {
            Toast.makeText(this, "まだ記録がありません。「● 録画」から記録してください", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = true
        btnPlay.text = "■ 停止"
        btnPlay.setBackgroundColor(Color.parseColor("#DD2222"))
        btnRec.isEnabled = false
        val list = steps.toList()

        Thread {
            var round = 1
            val first = list.first().time
            while (isPlaying) {
                val start = SystemClock.uptimeMillis() + 300
                for ((i, s) in list.withIndex()) {
                    if (!isPlaying) break
                    sleepUntil(start + ((s.time - first) * 1000).toLong())
                    if (!isPlaying) break
                    service.stroke(s.x, s.y, s.x2, s.y2, s.dur)
                    main.post { status.text = "$sourceLabel\n${round}周目 ${i + 1}/${list.size}" }
                }
                sleepUntil(SystemClock.uptimeMillis() + list.last().dur + 1500)
                round++
            }
        }.start()
    }

    /** 停止ボタンがすぐ効くよう、100ミリ秒ずつ待つ */
    private fun sleepUntil(targetUptimeMs: Long) {
        while (isPlaying) {
            val left = targetUptimeMs - SystemClock.uptimeMillis()
            if (left <= 0) return
            Thread.sleep(if (left > 100) 100 else left)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        isRecording = false
        touchSheet?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        if (::bar.isInitialized) {
            try {
                windowManager.removeView(bar)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
