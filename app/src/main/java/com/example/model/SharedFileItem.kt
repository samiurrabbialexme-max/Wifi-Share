package com.example.model

import android.net.Uri

data class SharedFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val uri: Uri? = null,
    val mimeType: String = "application/octet-stream"
)
