package com.example.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.model.SharedFileItem
import java.io.InputStream
import java.io.OutputStream

class FileProviderHelper(
    private val context: Context,
    private val rootUri: Uri?
) {

    private val rootDocument: DocumentFile? = rootUri?.let {
        DocumentFile.fromTreeUri(context, it)
    }

    val isAvailable: Boolean
        get() = rootDocument != null && rootDocument.exists() && rootDocument.canRead()

    val rootName: String
        get() = rootDocument?.name ?: "Shared Folder"

    /**
     * Resolves a relative path (e.g. "subfolder/file.txt") to a DocumentFile.
     */
    fun resolveDocument(relativePath: String): DocumentFile? {
        val root = rootDocument ?: return null
        val cleanPath = relativePath.trim('/').trim()
        if (cleanPath.isEmpty()) return root

        val segments = cleanPath.split('/').filter { it.isNotEmpty() }
        var current: DocumentFile = root

        for (segment in segments) {
            if (segment == ".") continue
            if (segment == "..") {
                current = current.parentFile ?: current
                continue
            }
            val child = current.findFile(segment) ?: return null
            current = child
        }
        return current
    }

    /**
     * Lists files and folders inside a given relative directory path.
     */
    fun listFiles(relativeDirPath: String = ""): List<SharedFileItem> {
        val dirDoc = resolveDocument(relativeDirPath) ?: return emptyList()
        if (!dirDoc.isDirectory) return emptyList()

        val items = mutableListOf<SharedFileItem>()
        val children = dirDoc.listFiles()

        val cleanParentPath = relativeDirPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }

        for (doc in children) {
            val name = doc.name ?: continue
            val relPath = "$cleanParentPath$name"
            items.add(
                SharedFileItem(
                    name = name,
                    relativePath = relPath,
                    isDirectory = doc.isDirectory,
                    sizeBytes = if (doc.isDirectory) 0L else doc.length(),
                    lastModified = doc.lastModified(),
                    uri = doc.uri,
                    mimeType = doc.type ?: NetworkUtils.getMimeType(name)
                )
            )
        }

        // Sort: directories first, then alphabetical
        return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Reads file stream for a given relative file path.
     */
    fun getInputStream(relativePath: String): InputStream? {
        val doc = resolveDocument(relativePath) ?: return null
        if (!doc.isFile) return null
        return try {
            context.contentResolver.openInputStream(doc.uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates or overwrites a file at relativePath for writing.
     */
    fun getOutputStream(relativePath: String, mimeType: String = "application/octet-stream"): OutputStream? {
        val cleanPath = relativePath.trim('/')
        val parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else ""
        val fileName = if (cleanPath.contains('/')) cleanPath.substringAfterLast('/') else cleanPath

        val parentDoc = resolveDocument(parentPath) ?: return null
        if (!parentDoc.isDirectory) return null

        var existingFile = parentDoc.findFile(fileName)
        if (existingFile == null) {
            existingFile = parentDoc.createFile(mimeType, fileName)
        }

        val targetUri = existingFile?.uri ?: return null
        return try {
            context.contentResolver.openOutputStream(targetUri, "rwt")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a directory at parent relative path.
     */
    fun createDirectory(parentRelativePath: String, dirName: String): Boolean {
        val parentDoc = resolveDocument(parentRelativePath) ?: return false
        if (!parentDoc.isDirectory) return false
        return parentDoc.createDirectory(dirName) != null
    }

    /**
     * Deletes a file or directory at relativePath.
     */
    fun deleteFile(relativePath: String): Boolean {
        val doc = resolveDocument(relativePath) ?: return false
        return try {
            doc.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Gets total recursive size and file count of root folder.
     */
    fun getFolderStats(): Pair<Int, Long> {
        val root = rootDocument ?: return Pair(0, 0L)
        var totalCount = 0
        var totalBytes = 0L

        fun scan(doc: DocumentFile) {
            val children = doc.listFiles()
            for (child in children) {
                if (child.isDirectory) {
                    scan(child)
                } else {
                    totalCount++
                    totalBytes += child.length()
                }
            }
        }

        try {
            scan(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Pair(totalCount, totalBytes)
    }
}
