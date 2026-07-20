package com.odinga.spotune

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App: Application() {
    @Inject
    lateinit var databaseDao: DatabaseDao

    override fun onCreate() {
        super.onCreate()
        SharedDependencies.initializeDao(databaseDao)

        ErrorReporter.initialize(this)
    }
}