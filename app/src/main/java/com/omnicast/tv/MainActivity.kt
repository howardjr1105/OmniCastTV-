package com.omnicast.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Actividad 100% Nativa para Google TV / Android TV (CERO WebView).
 * Renderiza directamente sobre SurfaceView por Hardware (MediaCodec H.264 Low-Latency + ExoPlayer + AirPlay + DLNA).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var exoPlayerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var standbyDashboard: LinearLayout
    private lateinit var statusBadgeText: TextView

    private var h264Decoder: HardwareH264Decoder? = null
    private var audioPlayer: LowLatencyAudioPlayer? = null
    private var exoPlayer: ExoPlayer? = null

    private var dlnaService: DlnaSsdpService? = null
    private var airPlayService: AirPlayBonjourService? = null
    private var streamServer: NativeStreamServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("OmniCastNativeMulticast")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val localIp = getLocalIpAddress()

        // Construir interfaz 100% nativa en Kotlin (Cero WebView)
        buildNativeTvUi(localIp)

        // Inicializar ExoPlayer nativo por hardware
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayerView.player = exoPlayer

        // Inicializar reproductor de audio PCM de baja latencia
        audioPlayer = LowLatencyAudioPlayer()

        // 1. Iniciar Demonio DLNA / UPnP MediaRenderer (Puerto 1900 UDP)
        dlnaService = DlnaSsdpService(localIp, 8080, "OmniCast Google TV").also { it.start() }

        // 2. Iniciar Demonio AirPlay + Bonjour mDNS (_airplay._tcp.local. Puerto 7000)
        airPlayService = AirPlayBonjourService(
            localIp = localIp,
            deviceName = "OmniCast Google TV",
            onPlayUrl = { url -> runOnUiThread { playNativeMedia(url, "video/mp4") } },
            onPhotoBytes = { bytes -> runOnUiThread { showNativePhoto(bytes) } },
            onStop = { runOnUiThread { showStandbyScreen() } }
        ).also { it.start() }

        // 3. Iniciar Servidor Binario Directo a MediaCodec (Puertos 8080 y 8444)
        streamServer = NativeStreamServer(
            context = applicationContext,
            dlnaService = dlnaService!!,
            httpPort = 8080,
            httpsPort = 8444,
            onStartH264Mirror = { w, h, fps ->
                runOnUiThread { startNativeHardwareMirror(w, h, fps) }
            },
            onH264NalPacket = { nal ->
                h264Decoder?.pushNalUnit(nal)
            },
            onPcmAudioPacket = { pcm, offset, len ->
                audioPlayer?.writePcm(pcm, offset, len)
            },
            onPlayMediaUrl = { urlOrPath, mime ->
                runOnUiThread { playNativeMedia(urlOrPath, mime) }
            },
            onStopStream = {
                runOnUiThread { showStandbyScreen() }
            },
            onWebRtcSignalFromSender = {}
        ).also { it.start() }
    }

    private fun buildNativeTvUi(localIp: String) {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#070B14"))
        }

        // 1. SurfaceView nativo directo al Hardware Composer del Google TV (MediaCodec H.264)
        mirrorSurfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    h264Decoder?.stop()
                }
            })
        }
        rootLayout.addView(mirrorSurfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 2. ExoPlayer View nativo (para AirPlay Video, DLNA y Videos 4K locales)
        exoPlayerView = PlayerView(this).apply {
            useController = true
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(exoPlayerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 3. ImageView nativo (para AirPlay Fotos y Galería)
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(photoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 4. Dashboard Nativo de Google TV (Modo Espera)
        standbyDashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#090E1A"), Color.parseColor("#111827"), Color.parseColor("#1E1B4B"))
            )
        }

        // Encabezado
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "OmniCast Native TV · Motor Hardware Directo"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadgeText = TextView(this).apply {
            text = "● ACTIVO: AirPlay + DLNA + MediaCodec H.264 (<5ms)"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1410B981"))
                setStroke(dp(1), Color.parseColor("#34D399"))
                cornerRadius = dp(99).toFloat()
            }
        }
        headerRow.addView(titleText)
        headerRow.addView(statusBadgeText)
        standbyDashboard.addView(headerRow)

        // Tarjetas centrales de protocolos nativos
        val cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(24)
            }
        }

        // Columna Izquierda: Protocolos Nativos sin abrir navegador
        val leftCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dp(16)
            }
        }
        leftCard.addView(createLabel("PROTOCOLOS NATIVOS ACTIVOS EN RED LOCAL (IGUAL QUE AIRSCREEN)", "#38BDF8"))
        leftCard.addView(createTitle("1. AirPlay + Google Cast + DLNA", 20f))
        leftCard.addView(createBody(
            "• Mac / Android (Chrome o Brave): Menú [⋮] -> Guardar y compartir -> Transmitir... -> Elige tu Google TV (Usa el receptor Cast de hardware con 0 lag).\n\n" +
            "• iPhone / iPad / Mac (AirPlay Video y Fotos): Detectado automáticamente como 'OmniCast Google TV' (_airplay._tcp puerto 7000).\n\n" +
            "• Windows / Android / iOS (DLNA / UPnP): Detectado en VLC, Galería y 'Transmitir en dispositivo' de Windows (SSDP puerto 1900)."
        ))

        // Columna Derecha: Motor H.264 Hardware Directo a SurfaceView
        val rightCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(16)
            }
        }
        rightCard.addView(createLabel("DUPLICACIÓN DIRECTA A CHIP MEDIACODEC (SIN WEBVIEW)", "#34D399"))
        rightCard.addView(createTitle("2. Stream Binario H.264 -> SurfaceView", 20f))
        rightCard.addView(createBody(
            "Para duplicar pantalla desde navegador enviando H.264 puro directo al decodificador de hardware del TV:"
        ))
        rightCard.addView(createUrlBox("Enlace Directo HTTP (Puerto 8080):", "http://$localIp:8080", "#34D399"))
        rightCard.addView(createUrlBox("Enlace Seguro HTTPS (Puerto 8444):", "https://$localIp:8444", "#38BDF8"))

        cardsContainer.addView(leftCard)
        cardsContainer.addView(rightCard)
        standbyDashboard.addView(cardsContainer)

        // Pie de página
        val footerText = TextView(this).apply {
            text = "Renderizador: Android SurfaceView + MediaCodec Low-Latency (0 B-Frames) · Presiona [BACK] o [OK] en el control del TV para detener una transmisión"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        standbyDashboard.addView(footerText)

        rootLayout.addView(standbyDashboard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)
    }

    private fun startNativeHardwareMirror(width: Int, height: Int, fps: Int) {
        exoPlayer?.stop()
        exoPlayerView.visibility = View.GONE
        photoView.visibility = View.GONE
        standbyDashboard.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE

        if (surfaceReady) {
            if (h264Decoder == null) {
                h264Decoder = HardwareH264Decoder(mirrorSurfaceView.holder.surface)
            }
            h264Decoder?.start(width, height, fps)
        }
        audioPlayer?.start(48000)
    }

    private fun playNativeMedia(urlOrPath: String, mime: String) {
        h264Decoder?.stop()
        audioPlayer?.stop()
        standbyDashboard.visibility = View.GONE

        if (mime.startsWith("image/")) {
            exoPlayer?.stop()
            exoPlayerView.visibility = View.GONE
            photoView.visibility = View.VISIBLE
            val bmp = BitmapFactory.decodeFile(urlOrPath)
            if (bmp != null) photoView.setImageBitmap(bmp)
        } else {
            photoView.visibility = View.GONE
            exoPlayerView.visibility = View.VISIBLE
            val uri = if (urlOrPath.startsWith("http")) Uri.parse(urlOrPath) else Uri.fromFile(File(urlOrPath))
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
    }

    private fun showNativePhoto(bytes: ByteArray) {
        h264Decoder?.stop()
        exoPlayer?.stop()
        exoPlayerView.visibility = View.GONE
        standbyDashboard.visibility = View.GONE
        photoView.visibility = View.VISIBLE
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) photoView.setImageBitmap(bmp)
    }

    private fun showStandbyScreen() {
        h264Decoder?.stop()
        audioPlayer?.stop()
        exoPlayer?.stop()
        exoPlayerView.visibility = View.GONE
        photoView.visibility = View.GONE
        standbyDashboard.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && standbyDashboard.visibility != View.VISIBLE) {
            showStandbyScreen()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9111827"))
                setStroke(dp(1), Color.parseColor("#1F2937"))
                cornerRadius = dp(20).toFloat()
            }
        }
    }

    private fun createLabel(textStr: String, colorHex: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun createTitle(textStr: String, sizeSp: Float): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
    }

    private fun createBody(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    }

    private fun createUrlBox(label: String, url: String, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#030712"))
                setStroke(dp(1), Color.parseColor("#374151"))
                cornerRadius = dp(12).toFloat()
            }
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            addView(TextView(context).apply {
                text = url
                setTextColor(Color.parseColor(colorHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
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
        return "192.168.0.14"
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
        h264Decoder?.stop()
        audioPlayer?.stop()
        exoPlayer?.release()
        streamServer?.stop()
        airPlayService?.stop()
        dlnaService?.stop()
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        super.onDestroy()
    }
}
