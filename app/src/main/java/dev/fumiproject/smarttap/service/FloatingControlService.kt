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

class FloatingControlService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var containerLayout: LinearLayout
    private lateinit var menuLayout: LinearLayout
    private lateinit var bubbleBtn: Button
    private lateinit var statusText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingOverlay()
    }

    private fun initFloatingOverlay() {
        containerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 浮遊バブルアイコン（常時表示される小さな丸ボタン）
        bubbleBtn = Button(this).apply {
            text = "●"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EE222222"))
            setPadding(20, 20, 20, 20)
        }

        // 展開メニュー（記録、再生、終了）
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EE333333"))
            setPadding(10, 10, 10, 10)
            visibility = View.GONE
        }

        statusText = TextView(this).apply {
            text = "待機中"
            setTextColor(Color.YELLOW)
            setPadding(10, 10, 10, 10)
        }

        val btnRecord = Button(this).apply {
            text = "記録"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener {
                toggleRecording(this)
            }
        }

        val btnPlay = Button(this).apply {
            text = "再生"
            setBackgroundColor(Color.parseColor("#008800"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startReplay()
            }
        }

        val btnClose = Button(this).apply {
            text = "✕"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopSelf()
            }
        }

        menuLayout.addView(statusText)
        menuLayout.addView(btnRecord)
        menuLayout.addView(btnPlay)
        menuLayout.addView(btnClose)

        containerLayout.addView(bubbleBtn)
        containerLayout.addView(menuLayout)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }

        // バブルをタップでメニュー展開、ドラッグで画面端移動
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubbleBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(containerLayout, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // タップされたら展開メニューを開閉
                        if (menuLayout.visibility == View.VISIBLE) {
                            menuLayout.visibility = View.GONE
                        } else {
                            menuLayout.visibility = View.VISIBLE
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(containerLayout, params)

        // AccessibilityServiceからの記録通知を受け取る
        SmartTapAccessibilityService.instance?.let { service ->
            service.onStepRecordedListener = { step ->
                mainHandler.post {
                    val label = step.targetText ?: step.viewId ?: "座標(${step.fallbackX.toInt()},${step.fallbackY.toInt()})"
                    statusText.text = "学習: No.${step.stepNumber} $label"
                }
            }
        }
    }

    private fun toggleRecording(btn: Button) {
        val service = SmartTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスをONにしてください", Toast.LENGTH_SHORT).show()
            return
        }

        if (!service.isRecording) {
            service.isRecording = true
            service.learnedSteps.clear()
            btn.text = "停止"
            statusText.text = "記録中(他アプリを操作)"
            // 操作しやすいようメニューを自動で閉じる
            menuLayout.visibility = View.GONE
            Toast.makeText(this, "操作を記録します。他のアプリを普通にタップしてください。", Toast.LENGTH_LONG).show()
        } else {
            service.isRecording = false
            btn.text = "記録"
            statusText.text = "完了: ${service.learnedSteps.size}件"
            Toast.makeText(this, "記録終了（${service.learnedSteps.size}件の操作を学習）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startReplay() {
        val service = SmartTapAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスをONにしてください", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.learnedSteps.isEmpty()) {
            Toast.makeText(this, "記録された操作がありません。「記録」を押してからタップしてください。", Toast.LENGTH_SHORT).show()
            return
        }

        menuLayout.visibility = View.GONE
        statusText.text = "自動再生中..."
        Toast.makeText(this, "自動再生を開始します", Toast.LENGTH_SHORT).show()

        service.replaySteps {
            mainHandler.post {
                statusText.text = "再生完了"
                Toast.makeText(this, "すべての操作が完了しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::containerLayout.isInitialized) {
            windowManager.removeView(containerLayout)
        }
    }
}
