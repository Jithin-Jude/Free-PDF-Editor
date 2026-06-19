package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed interface EditorUiState {
    object Idle : EditorUiState
    object Loading : EditorUiState
    data class Success(
        val pdfUri: Uri?,
        val fileName: String,
        val pages: List<PageData>,
        val currentPageIndex: Int = 0,
        val selectedElementId: String? = null
    ) : EditorUiState
    data class Error(val message: String) : EditorUiState
}

class PdfEditorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Idle)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // For keeping track of transient draw points during an active drag/drawing session
    private val _activeDrawingPoints = MutableStateFlow<List<PointData>>(emptyList())
    val activeDrawingPoints: StateFlow<List<PointData>> = _activeDrawingPoints.asStateFlow()

    fun clearActiveDrawingPoints() {
        _activeDrawingPoints.value = emptyList()
    }

    fun addActiveDrawingPoint(point: PointData) {
        _activeDrawingPoints.value = _activeDrawingPoints.value + point
    }

    fun getSuccessStateOrNull(): EditorUiState.Success? {
        return _uiState.value as? EditorUiState.Success
    }

    /**
     * Initializes the environment. If no PDF is loaded, it generates and loads a beautiful interactive welcome sample.
     */
    fun initializeWithSampleIfNeeded(context: Context) {
        if (_uiState.value !is EditorUiState.Idle) return
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            try {
                val sampleFile = withContext(Dispatchers.IO) {
                    generateSamplePdf(context)
                }
                loadPdfFromFile(context, sampleFile, "Sample Document.pdf", null)
            } catch (e: Exception) {
                Log.e("PdfEditorViewModel", "Failed to generate welcome sample", e)
                _uiState.value = EditorUiState.Error("Welcome sample generation failed: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Loads a PDF selected via storage file picker.
     */
    fun selectAndLoadPdf(context: Context, uri: Uri, fileName: String) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            try {
                // Clear any old cache files to save device space
                withContext(Dispatchers.IO) {
                    context.cacheDir.listFiles { file -> file.name.startsWith("pdf_page_") }?.forEach { it.delete() }
                }
                loadPdfFromUri(context, uri, fileName)
            } catch (e: Exception) {
                Log.e("PdfEditorViewModel", "Failed to load PDF", e)
                _uiState.value = EditorUiState.Error("Failed to open PDF: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun loadPdfFromUri(context: Context, uri: Uri, fileName: String) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val tempFile = File(context.cacheDir, "current_editing.pdf")
        
        // Copy the PDF into our local cache space to make it safely seekable for PdfRenderer
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        
        loadPdfFromFile(context, tempFile, fileName, uri)
    }

    private suspend fun loadPdfFromFile(context: Context, file: File, fileName: String, sourceUri: Uri?) = withContext(Dispatchers.IO) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount
        val pagesTemp = mutableListOf<PageData>()

        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val pWidth = page.width
            val pHeight = page.height

            // Calculate responsive scale: 2x points renders about 150-200 DPI, making text incredibly sharp
            val renderScale = 2
            val bitmapWidth = pWidth * renderScale
            val bitmapHeight = pHeight * renderScale

            val pageBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pageBitmap)
            canvas.drawColor(Color.WHITE) // PDF backgrounds default to clear; fill with pristine white
            page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Save rendered background bitmap as a high-quality JPEG in the local cache
            val cacheFile = File(context.cacheDir, "pdf_page_${UUID.randomUUID()}_$i.jpg")
            FileOutputStream(cacheFile).use { out ->
                pageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            pageBitmap.recycle()

            pagesTemp.add(
                PageData(
                    index = i,
                    width = pWidth,
                    height = pHeight,
                    cachePath = cacheFile.absolutePath,
                    elements = emptyList()
                )
            )
        }

        renderer.close()
        pfd.close()

        _uiState.value = EditorUiState.Success(
            pdfUri = sourceUri,
            fileName = fileName,
            pages = pagesTemp,
            currentPageIndex = 0
        )
    }

    /**
     * Adds a basic Text Overlay element in the center of the active page.
     */
    fun addTextOverlay(pageIndex: Int, text: String, colorHex: String = "#333333", fontSize: Float = 16f) {
        val state = getSuccessStateOrNull() ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return

        val elementId = UUID.randomUUID().toString()
        // Center the element
        val elementWidth = 200f
        val elementHeight = 60f
        val x = (page.width / 2f) - (elementWidth / 2f)
        val y = (page.height / 2f) - (elementHeight / 2f)

        val newElement = PdfElement.Text(
            id = elementId,
            x = x,
            y = y,
            width = elementWidth,
            height = elementHeight,
            text = text,
            fontSize = fontSize,
            colorHex = colorHex
        )

        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = p.elements + newElement) else p
        }

        _uiState.value = state.copy(pages = updatedPages, selectedElementId = elementId)
    }

    /**
     * Adds an Image Overlay element in the center of the active page.
     */
    fun addImageOverlay(pageIndex: Int, uri: Uri) {
        val state = getSuccessStateOrNull() ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return

        val elementId = UUID.randomUUID().toString()
        val elementWidth = 150f
        val elementHeight = 150f
        val x = (page.width / 2f) - (elementWidth / 2f)
        val y = (page.height / 2f) - (elementHeight / 2f)

        val newElement = PdfElement.Image(
            id = elementId,
            x = x,
            y = y,
            width = elementWidth,
            height = elementHeight,
            uriString = uri.toString()
        )

        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = p.elements + newElement) else p
        }

        _uiState.value = state.copy(pages = updatedPages, selectedElementId = elementId)
    }

    /**
     * Adds structured Whiteout Block elements in the center of the active page.
     */
    fun addWhiteoutOverlay(pageIndex: Int, colorHex: String = "#FFFFFF") {
        val state = getSuccessStateOrNull() ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return

        val elementId = UUID.randomUUID().toString()
        val elementWidth = 120f
        val elementHeight = 40f
        val x = (page.width / 2f) - (elementWidth / 2f)
        val y = (page.height / 2f) - (elementHeight / 2f)

        val newElement = PdfElement.Whiteout(
            id = elementId,
            x = x,
            y = y,
            width = elementWidth,
            height = elementHeight,
            colorHex = colorHex
        )

        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = p.elements + newElement) else p
        }

        _uiState.value = state.copy(pages = updatedPages, selectedElementId = elementId)
    }

    /**
     * Commits a freehand drawing path on the page.
     */
    fun commitDrawing(pageIndex: Int, colorHex: String = "#E91E63", strokeWidth: Float = 4f) {
        val points = _activeDrawingPoints.value
        if (points.size < 2) return

        val state = getSuccessStateOrNull() ?: return

        // Calculate a bounding box for our drawing element just to conform to the layout model
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }

        val width = (maxX - minX).coerceAtLeast(1f)
        val height = (maxY - minY).coerceAtLeast(1f)

        val newDrawing = PdfElement.Drawing(
            id = UUID.randomUUID().toString(),
            x = minX,
            y = minY,
            width = width,
            height = height,
            points = points,
            colorHex = colorHex,
            strokeWidth = strokeWidth
        )

        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = p.elements + newDrawing) else p
        }

        _uiState.value = state.copy(pages = updatedPages)
        clearActiveDrawingPoints()
    }

    /**
     * Updates coordinates, contents, sizes or styling attributes of a specific element.
     */
    fun updateElement(pageIndex: Int, elementId: String, updated: PdfElement) {
        val state = getSuccessStateOrNull() ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return

        val updatedElements = page.elements.map { el ->
            if (el.id == elementId) updated else el
        }

        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = updatedElements) else p
        }

        _uiState.value = state.copy(pages = updatedPages)
    }

    /**
     * Removes an element.
     */
    fun deleteElement(pageIndex: Int, elementId: String) {
        val state = getSuccessStateOrNull() ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return

        val updatedElements = page.elements.filter { el -> el.id != elementId }
        val updatedPages = state.pages.mapIndexed { idx, p ->
            if (idx == pageIndex) p.copy(elements = updatedElements) else p
        }

        val nextSelectedId = if (state.selectedElementId == elementId) null else state.selectedElementId
        _uiState.value = state.copy(pages = updatedPages, selectedElementId = nextSelectedId)
    }

    /**
     * Selects/Highlights an element for active configuration on-screen.
     */
    fun selectElement(elementId: String?) {
        val state = getSuccessStateOrNull() ?: return
        _uiState.value = state.copy(selectedElementId = elementId)
    }

    /**
     * Selects/Highlights active page viewport index.
     */
    fun selectPageIndex(pageIndex: Int) {
        val state = getSuccessStateOrNull() ?: return
        _uiState.value = state.copy(currentPageIndex = pageIndex)
    }

    /**
     * Generates a fully loaded welcome guide sandbox PDF inside cache directory directly.
     */
    private fun generateSamplePdf(context: Context): File {
        val pdfDoc = PdfDocument()

        // Page 1: Design guide and quick introduction
        val pageInfo1 = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 standard
        val page1 = pdfDoc.startPage(pageInfo1)
        val canvas1 = page1.canvas
        val paint = Paint()

        // 1. Sleek corporate design header background
        paint.color = Color.rgb(240, 244, 248)
        canvas1.drawRect(0f, 0f, 595f, 180f, paint)

        // Title and Subtitles
        paint.color = Color.rgb(21, 101, 192) // deep navy blue
        paint.textSize = 26f
        paint.isFakeBoldText = true
        paint.isAntiAlias = true
        canvas1.drawText("PDF Editor Welcome Sandbox", 50f, 80f, paint)

        paint.color = Color.rgb(100, 116, 139) // modern slate
        paint.textSize = 14f
        paint.isFakeBoldText = false
        canvas1.drawText("Test your real-time text and image modifications in this sheet!", 50f, 115f, paint)

        // Draw a decorative glowing line
        paint.color = Color.rgb(33, 150, 243)
        paint.strokeWidth = 3f
        canvas1.drawLine(50f, 135f, 545f, 135f, paint)

        // 2. Beautiful body cards and guidelines text
        paint.color = Color.BLACK
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas1.drawText("App Feature Walkthrough & Sandbox Areas:", 50f, 210f, paint)

        // Feature 1
        paint.color = Color.DKGRAY
        paint.textSize = 12f
        paint.isFakeBoldText = false
        canvas1.drawText("• TEXT ADDITIONS:", 50f, 245f, paint)
        paint.color = Color.GRAY
        canvas1.drawText("Tap '+ Text' above to place a new box. Modify fonts, sizing, and color.", 65f, 265f, paint)

        // Feature 2
        paint.color = Color.DKGRAY
        canvas1.drawText("• IMAGE PLACEMENTS:", 50f, 295f, paint)
        paint.color = Color.GRAY
        canvas1.drawText("Tap '+ Image' to add signatures, corporate stamps, logos or photos.", 65f, 315f, paint)

        // Feature 3
        paint.color = Color.DKGRAY
        canvas1.drawText("• WHITEOUTS & RECOLOR BLOCKS:", 50f, 345f, paint)
        paint.color = Color.GRAY
        canvas1.drawText("Use whiteout rectangle selectors to hide segments, then replace text.", 65f, 365f, paint)

        // Illustrative box to cover
        paint.color = Color.rgb(255, 243, 224) // warm light orange block background
        canvas1.drawRoundRect(50f, 400f, 545f, 560f, 10f, 10f, paint)

        paint.color = Color.rgb(230, 81, 0) // rich deep orange text
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas1.drawText("👉 CHALLENGE ARCH: HIDE ME!", 70f, 440f, paint)

        paint.color = Color.DKGRAY
        paint.isFakeBoldText = false
        canvas1.drawText("Use the '+ Whiteout' block tool to completely cover this yellow background", 70f, 475f, paint)
        canvas1.drawText("and write your own, freshly modified PDF text exactly on top!", 70f, 500f, paint)

        // Graphic design details
        paint.color = Color.rgb(33, 150, 243)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas1.drawRect(50f, 600f, 280f, 720f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(33, 150, 243)
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas1.drawText("Sandbox Image Center", 70f, 640f, paint)
        paint.color = Color.GRAY
        paint.textSize = 10f
        paint.isFakeBoldText = false
        canvas1.drawText("[Place any crop or logo here]", 70f, 670f, paint)

        // Brand details footer
        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 9f
        canvas1.drawText("PDF Editor Sandbox Companion • Device Native Document Render • Page 1 of 2", 50f, 800f, paint)

        pdfDoc.finishPage(page1)

        // Page 2: Sandbox playground
        val pageInfo2 = PdfDocument.PageInfo.Builder(595, 842, 2).create()
        val page2 = pdfDoc.startPage(pageInfo2)
        val canvas2 = page2.canvas

        paint.color = Color.rgb(240, 244, 248)
        canvas2.drawRect(0f, 0f, 595f, 120f, paint)

        paint.color = Color.rgb(21, 101, 192)
        paint.textSize = 22f
        paint.isFakeBoldText = true
        canvas2.drawText("Signing Sandbox and Drawings Hub", 50f, 65f, paint)

        paint.color = Color.rgb(100, 116, 139)
        paint.textSize = 12f
        paint.isFakeBoldText = false
        canvas2.drawText("Handwrite drawings, annotations or draw signs in this canvas page.", 50f, 95f, paint)

        // Draw drawing template card
        paint.color = Color.rgb(248, 250, 252)
        canvas2.drawRoundRect(50f, 160f, 545f, 700f, 12f, 12f, paint)

        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(226, 232, 240)
        paint.strokeWidth = 2f
        canvas2.drawRoundRect(50f, 160f, 545f, 700f, 12f, 12f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(71, 85, 105)
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas2.drawText("OFFICIAL DOCUMENT SIGN-OFF", 80f, 210f, paint)

        paint.color = Color.GRAY
        paint.textSize = 11f
        paint.isFakeBoldText = false
        canvas2.drawText("I hereby certify that all edits made within the local PDF Editor Sandbox", 80f, 250f, paint)
        canvas2.drawText("are rendered locally on-device and stored under device secure storage.", 80f, 275f, paint)

        // Drawing playground outline box
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(203, 213, 225)
        paint.strokeWidth = 1f
        canvas2.drawRect(80f, 330f, 515f, 580f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 11f
        canvas2.drawText("Signature Area (Select Pen Tool above and sign below)", 100f, 360f, paint)

        // Signature line
        paint.color = Color.rgb(148, 163, 184)
        paint.strokeWidth = 1.5f
        canvas2.drawLine(140f, 520f, 455f, 520f, paint)
        canvas2.drawText("AUTHORIZED SIGNATORY", 230f, 545f, paint)

        // Page footer
        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 9f
        canvas2.drawText("PDF Editor Sandbox Companion • Device Native Document Render • Page 2 of 2", 50f, 800f, paint)

        pdfDoc.finishPage(page2)

        val file = File(context.cacheDir, "welcome_sample.pdf")
        FileOutputStream(file).use { out ->
            pdfDoc.writeTo(out)
        }
        pdfDoc.close()
        return file
    }

    /**
     * Saves the modified pages and overlay elements into a new PDF document.
     */
    fun saveModifiedPdf(context: Context, outStream: OutputStream, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val state = getSuccessStateOrNull()
        if (state == null) {
            onFailure("No active document loaded")
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                withContext(Dispatchers.IO) {
                    val document = PdfDocument()

                    for (pageData in state.pages) {
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            pageData.width,
                            pageData.height,
                            pageData.index + 1
                        ).create()

                        val pdfPage = document.startPage(pageInfo)
                        val canvas = pdfPage.canvas

                        // 1. Draw the native page background bitmap
                        val bgBitmap = BitmapFactory.decodeFile(pageData.cachePath)
                        if (bgBitmap != null) {
                            val destRect = RectF(0f, 0f, pageData.width.toFloat(), pageData.height.toFloat())
                            canvas.drawBitmap(bgBitmap, null, destRect, null)
                        }

                        // 2. Draw all overlays sequentially (drawings, whiteouts, images, text)
                        for (element in pageData.elements) {
                            when (element) {
                                is PdfElement.Whiteout -> {
                                    val paint = Paint().apply {
                                        color = Color.parseColor(element.colorHex)
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawRect(
                                        element.x,
                                        element.y,
                                        element.x + element.width,
                                        element.y + element.height,
                                        paint
                                    )
                                }
                                is PdfElement.Drawing -> {
                                    if (element.points.size > 1) {
                                        val paint = Paint().apply {
                                            color = Color.parseColor(element.colorHex)
                                            strokeWidth = element.strokeWidth
                                            style = Paint.Style.STROKE
                                            strokeCap = Paint.Cap.ROUND
                                            strokeJoin = Paint.Join.ROUND
                                            isAntiAlias = true
                                        }
                                        val path = Path()
                                        val first = element.points.first()
                                        path.moveTo(first.x, first.y)
                                        for (i in 1 until element.points.size) {
                                            val p = element.points[i]
                                            path.lineTo(p.x, p.y)
                                        }
                                        canvas.drawPath(path, paint)
                                    }
                                }
                                is PdfElement.Image -> {
                                    try {
                                        val imageUri = Uri.parse(element.uriString)
                                        context.contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                                            val fd = pfd.fileDescriptor
                                            val bitmap = BitmapFactory.decodeFileDescriptor(fd)
                                            if (bitmap != null) {
                                                canvas.save()
                                                canvas.translate(
                                                    element.x + element.width / 2f,
                                                    element.y + element.height / 2f
                                                )
                                                canvas.rotate(element.rotation)
                                                val dstRect = RectF(
                                                    -element.width / 2f,
                                                    -element.height / 2f,
                                                    element.width / 2f,
                                                    element.height / 2f
                                                )
                                                canvas.drawBitmap(bitmap, null, dstRect, null)
                                                canvas.restore()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("PdfEditorViewModel", "Failed to render overlay element image", e)
                                    }
                                }
                                is PdfElement.Text -> {
                                    val paint = Paint().apply {
                                        color = Color.parseColor(element.colorHex)
                                        textSize = element.fontSize
                                        typeface = Typeface.create(
                                            if (element.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
                                            if (element.isItalic) Typeface.ITALIC else Typeface.NORMAL
                                        )
                                        isAntiAlias = true
                                    }

                                    canvas.save()
                                    // Optional background block behind the text (if styled)
                                    if (element.backgroundColorHex != null) {
                                        val bgPaint = Paint().apply {
                                            color = Color.parseColor(element.backgroundColorHex)
                                            style = Paint.Style.FILL
                                        }
                                        canvas.drawRect(
                                            element.x,
                                            element.y,
                                            element.x + element.width,
                                            element.y + element.height,
                                            bgPaint
                                        )
                                    }

                                    canvas.translate(element.x, element.y)
                                    
                                    // Wrap text nicely to prevent text running off page canvas
                                    val textWidth = element.width.toInt().coerceAtLeast(10)
                                    val textPaint = TextPaint(paint)
                                    val textLayout = StaticLayout.Builder.obtain(
                                        element.text,
                                        0,
                                        element.text.length,
                                        textPaint,
                                        textWidth
                                    ).build()

                                    textLayout.draw(canvas)
                                    canvas.restore()
                                }
                            }
                        }

                        document.finishPage(pdfPage)
                    }

                    document.writeTo(outStream)
                    document.close()
                    outStream.flush()
                    outStream.close()
                }

                _isSaving.value = false
                onSuccess()
            } catch (e: Exception) {
                _isSaving.value = false
                Log.e("PdfEditorViewModel", "Compilation failed", e)
                onFailure(e.localizedMessage ?: "Unknown compilation error")
            }
        }
    }
}
