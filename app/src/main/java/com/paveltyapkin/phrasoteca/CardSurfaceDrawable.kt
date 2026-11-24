package com.paveltyapkin.phrasoteca

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Color-preserving phrase card surface. A thin inset highlight and a soft edge
 * give the card depth without placing any decoration behind the phrase itself.
 * The folded corner is shown only for cards that have a second side.
 */
class CardSurfaceDrawable(
    private val background: Int,
    private val twoSided: Boolean,
    private val density: Float,
    private val oppositeSideColor: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val d = density
        val radius = 26f * d
        val surface = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val luminance = (0.2126f * Color.red(background) +
                0.7152f * Color.green(background) +
                0.0722f * Color.blue(background)) / 255f
        val darkSurface = luminance < 0.45f
        val edgeColor = if (darkSurface) Color.argb(115, 234, 242, 255)
                        else Color.argb(95, 50, 72, 110)
        val highlightColor = if (darkSurface) Color.argb(35, 255, 255, 255)
                             else Color.argb(100, 255, 255, 255)

        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(surface, radius, radius, paint)

        // Outline is inset to avoid clipping when the entire card rotates.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f * d
        paint.color = edgeColor
        val outline = RectF(surface).apply { inset(0.75f * d, 0.75f * d) }
        canvas.drawRoundRect(outline, radius - d, radius - d, paint)

        // Inner highlight gives separation from the surrounding UI for both
        // light and dark user-defined card colors.
        paint.strokeWidth = d
        paint.color = highlightColor
        val inner = RectF(surface).apply { inset(3f * d, 3f * d) }
        canvas.drawRoundRect(inner, radius - 3f * d, radius - 3f * d, paint)
        paint.style = Paint.Style.FILL

        if (twoSided) {
            val fold = minOf(36f * d, surface.width() / 5f, surface.height() / 5f)
            val x = surface.right
            val y = surface.bottom
            val clip = Path().apply { addRoundRect(surface, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)

            // Subtle shadow behind the raised corner.
            paint.color = if (darkSurface) Color.argb(110, 0, 0, 0)
                          else Color.argb(65, 25, 39, 65)
            canvas.drawPath(Path().apply {
                moveTo(x - fold - 3f * d, y)
                lineTo(x, y - fold - 3f * d)
                lineTo(x, y)
                close()
            }, paint)

            // The visible fold previews the opposite side of this card.
            paint.color = oppositeSideColor
            canvas.drawPath(Path().apply {
                moveTo(x - fold, y)
                lineTo(x, y - fold)
                lineTo(x, y)
                close()
            }, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
