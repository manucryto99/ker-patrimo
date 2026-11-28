package fr.eq.patrimo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eq.patrimo.domain.PointCourbe
import fr.eq.patrimo.domain.Portfolio
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Onglet « Historique » : valeur jour par jour de chaque portefeuille (import + relevé quotidien automatique). */
@Composable
fun Historique(vm: PortfolioViewModel) {
    val histo by vm.historique.collectAsStateWithLifecycle()
    val progression by vm.progression.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importerHistorique) }
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    // Menu à deux niveaux : Tout / catégories, puis contrats de la catégorie choisie
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var contratSel by rememberSaveable { mutableStateOf<String?>(null) }
    val choix = contratSel ?: cat ?: "TOTAL"
    var duree by rememberSaveable { mutableStateOf("1A") }
    var confirmer by remember { mutableStateOf(false) }
    val presents = histo.map { it.portefeuille }.toSet()
    val categories = contrats.map { it.enveloppe }.distinct().filter { it in presents }
    val contratsDe = contrats.filter { it.enveloppe == cat && it.contrat in presents }.map { it.contrat }.distinct()
    val serieComplete = histo.filter { it.portefeuille == choix }.sortedBy { it.epochDay }
    val auj = LocalDate.now().toEpochDay()
    val jours = mapOf("1M" to 31L, "3M" to 91L, "6M" to 183L, "1A" to 366L, "5A" to 1827L, "Tout" to Long.MAX_VALUE / 4)
    val serie = serieComplete.filter { it.epochDay >= auj - (jours[duree] ?: 366L) }
    val f = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Historique de valeur", fontWeight = FontWeight.SemiBold)
                Text(if (histo.isEmpty()) "Aucune donnée pour l'instant" else "${histo.size} relevés · depuis le ${LocalDate.ofEpochDay(histo.minOf { it.epochDay }).format(f)}",
                    color = C.Discret, fontSize = 11.sp)
            }
            OutlinedButton(enabled = progression == null, onClick = { launcher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(12.dp)) { Text("Importer", fontSize = 12.sp) }
        }
        if (histo.isEmpty()) {
            Text("Importe ton historique (Excel ou CSV : une colonne Date puis la valeur de chaque portefeuille). " +
                "Ensuite, l'appli relève chaque jour la valeur du total, de chaque catégorie et de chaque contrat.", color = C.Discret, fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp)) {
            item { FilterChip(selected = cat == null, onClick = { cat = null; contratSel = null }, label = { Text("Tout") }) }
            items(categories) { c -> FilterChip(selected = c == cat, onClick = { cat = c; contratSel = null }, label = { Text(c) }) }
        }
        if (cat != null && contratsDe.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 2.dp)) {
            item { FilterChip(selected = contratSel == null, onClick = { contratSel = null }, label = { Text("Tous", fontSize = 12.sp) }, modifier = Modifier.height(30.dp)) }
            items(contratsDe) { c -> FilterChip(selected = c == contratSel, onClick = { contratSel = c }, label = { Text(c, fontSize = 12.sp) }, modifier = Modifier.height(30.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            jours.keys.forEach { d -> FilterChip(selected = d == duree, onClick = { duree = d }, label = { Text(d, fontSize = 12.sp) }, modifier = Modifier.height(30.dp)) }
        }
        if (serie.size >= 2) {
            val dv = serie.last().valeur - serie.first().valeur
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(Portfolio.eur(serie.last().valeur), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("  ${Portfolio.signe(dv)} (${Portfolio.pct(if (serie.first().valeur > 0) dv / serie.first().valeur else 0.0)})",
                    color = C.signe(dv), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp))
            }
            CourbeDouble(serie, Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(LocalDate.ofEpochDay(serie.first().epochDay).format(f), color = C.Discret, fontSize = 11.sp)
                Spacer(Modifier.weight(1f)); Text(LocalDate.ofEpochDay(serie.last().epochDay).format(f), color = C.Discret, fontSize = 11.sp)
            }
        } else Text("Pas assez de relevés sur cette durée.", color = C.Discret, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp))
        // Tableau des relevés, du plus récent au plus ancien
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
            Text("Date", color = C.Discret, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Valeur", color = C.Discret, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Plus-value", color = C.Discret, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Var. jour", color = C.Discret, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f)) {
            val desc = serie.reversed()
            items(desc.size) { i ->
                val p = desc[i]; val avant = desc.getOrNull(i + 1)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(LocalDate.ofEpochDay(p.epochDay).format(f) + if (p.source == "auto") " •" else "", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(Portfolio.eur(p.valeur), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text(p.investi?.let { Portfolio.signe(p.valeur - it) } ?: "—", color = C.PlusValue, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    val d = avant?.let { p.valeur - it.valeur }
                    Text(d?.let { Portfolio.signe(it) } ?: "", color = d?.let { C.signe(it) } ?: C.Discret, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
            item {
                Text("• relevé automatique de l'appli · sans point : valeur importée", color = C.Discret, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { if (confirmer) { vm.viderHistoriqueImporte(); confirmer = false } else confirmer = true }) {
                    Text(if (confirmer) "Confirmer : effacer les valeurs importées" else "Effacer l'historique importé", color = C.Discret, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Valeur (doré, échelle de gauche) et plus-value = valeur − investi (bleu, échelle de droite).
 * Toucher ou glisser affiche la date, la valeur et la plus-value du jour.
 */
@Composable
fun CourbeDouble(serie: List<fr.eq.patrimo.data.PointHistorique>, modifier: Modifier = Modifier) {
    var sel by remember(serie) { mutableStateOf<Int?>(null) }
    val pv = serie.map { p -> p.investi?.let { p.valeur - it } }
    val aPv = pv.count { it != null } >= 2
    val f = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRANCE)
    Column(modifier) {
        val p = sel?.let { serie.getOrNull(it) }
        Text(if (p == null) "Touchez la courbe pour lire une valeur" else
            LocalDate.ofEpochDay(p.epochDay).format(f) + " · " + Portfolio.eur(p.valeur) + (pv[sel!!]?.let { "  · PV " + Portfolio.signe(it) } ?: ""),
            color = if (p == null) C.Discret else C.Or, fontSize = 12.sp, fontWeight = if (p == null) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("— Valeur", color = C.Or, fontSize = 11.sp)
            if (aPv) Text("   — Plus-value (échelle de droite)", color = C.PlusValue, fontSize = 11.sp)
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(210.dp).padding(vertical = 8.dp)
            .pointerInput(serie) {
                fun i(x: Float) = if (serie.size < 2) null else ((x / size.width) * (serie.size - 1)).toInt().coerceIn(0, serie.lastIndex)
                detectTapGestures { o -> sel = if (sel != null && sel == i(o.x)) null else i(o.x) }
            }
            .pointerInput(serie) {
                fun i(x: Float) = if (serie.size < 2) null else ((x / size.width) * (serie.size - 1)).toInt().coerceIn(0, serie.lastIndex)
                detectHorizontalDragGestures(onDragStart = { o -> sel = i(o.x) }) { c, _ -> sel = i(c.position.x) }
            }) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (serie.size < 2) return@Canvas
                fun x(i: Int) = i.toFloat() / (serie.size - 1) * size.width
                fun echelle(vs: List<Double>): (Double) -> Float {
                    val mn = vs.min(); val mx = vs.max(); val pad = ((mx - mn) * 0.1).takeIf { it > 0 } ?: 1.0
                    return { v -> (size.height - (v - mn + pad) / (mx - mn + 2 * pad) * size.height).toFloat() }
                }
                val yV = echelle(serie.map { it.valeur })
                val ligne = Path().apply { serie.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), yV(p.valeur)) else lineTo(x(i), yV(p.valeur)) } }
                val aire = Path().apply { addPath(ligne); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(aire, Brush.verticalGradient(listOf(C.Or.copy(alpha = .28f), androidx.compose.ui.graphics.Color.Transparent)))
                drawPath(ligne, C.Or, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                if (aPv) {
                    val yP = echelle(pv.filterNotNull())
                    val lp = Path(); var debut = true
                    pv.forEachIndexed { i, v -> if (v != null) { if (debut) lp.moveTo(x(i), yP(v)) else lp.lineTo(x(i), yP(v)); debut = false } }
                    drawPath(lp, C.PlusValue, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
                    // zéro de la plus-value, si visible
                    val z = yP(0.0); if (z in 0f..size.height) drawLine(C.PlusValue.copy(alpha = .35f), Offset(0f, z), Offset(size.width, z), 1.dp.toPx())
                }
                sel?.let { i ->
                    drawLine(C.Discret, Offset(x(i), 0f), Offset(x(i), size.height), 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                    drawCircle(C.Or, 4.dp.toPx(), Offset(x(i), yV(serie[i].valeur)))
                    if (aPv) pv[i]?.let { v -> drawCircle(C.PlusValue, 4.dp.toPx(), Offset(x(i), echelle(pv.filterNotNull())(v))) }
                }
            }
            // Bornes des deux échelles
            val vs = serie.map { it.valeur }
            Text(Portfolio.eur(vs.max()), color = C.Or.copy(alpha = .8f), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart))
            Text(Portfolio.eur(vs.min()), color = C.Or.copy(alpha = .8f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart))
            if (aPv) {
                val ps = pv.filterNotNull()
                Text(Portfolio.signe(ps.max()), color = C.PlusValue, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd))
                Text(Portfolio.signe(ps.min()), color = C.PlusValue, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}
