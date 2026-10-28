package com.example.stormview

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, R.layout.widget_small)
        }
    }

    companion object {
        private val DAY_IDS = listOf(
            intArrayOf(R.id.day1_name, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_precip),
            intArrayOf(R.id.day2_name, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_precip),
            intArrayOf(R.id.day3_name, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_precip),
            intArrayOf(R.id.day4_name, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_precip),
            intArrayOf(R.id.day5_name, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_precip),
            intArrayOf(R.id.day6_name, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_precip),
            intArrayOf(R.id.day7_name, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_precip)
        )

        private const val PREFS = "widget_location"
        private const val DEFAULT_LAT = 42.3356
        private const val DEFAULT_LON = -88.2590

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            layoutId: Int
        ) {
            withLocation(context) { lat, lon ->
                fetchAndUpdate(context, appWidgetManager, appWidgetId, layoutId, lat, lon)
            }
        }

        /** Remembers a location fix so widgets can use it while the app is in the background. */
        fun saveLocation(context: Context, location: Location) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("lat", location.latitude.toFloat())
                .putFloat("lon", location.longitude.toFloat())
                .apply()
        }

        /**
         * Background widget updates usually can't get a fresh fix on Android 10+, so fall back to
         * the location saved by the app, then to the default.
         */
        fun withLocation(context: Context, callback: (lat: Double, lon: Double) -> Unit) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fallback = {
                if (prefs.contains("lat")) {
                    callback(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
                } else {
                    callback(DEFAULT_LAT, DEFAULT_LON)
                }
            }
            try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            saveLocation(context, location)
                            callback(location.latitude, location.longitude)
                        } else {
                            fallback()
                        }
                    }
                    .addOnFailureListener { fallback() }
            } catch (e: SecurityException) {
                fallback()
            }
        }

        private fun fetchAndUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            layoutId: Int,
            lat: Double,
            lon: Double
        ) {
            val executor = Executors.newSingleThreadExecutor()
            val handler = Handler(Looper.getMainLooper())

            executor.execute {
                try {
                    val url = "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                            "weather_code,wind_speed_10m,uv_index,is_day" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                            "precipitation_probability_max" +
                            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=7"

                    val response = URL(url).readText()
                    val json = JSONObject(response)
                    val current = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")

                    val temp = current.getDouble("temperature_2m").roundToInt()
                    val feelsLike = current.getDouble("apparent_temperature").roundToInt()
                    val humidity = current.getInt("relative_humidity_2m")
                    val windSpeed = current.getDouble("wind_speed_10m").roundToInt()
                    val weatherCode = current.getInt("weather_code")
                    val uvIndex = current.optDouble("uv_index", 0.0).roundToInt()
                    val isDay = current.optInt("is_day", 1) == 1

                    val condition = getWeatherCondition(weatherCode)
                    val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

                    // Parse 7-day forecast arrays
                    val dates = daily.getJSONArray("time")
                    val codes = daily.getJSONArray("weather_code")
                    val highs = daily.getJSONArray("temperature_2m_max")
                    val lows = daily.getJSONArray("temperature_2m_min")
                    val precips = daily.getJSONArray("precipitation_probability_max")

                    // Build day name from date string e.g. "2026-02-20"
                    val dayNames = (0 until 7).map { i ->
                        val dateStr = dates.getString(i)
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val date = sdf.parse(dateStr)
                        if (i == 0) "Today"
                        else SimpleDateFormat("EEE", Locale.getDefault()).format(date!!)
                    }
                    val todayHigh = highs.getDouble(0).roundToInt()
                    val todayLow = lows.getDouble(0).roundToInt()

                    handler.post {
                        val views = baseViews(context, layoutId)

                        // Shared by every size
                        views.setImageViewResource(R.id.widget_icon, getWeatherIcon(weatherCode, isDay))
                        views.setContentDescription(R.id.widget_icon, condition)
                        views.setTextViewText(R.id.widget_temp, "$temp°")
                        views.setTextViewText(R.id.widget_condition, condition)
                        views.setTextViewText(R.id.widget_hilo, "H $todayHigh°  L $todayLow°")

                        // Medium and large: stat chips
                        if (layoutId != R.layout.widget_small) {
                            views.setTextViewText(R.id.widget_wind, "$windSpeed mph")
                            views.setTextViewText(R.id.widget_humidity, "$humidity%")
                            views.setTextViewText(R.id.widget_uv, "UV $uvIndex")
                        }

                        // Large: feels like, updated time and 7-day forecast
                        if (layoutId == R.layout.widget_large) {
                            views.setTextViewText(R.id.widget_feels_like, "Feels like $feelsLike°")
                            views.setTextViewText(R.id.widget_updated, "Updated $timeStr")

                            for (i in 0 until 7) {
                                val (nameId, iconId, highId, lowId, precipId) = DAY_IDS[i]
                                val high = highs.getDouble(i).roundToInt()
                                val low = lows.getDouble(i).roundToInt()
                                val precip = precips.optInt(i, 0)
                                val dayCode = codes.getInt(i)

                                views.setTextViewText(nameId, dayNames[i])
                                views.setImageViewResource(iconId, getWeatherIcon(dayCode, true))
                                views.setContentDescription(iconId, getWeatherCondition(dayCode))
                                views.setTextViewText(highId, "$high°")
                                views.setTextViewText(lowId, "$low°")
                                views.setTextViewText(precipId, "$precip%")
                            }
                        }

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                } catch (e: Exception) {
                    handler.post {
                        val views = baseViews(context, layoutId)
                        views.setTextViewText(R.id.widget_temp, "--°")
                        views.setTextViewText(R.id.widget_condition, "Can't load weather")
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            }
        }

        // Tapping anywhere on the widget opens the app.
        fun baseViews(context: Context, layoutId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, layoutId)
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(android.R.id.background, openApp)
            return views
        }

        private fun getWeatherIcon(code: Int, isDay: Boolean): Int {
            return when (code) {
                0 -> if (isDay) R.drawable.ic_w_clear_day else R.drawable.ic_w_clear_night
                1, 2 -> if (isDay) R.drawable.ic_w_partly_day else R.drawable.ic_w_partly_night
                3 -> R.drawable.ic_w_cloudy
                45, 48 -> R.drawable.ic_w_fog
                51, 53, 55 -> R.drawable.ic_w_drizzle
                56, 57, 66, 67 -> R.drawable.ic_w_freezing_rain
                61, 63, 65, 80, 81, 82 -> R.drawable.ic_w_rain
                71, 73, 75, 77, 85, 86 -> R.drawable.ic_w_snow
                95, 96, 99 -> R.drawable.ic_w_thunder
                else -> R.drawable.ic_w_cloudy
            }
        }

        private fun getWeatherCondition(code: Int): String {
            return when (code) {
                0 -> "Clear Sky"
                1 -> "Mostly Clear"
                2 -> "Partly Cloudy"
                3 -> "Overcast"
                45, 48 -> "Foggy"
                51, 53, 55 -> "Drizzle"
                56, 57 -> "Freezing Drizzle"
                61, 63, 65 -> "Rain"
                66, 67 -> "Freezing Rain"
                71, 73, 75 -> "Snow"
                77 -> "Snow Grains"
                80, 81, 82 -> "Rain Showers"
                85, 86 -> "Snow Showers"
                95 -> "Thunderstorm"
                96, 99 -> "Thunderstorm w/ Hail"
                else -> "Unknown"
            }
        }
    }
}
