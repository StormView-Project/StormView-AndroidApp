package com.example.stormview

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** Map of the area around you with the latest LibreWXR radar frame on top. */
class RadarWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdateWorker.refreshNow(context)
    }

    // Re-render at the new aspect ratio when the widget is resized.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetUpdateWorker.refreshNow(context)
    }

    companion object {
        private const val ZOOM = 8
        private const val TILE = 512
        private const val MAX_SIDE = 1024
        // Map pixels per dp: @2x tiles drawn at ~1:1 on a typical screen.
        private const val PX_PER_DP = 2

        // StormView's default radar settings: Rainbow @ Selex SI, smoothed, snow shown, 75% opacity.
        private const val RADAR_SCHEME = 7
        private const val RADAR_ALPHA = 191
        private const val LIBREWXR_API = "https://api.librewxr.net/public/weather-maps.json"
        private const val LIBREWXR_HOST = "https://api.librewxr.net"
        private const val USER_AGENT = "StormView-Android (https://github.com/arc360alt/StormView-AndroidApp)"

        private val tilePool = Executors.newFixedThreadPool(6)

        /** Renders one radar widget; blocks on the network, so call it off the main thread. */
        fun update(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            lat: Double,
            lon: Double
        ) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            // Portrait home screens use min width x max height.
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180

            try {
                val isDark = (context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                // Latest observed radar frame (LibreWXR mirrors the RainViewer v2 API).
                val frames = JSONObject(fetchText(LIBREWXR_API))
                    .getJSONObject("radar").getJSONArray("past")
                val frame = frames.getJSONObject(frames.length() - 1)
                val frameTime = frame.getLong("time")
                val radar = TileLayer(
                    "$LIBREWXR_HOST${frame.getString("path")}/$TILE/{z}/{x}/{y}/$RADAR_SCHEME/1_1.png",
                    ZOOM, TILE
                )
                val key = BuildConfig.MAPTILER_KEY
                // OSM's 256px tiles one zoom level deeper cover the same area as 512px ones at ZOOM.
                val base = if (key.isNotEmpty()) TileLayer(
                    "https://api.maptiler.com/maps/${if (isDark) "dataviz-dark" else "dataviz"}/256/{z}/{x}/{y}@2x.png?key=$key",
                    ZOOM, TILE
                ) else TileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", ZOOM + 1, TILE / 2)
                val attribution =
                    if (key.isNotEmpty()) "© MapTiler © OpenStreetMap · LibreWXR"
                    else "© OpenStreetMap · LibreWXR"

                val width = (widthDp * PX_PER_DP).coerceIn(64, MAX_SIDE)
                val height = (heightDp * PX_PER_DP).coerceIn(64, MAX_SIDE)
                val bitmap = drawMap(context, lat, lon, width, height, base, radar)

                val place = placeName(context, lat, lon)
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(frameTime * 1000))

                val views = WeatherWidget.baseViews(context, R.layout.widget_radar)
                views.setImageViewBitmap(R.id.radar_map, bitmap)
                views.setViewVisibility(R.id.radar_status, View.GONE)
                views.setTextViewText(R.id.radar_place, place)
                views.setTextViewText(R.id.radar_time, "Radar · $timeStr")
                views.setTextViewText(R.id.radar_attribution, attribution)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                // Keep the last map on screen; only flag that this refresh failed.
                val views = RemoteViews(context.packageName, R.layout.widget_radar)
                views.setTextViewText(R.id.radar_status, "Can't load radar")
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
        }

        /** A slippy-map tile source; every layer must share the same world size (tileSize * 2^zoom). */
        private class TileLayer(val template: String, val zoom: Int, val tileSize: Int)

        /** Stitches base map and radar tiles centred on the location, then marks the location. */
        private fun drawMap(
            context: Context,
            lat: Double,
            lon: Double,
            width: Int,
            height: Int,
            base: TileLayer,
            radar: TileLayer
        ): Bitmap {
            // Web Mercator pixel position of the location at ZOOM.
            val worldSize = TILE * (1 shl ZOOM).toDouble()
            val latRad = lat * PI / 180
            val cx = (lon + 180) / 360 * worldSize
            val cy = (1 - ln(tan(latRad) + 1 / kotlin.math.cos(latRad)) / PI) / 2 * worldSize
            val left = cx - width / 2.0
            val top = cy - height / 2.0

            // Fetch every tile of a layer overlapping the view, paired with where it lands.
            fun fetchAll(layer: TileLayer): List<Pair<RectF, Future<Bitmap?>>> {
                val t = layer.tileSize
                val count = 1 shl layer.zoom
                val xs = floor(left / t).toInt()..floor((left + width - 1) / t).toInt()
                val ys = (floor(top / t).toInt()..floor((top + height - 1) / t).toInt()).filter { it in 0 until count }
                return xs.flatMap { x ->
                    ys.map { y ->
                        val url = layer.template.replace("{z}", "${layer.zoom}")
                            .replace("{x}", "${Math.floorMod(x, count)}").replace("{y}", "$y")
                        val dx = (x * t - left).toFloat()
                        val dy = (y * t - top).toFloat()
                        RectF(dx, dy, dx + t, dy + t) to
                                tilePool.submit(Callable { runCatching { fetchBitmap(url) }.getOrNull() })
                    }
                }
            }
            val baseTiles = fetchAll(base)
            val radarTiles = fetchAll(radar)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Round the corners ourselves so the map is clipped before Android 12 too.
            val radiusPx = context.resources.getDimension(R.dimen.widget_radius) /
                    context.resources.displayMetrics.density * PX_PER_DP
            canvas.clipPath(Path().apply {
                addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, Path.Direction.CW)
            })
            canvas.drawColor(ContextCompat.getColor(context, R.color.widget_surface_inner))

            val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = RADAR_ALPHA }
            val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((dst, tile) in baseTiles) tile.get()?.let { canvas.drawBitmap(it, null, dst, basePaint) }
            for ((dst, tile) in radarTiles) tile.get()?.let { canvas.drawBitmap(it, null, dst, radarPaint) }

            // Location marker: primary dot with a white ring and soft halo.
            val px = width / 2f
            val py = height / 2f
            val primary = ContextCompat.getColor(context, R.color.widget_primary)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = primary
            paint.alpha = 60
            canvas.drawCircle(px, py, 26f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(px, py, 13f, paint)
            paint.color = primary
            canvas.drawCircle(px, py, 9f, paint)

            return bitmap
        }

        /** City name via Nominatim, cached per ~1 km so we don't re-query on every refresh. */
        private fun placeName(context: Context, lat: Double, lon: Double): String {
            val prefs = context.getSharedPreferences("radar_widget", Context.MODE_PRIVATE)
            val key = "%.2f,%.2f".format(Locale.US, lat, lon)
            prefs.getString("place_$key", null)?.let { return it }
            return try {
                val json = JSONObject(
                    fetchText("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=10&accept-language=en-US,en")
                )
                val address = json.optJSONObject("address")
                val name = listOf("city", "town", "village", "hamlet", "municipality", "county")
                    .map { address?.optString(it).orEmpty() }
                    .firstOrNull { it.isNotEmpty() } ?: "My Location"
                prefs.edit().putString("place_$key", name).apply()
                name
            } catch (e: Exception) {
                "My Location"
            }
        }

        private fun open(url: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
            }

        private fun fetchText(url: String): String {
            val conn = open(url)
            try {
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        private fun fetchBitmap(url: String): Bitmap? {
            val conn = open(url)
            try {
                if (conn.responseCode != 200) return null
                return conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        }
    }
}
