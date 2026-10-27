package app.thornberry.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Thornberry's colours: berry and deep green, light and dark. Chosen from the phone's setting. */
class Theme(val dark: Boolean) {
    val primary = if (dark) 0xFF7E2455.toInt() else 0xFF9C1F5C.toInt()
    val onPrimary = Color.WHITE
    val onPrimaryDim = 0xCCFFFFFF.toInt()
    val bg = if (dark) 0xFF140F18.toInt() else 0xFFFAF6F9.toInt()
    val surface = if (dark) 0xFF211A27.toInt() else Color.WHITE
    val bubbleMine = if (dark) 0xFF8F2A5F.toInt() else 0xFF9C1F5C.toInt()
    val bubbleOther = if (dark) 0xFF2D2434.toInt() else 0xFFEEE5EC.toInt()
    val text = if (dark) 0xFFF3ECF4.toInt() else 0xFF1E1620.toInt()
    val textDim = if (dark) 0xFFB8ABBD.toInt() else 0xFF6A5C70.toInt()
    val divider = if (dark) 0xFF2F2636.toInt() else 0xFFE8DEE5.toInt()
    val live = if (dark) 0xFF6FD6A3.toInt() else 0xFF1F7A53.toInt()
    val inputBg = if (dark) 0xFF2D2434.toInt() else 0xFFF1E9EF.toInt()
    val link = if (dark) 0xFFF29BC2.toInt() else 0xFF9C1F5C.toInt()

    private val palette = intArrayOf(
        0xFF9C1F5C.toInt(), 0xFF2E7D5B.toInt(), 0xFF5E4AA8.toInt(), 0xFFB85C14.toInt(),
        0xFF1F6F8F.toInt(), 0xFF8A3A3A.toInt(), 0xFF4F7A1E.toInt(), 0xFF7A4A8F.toInt(),
    )

    fun colorFor(id: String): Int = palette[(id.hashCode() and 0x7fffffff) % palette.size]
    fun tint(color: Int, alpha: Int = 0x2E): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

object Ui {
    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun rounded(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx.toFloat() }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** A chat bubble with a smaller corner on the side the message comes from. */
    fun bubble(color: Int, mine: Boolean, big: Int, small: Int): GradientDrawable {
        val b = big.toFloat(); val s = small.toFloat()
        // order: top-left, top-right, bottom-right, bottom-left (x,y pairs)
        val radii = if (mine) floatArrayOf(b, b, b, b, s, s, b, b) else floatArrayOf(b, b, b, b, b, b, s, s)
        return GradientDrawable().apply { setColor(color); cornerRadii = radii }
    }
}
