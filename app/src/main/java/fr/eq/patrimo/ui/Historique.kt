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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.withStyle
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
                    Text(p.investi?.let { Portfolio.signe(p.valeur - it) } ?: "—", color = if (p.investi != null && p.valeur < p.investi) C.Baisse else C.PlusValue, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
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
 * Aires empilées sur une seule échelle : investi (gris) en bas, plus-value (bleu) ajoutée par-dessus jusqu'à la valeur.
 * Une moins-value apparaît en rouge sous la ligne d'investi. Toucher ou glisser affiche les montants du jour.
 */
@Composable
fun CourbeDouble(serie: List<fr.eq.patrimo.data.PointHistorique>, modifier: Modifier = Modifier) {
    var sel by remember(serie) { mutableStateOf<Int?>(null) }
    val aInv = serie.count { it.investi != null } >= 2
    val f = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRANCE)
    Column(modifier) {
        // Montants du point lu (ou du dernier point) : investi en gris, plus-value en bleu
        val i = sel ?: serie.lastIndex
        val p = serie.getOrNull(i)
        if (p != null) Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = if (sel != null) C.Or else C.Discret)) {
                append(if (sel != null) LocalDate.ofEpochDay(p.epochDay).format(f) + " · " else "Dernier relevé · ")
            }
            append(Portfolio.eur(p.valeur))
            p.investi?.let { inv ->
                withStyle(androidx.compose.ui.text.SpanStyle(color = C.Investi)) { append("  ·  investi " + Portfolio.eur(inv)) }
                withStyle(androidx.compose.ui.text.SpanStyle(color = if (p.valeur >= inv) C.PlusValue else C.Baisse, fontWeight = FontWeight.SemiBold)) {
                    append("  ·  PV " + Portfolio.signe(p.valeur - inv))
                }
            }
        }, fontSize = 12.sp, maxLines = 2)
        if (aInv) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.padding(end = 4.dp).height(10.dp).width(10.dp).background(C.Investi, RoundedCornerShape(2.dp)))
            Text("Investi", color = C.Investi, fontSize = 11.sp)
            androidx.compose.foundation.layout.Box(Modifier.padding(start = 12.dp, end = 4.dp).height(10.dp).width(10.dp).background(C.PlusValue, RoundedCornerShape(2.dp)))
            Text("Plus-value", color = C.PlusValue, fontSize = 11.sp)
            androidx.compose.foundation.layout.Box(Modifier.padding(start = 12.dp, end = 4.dp).height(10.dp).width(10.dp).background(C.Baisse.copy(alpha = .6f), RoundedCornerShape(2.dp)))
            Text("Moins-value", color = C.Baisse, fontSize = 11.sp)
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp)
            .pointerInput(serie) {
                fun idx(x: Float) = if (serie.size < 2) null else ((x / size.width) * (serie.size - 1)).toInt().coerceIn(0, serie.lastIndex)
                detectTapGestures { o -> sel = if (sel != null && sel == idx(o.x)) null else idx(o.x) }
            }
            .pointerInput(serie) {
                fun idx(x: Float) = if (serie.size < 2) null else ((x / size.width) * (serie.size - 1)).toInt().coerceIn(0, serie.lastIndex)
                detectHorizontalDragGestures(onDragStart = { o -> sel = idx(o.x) }) { c, _ -> sel = idx(c.position.x) }
            }) {
            val max = serie.maxOf { maxOf(it.valeur, it.investi ?: 0.0) } * 1.05
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (serie.size < 2 || max <= 0) return@Canvas
                fun x(k: Int) = k.toFloat() / (serie.size - 1) * size.width
                fun y(v: Double) = (size.height - v / max * size.height).toFloat()
                fun aire(haut: (Int) -> Double, bas: (Int) -> Double) = Path().apply {
                    serie.indices.forEach { k -> if (k == 0) moveTo(x(k), y(haut(k))) else lineTo(x(k), y(haut(k))) }
                    serie.indices.reversed().forEach { k -> lineTo(x(k), y(bas(k))) }
                    close()
                }
                if (aInv) {
                    val inv = { k: Int -> serie[k].investi ?: serie[k].valeur }
                    val v = { k: Int -> serie[k].valeur }
                    drawPath(aire({ k -> minOf(inv(k), v(k)) }, { 0.0 }), C.Investi.copy(alpha = .55f))
                    drawPath(aire({ k -> maxOf(inv(k), v(k)) }, inv), C.PlusValue.copy(alpha = .75f))
                    drawPath(aire(inv, { k -> minOf(inv(k), v(k)) }), C.Baisse.copy(alpha = .45f))
                    val ligneInv = Path().apply { serie.indices.forEach { k -> if (k == 0) moveTo(x(k), y(inv(k))) else lineTo(x(k), y(inv(k))) } }
                    drawPath(ligneInv, C.Investi, style = Stroke(1.2.dp.toPx()))
                } else {
                    drawPath(aire({ k -> serie[k].valeur }, { 0.0 }), C.Or.copy(alpha = .35f))
                }
                val ligne = Path().apply { serie.forEachIndexed { k, q -> if (k == 0) moveTo(x(k), y(q.valeur)) else lineTo(x(k), y(q.valeur)) } }
                drawPath(ligne, if (aInv) C.PlusValue else C.Or, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
                sel?.let { k ->
                    drawLine(C.Texte.copy(alpha = .6f), Offset(x(k), 0f), Offset(x(k), size.height), 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                    drawCircle(C.Texte, 4.dp.toPx(), Offset(x(k), y(serie[k].valeur)))
                    serie[k].investi?.let { drawCircle(C.Investi, 3.5.dp.toPx(), Offset(x(k), y(it))) }
                }
            }
            Text(Portfolio.eur(max / 1.05), color = C.Discret, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart))
            Text("0 €", color = C.Discret, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}
