package com.pdfmaster.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.pdfmaster.app.domain.model.DefaultSaveFolder
import com.pdfmaster.app.domain.model.LastEditedFileInfo
import com.pdfmaster.app.domain.model.SaveDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Керує збереженням та експортом PDF у Scoped Storage (Android 10-15+)
 * Підтримує стандартні папки MediaStore (Downloads / Documents) та довільну теку через SAF (OpenDocumentTree),
 * а також зберігає інформацію про останній оброблений файл.
 */
class StorageManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("pdf_master_prefs", Context.MODE_PRIVATE)

    fun createTempPdfFile(prefix: String = "pdf_master_"): File {
        val storageDir = File(context.cacheDir, "generated_pdfs").apply { mkdirs() }
        return File.createTempFile(prefix, ".pdf", storageDir)
    }

    fun generateDefaultFileName(prefix: String = "PDF"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        return "${prefix}_$stamp"
    }

    fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun getSaveDestination(): SaveDestination {
        val treeUri = prefs.getString("save_tree_uri", null)
        val folderKey = prefs.getString("save_default_folder", DefaultSaveFolder.DOWNLOADS_PDFMASTER.name)
        val defaultFolder = runCatching {
            DefaultSaveFolder.valueOf(folderKey ?: DefaultSaveFolder.DOWNLOADS_PDFMASTER.name)
        }.getOrDefault(DefaultSaveFolder.DOWNLOADS_PDFMASTER)
        val customName = prefs.getString("save_folder_name", null)
        return SaveDestination(
            displayName = if (treeUri != null && !customName.isNullOrBlank()) customName else defaultFolder.displayName,
            treeUriString = treeUri,
            defaultFolder = defaultFolder
        )
    }

    fun setDefaultSaveFolder(folder: DefaultSaveFolder): SaveDestination {
        prefs.edit()
            .remove("save_tree_uri")
            .remove("save_folder_name")
            .putString("save_default_folder", folder.name)
            .apply()
        return getSaveDestination()
    }

    fun setCustomTreeFolder(treeUri: Uri): SaveDestination {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
            // Дозвіл може бути тимчасовим на деяких провайдерах
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: ""
        val cleanName = docId.substringAfter(':', docId).ifBlank { "Обрана тека" }
        prefs.edit()
            .putString("save_tree_uri", treeUri.toString())
            .putString("save_folder_name", cleanName)
            .apply()
        return getSaveDestination()
    }

    fun saveLastEditedFile(info: LastEditedFileInfo) {
        prefs.edit()
            .putString("last_file_name", info.fileName)
            .putString("last_file_uri", info.uriString)
            .putString("last_file_folder", info.folderName)
            .putInt("last_file_pages", info.pageCount)
            .putLong("last_file_size", info.fileSizeBytes)
            .putLong("last_file_time", info.updatedAt)
            .putString("last_file_action", info.actionType)
            .apply()
    }

    fun getLastEditedFile(): LastEditedFileInfo? {
        val name = prefs.getString("last_file_name", null) ?: return null
        val uri = prefs.getString("last_file_uri", null) ?: return null
        return LastEditedFileInfo(
            fileName = name,
            uriString = uri,
            folderName = prefs.getString("last_file_folder", DefaultSaveFolder.DOWNLOADS_PDFMASTER.displayName)
                ?: DefaultSaveFolder.DOWNLOADS_PDFMASTER.displayName,
            pageCount = prefs.getInt("last_file_pages", 1),
            fileSizeBytes = prefs.getLong("last_file_size", 0L),
            updatedAt = prefs.getLong("last_file_time", System.currentTimeMillis()),
            actionType = prefs.getString("last_file_action", "Відредаговано") ?: "Відредаговано"
        )
    }

    /**
     * Зберігає PDF у вибрану користувачем теку (SAF Tree URI) або у MediaStore (Downloads / Documents)
     */
    suspend fun savePdfToDestination(
        tempFile: File,
        displayName: String,
        destination: SaveDestination = getSaveDestination()
    ): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val baseClean = displayName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { generateDefaultFileName("PDF") }
        val sanitizedName = if (baseClean.endsWith(".pdf", ignoreCase = true)) baseClean else "$baseClean.pdf"

        // 1. Якщо користувач обрав довільну теку через SAF (OpenDocumentTree)
        val treeStr = destination.treeUriString
        if (!treeStr.isNullOrBlank()) {
            val treeUri = Uri.parse(treeStr)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/pdf",
                sanitizedName
            ) ?: throw IllegalStateException("Не вдалося створити файл у вибраній теці")

            context.contentResolver.openOutputStream(createdUri)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException("Не вдалося записати дані в обрану теку")

            return@withContext Pair(createdUri, destination.displayName)
        }

        // 2. Інакше зберігаємо через MediaStore у Downloads або Documents
        val folder = destination.defaultFolder
        val isDocuments = folder == DefaultSaveFolder.DOCUMENTS || folder == DefaultSaveFolder.DOCUMENTS_PDFMASTER
        val rootDir = if (isDocuments) Environment.DIRECTORY_DOCUMENTS else Environment.DIRECTORY_DOWNLOADS
        val relPath = if (folder.subPath.isNotEmpty()) "$rootDir/${folder.subPath}" else rootDir

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isDocuments) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Не вдалося створити запис у MediaStore")

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(tempFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("Не вдалося відкрити OutputStream для: $uri")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }

        Pair(uri, folder.displayName)
    }
}
