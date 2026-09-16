package lab.arl.target

import android.app.Application
import lab.arl.target.di.AppContainer

class TargetApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
