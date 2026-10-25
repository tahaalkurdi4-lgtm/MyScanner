package com.example.myscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import android.graphics.Bitmap
import java.io.File

enum class Mode { NEW, APPEND, OCR_ONLY }
class Pending(val mode: Mode, val title: String? = null, val target: Doc? = null)

class MainActivity : ComponentActivity() {
    val docs = mutableStateListOf<Doc>()
    var tab by mutableIntStateOf(0)
    var openDoc by mutableStateOf<Doc?>(null)
    var busy by mutableStateOf(false)
    var output by mutableStateOf<String?>(null)
    private var pending: Pending? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pending
        pending = null
        val res = if (r.resultCode == RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        val uris = res?.pages?.map { it.imageUri }
        if (p != null && !uris.isNullOrEmpty()) process(p, uris)
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        val p = pending
        pending = null
        if (p != null && uris.isNotEmpty()) process(p, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent { App() }
    }

    fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    fun refresh() {
        val list = Store.list(this)
        docs.clear()
        docs.addAll(list)
        openDoc = openDoc?.let { o -> list.find { it.id == o.id } }
    }

    /** تشغيل شغلة ثقيلة بالخلفية مع مؤشر انتظار */
    fun bg(work: () -> Unit) {
        busy = true
        Thread {
            try { work() } catch (e: Throwable) { toast("خطأ: ${e.message}") }
            runOnUiThread { busy = false; refresh() }
        }.start()
    }

    fun scan(p: Pending, limit: Int = 50) {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(limit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        pending = p
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { pending = null; toast("تعذر فتح الماسح: ${it.message}") }
    }

    fun importImages(p: Pending) {
        pending = p
        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun scanQr() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { output = it.rawValue ?: "" }
            .addOnFailureListener { toast("تعذر مسح الرمز: ${it.message}") }
    }

    private fun process(p: Pending, uris: List<Uri>) = bg {
        when (p.mode) {
            Mode.NEW -> Store.create(this, uris, p.title)
            Mode.APPEND -> p.target?.let { Store.append(this, it, uris) }
            Mode.OCR_ONLY -> {
                val tmp = Store.tempCopies(this, uris)
                val t = try { runOcr(this, tmp) } finally { tmp.forEach { it.delete() } }
                runOnUiThread { output = t.ifBlank { "لم يتم العثور على نص" } }
            }
        }
    }

    fun renameDoc(d: Doc, t: String) { if (t.isNotBlank()) { d.rename(t); refresh() } }

    fun deleteDoc(d: Doc) {
        d.dir.deleteRecursively()
        if (openDoc?.id == d.id) openDoc = null
        refresh()
    }

    fun ocrDoc(d: Doc) = bg {
        val t = runOcr(this, d.pages)
        File(d.dir, "ocr.txt").writeText(t)
        runOnUiThread { output = t.ifBlank { "لم يتم العثور على نص" } }
    }

    fun rotatePage(d: Doc, f: File, deg: Float) = bg { rotateImage(f, deg); Store.buildPdf(d.dir) }

    fun deletePage(d: Doc, f: File) = bg {
        if (d.pages.size <= 1) d.dir.deleteRecursively() else { f.delete(); Store.buildPdf(d.dir) }
    }

    fun watermarkDoc(d: Doc, text: String) = bg {
        if (text.isNotBlank()) { d.pages.forEach { watermarkImage(it, text) }; Store.buildPdf(d.dir) }
    }

    fun signPage(d: Doc, f: File, sig: Bitmap) = bg { stampSignature(f, sig); Store.buildPdf(d.dir) }

    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    fun sharePdf(d: Doc) {
        if (!d.pdf.exists()) { toast("ملف PDF غير موجود"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriOf(d.pdf))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة"))
    }

    fun shareImages(d: Doc) {
        val uris = ArrayList(d.pages.map { uriOf(it) })
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة"))
    }

    fun shareText(t: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t)
        }, "مشاركة"))
    }

    fun openPdf(d: Doc) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriOf(d.pdf), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { toast("ما في تطبيق لفتح PDF على جهازك") }
    }

    fun copyText(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", t))
        toast("تم النسخ")
    }
}
