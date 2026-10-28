package com.example.stormview

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val NOTIFICATION_PERMISSION_REQUEST = 1002
    private val ALERTS_CHANNEL_ID = "weather_alerts"
    private val APP_ORIGIN = "https://appassets.androidplatform.net"
    private var pageLoaded = false
    private var nextNotificationId = 1

    // WebView has no Web Notification API, so the web app's `new Notification(...)` and
    // `Notification.requestPermission()` calls are routed to this bridge by NOTIFICATION_SHIM.
    private val NOTIFICATION_SHIM = """
        (function () {
          if (!window.StormViewNative || window.Notification) return;
          var pending = [];
          function N(title, options) {
            options = options || {};
            StormViewNative.showNotification(String(title), String(options.body || ''));
          }
          Object.defineProperty(N, 'permission', {
            get: function () { return StormViewNative.notificationPermission(); }
          });
          N.requestPermission = function (cb) {
            return new Promise(function (resolve) {
              pending.push(function (state) { if (cb) cb(state); resolve(state); });
              StormViewNative.requestNotificationPermission();
            });
          };
          window.__stormViewNotificationResult = function (state) {
            var cbs = pending; pending = [];
            cbs.forEach(function (fn) { fn(state); });
          };
          window.Notification = N;
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createAlertsChannel()

        webView = findViewById(R.id.webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.setGeolocationEnabled(true)

        webView.addJavascriptInterface(NotificationBridge(), "StormViewNative")
        val documentStartShim = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartShim) {
            WebViewCompat.addDocumentStartJavaScript(webView, NOTIFICATION_SHIM, setOf(APP_ORIGIN))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!documentStartShim && url?.startsWith(APP_ORIGIN) == true) {
                    view?.evaluateJavascript(NOTIFICATION_SHIM, null)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        requestStartupPermissions()
    }

    private fun requestStartupPermissions() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needsNotificationPermission()) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            loadPage()
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

    private fun notificationPermissionState(): String = when {
        NotificationManagerCompat.from(this).areNotificationsEnabled() -> "granted"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "default"
        else -> "denied"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> loadPage()
            NOTIFICATION_PERMISSION_REQUEST -> webView.evaluateJavascript(
                "window.__stormViewNotificationResult && window.__stormViewNotificationResult('${notificationPermissionState()}')",
                null
            )
        }
    }

    private fun loadPage() {
        if (pageLoaded) return
        pageLoaded = true
        webView.loadUrl("$APP_ORIGIN/assets/www/index.html")
    }

    private fun createAlertsChannel() {
        val channel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Weather alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "NWS warnings and air quality alerts for your location"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private inner class NotificationBridge {
        @JavascriptInterface
        fun notificationPermission(): String = notificationPermissionState()

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread {
                if (needsNotificationPermission()) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST
                    )
                } else {
                    onRequestPermissionsResult(NOTIFICATION_PERMISSION_REQUEST, emptyArray(), IntArray(0))
                }
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            if (!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) return
            val openApp = PendingIntent.getActivity(
                this@MainActivity,
                0,
                Intent(this@MainActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this@MainActivity, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(this@MainActivity)
                    .notify(nextNotificationId++, notification)
            } catch (e: SecurityException) {
                // Permission revoked between the check and the post.
            }
        }
    }
}
