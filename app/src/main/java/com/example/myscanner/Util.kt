package com.example.myscanner

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** مستند = مجلد باسم الوقت: page_001.jpg ... + doc.pdf + title.txt + ocr.txt */
class Doc(val dir: File) {
    val id: Long = dir.name.toLongOrNull() ?: 0L
    val pages: List<File> = (dir.listFiles { f -> f.name.startsWith("page_") } ?: emptyArray<File>()).sortedBy { it.name }
    val title: String = read("title.txt")
        ?: ("مستند " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date(id)))
    val ocrText: String = read("ocr.txt") ?: ""
    val pdf: File get() = File(dir, "doc.pdf")

    private fun read(name: String): String? =
        File(dir, name).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun nextIndex(): Int = (pages.lastOrNull()?.name?.substring(5, 8)?.toIntOrNull() ?: 0) + 1
    fun rename(t: String) { File(dir, "title.txt").writeText(t) }
}

object Store {
    fun root(c: Context): File = File(c.filesDir, "docs").apply { mkdirs() }

    fun list(c: Context): List<Doc> =
        (root(c).listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null } ?: emptyArray<File>())
            .map { Doc(it) }.sortedByDescending { it.id }

    fun create(c: Context, uris: List<Uri>, title: String?): Doc {
        val d = Doc(File(root(c), System.currentTimeMillis().toString()).apply { mkdirs() })
        if (title != null) d.rename(title)
        append(c, d, uris)
        return d
    }

    fun append(c: Context, d: Doc, uris: List<Uri>) {
        var n = d.nextIndex()
        for (u in uris) {
            c.contentResolver.openInputStream(u)?.use { i ->
                File(d.dir, "page_%03d.jpg".format(n++)).outputStream().use { o -> i.copyTo(o) }
            }
        }
        buildPdf(d.dir)
    }

    fun tempCopies(c: Context, uris: List<Uri>): List<File> = uris.mapIndexedNotNull { i, u ->
        val f = File(c.cacheDir, "tmp_${System.nanoTime()}_$i.jpg")
        c.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { o -> s.copyTo(o) } }?.let { f }
    }

    fun buildPdf(dir: File) {
        val pdf = PdfDocument()
        Doc(dir).pages.forEachIndexed { i, f ->
            val bmp = BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
            val w = 595
            val h = (w.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            bmp.recycle()
        }
        File(dir, "doc.pdf").outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }
}

fun decodeSampled(f: File, req: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= req && o.outHeight / (s * 2) >= req) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

private fun saveJpg(b: Bitmap, f: File) { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }

fun rotateImage(f: File, deg: Float) {
    val b = BitmapFactory.decodeFile(f.path) ?: return
    saveJpg(Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true), f)
}

fun watermarkImage(f: File, text: String) {
    val b = BitmapFactory.decodeFile(f.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 120, 120, 120); textSize = b.width / 9f; textAlign = Paint.Align.CENTER
    }
    c.rotate(-30f, b.width / 2f, b.height / 2f)
    c.drawText(text, b.width / 2f, b.height / 2f, p)
    saveJpg(b, f)
}

fun renderSignature(strokes: List<List<Offset>>, w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    for (s in strokes) for (i in 1 until s.size) c.drawLine(s[i - 1].x, s[i - 1].y, s[i].x, s[i].y, p)
    return b
}

fun stampSignature(f: File, sig: Bitmap) {
    val b = BitmapFactory.decodeFile(f.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return
    val w = b.width * 0.35f
    val h = w * sig.height / sig.width
    val right = b.width * 0.95f
    val bottom = b.height * 0.95f
    Canvas(b).drawBitmap(sig, null, RectF(right - w, bottom - h, right, bottom), Paint(Paint.FILTER_BITMAP_FLAG))
    saveJpg(b, f)
}

/** OCR عربي + إنجليزي بـ Tesseract. لازم تشتغل بخيط خلفي. */
fun runOcr(ctx: Context, files: List<File>): String {
    val base = File(ctx.filesDir, "tess")
    val td = File(base, "tessdata").apply { mkdirs() }
    for (l in listOf("ara", "eng")) {
        val f = File(td, "$l.traineddata")
        if (!f.exists()) ctx.assets.open("tessdata/$l.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    }
    val api = TessBaseAPI()
    try {
        if (!api.init(base.absolutePath, "ara+eng")) return ""
        val sb = StringBuilder()
        for (f in files) {
            val bmp = decodeSampled(f, 2000) ?: continue
            api.setImage(bmp)
            sb.append(api.utf8Text).append("\n\n")
            bmp.recycle()
        }
        return sb.toString().trim()
    } finally {
        api.recycle()
    }
}
