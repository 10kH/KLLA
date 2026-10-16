package com.woolab.klla.netsetup

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log

/**
 * One Wi-Fi operation per launch, from adb:
 *
 *   am start -n com.woolab.klla.netsetup/.WifiActivity --es op add --es ssid NAME --es psk PASS
 *   am start -n ... --es op add --es ssid NAME --ez hidden true --es identity ID --es password PW   (WPA2-EAP, PEAP/MSCHAPv2)
 *   am start -n ... --es op add --es ssid NAME                     (open network)
 *   am start -n ... --es op remove --ei id NETWORK_ID              (only networks this app added)
 *   am start -n ... --es op wifi-on
 *
 * Results go to logcat under the tag KllaNet. Passwords are never logged.
 */
class WifiActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = runCatching { handle(intent) }
            .getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
        Log.i(TAG, result)
        finish()
    }

    // The deprecated WifiManager calls are the whole point: they still work for targetSdk 28.
    @Suppress("DEPRECATION")
    private fun handle(intent: Intent): String {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        return when (val op = intent.getStringExtra("op") ?: "add") {
            "wifi-on" -> "wifi-on ok=${wifi.setWifiEnabled(true)}"
            "remove" -> {
                val id = intent.getIntExtra("id", -1)
                "remove id=$id ok=${wifi.removeNetwork(id)}"
            }
            "add" -> {
                val ssid = requireNotNull(intent.getStringExtra("ssid")) { "missing --es ssid" }
                val psk = intent.getStringExtra("psk")
                val identity = intent.getStringExtra("identity")
                val config = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    hiddenSSID = intent.getBooleanExtra("hidden", false)
                    when {
                        identity != null -> {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                            enterpriseConfig = WifiEnterpriseConfig().apply {
                                eapMethod = WifiEnterpriseConfig.Eap.PEAP
                                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2
                                this.identity = identity
                                password = intent.getStringExtra("password") ?: ""
                            }
                        }
                        psk != null -> preSharedKey = "\"$psk\""
                        else -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val kind = if (identity != null) "eap" else if (psk != null) "psk" else "open"
                val id = wifi.addNetwork(config)
                val connect = intent.getBooleanExtra("connect", true)
                val enabled = if (id >= 0 && connect) wifi.enableNetwork(id, true) else false
                "add ssid=$ssid kind=$kind hidden=${config.hiddenSSID} id=$id enabled=$enabled"
            }
            else -> "unknown op=$op"
        }
    }

    private companion object {
        const val TAG = "KllaNet"
    }
}
