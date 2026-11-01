package dev.cluvex.strata.core.unity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Android implementation of [UnityPersianApplyPipeline.LetterRasterizer]:
 * renders one presentation-form glyph from a Persian TTF via
 * Paint / Typeface / Bitmap / Canvas, and returns a tight binary
 * core + metrics for [UnitySdfBaker].
 *
 * Android-only: this file imports android.graphics, so it is NOT
 * compiled in the VM scratch harnesses (which use a synthetic
 * rasterizer instead). The VM pipeline test proves shaping /
 * mapping / ranking / baking / fallback / coverage / patching
 * logic; device testing by the user proves real glyph shapes,
 * advances, and bearings from the selected font, which no VM test
 * can prove (no Android font stack in this VM).
 *
 * Rendering (analogue of the Python PIL/FreeType renderer):
 * - Paint: typeface = selected Persian font, textSize = pointSize
 *   (e.g. 84f, from the TMP FaceInfo), antiAlias = true, color =
 *   WHITE, style = FILL. Antialiasing matters: the binary core is
 *   thresholded at alpha > 128, so partially-covered edge pixels
 *   (alpha <= 128) become background, matching Python's FreeType
 *   thresholding; a non-antialiased render would give jagged cores
 *   and different tight bounds.
 * - Canvas: ARGB_8888 Bitmap (not ALPHA_8, which has drawing quirks
 *   on some Android versions; ARGB_8888 is proven in Strata's
 *   CloneIconRenderer), transparent background, white text. The
 *   alpha channel is the mask (pixel shr 24 & 0xFF); RGB is ignored.
 * - Bitmap size: wide enough for the glyph advance + margins
 *   (advance + 40 px) and tall enough for ascenders/descenders
 *   (pointSize * 3, with the baseline at 1.5 * pointSize from the
 *   top). A glyph that does not fit (advance absurdly large, or
 *   pointSize absurdly large) throws, rather than clipping ink
 *   and baking a truncated glyph.
 * - Tight crop: scan the alpha mask for ink (alpha > 128) and crop
 *   to its bounds. BearingX = tightMinX - penX, bearingY =
 *   baselineY - tightMinY, advance = Paint.measureText(char).
 *   These formulas match the baker's compositing (pen + bearing,
 *   baseline - bearingY = glyph top), so word glyphs composite
 *   exactly as Python's word bake did from PIL metrics.
 * - Missing glyph: if no ink is found (font has no glyph for this
 *   presentation form, rendering as blank / .notdef with no ink in
 *   the tight scan, or as a tofu box whose ink is... actually, a
 *   .notdef tofu box DOES have ink (a rectangle), so a missing
 *   glyph that renders as tofu would NOT be caught by the empty-
 *   ink check. To catch that, the caller should also check font
 *   coverage before pipeline (UnityFontInspector / readiness
 *   checks, which already report coverage gaps for shaped text).
 *   The empty-ink check here catches fonts that render the form
 *   as blank (e.g. a form outside the font's cmap, with no .notdef
 *   ink either, on fonts that render missing glyphs as blank).
 *   Either way, a blank core is never baked: it throws
 *   [UnityPersianApplyPipeline.PersianRasterizerException], which
 *   the pipeline converts to NotReady ("choose a Persian font
 *   that covers ..."), instead of baking an invisible glyph.
 *
 * Usage (caller loads the Typeface, e.g. from a user-picked TTF
 * via ContentResolver / Typeface.createFromFile, then passes it
 * here; this class does no file I/O, so it stays testable and
 * decoupled from picking):
 *   val rasterizer = UnityPersianFontRasterizer(typeface)
 *   pipeline.preparePersianTerms(terms, tmp, texture, rasterizer)
 */
class UnityPersianFontRasterizer(
    private val typeface: Typeface,
) : UnityPersianApplyPipeline.LetterRasterizer {

    override fun rasterize(
        letterPua: Int,
        originalCodepoint: Int,
        pointSize: Float,
    ): UnitySdfBaker.LetterInput {
        require(pointSize > 0f) { "Persian rasterizer: pointSize must be positive, got $pointSize" }
        val charString = originalCodepoint.toChar().toString()
        // Single BMP codepoint: presentation forms (FB50..FEFF) and
        // Arabic forms (0600..06FF) are all BMP, so toChar() is exact.
        // (Supplementary codepoints would need surrogate pairs; the
        // cipher / extension sets contain none, so this is safe.)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@UnityPersianFontRasterizer.typeface
            textSize = pointSize
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val advance = paint.measureText(charString)
        require(advance > 0f && advance < pointSize * 4f) {
            "Persian rasterizer: implausible advance $advance for U+${originalCodepoint.toString(16).uppercase()} " +
                "at pointSize $pointSize (font problem or wrong codepoint)"
        }
        val bitmapWidth = ceil(advance).toInt() + 40
        val bitmapHeight = ceil(pointSize * 3f).toInt()
        val penX = 20
        val baselineY = (pointSize * 1.5f).toInt()
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawText(charString, penX.toFloat(), baselineY.toFloat(), paint)
            val pixels = IntArray(bitmapWidth * bitmapHeight)
            bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
            // Tight bounds by alpha > 128 scan.
            var minX = bitmapWidth
            var maxX = -1
            var minY = bitmapHeight
            var maxY = -1
            for (y in 0 until bitmapHeight) {
                val row = y * bitmapWidth
                for (x in 0 until bitmapWidth) {
                    val alpha = (pixels[row + x] ushr 24) and 0xFF
                    if (alpha > 128) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) {
                throw UnityPersianApplyPipeline.PersianRasterizerException(
                    "No ink for U+${originalCodepoint.toString(16).uppercase()} " +
                        "(letter PUA U+${letterPua.toString(16).uppercase()}) at pointSize $pointSize",
                )
            }
            val coreWidth = maxX - minX + 1
            val coreHeight = maxY - minY + 1
            val core = BooleanArray(coreWidth * coreHeight)
            for (y in minY..maxY) {
                val row = y * bitmapWidth
                val coreRow = (y - minY) * coreWidth
                for (x in minX..maxX) {
                    val alpha = (pixels[row + x] ushr 24) and 0xFF
                    core[coreRow + (x - minX)] = alpha > 128
                }
            }
            // Bearings from tight bounds, relative to pen/baseline.
            val bearingX = (minX - penX).toFloat()
            val bearingY = (baselineY - minY).toFloat()
            // Sanity: tight bounds must be inside the bitmap (they are,
            // by construction), and bearings must be plausible (within
            // a few em of the pen/baseline, not wildly off due to a
            // rendering bug). A wildly-off bearing would composite
            // words wrong, so fail fast instead of baking it.
            require(bearingX > -pointSize * 2f && bearingX < pointSize * 2f &&
                bearingY > -pointSize * 2f && bearingY < pointSize * 3f) {
                "Persian rasterizer: implausible bearings ($bearingX, $bearingY) " +
                    "for U+${originalCodepoint.toString(16).uppercase()} (rendering bug)"
            }
            return UnitySdfBaker.LetterInput(
                pua = letterPua,
                core = core,
                coreWidth = coreWidth,
                coreHeight = coreHeight,
                advance = advance,
                bearingX = bearingX,
                bearingY = bearingY,
            )
        } finally {
            bitmap.recycle()
        }
    }
}
