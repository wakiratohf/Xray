package io.github.saeeddev94.xray

import android.annotation.SuppressLint
import android.content.Context
import com.utility.DebugLog
import com.utility.SharedPreference
import java.io.File
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class XrayDataHelper(val context: Context) {
    private val isDownloading = AtomicBoolean(false)

    companion object {
        private const val GEO_IP_ADDRESS = "geo_ip_address"
        private const val GEO_SITE_ADDRESS = "geo_site_address"

        private const val DEFAULT_GEO_SITE_ADDRESS = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        private const val DEFAULT_GEO_IP_ADDRESS = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"

        fun setGeoIpAddress(context: Context, url: String) {
            SharedPreference.setString(context, GEO_IP_ADDRESS, url)
        }

        fun setGeoSiteAddress(context: Context, url: String) {
            SharedPreference.setString(context, GEO_SITE_ADDRESS, url)
        }
    }

    fun getGeoIpAddress(context: Context): String {
        return SharedPreference.getString(context, GEO_IP_ADDRESS, DEFAULT_GEO_IP_ADDRESS)
    }

    fun getGeoSiteAddress(context: Context): String {
        return SharedPreference.getString(context, GEO_SITE_ADDRESS, DEFAULT_GEO_SITE_ADDRESS)
    }

    private fun geoIpFile(): File = File(context.filesDir, "geoip.dat")
    private fun geoSiteFile(): File = File(context.filesDir, "geosite.dat")


    @SuppressLint("LogNotTimber")
    fun downloadGeoData(successCallback: (() -> Unit)? = null, failCallback: (() -> Unit)? = null) {
        if (isDownloading.getAndSet(true)) {
            DebugLog.logd("Download already in progress")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val geoIPFileExist = geoIpFile().exists()
            val getSiteExist = geoSiteFile().exists()
            if (getSiteExist && geoIPFileExist) {
                withContext(Dispatchers.Main) {
                    DebugLog.logd("Success")
                    successCallback?.invoke()
                }
                return@launch
            }
            try {
                val geoIpJob = async {
                    if (!geoIPFileExist) {
                        DebugLog.logd("Downloading geoip.dat")
                        downloadFile(getGeoIpAddress(context), geoIpFile())
                        DebugLog.logd("geoip.dat downloaded")
                    }
                }

                val geoSiteJob = async {
                    if (!getSiteExist) {
                        DebugLog.logd("Downloading geosite.dat")
                        downloadFile(getGeoSiteAddress(context), geoSiteFile())
                        DebugLog.logd("geosite.dat downloaded")
                    }
                }

                geoIpJob.await()
                geoSiteJob.await()

                withContext(Dispatchers.Main) {
                    DebugLog.logd("Success")
                    successCallback?.invoke()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    DebugLog.logd("Fail: ${e.message}")
                    failCallback?.invoke()
                }
            } finally {
                isDownloading.set(false)
            }
        }
    }

    private fun downloadFile(url: String, file: File) {
        URL(url).openStream().use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}