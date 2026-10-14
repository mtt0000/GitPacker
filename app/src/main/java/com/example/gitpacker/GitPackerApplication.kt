package com.example.gitpacker

import android.app.Application

class GitPackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        var instance: GitPackerApplication? = null
            private set
    }
}
