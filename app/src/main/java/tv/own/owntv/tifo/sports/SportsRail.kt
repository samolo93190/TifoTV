package tv.own.owntv.tifo.sports

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Sports entry of the rail. Core's MainSection is a closed enum shared with OwnTV's phone app,
 * so Sports is not a section: the shell keeps its own "Sports is open" flag and the rail draws
 * this item above More.
 */
data class SportsRailItem(val active: Boolean, val onClick: () -> Unit)

/** A football in the rail's line style: a circle, a centre pentagon and its five seams. */
@Composable
fun SportsGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(tint, radius = 9.5f * unit, center = c, style = stroke)

        val inner = 3.6f * unit
        val outer = 9.5f * unit
        val pentagon = Path()
        for (i in 0 until 5) {
            val angle = Math.toRadians(-90.0 + i * 72.0)
            val p = Offset(c.x + inner * kotlin.math.cos(angle).toFloat(), c.y + inner * kotlin.math.sin(angle).toFloat())
            if (i == 0) pentagon.moveTo(p.x, p.y) else pentagon.lineTo(p.x, p.y)
            val edge = Offset(c.x + outer * kotlin.math.cos(angle).toFloat(), c.y + outer * kotlin.math.sin(angle).toFloat())
            drawLine(tint, p, edge, strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        pentagon.close()
        drawPath(pentagon, tint, style = stroke)
    }
}
