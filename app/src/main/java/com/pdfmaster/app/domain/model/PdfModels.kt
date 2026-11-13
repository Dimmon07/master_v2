package com.pdfmaster.app.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Елемент зображення, обраного користувачем для створення PDF
 */
@Parcelize
data class ImageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val rotation: Float = 0f, // 0f, 90f, 180f, 270f
    val croppedUri: Uri? = null,
    val sizeBytes: Long = 0L
) : Parcelable {
    val displayUri: Uri
        get() = croppedUri ?: uri
}

/**
 * Сторінка існуючого або створюваного PDF для редактора
 */
data class PdfPageItem(
    val pageIndex: Int,
    val originalPageIndex: Int,
    val rotationAngle: Int = 0, // 0, 90, 180, 270
    val thumbnailUri: Uri? = null,
    val sourceDocumentUri: Uri? = null,
    val isSelected: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString()
)

enum class PageOrientation(val displayName: String) {
    PORTRAIT("Портрет"),
    LANDSCAPE("Альбом"),
    AUTO("Авто")
}

enum class PageMargin(val displayName: String, val points: Float) {
    NONE("Без полів", 0f),
    SMALL("Малі", 18f), // ~0.25 inch
    LARGE("Великі", 36f)  // ~0.5 inch
}

enum class ImageCompression(val displayName: String, val quality: Int, val maxDimension: Int) {
    HIGH("Висока", 95, 2400),
    MEDIUM("Середня", 80, 1600),
    LOW("Низька", 60, 1000)
}

enum class PageSize(val displayName: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595f, 842f),
    A3("A3", 842f, 1191f),
    A5("A5", 420f, 595f),
    FIT_IMAGE("Самі фото", 0f, 0f)
}

enum class DefaultSaveFolder(val displayName: String, val subPath: String) {
    DOWNLOADS_PDFMASTER("Downloads / PDFMaster", "PDFMaster"),
    DOWNLOADS("Downloads (Завантаження)", ""),
    DOCUMENTS_PDFMASTER("Documents / PDFMaster", "PDFMaster"),
    DOCUMENTS("Documents (Документи)", "")
}

data class SaveDestination(
    val displayName: String = DefaultSaveFolder.DOWNLOADS_PDFMASTER.displayName,
    val treeUriString: String? = null,
    val defaultFolder: DefaultSaveFolder = DefaultSaveFolder.DOWNLOADS_PDFMASTER
)

data class LastEditedFileInfo(
    val fileName: String,
    val uriString: String,
    val folderName: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val actionType: String = "Відредаговано"
)

data class RecentPdfEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val pageSize: PageSize = PageSize.A4
)

data class PdfSettings(
    val fileName: String = "",
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val margin: PageMargin = PageMargin.SMALL,
    val compression: ImageCompression = ImageCompression.MEDIUM,
    val pageSize: PageSize = PageSize.A4,
    val includePageNumbers: Boolean = true
)

data class PdfExportResult(
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val folderName: String = DefaultSaveFolder.DOWNLOADS_PDFMASTER.displayName
)
