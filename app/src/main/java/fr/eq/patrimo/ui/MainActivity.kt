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
import androidx.compose.ui.text.font.FontWeight
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
        setContent { PatrimoTheme { App(vm) } }
    }
}

private val XLSX_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
    "application/vnd.ms-excel.sheet.macroEnabled.12",                    // .xlsm (classeur de suivi)
    "application/vnd.ms-excel", "application/octet-stream"
)

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
                    Triple("Patrimoine", Icons.Default.Home, 0), Triple("Placements", Icons.AutoMirrored.Filled.List, 1),
                    Triple("Surveiller", Icons.Default.Star, 4),
                    Triple("Assistant", Icons.Default.Person, 2), Triple("Alertes", Icons.Default.Notifications, 3)
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = onglet == i, onClick = { onglet = i }, icon = { Icon(icon, label) }, label = { Text(label) })
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
    if (toutes.isEmpty()) { Vide(); return }
    // Tableau de bord par catégorie : Tout / Assurance vie / PEA / CTO / Crypto
    val categories = Portfolio.parEnveloppe(toutes).map { it.first }
    val lignes = toutes.filter { categorie == null || it.p.enveloppe == categorie }
    val total = lignes.sumOf { it.valeur }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // Bandeau : le domaine, hier et aujourd'hui
        Image(
            painter = painterResource(R.drawable.bandeau_ker),
            contentDescription = "Ker Patrimo : château, photos de famille et livres anciens",
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(1500f / 420f).clip(RoundedCornerShape(16.dp))
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp)) {
            item { FilterChip(selected = categorie == null, onClick = { vm.setCategorie(null) }, label = { Text("Tout") }) }
            items(categories) { c -> FilterChip(selected = c == categorie, onClick = { vm.setCategorie(c) }, label = { Text(c) }) }
        }
        Text(categorie ?: "Patrimoine financier", color = C.Discret, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        val investi = lignes.sumOf { it.investi }; val gain = lignes.sumOf { it.gain }
        Text(Portfolio.eur(total), fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("Investi ${Portfolio.eur(investi)} · plus-value ${Portfolio.signe(gain)} (${Portfolio.pct(if (investi > 0) gain / investi else 0.0)})",
            color = C.Discret, fontSize = 13.sp)
        if (courbe.size >= 2) {
            val d = courbe.last().valeur - courbe.first().valeur
            Text("${Portfolio.signe(d)} (${Portfolio.pct(d / courbe.first().valeur)}) sur ${periode.label}", color = C.signe(d), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        CourbeChart(courbe, Modifier.fillMaxWidth().height(200.dp).padding(vertical = 12.dp))
        if (courbe.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            val f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)
            Text(LocalDate.ofEpochDay(courbe.first().epochDay).format(f), color = C.Discret, fontSize = 11.sp)
            Spacer(Modifier.weight(1f)); Text("Aujourd'hui", color = C.Discret, fontSize = 11.sp)
        }
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Periode.entries.forEach { p -> FilterChip(selected = p == periode, onClick = { vm.setPeriode(p) }, label = { Text(p.label) }) }
        }
        Text("Courbe à composition constante (positions actuelles)", color = C.Discret, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
        // Tout : répartition par catégorie ; une catégorie : répartition par contrat / compte
        val env = if (categorie == null) Portfolio.parEnveloppe(lignes)
            else lignes.groupBy { it.p.contrat }.mapValues { (_, v) -> v.sumOf { it.valeur } }.toList().sortedByDescending { it.second }
        Carte(titre = if (categorie == null) "Répartition par catégorie" else "Répartition par contrat") {
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
        if (tri.isNotEmpty()) Carte(titre = "Meilleures et pires lignes") {
            (tri.take(2) + tri.takeLast(2)).distinct().forEach { LigneResume(it) }
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
fun LigneResume(l: Ligne) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(C.Carte2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(l.p.libelle.take(2).uppercase(), color = C.Or, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(l.p.libelle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${l.p.enveloppe} · ${l.p.isin ?: l.p.type}", color = C.Discret, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Portfolio.eur(l.valeur), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(Portfolio.pct(l.perf), color = C.signe(l.gain), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private enum class Tri(val label: String) { INVESTI("Investi"), VALEUR("Valeur"), GAIN("+/- €"), PERF("+/- %") }

@Composable
fun Placements(vm: PortfolioViewModel) {
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    var filtre by rememberSaveable { mutableStateOf("Tous") }
    var tri by remember { mutableStateOf(Tri.VALEUR) }
    var desc by remember { mutableStateOf(true) }
    // Fiche ouverte : null = fermée ; Position(id = 0) = nouvelle ligne
    var fiche by remember { mutableStateOf<Position?>(null) }
    var nouvelle by remember { mutableStateOf(false) }
    if (fiche != null || nouvelle) FicheLigne(fiche, contrats, false, vm::enregistrerPosition, vm::supprimerPosition, vm::appliquer) { fiche = null; nouvelle = false }
    if (lignes.isEmpty()) { Vide(); return }
    val envs = listOf("Tous") + lignes.map { it.p.enveloppe }.distinct()
    val vues = lignes.filter { filtre == "Tous" || it.p.enveloppe == filtre }.sortedBy {
        when (tri) { Tri.INVESTI -> it.investi; Tri.VALEUR -> it.valeur; Tri.GAIN -> it.gain; Tri.PERF -> it.perf }
    }.let { if (desc) it.reversed() else it }
    Column(Modifier.padding(horizontal = 12.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { OutlinedButton(onClick = { nouvelle = true }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null); Text(" Ajouter") } }
            items(envs) { e -> FilterChip(selected = e == filtre, onClick = { filtre = e }, label = { Text(e) }) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Placement", color = C.Discret, fontSize = 12.sp, modifier = Modifier.weight(1.6f))
            Tri.entries.forEach { t ->
                Text(t.label + if (t == tri) (if (desc) " ↓" else " ↑") else "", color = if (t == tri) C.Or else C.Discret, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f).clickable { if (tri == t) desc = !desc else { tri = t; desc = true } })
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vues, key = { it.p.id }) { l ->
                Row(Modifier.fillMaxWidth().clickable { fiche = l.p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.6f)) {
                        Text(l.p.libelle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(l.p.enveloppe + (l.p.source?.let { " · $it" } ?: ""), color = C.Discret, fontSize = 11.sp)
                        if (l.fraisTotauxAn > 0.5) Text("frais ${Portfolio.eur(l.fraisTotauxAn)}/an" +
                            (if (l.partsPerdues > 0.0005) " · ${String.format(Locale.FRANCE, "%.3f", l.parts)} parts est." else ""),
                            color = C.Or, fontSize = 11.sp)
                    }
                    Cellule(Portfolio.eur(l.investi)); Cellule(Portfolio.eur(l.valeur))
                    Cellule(Portfolio.signe(l.gain), C.signe(l.gain)); Cellule(Portfolio.pct(l.perf), C.signe(l.gain))
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
            }
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
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(p.dernierCours?.let { Portfolio.eur(it, true) } ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    varJour?.let { Text(Portfolio.pct(it), color = C.signe(it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
