package com.mp.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.media3.common.Player

/**
 * Home-Screen-Widget: Cover, Titel, Artist, Prev / Play-Pause / Next.
 * Steuerung über Broadcasts an PlayerService; Update von PlayerService bei Zustandsänderung.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV -> {
                val i = Intent(context, PlayerService::class.java).setAction(intent.action)
                try {
                    context.startForegroundService(i)
                } catch (_: Exception) {
                    try { context.startService(i) } catch (_: Exception) {}
                }
            }
            ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
                ids.forEach { updateOne(context, mgr, it) }
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.mp.player.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.mp.player.widget.NEXT"
        const val ACTION_PREV = "com.mp.player.widget.PREV"
        const val ACTION_REFRESH = "com.mp.player.widget.REFRESH"

        fun requestUpdate(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_REFRESH).setComponent(ComponentName(context, PlayerWidget::class.java))
            )
        }

        fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.player_widget)
            val title = PlaybackState.title.ifBlank { "Secret Player" }
            val artist = PlaybackState.artist.ifBlank { "—" }
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setImageViewResource(
                R.id.widget_play,
                if (PlaybackState.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            val uri = PlaybackState.mediaId
            if (!uri.isNullOrBlank()) {
                try {
                    val f = Covers.ensureFile(context, uri)
                    if (f != null && f.exists()) {
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(f.absolutePath, opts)
                        var sample = 1
                        while (opts.outWidth / sample > 256 || opts.outHeight / sample > 256) sample *= 2
                        val bmp = BitmapFactory.decodeFile(
                            f.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                        if (bmp != null) views.setImageViewBitmap(R.id.widget_cover, bmp)
                    }
                } catch (_: Exception) {
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            views.setOnClickPendingIntent(
                R.id.widget_play,
                PendingIntent.getBroadcast(
                    context, 1, Intent(ACTION_PLAY_PAUSE).setComponent(ComponentName(context, PlayerWidget::class.java)), flags
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                PendingIntent.getBroadcast(
                    context, 2, Intent(ACTION_NEXT).setComponent(ComponentName(context, PlayerWidget::class.java)), flags
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_prev,
                PendingIntent.getBroadcast(
                    context, 3, Intent(ACTION_PREV).setComponent(ComponentName(context, PlayerWidget::class.java)), flags
                )
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_title, open)
            manager.updateAppWidget(id, views)
        }
    }
}
