#!/usr/bin/env kotlin

package org.torproject.android.service

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object BridgeDownloader {
    private const val GITHUB_URL = "https://raw.githubusercontent.com/scriptzteam/Tor-Bridges-Collector-v2/refs/heads/main/bridges/vanilla_tested.txt"

    fun fetchAndSaveBridges(context: Context): Boolean {
        val client = OkHttpClient()
        val request = Request.Builder().url(GITHUB_URL).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawText = response.body?.string()
                    if (!rawText.isNullOrBlank()) {
                        val cleanBridges = rawText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .joinToString("\n")

                        val prefs = context.getSharedPreferences("org.torproject.android_preferences", Context.MODE_PRIVATE)
                        prefs.edit().putString("pref_custom_bridges", cleanBridges).apply()
                        true
                    } else false
                } else false
            }
        } catch (e: IOException) {
            false
        }
    }
}
