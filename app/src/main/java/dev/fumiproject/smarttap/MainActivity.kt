package dev.fumiproject.smarttap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.fumiproject.smarttap.service.ClickerAccessibilityService
import dev.fumiproject.smarttap.service.ClickerOverlayService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "重ねて表示権限はすでに許可されています", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_accessibility_permission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_start_service).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先に「重ねて表示」を許可してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ClickerAccessibilityService.instance == null) {
                Toast.makeText(this, "先に「ユーザー補助」でSmartTapをONにしてください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(Intent(this, ClickerOverlayService::class.java))
            finish()
        }
    }
}
