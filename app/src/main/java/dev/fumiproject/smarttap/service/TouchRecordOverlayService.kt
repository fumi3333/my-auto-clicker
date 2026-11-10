package dev.fumiproject.smarttap.service

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

class TouchRecordOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private var recordingLayerView: View? = null
    private lateinit var layerParams: WindowManager.LayoutParams

    private var isRecording = false
    private var isPlaying = false
    private var lastTouchTime = 0L
    private var isDispatching = false

    // 記録されたタッチステップ
    data class RecordedTouch(
        val x: Float,
        val y: Float,
        val delayBeforeMs: Long
    )

    private val recordedTouches = mutableListOf<RecordedTouch>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var statusText: TextView

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
            setBackgroundColor(Color.parseColor("#EE1A1A1A"))
            setPadding(10, 10, 10, 10)
        }

        statusText = TextView(this).apply {
            text = "待機中"
            textSize = 12f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 8)
        }

        btnRecord = Button(this).apply {
            text = "● 録画"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DD2222"))
            setOnClickListener { toggleRecording() }
        }

        btnPlay = Button(this).apply {
            text = "▶ 再生"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#22AA22"))
            setOnClickListener { togglePlayback() }
        }

        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { stopSelf() }
        }

        controlBar.addView(statusText)
        controlBar.addView(btnRecord)
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

        // コントロールバーのドラッグ移動
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
     * 録画の開始・停止
     */
    private fun toggleRecording() {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスをONにしてください", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPlaying) {
            Toast.makeText(this, "再生中は録画できません", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = !isRecording
        if (isRecording) {
            recordedTouches.clear()
            lastTouchTime = SystemClock.uptimeMillis()
            btnRecord.text = "■ 終了"
            btnRecord.setBackgroundColor(Color.parseColor("#FF8800"))
            statusText.text = "録画中(0)"
            Toast.makeText(this, "録画スタート！ゲームのボタンを押して次のページへ進んでください。", Toast.LENGTH_LONG).show()

            attachTransparentTouchLayer()
        } else {
            btnRecord.text = "● 録画"
            btnRecord.setBackgroundColor(Color.parseColor("#DD2222"))
            statusText.text = "記録:${recordedTouches.size}"
            Toast.makeText(this, "録画完了: ${recordedTouches.size}件のタップを記録しました", Toast.LENGTH_SHORT).show()

            detachTransparentTouchLayer()
        }
    }

    /**
     * タッチ貫通プロキシレイヤーを展開
     */
    private fun attachTransparentTouchLayer() {
        if (recordingLayerView != null) return

        var rippleX = -1f
        var rippleY = -1f
        val paint = Paint().apply {
            color = Color.parseColor("#80FF0000") // タップ位置の赤い丸エフェクト
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        recordingLayerView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (rippleX >= 0 && rippleY >= 0) {
                    canvas.drawCircle(rippleX, rippleY, 50f, paint)
                }
            }
        }.apply {
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN && !isDispatching) {
                    isDispatching = true

                    val curTime = SystemClock.uptimeMillis()
                    val delay = if (recordedTouches.isEmpty()) 200L else Math.min(curTime - lastTouchTime, 10000L)
                    lastTouchTime = curTime

                    val rx = event.rawX
                    val ry = event.rawY

                    // 1. タップを記録
                    recordedTouches.add(RecordedTouch(rx, ry, delay))
                    statusText.text = "録画中(${recordedTouches.size})"

                    // 2. 赤い丸エフェクト表示
                    rippleX = rx
                    rippleY = ry
                    view.invalidate()
                    mainHandler.postDelayed({
                        rippleX = -1f
                        rippleY = -1f
                        view.invalidate()
                    }, 150)

                    // 3. 【最重要】前面レイヤーを一時的にタッチ透過 (FLAG_NOT_TOUCHABLE) に変更！
                    // これにより最前面の膜が「幽霊化」し、裏のゲームへ本物のタップが貫通する！
                    layerParams.flags = layerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(recordingLayerView, layerParams)

                    // 4. 裏のゲームのボタンへタップを注入（60msしっかり押して離す）
                    ClickerAccessibilityService.instance?.tap(rx, ry, 60L) {
                        // タップが裏のゲームに届いてボタンが反応した後、次のタップを待つためにレイヤーを戻す
                        mainHandler.postDelayed({
                            if (isRecording && recordingLayerView != null) {
                                layerParams.flags = layerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                windowManager.updateViewLayout(recordingLayerView, layerParams)
                            }
                            isDispatching = false
                        }, 120)
                    }
                }
                true
            }
        }

        layerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(recordingLayerView, layerParams)
        // 操作バーを前面に再配置
        windowManager.removeView(controlBar)
        windowManager.addView(controlBar, controlBar.layoutParams)
    }

    private fun detachTransparentTouchLayer() {
        recordingLayerView?.let {
            windowManager.removeView(it)
            recordingLayerView = null
        }
    }

    /**
     * 再生（記録されたタッチの自動再現）
     */
    private fun togglePlayback() {
        val service = ClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "ユーザー補助サービスをONにしてください", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecording) {
            Toast.makeText(this, "先に録画を終了してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (recordedTouches.isEmpty()) {
            Toast.makeText(this, "記録されたタップがありません。「● 録画」で操作してください。", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = !isPlaying
        if (isPlaying) {
            btnPlay.text = "■ 停止"
            btnPlay.setBackgroundColor(Color.RED)
            statusText.text = "再生中"
            Toast.makeText(this, "自動再生を開始しました（ページ遷移も含めて無限ループ）", Toast.LENGTH_SHORT).show()

            Thread {
                while (isPlaying) {
                    for (step in recordedTouches) {
                        if (!isPlaying) break
                        if (step.delayBeforeMs > 0) {
                            Thread.sleep(step.delayBeforeMs)
                        }
                        service.tap(step.x, step.y, 60L)
                    }
                    // 1周（全ページ終了）したら少し待って次の周へ
                    Thread.sleep(1500)
                }
            }.start()
        } else {
            btnPlay.text = "▶ 再生"
            btnPlay.setBackgroundColor(Color.parseColor("#22AA22"))
            statusText.text = "待機中"
            Toast.makeText(this, "自動再生を停止しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        isPlaying = false
        detachTransparentTouchLayer()
        if (::controlBar.isInitialized) {
            windowManager.removeView(controlBar)
        }
    }
}
