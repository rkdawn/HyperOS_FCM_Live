package io.github.howard20181.hyperos.fcmlive

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.io.File

/** 使用宿主已有权限读取，不申请 Root、不启动 shell、不向外部服务器发探测包。 */
internal class GmsRecoveryReader(private val context: Context) {
    data class NetworkStatus(val key: String?, val state: RecoveryNetworkState)
    data class Snapshot(val network: NetworkStatus, val observations: List<RecoveryObservation>)

    // 运行时检查 ACCESS_NETWORK_STATE；缺失或异常时保留未知，不视为掉线。
    @SuppressLint("MissingPermission")
    fun network(): NetworkStatus {
        return try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                return NetworkStatus(null, RecoveryNetworkState.UNKNOWN)
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val active = manager?.activeNetwork
            if (manager == null) NetworkStatus(null, RecoveryNetworkState.UNKNOWN)
            else if (active == null) NetworkStatus("none", RecoveryNetworkState.OFFLINE)
            else {
                val capabilities = manager.getNetworkCapabilities(active)
                val state = when {
                    capabilities == null -> RecoveryNetworkState.UNKNOWN
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> RecoveryNetworkState.OFFLINE
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> RecoveryNetworkState.OFFLINE
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> RecoveryNetworkState.READY
                    else -> RecoveryNetworkState.UNKNOWN
                }
                NetworkStatus(active.networkHandle.toString(), state)
            }
        } catch (_: Exception) { NetworkStatus(null, RecoveryNetworkState.UNKNOWN) }
    }

    fun read(): Snapshot {
        val network = network()
        val uids = linkedSetOf<Int>()
        runCatching { context.packageManager.getApplicationInfo(DiagnosticsParser.GMS, 0).uid }
            .getOrNull()?.takeIf(DiagnosticsParser::isAppUid)?.let(uids::add)
        val processes = runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
        }.getOrDefault(emptyList())
        for (process in processes) {
            if (!DiagnosticsParser.isAppUid(process.uid) || !DiagnosticsParser.gmsProcessName(process.processName)) continue
            val packages = runCatching { context.packageManager.getPackagesForUid(process.uid) }.getOrNull()
            if (packages?.contains(DiagnosticsParser.GMS) == true) uids += process.uid
        }
        // 不枚举未运行的其他用户，不把相同应用编号本身当成已核实身份。
        val targets = uids.take(32).mapNotNull { uid ->
            val userContext = contextFor(uid) ?: return@mapNotNull null
            val info = runCatching { userContext.packageManager.getApplicationInfo(DiagnosticsParser.GMS, 0) }.getOrNull()
                ?: return@mapNotNull null
            if (info.uid != uid) return@mapNotNull null
            uid to isEligible(userContext, info)
        }
        if (targets.isEmpty()) return Snapshot(network, emptyList())
        val v4 = RecoverySocketSnapshot.readTable { File("/proc/net/tcp").inputStream() }
        val v6 = RecoverySocketSnapshot.readTable { File("/proc/net/tcp6").inputStream() }
        val states = RecoverySocketSnapshot.states(v4, v6, targets.map { it.first }.toSet())
        return Snapshot(network, targets.map { (uid, eligible) ->
            RecoveryObservation(uid, network.key, network.state, states.getValue(uid), eligible)
        })
    }

    /** 发出请求前重新核验用户与应用状态；任何失败都不会回落到主用户。 */
    fun requestReconnect(uid: Int): Boolean {
        val userContext = contextFor(uid) ?: return false
        val info = runCatching { userContext.packageManager.getApplicationInfo(DiagnosticsParser.GMS, 0) }.getOrNull()
            ?: return false
        if (info.uid != uid || !isEligible(userContext, info)) return false
        userContext.sendBroadcast(Intent("com.google.android.intent.action.GCM_RECONNECT").setPackage(DiagnosticsParser.GMS))
        return true
    }

    private fun isEligible(userContext: Context, info: ApplicationInfo): Boolean =
        info.enabled && info.flags and ApplicationInfo.FLAG_STOPPED == 0 &&
            runCatching { userContext.getSystemService(UserManager::class.java)?.isUserUnlocked == true }.getOrDefault(false)

    private fun contextFor(uid: Int): Context? {
        if (!DiagnosticsParser.isAppUid(uid)) return null
        if (uid / 100000 == Process.myUid() / 100000) return context
        return runCatching {
            // 系统宿主的跨用户 Context；使用固定签名，失败时停止，不猜测其他重载。
            Context::class.java.getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, UserHandle.getUserHandleForUid(uid), 0) as? Context
        }.getOrNull()
    }
}
