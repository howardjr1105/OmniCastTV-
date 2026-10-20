# OmniCast TV - APK Receptor Inalámbrico para Google TV y Android TV

Este proyecto contiene el código fuente nativo completo para generar el archivo **`OmniCastTV.apk`** e instalarlo en cualquier **Google TV** o **Android TV** (Chromecast con Google TV, Xiaomi TV Box/Stick, Sony, TCL, Hisense, Nvidia Shield, etc.).

## Características principales
1. **100% Local (LAN) y Sin Pagos**: El propio televisor levanta un servidor embebido HTTP (`:8080`), HTTPS (`:8443`) y WebSocket (`RFC-6455`) junto con anuncio Bonjour/mDNS (`_http._tcp.`).
2. **Compatible con las 4 plataformas sin instalar nada en el emisor**:
   - **Mac y Windows**: Duplicación de pantalla completa y ventanas a 1080p/4K y 60 FPS con audio vía WebRTC P2P LAN (`https://<IP-DEL-TV>:8443`).
   - **iPhone / iPad (iOS)**: Envío directo de videos 4K y fotos desde la galería sin pérdida de calidad ni compresión (`http://<IP-DEL-TV>:8080`), más cámara en vivo.
   - **Android**: Duplicación de pantalla completa, cámara en vivo y envío de archivos multimedia.
3. **Integración Nativa con Google TV**:
   - Incluye soporte para `LEANBACK_LAUNCHER` (aparece con su banner en la pantalla principal de Google TV).
   - Incluye control mediante los botones del mando a distancia del televisor (`OK`, `Play/Pause`, `Arriba` para rotar, `Derecha` para cambiar ajuste de pantalla).

---

## Cómo obtener el archivo `.APK` (2 Opciones)

### Opción A: Usando Android Studio (En tu Mac o PC)
1. Abre **Android Studio** y selecciona **Open** -> elige esta carpeta `OmniCastTV`.
2. Espera a que Gradle sincronice el proyecto.
3. Ve al menú superior **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
4. El archivo `.apk` quedará listo en `app/build/outputs/apk/debug/app-debug.apk`.

### Opción B: Sin instalar nada en tu PC (Con GitHub Actions en 2 minutos)
1. Sube esta carpeta a un repositorio de GitHub.
2. Ve a la pestaña **Actions** en tu repositorio de GitHub (el archivo `.github/workflows/build-apk.yml` ya está configurado).
3. Al finalizar el flujo, descarga el archivo **`OmniCastTV-APK`** que contiene `app-debug.apk`.

---

## Cómo instalar el APK en tu Google TV / Android TV
1. En tu Google TV, ve a **Configuración -> Sistema -> Información -> Compilación del SO de Android TV** y presiona 7 veces hasta activar las **Opciones de desarrollador**.
2. Activa **Depuración por ADB (Red / Inalámbrica)** o usa la app gratuita **Send Files to TV** / **Downloader** desde la Play Store del TV.
3. Si usas ADB desde tu computadora en la misma red Wi-Fi:
   ```bash
   adb connect <IP_DE_TU_GOOGLE_TV>:5555
   adb install app-debug.apk
   ```
