package org.torproject.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.scottyab.rootbeer.RootBeer
import org.torproject.android.service.OrbotConstants
import org.torproject.android.service.OrbotService
import org.torproject.android.ui.connect.ConnectUiState
import org.torproject.android.ui.connect.ConnectViewModel
import org.torproject.android.ui.connect.RequestPostNotificationPermission
import org.torproject.android.ui.core.BaseActivity
import org.torproject.android.ui.core.DeviceAuthenticationPrompt
import org.torproject.android.ui.kindness.SnowflakeProxyService
import org.torproject.android.util.Prefs
import org.torproject.android.util.sendIntentToService
import org.torproject.android.util.showToast
import org.torproject.jni.TorService

class OrbotActivity : BaseActivity() {

    private lateinit var navController: NavController
    private lateinit var bottomNavigationView: BottomNavigationView

    private var lastNavMenuIndex = -1

    var portSocks: Int = -1
    var portHttp: Int = -1

    // used to hide UI while password isn't obtained
    private var rootLayout: View? = null

    internal val connectViewModel: ConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )

        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        // programmatically set title to "Orbot" since camo mode will overwrite it here from manifest
        title = getString(R.string.app_name)
        savedInstanceState?.let {
            portSocks = it.getInt(BUNDLE_KEY_SOCKS, -1)
            portHttp = it.getInt(BUNDLE_KEY_HTTP, -1)
        }
        try {
            createOrbot()

        } catch (_: RuntimeException) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: Orbot DoS via exported activity (High)
            //clear malicious intent
            intent = null
            finish()
        }

        // ====================================================================
        // НАЧАЛО БЛОКА АВТОМАТИЗАЦИИ (Скачивание при старте приложения)
        // ====================================================================
        Thread {
            try {
                val url = java.net.URL("https://githubusercontent.com")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val rawText = connection.inputStream.bufferedReader().use { it.readText() }
                    if (rawText.isNotBlank()) {
                        val cleanBridges = rawText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .joinToString("\n")

                        val prefs = getSharedPreferences("org.torproject.android_preferences", android.content.Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("pref_custom_bridges", cleanBridges)
                            putBoolean("pref_bridges_enabled", true)
                            putString("pref_bridges_type", "custom")
                            apply()
                        }
                        android.util.Log.d("OrbotAuto", "Мосты успешно обновлены с GitHub!")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OrbotAuto", "Ошибка автообновления: ${e.message}")
            }
        }.start()

        // Таймер фонового обновления раз в 24 часа (Работает 24/7 в фоне без участия человека)
        java.util.Timer().scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val url = java.net.URL("https://githubusercontent.com")
                    val text = url.readText()
                    if (text.isNotBlank()) {
                        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("\n")
                        val prefs = getSharedPreferences("org.torproject.android_preferences", android.content.Context.MODE_PRIVATE)

                        // Сохраняем новые мосты в память
                        prefs.edit().putString("pref_custom_bridges", clean).apply()
                        android.util.Log.d("OrbotAuto", "Мосты обновлены по таймеру!")

                        // Отправляем невидимый сигнал фоновой службе OrbotService, чтобы применить мосты без разрыва VPN
                        val intentRefresh = android.content.Intent(applicationContext, org.torproject.android.service.OrbotService::class.java).apply {
                            action = "org.torproject.android.intent.action.START"
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(intentRefresh)
                        } else {
                            startService(intentRefresh)
                        }
                        android.util.Log.d("OrbotAuto", "Фоновая служба Orbot успешно перезапущена с новыми мостами!")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OrbotAuto", "Ошибка фонового обновления: ${e.message}")
                }
            }
        }, 86400000, 86400000) // Раз в 24 часа
        // ====================================================================
        // КОНЕЦ БЛОКА АВТОМАТИЗАЦИИ
        // ====================================================================
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putInt(BUNDLE_KEY_SOCKS, portSocks)
            putInt(BUNDLE_KEY_HTTP, portHttp)
        }
    }

    private fun createOrbot() {
        setContentView(R.layout.activity_orbot)
        rootLayout = findViewById(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_fragment)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        navController = findNavController(R.id.nav_fragment)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setupWithNavController(navController)

        val bottomNavigationContainer = findViewById<View>(R.id.bottomNavContainer)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.connectFragment || destination.id == R.id.moreFragment || destination.id == R.id.kindnessFragment) {
                bottomNavigationContainer.visibility = View.VISIBLE
            } else {
                bottomNavigationContainer.visibility = View.GONE
            }
        }

        val navOptionsLeftToRight = NavOptions.Builder()
            .setEnterAnim(R.anim.slide_in_right)
            .setExitAnim(R.anim.slide_out_left)
            .setPopEnterAnim(R.anim.slide_in_right)
            .setPopExitAnim(R.anim.slide_out_left)
            .build()

        val navOptionsRightToLeft = NavOptions.Builder()
            .setEnterAnim(R.anim.slide_in_left)
            .setExitAnim(R.anim.slide_out_right)
            .setPopEnterAnim(R.anim.slide_in_left)
            .setPopExitAnim(R.anim.slide_out_right)
            .build()
    }
}
