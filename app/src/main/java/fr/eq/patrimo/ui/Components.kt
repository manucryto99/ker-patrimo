package fr.eq.patrimo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.eq.patrimo.domain.PointCourbe

object C {
    val Fond = Color(0xFF0D0F14)
    val Carte = Color(0xFF161A22)
    val Carte2 = Color(0xFF1D222C)
    val Ligne = Color(0xFF252B36)
    val Texte = Color(0xFFEEF1F6)
    val Discret = Color(0xFF8A92A1)
    val Or = Color(0xFFD9B46A)
    val Orange = Color(0xFFF2994A)
    val Parts = Color(0xFF7FB8E6)
    val Investi = Color(0xFF8A929E)    // gris : montant investi
    val PlusValue = Color(0xFF5DADE2)  // courbe de plus-value (échelle secondaire)   // bleu clair : nombre de parts
    val OrDoux = Color(0xFF2A2418)
    val Hausse = Color(0xFF3CCF91)
    val Baisse = Color(0xFFFF6B5E)
    val Palette = listOf(Or, Color(0xFF6AA8D9), Color(0xFF8FD3A8), Color(0xFFC99AE0), Color(0xFFE08F7A), Color(0xFF9AA3B2))
    fun signe(v: Double) = if (v >= 0) Hausse else Baisse
}

@Composable
fun PatrimoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Or, onPrimary = Color.Black, background = C.Fond, onBackground = C.Texte,
            surface = C.Carte, onSurface = C.Texte, surfaceVariant = C.Carte2, onSurfaceVariant = C.Discret,
            secondaryContainer = C.OrDoux, onSecondaryContainer = C.Or, outline = C.Ligne
        ),
        content = content
    )
}

@Composable
fun Carte(modifier: Modifier = Modifier, titre: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(C.Carte, RoundedCornerShape(18.dp))
            .border(1.dp, C.Ligne, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        titre?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp)) }
        content()
    }
}

/** Courbe dorée avec dégradé, façon appli patrimoniale. */
@Composable
fun CourbeChart(points: List<PointCourbe>, modifier: Modifier = Modifier, selection: Int? = null) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val vs = points.map { it.valeur }
        val mn = vs.min(); val mx = vs.max()
        val pad = ((mx - mn) * 0.12).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = i.toFloat() / (points.size - 1) * size.width
        fun y(v: Double) = (size.height - (v - mn + pad) / (mx - mn + 2 * pad) * size.height).toFloat()
        val ligne = Path().apply {
            moveTo(x(0), y(vs[0]))
            for (i in 1 until vs.size) lineTo(x(i), y(vs[i]))
        }
        val aire = Path().apply {
            addPath(ligne); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(aire, Brush.verticalGradient(listOf(C.Or.copy(alpha = .35f), Color.Transparent)))
        drawPath(ligne, C.Or, style = Stroke(width = 2.2.dp.toPx(), join = StrokeJoin.Round))
        drawCircle(C.Or, 4.5.dp.toPx(), Offset(x(vs.lastIndex), y(vs.last())))
        // Point lu au doigt : trait vertical pointillé et point
        selection?.takeIf { it in vs.indices }?.let { i ->
            drawLine(C.Discret, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            drawCircle(Color.White, 5.dp.toPx(), Offset(x(i), y(vs[i])))
            drawCircle(C.Or, 3.5.dp.toPx(), Offset(x(i), y(vs[i])))
        }
    }
}

/** Anneau de répartition. */
@Composable
fun Anneau(parts: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
    Canvas(modifier) {
            val ep = 16.dp.toPx()
            var debut = -90f
            parts.forEachIndexed { i, (_, v) ->
                val angle = (v / total * 360).toFloat()
                drawArc(C.Palette[i % C.Palette.size], debut, angle - 1.2f, false,
                    topLeft = Offset(ep / 2, ep / 2), size = Size(size.width - ep, size.height - ep), style = Stroke(ep))
                debut += angle
            }
    }
}
