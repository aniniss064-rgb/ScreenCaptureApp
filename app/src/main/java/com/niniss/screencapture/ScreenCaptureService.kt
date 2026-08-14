package com.niniss.screencapture

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import kotlin.math.abs

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var intervalMillis = 5000L
    private var autoMode = false
    private var running = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        val intervalSeconds = intent?.getIntExtra("intervalSeconds", 5) ?: 5
        autoMode = intent?.getBooleanExtra("autoMode", false) ?: false
        intervalMillis = intervalSeconds * 1000L

        startForeground(1, buildNotification())

        if (data != null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
            showFloatingButton()
            running = true
            if (autoMode) handler.postDelayed(captureLoop, intervalMillis)
        }

        return START_NOT_STICKY
    }

    // ---------- Bouton flottant ----------

    private fun showFloatingButton() {
        if (!Settings.canDrawOverlaysCompat(this)) {
            Toast.makeText(
                this,
                "Autorise l'affichage par-dessus les autres apps pour le bouton",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }

        val button = floatingView!!.findViewById<View>(R.id.floatingCaptureButton)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDrag = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 12 || abs(dy) > 12) isDrag = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        // Cache la bulle pour qu'elle n'apparaisse pas sur la capture
                        floatingView?.visibility = View.INVISIBLE
                        handler.postDelayed({
                            captureScreenshot()
                            floatingView?.visibility = View.VISIBLE
                        }, 120)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, params)
    }

    // ---------- Capture ----------

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            captureScreenshot()
            handler.postDelayed(this, intervalMillis)
        }
    }

    private fun captureScreenshot(attempt: Int = 0) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            // Aucune nouvelle frame dispo, on retente brievement
            if (attempt < 5) {
                handler.postDelayed({ captureScreenshot(attempt + 1) }, 60)
            }
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val wide = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(buffer)

            // Recadre pour enlever le padding a droite
            val bitmap = Bitmap.createBitmap(wide, 0, 0, screenWidth, screenHeight)
            wide.recycle()

            saveBitmap(bitmap)
            bitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val filename = "screenshot_${System.currentTimeMillis()}.png"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenCaptureApp")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            val out: OutputStream? = resolver.openOutputStream(it)
            out?.use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            handler.post {
                Toast.makeText(this, "Capture enregistree", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "screen_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Capture d'ecran", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val text = if (autoMode) {
            "Auto : toutes les ${intervalMillis / 1000}s"
        } else {
            "Appuie sur la bulle pour capturer"
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Capture d'ecran active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureLoop)
        floatingView?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        floatingView = null
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    // Petit helper pour la verif overlay
    object Settings {
        fun canDrawOverlaysCompat(ctx: Context): Boolean {
            return android.provider.Settings.canDrawOverlays(ctx)
        }
    }
}
