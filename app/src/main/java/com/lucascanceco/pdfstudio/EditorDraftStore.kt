package com.lucascanceco.pdfstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Single local draft for personal/offline use. Images are copied into app-private storage. */
class EditorDraftStore(private val context: Context) {
    private val folder = File(context.filesDir, "editor_draft")
    private val manifest = File(folder, "draft.json")

    data class Pending(
        val mode: PdfOperations.EditKind,
        val text: String = "",
        val x: Float? = null,
        val y: Float? = null,
        val lastX: Float? = null,
        val lastY: Float? = null,
        val fontSize: Float = 16f,
        val imageWidth: Float = .45f,
        val image: Bitmap? = null
    )

    data class Draft(
        val document: Uri,
        val documentName: String,
        val page: Int,
        val modifiedAt: Long,
        val edits: List<PdfOperations.EditOperation>,
        val pending: Pending? = null
    )

    fun metadata(): Draft? = load(withImages = false)

    fun load(withImages: Boolean = true): Draft? {
      return try {
        if (!manifest.isFile) return null
        val root = JSONObject(manifest.readText())
        val rows = root.getJSONArray("edits")
        val edits = buildList {
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                val image = if (withImages && item.has("image")) {
                    BitmapFactory.decodeFile(File(folder, item.getString("image")).absolutePath)
                } else null
                add(PdfOperations.EditOperation(
                    pageIndex = item.getInt("page"),
                    kind = PdfOperations.EditKind.valueOf(item.getString("kind")),
                    x = item.getDouble("x").toFloat(),
                    y = item.getDouble("y").toFloat(),
                    width = item.optDouble("width", 0.0).toFloat(),
                    height = item.optDouble("height", 0.0).toFloat(),
                    text = item.optString("text", ""),
                    fontSize = item.optDouble("fontSize", 16.0).toFloat(),
                    image = image
                ))
            }
        }
        val pending = root.optJSONObject("pending")?.let { item ->
            val image = if (withImages && item.has("image"))
                BitmapFactory.decodeFile(File(folder, item.getString("image")).absolutePath) else null
            Pending(
                mode = PdfOperations.EditKind.valueOf(item.getString("mode")),
                text = item.optString("text", ""),
                x = item.optDouble("x", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                y = item.optDouble("y", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                lastX = item.optDouble("lastX", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                lastY = item.optDouble("lastY", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                fontSize = item.optDouble("fontSize", 16.0).toFloat(),
                imageWidth = item.optDouble("imageWidth", .45).toFloat(),
                image = image
            )
        }
        Draft(Uri.parse(root.getString("uri")), root.optString("name", "Documento PDF"),
            root.optInt("page", 0), root.getLong("timestamp"), edits, pending)
      } catch (_: Exception) { null }
    }

    fun save(document: Uri, name: String, page: Int, edits: List<PdfOperations.EditOperation>, pending: Pending?) {
        folder.mkdirs()
        folder.listFiles()?.filter { it.name.startsWith("image_") }?.forEach { it.delete() }
        val rows = JSONArray()
        edits.forEachIndexed { index, edit ->
            val item = JSONObject().apply {
                put("page", edit.pageIndex)
                put("kind", edit.kind.name)
                put("x", edit.x.toDouble())
                put("y", edit.y.toDouble())
                put("width", edit.width.toDouble())
                put("height", edit.height.toDouble())
                put("text", edit.text)
                put("fontSize", edit.fontSize.toDouble())
            }
            edit.image?.let { bitmap ->
                val fileName = "image_${index}.png"
                File(folder, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                item.put("image", fileName)
            }
            rows.put(item)
        }
        val root = JSONObject().apply {
            put("uri", document.toString())
            put("name", name)
            put("page", page)
            put("timestamp", System.currentTimeMillis())
            put("edits", rows)
            pending?.let { active ->
                val item = JSONObject().apply {
                    put("mode", active.mode.name); put("text", active.text)
                    active.x?.let { put("x", it.toDouble()) }
                    active.y?.let { put("y", it.toDouble()) }
                    active.lastX?.let { put("lastX", it.toDouble()) }
                    active.lastY?.let { put("lastY", it.toDouble()) }
                    put("fontSize", active.fontSize.toDouble()); put("imageWidth", active.imageWidth.toDouble())
                }
                active.image?.let { bitmap ->
                    File(folder, "pending.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    item.put("image", "pending.png")
                }
                put("pending", item)
            }
        }
        val staging = File(folder, "draft.json.tmp")
        staging.writeText(root.toString())
        if (!staging.renameTo(manifest)) {
            staging.copyTo(manifest, overwrite = true)
            staging.delete()
        }
    }

    fun clear() {
        folder.listFiles()?.forEach { it.delete() }
        folder.delete()
    }
}
