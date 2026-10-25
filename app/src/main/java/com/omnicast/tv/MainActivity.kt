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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var exoPlayerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var standbyDashboard: LinearLayout
    private lateinit var statusBadgeText: TextView
    private lateinit var liveLogTextView: TextView

    private val logLines = ArrayDeque<String>(8)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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

        buildNativeTvUi(localIp)
        appendTvLog("Motor nativo iniciado en $localIp")

        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayerView.player = exoPlayer

        audioPlayer = LowLatencyAudioPlayer()

        // 1. Iniciar Demonio DLNA / UPnP MediaRenderer (Puerto 1900 UDP)
        dlnaService = DlnaSsdpService(localIp, 8080, "OmniCast Google TV").also { it.start() }

        // 2. Iniciar Demonio AirPlay 2 + FairPlay SAP v2.5 + Bonjour mDNS
        airPlayService = AirPlayBonjourService(
            context = applicationContext,
            localIp = localIp,
            deviceName = "OmniCast Google TV",
            onLogEvent = { msg -> appendTvLog(msg) },
            onStartMirroring = { w, h, fps ->
                startNativeHardwareMirror(w, h, fps)
            },
            onH264Nal = { nal ->
                h264Decoder?.pushNalUnit(nal)
            },
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
                startNativeHardwareMirror(w, h, fps)
                audioPlayer?.start(48000) // solo el emisor web manda PCM 48 kHz; AirPlay usa su propio player
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

    private fun appendTvLog(message: String) {
        val stamp = timeFormat.format(Date())
        runOnUiThread {
            if (logLines.size >= 6) logLines.removeFirst()
            logLines.addLast("[$stamp] $message")
            if (::liveLogTextView.isInitialized) {
                liveLogTextView.text = logLines.joinToString("\n")
            }
        }
    }

    private fun buildNativeTvUi(localIp: String) {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#070B14"))
        }

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

        exoPlayerView = PlayerView(this).apply {
            useController = true
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(exoPlayerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(photoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        standbyDashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(30), dp(44), dp(30))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#090E1A"), Color.parseColor("#111827"), Color.parseColor("#1E1B4B"))
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "OmniCast Native TV v2.4 · AirPlay 2 + FairPlay SAP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadgeText = TextView(this).apply {
            text = "● ACTIVO: AirPlay 2 + FairPlay + DLNA + MediaCodec"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1410B981"))
                setStroke(dp(1), Color.parseColor("#34D399"))
                cornerRadius = dp(99).toFloat()
            }
        }
        headerRow.addView(titleText)
        headerRow.addView(statusBadgeText)
        standbyDashboard.addView(headerRow)

        val cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(14)
            }
        }

        val leftCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dp(12)
            }
        }
        leftCard.addView(createLabel("PROTOCOLOS NATIVOS ACTIVOS (AIRPLAY 2 + CAST + DLNA)", "#38BDF8"))
        leftCard.addView(createTitle("1. Duplicar Pantalla desde Mac / iPhone / Android", 18f))
        leftCard.addView(createBody(
            "• Mac / iPhone / iPad (AirPlay 2): Centro de Control -> Duplicar Pantalla -> 'OmniCast Google TV' (Puertos 7000/5000/7100 + FairPlay SAP v2.5).\n\n" +
            "• Mac / Android (Chrome o Brave): Menú [⋮] -> Guardar y compartir -> Transmitir... -> Elige tu Google TV.\n\n" +
            "• Windows / Android / iOS (DLNA): Detectado en VLC y Galería (SSDP 1900)."
        ))

        val rightCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        rightCard.addView(createLabel("CONSOLA DE DIAGNÓSTICO AIRPLAY EN TIEMPO REAL", "#34D399"))
        rightCard.addView(createTitle("2. Estado de Conexión y Enlaces Directos", 18f))
        rightCard.addView(createUrlBox("HTTP (:8080) / HTTPS (:8444):", "http://$localIp:8080  |  https://$localIp:8444", "#34D399"))

        liveLogTextView = TextView(this).apply {
            text = "Esperando conexión AirPlay desde Mac/iPhone..."
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(10) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#030712"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
            }
        }
        rightCard.addView(liveLogTextView)

        cardsContainer.addView(leftCard)
        cardsContainer.addView(rightCard)
        standbyDashboard.addView(cardsContainer)

        val footerText = TextView(this).apply {
            text = "Renderizador: Android SurfaceView + MediaCodec Low-Latency · Presiona [BACK] en el control del TV para detener una transmisión"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        standbyDashboard.addView(footerText)

        rootLayout.addView(standbyDashboard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)
    }

    private fun startNativeHardwareMirror(width: Int, height: Int, fps: Int) {
        if (surfaceReady) {
            // Decoder nuevo en cada sesión: un Surface recreado (cambio de modo HDMI) deja inválido el anterior
            h264Decoder?.stop()
            h264Decoder = HardwareH264Decoder(mirrorSurfaceView.holder.surface) { appendTvLog(it) }
            h264Decoder?.start(width, height, fps)
        } else {
            appendTvLog("Superficie de video no lista: no se puede decodificar")
        }
        runOnUiThread {
            exoPlayer?.stop()
            exoPlayerView.visibility = View.GONE
            photoView.visibility = View.GONE
            standbyDashboard.visibility = View.GONE
            mirrorSurfaceView.visibility = View.VISIBLE
        }
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
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9111827"))
                setStroke(dp(1), Color.parseColor("#1F2937"))
                cornerRadius = dp(18).toFloat()
            }
        }
    }

    private fun createLabel(textStr: String, colorHex: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun createTitle(textStr: String, sizeSp: Float): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(6))
        }
    }

    private fun createBody(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    }

    private fun createUrlBox(label: String, url: String, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#030712"))
                setStroke(dp(1), Color.parseColor("#374151"))
                cornerRadius = dp(10).toFloat()
            }
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            })
            addView(TextView(context).apply {
                text = url
                setTextColor(Color.parseColor(colorHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

    @Suppress("DEPRECATION")
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
