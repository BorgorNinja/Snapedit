package app.snapedit

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.*
import kotlin.random.Random

data class Param(val name: String, val min: Float, val max: Float, val def: Float, val hidden: Boolean = false)
data class Tool(val id: String, val icon: String, val label: String, val params: List<Param>, val kind: String = "swipe")
data class BrushStroke(val pts: List<Float>, val r: Float, val erase: Boolean, val color: Int = 0xFFFFFFFF.toInt(), val glow: Float = 0f)
data class Pt(val x: Float, val y: Float, val r: Float, val br: Float, val co: Float, val sa: Float)
data class Spot(val x: Float, val y: Float, val r: Float)
data class Warp(val x: Float, val y: Float, val dx: Float, val dy: Float, val r: Float, val mode: Int, val k: Float)
data class Op(
    val tool: String,
    val v: Map<String, Float>,
    val strokes: List<BrushStroke> = emptyList(),
    val pts: List<Pt> = emptyList(),
    val spots: List<Spot> = emptyList(),
    val text: String = "",
    val curves: List<List<Float>> = emptyList(),
    val warps: List<Warp> = emptyList()
)

private fun sym(n: String, d: Float = 0f) = Param(n, -100f, 100f, d)
private fun pos(n: String, d: Float = 0f) = Param(n, 0f, 100f, d)

private fun hslHidden() = (0..7).flatMap {
    listOf(Param("h$it", -100f, 100f, 0f, true), Param("s$it", -100f, 100f, 0f, true), Param("l$it", -100f, 100f, 0f, true))
}

object Tools {
    val all = listOf(
        Tool("tune", "🎚️", "Tune Image", listOf(sym("Brightness"), sym("Contrast"), sym("Saturation"), sym("Ambiance"), sym("Highlights"), sym("Shadows"), sym("Warmth"))),
        Tool("details", "🔍", "Details", listOf(sym("Structure"), pos("Sharpening"))),
        Tool("crop", "✂️", "Crop", listOf(Param("Aspect", 0f, 5f, 0f), Param("L", 0f, 1f, 0f, true), Param("T", 0f, 1f, 0f, true), Param("R", 0f, 1f, 1f, true), Param("B", 0f, 1f, 1f, true)), "crop"),
        Tool("rotate", "🔄", "Rotate", listOf(Param("Rotate", 0f, 3f, 0f), Param("Flip", 0f, 1f, 0f), Param("Straighten", -45f, 45f, 0f))),
        Tool("persp", "📐", "Perspective", listOf(sym("Vertical"), sym("Horizontal"), sym("Rotate"), sym("Scale"))),
        Tool("light", "☀️", "Light", listOf(sym("Exposure"), sym("Contrast"), sym("Highlights"), sym("Shadows"), sym("Whites"), sym("Blacks"))),
        Tool("curves", "📈", "Curves", listOf(Param("Channel", 0f, 3f, 0f)), "curves"),
        Tool("hsl", "🌈", "Color Mixer", listOf(Param("Band", 0f, 7f, 0f), sym("Hue"), sym("Saturation"), sym("Luminance")) + hslHidden()),
        Tool("grade", "🎨", "Color Grading", listOf(Param("Shadow Hue", 0f, 360f, 220f), pos("Shadow Sat"), Param("Mid Hue", 0f, 360f, 40f), pos("Mid Sat"), Param("High Hue", 0f, 360f, 45f), pos("High Sat"), sym("Balance"))),
        Tool("presence", "🔆", "Presence", listOf(sym("Texture"), sym("Clarity"), sym("Dehaze"), sym("Vibrance"))),
        Tool("denoise", "🧹", "Noise Reduction", listOf(pos("Luminance"), pos("Color"), pos("Detail", 50f))),
        Tool("grad", "▤", "Graduated", listOf(sym("Exposure"), sym("Contrast"), sym("Saturation"), sym("Warmth"), sym("Highlights"), sym("Shadows"), Param("X0", 0f, 1f, 0.5f, true), Param("Y0", 0f, 1f, 0.25f, true), Param("X1", 0f, 1f, 0.5f, true), Param("Y1", 0f, 1f, 0.6f, true)), "grad"),
        Tool("liquify", "🌀", "Liquify", listOf(Param("Mode", 0f, 4f, 0f), Param("Size", 2f, 100f, 25f), pos("Strength", 50f)), "liquify"),
        Tool("brush", "🖌️", "Brush", listOf(Param("Effect", 0f, 3f, 0f), sym("Strength", 50f), Param("Size", 1f, 100f, 15f), Param("Erase", 0f, 1f, 0f)), "brush"),
        Tool("draw", "🖍️", "Draw", listOf(Param("Color", 0f, 7f, 0f), Param("Size", 1f, 100f, 18f), pos("Glow", 50f), pos("Opacity", 95f), Param("Erase", 0f, 1f, 0f)), "brush"),
        Tool("select", "🎯", "Selective", listOf(sym("Brightness"), sym("Contrast"), sym("Saturation"), Param("Size", 5f, 100f, 30f)), "select"),
        Tool("heal", "🩹", "Healing", listOf(Param("Size", 2f, 100f, 20f)), "heal"),
        Tool("text", "🔤", "Text", listOf(Param("X", 0f, 100f, 50f), Param("Y", 0f, 100f, 50f), Param("Size", 1f, 100f, 30f), Param("Opacity", 0f, 100f, 100f), Param("Color", 0f, 5f, 0f))),
        Tool("wb", "🌡️", "White Balance", listOf(sym("Temperature"), sym("Tint")), "pick"),
        Tool("sharpen", "🔪", "Sharpening", listOf(pos("Amount", 40f), pos("Radius", 50f), pos("Masking", 30f))),
        Tool("optics", "🔭", "Lens", listOf(sym("Distortion"), sym("Vignetting"), pos("Defringe Purple"), pos("Defringe Green"))),
        Tool("radial", "◎", "Radial", listOf(sym("Exposure"), sym("Contrast"), sym("Highlights"), sym("Shadows"), sym("Saturation"), sym("Warmth"), pos("Feather", 50f), Param("Invert", 0f, 1f, 0f), Param("CX", 0f, 1f, 0.5f, true), Param("CY", 0f, 1f, 0.5f, true), Param("RX", 0f, 1f, 0.3f, true), Param("RY", 0f, 1f, 0.25f, true)), "radial"),
        Tool("range", "🎛️", "Range Mask", listOf(Param("Mode", 0f, 1f, 0f), pos("Low", 0f), pos("High", 100f), Param("Hue", 0f, 360f, 0f), pos("Width", 30f), pos("Feather", 30f), sym("Exposure"), sym("Contrast"), sym("Saturation"), sym("Warmth"), Param("Show", 0f, 1f, 0f)), "pick"),
        Tool("splash", "💧", "Color Splash", listOf(Param("Hue", 0f, 360f, 0f), pos("Tolerance", 30f), pos("Vividness", 35f), sym("Brightness"), Param("Show", 0f, 1f, 0f)), "pick"),
        Tool("redeye", "👁️", "Red Eye", listOf(Param("Size", 2f, 100f, 15f)), "redeye"),
        Tool("tonal", "◑", "Tonal Contrast", listOf(pos("High", 40f), pos("Mid", 30f), pos("Low", 20f))),
        Tool("hdr", "🏔️", "HDR Scape", listOf(pos("Strength", 50f), sym("Saturation"))),
        Tool("glow", "✨", "Glamour Glow", listOf(pos("Glow", 40f), sym("Saturation"), sym("Warmth"))),
        Tool("drama", "🌩️", "Drama", listOf(pos("Strength", 50f), sym("Saturation"))),
        Tool("vintage", "📷", "Vintage", listOf(pos("Strength", 60f), pos("Vignette", 50f))),
        Tool("grain", "🎞️", "Grainy Film", listOf(pos("Grain", 40f), pos("Size", 25f), pos("Fade", 20f))),
        Tool("retro", "📼", "Retrolux", listOf(pos("Light Leak", 30f), pos("Grain", 30f), pos("Fade", 20f))),
        Tool("noir", "🎬", "Noir", listOf(pos("Grain", 20f), pos("Fade", 10f), pos("Vignette", 30f))),
        Tool("bw", "⚫", "Black & White", listOf(Param("Filter", 0f, 7f, 0f), sym("Brightness"), sym("Contrast"), pos("Grain"))),
        Tool("blur", "💧", "Lens Blur", listOf(pos("Blur", 40f), pos("Transition", 50f))),
        Tool("vignette", "⭕", "Vignette", listOf(sym("Amount", -50f), pos("Midpoint", 50f), sym("Roundness"), pos("Feather", 50f), pos("Highlights"))),
        Tool("frames", "🖼️", "Frames", listOf(pos("Size", 5f), Param("Color", 0f, 5f, 0f), pos("Radius", 0f), pos("Shadow", 0f))),
        Tool("neon", "⚡", "Neon Glow", listOf(pos("Strength", 65f), Param("Hue", 0f, 360f, 300f), pos("Glow", 50f))),
        Tool("sketch", "✏️", "Sketch", listOf(pos("Pencil", 70f), sym("Contrast", 20f), pos("Color", 0f))),
        Tool("popart", "🎭", "Pop Art", listOf(Param("Style", 0f, 3f, 0f), Param("Levels", 2f, 8f, 4f), pos("Strength", 80f))),
        Tool("drip", "🟣", "Bloom & Drip", listOf(pos("Bloom", 60f), Param("Hue", 0f, 360f, 285f), pos("Drip", 40f))),
        Tool("flare", "🌟", "Lens Flare", listOf(pos("Strength", 55f), Param("Hue", 0f, 360f, 35f), pos("Streak", 60f), pos("Rays", 50f), Param("X", 0f, 1f, 0.72f, true), Param("Y", 0f, 1f, 0.28f, true)), "flare"),
        Tool("leak", "🔶", "Light Leak", listOf(Param("Style", 0f, 3f, 0f), pos("Strength", 50f), Param("Hue", 0f, 360f, 25f)))
    )

    fun byId(id: String) = all.first { it.id == id }

    val cats: Map<String, List<String>> = linkedMapOf(
        "Light" to listOf("tune", "light", "curves", "tonal", "hdr"),
        "Color" to listOf("wb", "hsl", "grade", "splash", "bw"),
        "Magic" to listOf("neon", "sketch", "popart", "drip", "flare", "leak", "draw"),
        "Detail" to listOf("details", "sharpen", "presence", "denoise", "optics", "blur"),
        "Local" to listOf("select", "radial", "grad", "brush", "draw", "range", "splash", "heal", "redeye"),
        "Geometry" to listOf("crop", "rotate", "persp", "liquify"),
        "Creative" to listOf("glow", "drama", "vintage", "grain", "retro", "noir", "vignette", "frames", "text")
    )

    fun op(id: String, vararg o: Pair<String, Float>): Op {
        val m = byId(id).params.associate { it.name to it.def }.toMutableMap()
        o.forEach { m[it.first] = it.second }
        return Op(id, m)
    }

    val looks: List<Pair<String, List<Op>>> = listOf(
        "Portrait" to listOf(op("tune", "Brightness" to 5f, "Ambiance" to 20f, "Shadows" to 15f, "Warmth" to 5f), op("details", "Sharpening" to 20f)),
        "Smooth" to listOf(op("tune", "Contrast" to -10f, "Highlights" to -10f, "Shadows" to 20f), op("details", "Structure" to -25f)),
        "Pop" to listOf(op("tune", "Contrast" to 25f, "Saturation" to 35f, "Ambiance" to 20f)),
        "Cyberpunk" to listOf(op("grade", "Shadow Hue" to 260f, "Shadow Sat" to 55f, "High Hue" to 315f, "High Sat" to 50f), op("neon", "Strength" to 45f, "Hue" to 305f, "Glow" to 60f)),
        "Accentuate" to listOf(op("tune", "Contrast" to 15f, "Highlights" to -20f, "Shadows" to 25f, "Ambiance" to 30f), op("details", "Structure" to 30f)),
        "Fine Art" to listOf(op("tune", "Contrast" to 20f, "Saturation" to -20f, "Highlights" to -30f), op("tonal")),
        "Push" to listOf(op("tune", "Contrast" to 30f, "Saturation" to 30f, "Warmth" to 15f), op("vignette")),
        "Golden Leak" to listOf(op("tune", "Brightness" to 8f, "Warmth" to 20f), op("leak", "Style" to 0f, "Strength" to 45f, "Hue" to 28f)),
        "Morning" to listOf(op("tune", "Brightness" to 10f, "Warmth" to 25f, "Ambiance" to 15f), op("glow", "Glow" to 30f)),
        "Faded" to listOf(op("vintage"), op("glow", "Glow" to 30f)),
        "Noir" to listOf(op("noir"))
    )
}

object Engine {
    fun render(src: Bitmap, ops: List<Op>): Bitmap {
        var b = src
        for (o in ops) b = apply(b, o)
        return b
    }

    private fun apply(b: Bitmap, o: Op): Bitmap {
        fun g(n: String) = o.v[n] ?: 0f
        val m = min(b.width, b.height)
        return when (o.tool) {
            "tune" -> tune(b, g("Brightness"), g("Contrast"), g("Saturation"), g("Ambiance"), g("Highlights"), g("Shadows"), g("Warmth"))
            "details" -> {
                val s = unsharp(b, max(3, m / 90), g("Structure") / 100f * 1.5f)
                unsharp(s, 1, g("Sharpening") / 100f * 2f)
            }
            "crop" -> cropRect(b, g("L"), g("T"), g("R"), g("B"))
            "persp" -> perspective(b, g("Vertical"), g("Horizontal"), g("Rotate"), g("Scale"))
            "curves" -> curveOp(b, o.curves)
            "light" -> light(b, g("Exposure"), g("Contrast"), g("Highlights"), g("Shadows"), g("Whites"), g("Blacks"))
            "hsl" -> hslMix(b, FloatArray(8) { g("h$it") }, FloatArray(8) { g("s$it") }, FloatArray(8) { g("l$it") })
            "grade" -> grade(b, g("Shadow Hue"), g("Shadow Sat"), g("Mid Hue"), g("Mid Sat"), g("High Hue"), g("High Sat"), g("Balance"))
            "presence" -> {
                val t = unsharp(b, max(2, m / 400), g("Texture") / 100f * 1.5f)
                vibrance(dehaze(tonal(t, 0f, g("Clarity"), 0f), g("Dehaze")), g("Vibrance"))
            }
            "denoise" -> denoise(b, g("Luminance"), g("Color"), g("Detail"))
            "grad" -> gradOp(b, o)
            "liquify" -> liquify(b, o.warps)
            "sharpen" -> sharpenPro(b, g("Amount"), g("Radius"), g("Masking"))
            "optics" -> optics(b, g("Distortion"), g("Vignetting"), g("Defringe Purple"), g("Defringe Green"))
            "radial" -> radialOp(b, o)
            "range" -> rangeOp(b, o)
            "splash" -> splashOp(b, g("Hue"), g("Tolerance"), g("Vividness"), g("Brightness"), g("Show") > 0.5f)
            "redeye" -> redEye(b, o.spots)
            "brush" -> brush(b, o.strokes, g("Effect").roundToInt(), g("Strength"))
            "draw" -> drawOp(b, o.strokes, g("Opacity"))
            "select" -> selective(b, o.pts)
            "heal" -> heal(b, o.spots)
            "text" -> textOp(b, o)
            "rotate" -> straighten(rotate(b, g("Rotate").roundToInt(), g("Flip") > 0.5f), g("Straighten"))
            "wb" -> wb(b, g("Temperature"), g("Tint"))
            "tonal" -> tonal(b, g("High"), g("Mid"), g("Low"))
            "hdr" -> {
                val st = g("Strength")
                val s = unsharp(b, max(4, m / 60), st / 100f * 1.5f)
                tune(s, 0f, st * 0.15f, g("Saturation") + st * 0.3f, 0f, -st * 0.3f, st * 0.5f, 0f)
            }
            "glow" -> glow(b, g("Glow"), g("Saturation"), g("Warmth"))
            "drama" -> {
                val st = g("Strength")
                tune(unsharp(b, max(4, m / 70), st / 100f * 2f), 0f, st * 0.4f, g("Saturation") - st * 0.3f, 0f, -st * 0.3f, st * 0.2f, 0f)
            }
            "vintage" -> vignette(vintage(b, g("Strength") / 100f), -g("Vignette"), 50f)
            "grain" -> grainPro(fade(b, g("Fade")), g("Grain"), g("Size"))
            "retro" -> grain(fade(leak(b, g("Light Leak")), g("Fade")), g("Grain"))
            "noir" -> vignette(grain(fade(bw(b, 1, 0f, 30f), g("Fade")), g("Grain")), -g("Vignette"), 50f)
            "bw" -> grain(bw(b, g("Filter").roundToInt(), g("Brightness"), g("Contrast")), g("Grain"))
            "blur" -> lensBlur(b, g("Blur"), g("Transition"))
            "vignette" -> vignettePro(b, g("Amount"), g("Midpoint"), g("Roundness"), g("Feather"), g("Highlights"))
            "frames" -> framesPro(b, g("Size"), g("Color").roundToInt(), g("Radius"), g("Shadow"))
            "neon" -> neonOp(b, g("Strength"), g("Hue"), g("Glow"))
            "sketch" -> sketchOp(b, g("Pencil"), g("Contrast"), g("Color"))
            "popart" -> popArtOp(b, g("Style").roundToInt(), g("Levels").roundToInt(), g("Strength"))
            "drip" -> bloomDripOp(b, g("Bloom"), g("Hue"), g("Drip"))
            "flare" -> flareOp(b, g("X"), g("Y"), g("Strength"), g("Hue"), g("Streak"), g("Rays"))
            "leak" -> leakProOp(b, g("Style").roundToInt(), g("Strength"), g("Hue"))
            else -> b
        }
    }

    // ---------- helpers ----------
    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    private fun lum(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
    private fun ch(p: Int, s: Int) = (p shr s) and 255
    private fun cl(v: Float) = v.coerceIn(0f, 255f).toInt()
    private fun mix(a: Int, c: Int, s: Int, t: Float) = cl(ch(a, s) * (1f - t) + ch(c, s) * t)
    private fun pxOf(b: Bitmap): IntArray {
        val p = IntArray(b.width * b.height)
        b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
        return p
    }
    private fun bmpOf(p: IntArray, w: Int, h: Int): Bitmap {
        val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        o.setPixels(p, 0, w, 0, 0, w, h)
        return o
    }

    private inline fun map(b: Bitmap, f: (Int, Int, FloatArray) -> Unit): Bitmap {
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val c = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = p[i]
            c[0] = ch(v, 16) / 255f
            c[1] = ch(v, 8) / 255f
            c[2] = ch(v, 0) / 255f
            f(x, y, c)
            p[i] = (0xFF shl 24) or (to8(c[0]) shl 16) or (to8(c[1]) shl 8) or to8(c[2])
        }
        return bmpOf(p, w, h)
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r < 1) return src
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += ch(p, 16); sg += ch(p, 8); sb += ch(p, 0)
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val d = src[row + (x - r).coerceAtLeast(0)]
                sr += ch(a, 16) - ch(d, 16); sg += ch(a, 8) - ch(d, 8); sb += ch(a, 0) - ch(d, 0)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                sr += ch(p, 16); sg += ch(p, 8); sb += ch(p, 0)
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val d = tmp[(y - r).coerceAtLeast(0) * w + x]
                sr += ch(a, 16) - ch(d, 16); sg += ch(a, 8) - ch(d, 8); sb += ch(a, 0) - ch(d, 0)
            }
        }
        return out
    }

    private fun blurPx(p: IntArray, w: Int, h: Int, r: Int) = boxBlur(boxBlur(p, w, h, r), w, h, r)

    // ---------- tools ----------
    private fun tune(b: Bitmap, br: Float, co: Float, sa: Float, am: Float, hi: Float, sh: Float, wa: Float): Bitmap {
        if (br == 0f && co == 0f && sa == 0f && am == 0f && hi == 0f && sh == 0f && wa == 0f) return b
        val cf = 1f + co / 100f
        return map(b) { _, _, c ->
            for (k in 0..2) {
                val v = c[k] + br / 100f * 0.3f
                c[k] = (v - 0.5f) * cf + 0.5f
            }
            val l = lum(c).coerceIn(0f, 1f)
            val add = sh / 100f * 0.3f * (1f - l) * (1f - l) + hi / 100f * 0.3f * l * l + am / 100f * 0.15f * (1f - l) * (1f - l)
            c[0] += add; c[1] += add; c[2] += add
            val s = 1f + sa / 100f + am / 200f
            val l2 = lum(c)
            for (k in 0..2) c[k] = l2 + (c[k] - l2) * s
            c[0] += wa / 100f * 0.1f
            c[2] -= wa / 100f * 0.1f
        }
    }

    private fun unsharp(b: Bitmap, radius: Int, amount: Float): Bitmap {
        if (amount == 0f) return b
        val w = b.width; val h = b.height
        val p = pxOf(b)
        val bl = blurPx(p, w, h, radius)
        val o = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]; val c = bl[i]
            o[i] = (0xFF shl 24) or
                (cl(ch(a, 16) + amount * (ch(a, 16) - ch(c, 16))) shl 16) or
                (cl(ch(a, 8) + amount * (ch(a, 8) - ch(c, 8))) shl 8) or
                cl(ch(a, 0) + amount * (ch(a, 0) - ch(c, 0)))
        }
        return bmpOf(o, w, h)
    }

    private fun tonal(b: Bitmap, hi: Float, mid: Float, lo: Float): Bitmap {
        val w = b.width; val h = b.height
        val p = pxOf(b)
        val bl = blurPx(p, w, h, max(6, min(w, h) / 40))
        val o = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]; val c = bl[i]
            val l = (0.299f * ch(a, 16) + 0.587f * ch(a, 8) + 0.114f * ch(a, 0)) / 255f
            val k = ((l * l * hi + 4f * l * (1f - l) * mid + (1f - l) * (1f - l) * lo) / 100f) * 2.5f
            o[i] = (0xFF shl 24) or
                (cl(ch(a, 16) + k * (ch(a, 16) - ch(c, 16))) shl 16) or
                (cl(ch(a, 8) + k * (ch(a, 8) - ch(c, 8))) shl 8) or
                cl(ch(a, 0) + k * (ch(a, 0) - ch(c, 0)))
        }
        return bmpOf(o, w, h)
    }

    private fun screenCh(a: Int, c: Int, s: Int, k: Float): Int {
        val x = ch(a, s) / 255f
        val y = ch(c, s) / 255f
        val sc = 1f - (1f - x) * (1f - y)
        return cl((x + (sc - x) * k) * 255f)
    }

    private fun glow(b: Bitmap, g: Float, sat: Float, warm: Float): Bitmap {
        val w = b.width; val h = b.height
        val p = pxOf(b)
        val bl = blurPx(p, w, h, max(6, min(w, h) / 25))
        val k = g / 100f * 0.8f
        val o = IntArray(w * h)
        for (i in p.indices) {
            o[i] = (0xFF shl 24) or (screenCh(p[i], bl[i], 16, k) shl 16) or (screenCh(p[i], bl[i], 8, k) shl 8) or screenCh(p[i], bl[i], 0, k)
        }
        return tune(bmpOf(o, w, h), 0f, 0f, sat, 0f, 0f, 0f, warm)
    }

    private fun lensBlur(b: Bitmap, blur: Float, trans: Float): Bitmap {
        if (blur <= 0f) return b
        val w = b.width; val h = b.height
        val p = pxOf(b)
        val r = max(1, (blur / 100f * min(w, h) / 25f).toInt())
        val bl = blurPx(p, w, h, r)
        val inner = 0.1f + 0.7f * trans / 100f
        val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - w / 2f) / (w / 2f)
            val dy = (y - h / 2f) / (h / 2f)
            val t = smooth((sqrt(dx * dx + dy * dy) / 1.4142f - inner) / (1f - inner))
            val i = y * w + x
            o[i] = (0xFF shl 24) or (mix(p[i], bl[i], 16, t) shl 16) or (mix(p[i], bl[i], 8, t) shl 8) or mix(p[i], bl[i], 0, t)
        }
        return bmpOf(o, w, h)
    }

    private fun wb(b: Bitmap, t: Float, ti: Float): Bitmap {
        if (t == 0f && ti == 0f) return b
        return map(b) { _, _, c ->
            c[0] += t / 100f * 0.12f
            c[2] -= t / 100f * 0.12f
            c[1] -= ti / 100f * 0.08f
        }
    }

    private fun vintage(b: Bitmap, s: Float): Bitmap {
        if (s <= 0f) return b
        return map(b) { _, _, c ->
            val l = lum(c)
            for (k in 0..2) c[k] = l + (c[k] - l) * (1f - 0.25f * s)
            c[0] = c[0] * (1f + 0.1f * s) + 0.04f * s
            c[1] = c[1] + 0.02f * s
            c[2] = c[2] * (1f - 0.2f * s) + 0.06f * s
            for (k in 0..2) c[k] = c[k] * (1f - 0.1f * s) + 0.05f * s
        }
    }

    private fun fade(b: Bitmap, f: Float): Bitmap {
        if (f <= 0f) return b
        val k = f / 100f
        return map(b) { _, _, c -> for (i in 0..2) c[i] = c[i] * (1f - 0.3f * k) + 0.12f * k }
    }

    private fun leak(b: Bitmap, a: Float): Bitmap {
        if (a <= 0f) return b
        val w = b.width
        val k = a / 100f
        return map(b) { x, _, c ->
            val t = (1f - x / (w * 0.7f)).coerceAtLeast(0f)
            val v = t * t * k
            c[0] += v * 0.55f
            c[1] += v * 0.22f
            c[2] -= v * 0.05f
        }
    }

    private fun grain(b: Bitmap, a: Float): Bitmap {
        if (a <= 0f) return b
        val rnd = Random(7)
        val k = a / 100f * 0.35f
        return map(b) { _, _, c ->
            val n = (rnd.nextFloat() - 0.5f) * k
            c[0] += n; c[1] += n; c[2] += n
        }
    }

    private val bwW = arrayOf(
        floatArrayOf(.299f, .587f, .114f), floatArrayOf(.299f, .587f, .114f),
        floatArrayOf(.299f, .587f, .114f), floatArrayOf(.299f, .587f, .114f),
        floatArrayOf(.8f, .15f, .05f), floatArrayOf(.5f, .5f, 0f),
        floatArrayOf(.1f, .8f, .1f), floatArrayOf(.05f, .15f, .8f)
    )

    private fun bw(b: Bitmap, f: Int, br: Float, co: Float): Bitmap {
        val wt = bwW[f.coerceIn(0, 7)]
        val extraB = when (f) { 2 -> 0.12f; 3 -> -0.12f; else -> 0f }
        val cf = 1f + co / 100f + (if (f == 1) 0.3f else 0f)
        return map(b) { _, _, c ->
            var v = c[0] * wt[0] + c[1] * wt[1] + c[2] * wt[2]
            v += br / 100f * 0.3f + extraB
            v = (v - 0.5f) * cf + 0.5f
            c[0] = v; c[1] = v; c[2] = v
        }
    }

    private fun vignette(b: Bitmap, ob: Float, size: Float): Bitmap {
        if (ob == 0f) return b
        val w = b.width; val h = b.height
        val inner = 0.15f + 0.6f * size / 100f
        val k = ob / 100f
        return map(b) { x, y, c ->
            val dx = (x - w / 2f) / (w / 2f)
            val dy = (y - h / 2f) / (h / 2f)
            val t = smooth((sqrt(dx * dx + dy * dy) / 1.4142f - inner) / (1f - inner))
            if (k < 0f) {
                val f = 1f + k * t
                c[0] *= f; c[1] *= f; c[2] *= f
            } else {
                val a = k * t
                for (i in 0..2) c[i] += a * (1f - c[i])
            }
        }
    }

    private fun crop(b: Bitmap, idx: Int): Bitmap {
        val base = when (idx) { 1 -> 1f; 2 -> 4f / 3f; 3 -> 16f / 9f; 4 -> 3f / 2f; 5 -> 5f / 4f; else -> return b }
        val r = if (b.height > b.width && base != 1f) 1f / base else base
        var nw = b.width.toFloat()
        var nh = nw / r
        if (nh > b.height) { nh = b.height.toFloat(); nw = nh * r }
        val x = ((b.width - nw) / 2f).roundToInt()
        val y = ((b.height - nh) / 2f).roundToInt()
        return Bitmap.createBitmap(b, x, y, nw.roundToInt().coerceAtMost(b.width - x), nh.roundToInt().coerceAtMost(b.height - y))
    }

    private fun rotate(b: Bitmap, steps: Int, flip: Boolean): Bitmap {
        if (steps == 0 && !flip) return b
        val m = Matrix()
        if (flip) m.postScale(-1f, 1f, b.width / 2f, b.height / 2f)
        m.postRotate(steps * 90f)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun frames(b: Bitmap, size: Float, black: Boolean): Bitmap {
        if (size <= 0f) return b
        val o = b.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(o)
        val t = min(o.width, o.height) * size / 100f * 0.4f
        val p = Paint()
        p.color = if (black) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val w = o.width.toFloat(); val h = o.height.toFloat()
        cv.drawRect(0f, 0f, w, t, p); cv.drawRect(0f, h - t, w, h, p)
        cv.drawRect(0f, 0f, t, h, p); cv.drawRect(w - t, 0f, w, h, p)
        return o
    }

    // ---------- v2 tools ----------
    private fun cropRect(b: Bitmap, l: Float, t: Float, r: Float, bt: Float): Bitmap {
        val x = (l * b.width).roundToInt().coerceIn(0, b.width - 1)
        val y = (t * b.height).roundToInt().coerceIn(0, b.height - 1)
        val w = ((r - l) * b.width).roundToInt().coerceIn(1, b.width - x)
        val h = ((bt - t) * b.height).roundToInt().coerceIn(1, b.height - y)
        return if (x == 0 && y == 0 && w == b.width && h == b.height) b else Bitmap.createBitmap(b, x, y, w, h)
    }

    private fun perspective(b: Bitmap, v: Float, hz: Float, rot: Float, sc: Float): Bitmap {
        if (v == 0f && hz == 0f && rot == 0f && sc == 0f) return b
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val iv = abs(v) / 100f * 0.2f * w
        val ih = abs(hz) / 100f * 0.2f * h
        val d = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        if (v > 0f) { d[0] += iv; d[2] -= iv } else if (v < 0f) { d[4] -= iv; d[6] += iv }
        if (hz > 0f) { d[1] += ih; d[7] -= ih } else if (hz < 0f) { d[3] += ih; d[5] -= ih }
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, d, 0, 4)
        m.postRotate(rot / 100f * 30f, w / 2f, h / 2f)
        val zoom = (1f + abs(v) / 100f * 0.25f + abs(hz) / 100f * 0.25f + abs(rot) / 100f * 0.5f) * (1f + sc / 100f * 0.5f)
        m.postScale(zoom, zoom, w / 2f, h / 2f)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun curves(b: Bitmap, sh: Float, da: Float, li: Float, hl: Float): Bitmap {
        if (sh == 0f && da == 0f && li == 0f && hl == 0f) return b
        val xs = floatArrayOf(0f, .15f, .35f, .65f, .85f, 1f)
        val ys = floatArrayOf(0f, .15f + sh / 500f, .35f + da / 500f, .65f + li / 500f, .85f + hl / 500f, 1f)
        val lut = IntArray(256)
        for (i in 0..255) {
            val x = i / 255f
            var k = 0
            while (k < 4 && x > xs[k + 1]) k++
            val t = (x - xs[k]) / (xs[k + 1] - xs[k])
            val p0 = ys[max(0, k - 1)]
            val p1 = ys[k]
            val p2 = ys[k + 1]
            val p3 = ys[min(5, k + 2)]
            val y = 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
            lut[i] = cl(y * 255f)
        }
        val p = pxOf(b)
        for (i in p.indices) {
            val v = p[i]
            p[i] = (0xFF shl 24) or (lut[ch(v, 16)] shl 16) or (lut[ch(v, 8)] shl 8) or lut[ch(v, 0)]
        }
        return bmpOf(p, b.width, b.height)
    }

    private fun brush(b: Bitmap, strokes: List<BrushStroke>, effect: Int, s: Float): Bitmap {
        if (strokes.isEmpty()) return b
        val w = b.width
        val h = b.height
        val m = min(w, h).toFloat()
        val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(mb)
        for (st in strokes) {
            val rad = max(1f, st.r * m)
            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND
            line.strokeJoin = Paint.Join.ROUND
            line.strokeWidth = rad * 2f
            line.color = 0xFFFFFFFF.toInt()
            line.maskFilter = BlurMaskFilter(max(1f, rad * 0.35f), BlurMaskFilter.Blur.NORMAL)
            if (st.erase) line.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            if (st.pts.size < 4) {
                val fill = Paint(line)
                fill.style = Paint.Style.FILL
                cv.drawCircle(st.pts[0] * w, st.pts[1] * h, rad, fill)
            } else {
                val path = Path()
                path.moveTo(st.pts[0] * w, st.pts[1] * h)
                var i = 2
                while (i + 1 < st.pts.size) {
                    path.lineTo(st.pts[i] * w, st.pts[i + 1] * h)
                    i += 2
                }
                cv.drawPath(path, line)
            }
        }
        val fx = when (effect) {
            0 -> tune(b, s * 0.5f, 0f, 0f, 0f, 0f, s * 0.5f, 0f)
            1 -> tune(b, s, 0f, 0f, 0f, 0f, 0f, 0f)
            2 -> tune(b, 0f, 0f, 0f, 0f, 0f, 0f, s)
            else -> tune(b, 0f, 0f, s, 0f, 0f, 0f, 0f)
        }
        val mp = pxOf(mb)
        val orig = pxOf(b)
        val fp = pxOf(fx)
        val o = IntArray(w * h)
        for (i in o.indices) {
            val t = ch(mp[i], 24) / 255f
            o[i] = (0xFF shl 24) or (mix(orig[i], fp[i], 16, t) shl 16) or (mix(orig[i], fp[i], 8, t) shl 8) or mix(orig[i], fp[i], 0, t)
        }
        return bmpOf(o, w, h)
    }

    private fun selective(b: Bitmap, pts: List<Pt>): Bitmap {
        if (pts.isEmpty()) return b
        val w = b.width
        val h = b.height
        val m = min(w, h)
        val p = pxOf(b)
        for (pt in pts) {
            val cx = pt.x * w
            val cy = pt.y * h
            val rad = max(2f, pt.r * m)
            val x0 = max(0, (cx - rad).toInt())
            val x1 = min(w - 1, (cx + rad).toInt())
            val y0 = max(0, (cy - rad).toInt())
            val y1 = min(h - 1, (cy + rad).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x - cx, y - cy) / rad
                if (d >= 1f) continue
                val k = smooth(1f - d)
                val i = y * w + x
                val v = p[i]
                var r = ch(v, 16) / 255f
                var g = ch(v, 8) / 255f
                var bl = ch(v, 0) / 255f
                val add = pt.br / 100f * 0.3f * k
                val cf = 1f + pt.co / 100f * k
                r = (r + add - 0.5f) * cf + 0.5f
                g = (g + add - 0.5f) * cf + 0.5f
                bl = (bl + add - 0.5f) * cf + 0.5f
                val l = 0.299f * r + 0.587f * g + 0.114f * bl
                val sa = 1f + pt.sa / 100f * k
                r = l + (r - l) * sa
                g = l + (g - l) * sa
                bl = l + (bl - l) * sa
                p[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(bl)
            }
        }
        return bmpOf(p, w, h)
    }

    private fun px(p: IntArray, w: Int, h: Int, x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    private fun heal(b: Bitmap, spots: List<Spot>): Bitmap {
        if (spots.isEmpty()) return b
        val w = b.width
        val h = b.height
        val m = min(w, h)
        val p = pxOf(b)
        val n = 16
        for (s in spots) {
            val cx = s.x * w
            val cy = s.y * h
            val r = max(3f, s.r * m)
            var bestErr = Float.MAX_VALUE
            var bdx = 0f
            var bdy = 0f
            var found = false
            for (k in 0 until 8) {
                val ang = k * PI / 4
                val ox = (cos(ang) * r * 2.4).toFloat()
                val oy = (sin(ang) * r * 2.4).toFloat()
                if (cx + ox - r * 1.4f < 0f || cy + oy - r * 1.4f < 0f || cx + ox + r * 1.4f >= w || cy + oy + r * 1.4f >= h) continue
                var err = 0f
                for (j in 0 until n) {
                    val a2 = j * 2 * PI / n
                    val rx = (cos(a2) * r * 1.25).toFloat()
                    val ry = (sin(a2) * r * 1.25).toFloat()
                    val t = px(p, w, h, (cx + rx).toInt(), (cy + ry).toInt())
                    val q = px(p, w, h, (cx + ox + rx).toInt(), (cy + oy + ry).toInt())
                    err += (abs(ch(t, 16) - ch(q, 16)) + abs(ch(t, 8) - ch(q, 8)) + abs(ch(t, 0) - ch(q, 0))).toFloat()
                }
                if (err < bestErr) { bestErr = err; bdx = ox; bdy = oy; found = true }
            }
            if (!found) continue
            var dr = 0f
            var dg = 0f
            var db = 0f
            for (j in 0 until n) {
                val a2 = j * 2 * PI / n
                val rx = (cos(a2) * r * 1.25).toFloat()
                val ry = (sin(a2) * r * 1.25).toFloat()
                val t = px(p, w, h, (cx + rx).toInt(), (cy + ry).toInt())
                val q = px(p, w, h, (cx + bdx + rx).toInt(), (cy + bdy + ry).toInt())
                dr += (ch(t, 16) - ch(q, 16)).toFloat()
                dg += (ch(t, 8) - ch(q, 8)).toFloat()
                db += (ch(t, 0) - ch(q, 0)).toFloat()
            }
            dr /= n; dg /= n; db /= n
            val x0 = max(0, (cx - r).toInt())
            val x1 = min(w - 1, (cx + r).toInt())
            val y0 = max(0, (cy - r).toInt())
            val y1 = min(h - 1, (cy + r).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x - cx, y - cy) / r
                if (d >= 1f) continue
                val a = smooth((1f - d) / 0.4f)
                val q = px(p, w, h, (x + bdx).roundToInt(), (y + bdy).roundToInt())
                val i = y * w + x
                val t = p[i]
                p[i] = (0xFF shl 24) or
                    (cl((ch(q, 16) + dr) * a + ch(t, 16) * (1f - a)) shl 16) or
                    (cl((ch(q, 8) + dg) * a + ch(t, 8) * (1f - a)) shl 8) or
                    cl((ch(q, 0) + db) * a + ch(t, 0) * (1f - a))
            }
        }
        return bmpOf(p, w, h)
    }

    private val textColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFEB3B.toInt(),
        0xFFF44336.toInt(), 0xFF2196F3.toInt(), 0xFF4CAF50.toInt()
    )

    private fun textOp(b: Bitmap, o: Op): Bitmap {
        if (o.text.isBlank()) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = min(out.width, out.height) * (o.v["Size"] ?: 30f) / 100f * 0.25f
        p.color = textColors[(o.v["Color"] ?: 0f).roundToInt().coerceIn(0, 5)]
        p.alpha = ((o.v["Opacity"] ?: 100f) / 100f * 255f).toInt().coerceIn(0, 255)
        val x = out.width * (o.v["X"] ?: 50f) / 100f
        var y = out.height * (o.v["Y"] ?: 50f) / 100f
        val lh = p.fontSpacing
        for (line in o.text.split("\n")) {
            cv.drawText(line, x, y, p)
            y += lh
        }
        return out
    }

    // ---------- v3 pro tools ----------
    private val bandHue = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 275f, 320f)
    private val identityCurve = listOf(0f, 0f, 1f, 1f)

    private fun hsvToRgb(h: Float, s: Float, v: Float, c: FloatArray) {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        when (i % 6) {
            0 -> { c[0] = v; c[1] = t; c[2] = p }
            1 -> { c[0] = q; c[1] = v; c[2] = p }
            2 -> { c[0] = p; c[1] = v; c[2] = t }
            3 -> { c[0] = p; c[1] = q; c[2] = v }
            4 -> { c[0] = t; c[1] = p; c[2] = v }
            else -> { c[0] = v; c[1] = p; c[2] = q }
        }
    }

    private fun hslMix(b: Bitmap, hs: FloatArray, ss: FloatArray, ls: FloatArray): Bitmap {
        if (hs.all { it == 0f } && ss.all { it == 0f } && ls.all { it == 0f }) return b
        return map(b) { _, _, c ->
            val r = c[0]
            val g = c[1]
            val bl = c[2]
            val mx = max(r, max(g, bl))
            val mn = min(r, min(g, bl))
            val d = mx - mn
            if (d > 0.002f) {
                var h = (when {
                    mx == r -> ((g - bl) / d) % 6f
                    mx == g -> (bl - r) / d + 2f
                    else -> (r - g) / d + 4f
                }) * 60f
                if (h < 0f) h += 360f
                var s = d / mx
                var v = mx
                var dh = 0f
                var ds = 0f
                var dl = 0f
                for (i in 0..7) {
                    var ad = abs(h - bandHue[i])
                    if (ad > 180f) ad = 360f - ad
                    val w = (1f - ad / 50f).coerceAtLeast(0f)
                    if (w > 0f) { dh += w * hs[i]; ds += w * ss[i]; dl += w * ls[i] }
                }
                val sw = min(1f, s * 4f)
                h += dh / 100f * 30f * sw
                s = (s * (1f + ds / 100f * sw)).coerceIn(0f, 1f)
                v = (v * (1f + dl / 100f * 0.6f * sw)).coerceIn(0f, 1f)
                hsvToRgb(h, s, v, c)
            }
        }
    }

    private fun tintVec(h: Float): FloatArray {
        val c = FloatArray(3)
        hsvToRgb(h, 1f, 1f, c)
        val l = lum(c)
        return floatArrayOf(c[0] - l, c[1] - l, c[2] - l)
    }

    private fun grade(b: Bitmap, sh: Float, ss: Float, mh: Float, ms: Float, hh: Float, hs: Float, bal: Float): Bitmap {
        if (ss == 0f && ms == 0f && hs == 0f) return b
        val ts = tintVec(sh)
        val tm = tintVec(mh)
        val th = tintVec(hh)
        val bs = bal / 100f * 0.3f
        return map(b) { _, _, c ->
            val l = (lum(c) - bs).coerceIn(0f, 1f)
            val kS = ss / 100f * 0.4f * (1f - l) * (1f - l)
            val kM = ms / 100f * 0.4f * 4f * l * (1f - l)
            val kH = hs / 100f * 0.4f * l * l
            for (k in 0..2) c[k] += ts[k] * kS + tm[k] * kM + th[k] * kH
        }
    }

    private fun light(b: Bitmap, ex: Float, co: Float, hi: Float, sh: Float, wh: Float, bk: Float): Bitmap {
        if (ex == 0f && co == 0f && hi == 0f && sh == 0f && wh == 0f && bk == 0f) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val bl = blurPx(p, w, h, max(6, min(w, h) / 30))
        val gain = 2.0.pow((ex / 110f).toDouble()).toFloat()
        val blk = -bk / 100f * 0.2f
        val denom = max(0.1f, (1f - wh / 100f * 0.25f) - blk)
        val cf = 1f + co / 100f
        val o = IntArray(w * h)
        for (i in p.indices) {
            val v = p[i]
            val lm = (0.299f * ch(bl[i], 16) + 0.587f * ch(bl[i], 8) + 0.114f * ch(bl[i], 0)) / 255f
            val add = sh / 100f * 0.3f * (1f - lm) * (1f - lm) + hi / 100f * 0.3f * lm * lm
            val r = ((ch(v, 16) / 255f * gain - blk) / denom - 0.5f) * cf + 0.5f + add
            val g = ((ch(v, 8) / 255f * gain - blk) / denom - 0.5f) * cf + 0.5f + add
            val bb = ((ch(v, 0) / 255f * gain - blk) / denom - 0.5f) * cf + 0.5f + add
            o[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(bb)
        }
        return bmpOf(o, w, h)
    }

    private fun vibrance(b: Bitmap, vib: Float): Bitmap {
        if (vib == 0f) return b
        return map(b) { _, _, c ->
            val mx = max(c[0], max(c[1], c[2]))
            val mn = min(c[0], min(c[1], c[2]))
            val sat = if (mx > 0f) (mx - mn) / mx else 0f
            val f = 1f + vib / 100f * (1f - sat)
            val l = lum(c)
            for (k in 0..2) c[k] = l + (c[k] - l) * f
        }
    }

    private fun dehaze(b: Bitmap, amt: Float): Bitmap {
        if (amt == 0f) return b
        if (amt < 0f) {
            val k = -amt / 100f * 0.5f
            return map(b) { _, _, c -> for (i in 0..2) c[i] = c[i] * (1f - k) + 0.85f * k }
        }
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val dark = IntArray(w * h)
        for (i in p.indices) {
            val v = p[i]
            val m = min(ch(v, 16), min(ch(v, 8), ch(v, 0)))
            dark[i] = (0xFF shl 24) or (m shl 16) or (m shl 8) or m
        }
        val db = blurPx(dark, w, h, max(4, min(w, h) / 40))
        var a = 0
        for (i in db.indices step 7) a = max(a, ch(db[i], 0))
        val air = (a / 255f).coerceIn(0.6f, 0.98f)
        val wgt = amt / 100f * 0.85f
        val o = IntArray(w * h)
        for (i in p.indices) {
            val t = max(0.25f, 1f - wgt * (ch(db[i], 0) / 255f) / air)
            val v = p[i]
            o[i] = (0xFF shl 24) or
                (to8((ch(v, 16) / 255f - air) / t + air) shl 16) or
                (to8((ch(v, 8) / 255f - air) / t + air) shl 8) or
                to8((ch(v, 0) / 255f - air) / t + air)
        }
        return bmpOf(o, w, h)
    }

    private fun denoise(b: Bitmap, la: Float, ca: Float, detail: Float): Bitmap {
        if (la <= 0f && ca <= 0f) return b
        val w = b.width
        val h = b.height
        val m = min(w, h)
        val p = pxOf(b)
        val bl = blurPx(p, w, h, max(1, (m / 400f * (1f + la / 40f)).roundToInt()))
        val bc = if (ca > 0f) blurPx(p, w, h, max(2, m / 120)) else bl
        val thr = 8f + (100f - detail) / 100f * 40f
        val o = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]
            val s = bl[i]
            val la0 = 0.299f * ch(a, 16) + 0.587f * ch(a, 8) + 0.114f * ch(a, 0)
            val ls0 = 0.299f * ch(s, 16) + 0.587f * ch(s, 8) + 0.114f * ch(s, 0)
            val k = la / 100f * (1f - smooth(abs(la0 - ls0) / thr))
            var r = ch(a, 16) + (ch(s, 16) - ch(a, 16)) * k
            var g = ch(a, 8) + (ch(s, 8) - ch(a, 8)) * k
            var bb = ch(a, 0) + (ch(s, 0) - ch(a, 0)) * k
            if (ca > 0f) {
                val c2 = bc[i]
                val y = 0.299f * r + 0.587f * g + 0.114f * bb
                val yc = 0.299f * ch(c2, 16) + 0.587f * ch(c2, 8) + 0.114f * ch(c2, 0)
                val kc = ca / 100f
                r += ((ch(c2, 16) - yc) - (r - y)) * kc
                g += ((ch(c2, 8) - yc) - (g - y)) * kc
                bb += ((ch(c2, 0) - yc) - (bb - y)) * kc
            }
            o[i] = (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(bb)
        }
        return bmpOf(o, w, h)
    }

    private fun gradOp(b: Bitmap, o: Op): Bitmap {
        fun g(n: String) = o.v[n] ?: 0f
        val fx = tune(b, g("Exposure"), g("Contrast"), g("Saturation"), 0f, g("Highlights"), g("Shadows"), g("Warmth"))
        if (fx === b) return b
        val w = b.width
        val h = b.height
        val ax = g("X0") * w
        val ay = g("Y0") * h
        val dx = g("X1") * w - ax
        val dy = g("Y1") * h - ay
        val len2 = max(1f, dx * dx + dy * dy)
        val p = pxOf(b)
        val fp = pxOf(fx)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = ((x - ax) * dx + (y - ay) * dy) / len2
            val wt = 1f - smooth(t)
            val i = y * w + x
            out[i] = (0xFF shl 24) or (mix(p[i], fp[i], 16, wt) shl 16) or (mix(p[i], fp[i], 8, wt) shl 8) or mix(p[i], fp[i], 0, wt)
        }
        return bmpOf(out, w, h)
    }

    /** Monotone cubic curve through flat [x0,y0,x1,y1,...] points -> 256-entry LUT. */
    fun curveLut(pts: List<Float>): IntArray {
        val n = pts.size / 2
        val lut = IntArray(256)
        if (n < 2) { for (i in 0..255) lut[i] = i; return lut }
        val xs = FloatArray(n) { pts[2 * it] }
        val ys = FloatArray(n) { pts[2 * it + 1] }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-4f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f }
            else {
                val a = m[i] / d[i]
                val bb = m[i + 1] / d[i]
                val s2 = a * a + bb * bb
                if (s2 > 9f) {
                    val t = 3f / sqrt(s2)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * bb * d[i]
                }
            }
        }
        var k = 0
        for (i in 0..255) {
            val x = i / 255f
            while (k < n - 2 && x > xs[k + 1]) k++
            val h = max(1e-4f, xs[k + 1] - xs[k])
            val t = ((x - xs[k]) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val y = (2f * t3 - 3f * t2 + 1f) * ys[k] + (t3 - 2f * t2 + t) * h * m[k] +
                (-2f * t3 + 3f * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
            lut[i] = cl(y * 255f)
        }
        return lut
    }

    private fun curveOp(b: Bitmap, cv: List<List<Float>>): Bitmap {
        if (cv.size < 4 || cv.all { it == identityCurve }) return b
        val lm = curveLut(cv[0])
        val lr = curveLut(cv[1])
        val lg = curveLut(cv[2])
        val lb = curveLut(cv[3])
        val p = pxOf(b)
        for (i in p.indices) {
            val v = p[i]
            p[i] = (0xFF shl 24) or (lr[lm[ch(v, 16)]] shl 16) or (lg[lm[ch(v, 8)]] shl 8) or lb[lm[ch(v, 0)]]
        }
        return bmpOf(p, b.width, b.height)
    }

    private fun bil(p: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xf = x.coerceIn(0f, w - 1f)
        val yf = y.coerceIn(0f, h - 1f)
        val x0 = xf.toInt()
        val y0 = yf.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = xf - x0
        val fy = yf - y0
        val a = p[y0 * w + x0]
        val b = p[y0 * w + x1]
        val c = p[y1 * w + x0]
        val d = p[y1 * w + x1]
        val r = (ch(a, 16) * (1f - fx) + ch(b, 16) * fx) * (1f - fy) + (ch(c, 16) * (1f - fx) + ch(d, 16) * fx) * fy
        val g = (ch(a, 8) * (1f - fx) + ch(b, 8) * fx) * (1f - fy) + (ch(c, 8) * (1f - fx) + ch(d, 8) * fx) * fy
        val bl = (ch(a, 0) * (1f - fx) + ch(b, 0) * fx) * (1f - fy) + (ch(c, 0) * (1f - fx) + ch(d, 0) * fx) * fy
        return (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(bl)
    }

    private fun liquify(b: Bitmap, ws: List<Warp>): Bitmap {
        if (ws.isEmpty()) return b
        val w = b.width
        val h = b.height
        val m = min(w, h)
        val cur = pxOf(b)
        val nxt = cur.copyOf()
        for (wp in ws) {
            val cx = wp.x * w
            val cy = wp.y * h
            val rad = max(3f, wp.r * m)
            val x0 = max(0, (cx - rad).toInt())
            val x1 = min(w - 1, (cx + rad).toInt())
            val y0 = max(0, (cy - rad).toInt())
            val y1 = min(h - 1, (cy + rad).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                val d = hypot(x - cx, y - cy) / rad
                if (d >= 1f) { nxt[i] = cur[i]; continue }
                val f = smooth(1f - d)
                val sx: Float
                val sy: Float
                when (wp.mode) {
                    0 -> { sx = x - wp.dx * w * f; sy = y - wp.dy * h * f }
                    1 -> { sx = cx + (x - cx) * (1f - wp.k * f); sy = cy + (y - cy) * (1f - wp.k * f) }
                    2 -> { sx = cx + (x - cx) * (1f + wp.k * f); sy = cy + (y - cy) * (1f + wp.k * f) }
                    3 -> {
                        val sf = (1f - d * d).coerceAtLeast(0f)
                        sx = x - wp.dx * w * sf * 1.6f
                        sy = y - wp.dy * h * sf * 1.6f
                    }
                    else -> {
                        val ang = wp.k * f * 4.5f
                        val cs = cos(ang)
                        val sn = sin(ang)
                        val rx = x - cx
                        val ry = y - cy
                        sx = cx + rx * cs - ry * sn
                        sy = cy + rx * sn + ry * cs
                    }
                }
                nxt[i] = bil(cur, w, h, sx, sy)
            }
            for (y in y0..y1) System.arraycopy(nxt, y * w + x0, cur, y * w + x0, x1 - x0 + 1)
        }
        return bmpOf(cur, w, h)
    }

    private fun straighten(b: Bitmap, deg: Float): Bitmap {
        if (abs(deg) < 0.05f) return b
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val th = Math.toRadians(deg.toDouble())
        val zoom = (abs(cos(th)) + abs(sin(th)) * max(w / h, h / w)).toFloat()
        val m = Matrix()
        m.postRotate(deg, w / 2f, h / 2f)
        m.postScale(zoom, zoom, w / 2f, h / 2f)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---------- v4 Lightroom extras ----------
    private fun sharpenPro(b: Bitmap, amt: Float, rad: Float, mask: Float): Bitmap {
        if (amt <= 0f) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val r = max(1, (rad / 100f * 3f * min(w, h) / 700f + 0.5f).toInt())
        val bl = blurPx(p, w, h, r)
        val k = amt / 100f * 2.5f
        val thr = mask / 100f * 18f + 0.001f
        val o = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]
            val c = bl[i]
            val dl = 0.299f * (ch(a, 16) - ch(c, 16)) + 0.587f * (ch(a, 8) - ch(c, 8)) + 0.114f * (ch(a, 0) - ch(c, 0))
            val wt = if (mask <= 0f) 1f else smooth(abs(dl) / thr)
            val kk = k * wt
            o[i] = (0xFF shl 24) or
                (cl(ch(a, 16) + kk * (ch(a, 16) - ch(c, 16))) shl 16) or
                (cl(ch(a, 8) + kk * (ch(a, 8) - ch(c, 8))) shl 8) or
                cl(ch(a, 0) + kk * (ch(a, 0) - ch(c, 0)))
        }
        return bmpOf(o, w, h)
    }

    private fun vignettePro(b: Bitmap, amt: Float, mid: Float, round: Float, feather: Float, hl: Float): Bitmap {
        if (amt == 0f) return b
        val w = b.width
        val h = b.height
        val cx = w / 2f
        val cy = h / 2f
        val inner = mid / 100f * 0.85f
        val den = max(0.05f, (1f - inner) * (0.25f + feather / 100f * 0.75f))
        val k = amt / 100f
        val diag = hypot(cx, cy)
        return map(b) { x, y, c ->
            val dx = (x - cx) / cx
            val dy = (y - cy) / cy
            val de = sqrt(dx * dx + dy * dy) / 1.4142f
            val d = if (round >= 0f) {
                val dc = hypot(x - cx, y - cy) / diag
                de + (dc - de) * round / 100f
            } else {
                val dp = (abs(dx).pow(6f) + abs(dy).pow(6f)).pow(1f / 6f) / 2f.pow(1f / 6f)
                de + (dp - de) * (-round) / 100f
            }
            val t = smooth((d - inner) / den)
            val lm = lum(c)
            val wt = if (k < 0f) t * (1f - hl / 100f * lm) else t
            if (k < 0f) {
                val f = 1f + k * wt
                c[0] *= f; c[1] *= f; c[2] *= f
            } else {
                val a = k * wt
                for (i in 0..2) c[i] += a * (1f - c[i])
            }
        }
    }

    private fun hash2(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f - 0.5f
    }

    private fun grainPro(b: Bitmap, amt: Float, size: Float): Bitmap {
        if (amt <= 0f) return b
        val cell = max(1, (size / 100f * min(b.width, b.height) / 150f).roundToInt())
        val k = amt / 100f * 0.5f
        return map(b) { x, y, c ->
            val n = (hash2(x / cell, y / cell) + hash2(x, y) * 0.35f) * k
            val l = lum(c)
            val v = n * (0.6f + 0.8f * 4f * l * (1f - l))
            c[0] += v; c[1] += v; c[2] += v
        }
    }

    private fun radialOp(b: Bitmap, o: Op): Bitmap {
        fun g(n: String) = o.v[n] ?: 0f
        val fx = tune(b, g("Exposure"), g("Contrast"), g("Saturation"), 0f, g("Highlights"), g("Shadows"), g("Warmth"))
        if (fx === b) return b
        val w = b.width
        val h = b.height
        val cx = g("CX") * w
        val cy = g("CY") * h
        val rx = max(2f, g("RX") * w)
        val ry = max(2f, g("RY") * h)
        val inner = (1f - g("Feather") / 100f) * 0.98f
        val inv = g("Invert") > 0.5f
        val p = pxOf(b)
        val fp = pxOf(fx)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val ex = (x - cx) / rx
            val ey = (y - cy) / ry
            val d = sqrt(ex * ex + ey * ey)
            var wt = 1f - smooth((d - inner) / (1f - inner + 0.001f))
            if (inv) wt = 1f - wt
            val i = y * w + x
            out[i] = (0xFF shl 24) or (mix(p[i], fp[i], 16, wt) shl 16) or (mix(p[i], fp[i], 8, wt) shl 8) or mix(p[i], fp[i], 0, wt)
        }
        return bmpOf(out, w, h)
    }

    private fun rangeOp(b: Bitmap, o: Op): Bitmap {
        fun g(n: String) = o.v[n] ?: 0f
        val color = g("Mode") > 0.5f
        val low = g("Low")
        val high = g("High")
        val ft = g("Feather") / 100f * 20f + 0.5f
        val hue = g("Hue")
        val wdt = g("Width") / 100f * 90f + 1f
        val ftd = g("Feather") / 100f * 45f + 1f
        val show = g("Show") > 0.5f
        val fx = tune(b, g("Exposure"), g("Contrast"), g("Saturation"), 0f, 0f, 0f, g("Warmth"))
        if (!show && fx === b) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val fp = pxOf(fx)
        val out = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]
            val r = ch(a, 16) / 255f
            val gg = ch(a, 8) / 255f
            val bl = ch(a, 0) / 255f
            var m = 0f
            if (!color) {
                val l = (0.299f * r + 0.587f * gg + 0.114f * bl) * 100f
                m = smooth((l - low + ft) / (2f * ft)) * smooth((high + ft - l) / (2f * ft))
            } else {
                val mx = max(r, max(gg, bl))
                val mn = min(r, min(gg, bl))
                val d = mx - mn
                if (d >= 0.02f) {
                    var hh = (when {
                        mx == r -> ((gg - bl) / d) % 6f
                        mx == gg -> (bl - r) / d + 2f
                        else -> (r - gg) / d + 4f
                    }) * 60f
                    if (hh < 0f) hh += 360f
                    var ad = abs(hh - hue)
                    if (ad > 180f) ad = 360f - ad
                    m = (1f - smooth((ad - wdt) / ftd)) * smooth(d / mx * 5f)
                }
            }
            if (show) {
                val k = 0.75f * m
                out[i] = (0xFF shl 24) or (to8(r * (1f - k) + k) shl 16) or (to8(gg * (1f - k)) shl 8) or to8(bl * (1f - k))
            } else {
                out[i] = (0xFF shl 24) or (mix(a, fp[i], 16, m) shl 16) or (mix(a, fp[i], 8, m) shl 8) or mix(a, fp[i], 0, m)
            }
        }
        return bmpOf(out, w, h)
    }

    private fun redEye(b: Bitmap, spots: List<Spot>): Bitmap {
        if (spots.isEmpty()) return b
        val w = b.width
        val h = b.height
        val m = min(w, h)
        val p = pxOf(b)
        for (s in spots) {
            val cx = s.x * w
            val cy = s.y * h
            val rad = max(3f, s.r * m)
            val x0 = max(0, (cx - rad).toInt())
            val x1 = min(w - 1, (cx + rad).toInt())
            val y0 = max(0, (cy - rad).toInt())
            val y1 = min(h - 1, (cy + rad).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x - cx, y - cy) / rad
                if (d >= 1f) continue
                val i = y * w + x
                val v = p[i]
                val r0 = ch(v, 16) / 255f
                val g0 = ch(v, 8) / 255f
                val b0 = ch(v, 0) / 255f
                if (r0 > 0.3f && r0 > 1.4f * max(g0, b0)) {
                    val a = smooth((1f - d) / 0.3f)
                    val nr = r0 * (1f - a) + (g0 + b0) / 2f * 0.85f * a
                    p[i] = (0xFF shl 24) or (to8(nr) shl 16) or (to8(g0) shl 8) or to8(b0)
                }
            }
        }
        return bmpOf(p, w, h)
    }

    private fun optics(b: Bitmap, dist: Float, vig: Float, dp: Float, dg: Float): Bitmap {
        var cur = b
        if (dist != 0f) cur = distort(cur, dist)
        if (vig != 0f) cur = lensVig(cur, vig)
        if (dp > 0f || dg > 0f) cur = defringe(cur, dp, dg)
        return cur
    }

    private fun distort(b: Bitmap, d: Float): Bitmap {
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val cx = w / 2f
        val cy = h / 2f
        val k = d / 100f * 0.35f
        val zoom = 1f + abs(k)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / cx
            val dy = (y - cy) / cy
            val r2 = (dx * dx + dy * dy) / 2f
            val f = (1f + k * r2) / zoom
            out[y * w + x] = bil(p, w, h, cx + dx * cx * f, cy + dy * cy * f)
        }
        return bmpOf(out, w, h)
    }

    private fun lensVig(b: Bitmap, v: Float): Bitmap {
        val w = b.width
        val h = b.height
        val cx = w / 2f
        val cy = h / 2f
        return map(b) { x, y, c ->
            val dx = (x - cx) / cx
            val dy = (y - cy) / cy
            val f = 1f + v / 100f * 0.8f * (dx * dx + dy * dy) / 2f
            c[0] *= f; c[1] *= f; c[2] *= f
        }
    }

    private fun defringe(b: Bitmap, dp: Float, dg: Float): Bitmap {
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val bl = blurPx(p, w, h, max(2, min(w, h) / 400))
        val o = IntArray(w * h)
        for (i in p.indices) {
            val a = p[i]
            var r = ch(a, 16) / 255f
            var g = ch(a, 8) / 255f
            var bb = ch(a, 0) / 255f
            val mx = max(r, max(g, bb))
            val mn = min(r, min(g, bb))
            val d = mx - mn
            if (d > 0.04f) {
                var hh = (when {
                    mx == r -> ((g - bb) / d) % 6f
                    mx == g -> (bb - r) / d + 2f
                    else -> (r - g) / d + 4f
                }) * 60f
                if (hh < 0f) hh += 360f
                val wp = (1f - abs(hh - 290f) / 40f).coerceAtLeast(0f) * dp / 100f
                val wg = (1f - abs(hh - 120f) / 40f).coerceAtLeast(0f) * dg / 100f
                val amt = max(wp, wg)
                if (amt > 0f) {
                    val c = bl[i]
                    val ld = abs(0.299f * (ch(a, 16) - ch(c, 16)) + 0.587f * (ch(a, 8) - ch(c, 8)) + 0.114f * (ch(a, 0) - ch(c, 0)))
                    val f = 1f - amt * smooth(ld / 20f)
                    val l = 0.299f * r + 0.587f * g + 0.114f * bb
                    r = l + (r - l) * f
                    g = l + (g - l) * f
                    bb = l + (bb - l) * f
                }
            }
            o[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(bb)
        }
        return bmpOf(o, w, h)
    }

    // ---------- auto tools ----------
    fun avgColor(bm: Bitmap): FloatArray {
        val w = bm.width
        val h = bm.height
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val sx = max(1, w / 64)
        val sy = max(1, h / 64)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bm.getPixel(x, y)
                r += (p shr 16) and 255
                g += (p shr 8) and 255
                b += p and 255
                n++
                x += sx
            }
            y += sy
        }
        val d = 255f * max(1, n)
        return floatArrayOf(r / d, g / d, b / d)
    }

    /** Temperature/Tint that neutralise the given colour. */
    fun wbFrom(r: Float, g: Float, b: Float): FloatArray {
        val t = ((b - r) / 0.0024f).coerceIn(-100f, 100f)
        val ti = ((g - (r + b) / 2f) / 0.0008f).coerceIn(-100f, 100f)
        return floatArrayOf(t, ti)
    }

    /** Light-panel values [Exposure, Contrast, Highlights, Shadows, Whites, Blacks]. */
    fun autoLight(bm: Bitmap): FloatArray {
        val w = bm.width
        val h = bm.height
        val hist = IntArray(256)
        var n = 0
        val sx = max(1, w / 128)
        val sy = max(1, h / 128)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bm.getPixel(x, y)
                val l = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)).toInt().coerceIn(0, 255)
                hist[l]++
                n++
                x += sx
            }
            y += sy
        }
        fun pct(q: Float): Float {
            val target = n * q
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i / 255f
            }
            return 1f
        }
        val p05 = pct(0.005f)
        val p5 = pct(0.05f)
        val med = pct(0.5f)
        val p995 = pct(0.995f)
        val ex = (110f * ln(0.45f / max(med, 0.04f)) / ln(2f)).coerceIn(-70f, 70f)
        val hi = if (p995 > 0.97f) -45f else -15f
        val sh = if (p5 < 0.1f) 40f else 15f
        val wh = ((0.96f - p995) * 220f).coerceIn(-20f, 40f)
        val bk = (-(p05 - 0.02f) * 400f).coerceIn(-50f, 20f)
        val co = if (p995 - p05 < 0.8f) 15f else 5f
        return floatArrayOf(ex, co, hi, sh, wh, bk)
    }

    // ---------- v5 PicsArt creative & magic tools ----------
    val drawColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFF2A85.toInt(), 0xFF00E5FF.toInt(), 0xFFFFEA00.toInt(),
        0xFFFF3D00.toInt(), 0xFF00E676.toInt(), 0xFFB388FF.toInt(), 0xFF141414.toInt()
    )

    private val frameColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF141414.toInt(), 0xFFF7F2E7.toInt(),
        0xFF2B2D31.toInt(), 0xFFD4AF37.toInt(), 0xFF1F6FEB.toInt()
    )

    private fun framesPro(b: Bitmap, size: Float, colorIdx: Int, radius: Float, shadow: Float): Bitmap {
        if (size <= 0f && radius <= 0f && shadow <= 0f) return b
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val m = min(w, h)
        val pad = m * (size / 100f) * 0.18f
        val radPx = m * (radius / 100f) * 0.22f
        val shPx = m * (shadow / 100f) * 0.06f
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(frameColors[colorIdx.coerceIn(0, frameColors.lastIndex)])
        val dst = RectF(pad, pad, w - pad, h - pad)
        if (shPx > 0.5f) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                setShadowLayer(shPx, 0f, shPx * 0.35f, 0xAA000000.toInt())
            }
            cv.drawRoundRect(dst, radPx, radPx, sp)
        }
        val save = cv.save()
        if (radPx > 0.5f) {
            val clip = Path().apply { addRoundRect(dst, radPx, radPx, Path.Direction.CW) }
            cv.clipPath(clip)
        }
        cv.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        cv.restoreToCount(save)
        return out
    }

    private fun drawOp(b: Bitmap, strokes: List<BrushStroke>, opacity: Float): Bitmap {
        if (strokes.isEmpty() || opacity <= 0f) return b
        val w = b.width
        val h = b.height
        val m = min(w, h).toFloat()
        val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(mb)
        for (st in strokes) {
            val rad = max(1.5f, st.r * m)
            val path = Path()
            if (st.pts.size >= 4) {
                path.moveTo(st.pts[0] * w, st.pts[1] * h)
                var i = 2
                while (i + 1 < st.pts.size) {
                    path.lineTo(st.pts[i] * w, st.pts[i + 1] * h)
                    i += 2
                }
            }
            if (!st.erase && st.glow > 0f) {
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = if (st.pts.size < 4) Paint.Style.FILL else Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = rad * (1.8f + st.glow / 100f * 1.8f)
                    color = st.color
                    alpha = (st.glow * 1.9f).toInt().coerceIn(20, 220)
                    maskFilter = BlurMaskFilter(max(2f, rad * (0.4f + st.glow / 100f * 1.1f)), BlurMaskFilter.Blur.NORMAL)
                }
                if (st.pts.size < 4) cv.drawCircle(st.pts[0] * w, st.pts[1] * h, rad * 1.5f, gp)
                else cv.drawPath(path, gp)
            }
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = if (st.pts.size < 4) Paint.Style.FILL else Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = rad * 1.2f
                color = if (st.glow > 35f && !st.erase) {
                    val c = st.color
                    (0xFF shl 24) or (mix(c, -1, 16, 0.45f) shl 16) or (mix(c, -1, 8, 0.45f) shl 8) or mix(c, -1, 0, 0.45f)
                } else st.color
                if (st.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            if (st.pts.size < 4) cv.drawCircle(st.pts[0] * w, st.pts[1] * h, rad * 0.6f, lp)
            else cv.drawPath(path, lp)
        }
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (opacity / 100f * 255f).toInt().coerceIn(0, 255) }
        Canvas(out).drawBitmap(mb, 0f, 0f, p)
        return out
    }

    private fun neonOp(b: Bitmap, strength: Float, hue: Float, glowAmt: Float): Bitmap {
        if (strength <= 0f) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val lumArr = FloatArray(w * h) { i ->
            val v = p[i]
            (0.299f * ch(v, 16) + 0.587f * ch(v, 8) + 0.114f * ch(v, 0)) / 255f
        }
        val c1 = FloatArray(3)
        val c2 = FloatArray(3)
        hsvToRgb(hue, 0.95f, 1f, c1)
        hsvToRgb((hue + 135f) % 360f, 0.95f, 1f, c2)
        val edgePx = IntArray(w * h)
        val st = strength / 100f
        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = -lumArr[r0 + x - 1] + lumArr[r0 + x + 1] -
                    2f * lumArr[r1 + x - 1] + 2f * lumArr[r1 + x + 1] -
                    lumArr[r2 + x - 1] + lumArr[r2 + x + 1]
                val gy = -lumArr[r0 + x - 1] - 2f * lumArr[r0 + x] - lumArr[r0 + x + 1] +
                    lumArr[r2 + x - 1] + 2f * lumArr[r2 + x] + lumArr[r2 + x + 1]
                val mag = (sqrt(gx * gx + gy * gy) * 1.8f).coerceIn(0f, 1f)
                val e = smooth((mag - 0.08f) / 0.65f)
                val t = (x.toFloat() / w * 0.6f + y.toFloat() / h * 0.4f).coerceIn(0f, 1f)
                val nr = (c1[0] * (1f - t) + c2[0] * t) * e
                val ng = (c1[1] * (1f - t) + c2[1] * t) * e
                val nb = (c1[2] * (1f - t) + c2[2] * t) * e
                edgePx[r1 + x] = (0xFF shl 24) or (to8(nr) shl 16) or (to8(ng) shl 8) or to8(nb)
            }
        }
        val glowPx = if (glowAmt > 0f) blurPx(edgePx, w, h, max(3, (min(w, h) / 35f * (glowAmt / 100f)).roundToInt())) else edgePx
        val gk = glowAmt / 100f * 1.35f
        val dim = 1f - st * 0.35f
        val out = IntArray(w * h)
        for (i in p.indices) {
            val v = p[i]
            val e = edgePx[i]
            val gl = glowPx[i]
            val br = ch(v, 16) * dim / 255f
            val bg = ch(v, 8) * dim / 255f
            val bb = ch(v, 0) * dim / 255f
            val er = (ch(e, 16) + ch(gl, 16) * gk) / 255f * st
            val eg = (ch(e, 8) + ch(gl, 8) * gk) / 255f * st
            val eb = (ch(e, 0) + ch(gl, 0) * gk) / 255f * st
            val or = 1f - (1f - br) * (1f - er.coerceIn(0f, 1f))
            val og = 1f - (1f - bg) * (1f - eg.coerceIn(0f, 1f))
            val ob = 1f - (1f - bb) * (1f - eb.coerceIn(0f, 1f))
            out[i] = (0xFF shl 24) or (to8(or) shl 16) or (to8(og) shl 8) or to8(ob)
        }
        return bmpOf(out, w, h)
    }

    private fun sketchOp(b: Bitmap, pencil: Float, contrast: Float, colorMix: Float): Bitmap {
        if (pencil <= 0f) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val inv = IntArray(w * h)
        val gray = IntArray(w * h)
        for (i in p.indices) {
            val v = p[i]
            val l = (0.299f * ch(v, 16) + 0.587f * ch(v, 8) + 0.114f * ch(v, 0)).roundToInt().coerceIn(0, 255)
            gray[i] = l
            val iv = 255 - l
            inv[i] = (0xFF shl 24) or (iv shl 16) or (iv shl 8) or iv
        }
        val rad = max(2, (min(w, h) / 28f * (pencil / 100f)).roundToInt())
        val bl = blurPx(inv, w, h, rad)
        val cf = 1f + contrast / 100f * 0.8f
        val cm = colorMix / 100f
        val out = IntArray(w * h)
        for (i in p.indices) {
            val g = gray[i]
            val bv = ch(bl[i], 0)
            val dodge = min(255f, g * 255f / max(1, 255 - bv)) / 255f
            val sk = ((dodge - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
            val v = p[i]
            val r = sk * (1f - cm) + (ch(v, 16) / 255f * sk) * cm
            val gg = sk * (1f - cm) + (ch(v, 8) / 255f * sk) * cm
            val bb = sk * (1f - cm) + (ch(v, 0) / 255f * sk) * cm
            out[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(gg) shl 8) or to8(bb)
        }
        return bmpOf(out, w, h)
    }

    private fun popArtOp(b: Bitmap, style: Int, levels: Int, strength: Float): Bitmap {
        if (strength <= 0f) return b
        val lv = levels.coerceIn(2, 8).toFloat()
        val k = strength / 100f
        // Palettes: [shadowRGB, midRGB, highRGB]
        val pal = when (style.coerceIn(0, 3)) {
            0 -> arrayOf(floatArrayOf(0.08f, 0.05f, 0.28f), floatArrayOf(0.98f, 0.15f, 0.55f), floatArrayOf(0.05f, 0.95f, 0.98f))
            1 -> arrayOf(floatArrayOf(0.10f, 0.14f, 0.48f), floatArrayOf(0.92f, 0.18f, 0.18f), floatArrayOf(1.00f, 0.92f, 0.15f))
            2 -> arrayOf(floatArrayOf(0.18f, 0.04f, 0.32f), floatArrayOf(0.55f, 0.12f, 0.88f), floatArrayOf(0.55f, 1.00f, 0.12f))
            else -> arrayOf(floatArrayOf(0.05f, 0.25f, 0.32f), floatArrayOf(0.95f, 0.38f, 0.15f), floatArrayOf(1.00f, 0.85f, 0.35f))
        }
        return map(b) { _, _, c ->
            val l = lum(c).coerceIn(0f, 1f)
            val ql = (floor(l * lv) / (lv - 1f)).coerceIn(0f, 1f)
            val pr: Float
            val pg: Float
            val pb: Float
            if (ql < 0.5f) {
                val u = ql * 2f
                pr = pal[0][0] + (pal[1][0] - pal[0][0]) * u
                pg = pal[0][1] + (pal[1][1] - pal[0][1]) * u
                pb = pal[0][2] + (pal[1][2] - pal[0][2]) * u
            } else {
                val u = (ql - 0.5f) * 2f
                pr = pal[1][0] + (pal[2][0] - pal[1][0]) * u
                pg = pal[1][1] + (pal[2][1] - pal[1][1]) * u
                pb = pal[1][2] + (pal[2][2] - pal[1][2]) * u
            }
            c[0] = c[0] * (1f - k) + pr * k
            c[1] = c[1] * (1f - k) + pg * k
            c[2] = c[2] * (1f - k) + pb * k
        }
    }

    private fun bloomDripOp(b: Bitmap, bloom: Float, hue: Float, drip: Float): Bitmap {
        if (bloom <= 0f && drip <= 0f) return b
        val w = b.width
        val h = b.height
        val p = pxOf(b)
        val tint = FloatArray(3)
        hsvToRgb(hue, 0.85f, 1f, tint)
        val hi = IntArray(w * h)
        for (i in p.indices) {
            val v = p[i]
            val r = ch(v, 16) / 255f
            val g = ch(v, 8) / 255f
            val bb = ch(v, 0) / 255f
            val l = 0.299f * r + 0.587f * g + 0.114f * bb
            val m = smooth((l - 0.45f) / 0.45f)
            val hr = (r * 0.5f + tint[0] * 0.5f) * m
            val hg = (g * 0.5f + tint[1] * 0.5f) * m
            val hb = (bb * 0.5f + tint[2] * 0.5f) * m
            hi[i] = (0xFF shl 24) or (to8(hr) shl 16) or (to8(hg) shl 8) or to8(hb)
        }
        val bl = if (bloom > 0f) blurPx(hi, w, h, max(4, (min(w, h) / 22f * (bloom / 100f)).roundToInt())) else hi
        val out = p.copyOf()
        if (drip > 0f) {
            val maxDrip = (h * 0.16f * (drip / 100f)).roundToInt().coerceAtLeast(1)
            for (x in 0 until w) {
                val colWave = 0.35f + 0.65f * (hash2(x / 7, 13) + 0.5f)
                val colLen = (maxDrip * colWave).roundToInt()
                if (colLen < 2) continue
                var trailR = 0f
                var trailG = 0f
                var trailB = 0f
                val decay = 1f - 1.5f / colLen
                for (y in 0 until h) {
                    val idx = y * w + x
                    val hv = hi[idx]
                    trailR = max(ch(hv, 16).toFloat(), trailR * decay)
                    trailG = max(ch(hv, 8).toFloat(), trailG * decay)
                    trailB = max(ch(hv, 0).toFloat(), trailB * decay)
                    val v = out[idx]
                    out[idx] = (0xFF shl 24) or
                        (max(ch(v, 16), cl(trailR * 0.85f)) shl 16) or
                        (max(ch(v, 8), cl(trailG * 0.85f)) shl 8) or
                        max(ch(v, 0), cl(trailB * 0.85f))
                }
            }
        }
        val bk = bloom / 100f * 1.1f
        for (i in out.indices) {
            val v = out[i]
            val g = bl[i]
            out[i] = (0xFF shl 24) or
                (screenCh(v, g, 16, bk) shl 16) or
                (screenCh(v, g, 8, bk) shl 8) or
                screenCh(v, g, 0, bk)
        }
        return bmpOf(out, w, h)
    }

    private fun splashOp(b: Bitmap, hue: Float, tol: Float, vivid: Float, br: Float, show: Boolean): Bitmap {
        val wdt = tol / 100f * 85f + 4f
        val ftd = max(6f, wdt * 0.45f)
        val satBoost = 1f + vivid / 100f * 0.85f
        val brAdd = br / 100f * 0.25f
        return map(b) { _, _, c ->
            val r = c[0]
            val g = c[1]
            val bl = c[2]
            val mx = max(r, max(g, bl))
            val mn = min(r, min(g, bl))
            val d = mx - mn
            var keep = 0f
            if (d >= 0.025f) {
                var hh = (when {
                    mx == r -> ((g - bl) / d) % 6f
                    mx == g -> (bl - r) / d + 2f
                    else -> (r - g) / d + 4f
                }) * 60f
                if (hh < 0f) hh += 360f
                var ad = abs(hh - hue)
                if (ad > 180f) ad = 360f - ad
                keep = (1f - smooth((ad - wdt) / ftd)) * smooth(d / mx * 4.5f)
            }
            val l = (0.299f * r + 0.587f * g + 0.114f * bl + brAdd).coerceIn(0f, 1f)
            val vr = (l + (r - l) * satBoost + brAdd).coerceIn(0f, 1f)
            val vg = (l + (g - l) * satBoost + brAdd).coerceIn(0f, 1f)
            val vb = (l + (bl - l) * satBoost + brAdd).coerceIn(0f, 1f)
            if (show) {
                val m = 0.7f * keep
                c[0] = l * (1f - m) + m
                c[1] = l * (1f - m)
                c[2] = l * (1f - m)
            } else {
                c[0] = l + (vr - l) * keep
                c[1] = l + (vg - l) * keep
                c[2] = l + (vb - l) * keep
            }
        }
    }

    private fun flareOp(b: Bitmap, fx: Float, fy: Float, strength: Float, hue: Float, streak: Float, rays: Float): Bitmap {
        if (strength <= 0f) return b
        val w = b.width
        val h = b.height
        val m = min(w, h).toFloat()
        val cx = fx * w
        val cy = fy * h
        val gx = w - cx
        val gy = h - cy
        val k = strength / 100f
        val sk = streak / 100f
        val rk = rays / 100f
        val fc = FloatArray(3)
        val sc = FloatArray(3)
        hsvToRgb(hue, 0.7f, 1f, fc)
        hsvToRgb((hue + 190f) % 360f, 0.65f, 1f, sc)
        return map(b) { x, y, c ->
            val dx = (x - cx) / m
            val dy = (y - cy) / m
            val r = hypot(dx, dy)
            val core = exp(-r * r * 55f)
            val halo = exp(-r * 4.2f) * 0.65f
            val ringD = abs(r - 0.28f)
            val ring = exp(-ringD * ringD * 900f) * 0.22f
            val gdx = (x - gx) / m
            val gdy = (y - gy) / m
            val gr = hypot(gdx, gdy)
            val ghost = exp(-gr * gr * 38f) * 0.35f
            val anamorphic = exp(-abs(dy) * 42f) * exp(-abs(dx) * 1.8f) * sk * 0.85f
            val ang = atan2(dy, dx)
            val star = (0.5f + 0.5f * cos(ang * 8f)).pow(6f) * exp(-r * 5.5f) * rk * 0.7f
            val mainA = ((core + halo + star) * k).coerceIn(0f, 1.2f)
            val secA = ((ring + ghost + anamorphic) * k).coerceIn(0f, 1f)
            val ar = (core * 0.8f * k + fc[0] * mainA + sc[0] * secA).coerceIn(0f, 1f)
            val ag = (core * 0.8f * k + fc[1] * mainA + sc[1] * secA).coerceIn(0f, 1f)
            val ab = (core * 0.8f * k + fc[2] * mainA + sc[2] * secA).coerceIn(0f, 1f)
            c[0] = 1f - (1f - c[0]) * (1f - ar)
            c[1] = 1f - (1f - c[1]) * (1f - ag)
            c[2] = 1f - (1f - c[2]) * (1f - ab)
        }
    }

    private fun leakProOp(b: Bitmap, style: Int, strength: Float, hue: Float): Bitmap {
        if (strength <= 0f) return b
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val k = strength / 100f
        val c1 = FloatArray(3)
        val c2 = FloatArray(3)
        hsvToRgb(hue, 0.9f, 1f, c1)
        hsvToRgb((hue + 40f) % 360f, 0.8f, 1f, c2)
        return map(b) { x, y, c ->
            val nx = x / w
            val ny = y / h
            val t1: Float
            val t2: Float
            when (style.coerceIn(0, 3)) {
                0 -> {
                    t1 = (1f - nx / 0.65f).coerceAtLeast(0f).pow(1.8f)
                    t2 = (1f - hypot(nx, ny - 0.3f) / 0.7f).coerceAtLeast(0f).pow(2f)
                }
                1 -> {
                    t1 = ((nx - 0.35f) / 0.65f).coerceAtLeast(0f).pow(1.8f)
                    t2 = (1f - nx / 0.45f).coerceAtLeast(0f).pow(2.2f) * 0.6f
                }
                2 -> {
                    t1 = (1f - ny / 0.5f).coerceAtLeast(0f).pow(1.8f)
                    t2 = ((ny - 0.5f) / 0.5f).coerceAtLeast(0f).pow(1.8f)
                }
                else -> {
                    val d = (1f - (nx * 0.7f + ny * 0.7f)).coerceAtLeast(0f)
                    t1 = d * d
                    t2 = exp(-abs(nx + ny - 1f) * 6f) * 0.45f
                }
            }
            val lr = (c1[0] * t1 + c2[0] * t2) * k
            val lg = (c1[1] * t1 + c2[1] * t2) * k
            val lb = (c1[2] * t1 + c2[2] * t2) * k
            c[0] = 1f - (1f - c[0]) * (1f - lr.coerceIn(0f, 1f))
            c[1] = 1f - (1f - c[1]) * (1f - lg.coerceIn(0f, 1f))
            c[2] = 1f - (1f - c[2]) * (1f - lb.coerceIn(0f, 1f))
        }
    }
}
