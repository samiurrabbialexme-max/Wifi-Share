package com.example.server

import android.content.Context
import com.example.model.LogLevel
import com.example.model.LogProtocol
import com.example.model.ServerConfig
import com.example.model.ServerLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FtpServer(
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
                bind(InetSocketAddress(config.ftpPort))
            }
            isRunning = true
            log("System", LogLevel.INFO, "FTP Server started on port ${config.ftpPort}")

            serverScope.launch {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        activeClients.incrementAndGet()
                        launch {
                            try {
                                handleClient(socket)
                            } catch (e: Exception) {
                                log("System", LogLevel.ERROR, "FTP Client error: ${e.localizedMessage}")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            log("System", LogLevel.ERROR, "FTP Accept error: ${e.localizedMessage}")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            log("System", LogLevel.ERROR, "Failed to bind FTP port ${config.ftpPort}: ${e.localizedMessage}")
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
        log("System", LogLevel.INFO, "FTP Server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        log(clientIp, LogLevel.INFO, "New FTP connection")

        val fileHelper = FileProviderHelper(context, config.folderUri)

        try {
            socket.soTimeout = 60000 // 60s timeout
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            fun sendReply(code: Int, text: String) {
                try {
                    writer.write("$code $text\r\n")
                    writer.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            sendReply(220, "Wi-Fi Share FTP Server Ready")

            var currentDir = "" // relative path inside shared root
            var userLogged = !config.requireAuth
            var pasvServer: ServerSocket? = null
            var dataType = "I" // "A" = ASCII, "I" = Binary

            while (isRunning && !socket.isClosed) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val parts = trimmed.split(" ", limit = 2)
                val cmd = parts[0].uppercase(Locale.ENGLISH)
                val arg = if (parts.size > 1) parts[1] else ""

                when (cmd) {
                    "USER" -> {
                        if (!config.requireAuth || arg == config.username) {
                            sendReply(331, "User name okay, need password")
                        } else {
                            sendReply(331, "User name okay")
                        }
                    }

                    "PASS" -> {
                        if (!config.requireAuth || arg == config.password) {
                            userLogged = true
                            log(clientIp, LogLevel.SUCCESS, "Authenticated successfully")
                            sendReply(230, "User logged in, proceed")
                        } else {
                            log(clientIp, LogLevel.WARNING, "Authentication failed for password")
                            sendReply(530, "Not logged in, incorrect password")
                        }
                    }

                    "SYST" -> sendReply(215, "UNIX Type: L8")
                    "FEAT" -> {
                        writer.write("211-Features:\r\n UTF8\r\n PASV\r\n EPSV\r\n SIZE\r\n MLSD\r\n211 End\r\n")
                        writer.flush()
                    }

                    "OPTS" -> {
                        if (arg.uppercase().startsWith("UTF8")) {
                            sendReply(200, "UTF8 set to ON")
                        } else {
                            sendReply(200, "OK")
                        }
                    }

                    "NOOP" -> sendReply(200, "OK")
                    "TYPE" -> {
                        dataType = arg.uppercase()
                        sendReply(200, "Type set to $arg")
                    }

                    "PWD" -> {
                        val displayPath = if (currentDir.isEmpty()) "/" else "/$currentDir"
                        sendReply(257, "\"$displayPath\" is current directory")
                    }

                    "CWD" -> {
                        if (!userLogged) {
                            sendReply(530, "Please log in first")
                            continue
                        }
                        val newDir = resolvePath(currentDir, arg)
                        val doc = fileHelper.resolveDocument(newDir)
                        if (doc != null && doc.isDirectory) {
                            currentDir = newDir
                            log(clientIp, LogLevel.INFO, "CWD $currentDir")
                            sendReply(250, "Directory successfully changed to \"/$currentDir\"")
                        } else {
                            sendReply(550, "Failed to change directory")
                        }
                    }

                    "CDUP" -> {
                        if (currentDir.contains('/')) {
                            currentDir = currentDir.substringBeforeLast('/')
                        } else {
                            currentDir = ""
                        }
                        sendReply(200, "Directory changed to \"/$currentDir\"")
                    }

                    "PASV" -> {
                        try {
                            pasvServer?.close()
                            pasvServer = ServerSocket(0)
                            val localIp = socket.localAddress.hostAddress ?: NetworkUtils.getWifiIpAddress(context) ?: "127.0.0.1"
                            val ipParts = localIp.split(".").map { it.toInt() }
                            val port = pasvServer!!.localPort
                            val p1 = port shr 8
                            val p2 = port and 0xff
                            val pasvResp = "${ipParts[0]},${ipParts[1]},${ipParts[2]},${ipParts[3]},$p1,$p2"
                            sendReply(227, "Entering Passive Mode ($pasvResp)")
                        } catch (e: Exception) {
                            sendReply(425, "Cannot open passive connection")
                        }
                    }

                    "EPSV" -> {
                        try {
                            pasvServer?.close()
                            pasvServer = ServerSocket(0)
                            val port = pasvServer!!.localPort
                            sendReply(229, "Entering Extended Passive Mode (|||$port|)")
                        } catch (e: Exception) {
                            sendReply(425, "Cannot open passive connection")
                        }
                    }

                    "LIST", "NLST", "MLSD" -> {
                        if (!userLogged) {
                            sendReply(530, "Please log in first")
                            continue
                        }
                        sendReply(150, "Here comes the directory listing")
                        val dataSocket = acceptDataSocket(pasvServer)
                        if (dataSocket != null) {
                            try {
                                val dataWriter = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream()))
                                val items = fileHelper.listFiles(currentDir)
                                val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH)

                                for (item in items) {
                                    if (cmd == "MLSD") {
                                        val type = if (item.isDirectory) "dir" else "file"
                                        val size = item.sizeBytes
                                        val modify = SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH).format(Date(item.lastModified))
                                        dataWriter.write("type=$type;size=$size;modify=$modify; ${item.name}\r\n")
                                    } else {
                                        // Standard Unix LIST format
                                        val permissions = if (item.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                                        val dateStr = sdf.format(Date(item.lastModified))
                                        dataWriter.write("$permissions 1 owner group ${item.sizeBytes} $dateStr ${item.name}\r\n")
                                    }
                                }
                                dataWriter.flush()
                                dataSocket.close()
                                sendReply(226, "Directory send OK")
                            } catch (e: Exception) {
                                sendReply(426, "Connection closed; transfer aborted")
                            } finally {
                                dataSocket.close()
                            }
                        } else {
                            sendReply(425, "Can't open data connection")
                        }
                    }

                    "SIZE" -> {
                        val relPath = resolvePath(currentDir, arg)
                        val doc = fileHelper.resolveDocument(relPath)
                        if (doc != null && doc.isFile) {
                            sendReply(213, "${doc.length()}")
                        } else {
                            sendReply(550, "Could not get file size")
                        }
                    }

                    "RETR" -> {
                        if (!userLogged) {
                            sendReply(530, "Please log in first")
                            continue
                        }
                        val relPath = resolvePath(currentDir, arg)
                        val inputStream = fileHelper.getInputStream(relPath)
                        if (inputStream != null) {
                            sendReply(150, "Opening binary mode data connection for $arg")
                            val dataSocket = acceptDataSocket(pasvServer)
                            if (dataSocket != null) {
                                try {
                                    log(clientIp, LogLevel.INFO, "Downloading: $arg")
                                    val out = dataSocket.getOutputStream()
                                    inputStream.use { input ->
                                        input.copyTo(out)
                                    }
                                    out.flush()
                                    dataSocket.close()
                                    log(clientIp, LogLevel.SUCCESS, "Downloaded: $arg")
                                    sendReply(226, "Transfer complete")
                                } catch (e: Exception) {
                                    sendReply(426, "Transfer failed")
                                } finally {
                                    dataSocket.close()
                                }
                            } else {
                                inputStream.close()
                                sendReply(425, "Can't open data connection")
                            }
                        } else {
                            sendReply(550, "File not found or unreadable")
                        }
                    }

                    "STOR" -> {
                        if (!userLogged) {
                            sendReply(530, "Please log in first")
                            continue
                        }
                        if (config.isReadOnly) {
                            sendReply(550, "Server is in read-only mode")
                            continue
                        }
                        val relPath = resolvePath(currentDir, arg)
                        val outputStream = fileHelper.getOutputStream(relPath)
                        if (outputStream != null) {
                            sendReply(150, "Ok to send data")
                            val dataSocket = acceptDataSocket(pasvServer)
                            if (dataSocket != null) {
                                try {
                                    log(clientIp, LogLevel.INFO, "Uploading: $arg")
                                    val input = dataSocket.getInputStream()
                                    outputStream.use { out ->
                                        input.copyTo(out)
                                    }
                                    outputStream.flush()
                                    dataSocket.close()
                                    log(clientIp, LogLevel.SUCCESS, "Uploaded: $arg")
                                    sendReply(226, "Transfer complete")
                                } catch (e: Exception) {
                                    sendReply(426, "Upload failed")
                                } finally {
                                    dataSocket.close()
                                }
                            } else {
                                outputStream.close()
                                sendReply(425, "Can't open data connection")
                            }
                        } else {
                            sendReply(550, "Cannot create file")
                        }
                    }

                    "DELE" -> {
                        if (config.isReadOnly) {
                            sendReply(550, "Server is in read-only mode")
                            continue
                        }
                        val relPath = resolvePath(currentDir, arg)
                        if (fileHelper.deleteFile(relPath)) {
                            log(clientIp, LogLevel.SUCCESS, "Deleted: $arg")
                            sendReply(250, "File deleted")
                        } else {
                            sendReply(550, "Delete operation failed")
                        }
                    }

                    "MKD" -> {
                        if (config.isReadOnly) {
                            sendReply(550, "Server is in read-only mode")
                            continue
                        }
                        if (fileHelper.createDirectory(currentDir, arg)) {
                            log(clientIp, LogLevel.SUCCESS, "Created directory: $arg")
                            sendReply(257, "\"$arg\" directory created")
                        } else {
                            sendReply(550, "Create directory failed")
                        }
                    }

                    "QUIT" -> {
                        sendReply(221, "Goodbye")
                        break
                    }

                    else -> {
                        sendReply(502, "Command not implemented")
                    }
                }
            }
            pasvServer?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
            activeClients.decrementAndGet()
            log(clientIp, LogLevel.INFO, "FTP Client disconnected")
        }
    }

    private suspend fun acceptDataSocket(pasvServer: ServerSocket?): Socket? {
        if (pasvServer == null) return null
        return withContext(Dispatchers.IO) {
            try {
                pasvServer.soTimeout = 15000
                pasvServer.accept()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun resolvePath(current: String, target: String): String {
        val cleanTarget = target.trim()
        if (cleanTarget.startsWith("/")) {
            return cleanTarget.trim('/')
        }
        if (current.isEmpty()) return cleanTarget.trim('/')
        if (cleanTarget.isEmpty()) return current
        return "$current/${cleanTarget.trim('/')}".trim('/')
    }

    private fun log(clientIp: String, level: LogLevel, message: String) {
        serverScope.launch {
            logFlow.emit(
                ServerLog(
                    clientIp = clientIp,
                    protocol = LogProtocol.FTP,
                    level = level,
                    message = message
                )
            )
        }
    }
}
