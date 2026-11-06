package com.lucascanceco.pdfstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

private val Purple = Color(0xFF6355D9)
private val Ink = Color(0xFF242334)
private val Back = Color(0xFFF7F6FB)
private val Pastels = listOf(Color(0xFFE5DFFF), Color(0xFFFFE1EB), Color(0xFFDFF5EC), Color(0xFFFFEAD9), Color(0xFFE3F2FC), Color(0xFFFFEDD1))

/** A bounded decode prevents large phone photos from exhausting the editor's memory. */
private fun decodeEditableImage(context: android.content.Context, uri: Uri, maxDimension: Int = 2400): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    return context.contentResolver.openInputStream(uri).use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun fileNameFor(context: android.content.Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
} catch (_: Exception) { uri.lastPathSegment.orEmpty().substringAfterLast('/') }

private fun suggestedCopyName(name: String, operation: String): String {
    val base = name.substringBeforeLast('.').ifBlank { "PDFStudio" }.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60)
    return "${base}-${operation}.pdf"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdfStudioApp(intent?.data?.takeIf { intent?.action == Intent.ACTION_VIEW }) }
    }
}

private enum class Tool { IMAGES, DOCUMENT, MERGE, EXTRACT, ROTATE, TEXT, ORIGINAL, PAGES, OCR, SIGNATURE, PROTECT, WATERMARK, COMPRESS, PREPARE, MARKUP }

@Composable
private fun PdfStudioApp(initialUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    val ocr = remember { OcrOperations(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val draftStore = remember { EditorDraftStore(context) }
    var availableDraft by remember { mutableStateOf<EditorDraftStore.Draft?>(null) }
    var resumedDraft by remember { mutableStateOf<EditorDraftStore.Draft?>(null) }
    var current by remember { mutableStateOf(initialUri) }
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var selectedTool by remember { mutableStateOf<Tool?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var pendingEditBatch by remember { mutableStateOf<List<PdfOperations.EditOperation>?>(null) }
    var saveRetry by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf("copia") }
    var pendingSourceName by remember { mutableStateOf<String?>(null) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var preparedSend by remember { mutableStateOf<Pair<File, Long>?>(null) }
    var sendLimit by remember { mutableStateOf("5") }
    var wordPreview by remember { mutableStateOf<DocxOperations.Conversion?>(null) }
    var wordSource by remember { mutableStateOf<Uri?>(null) }
    var wordIssue by remember { mutableStateOf<String?>(null) }
    val mergeSelection = remember { mutableStateListOf<Uri>() }
    val photoSelection = remember { mutableStateListOf<Uri>() }
    var deliveryPattern by remember { mutableStateOf("Fotos") }
    var imageToPlace by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var extractStart by remember { mutableStateOf("1") }
    var extractEnd by remember { mutableStateOf("1") }
    var insertText by remember { mutableStateOf("") }
    var textSize by remember { mutableFloatStateOf(16f) }
    var textX by remember { mutableFloatStateOf(.12f) }
    var textY by remember { mutableFloatStateOf(.15f) }
    var pageSequence by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var watermark by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Int>>(emptyList()) }
    var markupMode by remember { mutableStateOf(PdfOperations.Markup.HIGHLIGHT) }
    var markupX by remember { mutableFloatStateOf(.12f) }
    var markupY by remember { mutableFloatStateOf(.30f) }
    var markupW by remember { mutableFloatStateOf(.60f) }
    var markupH by remember { mutableFloatStateOf(.03f) }
    var ocrResult by remember { mutableStateOf<String?>(null) }
    var ocrChooser by remember { mutableStateOf(false) }
    val signature = remember { mutableStateListOf<List<Offset>>() }
    var signatureCurrent by remember { mutableStateOf<List<Offset>>(emptyList()) }

    fun rememberUri(uri: Uri) {
        val value = uri.toString()
        recent = (listOf(value) + recent.filterNot { it == value }).take(8)
        context.getSharedPreferences("recent", 0).edit().putString("uris", recent.joinToString("\n")).apply()
    }
    LaunchedEffect(Unit) {
        recent = context.getSharedPreferences("recent", 0).getString("uris", "")!!.lines().filter { it.isNotBlank() }
        availableDraft = draftStore.metadata()
        deliveryPattern = context.getSharedPreferences("delivery", 0).getString("pattern", "Fotos") ?: "Fotos"
    }
    LaunchedEffect(current) {
        val uri = current ?: return@LaunchedEffect
        busy = true
        try {
            count = ops.pageCount(uri)
            page = context.getSharedPreferences("reading", 0).getInt("page_${uri.toString().hashCode()}", 0).coerceIn(0, (count - 1).coerceAtLeast(0))
            if (resumedDraft?.document == uri) page = resumedDraft?.page?.coerceIn(0, (count - 1).coerceAtLeast(0)) ?: 0
            extractEnd = count.toString()
            rememberUri(uri)
            if (selectedTool == Tool.OCR) {
                selectedTool = null
                ocrChooser = true
            }
        } catch (e: Exception) {
            current = null
            snackbar.showSnackbar("No se pudo abrir: ${e.message ?: "PDF incompatible"}")
        } finally { busy = false }
    }
    LaunchedEffect(current, page) {
        current?.let { uri ->
            if (count > 0) context.getSharedPreferences("reading", 0).edit()
                .putInt("page_${uri.toString().hashCode()}", page).apply()
        }
    }

    fun perform(operation: String = "copia", action: suspend () -> File) {
        scope.launch {
            busy = true
            try {
                pending?.delete()
                pendingOperation = operation
                pending = action()
            } catch (e: Exception) {
                snackbar.showSnackbar("Operación fallida: ${e.message ?: "Archivo incompatible"}")
            } finally { busy = false }
        }
    }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
            current = uri
        } else if (current == null) selectedTool = null
    }
    val manyPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.size >= 2) {
            mergeSelection.clear(); mergeSelection.addAll(files)
        } else if (files.size == 1) scope.launch { snackbar.showSnackbar("Elegí al menos dos archivos para unir") }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) { photoSelection.clear(); photoSelection.addAll(files) }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment.orEmpty().lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isDocx = name.endsWith(".docx") || mime.contains("wordprocessingml")
            pendingSourceName = fileNameFor(context, uri)
            if (isDocx) scope.launch {
                busy = true
                wordSource = uri
                try { wordPreview = DocxOperations(context).convert(uri); wordIssue = null }
                catch (e: Exception) { wordIssue = e.message ?: "Maquetación no compatible" }
                finally { busy = false }
            }
            else perform("texto") { ops.documentToPdf(uri, false) }
        }
    }
    val stampPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && current != null) {
            val bitmap = try {
                decodeEditableImage(context, uri)
            } catch (_: Exception) { null }
            if (bitmap != null) imageToPlace = bitmap
            else scope.launch { snackbar.showSnackbar("No se pudo abrir la imagen") }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pending
        if (uri == null) {
            if (pendingEditBatch == null) { file?.delete(); pending = null }
            else saveRetry = true
        } else if (file != null) scope.launch {
            busy = true
            try {
                ops.export(file, uri)
                file.delete(); pending = null
                saveRetry = false
                pendingEditBatch?.let { edits ->
                    edits.mapNotNull { it.image }.distinct().forEach { it.recycle() }
                    pendingEditBatch = null
                    draftStore.clear(); availableDraft = null; resumedDraft = null
                    selectedTool = null; imageToPlace = null
                }
                lastSavedUri = uri
                snackbar.showSnackbar("PDF guardado correctamente")
            } catch (e: Exception) {
                saveRetry = true
                snackbar.showSnackbar("No se pudo escribir: ${e.message}. Podés elegir otro destino.")
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(pending) {
        if (pending != null) save.launch(suggestedCopyName(pendingSourceName ?: current?.let { fileNameFor(context, it) } ?: "PDFStudio", pendingOperation))
    }

    val scheme = lightColorScheme(primary = Purple, background = Back, surface = Color.White, onSurface = Ink)
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (current == null) NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = current == null, onClick = { current = null }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
                    NavigationBarItem(selected = current != null, onClick = { openPdf.launch(arrayOf("application/pdf")) }, icon = { Icon(Icons.Default.FolderOpen, null) }, label = { Text("Abrir") })
                    NavigationBarItem(selected = false, onClick = { images.launch(arrayOf("image/*")) }, icon = { Icon(Icons.Default.AddCircle, null) }, label = { Text("Crear") })
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).background(Back)) {
                if (current == null) {
                    HomeScreen(recent, availableDraft, deliveryPattern, { openPdf.launch(arrayOf("application/pdf")) }, { tool ->
                        selectedTool = tool
                        when (tool) {
                            Tool.IMAGES -> images.launch(arrayOf("image/*"))
                            Tool.DOCUMENT -> documentPicker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                            Tool.MERGE -> manyPdf.launch(arrayOf("application/pdf"))
                            else -> if (current == null) openPdf.launch(arrayOf("application/pdf"))
                        }
                    }, { uri -> current = Uri.parse(uri) }, { draft ->
                        val restored = draftStore.load()
                        if (restored != null) {
                            resumedDraft = restored
                            page = restored.page
                            selectedTool = Tool.TEXT
                            current = restored.document
                        }
                    }, { images.launch(arrayOf("image/*")) })
                } else {
                    ViewerScreen(current!!, page, count, busy, onBack = { current = null }, onPage = { page = it }, onSearch = { searchOpen = true }, onExtract = { selectedTool = Tool.EXTRACT }, onRotate = { perform("rotado") { ops.rotate(current!!) } }, onText = { selectedTool = Tool.TEXT }, onOriginal = { selectedTool = Tool.ORIGINAL }, onPages = {
                        pageSequence = (1..count).joinToString(",")
                        selectedTool = Tool.PAGES
                    }, onImage = { stampPicker.launch("image/*") }, onSignature = { selectedTool = Tool.SIGNATURE }, onMarkup = { selectedTool = Tool.MARKUP }, onProtect = { selectedTool = Tool.PROTECT }, onWatermark = { selectedTool = Tool.WATERMARK }, onCompress = { selectedTool = Tool.COMPRESS }, onPrepare = { selectedTool = Tool.PREPARE }, onSearchable = {
                        val chosen = current!!
                        perform { ocr.createSearchableCopy(chosen) }
                    }, onOcr = { ocrChooser = true })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (selectedTool == Tool.EXTRACT && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Extraer páginas") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Elegí el rango de páginas (1 a $count).")
                    OutlinedTextField(extractStart, { extractStart = it }, label = { Text("Desde") }, singleLine = true)
                    OutlinedTextField(extractEnd, { extractEnd = it }, label = { Text("Hasta") }, singleLine = true)
                }
            }, confirmButton = { TextButton(onClick = {
                val a = extractStart.toIntOrNull(); val b = extractEnd.toIntOrNull()
                if (a != null && b != null && a in 1..count && b in a..count) perform { ops.extract(current!!, a, b) }
                else scope.launch { snackbar.showSnackbar("Rango inválido") }
                selectedTool = null
            }) { Text("Extraer") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
        }
        if (mergeSelection.isNotEmpty()) {
            MergePreview(mergeSelection.toList(), onDismiss = { mergeSelection.clear() }, onMerge = { ordered ->
                pendingSourceName = fileNameFor(context, ordered.first())
                perform("unido") { ops.merge(ordered) }
                mergeSelection.clear()
            })
        }
        if (photoSelection.isNotEmpty()) {
            PhotoOrderPreview(photoSelection.toList(), deliveryPattern,
                onDismiss = { photoSelection.clear() },
                onCreate = { ordered, pattern ->
                    deliveryPattern = pattern
                    context.getSharedPreferences("delivery", 0).edit().putString("pattern", pattern).apply()
                    pendingSourceName = pattern
                    perform("entrega") { ops.imagesToPdf(ordered) }
                    photoSelection.clear()
                })
        }
        if ((selectedTool == Tool.TEXT || selectedTool == Tool.MARKUP || imageToPlace != null) && current != null) {
            val source = current!!
            BatchEditor(
                uri = source,
                initialPage = page,
                pageCount = count,
                startWithText = selectedTool == Tool.TEXT,
                initialImage = imageToPlace,
                initialEdits = resumedDraft?.takeIf { it.document == source }?.edits ?: emptyList(),
                initialPending = resumedDraft?.takeIf { it.document == source }?.pending,
                onDismiss = { selectedTool = null; imageToPlace = null; resumedDraft = null },
                onKeepDraft = { edits, active, draftPage ->
                    scope.launch {
                        busy = true
                        try {
                            draftStore.save(source, fileNameFor(context, source), draftPage, edits, active)
                            availableDraft = draftStore.metadata()
                            edits.mapNotNull { it.image }.distinct().forEach { it.recycle() }
                            active.image?.takeUnless { it.isRecycled }?.recycle()
                            resumedDraft = null; selectedTool = null; imageToPlace = null
                            snackbar.showSnackbar("Borrador guardado en este teléfono")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("No se pudo conservar el borrador: ${e.message}")
                        } finally { busy = false }
                    }
                },
                onSave = { edits ->
                    scope.launch {
                        busy = true
                        try {
                            pending?.delete(); pending = null
                            val prepared = ops.applyEdits(source, edits)
                            pendingEditBatch = edits
                            pendingOperation = "editado"
                            pending = prepared
                        } catch (e: Exception) {
                            snackbar.showSnackbar("No se pudo preparar el PDF: ${e.message}. La edición sigue abierta.")
                        } finally { busy = false }
                    }
                }
            )
        }
        if (selectedTool == Tool.PAGES && current != null) {
            val source = current!!
            PageOrganizer(source, count, onDismiss = { selectedTool = null }, onSave = { order ->
                perform { ops.reorder(source, order) }
                selectedTool = null
            })
        }
        if (selectedTool == Tool.ORIGINAL && current != null) {
            val source = current!!
            OriginalTextEditor(source, page,
                onDismiss = { selectedTool = null },
                onReplace = { old, replacement ->
                    scope.launch {
                        busy = true
                        try {
                            val result = OriginalTextOperations(context).replace(source, page, old, replacement)
                            pendingOperation = "texto-corregido"
                            pending = result
                            selectedTool = null
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Reemplazo no compatible: ${e.message}")
                        } finally { busy = false }
                    }
                }
            )
        }
        if (ocrResult != null) {
            AlertDialog(onDismissRequest = { ocrResult = null }, title = { Text("Texto reconocido — página ${page + 1}") }, text = {
                Column {
                    Text("OCR sin conexión. Revisá el resultado antes de utilizarlo.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(ocrResult ?: "", { ocrResult = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
                }
            }, confirmButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR PDF Studio", ocrResult))
                ocrResult = null
            }) { Text("Copiar") } }, dismissButton = { TextButton(onClick = { ocrResult = null }) { Text("Cerrar") } })
        }
        if (ocrChooser && current != null) {
            val source = current!!
            OcrPageChooser(source, count, page, onDismiss = { ocrChooser = false }, onConfirm = { chosenPage ->
                page = chosenPage
                ocrChooser = false
                scope.launch {
                    busy = true
                    try { ocrResult = ocr.recognize(source, chosenPage).ifBlank { "No se reconoció texto en esta página." } }
                    catch (e: Exception) { snackbar.showSnackbar("No se pudo reconocer texto: ${e.message}") }
                    finally { busy = false }
                }
            })
        }
        if (selectedTool == Tool.SIGNATURE && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Firmar página ${page + 1}") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dibujá tu firma con el dedo. Se insertará sobre una copia del PDF.")
                    Canvas(Modifier.fillMaxWidth().height(170.dp).background(Color(0xFFF5F3FA), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start -> signatureCurrent = listOf(start) },
                                onDragEnd = { if (signatureCurrent.isNotEmpty()) signature.add(signatureCurrent); signatureCurrent = emptyList() },
                                onDragCancel = { signatureCurrent = emptyList() }
                            ) { change, _ ->
                                signatureCurrent = signatureCurrent + change.position
                                change.consume()
                            }
                        }) {
                        (signature.toList() + listOf(signatureCurrent)).forEach { line ->
                            if (line.size > 1) {
                                val path = Path().apply {
                                    moveTo(line.first().x, line.first().y)
                                    line.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, Ink, style = Stroke(width = 3f))
                            }
                        }
                    }
                    TextButton(onClick = { signature.clear(); signatureCurrent = emptyList() }) { Text("Borrar firma") }
                }
            }, confirmButton = { TextButton(onClick = {
                if (signature.any { it.size > 1 }) {
                    val lines = signature.map { it.toList() }
                    val bitmap = android.graphics.Bitmap.createBitmap(900, 300, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(36, 35, 52)
                        strokeWidth = 5f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        style = android.graphics.Paint.Style.STROKE
                    }
                    val maxX = lines.flatten().maxOf { it.x }.coerceAtLeast(1f)
                    val maxY = lines.flatten().maxOf { it.y }.coerceAtLeast(1f)
                    lines.forEach { points ->
                        points.zipWithNext().forEach { (a, b) ->
                            canvas.drawLine(a.x / maxX * 860f, a.y / maxY * 270f, b.x / maxX * 860f, b.y / maxY * 270f, paint)
                        }
                    }
                    imageToPlace = bitmap
                }
                signature.clear()
                selectedTool = null
            }) { Text("Insertar firma") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
        }
        if ((selectedTool == Tool.PROTECT || selectedTool == Tool.WATERMARK) && current != null) {
            val isProtect = selectedTool == Tool.PROTECT
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text(if (isProtect) "Proteger con contraseña" else "Agregar marca de agua") }, text = {
                Column {
                    if (isProtect) {
                        Text("La contraseña será necesaria para abrir la copia. Guardala en un lugar seguro.")
                        OutlinedTextField(password, { password = it }, label = { Text("Contraseña (4–64 caracteres)") }, singleLine = true)
                    } else {
                        Text("La marca de agua se aplicará a todas las páginas.")
                        OutlinedTextField(watermark, { watermark = it.take(80) }, label = { Text("Texto de la marca") })
                    }
                }
            }, confirmButton = { TextButton(onClick = {
                val chosen = current!!
                val chosenPassword = password
                val chosenWatermark = watermark
                if (isProtect && chosenPassword.length in 4..64) perform { ops.passwordProtect(chosen, chosenPassword) }
                else if (!isProtect && chosenWatermark.isNotBlank()) perform { ops.addWatermark(chosen, chosenWatermark) }
                else scope.launch { snackbar.showSnackbar("Ingresá un valor válido") }
                password = ""
                selectedTool = null
            }) { Text("Guardar copia") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
        }
        if (searchOpen && current != null) {
            AlertDialog(onDismissRequest = { searchOpen = false }, title = { Text("Buscar en el PDF") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Palabra o frase") }, singleLine = true)
                    Text(if (searchResults.isEmpty()) "Sin resultados todavía" else "Encontrado en las páginas: ${searchResults.joinToString(", ")}")
                }
            }, confirmButton = { TextButton(onClick = {
                val chosen = current!!
                scope.launch {
                    busy = true
                    try {
                        searchResults = ops.search(chosen, searchQuery)
                        searchResults.firstOrNull()?.let { page = it - 1 }
                    } catch (e: Exception) { snackbar.showSnackbar("Búsqueda fallida: ${e.message}") }
                    finally { busy = false }
                }
            }) { Text("Buscar") } }, dismissButton = { TextButton(onClick = { searchOpen = false }) { Text("Cerrar") } })
        }
        if (selectedTool == Tool.COMPRESS && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Comprimir PDF") }, text = {
                Text("Esta opción convierte las páginas en imágenes JPEG para intentar reducir el tamaño. Se perderán el texto seleccionable, los enlaces y los formularios interactivos. El archivo original no se modifica; verificá que el nuevo PDF sea realmente más pequeño.")
            }, confirmButton = { TextButton(onClick = {
                val chosen = current!!
                perform { ops.compressRasterized(chosen) }
                selectedTool = null
            }) { Text("Crear copia compacta") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
        }
        if (selectedTool == Tool.PREPARE && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Preparar para enviar") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Elegí el tamaño máximo. Esta compresión rasteriza las páginas: se pierden enlaces, formularios y texto seleccionable. El original permanece intacto.")
                    OutlinedTextField(sendLimit, { sendLimit = it.filter(Char::isDigit).take(3) }, label = { Text("Límite en MB (ej.: 5)") }, singleLine = true)
                } },
                confirmButton = { TextButton(onClick = {
                    val limit = sendLimit.toLongOrNull()?.takeIf { it in 1..100 }
                    if (limit == null) scope.launch { snackbar.showSnackbar("Elegí un límite entre 1 y 100 MB") }
                    else {
                        val source = current!!
                        selectedTool = null
                        scope.launch {
                            busy = true
                            try {
                                var result = ops.compressRasterized(source, .65f)
                                if (result.length() > limit * 1024L * 1024L) {
                                    val smaller = ops.compressRasterized(source, .40f)
                                    result.delete(); result = smaller
                                }
                                preparedSend = result to limit
                            } catch (e: Exception) { snackbar.showSnackbar("No se pudo preparar el archivo: ${e.message}") }
                            finally { busy = false }
                        }
                    }
                }) { Text("Analizar resultado") } },
                dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
        }
        preparedSend?.let { (file, limit) ->
            val mb = file.length() / (1024.0 * 1024.0)
            var zoomPreview by remember { mutableFloatStateOf(1f) }
            AlertDialog(onDismissRequest = { file.delete(); preparedSend = null }, title = { Text("Resultado para enviar") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Peso obtenido: %.2f MB · Máximo elegido: %d MB".format(Locale.US, mb, limit), fontWeight = FontWeight.Bold)
                    Text(if (mb > limit) "Supera el límite. Podés guardarlo igual o probar con otro objetivo." else "Cumple el límite elegido.", color = if (mb > limit) MaterialTheme.colorScheme.error else Purple)
                    Text("Ampliá la vista para comprobar que la firma o el sello se lean.")
                    PageThumbnail(Uri.fromFile(file), 0, Modifier.fillMaxWidth().height((150 * zoomPreview).dp))
                    Slider(zoomPreview, { zoomPreview = it }, valueRange = 1f..2f)
                    Text("La copia se rasterizó: no conserva enlaces ni texto seleccionable.", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = {
                    preparedSend = null
                    pendingOperation = "para-enviar"; pending = file
                }) { Text("Guardar copia") } },
                dismissButton = { TextButton(onClick = { file.delete(); preparedSend = null }) { Text("Cancelar") } })
        }
        wordPreview?.let { preview ->
            AlertDialog(onDismissRequest = { preview.file.delete(); wordPreview = null },
                title = { Text("Vista previa · Word a PDF") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${preview.pages} páginas · ${preview.tables} tablas")
                    PageThumbnail(Uri.fromFile(preview.file), 0, Modifier.fillMaxWidth().height(260.dp))
                    Text(preview.warning, style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = {
                    pending?.delete(); pendingSourceName = wordSource?.let { fileNameFor(context, it) }
                    pendingOperation = "Word"; pending = preview.file; wordPreview = null
                }) { Text("Guardar PDF") } },
                dismissButton = { TextButton(onClick = {
                    preview.file.delete(); wordPreview = null
                    wordSource?.let { source -> perform("texto-Word") { ops.documentToPdf(source, true) } }
                }) { Text("Crear PDF con el texto de Word") } }
            )
        }
        if (wordIssue != null) AlertDialog(onDismissRequest = { wordIssue = null },
            title = { Text("Diseño de Word no compatible") },
            text = { Text("${wordIssue.orEmpty()}\n\nPodés crear una copia con el texto, sin conservar tablas ni formatos, o cancelar.") },
            confirmButton = { TextButton(onClick = {
                wordIssue = null
                wordSource?.let { source -> perform("texto-Word") { ops.documentToPdf(source, true) } }
            }) { Text("Crear PDF con el texto de Word") } },
            dismissButton = { TextButton(onClick = { wordIssue = null }) { Text("Cancelar") } })
        if (saveRetry && pending != null) AlertDialog(onDismissRequest = { saveRetry = false },
            title = { Text("Guardar la copia") },
            text = { Text("El destino anterior se canceló o falló. Los cambios siguen intactos. Elegí otra carpeta para reintentar o volvé al editor.") },
            confirmButton = { TextButton(onClick = {
                saveRetry = false
                save.launch(suggestedCopyName(pendingSourceName ?: current?.let { fileNameFor(context, it) } ?: "PDFStudio", pendingOperation))
            }) { Text("Elegir otra carpeta") } },
            dismissButton = { TextButton(onClick = { saveRetry = false }) { Text("Seguir editando") } })
        lastSavedUri?.let { savedUri ->
            AlertDialog(onDismissRequest = { lastSavedUri = null }, title = { Text("PDF guardado") },
                text = { Text("La copia está lista. Podés abrirla en otro lector o compartirla.") },
                confirmButton = { TextButton(onClick = {
                    lastSavedUri = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(savedUri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (e: Exception) { scope.launch { snackbar.showSnackbar("No hay otro lector disponible: ${e.message}") } }
                }) { Text("Abrir copia") } },
                dismissButton = { Row {
                    TextButton(onClick = {
                        lastSavedUri = null
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, savedUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Compartir PDF"))
                    }) { Text("Compartir") }
                    TextButton(onClick = { lastSavedUri = null }) { Text("Cerrar") }
                } })
        }
    }
}

/** Non-destructive edit session. All positions are page-relative, not screen-relative. */
@Composable
private fun BatchEditor(
    uri: Uri,
    initialPage: Int,
    pageCount: Int,
    startWithText: Boolean,
    initialImage: android.graphics.Bitmap?,
    initialEdits: List<PdfOperations.EditOperation>,
    initialPending: EditorDraftStore.Pending?,
    onDismiss: () -> Unit,
    onKeepDraft: (List<PdfOperations.EditOperation>, EditorDraftStore.Pending, Int) -> Unit,
    onSave: (List<PdfOperations.EditOperation>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val operations = remember { PdfOperations(context) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val edits = remember { mutableStateListOf<PdfOperations.EditOperation>().apply { addAll(initialEdits) } }
    var page by remember { mutableIntStateOf(initialPage) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var mode by remember { mutableStateOf(initialPending?.mode ?: if (initialImage != null) PdfOperations.EditKind.IMAGE else if (startWithText) PdfOperations.EditKind.TEXT else PdfOperations.EditKind.HIGHLIGHT) }
    var imageToAdd by remember { mutableStateOf(initialPending?.image ?: initialImage) }
    var imageWidth by remember { mutableFloatStateOf(initialPending?.imageWidth ?: .45f) }
    var textToAdd by remember { mutableStateOf(initialPending?.text ?: "") }
    var fontSize by remember { mutableFloatStateOf(initialPending?.fontSize ?: 16f) }
    var first by remember { mutableStateOf(initialPending?.let { p -> if (p.x != null && p.y != null) Offset(p.x, p.y) else null }) }
    var last by remember { mutableStateOf(initialPending?.let { p -> if (p.lastX != null && p.lastY != null) Offset(p.lastX, p.lastY) else null }) }
    var renderError by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedEdit by remember { mutableIntStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { chosen ->
        if (chosen != null) {
            val picked = try { decodeEditableImage(context, chosen) }
            catch (_: Exception) { null }
            if (picked != null) {
                imageToAdd?.recycle()
                imageToAdd = picked
                mode = PdfOperations.EditKind.IMAGE
                first = null; last = null
            }
        }
    }

    LaunchedEffect(uri, page) {
        bitmap = null
        renderError = false
        bitmap = try { operations.renderPage(uri, page, 1200) } catch (_: Exception) { renderError = true; null }
        if (initialPending == null || page != initialPage) { first = null; last = null }
        selectedEdit = -1
    }
    val selectionReady = first != null && (mode == PdfOperations.EditKind.TEXT || mode == PdfOperations.EditKind.IMAGE || (last != null && abs(last!!.x - first!!.x) > .01f))

    fun addToSession() {
        val a = first ?: return
        if (!selectionReady) return
        val b = last ?: a
        if (mode == PdfOperations.EditKind.TEXT) {
            if (textToAdd.isBlank()) return
            edits.add(PdfOperations.EditOperation(page, mode, a.x, a.y, text = textToAdd, fontSize = fontSize))
            textToAdd = ""
            keyboard?.hide()
        } else if (mode == PdfOperations.EditKind.IMAGE) {
            val image = imageToAdd ?: return
            edits.add(PdfOperations.EditOperation(page, mode, a.x, a.y, width = imageWidth, image = image))
            imageToAdd = null
        } else {
            val x = min(a.x, b.x)
            val y = min(a.y, b.y)
            edits.add(PdfOperations.EditOperation(page, mode, x, y,
                width = abs(a.x - b.x).coerceAtLeast(.005f),
                height = abs(a.y - b.y).coerceAtLeast(.003f)))
        }
        first = null; last = null
    }

    fun discard() {
        imageToAdd?.recycle()
        edits.mapNotNull { it.image }.forEach { it.recycle() }
        onDismiss()
    }

    fun currentSession(): List<PdfOperations.EditOperation>? {
        val a = first
        val active = when (mode) {
            PdfOperations.EditKind.TEXT -> {
                if (textToAdd.isBlank() && a == null) null
                else if (textToAdd.isBlank()) { validationMessage = "Escribí el texto antes de continuar."; return null }
                else if (a == null) { validationMessage = "Tocá la página para elegir dónde colocar el texto."; return null }
                else PdfOperations.EditOperation(page, mode, a.x, a.y, text = textToAdd, fontSize = fontSize)
            }
            PdfOperations.EditKind.IMAGE -> {
                if (imageToAdd == null) null
                else if (a == null) { validationMessage = "Ubicá la imagen sobre la página antes de guardar."; return null }
                else PdfOperations.EditOperation(page, mode, a.x, a.y, width = imageWidth, image = imageToAdd)
            }
            else -> {
                if (a == null) null
                else if (!selectionReady) { validationMessage = "Completá la selección arrastrando el dedo."; return null }
                else {
                    val b = last ?: a
                    PdfOperations.EditOperation(page, mode, min(a.x, b.x), min(a.y, b.y),
                        width = abs(a.x - b.x).coerceAtLeast(.005f), height = abs(a.y - b.y).coerceAtLeast(.003f))
                }
            }
        }
        val result = edits.toList() + listOfNotNull(active)
        if (result.isEmpty()) { validationMessage = "Todavía no hay cambios para guardar."; return null }
        validationMessage = null
        return result
    }

    fun askToExit() {
        if (edits.isNotEmpty() || imageToAdd != null || textToAdd.isNotBlank() || first != null) confirmExit = true
        else discard()
    }

    Dialog(onDismissRequest = ::askToExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), color = Back) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::askToExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                    Column(Modifier.weight(1f)) {
                        Text("Editar PDF", fontWeight = FontWeight.Bold)
                        Text("${edits.size} cambios sin guardar", style = MaterialTheme.typography.labelSmall, color = Purple)
                    }
                    TextButton(onClick = { currentSession()?.let(onSave) }) {
                        Icon(Icons.Default.Check, null); Text("Guardar PDF")
                    }
                }
                validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page--; first = null; last = null }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Página anterior") }
                    Text("Página ${page + 1} de $pageCount", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { page++; first = null; last = null }, enabled = page + 1 < pageCount) { Icon(Icons.Default.ChevronRight, "Página siguiente") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PdfOperations.EditKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = mode == option, onClick = { mode = option; first = null; last = null }, shape = SegmentedButtonDefaults.itemShape(index, PdfOperations.EditKind.entries.size)) {
                            Text(when (option) {
                                PdfOperations.EditKind.TEXT -> "Texto"
                                PdfOperations.EditKind.IMAGE -> "Imagen"
                                PdfOperations.EditKind.HIGHLIGHT -> "Resaltar"
                                PdfOperations.EditKind.UNDERLINE -> "Subrayar"
                                PdfOperations.EditKind.STRIKEOUT -> "Tachar"
                            }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectionMode, onClick = { selectionMode = !selectionMode; selectedEdit = -1 }, label = { Text("Seleccionar cambios") })
                    if (selectedEdit in edits.indices) {
                        TextButton(onClick = {
                            val edit = edits.removeAt(selectedEdit)
                            mode = edit.kind; page = edit.pageIndex
                            first = Offset(edit.x, edit.y)
                            last = if (edit.kind == PdfOperations.EditKind.TEXT || edit.kind == PdfOperations.EditKind.IMAGE) null
                                else Offset(edit.x + edit.width, edit.y + edit.height)
                            if (edit.kind == PdfOperations.EditKind.TEXT) { textToAdd = edit.text; fontSize = edit.fontSize }
                            if (edit.kind == PdfOperations.EditKind.IMAGE) { imageToAdd = edit.image; imageWidth = edit.width }
                            selectionMode = false; selectedEdit = -1
                        }) { Text("Modificar") }
                        TextButton(onClick = { edits.removeAt(selectedEdit).image?.recycle(); selectedEdit = -1 }) { Text("Eliminar") }
                    }
                }
                if (mode == PdfOperations.EditKind.TEXT) {
                    OutlinedTextField(textToAdd, { textToAdd = it.take(1000) }, label = { Text("Texto a colocar") }, modifier = Modifier.fillMaxWidth(), maxLines = 2, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fontSize.toInt()} pt", modifier = Modifier.width(58.dp))
                        Slider(fontSize, { fontSize = it }, valueRange = 6f..72f, modifier = Modifier.weight(1f))
                    }
                }
                if (mode == PdfOperations.EditKind.IMAGE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (imageToAdd == null) "Elegir imagen" else "Cambiar imagen") }
                        Text("Tamaño ${(imageWidth * 100).toInt()}%", modifier = Modifier.padding(start = 8.dp))
                    }
                    Slider(imageWidth, { imageWidth = it }, valueRange = .08f..1f)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (edits.isNotEmpty()) edits.removeAt(edits.lastIndex).image?.recycle() }, enabled = edits.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Deshacer") }
                    Button(onClick = ::addToSession, enabled = selectionReady && (mode != PdfOperations.EditKind.TEXT || textToAdd.isNotBlank()) && (mode != PdfOperations.EditKind.IMAGE || imageToAdd != null), modifier = Modifier.weight(1f)) { Text("Aplicar cambio") }
                }
                val latest = edits.lastOrNull()
                if (latest != null && latest.pageIndex == page) {
                    TextButton(onClick = {
                        val edit = edits.removeAt(edits.lastIndex)
                        mode = edit.kind
                        first = Offset(edit.x, edit.y)
                        last = if (edit.kind == PdfOperations.EditKind.TEXT || edit.kind == PdfOperations.EditKind.IMAGE) null
                            else Offset(edit.x + edit.width, edit.y + edit.height)
                        if (edit.kind == PdfOperations.EditKind.TEXT) {
                            textToAdd = edit.text; fontSize = edit.fontSize
                        } else if (edit.kind == PdfOperations.EditKind.IMAGE) {
                            imageToAdd?.recycle()
                            imageToAdd = edit.image
                            imageWidth = edit.width
                        }
                    }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Reeditar último cambio de esta página") }
                }
                Text(when (mode) {
                    PdfOperations.EditKind.TEXT -> "El texto se previsualiza en vivo. Tocá la página para reubicarlo."
                    PdfOperations.EditKind.IMAGE -> "Tocá o arrastrá la imagen para ubicarla y pulsá Aplicar cambio."
                    else -> "Arrastrá para seleccionar la zona y pulsá Aplicar cambio."
                }, style = MaterialTheme.typography.labelSmall)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE9E8F1), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image == null) {
                        if (renderError) Text("No se pudo visualizar esta página") else CircularProgressIndicator()
                    } else {
                        val ratio = image.width.toFloat() / image.height
                        val drawWidth = if (maxWidth / ratio <= maxHeight) maxWidth else maxHeight * ratio
                        val drawHeight = drawWidth / ratio
                        val gestureModifier = if (selectionMode) {
                            Modifier.pointerInput(page, edits.size) {
                                detectTapGestures { tap ->
                                    val px = tap.x / size.width; val py = tap.y / size.height
                                    selectedEdit = edits.indices.reversed().firstOrNull { index ->
                                        val edit = edits[index]
                                        if (edit.pageIndex != page) false else {
                                            val w = when (edit.kind) {
                                                PdfOperations.EditKind.TEXT -> (edit.text.length * edit.fontSize * .55f / 595f).coerceAtLeast(.04f)
                                                else -> edit.width
                                            }
                                            val h = when (edit.kind) {
                                                PdfOperations.EditKind.TEXT -> (edit.fontSize / 842f).coerceAtLeast(.02f)
                                                PdfOperations.EditKind.IMAGE -> edit.image?.let { w * it.height.toFloat() / it.width * size.width / size.height } ?: .1f
                                                else -> edit.height.coerceAtLeast(.02f)
                                            }
                                            px in (edit.x - .015f)..(edit.x + w + .015f) &&
                                                py in (edit.y - h - .015f)..(edit.y + h + .015f)
                                        }
                                    } ?: -1
                                }
                            }.pointerInput(page, selectedEdit) {
                                detectDragGestures { change, drag ->
                                    val index = selectedEdit
                                    if (index in edits.indices && edits[index].pageIndex == page) {
                                        val edit = edits[index]
                                        edits[index] = edit.copy(
                                            x = (edit.x + drag.x / size.width).coerceIn(0f, 1f),
                                            y = (edit.y + drag.y / size.height).coerceIn(0f, 1f)
                                        )
                                        change.consume()
                                    }
                                }
                            }
                        } else if (mode == PdfOperations.EditKind.TEXT) {
                            Modifier.pointerInput(page, mode) {
                                detectTapGestures { tap ->
                                    first = Offset((tap.x / size.width).coerceIn(0f, 1f), (tap.y / size.height).coerceIn(0f, 1f))
                                    last = null
                                }
                            }
                        } else if (mode == PdfOperations.EditKind.IMAGE) {
                            Modifier.pointerInput(page, mode, imageWidth) {
                                detectTapGestures { tap ->
                                    first = Offset((tap.x / size.width).coerceIn(0f, (1f - imageWidth).coerceAtLeast(0f)), (tap.y / size.height).coerceIn(0f, 1f))
                                }
                            }.pointerInput(page, mode, imageWidth) {
                                detectDragGestures(onDragStart = { start ->
                                    if (first == null) first = Offset((start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f))
                                }) { change, drag ->
                                    val previous = first ?: Offset.Zero
                                    first = Offset(
                                        (previous.x + drag.x / size.width).coerceIn(0f, (1f - imageWidth).coerceAtLeast(0f)),
                                        (previous.y + drag.y / size.height).coerceIn(0f, 1f)
                                    )
                                    change.consume()
                                }
                            }
                        } else {
                            Modifier.pointerInput(page, mode) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        first = Offset((start.x / size.width).coerceIn(0f, 1f), (start.y / size.height).coerceIn(0f, 1f))
                                        last = first
                                    },
                                    onDragCancel = { first = null; last = null }
                                ) { change, _ ->
                                    last = Offset((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                                    change.consume()
                                }
                            }
                        }
                        Box(Modifier.size(drawWidth, drawHeight).clip(RoundedCornerShape(3.dp))) {
                            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize().then(gestureModifier)) {
                                fun drawMarkup(kind: PdfOperations.EditKind, x: Float, y: Float, w: Float, h: Float) {
                                    val left = x * size.width
                                    val top = y * size.height
                                    val width = w * size.width
                                    val height = h * size.height
                                    if (kind == PdfOperations.EditKind.HIGHLIGHT) {
                                        drawRect(Color(0xFFFFD32D), Offset(left, top), androidx.compose.ui.geometry.Size(width, height), alpha = .35f)
                                    } else {
                                        val lineY = if (kind == PdfOperations.EditKind.UNDERLINE) top + height else top + height / 2f
                                        drawLine(Color(0xFFE64E69), Offset(left, lineY), Offset(left + width, lineY), strokeWidth = 2.dp.toPx())
                                    }
                                }
                                edits.forEachIndexed { index, edit ->
                                    if (edit.pageIndex != page) return@forEachIndexed
                                    if (edit.kind == PdfOperations.EditKind.TEXT) {
                                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = android.graphics.Color.rgb(28, 28, 40)
                                            textSize = edit.fontSize * size.width / 595f
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(edit.text.replace('\n', ' '), edit.x * size.width, edit.y * size.height, paint)
                                    } else if (edit.kind == PdfOperations.EditKind.IMAGE) {
                                        edit.image?.let { image ->
                                            val w = size.width * edit.width
                                            val h = w * image.height / image.width
                                            val left = edit.x * size.width; val top = edit.y * size.height
                                            drawContext.canvas.nativeCanvas.drawBitmap(image, null, android.graphics.RectF(left, top, left + w, top + h), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                                        }
                                    } else drawMarkup(edit.kind, edit.x, edit.y, edit.width, edit.height)
                                    if (selectionMode && selectedEdit == index) {
                                        val w = if (edit.kind == PdfOperations.EditKind.TEXT) (edit.text.length * edit.fontSize * .55f / 595f).coerceAtLeast(.04f) else edit.width
                                        val h = if (edit.kind == PdfOperations.EditKind.IMAGE) edit.image?.let { w * it.height.toFloat() / it.width * size.width / size.height } ?: .1f
                                            else edit.height.coerceAtLeast(.02f)
                                        drawRect(Purple, Offset(edit.x * size.width, edit.y * size.height),
                                            androidx.compose.ui.geometry.Size(w * size.width, h * size.height),
                                            style = Stroke(width = 2.dp.toPx()))
                                    }
                                }
                                val a = first
                                if (a != null) {
                                    if (mode == PdfOperations.EditKind.TEXT) {
                                        val anchor = Offset(a.x * size.width, a.y * size.height)
                                        if (textToAdd.isNotBlank()) {
                                            val previewPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                color = android.graphics.Color.rgb(28, 28, 40)
                                                textSize = fontSize * size.width / 595f
                                            }
                                            drawContext.canvas.nativeCanvas.drawText(textToAdd.replace('\n', ' '), anchor.x, anchor.y, previewPaint)
                                        }
                                        drawCircle(Purple, radius = 3.dp.toPx(), center = anchor)
                                        drawLine(Purple, anchor.copy(x = anchor.x - 9.dp.toPx()), anchor.copy(x = anchor.x + 9.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                                        drawLine(Purple, anchor.copy(y = anchor.y - 9.dp.toPx()), anchor.copy(y = anchor.y + 9.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                                    } else if (mode == PdfOperations.EditKind.IMAGE) {
                                        imageToAdd?.let { image ->
                                            val w = size.width * imageWidth
                                            val h = w * image.height / image.width
                                            val left = a.x * size.width; val top = a.y * size.height
                                            drawContext.canvas.nativeCanvas.drawBitmap(image, null, android.graphics.RectF(left, top, left + w, top + h), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                                            drawRect(Purple, Offset(left, top), androidx.compose.ui.geometry.Size(w, h), style = Stroke(width = 2.dp.toPx()))
                                        }
                                    } else {
                                        val b = last ?: a
                                        val x = min(a.x, b.x); val y = min(a.y, b.y)
                                        val w = abs(a.x - b.x); val h = abs(a.y - b.y)
                                        drawMarkup(mode, x, y, w, h)
                                        drawRect(Purple, Offset(x * size.width, y * size.height), androidx.compose.ui.geometry.Size(w * size.width, h * size.height), style = Stroke(width = 2.dp.toPx()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("Podés seguir editando, conservar un borrador privado para retomarlo o descartar los cambios.") },
            confirmButton = { TextButton(onClick = {
                confirmExit = false
                onKeepDraft(edits.toList(), EditorDraftStore.Pending(mode, textToAdd, first?.x, first?.y,
                    last?.x, last?.y, fontSize, imageWidth, imageToAdd), page)
            }) { Text("Guardar borrador") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") }
                    TextButton(onClick = { confirmExit = false; discard() }) { Text("Descartar") }
                }
            }
        )
    }
}

/** Visual ordering with thumbnails, duplication, deletion, and reversible changes. */
@Composable
private fun PageOrganizer(uri: Uri, pageCount: Int, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    val order = remember(uri, pageCount) { mutableStateListOf<Int>().apply { addAll(0 until pageCount) } }
    var selected by remember { mutableIntStateOf(0) }
    var destination by remember { mutableStateOf("") }
    var showMoveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    fun swap(a: Int, b: Int) {
        if (a in order.indices && b in order.indices) {
            val item = order[a]; order[a] = order[b]; order[b] = item
            selected = b
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize().navigationBarsPadding(), color = Back) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancelar") }
                    Text("Organizar páginas", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSave(order.toList()) }, enabled = order.isNotEmpty()) { Text("Guardar PDF") }
                }
                Text("Tocá una miniatura o mantenela pulsada para arrastrarla. También podés mover, duplicar o eliminar.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { swap(selected, selected - 1) }, enabled = selected > 0) { Icon(Icons.Default.ArrowBack, "Mover izquierda") }
                    IconButton(onClick = { swap(selected, selected + 1) }, enabled = selected + 1 < order.size) { Icon(Icons.Default.ArrowForward, "Mover derecha") }
                    TextButton(onClick = { if (selected in order.indices) { order.add(selected + 1, order[selected]); selected++ } }, enabled = order.isNotEmpty()) { Text("Duplicar") }
                    TextButton(onClick = { if (order.size > 1 && selected in order.indices) { order.removeAt(selected); selected = selected.coerceAtMost(order.lastIndex) } }, enabled = order.size > 1) { Text("Eliminar") }
                    TextButton(onClick = { destination = (selected + 1).toString(); showMoveDialog = true }, enabled = order.isNotEmpty()) { Text("Mover a posición…") }
                }
                Text("${order.size} páginas en la copia", color = Purple, style = MaterialTheme.typography.labelMedium)
                LazyRow(Modifier.fillMaxWidth().weight(1f), state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                    items(order.size) { index ->
                        Column(
                            Modifier.width(138.dp)
                                .background(if (selected == index) Color(0xFFE4DEFF) else Color.White, RoundedCornerShape(14.dp))
                                .clickable { selected = index }
                                .pointerInput(index, order.size) {
                                    var dragged = 0f
                                    var draggedIndex = index
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { selected = index; draggedIndex = index; dragged = 0f }
                                    ) { change, movement ->
                                        dragged += movement.x
                                        val step = 148.dp.toPx()
                                        while (abs(dragged) >= step && draggedIndex in order.indices) {
                                            val next = draggedIndex + if (dragged > 0f) 1 else -1
                                            if (next !in order.indices) break
                                            swap(draggedIndex, next)
                                            draggedIndex = next
                                            dragged += if (dragged > 0f) -step else step
                                            val first = listState.firstVisibleItemIndex
                                            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
                                            if (draggedIndex >= last - 1 || draggedIndex <= first + 1) {
                                                scrollScope.launch { listState.animateScrollToItem(draggedIndex.coerceIn(0, order.lastIndex)) }
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PageThumbnail(uri, order[index], Modifier.fillMaxWidth().height(190.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("Posición ${index + 1}", fontWeight = FontWeight.SemiBold)
                            Text("Original ${order[index] + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedButton(onClick = { order.clear(); order.addAll(0 until pageCount); selected = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Restablecer orden original") }
            }
        }
    }
    if (showMoveDialog) AlertDialog(onDismissRequest = { showMoveDialog = false },
        title = { Text("Mover página a posición") },
        text = { OutlinedTextField(destination, { destination = it.filter(Char::isDigit) }, label = { Text("Posición (1 a ${order.size})") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val target = destination.toIntOrNull()?.minus(1)
            if (target != null && target in order.indices && selected in order.indices) {
                val item = order.removeAt(selected)
                order.add(target, item)
                selected = target
                scrollScope.launch { listState.animateScrollToItem(target) }
                showMoveDialog = false
            }
        }) { Text("Mover") } },
        dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text("Cancelar") } })
}

@Composable
private fun PageThumbnail(uri: Uri, pageIndex: Int, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    var preview by remember(uri, pageIndex) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri, pageIndex) {
        preview = try { ops.renderPage(uri, pageIndex, 260) } catch (_: Exception) { null }
    }
    Box(modifier.background(Color(0xFFEBEAF1), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap == null) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        else Image(bitmap.asImageBitmap(), "Página original ${pageIndex + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Image placement preview follows the actual PDF page bounds and keeps its aspect ratio. */
@Composable
private fun ImagePlacementEditor(
    uri: Uri,
    initialPage: Int,
    pageCount: Int,
    firstImage: android.graphics.Bitmap,
    onDismiss: () -> Unit,
    onSave: (List<PdfOperations.ImageStamp>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val operations = remember { PdfOperations(context) }
    val stamps = remember { mutableStateListOf<PdfOperations.ImageStamp>() }
    var stamp by remember { mutableStateOf<android.graphics.Bitmap?>(firstImage) }
    var page by remember { mutableIntStateOf(initialPage) }
    var background by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var x by remember { mutableFloatStateOf(.12f) }
    var y by remember { mutableFloatStateOf(.20f) }
    var width by remember { mutableFloatStateOf(.45f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { chosen ->
        if (chosen != null) {
            val bitmap = try { context.contentResolver.openInputStream(chosen).use { android.graphics.BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null }
            if (bitmap != null) {
                stamp?.recycle()
                stamp = bitmap
                x = .12f; y = .20f; width = .45f
            }
        }
    }
    fun closeWithoutSaving() {
        stamp?.recycle()
        stamps.forEach { it.bitmap.recycle() }
        onDismiss()
    }
    fun applyImage() {
        val image = stamp ?: return
        stamps.add(PdfOperations.ImageStamp(page, image, x, y, width))
        stamp = null
    }
    LaunchedEffect(uri, page) {
        background = try { operations.renderPage(uri, page, 1200) } catch (_: Exception) { null }
    }
    Dialog(onDismissRequest = ::closeWithoutSaving, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize().navigationBarsPadding(), color = Back) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::closeWithoutSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancelar") }
                    Column(Modifier.weight(1f)) {
                        Text("Editar imágenes", fontWeight = FontWeight.Bold)
                        Text("${stamps.size} imágenes aplicadas", style = MaterialTheme.typography.labelSmall, color = Purple)
                    }
                    TextButton(onClick = {
                        stamp?.recycle()
                        stamp = null
                        onSave(stamps.toList())
                    }, enabled = stamps.isNotEmpty()) { Text("Guardar PDF") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Anterior") }
                    Text("Página ${page + 1} de $pageCount")
                    IconButton(onClick = { page++ }, enabled = page + 1 < pageCount) { Icon(Icons.Default.ChevronRight, "Siguiente") }
                }
                if (stamp != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tamaño ${(width * 100).toInt()}%", modifier = Modifier.width(105.dp))
                        Slider(width, {
                            width = it
                            x = x.coerceIn(0f, (1f - width).coerceAtLeast(0f))
                        }, valueRange = .08f..1f, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text("La imagen quedó aplicada. Podés sumar otra o guardar todas juntas.", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Elegir otra") }
                    Button(onClick = ::applyImage, enabled = stamp != null, modifier = Modifier.weight(1f)) { Text("Aplicar imagen") }
                }
                if (stamps.isNotEmpty()) {
                    TextButton(onClick = {
                        val removed = stamps.removeAt(stamps.lastIndex)
                        removed.bitmap.recycle()
                    }) { Text("Deshacer última imagen") }
                }
                Text("Arrastrá la imagen para ubicarla y mirá el resultado antes de aplicar.", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE9E8F1), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    val pdf = background
                    if (pdf == null) CircularProgressIndicator()
                    else {
                        val ratio = pdf.width.toFloat() / pdf.height
                        val drawWidth = if (maxWidth / ratio <= maxHeight) maxWidth else maxHeight * ratio
                        val drawHeight = drawWidth / ratio
                        Box(Modifier.size(drawWidth, drawHeight)) {
                            Image(pdf.asImageBitmap(), "PDF", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            val gestures = if (stamp != null) Modifier.pointerInput(page, stamp, width) {
                                detectDragGestures(onDragStart = { position ->
                                    stamp?.let { active ->
                                        val heightFraction = width * active.height.toFloat() / active.width * size.width / size.height
                                        x = (position.x / size.width - width / 2f).coerceIn(0f, (1f - width).coerceAtLeast(0f))
                                        y = (position.y / size.height - heightFraction / 2f).coerceIn(0f, (1f - heightFraction).coerceAtLeast(0f))
                                    }
                                }) { change, drag ->
                                    stamp?.let { active ->
                                        val heightFraction = width * active.height.toFloat() / active.width * size.width / size.height
                                        x = (x + drag.x / size.width).coerceIn(0f, (1f - width).coerceAtLeast(0f))
                                        y = (y + drag.y / size.height).coerceIn(0f, (1f - heightFraction).coerceAtLeast(0f))
                                        change.consume()
                                    }
                                }
                            } else Modifier
                            Canvas(Modifier.fillMaxSize().then(gestures)) {
                                fun drawStamp(image: android.graphics.Bitmap, leftFraction: Float, topFraction: Float, widthFraction: Float, selected: Boolean) {
                                    val targetWidth = size.width * widthFraction
                                    val targetHeight = targetWidth * image.height / image.width
                                    val left = leftFraction * size.width
                                    val top = topFraction * size.height
                                    val target = android.graphics.RectF(left, top, left + targetWidth, top + targetHeight)
                                    drawContext.canvas.nativeCanvas.drawBitmap(image, null, target, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                                    if (selected) drawRect(Purple, Offset(left, top), androidx.compose.ui.geometry.Size(targetWidth, targetHeight), style = Stroke(width = 2.dp.toPx()))
                                }
                                stamps.filter { it.pageIndex == page }.forEach { drawStamp(it.bitmap, it.x, it.y, it.width, false) }
                                stamp?.let { drawStamp(it, x, y, width, true) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-page WYSIWYG placement: gesture coordinates come from the actual rendered page bounds. */
@Composable
private fun PlacementEditor(
    uri: Uri,
    page: Int,
    isText: Boolean,
    onDismiss: () -> Unit,
    onSave: (Float, Float, Float, Float, String, Float, PdfOperations.Markup) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    var bitmap by remember(uri, page) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var position by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var value by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var mark by remember { mutableStateOf(PdfOperations.Markup.HIGHLIGHT) }

    LaunchedEffect(uri, page) {
        bitmap = try { ops.renderPage(uri, page, 1200) } catch (_: Exception) { null }
    }
    val canSave = position != null && (if (isText) value.isNotBlank() else dragEnd != null && abs(dragEnd!!.x - position!!.x) > 10f)
    fun savePlacement() {
        val a = position ?: return
        if (pageSize.width == 0 || pageSize.height == 0) return
        val b = dragEnd ?: a
        val left = if (isText) a.x else min(a.x, b.x)
        val top = if (isText) a.y else min(a.y, b.y)
        val width = if (isText) 0f else abs(b.x - a.x)
        val height = if (isText) 0f else abs(b.y - a.y)
        onSave(
            (left / pageSize.width).coerceIn(0f, 1f),
            (top / pageSize.height).coerceIn(0f, 1f),
            (width / pageSize.width).coerceIn(.02f, 1f),
            (height / pageSize.height).coerceIn(.005f, 1f),
            value, fontSize, mark
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = Back) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancelar") }
                    Text(if (isText) "Colocar texto · Página ${page + 1}" else "Marcar · Página ${page + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::savePlacement, enabled = canSave) { Icon(Icons.Default.Check, null); Text("Guardar") }
                }
                Text(if (isText) "Tocá la página en el punto exacto donde querés escribir." else "Arrastrá el dedo para seleccionar la zona. Podés repetir el gesto para corregir.", style = MaterialTheme.typography.bodySmall)
                if (!isText) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PdfOperations.Markup.entries.forEachIndexed { index, mode ->
                            SegmentedButton(selected = mark == mode, onClick = { mark = mode }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                                Text(when (mode) { PdfOperations.Markup.HIGHLIGHT -> "Resaltar"; PdfOperations.Markup.UNDERLINE -> "Subrayar"; PdfOperations.Markup.STRIKEOUT -> "Tachar" }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE9E8F1), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image == null) CircularProgressIndicator()
                    else {
                        val ratio = image.width.toFloat() / image.height
                        val drawWidth = if (maxWidth / ratio <= maxHeight) maxWidth else maxHeight * ratio
                        val drawHeight = drawWidth / ratio
                        val gestures = if (isText) {
                            Modifier.pointerInput(uri, page) {
                                detectTapGestures { tap ->
                                    position = tap
                                    dragEnd = null
                                }
                            }
                        } else {
                            Modifier.pointerInput(uri, page) {
                                detectDragGestures(
                                    onDragStart = { position = it; dragEnd = it },
                                    onDragEnd = {}, onDragCancel = { position = null; dragEnd = null }
                                ) { change, _ ->
                                    dragEnd = change.position
                                    change.consume()
                                }
                            }
                        }
                        Box(Modifier.size(drawWidth, drawHeight).clip(RoundedCornerShape(3.dp)).onSizeChanged { pageSize = it }) {
                            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize().then(gestures)) {
                                val first = position
                                if (first != null) {
                                    if (isText) {
                                        drawCircle(Purple, radius = 7.dp.toPx(), center = first)
                                        drawLine(Purple, first.copy(x = first.x - 12.dp.toPx()), first.copy(x = first.x + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
                                        drawLine(Purple, first.copy(y = first.y - 12.dp.toPx()), first.copy(y = first.y + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
                                    } else {
                                        val second = dragEnd ?: first
                                        val left = min(first.x, second.x)
                                        val top = min(first.y, second.y)
                                        val w = abs(second.x - first.x)
                                        val h = abs(second.y - first.y)
                                        drawRect(Color(0xFFFFD32D), topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(w, h), alpha = .35f)
                                        drawRect(Purple, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(w, h), style = Stroke(width = 2.dp.toPx()))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (isText) {
                    OutlinedTextField(value, { value = it.take(1000) }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fontSize.toInt()} pt", modifier = Modifier.width(55.dp))
                        Slider(fontSize, { fontSize = it }, valueRange = 6f..72f, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { position = null; dragEnd = null }, modifier = Modifier.weight(1f)) { Text("Reubicar") }
                    Button(onClick = ::savePlacement, enabled = canSave, modifier = Modifier.weight(1f)) { Text("Guardar copia") }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    recent: List<String>, draft: EditorDraftStore.Draft?, deliveryPattern: String, open: () -> Unit,
    action: (Tool) -> Unit, openRecent: (String) -> Unit,
    resumeDraft: (EditorDraftStore.Draft) -> Unit, repeatDelivery: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.linearGradient(listOf(Color(0xFF5750C9), Color(0xFF9D8DEC), Color(0xFFFFD5C9))))) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PDF Studio", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Tus documentos, a tu manera.", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Todo lo que necesitás, sin conexión.", color = Color.White.copy(alpha = .85f))
                }
            }
        }
        if (draft != null) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp)
                .background(Color(0xFFE4DEFF), RoundedCornerShape(16.dp))
                .clickable { resumeDraft(draft) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EditNote, null, tint = Purple)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recuperar borrador", fontWeight = FontWeight.Bold)
                    Text(draft.documentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR")).format(Date(draft.modifiedAt)), style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Purple)
            }
        }
        if (recent.isNotEmpty()) item {
            val uri = Uri.parse(recent.first())
            val lastPage = context.getSharedPreferences("reading", 0).getInt("page_${uri.toString().hashCode()}", 0)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                .background(Color.White, RoundedCornerShape(16.dp)).clickable { openRecent(recent.first()) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PageThumbnail(uri, lastPage, Modifier.size(width = 58.dp, height = 78.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Continuar", fontWeight = FontWeight.Bold, color = Purple)
                    Text(fileNameFor(context, uri), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Última página: ${lastPage + 1}", style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp).offset(y = (-16).dp).background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp)) {
                Text("Acciones rápidas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Abrir PDF", Icons.Default.FolderOpen, Pastels[0], Modifier.weight(1f)) { open() }
                    ToolCard("Fotos a PDF", Icons.Default.AddPhotoAlternate, Pastels[1], Modifier.weight(1f)) { action(Tool.IMAGES) }
                    ToolCard("Editar", Icons.Default.Edit, Pastels[2], Modifier.weight(1f)) { action(Tool.TEXT) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Unir PDF", Icons.Default.FileCopy, Pastels[3], Modifier.weight(1f)) { action(Tool.MERGE) }
                    ToolCard("Texto/Word a PDF", Icons.Default.Article, Pastels[4], Modifier.weight(1f)) { action(Tool.DOCUMENT) }
                    ToolCard("Extraer texto", Icons.Default.DocumentScanner, Pastels[5], Modifier.weight(1f)) { action(Tool.OCR) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Elegí una acción. Si necesita un documento, te pediremos que abras el PDF. Para más herramientas, entrá al visor.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                if (deliveryPattern != "Fotos") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = repeatDelivery, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Repeat, null); Spacer(Modifier.width(8.dp)); Text("Repetir una entrega · $deliveryPattern", maxLines = 1)
                    }
                }
            }
        }
        item { Text("Documentos recientes", Modifier.padding(start = 22.dp, top = 8.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (recent.isEmpty()) item { Text("Todavía no hay documentos recientes.", Modifier.padding(horizontal = 22.dp), color = Color.Gray) }
        items(recent) { value ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).background(Color.White, RoundedCornerShape(16.dp)).clickable { openRecent(value) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFE66A73))
                Spacer(Modifier.width(12.dp))
                Text(Uri.parse(value).lastPathSegment ?: "Documento PDF", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
        }
    }
}

@Composable
private fun ToolCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.background(color.copy(alpha = if (enabled) 1f else .4f), RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 18.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) Purple else Color.LightGray, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (enabled) Ink else Color.Gray)
    }
}

@Composable
private fun OriginalTextEditor(uri: Uri, page: Int, onDismiss: () -> Unit, onReplace: (String, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val original = remember { OriginalTextOperations(context) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf("") }
    LaunchedEffect(uri, page) {
        try { lines = original.candidates(uri, page) }
        catch (e: Exception) { error = e.message ?: "No se pudo analizar la página" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Modificar texto original · Página ${page + 1}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Solo líneas nativas simples con una única secuencia de texto. No se tapa texto ni se deja la versión anterior oculta.", style = MaterialTheme.typography.bodySmall)
                if (error != null) Text("Incompatible: $error. Alternativa: OCR o Anotar.", color = MaterialTheme.colorScheme.error)
                else if (lines.isEmpty()) Text("No se encontraron líneas nativas compatibles. Para escaneos, usá OCR; para otros PDF, Anotar.")
                else lines.take(50).forEach { line ->
                    Row(Modifier.fillMaxWidth().clickable { selected = line; replacement = line }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == line, onClick = { selected = line; replacement = line })
                        Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (selected != null) {
                    Text("Texto original: $selected", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(replacement, { replacement = it.take(180) }, label = { Text("Reemplazar por") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let { onReplace(it, replacement) } }, enabled = selected != null && replacement.isNotBlank() && replacement != selected) { Text("Crear copia corregida") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun MergePreview(files: List<Uri>, onDismiss: () -> Unit, onMerge: (List<Uri>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    val order = remember(files) { mutableStateListOf<Uri>().apply { addAll(files) } }
    val counts = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(files) {
        files.forEach { uri -> counts[uri.toString()] = try { ops.pageCount(uri) } catch (_: Exception) { -1 } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Preparar unión") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Revisá el orden antes de generar el PDF. Podés poner una carátula al principio aunque la hayas elegido al final.", style = MaterialTheme.typography.bodySmall)
                order.forEachIndexed { index, uri ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFFF0EDF9), RoundedCornerShape(12.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PageThumbnail(uri, 0, Modifier.size(42.dp, 58.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fileNameFor(context, uri), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text("${counts[uri.toString()]?.takeIf { it >= 0 } ?: "?"} páginas", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { if (index > 0) { val item = order.removeAt(index); order.add(index - 1, item) } }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
                        IconButton(onClick = { if (index < order.lastIndex) { val item = order.removeAt(index); order.add(index + 1, item) } }, enabled = index < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Bajar") }
                        IconButton(onClick = { if (index > 0) { val item = order.removeAt(index); order.add(0, item) } }, enabled = index > 0) { Icon(Icons.Default.FirstPage, "Carátula al principio") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(order.toList()) }, enabled = order.size >= 2 && order.all { (counts[it.toString()] ?: 0) > 0 }) { Text("Unir ${order.size} archivos") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun PhotoOrderPreview(files: List<Uri>, suggestedPattern: String, onDismiss: () -> Unit, onCreate: (List<Uri>, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val order = remember(files) { mutableStateListOf<Uri>().apply { addAll(files) } }
    var pattern by remember { mutableStateOf(suggestedPattern) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Fotos a PDF · Preparar entrega") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ordená las fotos nuevas. El patrón de nombre se recuerda para la próxima entrega.")
            OutlinedTextField(pattern, { pattern = it.take(60) }, label = { Text("Patrón del archivo") }, modifier = Modifier.fillMaxWidth())
            order.forEachIndexed { index, uri ->
                var thumb by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(uri) { thumb = try { decodeEditableImage(context, uri, 260) } catch (_: Exception) { null } }
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = thumb
                    if (bitmap != null) Image(bitmap.asImageBitmap(), "Foto ${index + 1}", Modifier.size(50.dp, 65.dp), contentScale = ContentScale.Fit)
                    else Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(8.dp))
                    Text(fileNameFor(context, uri), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { if (index > 0) { val item = order.removeAt(index); order.add(index - 1, item) } }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
                    IconButton(onClick = { if (index < order.lastIndex) { val item = order.removeAt(index); order.add(index + 1, item) } }, enabled = index < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Bajar") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { onCreate(order.toList(), pattern.ifBlank { "Fotos" }) }) { Text("Crear PDF de ${order.size} fotos") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun OcrPageChooser(uri: Uri, count: Int, initialPage: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var chosen by remember { mutableIntStateOf(initialPage) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Copiar texto de una página") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Elegí la página para reconocer con OCR sin conexión.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(count) { index ->
                        Column(Modifier.width(105.dp).background(if (chosen == index) Color(0xFFE5DFFF) else Back, RoundedCornerShape(12.dp))
                            .clickable { chosen = index }.padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            PageThumbnail(uri, index, Modifier.size(88.dp, 125.dp))
                            Text("Página ${index + 1}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text("Extraer texto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun ViewerScreen(uri: Uri, page: Int, count: Int, busy: Boolean, onBack: () -> Unit, onPage: (Int) -> Unit, onSearch: () -> Unit, onExtract: () -> Unit, onRotate: () -> Unit, onText: () -> Unit, onOriginal: () -> Unit, onPages: () -> Unit, onImage: () -> Unit, onSignature: () -> Unit, onMarkup: () -> Unit, onOcr: () -> Unit, onProtect: () -> Unit, onWatermark: () -> Unit, onCompress: () -> Unit, onPrepare: () -> Unit, onSearchable: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    LaunchedEffect(uri, page, retry) {
        image = null
        zoom = 1f
        pan = Offset.Zero; renderError = null
        try { image = ops.renderPage(uri, page) }
        catch (e: Exception) { renderError = e.message ?: "Archivo incompatible" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(fileNameFor(context, uri), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onSearch, enabled = !busy) { Icon(Icons.Default.Search, "Buscar texto") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFE9E8F1)), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null && renderError == null) CircularProgressIndicator()
            else if (renderError != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo cargar la página ${page + 1}", fontWeight = FontWeight.Bold)
                Text(renderError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { retry++ }) { Text("Reintentar") }
            }
            else Image(bitmap!!.asImageBitmap(), "Página ${page + 1}", Modifier.fillMaxSize()
                .pointerInput(uri, page) {
                    detectTransformGestures { centroid, gesturePan, gestureZoom, _ ->
                        val next = (zoom * gestureZoom).coerceIn(1f, 6f)
                        val factor = next / zoom
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val shift = (centroid - center) * (1f - factor)
                        val candidate = pan + gesturePan + shift
                        val limitX = (next - 1f) * size.width / 2f
                        val limitY = (next - 1f) * size.height / 2f
                        pan = Offset(candidate.x.coerceIn(-limitX, limitX), candidate.y.coerceIn(-limitY, limitY))
                        zoom = next
                    }
                }.graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().background(Color.White), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Anterior") }
            TextButton(onClick = { jumpText = (page + 1).toString(); jumpDialog = true }) { Text("${page + 1} / $count") }
            IconButton(onClick = { onPage(page + 1) }, enabled = page + 1 < count) { Icon(Icons.Default.ChevronRight, "Siguiente") }
        }
        Row(Modifier.fillMaxWidth().background(Color.White), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Anotar", "Páginas", "Más").forEach { label ->
                Box {
                    TextButton(onClick = { openMenu = label }) { Text(label, fontWeight = FontWeight.SemiBold) }
                    DropdownMenu(expanded = openMenu == label, onDismissRequest = { openMenu = null }) {
                        val actions: List<Pair<String, () -> Unit>> = when (label) {
                            "Anotar" -> listOf("Agregar texto" to onText, "Agregar imagen" to onImage, "Dibujar firma" to onSignature, "Resaltar / subrayar" to onMarkup, "Modificar texto original" to onOriginal)
                            "Páginas" -> listOf("Organizar páginas" to onPages, "Extraer páginas" to onExtract, "Rotar PDF" to onRotate)
                            else -> listOf("Copiar texto de una página" to onOcr, "Crear PDF con búsqueda (OCR)" to onSearchable,
                                "Marca de agua" to onWatermark, "Proteger con clave" to onProtect,
                                "Comprimir" to onCompress, "Preparar para enviar" to onPrepare,
                                "Acerca de" to { showAbout = true })
                        }
                        actions.forEach { (text, action) ->
                            DropdownMenuItem(text = { Text(text) }, onClick = { openMenu = null; action() })
                        }
                    }
                }
            }
        }
    }
    if (jumpDialog) AlertDialog(onDismissRequest = { jumpDialog = false }, title = { Text("Ir a la página") },
        text = { OutlinedTextField(jumpText, { jumpText = it.filter(Char::isDigit) }, label = { Text("Página (1 a $count)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            jumpText.toIntOrNull()?.takeIf { it in 1..count }?.let { onPage(it - 1); jumpDialog = false }
        }) { Text("Ir") } },
        dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("Cancelar") } })
    if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("Acerca de PDF Studio") },
        text = { Text("Versión ${BuildConfig.VERSION_NAME} (código ${BuildConfig.VERSION_CODE})\nCommit de compilación: ${BuildConfig.BUILD_COMMIT}\nFunciona sin conexión. Tus archivos permanecen en el dispositivo.") },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Cerrar") } })
}
