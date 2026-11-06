package com.lucascanceco.pdfstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdfStudioApp(intent?.data?.takeIf { intent?.action == Intent.ACTION_VIEW }) }
    }
}

private enum class Tool { IMAGES, DOCUMENT, MERGE, EXTRACT, ROTATE, TEXT, PAGES, OCR, SIGNATURE, PROTECT, WATERMARK, COMPRESS, MARKUP }

@Composable
private fun PdfStudioApp(initialUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    val ocr = remember { OcrOperations(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var current by remember { mutableStateOf(initialUri) }
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var selectedTool by remember { mutableStateOf<Tool?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
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
    val signature = remember { mutableStateListOf<List<Offset>>() }
    var signatureCurrent by remember { mutableStateOf<List<Offset>>(emptyList()) }

    fun rememberUri(uri: Uri) {
        val value = uri.toString()
        recent = (listOf(value) + recent.filterNot { it == value }).take(8)
        context.getSharedPreferences("recent", 0).edit().putString("uris", recent.joinToString("\n")).apply()
    }
    LaunchedEffect(Unit) {
        recent = context.getSharedPreferences("recent", 0).getString("uris", "")!!.lines().filter { it.isNotBlank() }
    }
    LaunchedEffect(current) {
        val uri = current ?: return@LaunchedEffect
        busy = true
        try {
            count = ops.pageCount(uri)
            page = 0
            extractEnd = count.toString()
            rememberUri(uri)
        } catch (e: Exception) {
            current = null
            snackbar.showSnackbar("No se pudo abrir: ${e.message ?: "PDF incompatible"}")
        } finally { busy = false }
    }

    fun perform(action: suspend () -> File) {
        scope.launch {
            busy = true
            try {
                pending?.delete()
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
        }
    }
    val manyPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) perform { ops.merge(files) }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) perform { ops.imagesToPdf(files) }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment.orEmpty().lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isDocx = name.endsWith(".docx") || mime.contains("wordprocessingml")
            perform { ops.documentToPdf(uri, isDocx) }
        }
    }
    val stampPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && current != null) {
            val bitmap = try {
                context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            if (bitmap != null) imageToPlace = bitmap
            else scope.launch { snackbar.showSnackbar("No se pudo abrir la imagen") }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pending
        if (uri != null && file != null) scope.launch {
            busy = true
            try { ops.export(file, uri); snackbar.showSnackbar("PDF guardado correctamente") }
            catch (e: Exception) { snackbar.showSnackbar("Error al guardar: ${e.message}") }
            finally { file.delete(); pending = null; busy = false }
        }
    }
    LaunchedEffect(pending) {
        if (pending != null) save.launch("PDFStudio_${System.currentTimeMillis()}.pdf")
    }

    val scheme = lightColorScheme(primary = Purple, background = Back, surface = Color.White, onSurface = Ink)
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = current == null, onClick = { current = null }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
                    NavigationBarItem(selected = current != null, onClick = { openPdf.launch(arrayOf("application/pdf")) }, icon = { Icon(Icons.Default.FolderOpen, null) }, label = { Text("Abrir") })
                    NavigationBarItem(selected = false, onClick = { images.launch(arrayOf("image/*")) }, icon = { Icon(Icons.Default.AddCircle, null) }, label = { Text("Crear") })
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).background(Back)) {
                if (current == null) {
                    HomeScreen(recent, { openPdf.launch(arrayOf("application/pdf")) }, { tool ->
                        selectedTool = tool
                        when (tool) {
                            Tool.IMAGES -> images.launch(arrayOf("image/*"))
                            Tool.DOCUMENT -> documentPicker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                            Tool.MERGE -> manyPdf.launch(arrayOf("application/pdf"))
                            else -> if (current == null) openPdf.launch(arrayOf("application/pdf"))
                        }
                    }, { uri -> current = Uri.parse(uri) })
                } else {
                    ViewerScreen(current!!, page, count, busy, onBack = { current = null }, onPage = { page = it }, onSearch = { searchOpen = true }, onExtract = { selectedTool = Tool.EXTRACT }, onRotate = { perform { ops.rotate(current!!) } }, onText = { selectedTool = Tool.TEXT }, onPages = {
                        pageSequence = (1..count).joinToString(",")
                        selectedTool = Tool.PAGES
                    }, onImage = { stampPicker.launch("image/*") }, onSignature = { selectedTool = Tool.SIGNATURE }, onMarkup = { selectedTool = Tool.MARKUP }, onProtect = { selectedTool = Tool.PROTECT }, onWatermark = { selectedTool = Tool.WATERMARK }, onCompress = { selectedTool = Tool.COMPRESS }, onSearchable = {
                        val chosen = current!!
                        perform { ocr.createSearchableCopy(chosen) }
                    }, onOcr = {
                        scope.launch {
                            busy = true
                            try { ocrResult = ocr.recognize(current!!, page).ifBlank { "No se reconoció texto en esta página." } }
                            catch (e: Exception) { snackbar.showSnackbar("No se pudo reconocer texto: ${e.message}") }
                            finally { busy = false }
                        }
                    })
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
        if ((selectedTool == Tool.TEXT || selectedTool == Tool.MARKUP) && current != null) {
            val source = current!!
            BatchEditor(
                uri = source,
                initialPage = page,
                pageCount = count,
                startWithText = selectedTool == Tool.TEXT,
                onDismiss = { selectedTool = null },
                onSave = { edits ->
                    perform { ops.applyEdits(source, edits) }
                    selectedTool = null
                }
            )
        }
        val selectedImage = imageToPlace
        if (selectedImage != null && current != null) {
            val source = current!!
            val imagePage = page
            ImagePlacementEditor(source, imagePage, count, selectedImage,
                onDismiss = { imageToPlace = null },
                onSave = { stamps ->
                    imageToPlace = null
                    perform {
                        try { ops.stampImages(source, stamps) }
                        finally { stamps.forEach { it.bitmap.recycle() } }
                    }
                }
            )
        }
        if (selectedTool == Tool.PAGES && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Organizar páginas") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escribí los números en el orden deseado. Podés repetir una página para duplicarla o quitarla de la lista para eliminarla.")
                    OutlinedTextField(pageSequence, { pageSequence = it }, label = { Text("Orden de páginas") })
                    Text("Ejemplo: 3,1,2,2", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = {
                val parts = pageSequence.split(",").map { it.trim().toIntOrNull() }
                if (parts.isNotEmpty() && parts.all { it != null && it in 1..count }) {
                    val indices = parts.map { it!! - 1 }
                    perform { ops.reorder(current!!, indices) }
                } else scope.launch { snackbar.showSnackbar("La lista contiene páginas inválidas") }
                selectedTool = null
            }) { Text("Guardar copia") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
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
                    perform {
                        val bitmap = android.graphics.Bitmap.createBitmap(900, 300, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(36, 35, 52)
                            strokeWidth = 5f
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            style = android.graphics.Paint.Style.STROKE
                        }
                        // Input coordinates are in screen pixels; normalize to a 900x300 image.
                        val maxX = lines.flatten().maxOf { it.x }.coerceAtLeast(1f)
                        val maxY = lines.flatten().maxOf { it.y }.coerceAtLeast(1f)
                        lines.forEach { points ->
                            points.zipWithNext().forEach { (a, b) ->
                                canvas.drawLine(a.x / maxX * 860f, a.y / maxY * 270f, b.x / maxX * 860f, b.y / maxY * 270f, paint)
                            }
                        }
                        try { ops.stampImage(current!!, page, bitmap, .15f, .75f, .4f) }
                        finally { bitmap.recycle() }
                    }
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
    }
}

/** Non-destructive edit session. All positions are page-relative, not screen-relative. */
@Composable
private fun BatchEditor(
    uri: Uri,
    initialPage: Int,
    pageCount: Int,
    startWithText: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<PdfOperations.EditOperation>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val operations = remember { PdfOperations(context) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val edits = remember { mutableStateListOf<PdfOperations.EditOperation>() }
    var page by remember { mutableIntStateOf(initialPage) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var mode by remember { mutableStateOf(if (startWithText) PdfOperations.EditKind.TEXT else PdfOperations.EditKind.HIGHLIGHT) }
    var textToAdd by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var first by remember { mutableStateOf<Offset?>(null) }
    var last by remember { mutableStateOf<Offset?>(null) }
    var renderError by remember { mutableStateOf(false) }

    LaunchedEffect(uri, page) {
        bitmap = null
        renderError = false
        bitmap = try { operations.renderPage(uri, page, 1200) } catch (_: Exception) { renderError = true; null }
        first = null; last = null
    }
    val selectionReady = first != null && (mode == PdfOperations.EditKind.TEXT || (last != null && abs(last!!.x - first!!.x) > .01f))

    fun addToSession() {
        val a = first ?: return
        if (!selectionReady) return
        val b = last ?: a
        if (mode == PdfOperations.EditKind.TEXT) {
            if (textToAdd.isBlank()) return
            edits.add(PdfOperations.EditOperation(page, mode, a.x, a.y, text = textToAdd, fontSize = fontSize))
            textToAdd = ""
            keyboard?.hide()
        } else {
            val x = min(a.x, b.x)
            val y = min(a.y, b.y)
            edits.add(PdfOperations.EditOperation(page, mode, x, y,
                width = abs(a.x - b.x).coerceAtLeast(.005f),
                height = abs(a.y - b.y).coerceAtLeast(.003f)))
        }
        first = null; last = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), color = Back) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancelar") }
                    Column(Modifier.weight(1f)) {
                        Text("Editar PDF", fontWeight = FontWeight.Bold)
                        Text("${edits.size} cambios sin guardar", style = MaterialTheme.typography.labelSmall, color = Purple)
                    }
                    TextButton(onClick = { onSave(edits.toList()) }, enabled = edits.isNotEmpty()) {
                        Icon(Icons.Default.Check, null); Text("Guardar PDF")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page--; first = null; last = null }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Página anterior") }
                    Text("Página ${page + 1} de $pageCount", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { page++; first = null; last = null }, enabled = page + 1 < pageCount) { Icon(Icons.Default.ChevronRight, "Página siguiente") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PdfOperations.EditKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = mode == option, onClick = { mode = option; first = null; last = null }, shape = SegmentedButtonDefaults.itemShape(index, 4)) {
                            Text(when (option) {
                                PdfOperations.EditKind.TEXT -> "Texto"
                                PdfOperations.EditKind.HIGHLIGHT -> "Resaltar"
                                PdfOperations.EditKind.UNDERLINE -> "Subrayar"
                                PdfOperations.EditKind.STRIKEOUT -> "Tachar"
                            }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (mode == PdfOperations.EditKind.TEXT) {
                    OutlinedTextField(textToAdd, { textToAdd = it.take(1000) }, label = { Text("Texto a colocar") }, modifier = Modifier.fillMaxWidth(), maxLines = 2, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fontSize.toInt()} pt", modifier = Modifier.width(58.dp))
                        Slider(fontSize, { fontSize = it }, valueRange = 6f..72f, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (edits.isNotEmpty()) edits.removeAt(edits.lastIndex) }, enabled = edits.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Deshacer") }
                    Button(onClick = ::addToSession, enabled = selectionReady && (mode != PdfOperations.EditKind.TEXT || textToAdd.isNotBlank()), modifier = Modifier.weight(1f)) { Text("Aplicar cambio") }
                }
                Text(if (mode == PdfOperations.EditKind.TEXT) "El texto se previsualiza en vivo: ajustá tamaño y ubicación tocando la página. Después pulsá Aplicar cambio." else "Arrastrá para seleccionar la zona y pulsá Aplicar cambio.", style = MaterialTheme.typography.labelSmall)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE9E8F1), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image == null) {
                        if (renderError) Text("No se pudo visualizar esta página") else CircularProgressIndicator()
                    } else {
                        val ratio = image.width.toFloat() / image.height
                        val drawWidth = if (maxWidth / ratio <= maxHeight) maxWidth else maxHeight * ratio
                        val drawHeight = drawWidth / ratio
                        val gestureModifier = if (mode == PdfOperations.EditKind.TEXT) {
                            Modifier.pointerInput(page, mode) {
                                detectTapGestures { tap ->
                                    first = Offset((tap.x / size.width).coerceIn(0f, 1f), (tap.y / size.height).coerceIn(0f, 1f))
                                    last = null
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
                                edits.filter { it.pageIndex == page }.forEach { edit ->
                                    if (edit.kind == PdfOperations.EditKind.TEXT) {
                                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            color = android.graphics.Color.rgb(28, 28, 40)
                                            textSize = edit.fontSize * size.width / 595f
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(edit.text.replace('\n', ' '), edit.x * size.width, edit.y * size.height, paint)
                                    } else drawMarkup(edit.kind, edit.x, edit.y, edit.width, edit.height)
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
private fun HomeScreen(recent: List<String>, open: () -> Unit, action: (Tool) -> Unit, openRecent: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(245.dp).background(Brush.linearGradient(listOf(Color(0xFF5750C9), Color(0xFF9D8DEC), Color(0xFFFFD5C9))))) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PDF Studio", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(25.dp))
                    Text("Tus documentos, a tu manera.", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Todo lo que necesitás, sin conexión.", color = Color.White.copy(alpha = .85f))
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = open, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Purple)) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text("Abrir un PDF")
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp).offset(y = (-16).dp).background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp)) {
                Text("Acciones rápidas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Crear PDF", Icons.Default.AddPhotoAlternate, Pastels[0], Modifier.weight(1f)) { action(Tool.IMAGES) }
                    ToolCard("Unir PDF", Icons.Default.FileCopy, Pastels[1], Modifier.weight(1f)) { action(Tool.MERGE) }
                    ToolCard("Editar", Icons.Default.Edit, Pastels[2], Modifier.weight(1f)) { action(Tool.TEXT) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Extraer", Icons.Default.ContentCut, Pastels[3], Modifier.weight(1f), enabled = false) {}
                    ToolCard("TXT / Word", Icons.Default.Article, Pastels[4], Modifier.weight(1f)) { action(Tool.DOCUMENT) }
                    ToolCard("OCR", Icons.Default.DocumentScanner, Pastels[5], Modifier.weight(1f), enabled = false) {}
                }
                Spacer(Modifier.height(8.dp))
                Text("Para editar, extraer, rotar u organizar páginas, abrí un PDF. Las herramientas en gris se habilitan desde el visor o llegarán en próximas versiones.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
private fun ViewerScreen(uri: Uri, page: Int, count: Int, busy: Boolean, onBack: () -> Unit, onPage: (Int) -> Unit, onSearch: () -> Unit, onExtract: () -> Unit, onRotate: () -> Unit, onText: () -> Unit, onPages: () -> Unit, onImage: () -> Unit, onSignature: () -> Unit, onMarkup: () -> Unit, onOcr: () -> Unit, onProtect: () -> Unit, onWatermark: () -> Unit, onCompress: () -> Unit, onSearchable: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(uri, page) {
        image = null
        zoom = 1f
        try { image = ops.renderPage(uri, page) } catch (_: Exception) {}
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Documento PDF", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = onSearch, enabled = !busy) { Icon(Icons.Default.Search, "Buscar texto") }
            IconButton(onClick = onExtract, enabled = count > 0 && !busy) { Icon(Icons.Default.ContentCut, "Extraer páginas") }
            IconButton(onClick = onRotate, enabled = count > 0 && !busy) { Icon(Icons.Default.RotateRight, "Rotar páginas") }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onText, enabled = !busy) { Icon(Icons.Default.TextFields, null); Spacer(Modifier.width(4.dp)); Text("Texto") }
            TextButton(onClick = onImage, enabled = !busy) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text("Imagen") }
            TextButton(onClick = onPages, enabled = !busy) { Icon(Icons.Default.Reorder, null); Spacer(Modifier.width(4.dp)); Text("Páginas") }
        }
        Row(Modifier.fillMaxWidth().background(Color.White), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onSignature, enabled = !busy) { Icon(Icons.Default.Draw, null); Text("Firma") }
            TextButton(onClick = onMarkup, enabled = !busy) { Icon(Icons.Default.Highlight, null); Text("Marcar") }
            TextButton(onClick = onOcr, enabled = !busy) { Icon(Icons.Default.DocumentScanner, null); Text("OCR") }
            TextButton(onClick = onWatermark, enabled = !busy) { Icon(Icons.Default.WaterDrop, null); Text("Marca") }
            TextButton(onClick = onProtect, enabled = !busy) { Icon(Icons.Default.Lock, null); Text("Clave") }
        }
        Row(Modifier.fillMaxWidth().background(Color.White), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onSearchable, enabled = !busy) { Icon(Icons.Default.FindInPage, null); Spacer(Modifier.width(5.dp)); Text("Crear PDF buscable (OCR)") }
            TextButton(onClick = onCompress, enabled = !busy) { Icon(Icons.Default.Compress, null); Spacer(Modifier.width(5.dp)); Text("Comprimir") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null) CircularProgressIndicator()
            else Image(bitmap.asImageBitmap(), "Página ${page + 1}", Modifier.fillMaxSize()
                .pointerInput(uri, page) { detectTransformGestures { _, _, gestureZoom, _ -> zoom = (zoom * gestureZoom).coerceIn(1f, 5f) } }
                .graphicsLayer(scaleX = zoom, scaleY = zoom), contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Anterior") }
            Text("${page + 1} / $count", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onPage(page + 1) }, enabled = page + 1 < count) { Icon(Icons.Default.ChevronRight, "Siguiente") }
        }
    }
}
