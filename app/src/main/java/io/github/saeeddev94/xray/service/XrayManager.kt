package io.github.saeeddev94.xray.service

import XrayCore.XrayCore
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.Tile
import android.util.Log
import com.tohsoft.vpn.services.AppService
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.Xray
import io.github.saeeddev94.xray.database.Config
import io.github.saeeddev94.xray.database.Profile
import io.github.saeeddev94.xray.dto.XrayConfig
import io.github.saeeddev94.xray.helper.ConfigHelper
import io.github.saeeddev94.xray.helper.FileHelper
import io.github.saeeddev94.xray.helper.TransparentProxyHelper
import java.io.File
import kotlin.reflect.cast

@SuppressLint("VpnServicePolicy")
class XrayManager(private val service: AppService) {

    interface VpnServiceListener {
        fun getService(): AppService
        fun showToast(message: String)
        fun createNotification(name: String): Notification
        fun startForeground(id: Int, notification: Notification)
        fun stopForeground(remove: Boolean)
        fun stopSelf()
        fun updateTile(label: String, state: Int)
        fun broadcastStart(action: String, configName: String)
        fun broadcastStop()
        fun broadcastStatus()
    }

    private val context: Context = service.applicationContext
    private val connectivityManager by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val settings by lazy { Settings(context) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(service, settings) }
    private val configRepository by lazy { Xray::class.cast(service.application).configRepository }
    private val profileRepository by lazy { Xray::class.cast(service.application).profileRepository }

    private var isRunning: Boolean = false
    private var tunDevice: ParcelFileDescriptor? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null


    fun start(profile: Profile?, globalConfigs: Config) {
        if (profile == null) return
        getConfig(profile, globalConfigs)?.let {
            startXray(it)
            startVPN(profile)
        }
    }

    fun newConfig(profile: Profile?, globalConfigs: Config) {
        if (!getIsRunning() || profile == null) return
        stopXray()
        getConfig(profile, globalConfigs).also {
            if (it == null) stopVPN() else startXray(it)
        }?.let {
            val name = configName(profile)
            val notification = service.createNotification(name)
            service.showToast(name)
            service.broadcastStart(AppService.NEW_CONFIG_SERVICE_ACTION_NAME, name)
            service.updateTile(name, Tile.STATE_ACTIVE)
            service.notificationManager.notify(1, notification)
        }
    }

    fun stopVPN() {
        if (settings.transparentProxy) {
            transparentProxyHelper.disableProxy()
        } else {
            service.TProxyStopService()
            runCatching { tunDevice?.close() }
            tunDevice = null
            isRunning = false
        }
        stopXray()
        service.stopForeground(true)
        service.showToast("Stop VPN")
        service.broadcastStop()
        service.updateTile(service.getString(R.string.vpnStopped), Tile.STATE_INACTIVE)
        service.stopSelf()
    }

    fun networkUpdate() {
        transparentProxyHelper.networkUpdate()
    }

    fun getIsRunning(): Boolean {
        return if (settings.transparentProxy) {
            transparentProxyHelper.isRunning()
        } else {
            isRunning
        }
    }

    suspend fun getProfile(): Profile? {
        return if (settings.selectedProfile == 0L) {
            null
        } else {
            profileRepository.find(settings.selectedProfile)
        }
    }

    suspend fun globalConfigs(): Config {
        return configRepository.get()
    }

    fun onRevoke() {
        stopVPN()
    }

    fun onDestroy() {
        cellularCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        cellularCallback = null
    }

    private fun configName(profile: Profile?): String = profile?.name ?: settings.tunName

    private fun getConfig(profile: Profile, globalConfigs: Config): XrayConfig? {
        val dir: File = context.filesDir
        val config: File = settings.xrayConfig()
        val configHelper = runCatching { ConfigHelper(settings, globalConfigs, profile.config) }
        val error: String = if (configHelper.isSuccess) {
            FileHelper.createOrUpdate(config, configHelper.getOrNull().toString())
            XrayCore.test(dir.absolutePath, config.absolutePath)
        } else {
            configHelper.exceptionOrNull()?.message ?: service.getString(R.string.invalidProfile)
        }
        if (error.isNotEmpty()) {
            service.showToast(error)
            return null
        }
        return XrayConfig(dir.absolutePath, config.absolutePath)
    }

    private fun startXray(config: XrayConfig) {
        if (settings.transparentProxy) transparentProxyHelper.startService()
        else XrayCore.start(config.dir, config.file)
    }

    private fun stopXray() {
        if (settings.transparentProxy) transparentProxyHelper.stopService()
        else XrayCore.stop()
    }

    private fun startVPN(profile: Profile?) {
        if (settings.transparentProxy) {
            transparentProxyHelper.enableProxy()
            transparentProxyHelper.monitorNetwork()
        } else if (settings.tun2socks) {
            val tun = service.Builder()
            val tunName = service.getString(R.string.appName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tun.setMetered(false)
            tun.setMtu(settings.tunMtu)
            tun.setSession(tunName)

            tun.addAddress(settings.tunAddress, settings.tunPrefix)
            tun.addDnsServer(settings.primaryDns)
            tun.addDnsServer(settings.secondaryDns)

            if (settings.enableIpV6) {
                tun.addAddress(settings.tunAddressV6, settings.tunPrefixV6)
                tun.addDnsServer(settings.primaryDnsV6)
                tun.addDnsServer(settings.secondaryDnsV6)
                tun.addRoute("::", 0)
            }

            if (settings.bypassLan) {
                settings.tunRoutes.forEach {
                    val address = it.split('/')
                    tun.addRoute(address[0], address[1].toInt())
                }
            } else {
                tun.addRoute("0.0.0.0", 0)
            }

            if (settings.appsRoutingMode) tun.addDisallowedApplication(context.packageName)
            settings.appsRouting.split("\n").forEach {
                val packageName = it.trim()
                if (packageName.isBlank()) return@forEach
                if (settings.appsRoutingMode) tun.addDisallowedApplication(packageName)
                else tun.addAllowedApplication(packageName)
            }

            tunDevice = tun.establish()

            if (tunDevice == null) {
                Log.e("VpnManager", "tun#establish failed")
                return
            }

            val tun2socksConfig = arrayListOf(
                "tunnel:",
                "  name: $tunName",
                "  mtu: ${settings.tunMtu}",
                "socks5:",
                "  address: ${settings.socksAddress}",
                "  port: ${settings.socksPort}",
            )
            if (
                settings.socksUsername.trim().isNotEmpty() &&
                settings.socksPassword.trim().isNotEmpty()
            ) {
                tun2socksConfig.add("  username: ${settings.socksUsername}")
                tun2socksConfig.add("  password: ${settings.socksPassword}")
            }
            tun2socksConfig.add(if (settings.socksUdp) "  udp: udp" else "  udp: tcp")
            tun2socksConfig.add("")
            FileHelper.createOrUpdate(
                settings.tun2socksConfig(),
                tun2socksConfig.joinToString("\n")
            )

            service.TProxyStartService(settings.tun2socksConfig().absolutePath, tunDevice!!.fd)
        }

        val name = configName(profile)
        service.startForeground(1, service.createNotification(name))

        if (cellularCallback == null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cellularCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    transparentProxyHelper.networkUpdate()
                }
            }
            connectivityManager.registerNetworkCallback(request, cellularCallback!!)
        }

        service.showToast("Start VPN")
        isRunning = true
        service.broadcastStart(AppService.START_VPN_SERVICE_ACTION_NAME, name)
        service.updateTile(name, Tile.STATE_ACTIVE)
    }
}

