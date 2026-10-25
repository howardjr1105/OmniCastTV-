package com.omnicast.tv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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

    private val logLines = ArrayDeque<String>(2)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var h264Decoder: HardwareH264Decoder? = null
    private var audioPlayer: LowLatencyAudioPlayer? = null
    private var exoPlayer: ExoPlayer? = null

    private var dlnaService: DlnaSsdpService? = null
    private var airPlayService: AirPlayBonjourService? = null
    private var streamServer: NativeStreamServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pulseAnimator: ObjectAnimator? = null

    @Volatile private var surfaceReady = false

    private val deviceName = "OmniCast Google TV"

    // Paleta de la pantalla de inicio (fondo oscuro; un color de acento por método de conexión)
    private val colorText = Color.parseColor("#F8FAFC")
    private val colorMuted = Color.parseColor("#A5B4CB")
    private val colorCard = Color.parseColor("#16163A")
    private val colorCardBorder = Color.parseColor("#2E2E63")
    private val colorBg = Color.parseColor("#0F0F23")
    private val accentAirPlay = Color.parseColor("#60A5FA")
    private val accentWeb = Color.parseColor("#22C55E")
    private val accentMedia = Color.parseColor("#F59E0B")

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
        dlnaService = DlnaSsdpService(localIp, 8080, deviceName).also { it.start() }

        // 2. Iniciar Demonio AirPlay 2 + FairPlay SAP v2.5 + Bonjour mDNS
        airPlayService = AirPlayBonjourService(
            context = applicationContext,
            localIp = localIp,
            deviceName = deviceName,
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
            if (logLines.size >= 2) logLines.removeFirst()
            logLines.addLast("[$stamp] $message")
            if (::liveLogTextView.isInitialized) {
                liveLogTextView.text = logLines.joinToString("\n")
            }
        }
    }

    private fun buildNativeTvUi(localIp: String) {
        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

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
            setPadding(dp(36), dp(22), dp(36), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(colorBg, Color.parseColor("#17163F"), Color.parseColor("#1E1B4B"))
            )
        }

        standbyDashboard.addView(buildHeader(localIp))

        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
            }
        }
        cards.addView(buildAirPlayCard(), cardParams())
        cards.addView(buildWebCard(localIp), cardParams())
        cards.addView(buildMediaCard(localIp), cardParams())
        standbyDashboard.addView(cards)

        standbyDashboard.addView(buildFooter())

        rootLayout.addView(standbyDashboard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)
    }

    private fun buildHeader(localIp: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cast_logo)
            setColorFilter(accentAirPlay)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) }
        })

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(label("OmniCast", 30f, colorText, bold = true))
        titles.addView(label("Envía tu pantalla, videos y fotos a esta TV · v${appVersion()}", 13f, colorMuted))
        header.addView(titles)

        // Píldora de estado con punto que pulsa: indica que la TV está lista para recibir
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentWeb)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        statusBadgeText = label("Lista para conectar · $localIp", 14f, accentWeb, bold = true)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1422C55E"))
                setStroke(dp(1), accentWeb)
                cornerRadius = dp(99).toFloat()
            }
        }
        pill.addView(dot)
        pill.addView(statusBadgeText)
        header.addView(pill)
        return header
    }

    private fun buildAirPlayCard(): LinearLayout {
        val card = methodCard(R.drawable.ic_airplay, accentAirPlay, "Mac · iPhone · iPad", "Duplicar pantalla con AirPlay")
        card.addView(stepRow(1, "Abre el Centro de control", accentAirPlay))
        card.addView(stepRow(2, "Toca Duplicar pantalla", accentAirPlay))
        card.addView(stepRow(3, "Elige «$deviceName»", accentAirPlay))
        card.addView(spacer())
        card.addView(chip("Video + audio · sin instalar nada", accentAirPlay))
        return card
    }

    private fun buildWebCard(localIp: String): LinearLayout {
        val url = "https://$localIp:8444"
        val card = methodCard(R.drawable.ic_monitor, accentWeb, "Windows · Android · Mac", "Desde el navegador")
        val qr = generateQr(url, dp(120))
        if (qr != null) {
            card.addView(ImageView(this).apply {
                setImageBitmap(qr)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(132), dp(132)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(6)
                }
            })
        }
        card.addView(label(url, 15f, accentWeb, bold = true, mono = true).apply { gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(spacer(dp(6)))
        card.addView(stepRow(1, "Escanea el QR o abre la dirección en Chrome o Edge", accentWeb))
        card.addView(stepRow(2, "Acepta el aviso de seguridad y pulsa Compartir pantalla", accentWeb))
        return card
    }

    private fun buildMediaCard(localIp: String): LinearLayout {
        val card = methodCard(R.drawable.ic_movie, accentMedia, "Videos y fotos", "Envía un archivo a la TV")
        card.addView(stepRow(1, "En el navegador abre http://$localIp:8080", accentMedia))
        card.addView(stepRow(2, "Elige un video o una foto de tu dispositivo", accentMedia))
        card.addView(stepRow(3, "Se reproduce en la TV al instante", accentMedia))
        card.addView(spacer())
        card.addView(chip("Calidad original · sin comprimir", accentMedia))
        return card
    }

    private fun buildFooter(): LinearLayout {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        liveLogTextView = TextView(this).apply {
            text = "Esperando conexión…"
            setTextColor(Color.parseColor("#7C8DB5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        footer.addView(liveLogTextView)
        footer.addView(label(
            "Windows: Win+K (Miracast) no es compatible, usa el navegador  ·  BACK detiene la transmisión",
            11f, colorMuted
        ).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) }
        })
        return footer
    }

    private fun cardParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = dp(7)
        rightMargin = dp(7)
    }

    private fun methodCard(iconRes: Int, accent: Int, title: String, subtitle: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(colorCard)
                setStroke(dp(1), colorCardBorder)
                cornerRadius = dp(18).toFloat()
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        head.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(accent)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 18f, colorText, bold = true))
        texts.addView(label(subtitle, 12.5f, colorMuted))
        head.addView(texts)
        card.addView(head)
        return card
    }

    private fun stepRow(n: Int, text: String, accent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(7) }
            addView(TextView(context).apply {
                this.text = n.toString()
                setTextColor(colorBg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
            })
            addView(label(text, 14.5f, colorText).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun chip(text: String, accent: Int): TextView {
        return label(text, 12f, accent, bold = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                cornerRadius = dp(99).toFloat()
            }
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (mono) setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            else if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    // Empuja el contenido siguiente hacia abajo (o fija una separación si se indica altura)
    private fun spacer(height: Int = 0): View {
        return View(this).apply {
            layoutParams = if (height > 0) LinearLayout.LayoutParams(1, height)
            else LinearLayout.LayoutParams(1, 0, 1f)
        }
    }

    private fun generateQr(text: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx) { i ->
                if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }

    private fun appVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
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
        pulseAnimator?.cancel()
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
