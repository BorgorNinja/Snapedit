package app.snapedit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapEditAutomatedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Generates a synthetic test photograph with sky/ground gradients, bright highlights,
     * deep shadows, color swatches across all HSL bands, and a red-eye test spot.
     */
    private fun createSyntheticPhoto(width: Int = 640, height: Int = 480): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky to horizon gradient
        paint.shader = LinearGradient(
            0f, 0f, 0f, height * 0.6f,
            intArrayOf(Color.rgb(35, 95, 190), Color.rgb(135, 195, 245), Color.rgb(250, 210, 150)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.6f, paint)

        // Ground gradient
        paint.shader = LinearGradient(
            0f, height * 0.6f, 0f, height.toFloat(),
            intArrayOf(Color.rgb(65, 130, 55), Color.rgb(30, 65, 25)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Bright sun highlight + deep shadow box
        paint.color = Color.rgb(254, 252, 235)
        canvas.drawCircle(width * 0.8f, height * 0.2f, width * 0.08f, paint)
        paint.color = Color.rgb(12, 14, 18)
        canvas.drawRect(width * 0.05f, height * 0.7f, width * 0.22f, height * 0.92f, paint)

        // Colorful subject blocks across all HSL bands
        val swatches = intArrayOf(
            Color.rgb(220, 45, 45),   // Red
            Color.rgb(240, 135, 30),  // Orange
            Color.rgb(240, 220, 40),  // Yellow
            Color.rgb(45, 190, 75),   // Green
            Color.rgb(40, 200, 200),  // Aqua
            Color.rgb(45, 95, 225),   // Blue
            Color.rgb(145, 65, 215),  // Purple
            Color.rgb(215, 55, 175)   // Magenta
        )
        val swW = (width * 0.6f) / swatches.size
        swatches.forEachIndexed { idx, col ->
            paint.color = col
            val left = width * 0.25f + idx * swW
            canvas.drawRect(left, height * 0.65f, left + swW - 4f, height * 0.88f, paint)
        }

        // Portrait circle with red-eye test spot at (0.5, 0.4)
        paint.color = Color.rgb(225, 180, 150)
        canvas.drawCircle(width * 0.5f, height * 0.4f, width * 0.12f, paint)
        paint.shader = RadialGradient(
            width * 0.5f, height * 0.4f, width * 0.03f,
            Color.rgb(240, 20, 25), Color.rgb(90, 10, 15), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(width * 0.5f, height * 0.4f, width * 0.03f, paint)
        paint.shader = null

        return bmp
    }

    /**
     * Uploads a synthetic photo into the device's MediaStore with EXIF metadata,
     * returning its content:// Uri.
     */
    private fun uploadTestImageToMediaStore(ctx: Context, width: Int = 640, height: Int = 480): Uri {
        val bmp = createSyntheticPhoto(width, height)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SnapEdit_Test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapeditTest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore URI should not be null", uri)
        ctx.contentResolver.openOutputStream(uri!!)?.use { out ->
            assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out))
        }
        // Write EXIF metadata to verify exifInfo()
        ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_MAKE, "SnapEditCam")
            exif.setAttribute(ExifInterface.TAG_MODEL, "CI-Runner-Pro")
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35/1")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:30 12:00:00")
            exif.saveAttributes()
        }
        return uri
    }

    private fun bitmapsDiffer(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(b.width * b.height)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        return !pa.contentEquals(pb)
    }

    @Test
    fun testAllToolsLooksPresetsAndExportEngine() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Upload test image to MediaStore & verify loading + EXIF extraction
        val uploadedUri = uploadTestImageToMediaStore(ctx, 240, 180)
        val loaded = loadBitmap(ctx, uploadedUri, 4096)
        assertNotNull("Loaded bitmap from MediaStore must not be null", loaded)
        assertEquals(240, loaded!!.width)
        assertEquals(180, loaded.height)

        val exif = exifInfo(ctx, uploadedUri)
        assertTrue("EXIF should contain camera make/model, got: $exif", exif.contains("SnapEditCam CI-Runner-Pro"))
        assertTrue("EXIF should contain ISO 200, got: $exif", exif.contains("ISO 200"))

        val src = scaleTo(loaded, 160)
        assertTrue("Tools.all should contain at least 35 tools", Tools.all.size >= 35)

        // 2. Test EVERY single tool in Tools.all (all 43 tools including Magic tools)
        for (tool in Tools.all) {
            val baseParams = tool.params.associate { it.name to it.def }.toMutableMap()
            val op: Op = when (tool.id) {
                "crop" -> {
                    baseParams["L"] = 0.1f
                    baseParams["T"] = 0.1f
                    baseParams["R"] = 0.85f
                    baseParams["B"] = 0.85f
                    Op(tool.id, baseParams)
                }
                "rotate" -> {
                    baseParams["Rotate"] = 1f
                    baseParams["Flip"] = 1f
                    baseParams["Straighten"] = 8f
                    Op(tool.id, baseParams)
                }
                "curves" -> {
                    val customCurves = listOf(
                        listOf(0f, 0f, 0.3f, 0.18f, 0.7f, 0.85f, 1f, 1f),
                        listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f),
                        listOf(0f, 0f, 0.5f, 0.4f, 1f, 1f),
                        listOf(0f, 0f, 1f, 1f)
                    )
                    Op(tool.id, baseParams, curves = customCurves)
                }
                "hsl" -> {
                    for (band in 0..7) {
                        baseParams["h$band"] = 25f
                        baseParams["s$band"] = 35f
                        baseParams["l$band"] = -15f
                    }
                    Op(tool.id, baseParams)
                }
                "grad" -> {
                    baseParams["Exposure"] = 45f
                    baseParams["Contrast"] = 30f
                    baseParams["Warmth"] = 25f
                    baseParams["X0"] = 0.5f; baseParams["Y0"] = 0.2f
                    baseParams["X1"] = 0.5f; baseParams["Y1"] = 0.8f
                    Op(tool.id, baseParams)
                }
                "radial" -> {
                    baseParams["Exposure"] = 50f
                    baseParams["Saturation"] = 40f
                    baseParams["Feather"] = 50f
                    baseParams["CX"] = 0.5f; baseParams["CY"] = 0.5f
                    baseParams["RX"] = 0.35f; baseParams["RY"] = 0.3f
                    Op(tool.id, baseParams)
                }
                "range" -> {
                    baseParams["Mode"] = 0f
                    baseParams["Low"] = 20f
                    baseParams["High"] = 90f
                    baseParams["Exposure"] = 45f
                    baseParams["Saturation"] = 40f
                    Op(tool.id, baseParams)
                }
                "splash" -> {
                    baseParams["Hue"] = 120f
                    baseParams["Tolerance"] = 35f
                    baseParams["Vividness"] = 50f
                    baseParams["Brightness"] = 10f
                    Op(tool.id, baseParams)
                }
                "brush" -> {
                    baseParams["Effect"] = 1f // Exposure
                    baseParams["Strength"] = 70f
                    val strokes = listOf(
                        BrushStroke(listOf(0.2f, 0.2f, 0.4f, 0.4f, 0.6f, 0.5f), 0.12f, false)
                    )
                    Op(tool.id, baseParams, strokes = strokes)
                }
                "draw" -> {
                    baseParams["Color"] = 1f
                    baseParams["Glow"] = 60f
                    baseParams["Opacity"] = 95f
                    val strokes = listOf(
                        BrushStroke(listOf(0.15f, 0.2f, 0.5f, 0.5f, 0.8f, 0.7f), 0.08f, false, Engine.drawColors[1], 60f)
                    )
                    Op(tool.id, baseParams, strokes = strokes)
                }
                "select" -> {
                    val pts = listOf(
                        Pt(0.5f, 0.5f, 0.25f, 45f, 30f, 40f)
                    )
                    Op(tool.id, baseParams, pts = pts)
                }
                "heal" -> {
                    val spots = listOf(Spot(0.5f, 0.4f, 0.08f))
                    Op(tool.id, baseParams, spots = spots)
                }
                "redeye" -> {
                    val spots = listOf(Spot(0.5f, 0.4f, 0.08f))
                    Op(tool.id, baseParams, spots = spots)
                }
                "liquify" -> {
                    val warps = listOf(
                        Warp(0.5f, 0.5f, 0.06f, 0.04f, 0.2f, 0, 0f), // Push
                        Warp(0.3f, 0.3f, 0f, 0f, 0.2f, 1, 0.15f),     // Bloat
                        Warp(0.7f, 0.7f, 0f, 0f, 0.2f, 2, 0.15f),     // Pinch
                        Warp(0.4f, 0.6f, 0.05f, 0.03f, 0.2f, 3, 0f),  // Stretch
                        Warp(0.6f, 0.4f, 0f, 0f, 0.2f, 4, 0.15f)      // Swirl
                    )
                    Op(tool.id, baseParams, warps = warps)
                }
                "text" -> {
                    baseParams["X"] = 50f
                    baseParams["Y"] = 50f
                    baseParams["Size"] = 40f
                    baseParams["Opacity"] = 100f
                    baseParams["Color"] = 2f
                    Op(tool.id, baseParams, text = "SnapEdit CI")
                }
                else -> {
                    for (p in tool.params.filter { !it.hidden }) {
                        val target = if (p.def < (p.min + p.max) / 2f) {
                            (p.def + (p.max - p.min) * 0.35f).coerceIn(p.min, p.max)
                        } else {
                            (p.def - (p.max - p.min) * 0.35f).coerceIn(p.min, p.max)
                        }
                        baseParams[p.name] = if (p.max - p.min <= 7f) target.toInt().toFloat() else target
                    }
                    Op(tool.id, baseParams)
                }
            }

            assertTrue("Tool '${tool.id}' op should be marked as changed()", changed(op))
            val out = Engine.render(src, listOf(op))
            assertNotNull("Output bitmap for tool '${tool.id}' must not be null", out)
            assertTrue("Output bitmap for tool '${tool.id}' must have positive dimensions", out.width > 0 && out.height > 0)
            assertTrue("Output bitmap for tool '${tool.id}' must modify the image", bitmapsDiffer(src, out))
        }

        // 3. Test all built-in Looks
        assertTrue("Tools.looks should have built-in presets", Tools.looks.size >= 9)
        for ((lookName, lookOps) in Tools.looks) {
            val out = Engine.render(src, lookOps)
            assertTrue("Look '$lookName' should modify pixels", bitmapsDiffer(src, out))
        }

        // 4. Test Auto-Tone & Auto White-Balance helpers
        val autoLightVals = Engine.autoLight(src)
        assertEquals(6, autoLightVals.size)
        val avgCol = Engine.avgColor(src)
        assertEquals(3, avgCol.size)
        val wbVals = Engine.wbFrom(avgCol[0], avgCol[1], avgCol[2])
        assertEquals(2, wbVals.size)

        // 5. Test User Presets save/load/remove
        val presetName = "TestPreset_${System.currentTimeMillis()}"
        val sampleOps = listOf(Tools.op("tune", "Brightness" to 25f, "Contrast" to 15f))
        Presets.add(ctx, presetName, sampleOps)
        val loadedPresets = Presets.load(ctx)
        assertTrue("Saved preset should exist in Presets.load()", loadedPresets.any { it.first == presetName })
        Presets.remove(ctx, presetName)
        assertFalse("Removed preset should no longer exist", Presets.load(ctx).any { it.first == presetName })

        // 6. Test Watermark & Save/Export in all 3 formats (JPEG, PNG, WebP)
        val watermarked = watermark(src, "SnapEdit CI")
        assertTrue("Watermark should modify bitmap", bitmapsDiffer(src, watermarked))
        for (fmt in 0..2) {
            val savedUri = saveImage(ctx, watermarked, fmt, 90)
            assertNotNull("Saved URI for format $fmt must not be null", savedUri)
            val reloaded = loadBitmap(ctx, savedUri!!, 1024)
            assertNotNull("Reloaded exported image for format $fmt must not be null", reloaded)
            assertEquals(watermarked.width, reloaded!!.width)
            assertEquals(watermarked.height, reloaded.height)
        }
    }

    @Test
    fun testEndToEndUiAutomationAllFeatures() {
        // Use manual clock stepping so infinite pulse / indeterminate progress animations never hang waitForIdle()
        composeRule.mainClock.autoAdvance = false

        fun pump(ms: Long = 450) {
            val steps = (ms / 50).coerceAtLeast(1)
            repeat(steps.toInt()) {
                Thread.sleep(50)
                composeRule.mainClock.advanceTimeBy(50)
            }
        }

        fun pumpUntil(timeoutMs: Long = 12_000, condition: () -> Boolean) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (runCatching(condition).getOrDefault(false)) return
                Thread.sleep(50)
                composeRule.mainClock.advanceTimeBy(50)
            }
            assertTrue("Condition timed out after ${timeoutMs}ms", condition())
        }

        val ctx = composeRule.activity.applicationContext
        pump(300)

        // 1. Verify empty state screen
        composeRule.onNodeWithText("Tap to open a photo").assertIsDisplayed()

        // 2. Upload test photo to MediaStore and load it into MainActivity
        val uploadedUri = uploadTestImageToMediaStore(ctx, 640, 480)
        composeRule.activity.runOnUiThread {
            composeRule.activity.incoming = uploadedUri
        }

        // Wait until the photo is decoded and top bar actions appear
        pumpUntil {
            composeRule.onAllNodesWithContentDescription("Edits Stack").fetchSemanticsNodes().isNotEmpty()
        }
        pump(400)

        // 3. Test Top Bar Histogram & Before/After Split Compare modes
        composeRule.onNodeWithContentDescription("Histogram").performClick()
        pump(300)
        composeRule.onNodeWithContentDescription("Histogram").performClick()
        pump(300)
        composeRule.onNodeWithContentDescription("Histogram").performClick()
        pump(300)

        composeRule.onAllNodesWithContentDescription("Compare")[0].performClick()
        pump(400)
        composeRule.onNodeWithText("BEFORE").assertIsDisplayed()
        composeRule.onNodeWithText("AFTER").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Compare")[0].performClick()
        pump(300)

        // 4. Test LOOKS tab: apply built-in looks, Undo/Redo, and save a custom Look
        composeRule.onNodeWithText("LOOKS").performClick()
        pump(500)
        composeRule.onNodeWithText("Pop").performClick()
        pump(500)

        composeRule.onNodeWithContentDescription("Undo").performClick()
        pump(400)
        composeRule.onNodeWithContentDescription("Redo").performClick()
        pump(400)

        composeRule.onNodeWithText("Save look").performClick()
        pump(400)
        composeRule.onNodeWithText("Save as Look").assertIsDisplayed()
        composeRule.onNodeWithTag("preset_name_input").performTextInput("CI Look")
        pump(200)
        composeRule.onNodeWithText("Save").performClick()
        pump(500)
        assertTrue("Custom look 'CI Look' should be saved", Presets.load(ctx).any { it.first == "CI Look" })

        // 5. Test TOOLS tab across categories & tools
        composeRule.onNodeWithText("TOOLS").performClick()
        pump(500)

        // 5a. Light category (default selected) -> Light (AUTO TONE), Curves, Tune Image
        composeRule.onNodeWithTag("tool_light").performClick()
        pump(400)
        composeRule.onNodeWithText("AUTO TONE").performClick()
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        composeRule.onNodeWithTag("tool_tune").performClick()
        pump(400)
        composeRule.onNodeWithTag("param_slider").performTouchInput { swipeRight() }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        composeRule.onNodeWithTag("tool_curves").performClick()
        pump(400)
        composeRule.onNodeWithText("Red").performClick()
        pump(200)
        composeRule.onNodeWithText("RGB").performClick()
        pump(200)
        composeRule.onNodeWithTag("curve_editor").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY * 0.6f), 200)
        }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5b. Color category -> White Balance (AUTO)
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Color"))
        composeRule.onNodeWithText("Color").performClick()
        pump(400)
        composeRule.onNodeWithTag("tool_wb").performClick()
        pump(400)
        composeRule.onNodeWithText("AUTO").performClick()
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5c. Magic category -> Neon Glow
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Magic"))
        composeRule.onNodeWithText("Magic").performClick()
        pump(400)
        composeRule.onNodeWithTag("tool_neon").performClick()
        pump(400)
        composeRule.onNodeWithTag("param_slider").performTouchInput { swipeRight() }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5d. Detail category -> Details
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Detail"))
        composeRule.onNodeWithText("Detail").performClick()
        pump(400)
        composeRule.onNodeWithTag("tool_details").performClick()
        pump(400)
        composeRule.onNodeWithTag("param_slider").performTouchInput { swipeRight() }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5e. Local category -> Brush stroke on image stage
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Local"))
        composeRule.onNodeWithText("Local").performClick()
        pump(400)
        composeRule.onNodeWithTag("tool_brush").performClick()
        pump(400)
        composeRule.onNodeWithTag("image_stage").performTouchInput {
            swipe(Offset(centerX * 0.6f, centerY * 0.6f), Offset(centerX * 1.4f, centerY * 1.2f), 250)
        }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5f. Geometry category -> Rotate
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Geometry"))
        composeRule.onNodeWithText("Geometry").performClick()
        pump(400)
        composeRule.onNodeWithTag("tool_rotate").performClick()
        pump(400)
        composeRule.onNodeWithTag("param_slider").performTouchInput { swipeRight() }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 5g. Creative category -> Text & Frames
        composeRule.onNodeWithTag("category_row").performScrollToNode(hasText("Creative"))
        composeRule.onNodeWithText("Creative").performClick()
        pump(400)
        composeRule.onNodeWithTag("tools_grid").performScrollToNode(hasTestTag("tool_text"))
        composeRule.onNodeWithTag("tool_text").performClick()
        pump(400)
        composeRule.onNodeWithTag("text_tool_input").performTextInput("SnapEdit CI")
        pump(200)
        composeRule.onNodeWithText("OK").performClick()
        pump(300)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        composeRule.onNodeWithTag("tools_grid").performScrollToNode(hasTestTag("tool_frames"))
        composeRule.onNodeWithTag("tool_frames").performClick()
        pump(400)
        composeRule.onNodeWithTag("param_slider").performTouchInput { swipeRight() }
        pump(400)
        composeRule.onNodeWithTag("apply_tool").performClick()
        pump(500)

        // 6. Test Edits Stack Panel (Photo Info, Copy/Paste Edits, Close)
        composeRule.onNodeWithContentDescription("Edits Stack").performClick()
        pump(500)
        composeRule.onNodeWithText("Edits").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Photo Info").performClick()
        pump(400)
        composeRule.onNodeWithText("Photo info").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        pump(300)
        composeRule.onNodeWithContentDescription("Copy Edits").performClick()
        pump(300)
        composeRule.onNodeWithContentDescription("Paste Edits").performClick()
        pump(400)
        composeRule.onNodeWithText("DONE").performClick()
        pump(500)

        // 7. Test EXPORT tab (Theme, Format, Scale, Watermark, Save to Gallery)
        composeRule.onNodeWithText("EXPORT").performClick()
        pump(500)
        composeRule.onNodeWithText("Dark").performClick()
        pump(250)
        composeRule.onNodeWithText("Light").performClick()
        pump(250)
        composeRule.onNodeWithText("Auto").performClick()
        pump(250)
        composeRule.onNodeWithText("PNG").performClick()
        pump(250)
        composeRule.onNodeWithText("JPEG").performClick()
        pump(250)
        composeRule.onNodeWithText("75%").performClick()
        pump(250)
        composeRule.onNodeWithText("100%").performClick()
        pump(250)
        composeRule.onNodeWithTag("watermark_input").performTextInput("SnapEdit CI")
        pump(300)
        composeRule.onNodeWithTag("export_scroll").performScrollToNode(hasText("Save to gallery"))
        composeRule.onNodeWithText("Save to gallery").performClick()

        // Wait for background export coroutine to finish and display confirmation banner
        pumpUntil(timeoutMs = 15_000) {
            composeRule.onAllNodesWithText("Saved to Pictures/Snapedit").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Saved to Pictures/Snapedit").assertIsDisplayed()
    }
}
