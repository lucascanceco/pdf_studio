package com.lucascanceco.pdfstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

private enum class Tool { IMAGES, MERGE, EXTRACT, ROTATE }

@Composable
private fun PdfStudioApp(initialUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
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
                    ViewerScreen(current!!, page, count, busy, onBack = { current = null }, onPage = { page = it }, onExtract = { selectedTool = Tool.EXTRACT }, onRotate = { perform { ops.rotate(current!!) } })
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
                    ToolCard("Editar", Icons.Default.Edit, Pastels[2], Modifier.weight(1f), enabled = false) {}
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Extraer", Icons.Default.ContentCut, Pastels[3], Modifier.weight(1f), enabled = false) {}
                    ToolCard("Rotar", Icons.Default.RotateRight, Pastels[4], Modifier.weight(1f), enabled = false) {}
                    ToolCard("OCR", Icons.Default.DocumentScanner, Pastels[5], Modifier.weight(1f), enabled = false) {}
                }
                Spacer(Modifier.height(8.dp))
                Text("Para extraer o rotar páginas, primero abrí un PDF. Las herramientas en gris llegarán en próximas versiones.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
private fun ViewerScreen(uri: Uri, page: Int, count: Int, busy: Boolean, onBack: () -> Unit, onPage: (Int) -> Unit, onExtract: () -> Unit, onRotate: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ops = remember { PdfOperations(context) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri, page) {
        image = null
        try { image = ops.renderPage(uri, page) } catch (_: Exception) {}
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Documento PDF", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = onExtract, enabled = count > 0 && !busy) { Icon(Icons.Default.ContentCut, "Extraer páginas") }
            IconButton(onClick = onRotate, enabled = count > 0 && !busy) { Icon(Icons.Default.RotateRight, "Rotar páginas") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null) CircularProgressIndicator()
            else Image(bitmap.asImageBitmap(), "Página ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Anterior") }
            Text("${page + 1} / $count", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onPage(page + 1) }, enabled = page + 1 < count) { Icon(Icons.Default.ChevronRight, "Siguiente") }
        }
    }
}
