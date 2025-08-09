package com.videoplayerapp.utils

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

object FilePathUtils {
    
    /**
     * Get real file path from URI
     */
    fun getRealPathFromURI(context: Context, uri: Uri): String? {
        return when {
            // Direct file path
            uri.scheme == "file" -> uri.path
            
            // Content URI
            uri.scheme == "content" -> {
                when {
                    // Document URI (API 19+)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri) -> {
                        getPathFromDocumentUri(context, uri)
                    }
                    // MediaStore URI
                    "com.android.providers.media.documents" == uri.authority -> {
                        getPathFromMediaDocumentUri(context, uri)
                    }
                    // External storage documents
                    "com.android.externalstorage.documents" == uri.authority -> {
                        getPathFromExternalStorageUri(uri)
                    }
                    // Downloads documents
                    "com.android.providers.downloads.documents" == uri.authority -> {
                        getPathFromDownloadsUri(context, uri)
                    }
                    // Third-party file managers and other content providers
                    else -> {
                        // Try to extract real path from third-party content providers
                        getPathFromThirdPartyProvider(context, uri) ?: getPathFromContentUri(context, uri)
                    }
                }
            }
            
            else -> null
        }
    }
    
    private fun getPathFromDocumentUri(context: Context, uri: Uri): String? {
        return try {
            when (uri.authority) {
                "com.android.externalstorage.documents" -> getPathFromExternalStorageUri(uri)
                "com.android.providers.downloads.documents" -> getPathFromDownloadsUri(context, uri)
                "com.android.providers.media.documents" -> getPathFromMediaDocumentUri(context, uri)
                else -> getPathFromContentUri(context, uri)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getPathFromExternalStorageUri(uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        val split = docId.split(":")
        
        if (split.size >= 2) {
            val type = split[0]
            val relativePath = split[1]
            
            return when (type) {
                "primary" -> "${Environment.getExternalStorageDirectory()}/$relativePath"
                "home" -> "${Environment.getExternalStorageDirectory()}/Documents/$relativePath"
                else -> "/storage/$type/$relativePath"
            }
        }
        
        return null
    }
    
    private fun getPathFromDownloadsUri(context: Context, uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        
        return when {
            docId.startsWith("raw:") -> docId.substring(4)
            docId.matches(Regex("\\d+")) -> {
                val downloadUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), 
                    docId.toLong()
                )
                getPathFromContentUri(context, downloadUri)
            }
            else -> {
                // Try to get path directly from downloads folder
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, docId)
                if (file.exists()) file.absolutePath else null
            }
        }
    }
    
    private fun getPathFromMediaDocumentUri(context: Context, uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        val split = docId.split(":")
        
        if (split.size >= 2) {
            val type = split[0]
            val id = split[1]
            
            val contentUri = when (type) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return null
            }
            
            val selection = "${MediaStore.MediaColumns._ID}=?"
            val selectionArgs = arrayOf(id)
            
            return getPathFromContentUri(context, contentUri, selection, selectionArgs)
        }
        
        return null
    }
    
    private fun getPathFromContentUri(
        context: Context, 
        uri: Uri, 
        selection: String? = null, 
        selectionArgs: Array<String>? = null
    ): String? {
        var cursor: Cursor? = null
        return try {
            val column = MediaStore.MediaColumns.DATA
            val projection = arrayOf(column)
            
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.let {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow(column)
                    it.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
    
    private fun getPathFromThirdPartyProvider(context: Context, uri: Uri): String? {
        // Handle common third-party file manager patterns
        val path = uri.path
        if (path != null) {
            // Try to extract real path from URI structure
            when {
                // Explorer and similar apps often have patterns like:
                // content://com.speedsoftware.explorer.fileprovider/root/storage/emulated/0/...
                path.startsWith("/root/storage/") -> {
                    return path.removePrefix("/root")
                }
                path.startsWith("/storage/") -> {
                    return path
                }
                path.startsWith("/external_files/") -> {
                    return "${Environment.getExternalStorageDirectory()}${path.removePrefix("/external_files")}"
                }
                path.startsWith("/my_images/") -> {
                    return "${Environment.getExternalStorageDirectory()}/Pictures${path.removePrefix("/my_images")}"
                }
                path.startsWith("/my_videos/") -> {
                    return "${Environment.getExternalStorageDirectory()}/Movies${path.removePrefix("/my_videos")}"
                }
                path.startsWith("/downloads/") -> {
                    return "${Environment.getExternalStorageDirectory()}/Download${path.removePrefix("/downloads")}"
                }
                path.contains("/storage/emulated/") -> {
                    // Find and extract the storage path
                    val storageIndex = path.indexOf("/storage/emulated/")
                    if (storageIndex >= 0) {
                        return path.substring(storageIndex)
                    }
                }
            }
            
            // Try to decode URL-encoded path
            try {
                val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                if (decodedPath.startsWith("/storage/")) {
                    return decodedPath
                }
                if (decodedPath.contains("/storage/emulated/")) {
                    val storageIndex = decodedPath.indexOf("/storage/emulated/")
                    if (storageIndex >= 0) {
                        return decodedPath.substring(storageIndex)
                    }
                }
            } catch (e: Exception) {
                // URL decode failed, continue with other methods
            }
        }
        
        return null
    }
    
    /**
     * Get file display name from URI
     */
    fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(
                uri, 
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), 
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            // Extract from path as fallback
            getDisplayNameFromPath(uri)
        }
    }
    
    private fun getDisplayNameFromPath(uri: Uri): String? {
        val path = uri.path
        if (path != null) {
            // Try to get filename from path
            val fileName = path.substring(path.lastIndexOf('/') + 1)
            if (fileName.isNotEmpty()) {
                // Decode URL-encoded filename
                return try {
                    java.net.URLDecoder.decode(fileName, "UTF-8")
                } catch (e: Exception) {
                    fileName
                }
            }
        }
        
        // Try to extract from URI string as last resort
        val uriString = uri.toString()
        val lastSlash = uriString.lastIndexOf('/')
        if (lastSlash >= 0 && lastSlash < uriString.length - 1) {
            val fileName = uriString.substring(lastSlash + 1)
            return try {
                java.net.URLDecoder.decode(fileName, "UTF-8")
            } catch (e: Exception) {
                fileName
            }
        }
        
        return null
    }
    
    /**
     * Get file size from URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            val cursor = context.contentResolver.query(
                uri, 
                arrayOf(android.provider.OpenableColumns.SIZE), 
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getLong(0)
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Check if file exists at the given path
     */
    fun fileExists(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return try {
            val file = File(path)
            file.exists() && file.canRead()
        } catch (e: Exception) {
            false
        }
    }
}