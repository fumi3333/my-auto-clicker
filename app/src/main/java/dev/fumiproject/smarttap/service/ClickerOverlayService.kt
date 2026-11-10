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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ClickerOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private var isPlaying = false

    // 画面上に置いたターゲットマーカーのリスト
    private val targets = mutableListOf<TargetPoint>()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class TargetPoint(
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
            setBackgroundColor(Color.parseColor("#DD222222"))
            setPadding(8, 8, 8, 8)
        }

        // 「＋」マーカー追加ボタン
        val btnAdd = Button(this).apply {
            text = "＋"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3388FF"))
            setOnClickListener { addTargetPoint() }
        }

        // 「ー」マーカー削除ボタン
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
            setBackgroundColor(Color.parseColor("#22AA22"))
            setOnClickListener { togglePlay(this) }
        }

        // 「✕」終了ボタン
        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA2222"))
            setOnClickListener { stopSelf() }
        }

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
            y = 400
        }

        // コントロールバー自体のドラッグ移動
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

    private fun addTargetPoint() {
        val index = targets.size + 1
        val targetView = TextView(this).apply {
            text = "$index"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CCFF5500")) // オレンジの丸型風
            setPadding(25, 25, 25, 25)
        }

        val params = WindowManager.LayoutParams(
            120, 120,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200 + (index * 40)
            y = 500
        }

        var tInitX = 0
        var tInitY = 0
        var tTouchX = 0f
        var tTouchY = 0f

        targetView.setOnTouchListener { _, event ->
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
                    windowManager.updateViewLayout(targetView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(targetView, params)
        targets.add(TargetPoint(targetView, params))
        Toast.makeText(this, "ターゲット $index を追加しました。好きな位置へ動かしてください。", Toast.LENGTH_SHORT).show()
    }

    private fun removeLastTarget() {
        if (targets.isNotEmpty()) {
            val last = targets.removeAt(targets.size - 1)
            windowManager.removeView(last.view)
        }
    }

    private fun togglePlay(btn: Button) {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスをONにしてください", Toast.LENGTH_SHORT).show()
            return
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, "「＋」でタップしたい場所にターゲットを置いてください", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = !isPlaying
        if (isPlaying) {
            btn.text = "■"
            btn.setBackgroundColor(Color.RED)
            Toast.makeText(this, "自動タップ開始（無限ループ）", Toast.LENGTH_SHORT).show()

            Thread {
                while (isPlaying) {
                    for (target in targets) {
                        if (!isPlaying) break
                        // ターゲットの中心座標を計算
                        val clickX = (target.params.x + 60).toFloat()
                        val clickY = (target.params.y + 60).toFloat()
                        service.tap(clickX, clickY)
                        Thread.sleep(400) // 各ターゲット間のディレイ
                    }
                }
            }.start()
        } else {
            btn.text = "▶"
            btn.setBackgroundColor(Color.parseColor("#22AA22"))
            Toast.makeText(this, "自動タップ停止", Toast.LENGTH_SHORT).show()
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
