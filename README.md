# RUFFS para Android

App de Android para usar **RUFFS** desde el teléfono. Es una capa nativa (Kotlin)
sobre la interfaz web de tu servidor RUFFS que añade lo que el WebView de Android
no trae: micrófono y dictado, palabra de activación, voz (TTS), notificaciones,
portapapeles, compartir y descargas a `Descargas/RUFFS`.

## Descargar

**[Descarga el APK desde Releases](../../releases/latest)** (`RUFFS.apk`).

1. Abre el archivo en el teléfono (Android 8 o superior).
2. Si Android lo pide, permite instalar apps de esa fuente.
3. Abre RUFFS y sigue el primer inicio.

Necesitas tu propio servidor RUFFS funcionando en un PC y, para usarlo fuera de
casa, [Tailscale](https://tailscale.com/download) en el PC y en el teléfono.

## Primer inicio

Cada persona conecta la app con **su** RUFFS:

1. En el PC con RUFFS, RUFFS tiene que escuchar fuera del equipo
   (`APP_BIND=0.0.0.0` en `.env`; los instaladores ya lo hacen).
2. En el teléfono, abre Tailscale y conéctate a tu red.
3. Abre la app y escribe la IP de Tailscale del PC (`100.x.y.z`) o su nombre
   MagicDNS (`tu-pc.tailXXXX.ts.net`). Si no pones puerto se usa `7100`.
   También puedes escanear o pegar el código de emparejamiento de RUFFS
   (`/api/companion/pair`, como administrador).
4. Inicia sesión en la pantalla de acceso de RUFFS.

Se pueden añadir varias direcciones (Tailscale y la Wi‑Fi de casa): la app usa
la primera que responda y cambia de ruta sola si cambias de red.

## Tirar para recargar

Desliza hacia abajo desde cualquier parte cuando lo que ves está arriba del todo.
Vibra al pasar el punto de recarga y el círculo usa los colores de tu tema.

- No se activa al arrastrar burbujas/chips, ventanas, listas ordenables o
  deslizadores de RUFFS, ni tras una pulsación larga, ni en campos de texto,
  modales o listas que no estén arriba.
- No recarga mientras RUFFS está respondiendo (lo cortaría).
- Guarda lo que estabas escribiendo y lo devuelve tras recargar.
- Comprueba el servidor antes de recargar: si no responde, prueba tus otras
  direcciones y, si ninguna responde, deja la página como estaba.
- En la pantalla de "No encuentro tu RUFFS", tirar hacia abajo reintenta.

## Compilar

Requisitos: Android Studio (o el SDK de Android con `ANDROID_HOME`) y JDK 17+.

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

### Versión firmada

Una sola vez, crea tu clave de firma (te pide la contraseña; la clave queda en
`%USERPROFILE%\.ruffs-firma\` y la configuración en `keystore.properties`, que
git ignora):

```bash
powershell -ExecutionPolicy Bypass -File scripts/crear-clave-firma.ps1
```

Luego `./gradlew assembleRelease` genera `app/build/outputs/apk/release/app-release.apk`
firmado con esa clave. El APK oficial de Releases está firmado con la clave de
RUFFS; una versión que compiles tú tendrá otra firma y no se instalará encima.

## Estructura

| Archivo | Qué hace |
|---|---|
| `app/src/main/kotlin/com/ruffs/companion/MainActivity.kt` | WebView, conexión al servidor, ventanas emergentes, permisos, descargas, botón Atrás |
| `app/src/main/assets/ruffs_bridge.js` | Puente inyectado en la página: micrófono, voz, notificaciones, portapapeles, descargas |
| `.../NativeBridge.kt` | Lado Android del puente (`window.RuffsNative` / `window.Android`) |
| `.../ServerConfig.kt`, `ServerProbe.kt` | Direcciones del servidor y detección de la que responde |
| `.../SettingsActivity.kt` | Pantalla de primer inicio / servidor (QR, pegar código, probar) |
| `.../PullToRefreshLayout.kt` | Tirar para recargar: decide si el gesto es una recarga sin romper los arrastres de RUFFS |
| `.../SpeechManager.kt`, `TtsManager.kt`, `NativeRecorder.kt` | Reconocimiento de voz, síntesis de voz y grabación |
| `.../FileSaver.kt`, `WebNotifier.kt` | Descargas y notificaciones |

## Origen

La versión para Android (APK) de Odysseus la creé yo,
[kgalvezgonzalezz](https://github.com/kgalvezgonzalezz), como proyecto personal:
hasta la fecha, que yo sepa, nadie más en el mundo había creado su propia versión
de Odysseus para Android. RUFFS para Android parte de esa app y está reescrita
para RUFFS.

## Estrellas

Si te sirve, deja una ⭐: el contador se actualiza solo.

<p align="center">
  <a href="https://github.com/kgalvezgonzalezz/ruffs-android/stargazers">
    <img alt="Estrellas en GitHub" src="https://img.shields.io/github/stars/kgalvezgonzalezz/ruffs-android?style=for-the-badge&logo=github&label=Estrellas&color=8b5cff&cacheSeconds=300">
  </a>
</p>

<p align="center">
  <a href="https://star-history.com/#kgalvezgonzalezz/ruffs-android&Date">
    <img alt="Historial de estrellas" width="600" src="https://api.star-history.com/svg?repos=kgalvezgonzalezz/ruffs-android&type=Date">
  </a>
</p>
