package com.example.callagent

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.callagent.speech.SpeechModel
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnTest: Button
    private var testRunning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { !it }) {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    private val testPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTest()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnTest = findViewById(R.id.btnTest)

        val switchEnabled = findViewById<MaterialSwitch>(R.id.switchEnabled)
        switchEnabled.isChecked = Settings.isEnabled(this)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            Settings.setEnabled(this, checked)
            refreshStatus()
        }

        val etOwner = findViewById<EditText>(R.id.etOwner)
        etOwner.setText(Settings.ownerName(this))
        findViewById<Button>(R.id.btnSaveOwner).setOnClickListener {
            Settings.setOwnerName(this, etOwner.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnDialer).setOnClickListener { requestDialerRole() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            CallLogStore.clear(this)
            showCallLog()
        }
        btnTest.setOnClickListener { if (testRunning) CallSession.stop() else startTest() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (!testRunning) showCallLog()
        // The speech model unpacks in the background on first launch; keep the status fresh.
        handler.postDelayed(statusTicker, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // --- microphone test: the whole pipeline, without a phone call ---------------------------

    private fun startTest() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            testPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        tvLog.text = ""
        val started = CallSession.start(
            this, inCall = false, callerNumber = null, hangUp = {},
            onEvent = { line -> tvLog.append(line + "\n\n") },
            onFinished = {
                testRunning = false
                btnTest.setText(R.string.test_start)
            },
        )
        if (started) {
            testRunning = true
            btnTest.setText(R.string.test_stop)
        } else {
            Toast.makeText(this, R.string.busy, Toast.LENGTH_SHORT).show()
        }
    }

    // --- status ------------------------------------------------------------------------------

    private fun refreshStatus() {
        val model = when (SpeechModel.status) {
            SpeechModel.Status.READY -> "ready"
            SpeechModel.Status.LOADING -> "preparing (first launch takes a few seconds)…"
            SpeechModel.Status.FAILED -> "FAILED: ${SpeechModel.error}"
            SpeechModel.Status.NOT_LOADED -> "not loaded"
        }
        tvStatus.text = buildString {
            appendLine("Speech model: $model")
            appendLine("Permissions: " + yesNo(REQUIRED_PERMISSIONS.all(::granted)))
            appendLine("Accessibility service: " + yesNo(accessibilityEnabled()))
            appendLine("Default phone app: " + yesNo(holdsDialerRole()) + " (optional)")
            append("Auto-answer: " + if (Settings.isEnabled(this@MainActivity)) "ON" else "off")
        }
    }

    private fun showCallLog() {
        val entries = CallLogStore.recent(this)
        tvLog.text = if (entries.isEmpty()) getString(R.string.no_calls) else entries.joinToString("\n\n")
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "NO"

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = ComponentName(this, AccessibilityCallService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    private fun holdsDialerRole(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.dialer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
            Toast.makeText(this, R.string.dialer_already, Toast.LENGTH_SHORT).show()
        } else {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
        )
    }
}
