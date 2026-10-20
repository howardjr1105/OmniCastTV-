package com.omnicast.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var signalingServer: LanSignalingServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantener la pantalla del Google TV siempre encendida y en modo inmersivo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // 1. Adquirir MulticastLock para descubrimiento en red local
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("OmniCastMulticastLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        // 2. Iniciar el servidor embebido HTTP (8080) y HTTPS (8443)
        signalingServer = LanSignalingServer(applicationContext, 8080, 8443).also {
            it.start()
        }

        // 3. Anunciar el servicio por mDNS / Bonjour (_http._tcp.)
        registerMdnsService(8080)

        // 4. Configurar el WebView acelerado por Hardware (MediaCodec directo)
        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // Aceptar el certificado autofirmado local del propio TV
                    handler?.proceed()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }
        setContentView(webView)

        val localIp = getLocalIpAddress()
        // Cargamos en 127.0.0.1:8080 pasando la IP real de Wi-Fi/Ethernet para generar el QR
        webView.loadUrl("http://127.0.0.1:8080/?mode=tv&ip=$localIp")
    }

    // Soporte completo para el control remoto de Google TV / Android TV (D-Pad)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('OK');", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('PLAY_PAUSE');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('UP');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('DOWN');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('LEFT');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                webView.evaluateJavascript("window.onTvRemoteKey && window.onTvRemoteKey('RIGHT');", null)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.1.100"
    }

    private fun registerMdnsService(port: Int) {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "OmniCast-TV"
                serviceType = "_http._tcp."
                setPort(port)
            }
            nsdManager = (getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { manager ->
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo?) {}
                    override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo?) {}
                    override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                }
                manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            }
        } catch (_: Exception) {}
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onDestroy() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        signalingServer?.stop()
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        super.onDestroy()
    }
}
