package com.mangotree.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object UriToFile {
    /**
     * Attempts to resolve a content URI (from folder picker) to a real File path.
     * Works for external storage URIs on Android 26+.
     */
    fun fromUri(context: Context, uri: Uri): File? {
        return try {
            // Handle file:// URIs directly
            if (uri.scheme == "file") return File(uri.path ?: return null)

            // For content:// URIs from the folder picker (ACTION_OPEN_DOCUMENT_TREE)
            // We extract the real path from the URI
            val docUri = DocumentFile.fromTreeUri(context, uri) ?: return null
            val path = uri.path ?: return null

            // Extract path from URI like /tree/primary:MyFolder
            val treeDoc = path.substringAfter("/tree/", "")
            if (treeDoc.isEmpty()) return null

            val parts = treeDoc.split(":")
            val root = when (parts[0]) {
                "primary" -> android.os.Environment.getExternalStorageDirectory()
                else -> File("/storage/${parts[0]}")
            }
            val subPath = parts.getOrNull(1) ?: ""
            if (subPath.isEmpty()) root else File(root, subPath)
        } catch (e: Exception) {
            null
        }
    }
}
