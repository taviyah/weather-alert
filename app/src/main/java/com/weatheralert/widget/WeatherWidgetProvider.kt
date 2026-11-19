package com.weatheralert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.weatheralert.MainActivity
import com.weatheralert.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget.
 *
 * Its update period is zero on purpose: nothing polls it. The worker pushes a refresh after each
 * check, and the app pushes one when the watched list or the thresholds change. A widget that
 * woke itself every half hour would repaint an e-paper panel forty-odd times a day to redraw the
 * same sentence.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, manager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        render(context, manager, intArrayOf(appWidgetId))
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        // onUpdate is not a suspend function and the content lives in DataStore, so the broadcast
        // is held open while it is read.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val content = runCatching { WidgetContent.build(context) }
                    .getOrDefault(WidgetContent.EMPTY)
                ids.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, manager, id, content))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        c: WidgetContent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_alert)

        views.setTextViewText(R.id.widget_place, c.place)
        views.setTextViewText(R.id.widget_more, c.morePlaces ?: "")
        views.setViewVisibility(R.id.widget_more, if (c.morePlaces != null) View.VISIBLE else View.GONE)

        views.setTextViewText(R.id.widget_headline, c.headline)
        views.setTextViewText(R.id.widget_mark, c.mark ?: "")
        views.setViewVisibility(R.id.widget_mark, if (c.mark != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_checked, c.checked)

        // How much fits depends on how tall the user made it. Everything below the headline is
        // optional, and a short widget simply drops the tail rather than clipping mid-line.
        val minHeightDp = manager.getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

        fun line(viewId: Int, text: String?, needsDp: Int) {
            val show = text != null && minHeightDp >= needsDp
            views.setViewVisibility(viewId, if (show) View.VISIBLE else View.GONE)
            if (show) views.setTextViewText(viewId, text)
        }

        line(R.id.widget_detail, c.detail, 90)
        line(R.id.widget_advice, c.advice, 110)
        line(R.id.widget_spillover, c.spillover?.let { "also $it" }, 150)

        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    companion object {
        /**
         * Redraw every placed widget. Safe to call from anywhere; a no-op when none exist.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
