package app.stringalize.example

import android.app.Application
import app.stringalize.Stringalize

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Stringalize.start(this, token = BuildConfig.STRINGALIZE_TOKEN, server = BuildConfig.STRINGALIZE_SERVER)
    }
}
