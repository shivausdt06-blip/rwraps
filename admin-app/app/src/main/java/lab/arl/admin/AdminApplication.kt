package lab.arl.admin

import android.app.Application
import lab.arl.admin.di.AppContainer

class AdminApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
