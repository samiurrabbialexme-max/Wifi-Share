package com.example.model

import android.net.Uri

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class ServerConfig(
    val folderUri: Uri? = null,
    val folderPath: String = "No folder selected",
    val folderName: String = "Select a folder",
    val ftpPort: Int = 2121,
    val webPort: Int = 8080,
    val isReadOnly: Boolean = false,
    val requireAuth: Boolean = false,
    val username: String = "admin",
    val password: String = "1234",
    val enableFtp: Boolean = true,
    val enableWeb: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoStopMinutes: Int = 0 // 0 = never
)
