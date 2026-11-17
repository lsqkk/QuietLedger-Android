package dev.quietledger.app

import android.app.Application
import dev.quietledger.app.service.Digest
import dev.quietledger.app.service.Notifications
import dev.quietledger.app.service.WatchState
import dev.quietledger.app.sync.SyncScheduler
import dev.quietledger.app.widget.InvoiceWidgetProvider

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        Digest.schedule(this)
        SyncScheduler.ensurePeriodic(this)
        // 组件刷新要读账本，放到后台线程，避免冷启动主线程被 DB/AppWidget 拖住。
        Thread {
            try {
                InvoiceWidgetProvider.update(this)
            } catch (_: Exception) {
            }
        }.start()
        // 系统更新/重装后通知监听可能处于未绑定状态，主动请求重绑一次。
        if (WatchState.isEnabled(this)) WatchState.reconnect(this)
    }
}
