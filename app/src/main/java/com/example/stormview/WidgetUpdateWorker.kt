package com.example.stormview

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Refreshes every StormView widget, then schedules itself again in [INTERVAL_MINUTES].
 *
 * `updatePeriodMillis` can't go below 30 minutes, and work started from a widget broadcast can be
 * killed before it finishes, so all refreshes run here instead. A chained one-time request is
 * used because periodic work can't run more often than every 15 minutes. Android may still defer
 * runs while the device dozes.
 */
class WidgetUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val manager = AppWidgetManager.getInstance(context)
        fun ids(cls: Class<*>) = manager.getAppWidgetIds(ComponentName(context, cls))

        val weatherWidgets = listOf(
            R.layout.widget_small to ids(WeatherWidget::class.java),
            R.layout.widget_medium to ids(WeatherWidgetMedium::class.java),
            R.layout.widget_large to ids(WeatherWidgetLarge::class.java)
        )
        val radarIds = ids(RadarWidget::class.java)

        // Nothing on the home screen: stop the refresh chain until a widget is added again.
        if (weatherWidgets.all { it.second.isEmpty() } && radarIds.isEmpty()) return Result.success()

        val (lat, lon) = WeatherWidget.currentLocation(context)

        if (weatherWidgets.any { it.second.isNotEmpty() }) {
            val forecast = runCatching { WeatherWidget.fetchForecast(lat, lon) }.getOrNull()
            for ((layoutId, widgetIds) in weatherWidgets) {
                for (id in widgetIds) {
                    try {
                        if (forecast == null) throw IllegalStateException("No forecast")
                        WeatherWidget.render(context, manager, id, layoutId, forecast)
                    } catch (e: Exception) {
                        WeatherWidget.renderError(context, manager, id, layoutId)
                    }
                }
            }
        }

        for (id in radarIds) {
            RadarWidget.update(context, manager, id, lat, lon)
        }

        scheduleNext(context)
        return Result.success()
    }

    companion object {
        private const val INTERVAL_MINUTES = 10L
        private const val WORK_NOW = "widget_refresh_now"
        private const val WORK_NEXT = "widget_refresh_next"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Refreshes all widgets as soon as there's a network connection. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        // A separate unique name from WORK_NOW so scheduling the next run never cancels this one.
        private fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NEXT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
