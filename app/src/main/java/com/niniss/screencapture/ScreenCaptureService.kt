package com.niniss.screencapture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
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

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "screen_capture_channel"
    }

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

    // OBLIGATOIRE sur Android 14+ : sans ce callback enregistre avant
    // createVirtualDisplay(), le systeme leve une exception et tue le service.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection arretee par le systeme")
            handler.post {
                Toast.makeText(
                    this@ScreenCaptureService,
                    "Partage d'ecran arrete",
                    Toast.LENGTH_SHORT
                ).show()
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        val intervalSeconds = intent?.getIntExtra("intervalSeconds", 5) ?: 5
        autoMode = intent?.getBooleanExtra("autoMode", false) ?: false
        intervalMillis = (intervalSeconds.coerceAtLeast(1)) * 1000L

        // 1. Le foreground service DOIT demarrer avant getMediaProjection()
        startAsForeground()

        if (data == null || resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Pas de donnees de projection valides")
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. La bulle en premier : meme si la capture echoue, on voit qqch
        showFloatingButton()

        // 3. Petit delai : Android 14 veut que le FGS soit bien etabli
        handler.postDelayed({ initProjection(resultCode, data) }, 300)

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground a echoue", e)
            toast("Erreur notification : ${e.message}")
            stopSelf()
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                toast("Impossible d'obtenir la projection")
                stopSelf()
                return
            }

            // ORDRE CRITIQUE : callback AVANT createVirtualDisplay
            mediaProjection?.registerCallback(projectionCallback, handler)

            setupVirtualDisplay()
            running = true

            if (autoMode) handler.postDelayed(captureLoop, intervalMillis)
            toast("Pret - appuie sur la bulle")
        } catch (e: Exception) {
            Log.e(TAG, "initProjection a echoue", e)
            toast("Erreur : ${e.message}")
            stopSelf()
        }
    }

    // ---------- Bouton flottant ----------

    private fun showFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Autorisation 'par-dessus les autres apps' manquante")
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
            floatingView = view

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 500
            }

            val button = view.findViewById<View>(R.id.floatingCaptureButton)

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var isDrag = false

            button.setOnTouchListener { v, event ->
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
                        if (abs(dx) > 15 || abs(dy) > 15) isDrag = true
                        if (isDrag) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            try {
                                windowManager?.updateViewLayout(floatingView, params)
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            v.performClick()
                            onBubbleTapped()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(view, params)
            Log.d(TAG, "Bulle affichee")
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'afficher la bulle", e)
            toast("Bulle impossible : ${e.message}")
        }
    }

    private fun onBubbleTapped() {
        if (!running) {
            toast("Capture pas encore prete")
            return
        }
        // On cache la bulle pour qu'elle ne soit pas sur l'image
        floatingView?.visibility = View.INVISIBLE
        handler.postDelayed({
            captureScreenshot()
            handler.postDelayed({ floatingView?.visibility = View.VISIBLE }, 150)
        }, 150)
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
            imageReader?.surface, null, handler
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
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage", e)
            null
        }

        if (image == null) {
            if (attempt < 8) {
                handler.postDelayed({ captureScreenshot(attempt + 1) }, 80)
            } else {
                toast("Aucune image disponible")
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

            val bitmap = if (wide.width != screenWidth) {
                val cropped = Bitmap.createBitmap(wide, 0, 0, screenWidth, screenHeight)
                wide.recycle()
                cropped
            } else wide

            saveBitmap(bitmap)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenshot", e)
            toast("Erreur capture : ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val filename = "screenshot_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenCaptureApp")
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                toast("Impossible de creer le fichier")
                return
            }
            val out: OutputStream? = contentResolver.openOutputStream(uri)
            out?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Capture enregistree")
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap", e)
            toast("Erreur sauvegarde : ${e.message}")
        }
    }

    private fun toast(msg: String) {
        handler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Capture d'ecran", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val text = if (autoMode) {
            "Auto : toutes les ${intervalMillis / 1000}s"
        } else {
            "Appuie sur la bulle pour capturer"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
