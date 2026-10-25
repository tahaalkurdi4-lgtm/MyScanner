package com.example.myscanner

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Teal = Color(0xFF19BCAA)

class Tool(val name: String, val icon: ImageVector, val run: () -> Unit)

@Composable
fun MainActivity.App() {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Teal) else lightColorScheme(primary = Teal)
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                val d = openDoc
                if (d != null) {
                    BackHandler { openDoc = null }
                    DocScreen(d)
                } else {
                    Main()
                }
                if (busy) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = {}),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                output?.let { ResultDialog(it) }
            }
        }
    }
}

@Composable
fun MainActivity.Main() {
    val tabs = listOf("المستندات" to Icons.Filled.Home, "الأدوات" to Icons.Filled.Build, "أنا" to Icons.Filled.Person)
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) }, label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(
                    onClick = { scan(Pending(Mode.NEW)) },
                    shape = CircleShape, containerColor = Teal, contentColor = Color.White,
                    modifier = Modifier.size(64.dp)
                ) { Icon(Icons.Filled.CameraAlt, "مسح", Modifier.size(30.dp)) }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Docs()
                1 -> Tools()
                else -> Me()
            }
        }
    }
}

@Composable
fun MainActivity.Docs() {
    var q by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var byName by remember { mutableStateOf(false) }
    val filtered = docs.filter { q.isBlank() || it.title.contains(q, true) || it.ocrText.contains(q, true) }
    val shown = if (byName) filtered.sortedBy { it.title.lowercase() } else filtered
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ماسحتي", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal, modifier = Modifier.weight(1f))
            IconButton(onClick = { byName = !byName }) { Icon(Icons.Filled.Sort, "ترتيب", tint = if (byName) Teal else LocalContentColor.current) }
            IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, "عرض") }
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("ابحث في المستندات والنصوص") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("لا توجد مستندات\nاضغط على زر الكاميرا للمسح", textAlign = TextAlign.Center, color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (grid) 2 else 1),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(shown, key = { it.id }) { d -> DocCard(d, grid) }
            }
        }
    }
}

@Composable
fun MainActivity.DocCard(d: Doc, grid: Boolean) {
    var rename by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val info = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(d.id)) + " • " + d.pages.size + " صفحة"
    Card(Modifier.fillMaxWidth().clickable { openDoc = d }) {
        if (grid) {
            Column {
                Thumb(d.pages.firstOrNull(), Modifier.fillMaxWidth().height(160.dp))
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(info, fontSize = 11.sp, color = Color.Gray)
                    }
                    DocMenu(d, { rename = true }, { del = true })
                }
            }
        } else {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(d.pages.firstOrNull(), Modifier.size(60.dp, 76.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(info, fontSize = 12.sp, color = Color.Gray)
                }
                DocMenu(d, { rename = true }, { del = true })
            }
        }
    }
    if (rename) TextDialog("إعادة تسمية", d.title, { rename = false }) { renameDoc(d, it); rename = false }
    if (del) ConfirmDialog("حذف \"${d.title}\"؟", { del = false }) { deleteDoc(d); del = false }
}

@Composable
fun MainActivity.Tools() {
    val tools = listOf(
        Tool("مسح مستند", Icons.Filled.CameraAlt) { scan(Pending(Mode.NEW)) },
        Tool("استخراج نص OCR", Icons.Filled.TextFields) { scan(Pending(Mode.OCR_ONLY), 1) },
        Tool("نص من صورة", Icons.Filled.Image) { importImages(Pending(Mode.OCR_ONLY)) },
        Tool("بطاقة هوية", Icons.Filled.AccountBox) { scan(Pending(Mode.NEW, "بطاقة هوية"), 2) },
        Tool("جواز سفر", Icons.Filled.Face) { scan(Pending(Mode.NEW, "جواز سفر"), 1) },
        Tool("صور إلى PDF", Icons.Filled.PhotoLibrary) { importImages(Pending(Mode.NEW)) },
        Tool("ماسح QR", Icons.Filled.QrCodeScanner) { scanQr() }
    )
    Column(Modifier.fillMaxSize()) {
        Text("الأدوات", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools) { t ->
                Card(Modifier.clickable { t.run() }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(t.icon, null, Modifier.size(32.dp), tint = Teal)
                        Spacer(Modifier.height(8.dp))
                        Text(t.name, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun MainActivity.Me() {
    var confirm by remember { mutableStateOf(false) }
    val mb = remember(docs.size) {
        Store.root(this).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576.0
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("حسابي", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("عدد المستندات: ${docs.size}")
                Text("عدد الصفحات: ${docs.sumOf { it.pages.size }}")
                Text("المساحة المستخدمة: " + "%.1f".format(mb) + " MB")
            }
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("حذف كل المستندات") }
        Text("الإصدار 1.0 • يعمل بدون إنترنت وكل بياناتك على جهازك", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Code, null, Modifier.size(18.dp), tint = Teal)
            Spacer(Modifier.width(6.dp))
            Text("Developed by Taha Al-Kurdi", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Teal)
        }
    }
    if (confirm) ConfirmDialog("حذف كل المستندات؟", { confirm = false }) {
        Store.root(this).deleteRecursively(); openDoc = null; refresh(); confirm = false
    }
}

@Composable
fun MainActivity.DocScreen(d: Doc) {
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var wm by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<File?>(null) }
    var sign by remember { mutableStateOf<File?>(null) }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openDoc = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text("${d.pages.size} صفحة", fontSize = 12.sp, color = Color.Gray)
                }
                IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "تسمية") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "المزيد") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("فتح PDF") }, onClick = { menu = false; openPdf(d) })
                        if (d.ocrText.isNotBlank()) {
                            DropdownMenuItem(text = { Text("عرض النص المستخرج") }, onClick = { menu = false; output = d.ocrText })
                        }
                        DropdownMenuItem(text = { Text("علامة مائية") }, onClick = { menu = false; wm = true })
                        DropdownMenuItem(text = { Text("حذف المستند") }, onClick = { menu = false; del = true })
                    }
                }
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionBtn(Icons.Filled.Add, "إضافة") { scan(Pending(Mode.APPEND, target = d)) }
                ActionBtn(Icons.Filled.TextFields, "نص OCR") { ocrDoc(d) }
                ActionBtn(Icons.Filled.PictureAsPdf, "PDF") { sharePdf(d) }
                ActionBtn(Icons.Filled.Share, "صور") { shareImages(d) }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(d.pages) { i, f ->
                Box(Modifier.clickable { sel = f }) {
                    Thumb(f, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)), 700)
                    Text(
                        "${i + 1}", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
    sel?.let { f ->
        AlertDialog(
            onDismissRequest = { sel = null },
            title = { Text("الصفحة") },
            text = {
                Column {
                    Thumb(f, Modifier.fillMaxWidth().height(220.dp), 600)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { rotatePage(d, f, -90f); sel = null }) { Icon(Icons.Filled.RotateLeft, "تدوير يسار") }
                        IconButton(onClick = { rotatePage(d, f, 90f); sel = null }) { Icon(Icons.Filled.RotateRight, "تدوير يمين") }
                        IconButton(onClick = { sign = f; sel = null }) { Icon(Icons.Filled.Create, "توقيع") }
                        IconButton(onClick = { deletePage(d, f); sel = null }) { Icon(Icons.Filled.Delete, "حذف") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sel = null }) { Text("إغلاق") } }
        )
    }
    sign?.let { f -> SignatureDialog { bmp -> if (bmp != null) signPage(d, f, bmp); sign = null } }
    if (rename) TextDialog("إعادة تسمية", d.title, { rename = false }) { renameDoc(d, it); rename = false }
    if (wm) TextDialog("نص العلامة المائية", "سري", { wm = false }) { watermarkDoc(d, it); wm = false }
    if (del) ConfirmDialog("حذف \"${d.title}\"؟", { del = false }) { deleteDoc(d); del = false }
}

@Composable
fun ActionBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = Teal)
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun Thumb(file: File?, modifier: Modifier, px: Int = 400) {
    val bmp by produceState<Bitmap?>(null, file?.path, file?.lastModified()) {
        value = withContext(Dispatchers.IO) { file?.let { decodeSampled(it, px) } }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0xFFE6E6E6)))
}

@Composable
fun TextDialog(title: String, initial: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text(title) },
        text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(t.trim()) }) { Text("حسناً") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } }
    )
}

@Composable
fun ConfirmDialog(title: String, onCancel: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel, title = { Text(title) },
        confirmButton = { TextButton(onClick = onOk) { Text("نعم") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } }
    )
}

@Composable
fun MainActivity.ResultDialog(t: String) {
    AlertDialog(
        onDismissRequest = { output = null },
        title = { Text("النتيجة") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(t) } },
        confirmButton = { TextButton(onClick = { copyText(t); output = null }) { Text("نسخ") } },
        dismissButton = {
            Row {
                TextButton(onClick = { shareText(t) }) { Text("مشاركة") }
                TextButton(onClick = { output = null }) { Text("إغلاق") }
            }
        }
    )
}

@Composable
fun SignatureDialog(onDone: (Bitmap?) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var cur by remember { mutableStateOf(listOf<Offset>()) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("التوقيع") },
        text = {
            Canvas(
                Modifier.fillMaxWidth().height(180.dp).background(Color.White).onSizeChanged { size = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { cur = listOf(it) },
                            onDrag = { change, _ -> cur = cur + change.position },
                            onDragEnd = { strokes.add(cur); cur = emptyList() }
                        )
                    }
            ) {
                val all = ArrayList<List<Offset>>(strokes)
                all.add(cur)
                for (s in all) {
                    for (i in 1 until s.size) drawLine(Color.Black, s[i - 1], s[i], strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(if (strokes.isEmpty()) null else renderSignature(strokes.toList(), size.width, size.height))
            }) { Text("تطبيق") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text("مسح") }
                TextButton(onClick = { onDone(null) }) { Text("إلغاء") }
            }
        }
    )
}

@Composable
fun MainActivity.DocMenu(d: Doc, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "المزيد") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("فتح PDF") }, onClick = { menu = false; openPdf(d) })
            DropdownMenuItem(text = { Text("مشاركة PDF") }, onClick = { menu = false; sharePdf(d) })
            DropdownMenuItem(text = { Text("استخراج النص") }, onClick = { menu = false; ocrDoc(d) })
            DropdownMenuItem(text = { Text("إعادة تسمية") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
        }
    }
}
