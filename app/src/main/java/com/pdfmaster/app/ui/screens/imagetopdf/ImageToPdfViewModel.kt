package com.pdfmaster.app.ui.screens.imagetopdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmaster.app.data.pdf.NativePdfDocumentGenerator
import com.pdfmaster.app.data.storage.StorageManager
import com.pdfmaster.app.domain.model.DefaultSaveFolder
import com.pdfmaster.app.domain.model.ImageItem
import com.pdfmaster.app.domain.model.LastEditedFileInfo
import com.pdfmaster.app.domain.model.PdfExportResult
import com.pdfmaster.app.domain.model.PdfSettings
import com.pdfmaster.app.domain.model.SaveDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImageToPdfUiState {
    data object Idle : ImageToPdfUiState
    data class Generating(val progress: Int, val message: String) : ImageToPdfUiState
    data class Success(val result: PdfExportResult) : ImageToPdfUiState
    data class Error(val message: String) : ImageToPdfUiState
}

class ImageToPdfViewModel(application: Application) : AndroidViewModel(application) {

    private val generator = NativePdfDocumentGenerator(application)
    private val storageManager = StorageManager(application)

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _settings = MutableStateFlow(
        PdfSettings(fileName = storageManager.generateDefaultFileName("PDF"))
    )
    val settings: StateFlow<PdfSettings> = _settings.asStateFlow()

    private val _saveDestination = MutableStateFlow(storageManager.getSaveDestination())
    val saveDestination: StateFlow<SaveDestination> = _saveDestination.asStateFlow()

    private val _uiState = MutableStateFlow<ImageToPdfUiState>(ImageToPdfUiState.Idle)
    val uiState: StateFlow<ImageToPdfUiState> = _uiState.asStateFlow()

    fun onImagesPicked(uris: List<Uri>) {
        val newItems = uris.map { uri ->
            ImageItem(
                uri = uri,
                name = uri.lastPathSegment ?: "Image"
            )
        }
        _images.update { current -> current + newItems }
    }

    fun removeImage(imageId: String) {
        _images.update { current -> current.filterNot { it.id == imageId } }
    }

    fun rotateImage(imageId: String) {
        _images.update { current ->
            current.map { item ->
                if (item.id == imageId) {
                    val nextAngle = (item.rotation + 90f) % 360f
                    item.copy(rotation = nextAngle)
                } else item
            }
        }
    }

    fun onImageCropped(imageId: String, croppedUri: Uri) {
        _images.update { current ->
            current.map { item ->
                if (item.id == imageId) item.copy(croppedUri = croppedUri) else item
            }
        }
    }

    fun moveImage(fromIndex: Int, toIndex: Int) {
        _images.update { list ->
            val mutable = list.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            mutable
        }
    }

    fun updateSettings(newSettings: PdfSettings) {
        _settings.value = newSettings
    }

    fun selectDefaultFolder(folder: DefaultSaveFolder) {
        _saveDestination.value = storageManager.setDefaultSaveFolder(folder)
    }

    fun selectCustomFolder(treeUri: Uri) {
        _saveDestination.value = storageManager.setCustomTreeFolder(treeUri)
    }

    fun refreshSuggestedFileNameIfBlank(): String {
        if (_settings.value.fileName.isBlank()) {
            _settings.update { it.copy(fileName = storageManager.generateDefaultFileName("PDF")) }
        }
        return _settings.value.fileName
    }

    fun generatePdf(customFileName: String? = null) {
        val currentImages = _images.value
        if (currentImages.isEmpty()) {
            _uiState.value = ImageToPdfUiState.Error("Додайте хоча б одне зображення")
            return
        }

        val resolvedName = (customFileName ?: _settings.value.fileName)
            .trim()
            .ifBlank { storageManager.generateDefaultFileName("PDF") }
        _settings.update { it.copy(fileName = resolvedName) }

        viewModelScope.launch {
            try {
                _uiState.value = ImageToPdfUiState.Generating(5, "Підготовка до генерації...")

                val tempFile = storageManager.createTempPdfFile()
                generator.generatePdf(
                    images = currentImages,
                    settings = _settings.value,
                    outputFile = tempFile,
                    onProgress = { progress, msg ->
                        _uiState.value = ImageToPdfUiState.Generating(progress, msg)
                    }
                )

                val (publicUri, folderDisplayName) = storageManager.savePdfToDestination(
                    tempFile = tempFile,
                    displayName = resolvedName,
                    destination = _saveDestination.value
                )
                val finalFileName = if (resolvedName.endsWith(".pdf", ignoreCase = true)) resolvedName else "$resolvedName.pdf"
                val fileSize = tempFile.length()

                storageManager.saveLastEditedFile(
                    LastEditedFileInfo(
                        fileName = finalFileName,
                        uriString = publicUri.toString(),
                        folderName = folderDisplayName,
                        pageCount = currentImages.size,
                        fileSizeBytes = fileSize,
                        updatedAt = System.currentTimeMillis(),
                        actionType = "Створено"
                    )
                )

                val exportResult = PdfExportResult(
                    uri = publicUri,
                    fileName = finalFileName,
                    fileSizeBytes = fileSize,
                    pageCount = currentImages.size,
                    folderName = folderDisplayName
                )
                _uiState.value = ImageToPdfUiState.Success(exportResult)
            } catch (e: Exception) {
                _uiState.value = ImageToPdfUiState.Error("Помилка генерації PDF: ${e.localizedMessage}")
            }
        }
    }

    fun resetState() {
        _uiState.value = ImageToPdfUiState.Idle
    }
}
