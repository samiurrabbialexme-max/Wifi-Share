package com.example.server

import android.content.Context
import android.util.Base64
import com.example.model.LogLevel
import com.example.model.LogProtocol
import com.example.model.ServerConfig
import com.example.model.ServerLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class HttpWebServer(
    private val context: Context,
    private val config: ServerConfig,
    private val logFlow: MutableSharedFlow<ServerLog>
) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeClients = AtomicInteger(0)

    val activeClientCount: Int
        get() = activeClients.get()

    fun start(): Boolean {
        if (isRunning) return true
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(config.webPort))
            }
            isRunning = true
            log("System", LogLevel.INFO, "Web File Server started on port ${config.webPort}")

            serverScope.launch {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        activeClients.incrementAndGet()
                        launch {
                            try {
                                handleHttpRequest(socket)
                            } catch (e: Exception) {
                                log("System", LogLevel.ERROR, "HTTP Request error: ${e.localizedMessage}")
                            } finally {
                                activeClients.decrementAndGet()
                                try { socket.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            log("System", LogLevel.ERROR, "HTTP Accept error: ${e.localizedMessage}")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            log("System", LogLevel.ERROR, "Failed to bind Web port ${config.webPort}: ${e.localizedMessage}")
            isRunning = false
            return false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        serverScope.coroutineContext.cancelChildren()
        activeClients.set(0)
        log("System", LogLevel.INFO, "Web File Server stopped")
    }

    private suspend fun handleHttpRequest(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        val fileHelper = FileProviderHelper(context, config.folderUri)

        try {
            socket.soTimeout = 30000
            val rawInput = socket.getInputStream()
            val bis = BufferedInputStream(rawInput, 65536)
            val output = socket.getOutputStream()

            val requestLine = readLineFromStream(bis) ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return

            val method = requestParts[0].uppercase(Locale.ENGLISH)
            val fullUrl = requestParts[1]
            val urlPath = URLDecoder.decode(fullUrl.substringBefore('?'), "UTF-8")

            // Read Headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = readLineFromStream(bis)
                if (headerLine.isNullOrBlank()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    headers[headerLine.substring(0, colonIdx).trim().lowercase()] =
                        headerLine.substring(colonIdx + 1).trim()
                }
            }

            // Authentication Check
            if (config.requireAuth) {
                val authHeader = headers["authorization"]
                var authenticated = false
                if (authHeader != null && authHeader.lowercase().startsWith("basic ")) {
                    val base64Creds = authHeader.substring(6)
                    val creds = String(Base64.decode(base64Creds, Base64.NO_WRAP))
                    val userPass = creds.split(":", limit = 2)
                    if (userPass.size == 2 && userPass[0] == config.username && userPass[1] == config.password) {
                        authenticated = true
                    }
                }

                if (!authenticated) {
                    log(clientIp, LogLevel.WARNING, "HTTP 401 Unauthorized")
                    sendHttpResponse(
                        output,
                        401,
                        "Unauthorized",
                        "text/plain",
                        "401 Unauthorized".toByteArray(),
                        headers = mapOf("WWW-Authenticate" to "Basic realm=\"Wi-Fi Folder Share\"")
                    )
                    return
                }
            }

            val relPath = urlPath.trim('/')

            // API endpoint for Realtime Text Transfer
            if (urlPath == "/api/text" || urlPath == "/api/text/") {
                if (method == "GET") {
                    val snippets = com.example.model.TextTransferRepository.snippets.value
                    val json = snippets.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
                        """{"id":"${item.id}","content":"${escapeJson(item.content)}","sender":"${escapeJson(item.sender)}","timestamp":${item.timestamp}}"""
                    }
                    sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray())
                    return
                } else if (method == "POST") {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyString = if (contentLength > 0) {
                        val bytes = ByteArray(contentLength)
                        var readTotal = 0
                        while (readTotal < contentLength) {
                            val read = bis.read(bytes, readTotal, contentLength - readTotal)
                            if (read == -1) break
                            readTotal += read
                        }
                        String(bytes, 0, readTotal, Charsets.UTF_8)
                    } else ""

                    val textContent = when {
                        bodyString.contains("text=") -> {
                            val rawVal = bodyString.substringAfter("text=").substringBefore("&")
                            URLDecoder.decode(rawVal, "UTF-8")
                        }
                        bodyString.contains("\"content\":") -> {
                            val rawVal = bodyString.substringAfter("\"content\":").substringBefore("}").trim().trim('"')
                            rawVal.replace("\\n", "\n").replace("\\\"", "\"")
                        }
                        else -> bodyString.trim()
                    }

                    if (textContent.isNotEmpty()) {
                        com.example.model.TextTransferRepository.addSnippet(textContent, "Browser ($clientIp)")
                        log(clientIp, LogLevel.SUCCESS, "Text transferred via Portal: ${textContent.take(30)}")
                    }

                    if (fullUrl.contains("/api/text") || headers["accept"]?.contains("json") == true) {
                        sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", """{"status":"ok"}""".toByteArray())
                    } else {
                        val redirectUrl = if (relPath.isEmpty()) "/" else "/$relPath"
                        sendHttpResponse(output, 303, "See Other", "text/plain", "Text Sent".toByteArray(), mapOf("Location" to redirectUrl))
                    }
                    return
                }
            }

            if (method == "GET" || method == "HEAD") {
                val doc = fileHelper.resolveDocument(relPath)
                if (doc == null || !doc.exists()) {
                    log(clientIp, LogLevel.WARNING, "404 Not Found: $urlPath")
                    sendHttpResponse(output, 404, "Not Found", "text/html", generateErrorHtml("404 - File or Folder Not Found").toByteArray())
                    return
                }

                if (doc.isDirectory) {
                    val isDownload = fullUrl.contains("download=true") || fullUrl.contains("zip=true")
                    if (isDownload) {
                        log(clientIp, LogLevel.SUCCESS, "Downloading folder as ZIP: /$relPath")
                        val folderName = if (relPath.isEmpty()) fileHelper.rootName else doc.name ?: "folder"
                        val zipFileName = folderName.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".zip"
                        val respHeaders = mapOf("Content-Disposition" to "attachment; filename=\"$zipFileName\"")
                        sendHttpResponseHeaders(output, 200, "OK", "application/zip", -1, respHeaders)
                        val zipOut = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(output))
                        zipDirectory(fileHelper, relPath, "", zipOut)
                        zipOut.finish()
                        zipOut.flush()
                        return
                    }

                    // Serve Directory Listing Page
                    log(clientIp, LogLevel.INFO, "Browsing folder: /$relPath")
                    val items = fileHelper.listFiles(relPath)
                    val html = generateDirectoryHtml(relPath, items, fileHelper.rootName, config.isReadOnly)
                    sendHttpResponse(output, 200, "OK", "text/html; charset=utf-8", html.toByteArray())
                } else {
                    // Serve File Download / Stream
                    val length = doc.length()
                    val mimeType = doc.type ?: NetworkUtils.getMimeType(doc.name ?: "file")
                    val fileName = doc.name ?: "download"

                    val isDownload = fullUrl.contains("download=true")
                    val isRange = headers.containsKey("range")

                    log(clientIp, LogLevel.SUCCESS, "Serving file: $fileName (${NetworkUtils.formatFileSize(length)})")

                    if (isRange) {
                        val rangeVal = headers["range"]!!
                        val rangeMatch = "bytes=(\\d*)-(\\d*)".toRegex().find(rangeVal)
                        var start = 0L
                        var end = length - 1
                        if (rangeMatch != null) {
                            val startStr = rangeMatch.groupValues[1]
                            val endStr = rangeMatch.groupValues[2]
                            if (startStr.isNotEmpty()) start = startStr.toLong()
                            if (endStr.isNotEmpty()) end = endStr.toLong()
                        }
                        if (start > end || start >= length) {
                            sendHttpResponse(output, 416, "Range Not Satisfiable", "text/plain", "Requested Range Not Satisfiable".toByteArray())
                            return
                        }
                        val contentLength = end - start + 1
                        val respHeaders = mutableMapOf(
                            "Content-Range" to "bytes $start-$end/$length",
                            "Accept-Ranges" to "bytes"
                        )

                        sendHttpResponseHeaders(output, 206, "Partial Content", mimeType, contentLength, respHeaders)
                        if (method == "GET") {
                            fileHelper.getInputStream(relPath)?.use { stream ->
                                stream.skip(start)
                                copyStreamWithLength(stream, output, contentLength)
                            }
                        }
                    } else {
                        val respHeaders = mutableMapOf<String, String>()
                        if (isDownload) {
                            respHeaders["Content-Disposition"] = "attachment; filename=\"$fileName\""
                        }
                        sendHttpResponseHeaders(output, 200, "OK", mimeType, length, respHeaders)
                        if (method == "GET") {
                            fileHelper.getInputStream(relPath)?.use { stream ->
                                stream.copyTo(output)
                            }
                        }
                    }
                }
            } else if (method == "POST" && fullUrl.startsWith("/upload")) {
                if (config.isReadOnly) {
                    sendHttpResponse(output, 403, "Forbidden", "text/plain", "Server is read-only".toByteArray())
                    return
                }

                val targetRelPath = URLDecoder.decode(fullUrl.substringAfter("dir=", "").substringBefore("&"), "UTF-8").trim('/')
                val contentType = headers["content-type"] ?: ""

                if (contentType.contains("multipart/form-data")) {
                    val rawBoundary = contentType.substringAfter("boundary=").substringBefore(";").trim().trim('"')
                    val uploadSuccess = handleMultipartUpload(bis, rawBoundary, targetRelPath, fileHelper)
                    val isAjax = headers["x-requested-with"]?.lowercase() == "xmlhttprequest" || headers["accept"]?.contains("json") == true
                    if (uploadSuccess) {
                        log(clientIp, LogLevel.SUCCESS, "File uploaded to /$targetRelPath")
                        if (isAjax) {
                            sendHttpResponse(output, 200, "OK", "application/json; charset=utf-8", """{"status":"ok","message":"File uploaded"}""".toByteArray())
                        } else {
                            val redirectUrl = if (targetRelPath.isEmpty()) "/" else "/$targetRelPath"
                            sendHttpResponse(output, 303, "See Other", "text/plain", "Uploaded".toByteArray(), mapOf("Location" to redirectUrl))
                        }
                    } else {
                        log(clientIp, LogLevel.ERROR, "File upload failed for /$targetRelPath")
                        if (isAjax) {
                            sendHttpResponse(output, 500, "Server Error", "application/json; charset=utf-8", """{"status":"error","message":"Upload failed"}""".toByteArray())
                        } else {
                            sendHttpResponse(output, 500, "Server Error", "text/plain", "Upload failed".toByteArray())
                        }
                    }
                } else {
                    sendHttpResponse(output, 400, "Bad Request", "text/plain", "Invalid Content-Type".toByteArray())
                }
            } else {
                sendHttpResponse(output, 405, "Method Not Allowed", "text/plain", "Method Not Allowed".toByteArray())
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (_: Exception) {}
            activeClients.decrementAndGet()
        }
    }

    private fun readLineFromStream(stream: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("UTF-8")
    }

    private fun handleMultipartUpload(
        bis: InputStream,
        rawBoundary: String,
        targetRelDir: String,
        fileHelper: FileProviderHelper
    ): Boolean {
        try {
            var fileName: String? = null

            // Read part headers until empty line
            var line: String?
            while (readLineFromStream(bis).also { line = it } != null) {
                val trimmedLine = line!!
                if (trimmedLine.isEmpty()) {
                    if (!fileName.isNullOrBlank()) break
                }
                if (trimmedLine.contains("filename=", ignoreCase = true)) {
                    val rawVal = trimmedLine.substringAfter("filename=").trim()
                    fileName = if (rawVal.startsWith("\"")) {
                        rawVal.substring(1).substringBefore("\"")
                    } else {
                        rawVal.substringBefore(";").substringBefore(" ")
                    }
                    fileName = fileName.substringAfterLast('/').substringAfterLast('\\')
                }
            }

            if (fileName.isNullOrBlank()) return false

            val targetPath = if (targetRelDir.isEmpty()) fileName else "$targetRelDir/$fileName"
            val outputStream = fileHelper.getOutputStream(targetPath) ?: return false

            val searchPattern = "\r\n--$rawBoundary".toByteArray(Charsets.UTF_8)
            val patternLen = searchPattern.size
            val buffer = ByteArray(65536)
            var prevData = ByteArray(0)

            outputStream.use { out ->
                var bytesRead: Int
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = buffer.copyOf(bytesRead)
                    val combined = prevData + chunk
                    val matchIdx = findSequence(combined, searchPattern)
                    if (matchIdx != -1) {
                        out.write(combined, 0, matchIdx)
                        break
                    } else {
                        val safeLen = combined.size - patternLen
                        if (safeLen > 0) {
                            out.write(combined, 0, safeLen)
                            prevData = combined.copyOfRange(safeLen, combined.size)
                        } else {
                            prevData = combined
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun findSequence(data: ByteArray, target: ByteArray): Int {
        for (i in 0..data.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (data[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun copyStreamWithLength(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val readLen = input.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
            if (readLen == -1) break
            output.write(buffer, 0, readLen)
            remaining -= readLen
        }
        output.flush()
    }

    private fun sendHttpResponseHeaders(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        contentLength: Long,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val pw = PrintWriter(OutputStreamWriter(output, "UTF-8"))
        pw.print("HTTP/1.1 $statusCode $statusText\r\n")
        pw.print("Content-Type: $contentType\r\n")
        pw.print("Content-Length: $contentLength\r\n")
        pw.print("Connection: close\r\n")
        for ((k, v) in extraHeaders) {
            pw.print("$k: $v\r\n")
        }
        pw.print("\r\n")
        pw.flush()
    }

    private fun sendHttpResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap()
    ) {
        sendHttpResponseHeaders(output, statusCode, statusText, contentType, body.size.toLong(), headers)
        output.write(body)
        output.flush()
    }

    private fun generateDirectoryHtml(
        currentRelPath: String,
        items: List<com.example.model.SharedFileItem>,
        rootFolderName: String,
        isReadOnly: Boolean
    ): String {
        val cleanPath = currentRelPath.trim('/')
        val pathSegments = if (cleanPath.isEmpty()) emptyList() else cleanPath.split('/')

        val breadcrumbsHtml = StringBuilder("""<a href="/">🏠 ${rootFolderName}</a>""")
        var accumulatedPath = ""
        for (seg in pathSegments) {
            accumulatedPath += "/$seg"
            breadcrumbsHtml.append(""" <span class="sep">/</span> <a href="$accumulatedPath">$seg</a>""")
        }

        val folderDownloadUrl = if (cleanPath.isEmpty()) "/?download=true" else "/$cleanPath?download=true"

        val rowsHtml = StringBuilder()
        if (pathSegments.isNotEmpty()) {
            val parentPath = if (pathSegments.size == 1) "/" else "/" + pathSegments.dropLast(1).joinToString("/")
            rowsHtml.append("""
                <tr class="folder-row">
                    <td><a href="$parentPath" class="item-link">📁 <b>.. (Parent Directory)</b></a></td>
                    <td>Folder</td>
                    <td>-</td>
                    <td>-</td>
                    <td>-</td>
                </tr>
            """.trimIndent())
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        for (item in items) {
            val itemPath = if (cleanPath.isEmpty()) "/${item.name}" else "/$cleanPath/${item.name}"
            val icon = getFileIcon(item.isDirectory, item.name)
            val dateStr = dateFormat.format(Date(item.lastModified))
            val sizeStr = if (item.isDirectory) "-" else NetworkUtils.formatFileSize(item.sizeBytes)
            val safeName = escapeHtmlKt(item.name)

            if (item.isDirectory) {
                rowsHtml.append("""
                    <tr class="folder-row">
                        <td><a href="$itemPath" class="item-link">$icon <b>$safeName</b></a></td>
                        <td>Folder</td>
                        <td>$sizeStr</td>
                        <td>$dateStr</td>
                        <td style="white-space: nowrap;">
                            <a href="$itemPath" class="btn btn-sm btn-primary">📁 Open</a>
                            <a href="$itemPath?download=true" class="btn btn-sm btn-success">⬇️ Download ZIP</a>
                        </td>
                    </tr>
                """.trimIndent())
            } else {
                val isMedia = item.mimeType.startsWith("video/") || item.mimeType.startsWith("audio/") || item.mimeType.startsWith("image/") || item.mimeType == "application/pdf"
                val downloadBtn = """<a href="$itemPath?download=true" download="$safeName" class="btn btn-sm btn-success">⬇️ Download</a>"""
                val actionButtons = if (isMedia) {
                    """<a href="$itemPath" target="_blank" class="btn btn-sm btn-primary">👁️ Preview</a> $downloadBtn"""
                } else {
                    downloadBtn
                }

                rowsHtml.append("""
                    <tr>
                        <td><a href="$itemPath?download=true" download="$safeName" class="item-link">$icon $safeName</a></td>
                        <td>${item.mimeType.substringAfter('/')}</td>
                        <td>$sizeStr</td>
                        <td>$dateStr</td>
                        <td style="white-space: nowrap;">$actionButtons</td>
                    </tr>
                """.trimIndent())
            }
        }

        val uploadSectionHtml = if (!isReadOnly) {
            """
            <div class="card upload-card">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                    <h3 style="margin: 0; font-size: 1.15rem;">📤 Upload File to this Folder</h3>
                </div>
                <div id="dropZone" style="border: 2px dashed var(--border-color); border-radius: 10px; padding: 20px; text-align: center; background: #0f172a; transition: all 0.2s ease;">
                    <p style="margin: 0 0 12px 0; color: var(--text-secondary); font-size: 0.95rem;">Drag & drop files here, or click choose file to upload</p>
                    <div style="display: flex; gap: 12px; align-items: center; justify-content: center; flex-wrap: wrap;">
                        <input type="file" id="uploadFileInput" class="file-input" style="max-width: 300px;" onchange="onFileSelected()" />
                        <button type="button" id="uploadBtn" onclick="startFileUpload()" class="btn btn-success" style="font-weight: 600;">Start Upload</button>
                    </div>
                </div>

                <div id="uploadProgressContainer" style="display: none; margin-top: 16px;">
                    <div style="display: flex; justify-content: space-between; font-size: 0.9rem; margin-bottom: 6px; font-weight: 500;">
                        <span id="uploadFileName" style="color: var(--text-primary); font-weight: 600; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; max-width: 70%;">File Name</span>
                        <span id="uploadPercent" style="color: var(--accent-color); font-weight: 700;">0%</span>
                    </div>
                    <div style="background: #0f172a; border-radius: 8px; height: 16px; width: 100%; overflow: hidden; border: 1px solid var(--border-color);">
                        <div id="uploadProgressBarFill" style="background: linear-gradient(90deg, #10b981, #38bdf8); width: 0%; height: 100%; transition: width 0.15s ease;"></div>
                    </div>
                    <div style="display: flex; justify-content: space-between; font-size: 0.8rem; color: var(--text-secondary); margin-top: 6px;">
                        <span id="uploadDetail">0 MB / 0 MB</span>
                        <span id="uploadSpeed">0 MB/s</span>
                    </div>
                </div>
            </div>
            """.trimIndent()
        } else {
            """<div class="badge badge-warning">🔒 Server is in Read-Only Mode</div>"""
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Wi-Fi Share - $rootFolderName</title>
                <style>
                    :root {
                        --bg-color: #0f172a;
                        --card-bg: #1e293b;
                        --text-primary: #f8fafc;
                        --text-secondary: #94a3b8;
                        --accent-color: #38bdf8;
                        --accent-hover: #0284c7;
                        --border-color: #334155;
                        --success-color: #10b981;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background-color: var(--bg-color);
                        color: var(--text-primary);
                        margin: 0;
                        padding: 20px;
                        line-height: 1.5;
                    }
                    .container {
                        max-width: 1000px;
                        margin: 0 auto;
                    }
                    header {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        padding-bottom: 16px;
                        border-bottom: 1px solid var(--border-color);
                        margin-bottom: 20px;
                    }
                    h1 {
                        font-size: 1.5rem;
                        margin: 0;
                        color: var(--accent-color);
                    }
                    .breadcrumbs {
                        background-color: var(--card-bg);
                        padding: 12px 18px;
                        border-radius: 8px;
                        margin-bottom: 20px;
                        font-size: 1.05rem;
                        border: 1px solid var(--border-color);
                    }
                    .breadcrumbs a {
                        color: var(--accent-color);
                        text-decoration: none;
                        font-weight: 500;
                    }
                    .breadcrumbs a:hover {
                        text-decoration: underline;
                    }
                    .sep {
                        color: var(--text-secondary);
                        margin: 0 6px;
                    }
                    .card {
                        background-color: var(--card-bg);
                        border-radius: 12px;
                        padding: 20px;
                        margin-bottom: 24px;
                        border: 1px solid var(--border-color);
                        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                    }
                    .search-bar {
                        width: 100%;
                        padding: 10px 14px;
                        border-radius: 6px;
                        border: 1px solid var(--border-color);
                        background-color: #0f172a;
                        color: #fff;
                        margin-bottom: 16px;
                        box-sizing: border-box;
                        font-size: 0.95rem;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        text-align: left;
                    }
                    th, td {
                        padding: 12px 14px;
                        border-bottom: 1px solid var(--border-color);
                    }
                    th {
                        color: var(--text-secondary);
                        font-weight: 600;
                        font-size: 0.85rem;
                        text-transform: uppercase;
                    }
                    tr:hover {
                        background-color: #26334d;
                    }
                    .item-link {
                        color: var(--text-primary);
                        text-decoration: none;
                        word-break: break-all;
                    }
                    .item-link:hover {
                        color: var(--accent-color);
                    }
                    .btn {
                        display: inline-block;
                        padding: 6px 12px;
                        border-radius: 6px;
                        font-size: 0.85rem;
                        font-weight: 500;
                        text-decoration: none;
                        background-color: #334155;
                        color: #fff;
                        border: none;
                        cursor: pointer;
                    }
                    .btn:hover {
                        background-color: #475569;
                    }
                    .btn-sm {
                        padding: 5px 11px;
                        font-size: 0.8rem;
                    }
                    .btn-primary {
                        background-color: var(--accent-color);
                        color: #0f172a;
                    }
                    .btn-primary:hover {
                        background-color: var(--accent-hover);
                        color: #fff;
                    }
                    .btn-success {
                        background-color: var(--success-color);
                        color: #fff;
                    }
                    .btn-success:hover {
                        background-color: #059669;
                        color: #fff;
                    }
                    .upload-card h3 {
                        margin-top: 0;
                        font-size: 1.1rem;
                    }
                    .upload-form {
                        display: flex;
                        gap: 12px;
                        align-items: center;
                        flex-wrap: wrap;
                    }
                    .file-input {
                        color: var(--text-secondary);
                    }
                    .badge {
                        display: inline-block;
                        padding: 4px 10px;
                        border-radius: 20px;
                        font-size: 0.8rem;
                        font-weight: 600;
                    }
                    .badge-warning {
                        background-color: #f59e0b;
                        color: #000;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>⚡ Wi-Fi Folder Share</h1>
                        <div><span class="badge" style="background-color: #1e293b; color: var(--accent-color); border: 1px solid var(--border-color);">Local Server Active</span></div>
                    </header>

                    <div class="breadcrumbs" style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px;">
                        <div>$breadcrumbsHtml</div>
                        <a href="$folderDownloadUrl" class="btn btn-sm btn-success" style="font-weight: 600;">⬇️ Download Folder (ZIP)</a>
                    </div>

                    <div class="card text-portal-card">
                        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                            <h3 style="margin: 0; font-size: 1.15rem; display: flex; align-items: center; gap: 8px;">💬 Realtime Text & Clipboard Portal</h3>
                            <span class="badge" style="background-color: #0284c7; color: #fff;">Live Sync Active</span>
                        </div>
                        <div style="display: flex; gap: 10px; margin-bottom: 16px;">
                            <textarea id="textInput" placeholder="Paste or type text, links, or notes to transfer in real time to phone..." style="flex: 1; min-height: 56px; padding: 10px; border-radius: 8px; border: 1px solid var(--border-color); background: #0f172a; color: #fff; resize: vertical; font-family: inherit; font-size: 0.95rem;"></textarea>
                            <button onclick="sendTextPortal()" class="btn btn-primary" style="height: auto; font-weight: 600; padding: 0 20px;">Send Text</button>
                        </div>
                        <div id="textSnippetsList" style="display: flex; flex-direction: column; gap: 10px; max-height: 250px; overflow-y: auto;">
                            <!-- Snippets loaded dynamically -->
                        </div>
                    </div>

                    $uploadSectionHtml

                    <div class="card">
                        <input type="text" id="searchInput" class="search-bar" placeholder="🔍 Search files in this directory..." onkeyup="filterFiles()" />
                        <table id="fileTable">
                            <thead>
                                <tr>
                                    <th>Name</th>
                                    <th>Type</th>
                                    <th>Size</th>
                                    <th>Modified</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                $rowsHtml
                            </tbody>
                        </table>
                    </div>
                </div>

                <script>
                    function filterFiles() {
                        const input = document.getElementById('searchInput').value.toLowerCase();
                        const rows = document.querySelectorAll('#fileTable tbody tr');
                        rows.forEach(row => {
                            const nameCell = row.cells[0];
                            if (nameCell) {
                                const text = nameCell.textContent.toLowerCase();
                                row.style.display = text.includes(input) ? '' : 'none';
                            }
                        });
                    }

                    async function fetchTextSnippets() {
                        try {
                            const res = await fetch('/api/text');
                            if (!res.ok) return;
                            const snippets = await res.json();
                            const container = document.getElementById('textSnippetsList');
                            if (!snippets || snippets.length === 0) {
                                container.innerHTML = '<div style="color: var(--text-secondary); font-size: 0.85rem; text-align: center; padding: 10px;">No text snippets shared yet. Type above to transfer text in real-time.</div>';
                                return;
                            }
                            container.innerHTML = snippets.map(function(item) {
                                var timeStr = new Date(item.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                                var safeContent = escapeHtml(item.content);
                                var jsonEscaped = escapeAttr(item.content);
                                return '<div style="background: #0f172a; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--border-color); display: flex; justify-content: space-between; align-items: flex-start; gap: 12px;">' +
                                    '<div style="flex: 1; overflow-wrap: anywhere;">' +
                                        '<div style="font-size: 0.75rem; color: var(--accent-color); margin-bottom: 4px; display: flex; gap: 8px;">' +
                                            '<strong>' + escapeHtml(item.sender) + '</strong>' +
                                            '<span style="color: var(--text-secondary);">' + timeStr + '</span>' +
                                        '</div>' +
                                        '<div style="white-space: pre-wrap; font-size: 0.95rem; line-height: 1.4;">' + safeContent + '</div>' +
                                    '</div>' +
                                    '<button onclick="copyToClipboard(\'' + jsonEscaped + '\', this)" class="btn btn-sm" style="white-space: nowrap;">📋 Copy</button>' +
                                '</div>';
                            }).join('');
                        } catch (e) {
                            console.error(e);
                        }
                    }

                    async function sendTextPortal() {
                        const input = document.getElementById('textInput');
                        const content = input.value.trim();
                        if (!content) return;
                        try {
                            await fetch('/api/text', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'text=' + encodeURIComponent(content)
                            });
                            input.value = '';
                            fetchTextSnippets();
                        } catch (e) {
                            alert('Failed to send text');
                        }
                    }

                    function copyToClipboard(text, btn) {
                        navigator.clipboard.writeText(text).then(() => {
                            const orig = btn.innerText;
                            btn.innerText = '✓ Copied!';
                            btn.style.backgroundColor = '#10b981';
                            setTimeout(() => {
                                btn.innerText = orig;
                                btn.style.backgroundColor = '';
                            }, 1500);
                        });
                    }

                    function escapeHtml(str) {
                        return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
                    }

                    function escapeAttr(str) {
                        return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;').replace(/\n/g, '\\n').replace(/\r/g, '');
                    }

                    let isUploading = false;

                    function onFileSelected() {
                        const input = document.getElementById('uploadFileInput');
                        if (input && input.files && input.files[0]) {
                            const fileNameText = document.getElementById('uploadFileName');
                            if (fileNameText) fileNameText.textContent = input.files[0].name;
                        }
                    }

                    function startFileUpload() {
                        if (isUploading) return;
                        const fileInput = document.getElementById('uploadFileInput');
                        if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
                            alert('Please select a file to upload first.');
                            return;
                        }

                        const file = fileInput.files[0];
                        const formData = new FormData();
                        formData.append('file', file);

                        const uploadBtn = document.getElementById('uploadBtn');
                        const progressContainer = document.getElementById('uploadProgressContainer');
                        const progressBarFill = document.getElementById('uploadProgressBarFill');
                        const percentText = document.getElementById('uploadPercent');
                        const detailText = document.getElementById('uploadDetail');
                        const speedText = document.getElementById('uploadSpeed');
                        const fileNameText = document.getElementById('uploadFileName');

                        isUploading = true;
                        if (uploadBtn) {
                            uploadBtn.disabled = true;
                            uploadBtn.textContent = 'Uploading...';
                        }
                        if (fileNameText) fileNameText.textContent = file.name;
                        if (progressContainer) progressContainer.style.display = 'block';
                        if (progressBarFill) progressBarFill.style.width = '0%';
                        if (percentText) percentText.textContent = '0%';
                        if (detailText) detailText.textContent = '0 MB / ' + (file.size / (1024 * 1024)).toFixed(2) + ' MB';
                        if (speedText) speedText.textContent = 'Starting...';

                        const startTime = Date.now();
                        let lastLoaded = 0;
                        let lastTime = startTime;

                        const xhr = new XMLHttpRequest();
                        const cleanDir = "$cleanPath";
                        xhr.open('POST', '/upload?dir=' + encodeURIComponent(cleanDir), true);
                        xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');

                        xhr.upload.addEventListener('progress', function(e) {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                if (progressBarFill) progressBarFill.style.width = percent + '%';
                                if (percentText) percentText.textContent = percent + '%';

                                const now = Date.now();
                                const loadedMb = (e.loaded / (1024 * 1024)).toFixed(2);
                                const totalMb = (e.total / (1024 * 1024)).toFixed(2);
                                if (detailText) detailText.textContent = loadedMb + ' MB / ' + totalMb + ' MB';

                                const timeDiff = (now - lastTime) / 1000;
                                if (timeDiff >= 0.3) {
                                    const bytesDiff = e.loaded - lastLoaded;
                                    const speedBytes = bytesDiff / timeDiff;
                                    const speedMb = (speedBytes / (1024 * 1024)).toFixed(2);
                                    if (speedText) speedText.textContent = speedMb + ' MB/s';
                                    lastLoaded = e.loaded;
                                    lastTime = now;
                                }
                            }
                        });

                        xhr.onload = function() {
                            isUploading = false;
                            if (xhr.status >= 200 && xhr.status < 400) {
                                if (progressBarFill) progressBarFill.style.width = '100%';
                                if (percentText) percentText.textContent = '100%';
                                if (speedText) speedText.textContent = '✅ Upload Complete!';
                                setTimeout(() => {
                                    window.location.reload();
                                }, 600);
                            } else {
                                alert('Upload failed: HTTP ' + xhr.status + '\n' + xhr.responseText);
                                if (uploadBtn) {
                                    uploadBtn.disabled = false;
                                    uploadBtn.textContent = 'Start Upload';
                                }
                                if (speedText) speedText.textContent = '❌ Failed';
                            }
                        };

                        xhr.onerror = function() {
                            isUploading = false;
                            alert('Upload failed due to network error.');
                            if (uploadBtn) {
                                uploadBtn.disabled = false;
                                uploadBtn.textContent = 'Start Upload';
                            }
                            if (speedText) speedText.textContent = '❌ Connection Error';
                        };

                        xhr.send(formData);
                    }

                    const dropZone = document.getElementById('dropZone');
                    if (dropZone) {
                        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
                            dropZone.addEventListener(eventName, e => {
                                e.preventDefault();
                                e.stopPropagation();
                            }, false);
                        });
                        ['dragenter', 'dragover'].forEach(eventName => {
                            dropZone.addEventListener(eventName, () => {
                                dropZone.style.borderColor = 'var(--accent-color)';
                                dropZone.style.background = '#1e293b';
                            }, false);
                        });
                        ['dragleave', 'drop'].forEach(eventName => {
                            dropZone.addEventListener(eventName, () => {
                                dropZone.style.borderColor = 'var(--border-color)';
                                dropZone.style.background = '#0f172a';
                            }, false);
                        });
                        dropZone.addEventListener('drop', e => {
                            const dt = e.dataTransfer;
                            const files = dt.files;
                            if (files && files.length > 0) {
                                const fileInput = document.getElementById('uploadFileInput');
                                if (fileInput) {
                                    fileInput.files = files;
                                    onFileSelected();
                                    startFileUpload();
                                }
                            }
                        }, false);
                    }

                    fetchTextSnippets();
                    setInterval(fetchTextSnippets, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun zipDirectory(
        fileHelper: FileProviderHelper,
        dirRelPath: String,
        basePath: String,
        zipOut: java.util.zip.ZipOutputStream
    ) {
        val items = fileHelper.listFiles(dirRelPath)
        if (items.isEmpty() && basePath.isNotEmpty()) {
            val zipEntry = java.util.zip.ZipEntry("$basePath/")
            zipOut.putNextEntry(zipEntry)
            zipOut.closeEntry()
            return
        }
        for (item in items) {
            val entryName = if (basePath.isEmpty()) item.name else "$basePath/${item.name}"
            if (item.isDirectory) {
                zipDirectory(fileHelper, item.relativePath, entryName, zipOut)
            } else {
                val zipEntry = java.util.zip.ZipEntry(entryName)
                zipOut.putNextEntry(zipEntry)
                fileHelper.getInputStream(item.relativePath)?.use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }

    private fun escapeHtmlKt(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;")
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun getFileIcon(isDirectory: Boolean, name: String): String {
        if (isDirectory) return "📁"
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "svg" -> "🖼️"
            "mp4", "mkv", "avi", "mov", "webm" -> "🎬"
            "mp3", "wav", "flac", "m4a", "ogg" -> "🎵"
            "pdf" -> "📄"
            "zip", "rar", "7z", "tar", "gz" -> "📦"
            "txt", "log", "md", "json", "xml" -> "📝"
            "apk" -> "📱"
            else -> "📄"
        }
    }

    private fun generateErrorHtml(message: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head><title>Error</title>
            <style>
                body { background: #0f172a; color: #fff; font-family: sans-serif; text-align: center; padding-top: 50px; }
                .card { background: #1e293b; display: inline-block; padding: 40px; border-radius: 12px; }
                a { color: #38bdf8; }
            </style>
            </head>
            <body>
                <div class="card">
                    <h2>⚠️ $message</h2>
                    <p><a href="/">Return to Root Folder</a></p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun log(clientIp: String, level: LogLevel, message: String) {
        serverScope.launch {
            logFlow.emit(
                ServerLog(
                    clientIp = clientIp,
                    protocol = LogProtocol.HTTP,
                    level = level,
                    message = message
                )
            )
        }
    }
}
