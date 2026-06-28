package com.keyboardhawk.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnEnableKeyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSelectKeyboard).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        val accessEnabled = am.isEnabled
        val keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }

        val status = buildString {
            appendLine("System Status")
            appendLine()
            appendLine(if (accessEnabled) "✓  Accessibility Service: ACTIVE" else "✗  Accessibility Service: DISABLED")
            appendLine(if (keyboardEnabled) "✓  Keyboard Hawk: ENABLED" else "✗  Keyboard Hawk: DISABLED")
            appendLine()
            appendLine("AI Proxy: hawk-proxyout.onrender.com")
            appendLine("Models: LOCAL (Colab) / FAST / SMART (Groq)")
        }
        findViewById<TextView>(R.id.tvSetupStatus).text = status
    }
}
