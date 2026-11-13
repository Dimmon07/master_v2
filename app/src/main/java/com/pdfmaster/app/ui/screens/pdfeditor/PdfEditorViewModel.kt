package com.pdfmaster.app.ui.screens.pdfeditor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmaster.app.data.pdf.AndroidPdfRendererManager
import com.pdfmaster.app.data.pdf.PdfBoxEditor
import com.pdfmaster.app.data.storage.StorageManager
import com.pdfmaster.app.domain.model.DefaultSaveFolder
import com.pdfmaster.app.domain.model.LastEditedFileInfo
import com.pdfmaster.app.domain.model.PdfExportResult
import com.pdfmaster.app.domain.model.PdfPageItem
import com.pdfmaster.app.domain.model.SaveDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PdfEditorUiState {
    data object NoFileLoaded : PdfEditorUiState
    data object Loading : PdfEditorUiState
    data class Ready(val pages: List<PdfPageItem>) : PdfEditorUiState
    data class Saving(val progress: Int, val message: String) : PdfEditorUiState
    data class Success(val result: PdfExportResult) : PdfEditorUiState
    data class Error(val message: String, val previousPages: List<PdfPageItem>? = null) : PdfEditorUiState
}

class PdfEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val rendererManager = AndroidPdfRendererManager(application)
    private val pdfEditor = PdfBoxEditor(application)
    private val storageManager = StorageManager(application)

    private val _uiState = MutableStateFlow<PdfEditorUiState>(PdfEditorUiState.NoFileLoaded)
    val uiState: StateFlow<PdfEditorUiState> = _uiState.asStateFlow()

    private val _saveDestination = MutableStateFlow(storageManager.getSaveDestination())
    val saveDestination: StateFlow<SaveDestination> = _saveDestination.asStateFlow()

    private val _suggestedFileName = MutableStateFlow(storageManager.generateDefaultFileName("Edited"))
    val suggestedFileName: StateFlow<String> = _suggestedFileName.asStateFlow()

    private var currentPdfUri: Uri? = null
    private val additionalMergeUris = mutableListOf<Uri>()

    fun loadPdf(uri: Uri) {
        currentPdfUri = uri
        additionalMergeUris.clear()
        val rawName = storageManager.getFileNameFromUri(uri)
        if (!rawName.isNullOrBlank()) {
            val base = rawName.removeSuffix(".pdf").removeSuffix(".PDF")
            _suggestedFileName.value = "${base}_edited"
        } else {
            _suggestedFileName.value = storageManager.generateDefaultFileName("Edited")
        }

        viewModelScope.launch {
            _uiState.value = PdfEditorUiState.Loading
            try {
                val pages = rendererManager.extractPagesInfo(uri)
                _uiState.value = PdfEditorUiState.Ready(pages)
            } catch (e: Exception) {
                _uiState.value = PdfEditorUiState.Error("Не вдалося відкрити PDF: ${e.localizedMessage}")
            }
        }
    }

    fun selectDefaultFolder(folder: DefaultSaveFolder) {
        _saveDestination.value = storageManager.setDefaultSaveFolder(folder)
    }

    fun selectCustomFolder(treeUri: Uri) {
        _saveDestination.value = storageManager.setCustomTreeFolder(treeUri)
    }

    fun rotatePage(pageIndex: Int) {
        val currentState = _uiState.value as? PdfEditorUiState.Ready ?: return
        val updated = currentState.pages.mapIndexed { index, page ->
            if (index == pageIndex) {
                page.copy(rotationAngle = (page.rotationAngle + 90) % 360)
            } else page
        }
        _uiState.value = PdfEditorUiState.Ready(updated)
    }

    fun deletePage(pageIndex: Int) {
        val currentState = _uiState.value as? PdfEditorUiState.Ready ?: return
        if (currentState.pages.size <= 1) {
            _uiState.value = PdfEditorUiState.Error(
                message = "Документ повинен містити хоча б 1 сторінку",
                previousPages = currentState.pages
            )
            return
        }
        val mutable = currentState.pages.toMutableList()
        mutable.removeAt(pageIndex)
        _uiState.value = PdfEditorUiState.Ready(mutable)
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        val currentState = _uiState.value as? PdfEditorUiState.Ready ?: return
        val mutable = currentState.pages.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        _uiState.value = PdfEditorUiState.Ready(mutable)
    }

    fun mergeAdditionalPdf(extraUri: Uri) {
        additionalMergeUris.add(extraUri)
        val currentPagesSnapshot = (_uiState.value as? PdfEditorUiState.Ready)?.pages ?: emptyList()
        viewModelScope.launch {
            try {
                val extraPages = rendererManager.extractPagesInfo(extraUri)
                _uiState.value = PdfEditorUiState.Ready(currentPagesSnapshot + extraPages)
            } catch (e: Exception) {
                _uiState.value = PdfEditorUiState.Error(
                    message = "Помилка додавання PDF: ${e.localizedMessage}",
                    previousPages = currentPagesSnapshot.takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    fun saveModifiedPdf(customFileName: String? = null) {
        val originalUri = currentPdfUri ?: return
        val currentState = _uiState.value as? PdfEditorUiState.Ready ?: return
        val resolvedName = (customFileName ?: _suggestedFileName.value)
            .trim()
            .ifBlank { storageManager.generateDefaultFileName("Edited") }
        _suggestedFileName.value = resolvedName

        viewModelScope.launch {
            try {
                _uiState.value = PdfEditorUiState.Saving(10, "Початок збереження...")
                val tempFile = storageManager.createTempPdfFile("edited_")

                pdfEditor.saveEditedPdf(
                    originalPdfUri = originalUri,
                    modifiedPages = currentState.pages,
                    additionalPdfUris = additionalMergeUris,
                    outputFile = tempFile,
                    onProgress = { progress, msg ->
                        _uiState.value = PdfEditorUiState.Saving(progress, msg)
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
                        pageCount = currentState.pages.size,
                        fileSizeBytes = fileSize,
                        updatedAt = System.currentTimeMillis(),
                        actionType = "Відредаговано"
                    )
                )

                _uiState.value = PdfEditorUiState.Success(
                    PdfExportResult(
                        uri = publicUri,
                        fileName = finalFileName,
                        fileSizeBytes = fileSize,
                        pageCount = currentState.pages.size,
                        folderName = folderDisplayName
                    )
                )
            } catch (e: Exception) {
                _uiState.value = PdfEditorUiState.Error(
                    message = "Помилка збереження: ${e.localizedMessage}",
                    previousPages = currentState.pages
                )
            }
        }
    }

    fun dismissError() {
        val err = _uiState.value as? PdfEditorUiState.Error ?: return
        _uiState.value = if (err.previousPages != null) {
            PdfEditorUiState.Ready(err.previousPages)
        } else {
            PdfEditorUiState.NoFileLoaded
        }
    }
}
