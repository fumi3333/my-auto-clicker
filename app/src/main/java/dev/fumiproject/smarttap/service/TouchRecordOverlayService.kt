package dev.fumiproject.smarttap.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class TouchRecordOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private var isPlaying = false

    // ピン（ターゲット）のリスト
    private val targets = mutableListOf<TargetMarker>()

    data class TargetMarker(
        val view: TextView,
        val params: WindowManager.LayoutParams
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initControlBar()
    }

    private fun getOverlayFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun initControlBar() {
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1F1F1F"))
            setPadding(8, 8, 8, 8)
        }

        // 状態表示
        val statusText = TextView(this).apply {
            text = "MyClicker"
            textSize = 10f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(2, 2, 2, 4)
        }

        // 「＋」ピン追加ボタン
        val btnAdd = Button(this).apply {
            text = "＋"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#007ACC"))
            setOnClickListener { addTargetMarker() }
        }

        // 「ー」ピン削除ボタン
        val btnRemove = Button(this).apply {
            text = "ー"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#555555"))
            setOnClickListener { removeLastTarget() }
        }

        // 「▶ / ■」再生・停止ボタン
        val btnPlay = Button(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#28A745"))
            setOnClickListener { togglePlayback(this) }
        }

        // 「✕」アプリ終了ボタン
        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DC3545"))
            setOnClickListener { stopSelf() }
        }

        controlBar.addView(statusText)
        controlBar.addView(btnAdd)
        controlBar.addView(btnRemove)
        controlBar.addView(btnPlay)
        controlBar.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }

        // バー自体のドラッグ移動
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        controlBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(controlBar, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(controlBar, params)
    }

    /**
     * 画面上にタップしたい場所を示すピン（①②③）を追加
     */
    private fun addTargetMarker() {
        val index = targets.size + 1
        val markerView = TextView(this).apply {
            text = "$index"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CCFF5500")) // 半透明オレンジ
            setPadding(20, 20, 20, 20)
        }

        val params = WindowManager.LayoutParams(
            130, 130,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 250 + (index * 30)
            y = 450
        }

        var tInitX = 0
        var tInitY = 0
        var tTouchX = 0f
        var tTouchY = 0f

        // ピンを指でドラッグして好きなキャラ枠へ置ける
        markerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    tInitX = params.x
                    tInitY = params.y
                    tTouchX = event.rawX
                    tTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = tInitX + (event.rawX - tTouchX).toInt()
                    params.y = tInitY + (event.rawY - tTouchY).toInt()
                    windowManager.updateViewLayout(markerView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(markerView, params)
        targets.add(TargetMarker(markerView, params))
        Toast.makeText(this, "ピン $index を追加しました。キャラの上に置いてください。", Toast.LENGTH_SHORT).show()
    }

    private fun removeLastTarget() {
        if (targets.isNotEmpty()) {
            val last = targets.removeAt(targets.size - 1)
            windowManager.removeView(last.view)
        }
    }

    /**
     * ピンを置いた場所を順番に自動連打
     */
    private fun togglePlayback(btn: Button) {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスが未接続です", Toast.LENGTH_SHORT).show()
            return
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, "「＋」を押してキャラの上にピンを置いてください", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = !isPlaying
        if (isPlaying) {
            btn.text = "■"
            btn.setBackgroundColor(Color.parseColor("#DC3545"))
            Toast.makeText(this, "自動連打スタート！", Toast.LENGTH_SHORT).show()

            Thread {
                while (isPlaying) {
                    for (target in targets) {
                        if (!isPlaying) break
                        // ピンの中心をタップ
                        val clickX = (target.params.x + 65).toFloat()
                        val clickY = (target.params.y + 65).toFloat()
                        service.tap(clickX, clickY)
                        Thread.sleep(350) // 連打間隔（ミリ秒）
                    }
                }
            }.start()
        } else {
            btn.text = "▶"
            btn.setBackgroundColor(Color.parseColor("#28A745"))
            Toast.makeText(this, "自動連打ストップ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        for (target in targets) {
            windowManager.removeView(target.view)
        }
        targets.clear()
        if (::controlBar.isInitialized) {
            windowManager.removeView(controlBar)
        }
    }
}
