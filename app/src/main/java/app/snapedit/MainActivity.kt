package app.snapedit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

private val BG = Color(0xFF121212)
private val BAR = Color(0xFF212121)
private val ACC = Color(0xFF4DA3FF)
private val TXT = Color(0xFFEEEEEE)
private val DIM = Color(0xFF8A8A8A)

class MainActivity : ComponentActivity() {
    var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = uriFrom(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = uriFrom(intent)
    }

    @Suppress("DEPRECATION")
    private fun uriFrom(i: Intent?): Uri? {
        val a = i ?: return null
        return when (a.action) {
            Intent.ACTION_SEND -> a.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> a.data
            else -> null
        }
    }
}

// ---------- utils ----------
fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val s = ImageDecoder.createSource(ctx.contentResolver, uri)
    ImageDecoder.decodeBitmap(s) { dec, info, _ ->
        val big = max(info.size.width, info.size.height)
        if (big > maxSide) {
            val f = maxSide.toFloat() / big
            dec.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
        }
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

fun scaleTo(b: Bitmap, side: Int): Bitmap {
    val big = max(b.width, b.height)
    if (big <= side) return b
    val f = side.toFloat() / big
    return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
}

@Suppress("DEPRECATION")
fun saveImage(ctx: Context, bmp: Bitmap, fmt: Int, quality: Int): Uri? {
    val (ext, mime) = when (fmt) { 1 -> "png" to "image/png"; 2 -> "webp" to "image/webp"; else -> "jpg" to "image/jpeg" }
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Snapedit_${System.currentTimeMillis()}.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Snapedit")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
    val cf = when (fmt) {
        1 -> Bitmap.CompressFormat.PNG
        2 -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(cf, quality, it) }
    return uri
}

private val ASPECTS = listOf("Free", "1:1", "4:3", "16:9", "3:2", "4:5")
private val BWF = listOf("Neutral", "Contrast", "Bright", "Dark", "Red", "Yellow", "Green", "Blue")

fun fmt(t: Tool, p: Param, v: Float): String {
    val i = v.roundToInt()
    return when {
        t.id == "crop" -> ASPECTS[i.coerceIn(0, 5)]
        t.id == "bw" && p.name == "Filter" -> BWF[i.coerceIn(0, 7)]
        t.id == "rotate" && p.name == "Rotate" -> "${i * 90}°"
        t.id == "rotate" && p.name == "Straighten" -> "${if (i > 0) "+" else ""}$i°"
        t.id == "rotate" -> if (i == 0) "Off" else "On"
        t.id == "hsl" && p.name == "Band" -> listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")[i.coerceIn(0, 7)]
        t.id == "curves" -> listOf("RGB", "Red", "Green", "Blue")[i.coerceIn(0, 3)]
        t.id == "liquify" && p.name == "Mode" -> listOf("Push", "Bloat", "Pinch")[i.coerceIn(0, 2)]
        t.id == "brush" && p.name == "Effect" -> listOf("Dodge", "Exposure", "Temp", "Saturation")[i.coerceIn(0, 3)]
        t.id == "brush" && p.name == "Erase" -> if (i == 0) "Off" else "On"
        t.id == "range" && p.name == "Mode" -> if (i == 0) "Luminance" else "Color"
        (t.id == "range" && p.name == "Show") || (t.id == "radial" && p.name == "Invert") -> if (i == 0) "Off" else "On"
        t.id == "text" && p.name == "Color" -> listOf("White", "Black", "Yellow", "Red", "Blue", "Green")[i.coerceIn(0, 5)]
        t.id == "frames" && p.name == "Color" -> if (i == 0) "White" else "Black"
        p.min < 0f && i > 0 -> "+$i"
        else -> "$i"
    }
}

@Composable
fun Txt(s: String, m: Modifier = Modifier, c: Color = TXT, size: Int = 14, bold: Boolean = false) =
    Text(s, m, color = c, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else null)

// ---------- main screen ----------
fun fit(bw: Float, bh: Float, iw: Int, ih: Int): FloatArray {
    val s = min(bw / iw, bh / ih)
    val dw = iw * s
    val dh = ih * s
    return floatArrayOf((bw - dw) / 2f, (bh - dh) / 2f, dw, dh)
}

fun changed(op: Op): Boolean = when (op.tool) {
    "brush" -> op.strokes.isNotEmpty()
    "select" -> op.pts.isNotEmpty()
    "heal", "redeye" -> op.spots.isNotEmpty()
    "text" -> op.text.isNotBlank()
    "crop" -> listOf("L", "T", "R", "B").any { op.v[it] != Tools.op("crop").v[it] }
    "curves" -> op.curves.any { it != listOf(0f, 0f, 1f, 1f) }
    "liquify" -> op.warps.isNotEmpty()
    "hsl" -> (0..7).any { (op.v["h$it"] ?: 0f) != 0f || (op.v["s$it"] ?: 0f) != 0f || (op.v["l$it"] ?: 0f) != 0f }
    else -> op.v != Tools.op(op.tool).v
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(act: MainActivity) {
    val ctx = LocalContext.current
    val hitPx = with(LocalDensity.current) { 44.dp.toPx() }
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var prev by remember { mutableStateOf<Bitmap?>(null) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    val hist = remember { mutableStateListOf<List<Op>>(emptyList()) }
    var hi by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Tool?>(null) }
    var pIdx by remember { mutableIntStateOf(0) }
    val live = remember { mutableStateMapOf<String, Float>() }
    var comparing by remember { mutableStateOf(false) }
    var showStack by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<BrushStroke>() }
    val pts = remember { mutableStateListOf<Pt>() }
    var sel by remember { mutableIntStateOf(-1) }
    val spots = remember { mutableStateListOf<Spot>() }
    var textValue by remember { mutableStateOf("") }
    var textDialog by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val warps = remember { mutableStateListOf<Warp>() }
    val warpMarks = remember { mutableStateListOf<Int>() }
    val curvePts = remember { mutableStateListOf<List<Float>>().apply { repeat(4) { add(listOf(0f, 0f, 1f, 1f)) } } }
    var hslBand by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf("Light") }
    var histoMode by remember { mutableIntStateOf(0) }
    var clipOps by remember { mutableStateOf<List<Op>>(emptyList()) }
    var userPresets by remember { mutableStateOf(Presets.load(ctx)) }
    var saveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf("") }
    var infoDialog by remember { mutableStateOf(false) }
    var expWm by remember { mutableStateOf("") }
    var expFmt by remember { mutableIntStateOf(0) }
    var expQ by remember { mutableFloatStateOf(92f) }
    var expScale by remember { mutableIntStateOf(100) }
    val ops = hist[hi.coerceIn(0, hist.lastIndex)]
    val tool = active

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun commit(n: List<Op>) {
        while (hist.size > hi + 1) hist.removeAt(hist.lastIndex)
        hist.add(n)
        hi = hist.size - 1
    }

    fun load(uri: Uri) {
        scope.launch {
            val full = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, 4096) }
            if (full == null) { toast("Couldn't open image"); return@launch }
            val p = withContext(Dispatchers.Default) { scaleTo(full, 1280) }
            infoText = (withContext(Dispatchers.IO) { exifInfo(ctx, uri) } + "\nSize  ${full.width} × ${full.height}").trim()
            src = full; prev = p
            zoom = 1f; pan = Offset.Zero
            hist.clear(); hist.add(emptyList()); hi = 0
            active = null; showStack = false
        }
    }

    fun openTool(t: Tool) {
        if (prev == null) { toast("Open a photo first"); return }
        live.clear()
        t.params.forEach { live[it.name] = it.def }
        strokes.clear(); pts.clear(); spots.clear(); sel = -1; textValue = ""
        warps.clear(); warpMarks.clear(); hslBand = 0
        for (k in 0..3) curvePts[k] = listOf(0f, 0f, 1f, 1f)
        pIdx = 0
        zoom = 1f; pan = Offset.Zero
        active = t
        if (t.id == "text") textDialog = true
    }

    fun export(share: Boolean) {
        val s = src ?: return
        val list = ops
        scope.launch {
            val uri = withContext(Dispatchers.Default) {
                var r = Engine.render(s, list)
                if (expScale < 100) r = Bitmap.createScaledBitmap(r, max(1, r.width * expScale / 100), max(1, r.height * expScale / 100), true)
                if (expWm.isNotBlank()) r = watermark(r, expWm)
                saveImage(ctx, r, expFmt, expQ.roundToInt())
            }
            if (uri == null) { toast("Save failed"); return@launch }
            if (share) {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = listOf("image/jpeg", "image/png", "image/webp")[expFmt]
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.startActivity(Intent.createChooser(i, null))
            } else toast("Saved to Pictures/Snapedit")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) load(u) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // ----- touch helpers -----
    // screen point -> unzoomed content point
    fun unz(o: Offset): Offset {
        val c = Offset(boxSize.width / 2f, boxSize.height / 2f)
        return (o - c - pan) / zoom + c
    }

    fun norm(o0: Offset): Offset? {
        val bm = base ?: return null
        val o = unz(o0)
        val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
        return Offset(((o.x - f[0]) / f[2]).coerceIn(0f, 1f), ((o.y - f[1]) / f[3]).coerceIn(0f, 1f))
    }

    fun hitPt(o0: Offset): Int {
        val bm = base ?: return -1
        val o = unz(o0)
        val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
        var best = -1
        var bd = hitPx / zoom
        pts.forEachIndexed { i, p ->
            val d = hypot(o.x - (f[0] + p.x * f[2]), o.y - (f[1] + p.y * f[3]))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun flushSel() {
        if (sel in pts.indices) {
            pts[sel] = pts[sel].copy(
                r = (live["Size"] ?: 30f) / 200f,
                br = live["Brightness"] ?: 0f,
                co = live["Contrast"] ?: 0f,
                sa = live["Saturation"] ?: 0f
            )
        }
    }

    fun selectPt(i: Int) {
        flushSel()
        sel = i
        val p = pts[i]
        live["Brightness"] = p.br; live["Contrast"] = p.co; live["Saturation"] = p.sa; live["Size"] = p.r * 200f
    }

    fun addPt(n: Offset) {
        flushSel()
        pts.add(Pt(n.x, n.y, 0.15f, 0f, 0f, 0f))
        sel = pts.lastIndex
        live["Brightness"] = 0f; live["Contrast"] = 0f; live["Saturation"] = 0f; live["Size"] = 30f
    }

    fun undoTouch() {
        when (tool?.kind) {
            "brush" -> if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            "heal", "redeye" -> if (spots.isNotEmpty()) spots.removeAt(spots.lastIndex)
            "select" -> if (sel in pts.indices) { pts.removeAt(sel); sel = -1 } else if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex)
            "liquify" -> if (warpMarks.isNotEmpty()) {
                val mk = warpMarks.removeAt(warpMarks.lastIndex)
                while (warps.size > mk) warps.removeAt(warps.lastIndex)
            }
        }
    }

    fun sample(n: Offset): FloatArray? {
        val bm = base ?: return null
        val x = (n.x * (bm.width - 1)).roundToInt()
        val y = (n.y * (bm.height - 1)).roundToInt()
        var r = 0f
        var g = 0f
        var b = 0f
        var cnt = 0
        for (dy in -2..2) for (dx in -2..2) {
            val p = bm.getPixel((x + dx).coerceIn(0, bm.width - 1), (y + dy).coerceIn(0, bm.height - 1))
            r += ((p shr 16) and 255); g += ((p shr 8) and 255); b += (p and 255)
            cnt++
        }
        return floatArrayOf(r / cnt / 255f, g / cnt / 255f, b / cnt / 255f)
    }

    fun autoTone() {
        val bm = base ?: return
        val a = Engine.autoLight(bm)
        live["Exposure"] = a[0]; live["Contrast"] = a[1]; live["Highlights"] = a[2]
        live["Shadows"] = a[3]; live["Whites"] = a[4]; live["Blacks"] = a[5]
    }

    fun autoWb() {
        val bm = base ?: return
        val c = Engine.avgColor(bm)
        val r = Engine.wbFrom(c[0], c[1], c[2])
        live["Temperature"] = r[0]; live["Tint"] = r[1]
    }

    LaunchedEffect(act.incoming) {
        val u = act.incoming
        if (u != null) { load(u); act.incoming = null }
    }

    val effPts = if (tool?.kind == "select") pts.mapIndexed { i, p ->
        if (i == sel) p.copy(
            r = (live["Size"] ?: 30f) / 200f,
            br = live["Brightness"] ?: 0f,
            co = live["Contrast"] ?: 0f,
            sa = live["Saturation"] ?: 0f
        ) else p
    } else emptyList()
    val liveOp = tool?.let { t ->
        Op(t.id, t.params.associate { it.name to (live[it.name] ?: it.def) }, strokes.toList(), effPts, spots.toList(), textValue,
            if (t.kind == "curves") curvePts.toList() else emptyList(), warps.toList())
    }
    val vp = tool?.params?.filter { !it.hidden } ?: emptyList()

    LaunchedEffect(prev, ops) {
        val p = prev
        base = if (p == null) null else withContext(Dispatchers.Default) { Engine.render(p, ops) }
    }
    LaunchedEffect(base, liveOp) {
        val b = base
        shown = if (b == null || liveOp == null || liveOp.tool == "crop") b
        else withContext(Dispatchers.Default) { Engine.render(b, listOf(liveOp)) }
    }
    // Crop aspect presets -> set the crop rectangle
    LaunchedEffect(tool?.id, live["Aspect"]) {
        val bm = base
        if (tool != null && tool.id == "crop" && bm != null) {
            val a = (live["Aspect"] ?: 0f).roundToInt().coerceIn(0, 5)
            val ratio = listOf(0f, 1f, 4f / 3f, 16f / 9f, 3f / 2f, 5f / 4f)[a]
            if (ratio > 0f) {
                val rr = if (bm.height > bm.width && ratio != 1f) 1f / ratio else ratio
                var nw = 1f
                var nh = bm.width / (rr * bm.height)
                if (nh > 1f) { nh = 1f; nw = rr * bm.height / bm.width }
                live["L"] = (1f - nw) / 2f; live["R"] = (1f + nw) / 2f
                live["T"] = (1f - nh) / 2f; live["B"] = (1f + nh) / 2f
            }
        }
    }

    // Color Mixer: Hue/Sat/Lum sliders edit the currently selected color band
    LaunchedEffect(tool?.id, live["Band"], live["Hue"], live["Saturation"], live["Luminance"]) {
        if (tool != null && tool.id == "hsl") {
            val nb = (live["Band"] ?: 0f).roundToInt().coerceIn(0, 7)
            if (nb != hslBand) {
                hslBand = nb
                live["Hue"] = live["h$nb"] ?: 0f
                live["Saturation"] = live["s$nb"] ?: 0f
                live["Luminance"] = live["l$nb"] ?: 0f
            } else {
                live["h$nb"] = live["Hue"] ?: 0f
                live["s$nb"] = live["Saturation"] ?: 0f
                live["l$nb"] = live["Luminance"] ?: 0f
            }
        }
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val ae = event.toAndroidDragEvent()
                val clip = ae.clipData
                if (clip == null || clip.itemCount == 0) return false
                val uri = clip.getItemAt(0).uri ?: return false
                act.requestDragAndDropPermissions(ae)
                load(uri)
                return true
            }
        }
    }

    val swipeMod: Modifier = if (tool == null) Modifier else Modifier.pointerInput(tool) {
            // Snapseed-style: swipe up/down = pick parameter, left/right = change value
            var accX = 0f
            var accY = 0f
            detectDragGestures(onDragStart = { accX = 0f; accY = 0f }) { change, d ->
                change.consume()
                if (abs(d.x) >= abs(d.y)) {
                    val p = vp[pIdx.coerceIn(0, vp.lastIndex)]
                    val cur = live[p.name] ?: p.def
                    if (p.max - p.min <= 7f) {
                        accX += d.x
                        if (abs(accX) > 60f) {
                            live[p.name] = (cur + sign(accX)).coerceIn(p.min, p.max)
                            accX = 0f
                        }
                    } else {
                        live[p.name] = (cur + d.x / (size.width * 0.8f) * (p.max - p.min)).coerceIn(p.min, p.max)
                    }
                } else {
                    accY += d.y
                    if (abs(accY) > 70f) {
                        pIdx = (pIdx + (if (accY > 0) 1 else -1)).coerceIn(0, vp.lastIndex)
                        accY = 0f
                    }
                }
            }
        }

    // ----- per-tool input -----
    val inputMod: Modifier = if (tool == null) Modifier else when (tool.kind) {
        "brush" -> Modifier.pointerInput(tool) {
            detectDragGestures(onDragStart = { o ->
                val n = norm(o)
                if (n != null) strokes.add(BrushStroke(listOf(n.x, n.y), (live["Size"] ?: 15f) / 100f * 0.2f, (live["Erase"] ?: 0f) > 0.5f))
            }) { change, _ ->
                change.consume()
                val n = norm(change.position)
                val i = strokes.lastIndex
                if (n != null && i >= 0) strokes[i] = strokes[i].copy(pts = strokes[i].pts + listOf(n.x, n.y))
            }
        }
        "select" -> Modifier
            .pointerInput(tool) {
                detectTapGestures { o ->
                    val hit = hitPt(o)
                    if (hit >= 0) selectPt(hit)
                    else {
                        val n = norm(o)
                        if (n != null && pts.size < 8) addPt(n)
                    }
                }
            }
            .pointerInput(tool) {
                detectDragGestures(onDragStart = { o ->
                    val hit = hitPt(o)
                    if (hit >= 0) selectPt(hit)
                }) { change, d ->
                    change.consume()
                    val bm = base
                    if (bm != null && sel in pts.indices) {
                        val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                        val p = pts[sel]
                        pts[sel] = p.copy(x = (p.x + d.x / zoom / f[2]).coerceIn(0f, 1f), y = (p.y + d.y / zoom / f[3]).coerceIn(0f, 1f))
                    }
                }
            }
        "heal" -> Modifier
            .pointerInput(tool) {
                detectTapGestures { o ->
                    val n = norm(o)
                    if (n != null) spots.add(Spot(n.x, n.y, (live["Size"] ?: 20f) / 100f * 0.12f))
                }
            }
            .pointerInput(tool) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val n = norm(change.position)
                    if (n != null) {
                        val r = (live["Size"] ?: 20f) / 100f * 0.12f
                        val last = spots.lastOrNull()
                        if (last == null || abs(n.x - last.x) + abs(n.y - last.y) > r * 0.8f) spots.add(Spot(n.x, n.y, r))
                    }
                }
            }
        "curves" -> Modifier
        "grad" -> Modifier.pointerInput(tool) {
            var mode = 2
            detectDragGestures(onDragStart = { o0 ->
                val o = unz(o0)
                val bm = base
                val n = norm(o0)
                if (bm != null && n != null) {
                    val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                    val ax = f[0] + (live["X0"] ?: 0.5f) * f[2]
                    val ay = f[1] + (live["Y0"] ?: 0.25f) * f[3]
                    val bx = f[0] + (live["X1"] ?: 0.5f) * f[2]
                    val bY = f[1] + (live["Y1"] ?: 0.6f) * f[3]
                    if (hypot(o.x - ax, o.y - ay) < hitPx / zoom) mode = 1
                    else if (hypot(o.x - bx, o.y - bY) < hitPx / zoom) mode = 2
                    else {
                        live["X0"] = n.x; live["Y0"] = n.y; live["X1"] = n.x; live["Y1"] = n.y
                        mode = 2
                    }
                }
            }) { change, _ ->
                change.consume()
                val n = norm(change.position)
                if (n != null) {
                    if (mode == 1) { live["X0"] = n.x; live["Y0"] = n.y }
                    else { live["X1"] = n.x; live["Y1"] = n.y }
                }
            }
        }
        "liquify" -> Modifier
            .pointerInput(tool) {
                detectDragGestures(onDragStart = { warpMarks.add(warps.size) }) { change, d ->
                    change.consume()
                    val bm = base
                    val n = norm(change.position)
                    if (bm != null && n != null) {
                        val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                        val mode = (live["Mode"] ?: 0f).roundToInt()
                        val r = (live["Size"] ?: 25f) / 100f * 0.2f
                        val st = live["Strength"] ?: 50f
                        if (mode == 0) warps.add(Warp(n.x, n.y, d.x / zoom / f[2] * st / 50f, d.y / zoom / f[3] * st / 50f, r, 0, 0f))
                        else warps.add(Warp(n.x, n.y, 0f, 0f, r, mode, st / 100f * 0.08f))
                    }
                }
            }
            .pointerInput(tool) {
                detectTapGestures { o ->
                    val n = norm(o)
                    val mode = (live["Mode"] ?: 0f).roundToInt()
                    if (n != null && mode > 0) {
                        warpMarks.add(warps.size)
                        warps.add(Warp(n.x, n.y, 0f, 0f, (live["Size"] ?: 25f) / 100f * 0.2f, mode, (live["Strength"] ?: 50f) / 100f * 0.3f))
                    }
                }
            }
        "crop" -> Modifier.pointerInput(tool) {
            var mode = 0
            detectDragGestures(onDragStart = { o0 ->
                val o = unz(o0)
                val bm = base
                if (bm != null) {
                    val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                    val l = f[0] + (live["L"] ?: 0f) * f[2]
                    val r = f[0] + (live["R"] ?: 1f) * f[2]
                    val t = f[1] + (live["T"] ?: 0f) * f[3]
                    val b = f[1] + (live["B"] ?: 1f) * f[3]
                    var m = 0
                    if (abs(o.x - l) < hitPx / zoom) m = m or 1 else if (abs(o.x - r) < hitPx / zoom) m = m or 4
                    if (abs(o.y - t) < hitPx / zoom) m = m or 2 else if (abs(o.y - b) < hitPx / zoom) m = m or 8
                    if (m == 0 && o.x in l..r && o.y in t..b) m = 16
                    mode = m
                }
            }) { change, d ->
                change.consume()
                val bm = base
                if (bm != null) {
                    val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                    val dx = d.x / zoom / f[2]
                    val dy = d.y / zoom / f[3]
                    var l = live["L"] ?: 0f
                    var r = live["R"] ?: 1f
                    var t = live["T"] ?: 0f
                    var b = live["B"] ?: 1f
                    if ((mode and 16) != 0) {
                        val w = r - l
                        val h = b - t
                        l = (l + dx).coerceIn(0f, 1f - w); r = l + w
                        t = (t + dy).coerceIn(0f, 1f - h); b = t + h
                    } else {
                        if ((mode and 1) != 0) l = (l + dx).coerceIn(0f, r - 0.05f)
                        if ((mode and 4) != 0) r = (r + dx).coerceIn(l + 0.05f, 1f)
                        if ((mode and 2) != 0) t = (t + dy).coerceIn(0f, b - 0.05f)
                        if ((mode and 8) != 0) b = (b + dy).coerceIn(t + 0.05f, 1f)
                    }
                    live["L"] = l; live["R"] = r; live["T"] = t; live["B"] = b
                }
            }
        }
        "pick" -> Modifier
            .pointerInput(tool) {
                detectTapGestures { o ->
                    val n = norm(o)
                    val c = if (n != null) sample(n) else null
                    if (c != null) {
                        if (tool.id == "wb") {
                            val r = Engine.wbFrom(c[0], c[1], c[2])
                            live["Temperature"] = r[0]; live["Tint"] = r[1]
                        } else {
                            val mx = max(c[0], max(c[1], c[2]))
                            val mn = min(c[0], min(c[1], c[2]))
                            val d = mx - mn
                            if (d > 0.02f) {
                                var hh = (when {
                                    mx == c[0] -> ((c[1] - c[2]) / d) % 6f
                                    mx == c[1] -> (c[2] - c[0]) / d + 2f
                                    else -> (c[0] - c[1]) / d + 4f
                                }) * 60f
                                if (hh < 0f) hh += 360f
                                live["Hue"] = hh; live["Mode"] = 1f
                            }
                        }
                    }
                }
            }
            .then(swipeMod)
        "redeye" -> Modifier.pointerInput(tool) {
            detectTapGestures { o ->
                val n = norm(o)
                if (n != null) spots.add(Spot(n.x, n.y, (live["Size"] ?: 15f) / 100f * 0.12f))
            }
        }
        "radial" -> Modifier.pointerInput(tool) {
            var mode = 0
            detectDragGestures(onDragStart = { o0 ->
                val o = unz(o0)
                val n = norm(o0)
                val bm = base
                if (bm != null && n != null) {
                    val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                    val cx = f[0] + (live["CX"] ?: 0.5f) * f[2]
                    val cy = f[1] + (live["CY"] ?: 0.5f) * f[3]
                    val ex = cx + (live["RX"] ?: 0.3f) * f[2]
                    val ey = cy + (live["RY"] ?: 0.25f) * f[3]
                    if (hypot(o.x - ex, o.y - cy) < hitPx / zoom) mode = 1
                    else if (hypot(o.x - cx, o.y - ey) < hitPx / zoom) mode = 2
                    else if (hypot(o.x - cx, o.y - cy) < hitPx / zoom * 1.4f) mode = 3
                    else {
                        live["CX"] = n.x; live["CY"] = n.y; live["RX"] = 0.02f; live["RY"] = 0.02f
                        mode = 4
                    }
                }
            }) { change, d ->
                change.consume()
                val n = norm(change.position)
                val bm = base
                if (n != null && bm != null) {
                    val cx = live["CX"] ?: 0.5f
                    val cy = live["CY"] ?: 0.5f
                    when (mode) {
                        1 -> { live["RX"] = max(0.02f, abs(n.x - cx)) }
                        2 -> { live["RY"] = max(0.02f, abs(n.y - cy)) }
                        4 -> { live["RX"] = max(0.02f, abs(n.x - cx)); live["RY"] = max(0.02f, abs(n.y - cy)) }
                        3 -> {
                            val f = fit(boxSize.width.toFloat(), boxSize.height.toFloat(), bm.width, bm.height)
                            live["CX"] = (cx + d.x / zoom / f[2]).coerceIn(0f, 1f)
                            live["CY"] = (cy + d.y / zoom / f[3]).coerceIn(0f, 1f)
                        }
                        else -> {}
                    }
                }
            }
        }
        else -> swipeMod
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { e -> e.mimeTypes().any { it.startsWith("image/") } },
                target = dropTarget
            )
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(BAR).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (tool == null) {
                Txt("OPEN", Modifier.clickable { pick() }, ACC, 15, true)
                Spacer(Modifier.weight(1f))
                if (prev != null) {
                    Txt("↶", Modifier.clickable(enabled = hi > 0) { hi-- }, if (hi > 0) TXT else DIM, 26)
                    Txt("↷", Modifier.clickable(enabled = hi < hist.lastIndex) { hi++ }, if (hi < hist.lastIndex) TXT else DIM, 26)
                    Txt("◧", Modifier.pointerInput(Unit) {
                        detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false })
                    }, TXT, 26)
                    Txt("▥", Modifier.clickable { histoMode = (histoMode + 1) % 3 }, if (histoMode > 0) ACC else TXT, 26)
                    Txt("☰", Modifier.clickable { showStack = !showStack }, if (showStack) ACC else TXT, 26)
                }
            } else {
                Txt(tool.label, Modifier, TXT, 17, true)
                Spacer(Modifier.weight(1f))
                if (zoom > 1f) Txt("⤢ reset zoom", Modifier.clickable { zoom = 1f; pan = Offset.Zero }, ACC, 13, true)
                Txt("▥", Modifier.clickable { histoMode = (histoMode + 1) % 3 }, if (histoMode > 0) ACC else TXT, 24)
            }
        }

        // Image area
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0A))
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    // two-finger pinch zoom + pan (consumed first so tools don't react)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                val pc = ev.calculatePan()
                                val cen = ev.calculateCentroid()
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val nz = (zoom * z).coerceIn(1f, 8f)
                                val ratio = nz / zoom
                                val np = pan * ratio + (cen - c) * (1f - ratio) + pc
                                val mx = size.width * (nz - 1f) / 2f
                                val my = size.height * (nz - 1f) / 2f
                                zoom = nz
                                pan = if (nz <= 1f) Offset.Zero else Offset(np.x.coerceIn(-mx, mx), np.y.coerceIn(-my, my))
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .then(if (tool == null) Modifier.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
                } else Modifier)
                .then(inputMod),
            contentAlignment = Alignment.Center
        ) {
            val img = if (comparing) prev else shown
            if (img == null) {
                Column(
                    Modifier.fillMaxSize().clickable { pick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Txt("＋", Modifier, DIM, 64)
                    Txt("Tap to open a photo", Modifier.padding(top = 8.dp), DIM, 15)
                    Txt("or drag one in", Modifier, DIM, 13)
                }
            } else {
                Image(
                    bitmap = img.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                    }
                )
            }
            val sb = shown
            if (histoMode == 2 && sb != null && !comparing) {
                ClipOverlay(sb, Modifier.graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                })
            }
            if (histoMode >= 1 && sb != null && !comparing) {
                Histo(sb, Modifier.align(Alignment.TopStart).padding(8.dp).size(130.dp, 56.dp))
            }
            val bm = base
            if (tool != null && bm != null && !comparing) {
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                }) {
                    val f = fit(size.width, size.height, bm.width, bm.height)
                    if (tool.id == "crop") {
                        val l = f[0] + (live["L"] ?: 0f) * f[2]
                        val r = f[0] + (live["R"] ?: 1f) * f[2]
                        val t = f[1] + (live["T"] ?: 0f) * f[3]
                        val b = f[1] + (live["B"] ?: 1f) * f[3]
                        val dim = Color(0x99000000)
                        drawRect(dim, Offset(f[0], f[1]), Size(l - f[0], f[3]))
                        drawRect(dim, Offset(r, f[1]), Size(f[0] + f[2] - r, f[3]))
                        drawRect(dim, Offset(l, f[1]), Size(r - l, t - f[1]))
                        drawRect(dim, Offset(l, b), Size(r - l, f[1] + f[3] - b))
                        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(3f))
                        for (k in 1..2) {
                            val gx = l + (r - l) * k / 3f
                            val gy = t + (b - t) * k / 3f
                            drawLine(Color(0x88FFFFFF), Offset(gx, t), Offset(gx, b), 1f)
                            drawLine(Color(0x88FFFFFF), Offset(l, gy), Offset(r, gy), 1f)
                        }
                        listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color.White, 12f, it) }
                    } else if (tool.id == "radial") {
                        val cx = f[0] + (live["CX"] ?: 0.5f) * f[2]
                        val cy = f[1] + (live["CY"] ?: 0.5f) * f[3]
                        val rx = (live["RX"] ?: 0.3f) * f[2]
                        val ry = (live["RY"] ?: 0.25f) * f[3]
                        drawOval(Color.White, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f), style = Stroke(3f))
                        drawCircle(Color(0xFF4DA3FF), 12f, Offset(cx, cy))
                        drawCircle(Color.White, 12f, Offset(cx + rx, cy))
                        drawCircle(Color.White, 12f, Offset(cx, cy + ry))
                    } else if (tool.id == "grad") {
                        val a = Offset(f[0] + (live["X0"] ?: 0.5f) * f[2], f[1] + (live["Y0"] ?: 0.25f) * f[3])
                        val bq = Offset(f[0] + (live["X1"] ?: 0.5f) * f[2], f[1] + (live["Y1"] ?: 0.6f) * f[3])
                        drawLine(Color.White, a, bq, 3f)
                        drawCircle(Color(0xFF4DA3FF), 14f, a)
                        drawCircle(Color.White, 14f, bq)
                    } else if (tool.kind == "select") {
                        effPts.forEachIndexed { i, p ->
                            val c = Offset(f[0] + p.x * f[2], f[1] + p.y * f[3])
                            val col = if (i == sel) Color(0xFF4DA3FF) else Color.White
                            drawCircle(col, p.r * min(f[2], f[3]), c, style = Stroke(3f))
                            drawCircle(col, 9f, c)
                        }
                    }
                }
            }
            if (tool != null && tool.kind != "curves" && vp.isNotEmpty()) {
                val p = vp[pIdx.coerceIn(0, vp.lastIndex)]
                Txt(
                    "${p.name}  ${fmt(tool, p, live[p.name] ?: p.def)}",
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    TXT, 16, true
                )
            }
        }

        // Bottom panel
        Column(Modifier.fillMaxWidth().background(BAR)) {
            when {
                showStack -> StackPanel(
                    ops,
                    onChange = { commit(it) },
                    onClose = { showStack = false },
                    onCopy = { clipOps = Presets.portable(ops); toast("Edits copied") },
                    onPaste = { if (clipOps.isEmpty()) toast("Nothing copied") else { commit(ops + clipOps); toast("Edits pasted") } },
                    onInfo = { infoDialog = true }
                )
                tool != null -> {
                    if (tool.kind == "curves") {
                        val chn = (live["Channel"] ?: 0f).roundToInt().coerceIn(0, 3)
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("RGB", "Red", "Green", "Blue").forEachIndexed { i, nm ->
                                Chip(nm, i == chn) { live["Channel"] = i.toFloat() }
                            }
                            Spacer(Modifier.weight(1f))
                            Txt("RESET", Modifier.clickable { curvePts[chn] = listOf(0f, 0f, 1f, 1f) }.padding(6.dp), DIM, 12, true)
                        }
                        CurveEditor(curvePts[chn], chn, { curvePts[chn] = it }, Modifier.padding(12.dp).fillMaxWidth().height(170.dp))
                    } else {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(vp) { i, p ->
                            val on = i == pIdx
                            Txt(
                                "${p.name} ${fmt(tool, p, live[p.name] ?: p.def)}",
                                Modifier
                                    .background(if (on) ACC else Color(0xFF3A3A3A), RoundedCornerShape(16.dp))
                                    .clickable { pIdx = i }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                if (on) Color.Black else TXT, 13, true
                            )
                        }
                    }
                    if (vp.isNotEmpty()) {
                        val p = vp[pIdx.coerceIn(0, vp.lastIndex)]
                        val discrete = p.max - p.min <= 7f
                        Slider(
                            value = live[p.name] ?: p.def,
                            onValueChange = { live[p.name] = if (discrete) it.roundToInt().toFloat() else it },
                            valueRange = p.min..p.max,
                            steps = if (discrete && p.max - p.min > 1f) (p.max - p.min).toInt() - 1 else 0,
                            colors = SliderDefaults.colors(thumbColor = ACC, activeTrackColor = ACC),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Txt("✕", Modifier.clickable { active = null }.padding(8.dp), TXT, 26)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            when (tool.kind) {
                                "brush", "heal", "select", "liquify", "redeye" -> Txt(
                                    if (tool.kind == "select") "tap: add/select · UNDO removes point" else "paint on photo · UNDO",
                                    Modifier.clickable { undoTouch() }.padding(8.dp), ACC, 12, true
                                )
                                "crop" -> Txt("drag the handles", Modifier, DIM, 12)
                                "radial" -> Txt("drag to place · drag dots to resize", Modifier, DIM, 12)
                                "pick" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tool.id == "wb") Txt("✦ AUTO", Modifier.clickable { autoWb() }.padding(8.dp), ACC, 13, true)
                                    Txt(if (tool.id == "wb") "or tap a neutral spot" else "tap photo to pick a color", Modifier, DIM, 12)
                                }
                                "curves" -> Txt("drag to bend · double-tap a point to remove", Modifier, DIM, 12)
                                "grad" -> Txt("drag to place gradient · drag dots to adjust", Modifier, DIM, 12)
                                else -> if (tool.id == "text") Txt("✎ Edit text", Modifier.clickable { textDialog = true }.padding(8.dp), ACC, 13, true)
                                else if (tool.id == "light") Txt("✦ AUTO TONE", Modifier.clickable { autoTone() }.padding(8.dp), ACC, 13, true)
                                else Txt("swipe ↔ adjust  ·  ↕ switch", Modifier, DIM, 12)
                            }
                        }
                        Txt("✓", Modifier.clickable {
                            val op = liveOp
                            if (op != null && changed(op)) commit(ops + (if (op.tool == "range") op.copy(v = op.v + ("Show" to 0f)) else op))
                            active = null
                        }.padding(8.dp), ACC, 28, true)
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth().height(250.dp)) {
                        when (tab) {
                            0 -> LooksRow(
                                prev, ops, userPresets,
                                onPick = { commit(it) },
                                onSave = {
                                    if (Presets.portable(ops).isEmpty()) toast("Make some edits first")
                                    else { presetName = ""; saveDialog = true }
                                },
                                onDelete = { Presets.remove(ctx, it); userPresets = Presets.load(ctx) }
                            )
                            1 -> Column(Modifier.fillMaxSize()) {
                                LazyRow(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(listOf("All") + Tools.cats.keys.toList()) { c -> Chip(c, c == cat) { cat = c } }
                                }
                                val list = if (cat == "All") Tools.all else Tools.cats[cat].orEmpty().map { Tools.byId(it) }
                                LazyVerticalGrid(
                                    GridCells.Fixed(4),
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(8.dp)
                                ) {
                                    items(list) { t ->
                                        Column(
                                            Modifier.clickable { openTool(t) }.padding(vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Txt(t.icon, Modifier, TXT, 26)
                                            Text(t.label, color = TXT, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
                                        }
                                    }
                                }
                            }
                            else -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Txt("Format", Modifier.width(64.dp), DIM, 12)
                                    listOf("JPEG", "PNG", "WebP").forEachIndexed { i, nm -> Chip(nm, expFmt == i) { expFmt = i } }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Txt("Size", Modifier.width(64.dp), DIM, 12)
                                    listOf(100, 75, 50, 25).forEach { pc -> Chip("$pc%", expScale == pc) { expScale = pc } }
                                }
                                OutlinedTextField(
                                    value = expWm, onValueChange = { expWm = it },
                                    label = { Text("Watermark (optional)") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (expFmt != 1) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt("Quality ${expQ.roundToInt()}", Modifier.width(100.dp), DIM, 12)
                                    Slider(
                                        value = expQ, onValueChange = { expQ = it }, valueRange = 40f..100f,
                                        colors = SliderDefaults.colors(thumbColor = ACC, activeTrackColor = ACC),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { export(false) }, enabled = src != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = ACC, contentColor = Color.Black)
                                    ) { Text("Save to gallery") }
                                    Button(
                                        onClick = { export(true) }, enabled = src != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A), contentColor = TXT)
                                    ) { Text("Share") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp)) {
                        listOf("LOOKS", "TOOLS", "EXPORT").forEachIndexed { i, s ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clickable { tab = i },
                                contentAlignment = Alignment.Center
                            ) {
                                Txt(s, Modifier, if (tab == i) ACC else DIM, 14, true)
                                if (tab == i) Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.4f).height(3.dp).background(ACC)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val nm = presetName.trim()
                    if (nm.isNotEmpty()) { Presets.add(ctx, nm, ops); userPresets = Presets.load(ctx) }
                    saveDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
            title = { Text("Save as Look") },
            text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it }, singleLine = true, label = { Text("Name") }) }
        )
    }
    if (infoDialog) {
        AlertDialog(
            onDismissRequest = { infoDialog = false },
            confirmButton = { TextButton(onClick = { infoDialog = false }) { Text("OK") } },
            title = { Text("Photo info") },
            text = { Text(infoText.ifBlank { "No info available" }) }
        )
    }
    if (textDialog) {
        AlertDialog(
            onDismissRequest = { textDialog = false },
            confirmButton = { TextButton(onClick = { textDialog = false }) { Text("OK") } },
            title = { Text("Text") },
            text = { OutlinedTextField(value = textValue, onValueChange = { textValue = it }) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LooksRow(
    prev: Bitmap?,
    ops: List<Op>,
    user: List<Pair<String, List<Op>>>,
    onPick: (List<Op>) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit
) {
    val all = Tools.looks + user
    val thumbs by produceState<List<Bitmap>>(emptyList(), prev, ops, user) {
        val p = prev
        value = if (p == null) emptyList() else withContext(Dispatchers.Default) {
            val t = scaleTo(p, 180)
            all.map { Engine.render(t, ops + it.second) }
        }
    }
    if (prev == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Open a photo to see looks", Modifier, DIM, 14) }
        return
    }
    LazyRow(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            Column(Modifier.clickable { onSave() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(110.dp).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                    Txt("＋", Modifier, ACC, 36, true)
                }
                Txt("Save look", Modifier.padding(top = 6.dp), TXT, 12)
            }
        }
        itemsIndexed(all) { i, l ->
            val isUser = i >= Tools.looks.size
            Column(
                Modifier.combinedClickable(
                    onClick = { onPick(ops + l.second) },
                    onLongClick = { if (isUser) onDelete(l.first) }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val t = thumbs.getOrNull(i)
                if (t != null) Image(t.asImageBitmap(), null, Modifier.size(110.dp), contentScale = ContentScale.Crop)
                else Box(Modifier.size(110.dp).background(Color(0xFF333333)))
                Txt(if (isUser) "★ ${l.first}" else l.first, Modifier.padding(top = 6.dp), TXT, 12)
            }
        }
    }
}

// Edit stack: long-press the ≡ handle and drag to reorder, ✕ to remove
@Composable
fun StackPanel(
    ops: List<Op>,
    onChange: (List<Op>) -> Unit,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInfo: () -> Unit
) {
    val rowH = 52.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Edits", Modifier.weight(1f), TXT, 16, true)
            Txt("INFO", Modifier.clickable { onInfo() }.padding(8.dp), TXT, 12, true)
            Txt("COPY", Modifier.clickable { onCopy() }.padding(8.dp), TXT, 12, true)
            Txt("PASTE", Modifier.clickable { onPaste() }.padding(8.dp), TXT, 12, true)
            Txt("DONE", Modifier.clickable { onClose() }.padding(8.dp), ACC, 14, true)
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            if (ops.isEmpty()) Txt("No edits yet", Modifier.padding(16.dp), DIM, 14)
            ops.forEachIndexed { i, op ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowH)
                        .offset { IntOffset(0, if (i == dragging) dy.roundToInt() else 0) }
                        .zIndex(if (i == dragging) 1f else 0f)
                        .background(if (i == dragging) Color(0xFF3A3A3A) else BAR)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Txt(
                        "≡",
                        Modifier.pointerInput(ops, i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = i; dy = 0f },
                                onDrag = { c, d -> c.consume(); dy += d.y },
                                onDragEnd = {
                                    val to = (i + (dy / rowPx).roundToInt()).coerceIn(0, ops.lastIndex)
                                    if (to != i) {
                                        val m = ops.toMutableList()
                                        m.add(to, m.removeAt(i))
                                        onChange(m)
                                    }
                                    dragging = -1; dy = 0f
                                },
                                onDragCancel = { dragging = -1; dy = 0f }
                            )
                        }.padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                        DIM, 24
                    )
                    Txt(Tools.byId(op.tool).label, Modifier.weight(1f), TXT, 15)
                    Txt("✕", Modifier.clickable { onChange(ops.filterIndexed { j, _ -> j != i }) }.padding(8.dp), DIM, 20)
                }
            }
        }
    }
}


@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) =
    Txt(
        label,
        Modifier.background(if (on) ACC else Color(0xFF3A3A3A), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        if (on) Color.Black else TXT, 13, true
    )

@Composable
fun Histo(bm: Bitmap, modifier: Modifier) {
    val data by produceState<IntArray?>(null, bm) {
        value = withContext(Dispatchers.Default) {
            val h = IntArray(768)
            val px = IntArray(bm.width * bm.height)
            bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
            val step = max(1, px.size / 150000)
            var i = 0
            while (i < px.size) {
                val p = px[i]
                h[(p shr 16) and 255]++
                h[256 + ((p shr 8) and 255)]++
                h[512 + (p and 255)]++
                i += step
            }
            h
        }
    }
    Canvas(modifier.background(Color(0x99000000))) {
        val d = data
        if (d != null) {
            var mx = 1
            for (i in 1..254) mx = maxOf(mx, d[i], d[256 + i], d[512 + i])
            val cols = listOf(Color(0x88FF4040), Color(0x8840FF40), Color(0x884080FF))
            for (c in 0..2) for (i in 0..255) {
                val hgt = (d[c * 256 + i].toFloat() / mx).coerceAtMost(1f) * size.height
                val x = i / 255f * size.width
                drawLine(cols[c], Offset(x, size.height), Offset(x, size.height - hgt), size.width / 256f * 1.3f)
            }
        }
    }
}

@Composable
fun CurveEditor(pts: List<Float>, ch: Int, onChange: (List<Float>) -> Unit, modifier: Modifier) {
    val cur by rememberUpdatedState(pts)
    val cb by rememberUpdatedState(onChange)
    val col = listOf(Color.White, Color(0xFFFF5252), Color(0xFF66BB6A), Color(0xFF42A5F5))[ch.coerceIn(0, 3)]
    Canvas(
        modifier
            .background(Color(0xFF181818))
            .pointerInput(ch) {
                var work = listOf<Float>()
                var idx = -1
                detectDragGestures(onDragStart = { o ->
                    val nx = (o.x / size.width).coerceIn(0f, 1f)
                    val ny = 1f - (o.y / size.height).coerceIn(0f, 1f)
                    var l = cur
                    var best = -1
                    var bd = 0.09f
                    for (i in 0 until l.size / 2) {
                        val d = hypot(l[2 * i] - nx, l[2 * i + 1] - ny)
                        if (d < bd) { bd = d; best = i }
                    }
                    if (best < 0) {
                        val list = (0 until l.size / 2).map { Pair(l[2 * it], l[2 * it + 1]) }.toMutableList()
                        list.add(Pair(nx, ny))
                        list.sortBy { it.first }
                        best = list.indexOfFirst { it.first == nx && it.second == ny }
                        l = list.flatMap { listOf(it.first, it.second) }
                        cb(l)
                    }
                    work = l
                    idx = best
                }) { change, _ ->
                    change.consume()
                    val l = work.toMutableList()
                    val n = l.size / 2
                    if (idx in 0 until n) {
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        if (idx != 0 && idx != n - 1) {
                            val lo = l[2 * (idx - 1)] + 0.01f
                            val hi = l[2 * (idx + 1)] - 0.01f
                            l[2 * idx] = nx.coerceIn(lo, max(lo, hi))
                        }
                        l[2 * idx + 1] = ny
                        work = l
                        cb(l)
                    }
                }
            }
            .pointerInput(ch) {
                detectTapGestures(onDoubleTap = { o ->
                    val l = cur
                    val n = l.size / 2
                    val nx = (o.x / size.width).coerceIn(0f, 1f)
                    val ny = 1f - (o.y / size.height).coerceIn(0f, 1f)
                    var best = -1
                    var bd = 0.09f
                    for (i in 1 until n - 1) {
                        val d = hypot(l[2 * i] - nx, l[2 * i + 1] - ny)
                        if (d < bd) { bd = d; best = i }
                    }
                    if (best >= 0) {
                        val m = l.toMutableList()
                        m.removeAt(2 * best + 1)
                        m.removeAt(2 * best)
                        cb(m)
                    }
                })
            }
    ) {
        val w = size.width
        val h = size.height
        for (k in 1..3) {
            drawLine(Color(0xFF333333), Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
            drawLine(Color(0xFF333333), Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        }
        drawLine(Color(0xFF444444), Offset(0f, h), Offset(w, 0f), 1f)
        val lut = Engine.curveLut(pts)
        for (i in 0 until 255) {
            drawLine(col, Offset(i / 255f * w, h - lut[i] / 255f * h), Offset((i + 1) / 255f * w, h - lut[i + 1] / 255f * h), 3f)
        }
        for (i in 0 until pts.size / 2) {
            drawCircle(Color.White, 10f, Offset(pts[2 * i] * w, h - pts[2 * i + 1] * h))
            drawCircle(col, 6f, Offset(pts[2 * i] * w, h - pts[2 * i + 1] * h))
        }
    }
}


fun watermark(b: Bitmap, text: String): Bitmap {
    val o = b.copy(Bitmap.Config.ARGB_8888, true)
    val cv = android.graphics.Canvas(o)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0xB3FFFFFF.toInt()
    p.textSize = min(o.width, o.height) * 0.035f
    p.textAlign = android.graphics.Paint.Align.RIGHT
    p.setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
    cv.drawText(text, o.width - p.textSize, o.height - p.textSize, p)
    return o
}

fun exifInfo(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val e = ExifInterface(ins)
        val lines = ArrayList<String>()
        val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ")
        if (cam.isNotBlank()) lines.add(cam)
        e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { lines.add(it) }
        val iso = e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        val et = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val fn = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        val parts = ArrayList<String>()
        if (iso != null) parts.add("ISO $iso")
        if (et > 0.0) parts.add(if (et < 1.0) "1/${(1.0 / et).roundToInt()}s" else "${et}s")
        if (fn > 0.0) parts.add("f/" + "%.1f".format(fn))
        if (fl > 0.0) parts.add("${fl.roundToInt()}mm")
        if (parts.isNotEmpty()) lines.add(parts.joinToString("  ·  "))
        e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { lines.add(it) }
        lines.joinToString("\n")
    }.orEmpty()
}.getOrDefault("")

@Composable
fun ClipOverlay(bm: Bitmap, modifier: Modifier) {
    val ov by produceState<Bitmap?>(null, bm) {
        value = withContext(Dispatchers.Default) {
            val w = bm.width
            val h = bm.height
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val p = px[i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                px[i] = if (r >= 254 || g >= 254 || b >= 254) 0xCCFF3030.toInt()
                else if (r <= 1 && g <= 1 && b <= 1) 0xCC3060FF.toInt() else 0
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
    val o = ov
    if (o != null) Image(o.asImageBitmap(), null, modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}
