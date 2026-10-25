# OmniCast TV

Receptor inalámbrico para **Google TV / Android TV**. Instala la app en la TV y envía a la pantalla desde tu Mac, iPhone, iPad, Windows o Android, sin instalar nada en el emisor y sin pagar nada. Todo funciona dentro de tu red Wi-Fi.

![Pantalla de inicio de OmniCast TV](docs/screenshot.png)

La pantalla de inicio de la TV muestra, paso a paso, cómo conectar cada tipo de dispositivo. Incluye un **código QR** que abre el emisor web desde el teléfono.

---

## Cómo conectarte

### Mac, iPhone y iPad: AirPlay
1. Abre el **Centro de control**.
2. Toca **Duplicar pantalla**.
3. Elige **«OmniCast Google TV»**.

Se envía video y audio. La TV y el dispositivo deben estar en la misma red Wi-Fi.

### Windows, Android y Mac: desde el navegador
1. En la TV aparece una dirección como `https://192.168.0.14:8444` y su QR. Escanéalo o escribe la dirección en **Chrome o Edge**.
2. El navegador avisa que el certificado no es de confianza (es autofirmado): acepta y continúa.
3. Pulsa **Compartir pantalla** y elige la pantalla o ventana. Marca *compartir audio* para enviar también el sonido.

### Enviar un video o una foto
1. Abre `http://<IP-DE-LA-TV>:8080` en el navegador.
2. Elige un archivo de tu dispositivo.
3. Se reproduce en la TV con su calidad original.

> **Windows y Miracast.** La proyección nativa de Windows (**Win+K** / *Conectar a pantalla inalámbrica*) **no es compatible**. Miracast exige que el receptor use Wi-Fi Direct, y no todas las TV con Android lo tienen (por ejemplo, el Chromecast HD no). Android tampoco permite que una app actúe como receptor Miracast. En Windows usa el navegador, como se explica arriba.

---

## Qué funciona

| Origen | Método | Pantalla | Audio | Video / foto |
|---|---|:---:|:---:|:---:|
| Mac, iPhone, iPad | AirPlay | ✅ | ✅ | foto ✅ · video por URL (experimental) |
| Windows, Android, Mac | Navegador (`:8444`) | ✅ | ✅ | ✅ (subida a `:8080`) |
| Windows | Miracast / Win+K | ❌ no compatible | | |
| VLC, apps DLNA | DLNA/UPnP | | | experimental |

Requisitos del navegador para compartir pantalla: **Chrome o Edge** reciente (necesita WebCodecs). Safari y Firefox no están soportados para el emisor web.

---

## Instalación en la TV

1. Descarga **`OmniCastTV.apk`** desde la [última Release](../../releases/latest).
2. En la TV activa las **Opciones de desarrollador** (*Configuración → Sistema → Acerca de*, pulsa 7 veces sobre *Compilación*) y la **depuración por ADB / inalámbrica**. Otra opción es instalar el APK con la app **Downloader**.
3. Con ADB desde tu computadora, en la misma red:
   ```bash
   adb connect <IP-DE-LA-TV>:5555
   adb install -r OmniCastTV.apk
   ```
4. Abre **OmniCast Native TV** desde el menú de aplicaciones. Aparece con su banner en la pantalla principal de Google TV.

Todas las versiones se firman con la misma clave, así que cada una se instala encima de la anterior sin desinstalar.

## Compilación

- **GitHub Actions (recomendado):** cada `push` a `master` ejecuta `.github/workflows/build-apk.yml`, compila con Gradle 8.4 / JDK 17 y publica una Release con `OmniCastTV.apk` (release) y `OmniCastTV-debug.apk`.
- **Android Studio:** abre esta carpeta y ejecuta **Build → Build APK(s)**.

La tarea `injectFairPlayEngine` descarga en `preBuild` el motor FairPlay de [`serezhka/java-airplay-lib`](https://github.com/serezhka/java-airplay-lib), así que la primera compilación necesita internet.

---

## Puertos

| Puerto | Protocolo | Uso |
|---|---|---|
| 7000, 5000 | TCP | AirPlay / RAOP (RTSP, emparejamiento, FairPlay) |
| 7100 | TCP | Video de duplicación H.264 |
| 7101 | TCP | Eventos AirPlay |
| 7102 | UDP | Sincronización de reloj (NTP) |
| 7103, 7104 | UDP | Audio AirPlay (RTP) y control |
| 8080 | TCP | Emisor web (HTTP), subida de archivos, DLNA |
| 8444 | TCP | Emisor web (HTTPS) con duplicación de pantalla |
| 1900 | UDP | Descubrimiento DLNA (SSDP) |
| 5353 | UDP | Bonjour / mDNS (AirPlay) |

## Solución de problemas

- **La TV no aparece en AirPlay:** comprueba que ambos estén en la misma red y que el router no tenga *aislamiento de clientes* (AP isolation) ni bloquee mDNS/multicast.
- **El navegador no abre `:8444`:** acepta el aviso del certificado. Sin HTTPS el navegador no deja compartir pantalla.
- **Se ve la imagen pero no se oye:** en el navegador marca *compartir audio* al elegir la pantalla; en AirPlay revisa que el volumen de la TV no esté en silencio.
- **Ver qué pasa:** la pantalla de inicio muestra los últimos eventos al pie. Para el detalle: `adb logcat | grep AirPlayService`.

## Estructura del código

| Archivo | Función |
|---|---|
| `MainActivity.kt` | Pantalla de inicio, reproductor y arranque de los servicios |
| `AirPlayBonjourService.kt` | AirPlay: anuncio mDNS, RTSP, emparejamiento, FairPlay, video y audio |
| `AirPlayAudioDecoder.kt` | Decodifica el audio AAC (ELD/LC) de AirPlay |
| `HardwareH264Decoder.kt` | Decodificador H.264 por hardware (MediaCodec) hacia la pantalla |
| `LowLatencyAudioPlayer.kt` | Reproducción de audio PCM de baja latencia |
| `NativeStreamServer.kt` | Servidor HTTP/HTTPS + WebSocket del emisor web y subida de archivos |
| `DlnaSsdpService.kt` | Descubrimiento DLNA/UPnP |
| `assets/sender.html` | Página del emisor web (captura, codificación H.264 y envío) |
