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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

private enum class Tool { IMAGES, MERGE, EXTRACT, ROTATE, TEXT, PAGES, OCR, SIGNATURE, PROTECT, WATERMARK, COMPRESS }

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
    val stampPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && current != null) perform {
            val bitmap = context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: error("Imagen no válida")
            try { ops.stampImage(current!!, page, bitmap, .12f, .20f, .45f) }
            finally { bitmap.recycle() }
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
                            Tool.MERGE -> manyPdf.launch(arrayOf("application/pdf"))
                            else -> if (current == null) openPdf.launch(arrayOf("application/pdf"))
                        }
                    }, { uri -> current = Uri.parse(uri) })
                } else {
                    ViewerScreen(current!!, page, count, busy, onBack = { current = null }, onPage = { page = it }, onSearch = { searchOpen = true }, onExtract = { selectedTool = Tool.EXTRACT }, onRotate = { perform { ops.rotate(current!!) } }, onText = { selectedTool = Tool.TEXT }, onPages = {
                        pageSequence = (1..count).joinToString(",")
                        selectedTool = Tool.PAGES
                    }, onImage = { stampPicker.launch("image/*") }, onSignature = { selectedTool = Tool.SIGNATURE }, onProtect = { selectedTool = Tool.PROTECT }, onWatermark = { selectedTool = Tool.WATERMARK }, onCompress = { selectedTool = Tool.COMPRESS }, onSearchable = {
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
        if (selectedTool == Tool.TEXT && current != null) {
            AlertDialog(onDismissRequest = { selectedTool = null }, title = { Text("Agregar texto — página ${page + 1}") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = insertText, onValueChange = { insertText = it.take(1000) }, label = { Text("Texto a insertar") })
                    Text("Tamaño: ${textSize.toInt()} pt")
                    Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 6f..72f)
                    Text("Posición horizontal: ${(textX * 100).toInt()} %")
                    Slider(value = textX, onValueChange = { textX = it }, valueRange = 0f..0.9f)
                    Text("Posición vertical: ${(textY * 100).toInt()} %")
                    Slider(value = textY, onValueChange = { textY = it }, valueRange = .05f..1f)
                    Text("Se guarda una copia; el documento original no cambia.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = {
                if (insertText.isNotBlank()) perform { ops.addText(current!!, page, insertText, textX, textY, textSize) }
                selectedTool = null
            }) { Text("Guardar copia") } }, dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("Cancelar") } })
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
                    ToolCard("Rotar", Icons.Default.RotateRight, Pastels[4], Modifier.weight(1f), enabled = false) {}
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
private fun ViewerScreen(uri: Uri, page: Int, count: Int, busy: Boolean, onBack: () -> Unit, onPage: (Int) -> Unit, onSearch: () -> Unit, onExtract: () -> Unit, onRotate: () -> Unit, onText: () -> Unit, onPages: () -> Unit, onImage: () -> Unit, onSignature: () -> Unit, onOcr: () -> Unit, onProtect: () -> Unit, onWatermark: () -> Unit, onCompress: () -> Unit, onSearchable: () -> Unit) {
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
