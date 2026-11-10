package dev.fumiproject.smarttap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 画面上の操作を監視・学習し、任意のアプリに対して自動タップを注入するサービス
 */
class SmartTapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartTapService"
        var instance: SmartTapAccessibilityService? = null
    }

    // 学習したタップステップの定義
    data class LearnedStep(
        val stepNumber: Int,
        val targetText: String?,
        val viewId: String?,
        val contentDescription: String?,
        val fallbackX: Float,
        val fallbackY: Float,
        val delayAfterMs: Long = 1000L
    )

    var isRecording = false
    val learnedSteps = mutableListOf<LearnedStep>()
    var onStepRecordedListener: ((LearnedStep) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "SmartTapAccessibilityService 接続完了")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "SmartTapAccessibilityService 終了")
    }

    /**
     * 他のアプリを開きながらユーザーが画面をタップした時に自動で呼ばれる
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return

        // タップ（クリック）されたイベントのみキャッチ
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source ?: return

            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()

            val text = source.text?.toString()?.trim()
            val desc = source.contentDescription?.toString()?.trim()
            val viewId = source.viewIdResourceName

            val step = LearnedStep(
                stepNumber = learnedSteps.size + 1,
                targetText = if (!text.isNullOrEmpty()) text else null,
                viewId = if (!viewId.isNullOrEmpty()) viewId else null,
                contentDescription = if (!desc.isNullOrEmpty()) desc else null,
                fallbackX = centerX,
                fallbackY = centerY,
                delayAfterMs = 1200L
            )

            learnedSteps.add(step)
            Log.d(TAG, "タップ学習: No.${step.stepNumber} Text=[$text] ID=[$viewId] Pos=($centerX, $centerY)")
            onStepRecordedListener?.invoke(step)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "SmartTapAccessibilityService 中断")
    }

    /**
     * 学習したステップを順次実行（スマート再現）
     */
    fun replaySteps(onFinished: () -> Unit) {
        Thread {
            for (step in learnedSteps) {
                var tapped = false

                // 1. まず画面内のUI要素（テキストやボタンID）から学習対象を探す
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val candidateNode = findMatchingNode(rootNode, step)
                    if (candidateNode != null) {
                        // ノードが見つかったらその中心座標をタップ
                        val bounds = Rect()
                        candidateNode.getBoundsInScreen(bounds)
                        performTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        tapped = true
                        Log.d(TAG, "ステップ ${step.stepNumber}: UI一致でタップ実行 -> ${bounds.centerX()}, ${bounds.centerY()}")
                    }
                }

                // 2. 見つからない場合は記録された座標をフォールバックでタップ
                if (!tapped) {
                    performTap(step.fallbackX, step.fallbackY)
                    Log.d(TAG, "ステップ ${step.stepNumber}: 座標フォールバックタップ -> ${step.fallbackX}, ${step.fallbackY}")
                }

                Thread.sleep(step.delayAfterMs)
            }
            onFinished()
        }.start()
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, step: LearnedStep): AccessibilityNodeInfo? {
        // テキストで一致する要素を検索
        step.targetText?.let { text ->
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                return nodes.first()
            }
        }
        // View IDで一致する要素を検索
        step.viewId?.let { id ->
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                return nodes.first()
            }
        }
        return null
    }

    /**
     * 指定座標へタッチジェスチャーを注入
     */
    fun performTap(x: Float, y: Float, durationMs: Long = 50L) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }
}
