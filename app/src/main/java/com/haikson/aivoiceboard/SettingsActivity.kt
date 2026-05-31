package com.haikson.aivoiceboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var btnMicPermission: Button

    private val modelValues = listOf(
        "gpt-4o-transcribe",
        "gpt-4o-mini-transcribe",
        "whisper-1"
    )

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMicButton(granted)
        if (!granted) {
            Toast.makeText(this, getString(R.string.permission_mic_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        prefs = PrefsManager(this)
        val rawPrefs = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        val etPrompt = findViewById<EditText>(R.id.etPrompt)
        val etProxy = findViewById<EditText>(R.id.etProxy)
        val switchFmt = findViewById<Switch>(R.id.switchFmt)
        val btnSave = findViewById<Button>(R.id.btnSave)
        btnMicPermission = findViewById(R.id.btnMicPermission)

        // Load current values
        etApiKey.setText(prefs.apiKey)
        etPrompt.setText(prefs.prompt)
        etProxy.setText(prefs.proxy)
        switchFmt.isChecked = prefs.fmtEnable

        val modelIndex = modelValues.indexOf(prefs.model).takeIf { it >= 0 } ?: 0
        ArrayAdapter.createFromResource(this, R.array.model_entries, android.R.layout.simple_spinner_item)
            .also { adapter ->
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerModel.adapter = adapter
                spinnerModel.setSelection(modelIndex)
            }

        // Mic permission button
        updateMicButton(hasMicPermission())
        btnMicPermission.setOnClickListener {
            if (hasMicPermission()) {
                Toast.makeText(this, getString(R.string.permission_mic_granted), Toast.LENGTH_SHORT).show()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // IME setup buttons
        findViewById<Button>(R.id.btnEnableIme).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSelectIme).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Save
        btnSave.setOnClickListener {
            rawPrefs.edit()
                .putString("api_key", etApiKey.text.toString().trim())
                .putString("model", modelValues[spinnerModel.selectedItemPosition])
                .putString("prompt", etPrompt.text.toString().trim())
                .putString("proxy", etProxy.text.toString().trim())
                .putBoolean("fmt_enable", switchFmt.isChecked)
                .apply()
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateMicButton(hasMicPermission())
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun updateMicButton(granted: Boolean) {
        if (granted) {
            btnMicPermission.text = getString(R.string.permission_mic_granted)
            btnMicPermission.isEnabled = false
        } else {
            btnMicPermission.text = getString(R.string.permission_mic_request)
            btnMicPermission.isEnabled = true
        }
    }
}
