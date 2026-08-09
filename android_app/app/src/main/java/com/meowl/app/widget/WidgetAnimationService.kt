package com.meowl.app.widget

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import com.meowl.app.R

/**
 * Lightweight Background Service providing ultra-smooth Bitmap Render Ticks
 * for Meowl Cat Eye AppWidget Home Screen animations.
 */
class WidgetAnimationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var animFrame = 0

    private val runnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            animFrame = (animFrame + 1) % 40
            updateWidgetBitmapFrame(animFrame)
            handler.postDelayed(this, 100) // 10 FPS smooth tick
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            handler.post(runnable)
        }
        return START_STICKY
    }

    private fun updateWidgetBitmapFrame(frame: Int) {
        try {
            val context = applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, MeowlCatWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            if (allWidgetIds.isEmpty()) {
                stopSelf()
                return
            }

            // Draw smooth custom eyes Bitmap
            val eyeHeight = when {
                frame in 35..37 -> 4f
                frame in 34..38 -> 16f
                else -> 32f
            }

            val bitmap = Bitmap.createBitmap(140, 50, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                color = Color.parseColor("#00F5FF")
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            // Draw Left & Right Cyan Eyes smoothly
            val top = (50f - eyeHeight) / 2f
            val bottom = top + eyeHeight
            canvas.drawRoundRect(RectF(30f, top, 52f, bottom), 10f, 10f, paint)
            canvas.drawRoundRect(RectF(88f, top, 110f, bottom), 10f, 10f, paint)

            for (widgetId in allWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_meowl_cat)
                views.setImageViewBitmap(R.id.img_idle_eyes_bitmap, bitmap)
                appWidgetManager.partiallyUpdateAppWidget(widgetId, views)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(runnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
