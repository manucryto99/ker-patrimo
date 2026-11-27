package fr.eq.patrimo.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import fr.eq.patrimo.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedButton
import fr.eq.patrimo.assistant.Etat
import fr.eq.patrimo.data.Position
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eq.patrimo.domain.Ligne
import fr.eq.patrimo.domain.Niveau
import fr.eq.patrimo.domain.Periode
import fr.eq.patrimo.domain.Portfolio
import fr.eq.patrimo.work.RefreshWorker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: PortfolioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.planifier(this)
        setContent { PatrimoTheme { Box(Modifier.fillMaxSize()) { App(vm); PageAccueil(montrer = savedInstanceState == null) } } }
    }
}

// Tous les fichiers : certains téléphones ne reconnaissent pas le type .xlsm et le grisent
private val XLSX_TYPES = arrayOf("*/*")

@Composable
fun App(vm: PortfolioViewModel) {
    var onglet by rememberSaveable { mutableIntStateOf(0) }
    var reglages by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importer) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(info) { info?.let { snack.showSnackbar(it); vm.clearInfo() } }
    if (reglages) { ReglagesEcran(vm, snack) { reglages = false }; return }

    Scaffold(
        containerColor = C.Fond,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ker ", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = C.Or)
                Text("Patrimo", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                // Médaillon doré de l'enfant, à côté du nom
                Box(Modifier.padding(start = 10.dp).size(38.dp)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFF3DFA6), Color(0xFFB8913F))), CircleShape)
                    .padding(2.5.dp).clip(CircleShape)) {
                    Image(painterResource(R.drawable.medaillon_enfant), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Or)
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser les cours") }
                IconButton(onClick = { reglages = true }) { Icon(Icons.Default.Settings, "Réglages") }
                Button(onClick = { importLauncher.launch(XLSX_TYPES) }, shape = RoundedCornerShape(12.dp)) { Text("Importer") }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = C.Carte) {
                listOf(
                    Triple("Synthèse", Icons.Default.Home, 0), Triple("Titres", Icons.AutoMirrored.Filled.List, 1),
                    Triple("Surveiller", Icons.Default.Star, 4), Triple("Fonds", Icons.Default.Search, 5),
                    Triple("Assistant", Icons.Default.Person, 2), Triple("Alertes", Icons.Default.Notifications, 3)
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = onglet == i, onClick = { onglet = i }, icon = { Icon(icon, label) }, label = { Text(label, fontSize = 10.sp, maxLines = 1) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (onglet) {
                0 -> Accueil(vm)
                1 -> Placements(vm)
                2 -> Discussion(vm)
                4 -> Surveiller(vm)
                5 -> Catalogue(vm) { onglet = 2 }
                else -> Alertes(vm)
            }
        }
    }
}

@Composable
fun Accueil(vm: PortfolioViewModel) {
    val toutes by vm.lignes.collectAsStateWithLifecycle()
    val courbe by vm.courbe.collectAsStateWithLifecycle()
    val periode by vm.periode.collectAsStateWithLifecycle()
    val categorie by vm.categorie.collectAsStateWithLifecycle()
    val comptes by vm.comptes.collectAsStateWithLifecycle()
    val vars by vm.variations.collectAsStateWithLifecycle()
    val horizon by vm.horizon.collectAsStateWithLifecycle()
    if (toutes.isEmpty()) { Vide(); return }
    // Tableau de bord par catégorie : Tout / Assurance vie / PEA / CTO / Crypto
    val categories = Portfolio.parEnveloppe(toutes).map { it.first }
    val lignesCat = toutes.filter { categorie == null || it.p.enveloppe == categorie }
    // Contrats cochés : tous les indicateurs ci-dessous ne portent que sur eux
    val lignes = lignesCat.filter { comptes.isEmpty() || it.p.contrat in comptes }
    val total = lignes.sumOf { it.valeur }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        MenuCategories(vm, toutes)
        Text(when {
            categorie == null -> "Patrimoine financier"
            comptes.isEmpty() -> categorie!!
            else -> "$categorie · ${comptes.joinToString()}"
        }, color = C.Discret, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        val investi = lignes.sumOf { it.investi }; val gain = lignes.sumOf { it.gain }
        Text(Portfolio.eur(total), fontSize = 38.sp, fontWeight = FontWeight.Bold)
        // Gros indicateur de variation : jour / 1 semaine / 1 mois
        run {
            val (dv, pv) = Portfolio.variation(lignes, vars, horizon)
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().background(C.Carte, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fr.eq.patrimo.domain.Horizon.entries.forEach { h ->
                        FilterChip(selected = h == horizon, onClick = { vm.setHorizon(h) }, label = { Text(h.label, fontSize = 12.sp) }, modifier = Modifier.height(30.dp))
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text((if (dv >= 0) "▲ " else "▼ ") + Portfolio.signe(dv), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.signe(dv))
                    Text("  " + Portfolio.pct(pv), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.signe(dv), modifier = Modifier.padding(bottom = 3.dp))
                }
                Text(when (horizon) {
                    fr.eq.patrimo.domain.Horizon.JOUR -> "Depuis la clôture de la veille"
                    fr.eq.patrimo.domain.Horizon.SEMAINE -> "Sur 7 jours"
                    fr.eq.patrimo.domain.Horizon.MOIS -> "Sur 30 jours"
                } + " · lignes cotées (fonds euros et cash comptés stables)", color = C.Discret, fontSize = 11.sp)
            }
        }
        Text("Investi ${Portfolio.eur(investi)} · plus-value ${Portfolio.signe(gain)} (${Portfolio.pct(if (investi > 0) gain / investi else 0.0)})",
            color = C.Discret, fontSize = 13.sp)
        if (courbe.size >= 2) {
            val d = courbe.last().valeur - courbe.first().valeur
            Text("${Portfolio.signe(d)} (${Portfolio.pct(d / courbe.first().valeur)}) sur ${periode.label}", color = C.signe(d), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        CourbeChart(courbe, Modifier.fillMaxWidth().height(200.dp).padding(vertical = 12.dp))
        if (courbe.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            val f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)
            val debut = courbe.first().epochSec
            if (debut != null) {
                val h = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.FRANCE)
                Text(java.time.Instant.ofEpochSecond(debut).atZone(java.time.ZoneId.systemDefault()).format(h), color = C.Discret, fontSize = 11.sp)
                Spacer(Modifier.weight(1f)); Text("Maintenant", color = C.Discret, fontSize = 11.sp)
            } else {
                Text(LocalDate.ofEpochDay(courbe.first().epochDay).format(f), color = C.Discret, fontSize = 11.sp)
                Spacer(Modifier.weight(1f)); Text("Aujourd'hui", color = C.Discret, fontSize = 11.sp)
            }
        }
        if (periode.intraday && courbe.size < 2) Text("Cours intraday indisponibles pour l'instant (hors ligne ?)", color = C.Discret, fontSize = 12.sp)
        Row(Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Periode.entries.forEach { p -> FilterChip(selected = p == periode, onClick = { vm.setPeriode(p) }, label = { Text(p.label) }) }
        }
        Text("Courbe à composition constante (positions actuelles)", color = C.Discret, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
        // Tout : répartition par catégorie ; une catégorie : répartition par contrat / compte
        val env = if (categorie == null) Portfolio.parEnveloppe(lignes)
            else if (comptes.size == 1) lignes.groupBy { it.p.type }.mapValues { (_, v) -> v.sumOf { it.valeur } }.toList().sortedByDescending { it.second }
            else lignes.groupBy { it.p.contrat }.mapValues { (_, v) -> v.sumOf { it.valeur } }.toList().sortedByDescending { it.second }
        Carte(titre = when { categorie == null -> "Répartition par catégorie"; comptes.size == 1 -> "Répartition par type de support"; else -> "Répartition par contrat" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Anneau(env, Modifier.size(120.dp))
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    env.forEachIndexed { i, (nom, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(C.Palette[i % C.Palette.size], RoundedCornerShape(3.dp)))
                            Text(nom, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                            Text(Portfolio.pctAbs(v / total, 0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        val fFonds = lignes.sumOf { it.fraisFondsAn }; val fContrat = lignes.sumOf { it.fraisContratAn }; val fGarde = lignes.sumOf { it.gardeAn }
        Carte(titre = "Frais annuels : ${Portfolio.eur(fFonds + fContrat + fGarde)} (${Portfolio.pctAbs((fFonds + fContrat + fGarde) / total, 2)})") {
            LigneFrais("Frais des fonds et ETF", fFonds, "déjà inclus dans les cours")
            LigneFrais("Frais des contrats d'assurance vie", fContrat, "prélevés en nombre de parts")
            LigneFrais("Droits de garde PEA / compte-titres", fGarde, "débités sur les espèces")
            Text("Gains affichés nets de ces trois frais.", color = C.Discret, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        val tri = lignes.filter { it.estCote }.sortedByDescending { it.perf }
        // Plus et moins-values par portefeuille (dès que plusieurs contrats sont affichés)
        val parContrat = lignes.groupBy { it.p.contrat }.map { (c, l) -> c to l.sumOf { it.gain } }.sortedByDescending { it.second }
        if (parContrat.size > 1) Carte(titre = "Plus et moins-values par portefeuille") {
            val maxAbs = parContrat.maxOf { kotlin.math.abs(it.second) }.takeIf { it > 0 } ?: 1.0
            parContrat.forEach { (c, g) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.9f))
                    // barre divergente : pertes à gauche de l'axe, gains à droite
                    Row(Modifier.weight(1.6f).height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val f = (kotlin.math.abs(g) / maxAbs).toFloat().coerceIn(0.02f, 1f)
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                            if (g < 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(C.Baisse, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(C.Discret))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            if (g >= 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(C.Hausse, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                        }
                    }
                    Text(Portfolio.signe(g), color = C.signe(g), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                }
            }
        }
        if (tri.isNotEmpty()) Carte(titre = "Meilleures et pires lignes") {
            (tri.take(2) + tri.takeLast(2)).distinct().forEach { LigneResume(it, vars[it.p.id]) }
        }
    }
}

@Composable
fun LigneFrais(titre: String, montant: Double, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titre, fontSize = 13.5.sp)
            Text(detail, color = C.Discret, fontSize = 11.5.sp)
        }
        Text(Portfolio.eur(montant), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun LigneResume(l: Ligne, v: fr.eq.patrimo.domain.Variations? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(C.Carte2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(l.p.libelle.take(2).uppercase(), color = C.Or, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(l.p.libelle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${l.p.enveloppe} · ${l.p.isin ?: l.p.type}", color = C.Discret, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Portfolio.eur(l.valeur), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            // gain cumulé depuis l'achat, en € et en %
            Text(Portfolio.signe(l.gain) + "  " + Portfolio.pct(l.perf), color = C.signe(l.gain), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (v != null) VariationsLigne(v, avec5ans = true)
    }
}

private enum class Tri(val label: String) { INVESTI("Investi"), VALEUR("Valeur"), GAIN("+/- €"), PERF("+/- %") }

/** Menu à deux étages : catégories (Tout, Assurance vie, CTO…) puis contrats de la catégorie (sélection multiple). */
@Composable
private fun MenuCategories(vm: PortfolioViewModel, toutes: List<fr.eq.patrimo.domain.Ligne>) {
    val categorie by vm.categorie.collectAsStateWithLifecycle()
    val comptes by vm.comptes.collectAsStateWithLifecycle()
    val categories = Portfolio.parEnveloppe(toutes).map { it.first }
    val lignesCat = toutes.filter { categorie == null || it.p.enveloppe == categorie }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp)) {
        item { FilterChip(selected = categorie == null, onClick = { vm.setCategorie(null) }, label = { Text("Tout") }) }
        items(categories) { c -> FilterChip(selected = c == categorie, onClick = { vm.setCategorie(c) }, label = { Text(c) }) }
    }
    // Liste cliquable des contrats de la catégorie (une ou plusieurs sélections)
    if (categorie != null) {
        val parContrat = lignesCat.groupBy { it.p.contrat }.map { (c, l) -> Triple(c, l.sumOf { it.valeur }, l.sumOf { it.investi } to l.sumOf { it.gain }) }
            .sortedByDescending { it.second }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 2.dp)) {
            item {
                FilterChip(selected = comptes.isEmpty(), onClick = { vm.tousLesComptes() }, label = { Text("Tous", fontSize = 12.sp) },
                    modifier = Modifier.height(30.dp))
            }
            items(parContrat) { (nom, _, ig) ->
                val (inv, g) = ig
                FilterChip(selected = nom in comptes, onClick = { vm.basculerCompte(nom) }, modifier = Modifier.height(30.dp), label = {
                    Text(nom, fontSize = 12.sp, maxLines = 1)
                    Text(" " + Portfolio.pct(if (inv > 0) g / inv else 0.0), fontSize = 11.sp, color = C.signe(g), maxLines = 1)
                })
            }
        }
    }
}

@Composable
fun Placements(vm: PortfolioViewModel) {
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    val vars by vm.variations.collectAsStateWithLifecycle()
    val categorie by vm.categorie.collectAsStateWithLifecycle()
    val comptes by vm.comptes.collectAsStateWithLifecycle()
    var tri by remember { mutableStateOf(Tri.VALEUR) }
    var desc by remember { mutableStateOf(true) }
    // Fiche ouverte : null = fermée ; Position(id = 0) = nouvelle ligne
    var fiche by remember { mutableStateOf<Position?>(null) }
    var nouvelle by remember { mutableStateOf(false) }
    if (fiche != null || nouvelle) FicheLigne(fiche, contrats, false, vm::enregistrerPosition, vm::supprimerPosition, vm::appliquer) { fiche = null; nouvelle = false }
    if (lignes.isEmpty()) { Vide(); return }
    val vues = lignes.filter { (categorie == null || it.p.enveloppe == categorie) && (comptes.isEmpty() || it.p.contrat in comptes) }.sortedBy {
        when (tri) { Tri.INVESTI -> it.investi; Tri.VALEUR -> it.valeur; Tri.GAIN -> it.gain; Tri.PERF -> it.perf }
    }.let { if (desc) it.reversed() else it }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(categorie ?: "Tous les titres", comptes.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · "),
                color = C.Discret, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { nouvelle = true }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null); Text(" Ajouter") }
        }
        MenuCategories(vm, lignes)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Titre", color = C.Discret, fontSize = 12.sp, modifier = Modifier.weight(1.6f))
            Tri.entries.forEach { t ->
                Text(t.label + if (t == tri) (if (desc) " ↓" else " ↑") else "", color = if (t == tri) C.Or else C.Discret, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f).clickable { if (tri == t) desc = !desc else { tri = t; desc = true } })
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vues, key = { it.p.id }) { l ->
                Column(Modifier.fillMaxWidth().clickable { fiche = l.p }.padding(vertical = 8.dp)) {
                // Nom du fonds sur toute la largeur de l'écran
                Text(l.p.libelle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.6f)) {
                        Text(l.p.enveloppe + (l.p.source?.let { " · $it" } ?: ""), color = C.Discret, fontSize = 11.sp)
                        if (l.fraisTotauxAn > 0.5) Text("frais ${Portfolio.eur(l.fraisTotauxAn)}/an" +
                            (if (l.partsPerdues > 0.0005) " · ${String.format(Locale.FRANCE, "%.3f", l.parts)} parts est." else ""),
                            color = C.Or, fontSize = 11.sp)
                    }
                    Cellule(Portfolio.eur(l.investi)); Cellule(Portfolio.eur(l.valeur))
                    Cellule(Portfolio.signe(l.gain), C.signe(l.gain)); Cellule(Portfolio.pct(l.perf), C.signe(l.gain))
                }
                vars[l.p.id]?.let { v -> VariationsLigne(v) }
                }
            }
            item {
                val inv = vues.sumOf { it.investi }; val v = vues.sumOf { it.valeur }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("Total", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1.6f))
                    Cellule(Portfolio.eur(inv), bold = true); Cellule(Portfolio.eur(v), bold = true)
                    Cellule(Portfolio.signe(v - inv), C.signe(v - inv), true)
                    Cellule(Portfolio.pct(if (inv > 0) (v - inv) / inv else 0.0), C.signe(v - inv), true)
                }
                // Variations du total : J, 1S, 1M, 3M, 6M, 1A, 5A (4 par ligne)
                val horizons = fr.eq.patrimo.domain.Variations(null, null, null).avec5ans.map { it.first }
                val labels = mapOf("J" to "Jour", "1S" to "1 sem.", "1M" to "1 mois", "3M" to "3 mois", "6M" to "6 mois", "1A" to "1 an", "5A" to "5 ans")
                var partielle = false
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    horizons.indices.chunked(4).forEach { groupe ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            groupe.forEach { i ->
                                val (d, p, couverture) = Portfolio.variationHorizon(vues, vars, i)
                                if (couverture in 0.01..0.95) partielle = true
                                Column(Modifier.weight(1f)) {
                                    Text(labels[horizons[i]] ?: horizons[i], color = C.Discret, fontSize = 11.sp)
                                    if (couverture < 0.01) Text("—", color = C.Discret, fontSize = 13.sp)
                                    else {
                                        Text(Portfolio.signe(d), color = C.signe(d), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(Portfolio.pct(p), color = C.signe(d), fontSize = 11.sp)
                                    }
                                }
                            }
                            repeat(4 - groupe.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (partielle) Text("Sur les longues durées, seuls les titres ayant un historique suffisant sont comptés (fonds euros et titres récents exclus).",
                        color = C.Discret, fontSize = 11.sp)
                }
            }
        }
    }
}

/** « J +0,4% · 1S −1,2% · 1M +3,1% · 3M · 6M · 1A » sur une ligne, pleine largeur, sous le titre. */
@Composable
private fun VariationsLigne(v: fr.eq.patrimo.domain.Variations, avec5ans: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        (if (avec5ans) v.avec5ans else v.toutes).forEach { (lab, x) ->
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = C.Discret)) { append("$lab ") }
                withStyle(SpanStyle(color = if (x == null) C.Discret else C.signe(x), fontWeight = FontWeight.SemiBold)) {
                    append(x?.let { (if (it >= 0) "+" else "−") + String.format(Locale.FRANCE, "%.1f", kotlin.math.abs(it) * 100) + "%" } ?: "—")
                }
            }, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cellule(t: String, couleur: androidx.compose.ui.graphics.Color = C.Texte, bold: Boolean = false) =
    Text(t, color = couleur, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 1,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))

@Composable
fun Discussion(vm: PortfolioViewModel) {
    val msgs by vm.chat.collectAsStateWithLifecycle()
    var saisie by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            itemsIndexed(msgs) { idx, m ->
                val moi = m.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (moi) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 320.dp).wrapBulle(moi)) {
                        Text(m.texte, color = if (moi) androidx.compose.ui.graphics.Color.Black else C.Texte, fontSize = 14.sp)
                        // Modifications proposées par l'assistant : rien n'est appliqué sans validation
                        m.descriptions.forEach { d -> Text("• $d", color = C.Or, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp)) }
                        when (m.etat) {
                            Etat.EN_ATTENTE -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.valider(idx) }, shape = RoundedCornerShape(10.dp)) { Text("Valider") }
                                OutlinedButton(onClick = { vm.annuler(idx) }, shape = RoundedCornerShape(10.dp)) { Text("Annuler") }
                            }
                            Etat.VALIDEE -> Text("Validé", color = C.Hausse, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            Etat.ANNULEE -> Text("Annulé", color = C.Discret, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            Etat.AUCUNE -> Unit
                        }
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Quelle est ma pire ligne ?", "Combien sur mon PEA ?", "Des suggestions ?", "Achat de 2 parts de Air Liquide à 170 sur PEA", "Surveille LVMH")) { s ->
                FilterChip(selected = false, onClick = { vm.poser(s) }, label = { Text(s) })
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(saisie, { saisie = it }, Modifier.weight(1f), placeholder = { Text("Posez votre question…") }, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { vm.poser(saisie); saisie = "" }, modifier = Modifier.background(C.Or, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = androidx.compose.ui.graphics.Color.Black)
            }
        }
    }
}

private fun Modifier.wrapBulle(moi: Boolean) =
    this.background(if (moi) C.Or else C.Carte, RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 10.dp)

@Composable
fun Surveiller(vm: PortfolioViewModel) {
    val liste by vm.surveilles.collectAsStateWithLifecycle()
    val perfs by vm.perfsSurveilles.collectAsStateWithLifecycle()
    val toutes by vm.positions.collectAsStateWithLifecycle()
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    var fiche by remember { mutableStateOf<Position?>(null) }
    var nouvelle by remember { mutableStateOf(false) }
    if (fiche != null || nouvelle) FicheLigne(fiche, contrats, true, vm::enregistrerPosition, vm::supprimerPosition, vm::appliquer) { fiche = null; nouvelle = false }
    LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text("Lignes soldées et idées d'investissement, hors patrimoine. Les cours sont suivis comme pour vos placements.",
                color = C.Discret, fontSize = 13.sp)
            OutlinedButton(onClick = { nouvelle = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                Icon(Icons.Default.Add, null); Text(" Ajouter un titre à surveiller")
            }
        }
        if (liste.isEmpty()) item { Text("Rien à surveiller pour l'instant.", color = C.Discret) }
        // Top 3 du jour, de la semaine et du mois
        if (perfs.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Top 3 jour", 1 to "Top 3 semaine", 2 to "Top 3 mois").forEach { (i, titre) ->
                    val top = liste.mapNotNull { p -> perfs[p.id]?.get(i)?.let { p to it } }.sortedByDescending { it.second }.take(3)
                    Column(Modifier.weight(1f).background(C.Carte, RoundedCornerShape(14.dp)).padding(10.dp)) {
                        Text(titre, color = C.Or, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (top.isEmpty()) Text("—", color = C.Discret, fontSize = 12.sp)
                        top.forEachIndexed { rang, (p, v) ->
                            Column(Modifier.padding(top = 6.dp).clickable { fiche = p }) {
                                Text("${rang + 1}. ${p.libelle}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Portfolio.pct(v), color = C.signe(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        items(liste, key = { it.id }) { p ->
            val varJour = p.dernierCours?.let { c -> p.coursVeille?.takeIf { it > 0 }?.let { c / it - 1 } }
            val ecartHaut = p.dernierCours?.let { c -> p.plusHaut52s?.takeIf { it > 0 }?.let { c / it - 1 } }
            Row(Modifier.fillMaxWidth().clickable { fiche = p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(C.Carte2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(p.libelle.take(2).uppercase(), color = C.Or, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.libelle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(p.isin, p.contrat.ifEmpty { null }).joinToString(" · ") +
                        (ecartHaut?.let { " · ${Portfolio.pct(it)} vs plus haut 1 an" } ?: ""), color = C.Discret, fontSize = 12.sp)
                    // Frais annuels en % : frais courants du fonds + frais de gestion du contrat (assurance vie)
                    val ct = contrats.firstOrNull { it.etablissement == p.etablissement && it.contrat == p.contrat }
                    val fFonds = p.fraisAnnuels ?: 0.0
                    val fContrat = if (p.enveloppe.contains("assurance", true)) ct?.fraisGestionUc ?: 0.0 else 0.0
                    if (fFonds + fContrat > 0) Text(
                        listOfNotNull(
                            fFonds.takeIf { it > 0 }?.let { "fonds ${Portfolio.pctAbs(it, 2)}" },
                            fContrat.takeIf { it > 0 }?.let { "contrat ${Portfolio.pctAbs(it, 2)}" }
                        ).joinToString(" + ") + (if (fFonds > 0 && fContrat > 0) " = ${Portfolio.pctAbs(fFonds + fContrat, 2)}" else "") + " /an",
                        color = C.Or, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(p.dernierCours?.let { Portfolio.eur(it, true) } ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    varJour?.let { Text(Portfolio.pct(it), color = C.signe(it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            // Où le fonds est disponible : vos contrats (ligne elle-même + autres lignes du même ISIN) et autres contrats saisis
            run {
                val chez = (listOf(p) + toutes.filter { o -> o.id != p.id && p.isin != null && o.isin.equals(p.isin, true) })
                    .mapNotNull { it.contrat.ifBlank { it.etablissement }.ifBlank { null } }.distinct()
                val vos = (contrats.map { it.contrat } + contrats.map { it.etablissement } + toutes.map { it.contrat }).filter { it.isNotBlank() }.distinct()
                fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
                val saisis = p.disponibilites?.split(',', ';', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val saisisVos = saisis.filter { s -> vos.any { v -> norm(v).contains(norm(s)) || norm(s).contains(norm(v)) } }
                val autres = saisis - saisisVos.toSet()
                val vosListe = (chez + saisisVos).distinctBy { norm(it) }
                if (p.type != "Crypto" && (vosListe.isNotEmpty() || autres.isNotEmpty())) Column(Modifier.padding(start = 50.dp, bottom = 4.dp)) {
                    if (vosListe.isNotEmpty()) Text("✓ Dans vos contrats : " + vosListe.joinToString(", "), color = C.Hausse, fontSize = 11.sp)
                    if (autres.isNotEmpty()) Text("Ailleurs : " + autres.joinToString(", "), color = C.Orange, fontSize = 11.sp)
                }
            }
            perfs[p.id]?.let { pf ->
                Row(Modifier.fillMaxWidth().padding(start = 50.dp, bottom = 8.dp)) {
                    fr.eq.patrimo.domain.Perfs.LABELS.forEachIndexed { i, lab ->
                        val v = pf[i]
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(lab, color = C.Discret, fontSize = 10.sp)
                            Text(v?.let { Portfolio.pct(it) } ?: "—", color = if (v == null) C.Discret else C.signe(v), fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Alertes(vm: PortfolioViewModel) {
    val insights by vm.insights.collectAsStateWithLifecycle()
    if (insights.isEmpty()) { Vide(); return }
    LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(insights) { i ->
            Carte {
                Row {
                    Box(Modifier.padding(top = 6.dp).size(10.dp).background(
                        when (i.niveau) { Niveau.ALERTE -> C.Baisse; Niveau.POSITIF -> C.Hausse; Niveau.ATTENTION -> C.Or; Niveau.INFO -> C.Discret }, CircleShape))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(i.titre, fontWeight = FontWeight.SemiBold)
                        Text(i.texte, color = C.Discret, fontSize = 13.5.sp)
                    }
                }
            }
        }
        item { Text("Constats automatiques, pas un conseil en investissement.", color = C.Discret, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
fun Vide() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Aucun placement", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Touchez « Importer » et choisissez votre classeur de suivi (.xlsm, onglet « portefeuille ») ou le modèle Ker Patrimo (.xlsx).", color = C.Discret, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Code d'accès de la page d'accueil (provisoire, en dur). */
private const val CODE_ACCES = "5522"

/** Page d'accueil plein écran (bandeau en portrait) : apparaît avec un flou progressif, ne s'ouvre qu'avec le code. */
@Composable
fun PageAccueil(montrer: Boolean) {
    var visible by rememberSaveable { mutableStateOf(montrer) }
    var saisie by rememberSaveable { mutableStateOf("") }
    var erreur by remember { mutableStateOf(false) }
    // Apparition : l'image passe de floue et transparente à nette
    val apparition = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { apparition.animateTo(1f, androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val secousse = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(erreur) {
        if (erreur) {
            repeat(3) { secousse.animateTo(14f, androidx.compose.animation.core.tween(45)); secousse.animateTo(-14f, androidx.compose.animation.core.tween(45)) }
            secousse.animateTo(0f, androidx.compose.animation.core.tween(45))
            saisie = ""
        }
    }
    var clavierCache by remember { mutableStateOf<androidx.compose.ui.platform.SoftwareKeyboardController?>(null) }
    fun taper(ch: String) {
        if (saisie.length >= 4) return
        erreur = false
        saisie += ch
        if (saisie.length == 4) { if (saisie == CODE_ACCES) { visible = false; clavierCache?.hide() } else erreur = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(0)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(500)) +
            androidx.compose.animation.scaleOut(targetScale = 1.06f, animationSpec = androidx.compose.animation.core.tween(500))
    ) {
        val t = apparition.value
        // Hauteur d'écran fixe : l'ouverture du clavier ne doit ni recadrer l'image ni déplacer les cases
        val hEcran = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        Box(Modifier.fillMaxSize().background(C.Fond).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }) {
            Image(painterResource(R.drawable.accueil_ker), contentDescription = "Ker Patrimo", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().requiredHeight(hEcran).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = t; scaleX = 1.08f - 0.08f * t; scaleY = 1.08f - 0.08f * t }
                    .blur(((1f - t) * 28f).dp))
            // Les 4 cases du code, juste sous le titre, sans texte : le clavier numérique du téléphone sert à la saisie
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            val clavier = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            clavierCache = clavier
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(900); runCatching { focus.requestFocus(); clavier?.show() } }
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.text.BasicTextField(
                    value = saisie,
                    onValueChange = { v -> val c = v.filter { it.isDigit() }.take(4); if (c.length < saisie.length) { saisie = c; erreur = false } else c.drop(saisie.length).forEach { taper(it.toString()) } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }.focusRequester(focus)
                )
                Row(Modifier.align(Alignment.TopCenter).padding(top = hEcran * 0.285f)
                    .graphicsLayer { alpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f); translationX = secousse.value }
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        runCatching { focus.requestFocus(); clavier?.show() } },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { i ->
                        Box(Modifier.size(46.dp).background(Color(0x990D0F14), RoundedCornerShape(10.dp))
                            .border(1.5.dp, if (erreur) C.Baisse else C.Or.copy(alpha = if (i == saisie.length) 1f else .6f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            if (i < saisie.length) Box(Modifier.size(12.dp).background(C.Or, CircleShape))
                        }
                    }
                }
            }
        }
    }
}
