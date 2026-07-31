package com.example.model

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

enum class LogProtocol {
    FTP,
    HTTP,
    SYSTEM
}

data class ServerLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val clientIp: String,
    val protocol: LogProtocol,
    val level: LogLevel,
    val message: String
)
