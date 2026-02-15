package com.parseable.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class ParseableApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        installUncaughtExceptionHandler()
    }

    private fun installUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception on thread ${thread.name}")
            // Delegate to the default handler so the system can still terminate
            // the process for truly fatal errors (e.g. OOM).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
