package dev.gab8bit.madovai.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import dev.gab8bit.madovai.R
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.ui.theme.TransitColors

/** Bitmap marker icons for osmdroid, cached by appearance (many markers share one drawable). */
class MarkerIcons(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val cache = HashMap<String, Drawable>()

    private fun px(dp: Float) = dp * density

    private enum class Glyph(val res: Int) { BUS(R.drawable.glyph_bus), TRAIN(R.drawable.glyph_train), TRAM(R.drawable.glyph_tram), STAR(R.drawable.glyph_star) }

    fun pole(isRail: Boolean, isFavorite: Boolean): Drawable = cache.getOrPut("pole-$isRail-$isFavorite") {
        val fill = when {
            isFavorite -> TransitColors.Favorite
            isRail -> TransitColors.CotralPoleRail
            else -> TransitColors.CotralPoleBus
        }
        circle(sizeDp = 30f, fill = fill, borderDp = 2f, glyph = if (isRail) Glyph.TRAIN else Glyph.BUS, text = null, badgeStar = isFavorite)
    }

    fun atacStop(): Drawable = cache.getOrPut("atacStop") {
        circle(sizeDp = 13f, fill = TransitColors.AtacStop, borderDp = 1.8f, glyph = null, text = null)
    }

    fun vehicle(v: TransitVehicle): Drawable {
        val color = vehicleColor(v)
        val label = v.routeLabel?.takeIf { it.length <= 3 }
        val glyph = when (v.kind) {
            TransitVehicleKind.BUS -> Glyph.BUS
            TransitVehicleKind.TRAM -> Glyph.TRAM
            TransitVehicleKind.TRENO -> Glyph.TRAIN
            TransitVehicleKind.METRO -> null
        }
        val key = "veh-${color.toArgb()}-${label ?: ""}-${v.kind}"
        return cache.getOrPut(key) {
            // Short route numbers read better as text; longer labels (or none) use the glyph.
            if (label != null) circle(28f, color, 1.6f, null, label)
            else if (glyph == null) circle(28f, color, 1.6f, null, "M")
            else circle(28f, color, 1.6f, glyph, null)
        }
    }

    fun followedVehicle(isTreno: Boolean, lost: Boolean): Drawable = cache.getOrPut("followed-$isTreno-$lost") {
        val d = circle(40f, if (lost) Color(0xFF8E8E93) else TransitColors.CotralOrange, 2.5f, if (isTreno) Glyph.TRAIN else Glyph.BUS, null, haloDp = 6f)
        if (lost) d.alpha = 170
        d
    }

    fun userLocation(): Drawable = cache.getOrPut("user") {
        circle(16f, Color(0xFF1A73E8), 3f, null, null, haloDp = 10f, haloColor = Color(0x331A73E8))
    }

    private fun vehicleColor(v: TransitVehicle): Color = when (v.transitOperator) {
        TransitOperator.COTRAL -> TransitColors.CotralOrange
        TransitOperator.ROMA_TPL -> TransitColors.RomaTpl
        TransitOperator.ATAC -> when (v.kind) {
            TransitVehicleKind.METRO -> TransitColors.Metro
            TransitVehicleKind.TRAM -> TransitColors.Tram
            TransitVehicleKind.TRENO -> TransitColors.Treno
            TransitVehicleKind.BUS -> TransitColors.AtacTeal
        }
    }

    private fun circle(
        sizeDp: Float,
        fill: Color,
        borderDp: Float,
        glyph: Glyph?,
        text: String?,
        badgeStar: Boolean = false,
        haloDp: Float = 0f,
        haloColor: Color = Color(0x40000000),
    ): BitmapDrawable {
        val total = px(sizeDp + haloDp * 2)
        val sizePx = total.toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sizePx / 2f
        val r = px(sizeDp) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (haloDp > 0) {
            paint.color = haloColor.toArgb()
            c.drawCircle(cx, cx, r + px(haloDp), paint)
        } else {
            // soft shadow
            paint.color = 0x33000000
            c.drawCircle(cx, cx + px(0.8f), r, paint)
        }
        paint.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cx, r, paint)
        paint.color = fill.toArgb()
        c.drawCircle(cx, cx, r - px(borderDp), paint)

        if (glyph != null) {
            val d = ContextCompat.getDrawable(context, glyph.res)!!.mutate()
            val g = (r * 1.05f).toInt()
            d.setBounds((cx - g / 2f).toInt(), (cx - g / 2f).toInt(), (cx + g / 2f).toInt(), (cx + g / 2f).toInt())
            d.setTint(android.graphics.Color.WHITE)
            d.draw(c)
        }
        if (text != null) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = px(if (text.length <= 2) 11f else 9f)
            }
            val maxW = (r - px(borderDp)) * 1.8f
            while (tp.measureText(text) > maxW && tp.textSize > px(6f)) tp.textSize -= px(0.5f)
            val y = cx - (tp.descent() + tp.ascent()) / 2f
            c.drawText(text, cx, y, tp)
        }
        if (badgeStar) {
            val d = ContextCompat.getDrawable(context, Glyph.STAR.res)!!.mutate()
            val s = (r * 0.8f).toInt()
            val left = (cx + r * 0.35f).toInt().coerceAtMost(sizePx - s)
            d.setBounds(left, 0, left + s, s)
            d.setTint(0xFFB26A00.toInt())
            d.draw(c)
        }
        return BitmapDrawable(context.resources, bmp)
    }
}
