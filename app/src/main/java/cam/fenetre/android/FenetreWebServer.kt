package cam.fenetre.android

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class FenetreWebServer(
    private val rootDir: File,
    private val settings: FenetreCameraSettings,
    private val port: Int = settings.webPort(),
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (running) {
            return
        }
        rootDir.mkdirs()
        running = true
        acceptThread = thread(name = "fenetre-web-server") {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    while (running) {
                        val client = socket.accept()
                        clientExecutor.execute { handleClient(client) }
                    }
                }
            } catch (exception: Exception) {
                if (running) {
                    Log.e(TAG, "Web server failed", exception)
                }
            } finally {
                running = false
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientExecutor.shutdownNow()
    }

    fun url(host: String = settings.webHost()): String = "http://$host:$port/"

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val input = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(client, 400, "Bad Request", "text/plain", "Bad Request\n".toByteArray())
                    return
                }

                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) {
                        break
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    writeResponse(client, 405, "Method Not Allowed", "text/plain", "Method Not Allowed\n".toByteArray())
                    return
                }

                if (target.substringBefore("?") == "/") {
                    val body = homePage().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(client, 200, "OK", "text/html; charset=utf-8", body, method == "HEAD")
                    return
                }

                if (target.substringBefore("?") == "/timelapse.html") {
                    val body = timelapsePage().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(client, 200, "OK", "text/html; charset=utf-8", body, method == "HEAD")
                    return
                }

                if (target.substringBefore("?") == "/cameras.json") {
                    val body = camerasJson().toByteArray(StandardCharsets.UTF_8)
                    writeResponse(client, 200, "OK", "application/json", body, method == "HEAD")
                    return
                }

                val file = resolveTarget(target)
                if (file == null || !file.exists()) {
                    writeResponse(client, 404, "Not Found", "text/plain", "Not Found\n".toByteArray(), method == "HEAD")
                    return
                }

                if (file.isDirectory) {
                    val body = directoryListing(file, target).toByteArray(StandardCharsets.UTF_8)
                    writeResponse(client, 200, "OK", "text/html; charset=utf-8", body, method == "HEAD")
                    return
                }

                writeFileResponse(client, file, method == "HEAD")
            }
        } catch (exception: SocketException) {
            Log.i(TAG, "Client disconnected: ${exception.message}")
        } catch (exception: IOException) {
            Log.i(TAG, "Client I/O failed: ${exception.message}")
        } catch (exception: Exception) {
            Log.e(TAG, "Web request failed", exception)
        }
    }

    private fun resolveTarget(target: String): File? {
        val pathOnly = target.substringBefore("?").substringBefore("#")
        val decoded = URLDecoder.decode(pathOnly, StandardCharsets.UTF_8.name())
        val relative = decoded.trimStart('/').ifEmpty { "." }
        val file = File(rootDir, relative)
        val canonicalRoot = rootDir.canonicalFile
        val canonicalFile = file.canonicalFile
        return if (canonicalFile.toPath().startsWith(canonicalRoot.toPath())) canonicalFile else null
    }

    private fun directoryListing(dir: File, target: String): String {
        val prefix = target.substringBefore("?").trimEnd('/')
        val appTitle = settings.deploymentName()
        val entries = dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            .orEmpty()
        val links = entries.joinToString("\n") { file ->
            val name = if (file.isDirectory) "${file.name}/" else file.name
            val href = "${if (prefix.isEmpty()) "" else prefix}/$name"
            """<a class="entry" href="$href"><span>$name</span><small>${fileLabel(file)}</small></a>"""
        }
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$appTitle</title>
            <style>${styles()}</style>
            </head>
            <body>
            <main class="browser">
              <header class="browser-header">
                <a href="/" class="brand">$appTitle</a>
                <span>${target.substringBefore("?").ifEmpty { "/" }}</span>
              </header>
              <nav class="entries">$links</nav>
            </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun homePage(): String {
        val cameraName = settings.cameraName()
        val latest = File(File(File(rootDir, "photos"), cameraName), "latest.jpg")
        val metadata = File(File(File(rootDir, "photos"), cameraName), "metadata.json")
        val latestVersion = if (latest.exists()) latest.lastModified().toString() else System.currentTimeMillis().toString()
        val metadataVersion = if (metadata.exists()) metadata.lastModified().toString() else latestVersion
        val appTitle = settings.deploymentName()
        val comparisonUrl = settings.comparisonUrl()
        val dailyExtension = settings.dailyTimelapseEncoderMode().fileExtension
        val comparisonLink = if (comparisonUrl.isNotBlank()) {
            """<a href="$comparisonUrl">Compare</a>"""
        } else {
            ""
        }
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$appTitle</title>
            <style>${styles()}</style>
            </head>
            <body class="home">
              <main class="stage">
                <img id="latest" class="latest" src="/photos/$cameraName/latest.jpg?t=$latestVersion" alt="$appTitle latest capture">
                <div class="shade top"></div>
                <div class="shade bottom"></div>
                <header class="hud topbar">
                  <div>
                    <h1>$appTitle</h1>
                    <p id="captureTime">Loading capture...</p>
                  </div>
                  <nav>
                    <a href="/photos/$cameraName/latest.jpg">Latest</a>
                    <a href="/photos/$cameraName/metadata.json">Metadata</a>
                    <a id="timelapseLink" href="/photos/">Today's timelapse</a>
                    <a id="dailyLink" href="/photos/">Yesterday's timelapse</a>
                    <a href="/photos/$cameraName/daylight.html">Daylight</a>
                    <a href="/photos/">Files</a>
                    $comparisonLink
                  </nav>
                </header>
                <section class="hud status" aria-label="Camera status">
                  <span id="lens">Lens</span>
                  <span id="mode">Mode</span>
                  <span id="rotation">Rotation</span>
                  <span id="iso">ISO</span>
                  <span id="exposure">Exposure</span>
                  <span id="brightness">Brightness</span>
                  <span id="whiteBalance">WB</span>
                  <span id="refresh">Live</span>
                </section>
              </main>
              <script>
                const image = document.getElementById('latest');
                const captureTime = document.getElementById('captureTime');
                const lens = document.getElementById('lens');
                const mode = document.getElementById('mode');
                const rotation = document.getElementById('rotation');
                const iso = document.getElementById('iso');
                const exposure = document.getElementById('exposure');
                const brightness = document.getElementById('brightness');
                const whiteBalance = document.getElementById('whiteBalance');
                const timelapseLink = document.getElementById('timelapseLink');
                const dailyLink = document.getElementById('dailyLink');
                const refresh = document.getElementById('refresh');
                const cameraName = ${jsonString(cameraName)};
                const dailyExtension = ${jsonString(dailyExtension)};
                let chromeHidden = false;

                image.addEventListener('click', () => {
                  chromeHidden = !chromeHidden;
                  document.body.classList.toggle('chrome-hidden', chromeHidden);
                });

                async function setOptionalLink(anchor, url, href) {
                  try {
                    const response = await fetch(url + '?t=' + Date.now(), { method: 'HEAD', cache: 'no-store' });
                    if (response.ok) {
                      anchor.href = href || url;
                      anchor.classList.remove('disabled');
                      anchor.removeAttribute('aria-disabled');
                      return;
                    }
                  } catch (error) {
                  }
                  anchor.removeAttribute('href');
                  anchor.classList.add('disabled');
                  anchor.setAttribute('aria-disabled', 'true');
                }

                function previousDay(day) {
                  const date = new Date(day + 'T12:00:00Z');
                  date.setUTCDate(date.getUTCDate() - 1);
                  return date.toISOString().slice(0, 10);
                }

                async function refreshCamera() {
                  const now = Date.now();
                  image.src = '/photos/' + cameraName + '/latest.jpg?t=' + now;
                  try {
                    const response = await fetch('/photos/' + cameraName + '/metadata.json?t=' + now, { cache: 'no-store' });
                    const metadata = await response.json();
                    captureTime.textContent = metadata.last_picture_url || 'Latest capture';
                    if (metadata.last_picture_url && metadata.last_picture_url.includes('/')) {
                      const day = metadata.last_picture_url.split('/')[0];
                      const yesterday = previousDay(day);
                      const playlist = '/photos/' + cameraName + '/' + day + '/' + day + '.m3u8';
                      const params = new URLSearchParams({ src: playlist, title: cameraName + ' ' + day });
                      setOptionalLink(timelapseLink, playlist, '/timelapse.html?' + params.toString());
                      setOptionalLink(dailyLink, '/photos/' + cameraName + '/' + yesterday + '/' + yesterday + '.' + dailyExtension);
                    }
                    lens.textContent = (metadata.lens_mode || 'camera').replace('_', ' ');
                    mode.textContent = (metadata.exposure_mode || 'auto') + ' / ' + (metadata.capture_mode || 'day');
                    rotation.textContent = 'rotate ' + (metadata.rotation_degrees ?? 0);
                    iso.textContent = metadata.iso ? 'ISO ' + metadata.iso : 'ISO n/a';
                    exposure.textContent = metadata.shutter_speed || metadata.exposure_time || 'Exposure n/a';
                    brightness.textContent = metadata.image_brightness == null ? 'Brightness n/a' : 'Brightness ' + Math.round(metadata.image_brightness * 100) + '%';
                    whiteBalance.textContent = metadata.white_balance ? 'WB ' + metadata.white_balance : 'WB n/a';
                    refresh.textContent = new Date().toLocaleTimeString();
                  } catch (error) {
                    captureTime.textContent = 'Waiting for camera';
                    refresh.textContent = 'Offline';
                  }
                }

                refreshCamera();
                setInterval(refreshCamera, ${settings.captureIntervalSeconds() * 1000});
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun timelapsePage(): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Fenetre Timelapse</title>
            <style>
              :root { color-scheme: dark; font-family: Inter, Roboto, Arial, sans-serif; background: #05070a; color: #f5f7fb; }
              * { box-sizing: border-box; }
              html, body { margin: 0; min-height: 100%; background: #05070a; }
              body { display: flex; align-items: center; justify-content: center; padding: 16px; }
              main { width: min(1180px, 100%); }
              header { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; margin-bottom: 12px; }
              h1 { margin: 0; font-size: 22px; font-weight: 650; }
              a { color: rgba(255,255,255,.78); text-decoration: none; }
              video { width: 100%; max-height: calc(100vh - 110px); background: #000; }
              #status { margin-top: 10px; color: rgba(255,255,255,.72); font-size: 14px; }
              #status.error { color: #ff9c9c; }
            </style>
            </head>
            <body>
              <main>
                <header>
                  <h1 id="title">Timelapse</h1>
                  <a href="/">Live camera</a>
                </header>
                <video id="video" controls autoplay playsinline muted></video>
                <div id="status">Loading player...</div>
              </main>
              <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
              <script>
                const params = new URLSearchParams(window.location.search);
                const src = params.get('src');
                const title = params.get('title') || 'Timelapse';
                const titleElement = document.getElementById('title');
                const videoElement = document.getElementById('video');
                const statusElement = document.getElementById('status');
                titleElement.textContent = title;

                function setStatus(message, isError = false) {
                  statusElement.textContent = message;
                  statusElement.classList.toggle('error', isError);
                }

                function seekToStartAndPlay() {
                  try { videoElement.currentTime = 0; } catch (_) {}
                  videoElement.play().catch(() => {});
                }

                async function initializePlayer() {
                  if (!src) {
                    setStatus('Missing playlist source.', true);
                    return;
                  }
                  const playlistUrl = new URL(src, window.location.href).href;
                  if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
                    videoElement.src = playlistUrl;
                    videoElement.addEventListener('loadedmetadata', seekToStartAndPlay, { once: true });
                    setStatus('Playing with native HLS.');
                    return;
                  }
                  if (window.Hls && window.Hls.isSupported()) {
                    const hls = new window.Hls({ startPosition: 0 });
                    hls.loadSource(playlistUrl);
                    hls.attachMedia(videoElement);
                    hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
                      setStatus('Playing with hls.js.');
                      seekToStartAndPlay();
                    });
                    hls.on(window.Hls.Events.ERROR, (_, data) => {
                      if (data && data.fatal) {
                        setStatus('Playback error: ' + (data.details || 'unknown error'), true);
                      }
                    });
                    return;
                  }
                  setStatus('This browser does not support HLS playback.', true);
                }

                initializePlayer();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun camerasJson(): String {
        val cameraName = settings.cameraName()
        val publicBaseUrl = settings.publicBaseUrl()
        val latest = File(File(File(rootDir, "photos"), cameraName), "latest.jpg")
        val thumbnailUrl = if (latest.exists()) {
            "/photos/$cameraName/latest.jpg?t=${latest.lastModified()}"
        } else {
            "/photos/$cameraName/latest.jpg"
        }
        return """
            {
              "cameras": [
                {
                  "title": ${jsonString(cameraName)},
                  "url": "/",
                  "fullscreen_url": "/",
                  "original_url": ${jsonString(publicBaseUrl)},
                  "description": ${jsonString(settings.cameraDescription())},
                  "snap_interval_s": ${settings.captureIntervalSeconds()},
                  "dynamic_metadata": "photos/$cameraName/metadata.json",
                  "image": "$thumbnailUrl",
                  "thumbnail_url": "$thumbnailUrl",
                  "lat": null,
                  "lon": null,
                  "map_radius_m": 0
                }
              ],
              "global": {
                "timelapse_file_extension": ${jsonString(settings.dailyTimelapseEncoderMode().fileExtension)},
                "frequent_timelapse_file_extension": "m3u8",
                "deployment_name": ${jsonString(settings.deploymentName())},
                "ui": {
                  "landing_page": "camera",
                  "show_map_by_default": false
                }
              }
            }
        """.trimIndent() + "\n"
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private fun fileLabel(file: File): String {
        return if (file.isDirectory) "folder" else "${file.length() / 1024} KB"
    }

    private fun styles(): String {
        return """
            :root {
              color-scheme: dark;
              font-family: Inter, Roboto, Arial, sans-serif;
              background: #07090d;
              color: #f5f7fb;
            }
            * { box-sizing: border-box; }
            html, body { margin: 0; min-height: 100%; }
            body { background: #07090d; }
            a { color: inherit; text-decoration: none; }
            .stage {
              position: relative;
              min-height: 100vh;
              overflow: hidden;
              background: #07090d;
            }
            .latest {
              position: absolute;
              inset: 0;
              width: 100%;
              height: 100%;
              object-fit: contain;
              background: #05070a;
            }
            .shade {
              position: absolute;
              left: 0;
              right: 0;
              pointer-events: none;
              transition: opacity .18s ease;
            }
            .shade.top {
              top: 0;
              height: 28vh;
              background: linear-gradient(to bottom, rgba(0,0,0,.58), rgba(0,0,0,0));
            }
            .shade.bottom {
              bottom: 0;
              height: 24vh;
              background: linear-gradient(to top, rgba(0,0,0,.54), rgba(0,0,0,0));
            }
            .hud {
              position: absolute;
              z-index: 2;
              color: #fff;
              text-shadow: 0 1px 10px rgba(0,0,0,.65);
              transition: opacity .18s ease;
            }
            .chrome-hidden .hud,
            .chrome-hidden .shade {
              opacity: 0;
              pointer-events: none;
            }
            .topbar {
              top: 0;
              left: 0;
              right: 0;
              display: flex;
              align-items: flex-start;
              justify-content: space-between;
              gap: 24px;
              padding: 22px 26px;
            }
            h1 {
              margin: 0;
              font-size: 28px;
              font-weight: 650;
            }
            p {
              margin: 5px 0 0;
              color: rgba(255,255,255,.78);
              font-size: 14px;
            }
            .topbar nav {
              display: flex;
              flex-wrap: wrap;
              justify-content: flex-end;
              gap: 8px;
            }
            .topbar a, .status span {
              min-height: 36px;
              display: inline-flex;
              align-items: center;
              border: 1px solid rgba(255,255,255,.24);
              background: rgba(5, 7, 10, .42);
              backdrop-filter: blur(10px);
              padding: 0 12px;
              font-size: 13px;
            }
            .topbar a:hover {
              background: rgba(255,255,255,.16);
            }
            .topbar a.disabled {
              opacity: .46;
              pointer-events: none;
            }
            .status {
              left: 26px;
              bottom: 22px;
              display: flex;
              flex-wrap: wrap;
              gap: 8px;
              text-transform: capitalize;
            }
            .browser {
              width: min(980px, calc(100vw - 32px));
              margin: 0 auto;
              padding: 28px 0;
            }
            .browser-header {
              display: flex;
              align-items: baseline;
              justify-content: space-between;
              gap: 16px;
              margin-bottom: 18px;
              color: rgba(255,255,255,.74);
            }
            .brand {
              color: #fff;
              font-size: 24px;
              font-weight: 650;
            }
            .entries {
              display: grid;
              gap: 8px;
            }
            .entry {
              display: flex;
              justify-content: space-between;
              gap: 16px;
              border: 1px solid rgba(255,255,255,.12);
              background: #10151d;
              padding: 14px 16px;
            }
            .entry:hover { background: #18202b; }
            .entry small { color: rgba(255,255,255,.56); }
            @media (max-width: 720px) {
              .topbar {
                padding: 16px;
                flex-direction: column;
              }
              .topbar nav {
                justify-content: flex-start;
              }
              .status {
                left: 16px;
                right: 16px;
                bottom: 14px;
              }
              h1 { font-size: 24px; }
            }
        """.trimIndent()
    }

    private fun writeFileResponse(socket: Socket, file: File, headOnly: Boolean) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val contentType = contentType(file)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            BufferedInputStream(file.inputStream()).use { input ->
                input.copyTo(output, FILE_COPY_BUFFER_SIZE)
            }
        }
        output.flush()
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false,
    ) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            output.write(body)
        }
        output.flush()
    }

    private fun contentType(file: File): String {
        return when (file.extension.lowercase(Locale.US)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "FenetreWebServer"
        private const val FILE_COPY_BUFFER_SIZE = 64 * 1024
    }
}
