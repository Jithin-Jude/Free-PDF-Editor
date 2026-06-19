package free.pdf.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import free.pdf.editor.pdf.PdfEditorScreen
import free.pdf.editor.pdf.PdfEditorViewModel
import free.pdf.editor.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val pdfViewModel: PdfEditorViewModel = viewModel()
                PdfEditorScreen(
                    viewModel = pdfViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

