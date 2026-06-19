package com.example.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

enum class ToolbarTool {
    SELECT,
    WRITE,
    IMAGE,
    WHITEOUT,
    PEN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(
    viewModel: PdfEditorViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val activeDrawingPoints by viewModel.activeDrawingPoints.collectAsState()

    var activeTool by remember { mutableStateOf(ToolbarTool.SELECT) }
    var selectedColorHex by remember { mutableStateOf("#2196F3") }
    var selectedStrokeWidth by remember { mutableStateOf(5f) }

    // Dialog state for configuring standard text overlay elements
    var textEditElement by remember { mutableStateOf<PdfElement.Text?>(null) }
    var textEditPageIndex by remember { mutableStateOf(-1) }

    // Dialog state for editing colors/whiteout elements
    var whiteoutEditElement by remember { mutableStateOf<PdfElement.Whiteout?>(null) }
    var whiteoutEditPageIndex by remember { mutableStateOf(-1) }

    // Eye Dropper / Color picker from PDF states
    var isEyeDropping by remember { mutableStateOf(false) }
    var eyeDropperTargetElement by remember { mutableStateOf<PdfElement.Whiteout?>(null) }
    var eyeDropperPageIndex by remember { mutableStateOf(-1) }

    // Dialog state for general successes or errors
    var showExportSuccessDialog by remember { mutableStateOf(false) }

    // Activity launchers for import/export
    val openPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(context, uri) ?: "imported_document.pdf"
            viewModel.selectAndLoadPdf(context, uri, fileName)
            activeTool = ToolbarTool.SELECT
        }
    }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val outStream = context.contentResolver.openOutputStream(uri)
                if (outStream != null) {
                    viewModel.saveModifiedPdf(
                        context = context,
                        outStream = outStream,
                        onSuccess = {
                            showExportSuccessDialog = true
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Save Failed: $error", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error saving: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val pickOverlayImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val state = viewModel.getSuccessStateOrNull()
            if (state != null) {
                viewModel.addImageOverlay(state.currentPageIndex, uri)
                activeTool = ToolbarTool.SELECT
            }
        }
    }

    // Load welcome guide template on start
    LaunchedEffect(Unit) {
        viewModel.initializeWithSampleIfNeeded(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PDF Editor Workspace",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        (uiState as? EditorUiState.Success)?.let {
                            Text(
                                text = "Editing: ${it.fileName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Quick click generates welcome sample again or clears loaded
                        viewModel.initializeWithSampleIfNeeded(context)
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.PictureAsPdf,
                            contentDescription = "PDF Icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.testTag("open_pdf_button")
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Open PDF")
                    }

                    if (uiState is EditorUiState.Success) {
                        Button(
                            onClick = {
                                val state = uiState as EditorUiState.Success
                                val cleanName = state.fileName.substringBeforeLast(".pdf")
                                createPdfLauncher.launch("${cleanName}_edited.pdf")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("save_pdf_button")
                        ) {
                            Icon(
                                Icons.Rounded.Save,
                                contentDescription = "Save",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Export", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        },
        bottomBar = {
            // Document Control Quick-Tool Row to select modes and inject content easily
            if (uiState is EditorUiState.Success) {
                Surface(
                    tonalElevation = 8.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    Column {
                        // Tool settings contextual tray
                        AnimatedVisibility(
                            visible = activeTool == ToolbarTool.PEN,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(vertical = 8.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Palette,
                                        contentDescription = "Color",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Pen Colors:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("#E91E63", "#2196F3", "#4CAF50", "#000000", "#FFC107").forEach { hex ->
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(ComposeColor(android.graphics.Color.parseColor(hex)))
                                                .border(
                                                    width = if (selectedColorHex == hex) 3.dp else 1.dp,
                                                    color = if (selectedColorHex == hex) MaterialTheme.colorScheme.primary else ComposeColor.White.copy(0.6f),
                                                    shape = CircleShape
                                                )
                                                .clickable { selectedColorHex = hex }
                                        )
                                    }
                                }
                            }
                        }

                        NavigationBar(
                            modifier = Modifier.height(72.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        ) {
                            NavigationBarItem(
                                selected = activeTool == ToolbarTool.SELECT,
                                onClick = { activeTool = ToolbarTool.SELECT },
                                icon = { Icon(Icons.Rounded.PanTool, contentDescription = "Interact Mode") },
                                label = { Text("Move", fontSize = 11.sp) }
                            )

                            NavigationBarItem(
                                selected = activeTool == ToolbarTool.WRITE,
                                onClick = {
                                    val state = uiState as EditorUiState.Success
                                    viewModel.addTextOverlay(state.currentPageIndex, "Double tap to Edit")
                                    activeTool = ToolbarTool.SELECT
                                    Toast.makeText(context, "Added text overlay. Double tap to customize content", Toast.LENGTH_SHORT).show()
                                },
                                icon = { Icon(Icons.Rounded.Title, contentDescription = "Add Text") },
                                label = { Text("+ Text", fontSize = 11.sp) },
                                modifier = Modifier.testTag("add_text_button")
                            )

                            NavigationBarItem(
                                selected = activeTool == ToolbarTool.IMAGE,
                                onClick = {
                                    pickOverlayImageLauncher.launch("image/*")
                                    activeTool = ToolbarTool.SELECT
                                },
                                icon = { Icon(Icons.Rounded.Image, contentDescription = "Add Image") },
                                label = { Text("+ Image", fontSize = 11.sp) },
                                modifier = Modifier.testTag("add_image_button")
                            )

                            NavigationBarItem(
                                selected = activeTool == ToolbarTool.WHITEOUT,
                                onClick = {
                                    val state = uiState as EditorUiState.Success
                                    viewModel.addWhiteoutOverlay(state.currentPageIndex)
                                    activeTool = ToolbarTool.SELECT
                                    Toast.makeText(context, "Added whiteout rectangle. Drag over components to cover details", Toast.LENGTH_LONG).show()
                                },
                                icon = { Icon(Icons.Rounded.Layers, contentDescription = "Add Whiteout") },
                                label = { Text("+ Block", fontSize = 11.sp) },
                                modifier = Modifier.testTag("add_whiteout_button")
                            )

                            NavigationBarItem(
                                selected = activeTool == ToolbarTool.PEN,
                                onClick = { activeTool = ToolbarTool.PEN },
                                icon = { Icon(Icons.Rounded.Brush, contentDescription = "Pen Drawing") },
                                label = { Text("Sign Pen", fontSize = 11.sp) },
                                modifier = Modifier.testTag("activate_pen_button")
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is EditorUiState.Idle, is EditorUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Initializing PDF Sandbox Workspace...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is EditorUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Oops, something broke!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { viewModel.initializeWithSampleIfNeeded(context) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Try Loading Sample PDF")
                        }
                    }
                }
                is EditorUiState.Success -> {
                    // Document display workspace with elegant paging list
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Quick page jump header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Interactive Page ${state.currentPageIndex + 1} of ${state.pages.size}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = {
                                        if (state.currentPageIndex > 0) {
                                            viewModel.selectPageIndex(state.currentPageIndex - 1)
                                        }
                                    },
                                    enabled = state.currentPageIndex > 0,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Prev Page")
                                }

                                IconButton(
                                    onClick = {
                                        if (state.currentPageIndex < state.pages.size - 1) {
                                            viewModel.selectPageIndex(state.currentPageIndex + 1)
                                        }
                                    },
                                    enabled = state.currentPageIndex < state.pages.size - 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next Page")
                                }
                            }
                        }

                        // Scrollable viewport of pages
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(state.pages) { page ->
                                val isActivePage = page.index == state.currentPageIndex
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(
                                            width = if (isActivePage) 2.5.dp else 1.dp,
                                            color = if (isActivePage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .shadow(
                                            elevation = if (isActivePage) 8.dp else 2.dp,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { viewModel.selectPageIndex(page.index) }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "Page ${page.index + 1}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActivePage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                                    )

                                    // Rendered background box holding all overlays asynchronously
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(page.width.toFloat() / page.height.toFloat())
                                            .background(ComposeColor.White)
                                    ) {
                                        val containerWidthDp = maxWidth
                                        val scalePointsToDp = containerWidthDp.value / page.width.toFloat()

                                        // Background rendered PDF image
                                        AsyncImage(
                                            model = File(page.cachePath),
                                            contentDescription = "PDF Background Page ${page.index + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )

                                        // Active Freehand Drawing Canvas Layer (only enabled when PEN mode is selected on the active page)
                                        val scalePointsToPx = scalePointsToDp * (LocalContext.current.resources.displayMetrics.density)

                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(activeTool, isActivePage) {
                                                    if (activeTool != ToolbarTool.PEN || !isActivePage) return@pointerInput
                                                    detectDragGestures(
                                                        onDragStart = { offset ->
                                                            val xPoints = offset.x / scalePointsToPx
                                                            val yPoints = offset.y / scalePointsToPx
                                                            viewModel.clearActiveDrawingPoints()
                                                            viewModel.addActiveDrawingPoint(PointData(xPoints, yPoints))
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            val position = change.position
                                                            val xPoints = position.x / scalePointsToPx
                                                            val yPoints = position.y / scalePointsToPx
                                                            viewModel.addActiveDrawingPoint(PointData(xPoints, yPoints))
                                                        },
                                                        onDragEnd = {
                                                            viewModel.commitDrawing(
                                                                pageIndex = page.index,
                                                                colorHex = selectedColorHex,
                                                                strokeWidth = selectedStrokeWidth
                                                            )
                                                        }
                                                    )
                                                }
                                        ) {
                                            // 1. Draw already completed / saved drawings
                                            page.elements.filterIsInstance<PdfElement.Drawing>().forEach { drawing ->
                                                if (drawing.points.size > 1) {
                                                    val path = ComposePath().apply {
                                                        val first = drawing.points.first()
                                                        moveTo(first.x * scalePointsToPx, first.y * scalePointsToPx)
                                                    }
                                                    for (i in 1 until drawing.points.size) {
                                                        val p = drawing.points[i]
                                                        path.lineTo(p.x * scalePointsToPx, p.y * scalePointsToPx)
                                                    }
                                                    drawPath(
                                                        path = path,
                                                        color = ComposeColor(android.graphics.Color.parseColor(drawing.colorHex)),
                                                        style = Stroke(
                                                            width = drawing.strokeWidth * (scalePointsToPx / 2f),
                                                            cap = StrokeCap.Round,
                                                            join = StrokeJoin.Round
                                                        )
                                                    )
                                                }
                                            }

                                            // 2. Draw active live draw line path feedback
                                            if (isActivePage && activeDrawingPoints.size > 1) {
                                                val path = ComposePath().apply {
                                                    val first = activeDrawingPoints.first()
                                                    moveTo(first.x * scalePointsToPx, first.y * scalePointsToPx)
                                                }
                                                for (i in 1 until activeDrawingPoints.size) {
                                                    val p = activeDrawingPoints[i]
                                                    path.lineTo(p.x * scalePointsToPx, p.y * scalePointsToPx)
                                                }
                                                drawPath(
                                                    path = path,
                                                    color = ComposeColor(android.graphics.Color.parseColor(selectedColorHex)),
                                                    style = Stroke(
                                                        width = selectedStrokeWidth * (scalePointsToPx / 2f),
                                                        cap = StrokeCap.Round,
                                                        join = StrokeJoin.Round
                                                    )
                                                )
                                            }
                                        }

                                        // 3. Render all interactive overlay elements (Text, Images, Whiteouts)
                                        page.elements.filter { it !is PdfElement.Drawing }.forEach { element ->
                                            val isSelected = state.selectedElementId == element.id

                                            InteractiveOverlayWidget(
                                                element = element,
                                                scale = scalePointsToDp,
                                                isSelected = isSelected,
                                                onSelect = { viewModel.selectElement(element.id) },
                                                onPositionChange = { newX, newY ->
                                                    // Constrain within document canvas borders nicely
                                                    val boundedX = newX.coerceIn(0f, page.width.toFloat() - element.width)
                                                    val boundedY = newY.coerceIn(0f, page.height.toFloat() - element.height)
                                                    viewModel.updateElement(
                                                        pageIndex = page.index,
                                                        elementId = element.id,
                                                        updated = element.copyWithPosition(boundedX, boundedY)
                                                    )
                                                },
                                                onResizeChange = { newW, newH ->
                                                    viewModel.updateElement(
                                                        pageIndex = page.index,
                                                        elementId = element.id,
                                                        updated = element.copyWithSize(
                                                            newW.coerceAtLeast(30f),
                                                            newH.coerceAtLeast(20f)
                                                        )
                                                    )
                                                },
                                                onEditTap = {
                                                    when (element) {
                                                        is PdfElement.Text -> {
                                                            textEditElement = element
                                                            textEditPageIndex = page.index
                                                        }
                                                        is PdfElement.Whiteout -> {
                                                            whiteoutEditElement = element
                                                            whiteoutEditPageIndex = page.index
                                                        }
                                                        else -> {
                                                            Toast.makeText(context, "Tip: Drag handles to position or resize this image", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onDeleteTap = {
                                                    viewModel.deleteElement(page.index, element.id)
                                                }
                                            )
                                        }

                                        // Eye-dropper/Color sampling overlay (captures absolute pixel color on click, placed on top of all interactive layers)
                                        if (isEyeDropping) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(ComposeColor.Black.copy(alpha = 0.08f))
                                                    .pointerInput(page, eyeDropperTargetElement, eyeDropperPageIndex) {
                                                        detectTapGestures { offset ->
                                                            val innerContainerWidthDp = maxWidth
                                                            val innerScalePointsToDp = innerContainerWidthDp.value / page.width.toFloat()
                                                            val scalePointsToPx = innerScalePointsToDp * (context.resources.displayMetrics.density)
                                                            
                                                            val tappedXPoints = offset.x / scalePointsToPx
                                                            val tappedYPoints = offset.y / scalePointsToPx

                                                            val file = File(page.cachePath)
                                                            if (file.exists()) {
                                                                try {
                                                                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                                                    if (bitmap != null) {
                                                                        val pxX = ((tappedXPoints / page.width.toFloat()) * bitmap.width).toInt()
                                                                            .coerceIn(0, bitmap.width - 1)
                                                                        val pxY = ((tappedYPoints / page.height.toFloat()) * bitmap.height).toInt()
                                                                            .coerceIn(0, bitmap.height - 1)
                                                                        val pixelColorInt = bitmap.getPixel(pxX, pxY)
                                                                        val r = android.graphics.Color.red(pixelColorInt)
                                                                        val g = android.graphics.Color.green(pixelColorInt)
                                                                        val b = android.graphics.Color.blue(pixelColorInt)
                                                                        val sampledHex = String.format("#%02X%02X%02X", r, g, b)

                                                                        bitmap.recycle()

                                                                        // Re-open original dialog with sampled color
                                                                        eyeDropperTargetElement?.let { target ->
                                                                            whiteoutEditElement = target.copy(colorHex = sampledHex)
                                                                            whiteoutEditPageIndex = eyeDropperPageIndex
                                                                        }

                                                                        Toast.makeText(context, "Sampled color: $sampledHex", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                } catch (e: Exception) {
                                                                    Toast.makeText(context, "Error sampling color: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                                } finally {
                                                                    isEyeDropping = false
                                                                    eyeDropperTargetElement = null
                                                                    eyeDropperPageIndex = -1
                                                                }
                                                            } else {
                                                                isEyeDropping = false
                                                                eyeDropperTargetElement = null
                                                                eyeDropperPageIndex = -1
                                                            }
                                                        }
                                                    }
                                                    .testTag("eyedropper_capture_layer"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .border(
                                                            width = 3.dp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            shape = RoundedCornerShape(4.dp)
                                                        )
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.Colorize,
                                                    contentDescription = "Target",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                                                        .padding(8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Exporter saving overlay loader
            AnimatedVisibility(
                visible = isSaving,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = ComposeColor.Black.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Compiling Layers...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Writing high-DPI JPEGs and overlay nodes to the PDF device container",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            if (isEyeDropping) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .fillMaxWidth(0.95f)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .testTag("eyedropper_banner_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Colorize,
                                contentDescription = "Eye Dropper Mode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Eyedropper Color Picker Mode",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Tap anywhere on any PDF page layout to lift its color",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                // Resume the original block config dialog
                                whiteoutEditElement = eyeDropperTargetElement
                                whiteoutEditPageIndex = eyeDropperPageIndex
                                
                                isEyeDropping = false
                                eyeDropperTargetElement = null
                                eyeDropperPageIndex = -1
                            }
                        ) {
                            Text("Cancel", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal Builder: Text Customizer Dialog
    textEditElement?.let { element ->
        var currentText by remember { mutableStateOf(element.text) }
        var currentFontSize by remember { mutableStateOf(element.fontSize) }
        var currentFontColorHex by remember { mutableStateOf(element.colorHex) }
        var isBold by remember { mutableStateOf(element.isBold) }
        var isItalic by remember { mutableStateOf(element.isItalic) }
        var fillBackgroundBlock by remember { mutableStateOf(element.backgroundColorHex != null) }
        var bgBlockColorHex by remember { mutableStateOf(element.backgroundColorHex ?: "#FFFFFF") }

        AlertDialog(
            onDismissRequest = { textEditElement = null },
            confirmButton = {
                Button(
                    onClick = {
                        val modifiedTextElement = element.copy(
                            text = currentText,
                            fontSize = currentFontSize,
                            colorHex = currentFontColorHex,
                            isBold = isBold,
                            isItalic = isItalic,
                            backgroundColorHex = if (fillBackgroundBlock) bgBlockColorHex else null
                        )
                        viewModel.updateElement(textEditPageIndex, element.id, modifiedTextElement)
                        textEditElement = null
                    },
                    modifier = Modifier.testTag("apply_text_changes_button")
                ) {
                    Text("Apply Edits")
                }
            },
            dismissButton = {
                TextButton(onClick = { textEditElement = null }) { Text("Cancel") }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.EditNote, contentDescription = "Edit Text", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Customize Text Overlay")
                }
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(
                            value = currentText,
                            onValueChange = { currentText = it },
                            label = { Text("Overlay Text") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_text_input_field"),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                        )
                    }

                    item {
                        Text(
                            "Font Size: ${currentFontSize.toInt()} pt",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = currentFontSize,
                            onValueChange = { currentFontSize = it },
                            valueRange = 8f..64f,
                            steps = 56,
                            modifier = Modifier.testTag("font_size_slider")
                        )
                    }

                    item {
                        Text("Text Color", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            listOf("#000000", "#D32F2F", "#1976D2", "#388E3C", "#7B1FA2", "#FF5722").forEach { hex ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(ComposeColor(android.graphics.Color.parseColor(hex)))
                                        .border(
                                            width = if (currentFontColorHex == hex) 3.dp else 1.dp,
                                            color = if (currentFontColorHex == hex) MaterialTheme.colorScheme.primary else ComposeColor.White.copy(0.5f),
                                            shape = CircleShape
                                        )
                                        .clickable { currentFontColorHex = hex }
                                )
                            }
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isBold,
                                onCheckedChange = { isBold = it },
                                modifier = Modifier.testTag("bold_checkbox")
                            )
                            Text("Bold Face", fontSize = 13.sp)
                            Spacer(Modifier.width(16.dp))
                            Checkbox(
                                checked = isItalic,
                                onCheckedChange = { isItalic = it },
                                modifier = Modifier.testTag("italic_checkbox")
                            )
                            Text("Italic Shape", fontSize = 13.sp)
                        }
                    }

                    item {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = fillBackgroundBlock,
                                    onCheckedChange = { fillBackgroundBlock = it },
                                    modifier = Modifier.testTag("bg_block_checkbox")
                                )
                                Text("Fill Label Background", fontSize = 13.sp)
                            }

                            if (fillBackgroundBlock) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp, start = 12.dp)
                                ) {
                                    listOf("#FFFFFF", "#FFFF00", "#FFCDD2", "#C8E6C9", "#BBDEFB").forEach { bgHex ->
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ComposeColor(android.graphics.Color.parseColor(bgHex)))
                                                .border(
                                                    width = if (bgBlockColorHex == bgHex) 3.dp else 1.dp,
                                                    color = if (bgBlockColorHex == bgHex) MaterialTheme.colorScheme.primary else ComposeColor.Gray,
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .clickable { bgBlockColorHex = bgHex }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // Modal Builder: Whiteout Color Customizer Dialog
    whiteoutEditElement?.let { element ->
        var selectedBgColor by remember { mutableStateOf(element.colorHex) }
        var customHexText by remember { mutableStateOf(selectedBgColor) }

        AlertDialog(
            onDismissRequest = { whiteoutEditElement = null },
            confirmButton = {
                Button(
                    onClick = {
                        val modifiedWhiteout = element.copy(colorHex = selectedBgColor)
                        viewModel.updateElement(whiteoutEditPageIndex, element.id, modifiedWhiteout)
                        whiteoutEditElement = null
                    },
                    modifier = Modifier.testTag("apply_whiteout_changes_button")
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { whiteoutEditElement = null }) { Text("Cancel") }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.ColorLens,
                        contentDescription = "Block Color",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Configure Cover Block")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    
                    // Eye dropper / Color picker from PDF Section
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Live Sample from Document",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Sample any exact pixel color from your opened PDF document background to match the style perfectly.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    // Start eye dropping!
                                    eyeDropperTargetElement = element
                                    eyeDropperPageIndex = whiteoutEditPageIndex
                                    isEyeDropping = true
                                    // Close current dialog instance
                                    whiteoutEditElement = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("eyedropper_trigger_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Colorize,
                                    contentDescription = "Pick code",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Pick Color from PDF", fontSize = 12.sp)
                            }
                        }
                    }

                    // Divider
                    Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))

                    // Palette Preset Row 1 & 2
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Color Palette Presets:", 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "#FFFFFF" to "Whiteout",
                                "#000000" to "Redact",
                                "#FFEB3B" to "Highlight",
                                "#FFCDD2" to "Ruby"
                            ).forEach { (hex, label) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (selectedBgColor.uppercase() == hex.uppercase()) 2.5.dp else 1.dp,
                                            color = if (selectedBgColor.uppercase() == hex.uppercase()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedBgColor = hex
                                            customHexText = hex
                                        }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ComposeColor(android.graphics.Color.parseColor(hex)))
                                                .border(1.dp, ComposeColor.Gray.copy(0.3f), RoundedCornerShape(6.dp))
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(label, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "#C8E6C9" to "Sage",
                                "#BBDEFB" to "Sky",
                                "#FFF9C4" to "Cream",
                                "#E1BEE7" to "Lavender"
                            ).forEach { (hex, label) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (selectedBgColor.uppercase() == hex.uppercase()) 2.5.dp else 1.dp,
                                            color = if (selectedBgColor.uppercase() == hex.uppercase()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedBgColor = hex
                                            customHexText = hex
                                        }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ComposeColor(android.graphics.Color.parseColor(hex)))
                                                .border(1.dp, ComposeColor.Gray.copy(0.3f), RoundedCornerShape(6.dp))
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(label, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }

                    // Divider
                    Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))

                    // Custom Color Input / Preview Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Enter Custom Hex Color:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = customHexText,
                                onValueChange = { input ->
                                    customHexText = input
                                    // Try to parse Hex color if length makes sense
                                    if (input.startsWith("#") && (input.length == 7 || input.length == 9)) {
                                        try {
                                            android.graphics.Color.parseColor(input)
                                            selectedBgColor = input
                                        } catch (e: Exception) {
                                            // Invalid hex value, don't update
                                        }
                                    } else if (!input.startsWith("#") && (input.length == 6 || input.length == 8)) {
                                        val formatted = "#$input"
                                        try {
                                            android.graphics.Color.parseColor(formatted)
                                            selectedBgColor = formatted
                                        } catch (e: Exception) {
                                            // Invalid hex value
                                        }
                                    }
                                },
                                placeholder = { Text("#FFFFFF") },
                                modifier = Modifier.weight(1f).testTag("custom_hex_input_field"),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                                singleLine = true,
                                label = { Text("Color Hex") }
                            )

                            // Preview Box of active selection
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            try {
                                                ComposeColor(android.graphics.Color.parseColor(selectedBgColor))
                                            } catch (e: Exception) {
                                                ComposeColor.LightGray
                                            }
                                        )
                                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                        .testTag("custom_color_preview_box")
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("Preview", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        )
    }

    // Modal: Success Dialog
    if (showExportSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showExportSuccessDialog = false },
            confirmButton = {
                Button(onClick = { showExportSuccessDialog = false }) { Text("Done") }
            },
            icon = {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Success",
                    tint = ComposeColor(0xFF4CAF50),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text("PDF Compiled Successfully!") },
            text = {
                Text(
                    "Your modified PDF document has been safely compiled, vector elements flattened, and written out to your device storage folder.",
                    textAlign = TextAlign.Center
                )
            }
        )
    }
}

@Composable
fun InteractiveOverlayWidget(
    element: PdfElement,
    scale: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPositionChange: (Float, Float) -> Unit,
    onResizeChange: (Float, Float) -> Unit,
    onEditTap: () -> Unit,
    onDeleteTap: () -> Unit
) {
    val context = LocalContext.current
    val systemDensity = context.resources.displayMetrics.density
    val displayX = element.x * scale
    val displayY = element.y * scale
    val displayWidth = element.width * scale
    val displayHeight = element.height * scale

    val currentElement by rememberUpdatedState(element)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnResizeChange by rememberUpdatedState(onResizeChange)

    Box(
        modifier = Modifier
            .offset(x = displayX.dp, y = displayY.dp)
            .size(width = displayWidth.dp, height = displayHeight.dp)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else ComposeColor.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .pointerInput(scale, systemDensity) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Convert drag displacement (pixels) back into PDF original point values
                        val scalePointsToPx = scale * systemDensity
                        val deltaXPoints = dragAmount.x / scalePointsToPx
                        val deltaYPoints = dragAmount.y / scalePointsToPx
                        currentOnPositionChange(currentElement.x + deltaXPoints, currentElement.y + deltaYPoints)
                    }
                )
            }
            .clickable { onSelect() }
    ) {
        // Render element contents based on type
        when (element) {
            is PdfElement.Whiteout -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = ComposeColor(android.graphics.Color.parseColor(element.colorHex)),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
            is PdfElement.Image -> {
                AsyncImage(
                    model = element.uriString,
                    contentDescription = "Overlay Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }
            is PdfElement.Text -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (element.backgroundColorHex != null) {
                                Modifier.background(ComposeColor(android.graphics.Color.parseColor(element.backgroundColorHex)))
                            } else Modifier
                        ),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = element.text,
                        fontSize = (element.fontSize * (scale * 0.95f)).sp, 
                        fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (element.isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                        color = ComposeColor(android.graphics.Color.parseColor(element.colorHex)),
                        lineHeight = (element.fontSize * (scale * 1.1f)).sp,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(1.dp)
                    )
                }
            }
            else -> {}
        }

        // Selected interactive action badges/corner handles overlay
        if (isSelected) {
            // Edit trigger (top-right corner)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { onEditTap() }
                    .testTag("element_edit_handle"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Edit Element",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }

            // Delete trigger (top-left corner)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clickable { onDeleteTap() }
                    .testTag("element_delete_handle"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete Element",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(12.dp)
                )
            }

            // Drag-resize corner handle (bottom-right corner)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 6.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .pointerInput(scale, systemDensity) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val scalePointsToPx = scale * systemDensity
                            val deltaWPoints = dragAmount.x / scalePointsToPx
                            val deltaHPoints = dragAmount.y / scalePointsToPx
                            currentOnResizeChange(currentElement.width + deltaWPoints, currentElement.height + deltaHPoints)
                        }
                    }
                    .testTag("element_resize_handle"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.OpenInFull,
                    contentDescription = "Resize Element",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * Utility to extract actual filename from storage SAF Uri correctly.
 */
private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result.substring(cut + 1)
        }
    }
    return result
}
