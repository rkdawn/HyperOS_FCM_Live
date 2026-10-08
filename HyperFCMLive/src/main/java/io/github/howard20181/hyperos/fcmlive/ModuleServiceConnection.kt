package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** libxposed 的监听只注册一次；服务跨 Activity 重建复用，页面销毁时只取消自己的订阅。 */
internal object ModuleServiceConnection {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var current: XposedService? = null
    private val listeners = linkedSetOf<(XposedService?) -> Unit>()

    fun subscribe(context: Context, listener: (XposedService?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners += listener
        if (!registered) {
            registered = true
            val app = context.applicationContext
            try {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        main.post {
                            current = service
                            val prefs = runCatching { service.getRemotePreferences(Prefs.GROUP_CONFIG) }.getOrNull()
                            Prefs.setRemote(prefs)
                            Prefs.syncPending(app, prefs)
                            listeners.toList().forEach { it(service) }
                        }
                    }
                    override fun onServiceDied(service: XposedService) {
                        main.post {
                            if (current === service) {
                                current = null
                                Prefs.setRemote(null)
                                listeners.toList().forEach { it(null) }
                            }
                        }
                    }
                })
            } catch (_: Exception) { registered = false }
        }
        current?.let {
            Prefs.syncPending(context.applicationContext, Prefs.remote())
            listener(it)
        }
    }

    fun unsubscribe(listener: (XposedService?) -> Unit) {
        listeners -= listener
    }
}
