package com.niniss.screencapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var autoCheckBox: CheckBox

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intervalSeconds = intervalInput.text.toString().toIntOrNull() ?: 5
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                    putExtra("intervalSeconds", intervalSeconds)
                    putExtra("autoMode", autoCheckBox.isChecked)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                statusText.text = "Capture active - utilise la bulle bleue"
                // On reduit l'app pour que la bulle soit utilisable tout de suite
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Permission refusee", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkOverlayThenStart()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                Toast.makeText(
                    this,
                    "Sans cette autorisation, pas de bouton flottant",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        intervalInput = findViewById(R.id.intervalInput)
        autoCheckBox = findViewById(R.id.autoCheckBox)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            checkNotificationThenStart()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            statusText.text = "En pause"
        }
    }

    private fun checkNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkOverlayThenStart()
    }

    private fun checkOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Active l'autorisation pour le bouton flottant",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
