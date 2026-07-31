package com.example.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.*
import com.example.server.FileProviderHelper
import com.example.server.NetworkUtils
import com.example.service.FtpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val _config = MutableStateFlow(ServerConfig())
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _activeClients = MutableStateFlow(0)
    val activeClients: StateFlow<Int> = _activeClients.asStateFlow()

    private val _logs = MutableStateFlow<List<ServerLog>>(emptyList())
    val logs: StateFlow<List<ServerLog>> = _logs.asStateFlow()

    private val _wifiIp = MutableStateFlow<String?>(null)
    val wifiIp: StateFlow<String?> = _wifiIp.asStateFlow()

    private val _wifiSsid = MutableStateFlow("Wi-Fi Network")
    val wifiSsid: StateFlow<String> = _wifiSsid.asStateFlow()

    private val _folderFileCount = MutableStateFlow(0)
    val folderFileCount: StateFlow<Int> = _folderFileCount.asStateFlow()

    private val _folderTotalSize = MutableStateFlow(0L)
    val folderTotalSize: StateFlow<Long> = _folderTotalSize.asStateFlow()

    private val _folderContents = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val folderContents: StateFlow<List<SharedFileItem>> = _folderContents.asStateFlow()

    val textSnippets: StateFlow<List<TextSnippet>> = TextTransferRepository.snippets

    fun sendTextSnippet(content: String, sender: String = "Phone") {
        if (content.isNotBlank()) {
            TextTransferRepository.addSnippet(content, sender)
        }
    }

    fun deleteTextSnippet(id: String) {
        TextTransferRepository.deleteSnippet(id)
    }

    fun clearAllTextSnippets() {
        TextTransferRepository.clearAll()
    }

    fun getClipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        return clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Portal Text", text)
        clipboard?.setPrimaryClip(clip)
    }

    private var boundService: FtpServerService? = null
    private var isBound = false
    private var pendingStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FtpServerService.LocalBinder
            val srv = binder.getService()
            boundService = srv
            isBound = true

            // Observe service flows
            viewModelScope.launch {
                srv.serverStatus.collect { _status.value = it }
            }
            viewModelScope.launch {
                srv.activeClientsCount.collect { _activeClients.value = it }
            }
            viewModelScope.launch {
                srv.logFlow.collect { newLog ->
                    _logs.update { (listOf(newLog) + it).take(300) }
                }
            }

            if (pendingStart) {
                pendingStart = false
                srv.startServer(_config.value)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    init {
        refreshNetworkInfo()
        loadSavedConfig()
    }

    fun refreshNetworkInfo() {
        _wifiIp.value = NetworkUtils.getWifiIpAddress(context)
        _wifiSsid.value = NetworkUtils.getWifiSsid(context)
    }

    private fun loadSavedConfig() {
        val prefs = context.getSharedPreferences("wifi_share_prefs", Context.MODE_PRIVATE)
        val savedUriStr = prefs.getString("folder_uri", null)
        val savedFolderName = prefs.getString("folder_name", "Select a folder")
        val savedFolderPath = prefs.getString("folder_path", "No folder selected")
        val ftpPort = prefs.getInt("ftp_port", 2121)
        val webPort = prefs.getInt("web_port", 8080)
        val isReadOnly = prefs.getBoolean("is_read_only", false)
        val requireAuth = prefs.getBoolean("require_auth", false)
        val user = prefs.getString("username", "admin") ?: "admin"
        val pass = prefs.getString("password", "1234") ?: "1234"

        val uri = savedUriStr?.let { Uri.parse(it) }

        _config.update {
            it.copy(
                folderUri = uri,
                folderName = savedFolderName ?: "Select a folder",
                folderPath = savedFolderPath ?: "No folder selected",
                ftpPort = ftpPort,
                webPort = webPort,
                isReadOnly = isReadOnly,
                requireAuth = requireAuth,
                username = user,
                password = pass
            )
        }

        if (uri != null) {
            updateFolderStats(uri)
        }
    }

    fun setFolderUri(uri: Uri) {
        try {
            // Take persistent permission
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val doc = DocumentFile.fromTreeUri(context, uri)
        val folderName = doc?.name ?: "Shared Folder"
        val folderPath = doc?.uri?.path ?: uri.toString()

        _config.update {
            it.copy(
                folderUri = uri,
                folderName = folderName,
                folderPath = folderPath
            )
        }

        // Save in SharedPreferences
        context.getSharedPreferences("wifi_share_prefs", Context.MODE_PRIVATE).edit()
            .putString("folder_uri", uri.toString())
            .putString("folder_name", folderName)
            .putString("folder_path", folderPath)
            .apply()

        updateFolderStats(uri)
    }

    private fun updateFolderStats(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = FileProviderHelper(context, uri)
            val (count, size) = helper.getFolderStats()
            val items = helper.listFiles("")

            _folderFileCount.value = count
            _folderTotalSize.value = size
            _folderContents.value = items
        }
    }

    fun updateConfig(
        ftpPort: Int = _config.value.ftpPort,
        webPort: Int = _config.value.webPort,
        isReadOnly: Boolean = _config.value.isReadOnly,
        requireAuth: Boolean = _config.value.requireAuth,
        username: String = _config.value.username,
        password: String = _config.value.password,
        enableFtp: Boolean = _config.value.enableFtp,
        enableWeb: Boolean = _config.value.enableWeb,
        keepScreenOn: Boolean = _config.value.keepScreenOn
    ) {
        _config.update {
            it.copy(
                ftpPort = ftpPort,
                webPort = webPort,
                isReadOnly = isReadOnly,
                requireAuth = requireAuth,
                username = username,
                password = password,
                enableFtp = enableFtp,
                enableWeb = enableWeb,
                keepScreenOn = keepScreenOn
            )
        }

        // Save preferences
        context.getSharedPreferences("wifi_share_prefs", Context.MODE_PRIVATE).edit()
            .putInt("ftp_port", ftpPort)
            .putInt("web_port", webPort)
            .putBoolean("is_read_only", isReadOnly)
            .putBoolean("require_auth", requireAuth)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun toggleServer() {
        refreshNetworkInfo()
        if (_status.value == ServerStatus.RUNNING) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        if (_config.value.folderUri == null) {
            _logs.update {
                listOf(
                    ServerLog(
                        clientIp = "System",
                        protocol = LogProtocol.SYSTEM,
                        level = LogLevel.ERROR,
                        message = "Cannot start server: Please select a folder first"
                    )
                ) + it
            }
            return
        }

        _status.value = ServerStatus.STARTING
        pendingStart = true

        val intent = Intent(context, FtpServerService::class.java)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        if (isBound && boundService != null) {
            pendingStart = false
            boundService?.startServer(_config.value)
        } else {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopServer() {
        pendingStart = false
        boundService?.stopServer()
        boundService = null
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        val intent = Intent(context, FtpServerService::class.java)
        context.stopService(intent)
        _status.value = ServerStatus.STOPPED
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun getWebUrl(): String {
        val ip = _wifiIp.value ?: "127.0.0.1"
        return "http://$ip:${_config.value.webPort}"
    }

    fun getFtpUrl(): String {
        val ip = _wifiIp.value ?: "127.0.0.1"
        return "ftp://$ip:${_config.value.ftpPort}"
    }

    override fun onCleared() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onCleared()
    }
}
