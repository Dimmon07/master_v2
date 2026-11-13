package com.pdfmaster.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pdfmaster.app.data.storage.StorageManager
import com.pdfmaster.app.ui.screens.home.HomeScreen
import com.pdfmaster.app.ui.screens.imagetopdf.ImageToPdfScreen
import com.pdfmaster.app.ui.screens.imagetopdf.ImageToPdfViewModel
import com.pdfmaster.app.ui.screens.pdfeditor.PdfEditorScreen
import com.pdfmaster.app.ui.screens.pdfeditor.PdfEditorViewModel
import com.pdfmaster.app.ui.screens.translate.PdfTranslateScreen
import com.pdfmaster.app.ui.theme.PdfMasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val storageManager = StorageManager(this)

        fun openPdfInExternalViewer(uri: Uri) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, "Відкрити PDF"))
            } catch (_: Exception) {
                Toast.makeText(this, "Не знайдено програму для перегляду PDF", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            PdfMasterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var lastEditedFile by remember { mutableStateOf(storageManager.getLastEditedFile()) }
                    var pendingEditorPdfUri by remember { mutableStateOf<Uri?>(null) }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                lastEditedFile = lastEditedFile,
                                onRefreshLastFile = { lastEditedFile = storageManager.getLastEditedFile() },
                                onNavigateToImageToPdf = { navController.navigate("image_to_pdf") },
                                onNavigateToPdfEditor = { navController.navigate("pdf_editor") },
                                onNavigateToPdfTranslate = { navController.navigate("pdf_translate") },
                                onOpenLastFileInEditor = { uri ->
                                    pendingEditorPdfUri = uri
                                    navController.navigate("pdf_editor")
                                },
                                onViewLastFile = { uri -> openPdfInExternalViewer(uri) }
                            )
                        }

                        composable("image_to_pdf") {
                            val viewModel: ImageToPdfViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                            ImageToPdfScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenPdfViewer = { uri -> openPdfInExternalViewer(uri) }
                            )
                        }

                        composable("pdf_editor") {
                            val viewModel: PdfEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                            PdfEditorScreen(
                                viewModel = viewModel,
                                initialPdfUri = pendingEditorPdfUri,
                                onInitialPdfConsumed = { pendingEditorPdfUri = null },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable("pdf_translate") {
                            PdfTranslateScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
