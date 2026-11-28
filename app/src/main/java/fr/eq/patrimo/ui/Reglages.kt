package fr.eq.patrimo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eq.patrimo.data.Contrat
import fr.eq.patrimo.domain.Frais
import fr.eq.patrimo.domain.Ligne
import fr.eq.patrimo.domain.Portfolio
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Saisie « 0,85 » -> 0.0085 ; vide -> null. */
private fun pctEnFraction(s: String): Double? = s.trim().replace(",", ".").replace("%", "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.div(100)
private fun fractionEnPct(v: Double?): String = v?.let { String.format(Locale.FRANCE, "%.3f", it * 100).trimEnd('0').trimEnd(',') } ?: ""
private val JJMMAAAA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun ReglagesEcran(vm: PortfolioViewModel, snack: SnackbarHostState, fermer: () -> Unit) {
    BackHandler(onBack = fermer)
    var section by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        containerColor = C.Fond,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = fermer) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Text("Réglages", fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Frais des contrats", "Frais des fonds", "Journal des parts", "Assistant").forEachIndexed { i, t ->
                    FilterChip(selected = section == i, onClick = { section = i }, label = { Text(t) })
                }
            }
            when (section) {
                0 -> FraisContrats(vm)
                1 -> FraisFonds(vm)
                2 -> {
                    val j by vm.journalParts.collectAsStateWithLifecycle()
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        item {
                            Text("Chaque jour, les frais de gestion des contrats d'assurance vie et PER sont déduits du nombre de parts, " +
                                "comme le fait l'assureur. Chaque ajustement est noté ici (et dans la fiche de la ligne).",
                                color = C.Discret, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                        }
                        if (j.isEmpty()) item { Text("Aucun ajustement pour l'instant.", color = C.Discret) }
                        else item { JournalLignes(j.take(150), avecLibelle = true) }
                    }
                }
                else -> ReglagesAssistant(vm)
            }
        }
    }
}

@Composable
private fun FraisContrats(vm: PortfolioViewModel) {
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Frais prélevés par chaque contrat ou compte. Une valeur modifiée ici est conservée lors des prochains imports.",
                color = C.Discret, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        if (contrats.isEmpty()) item { Text("Importez d'abord votre fichier.", color = C.Discret) }
        items(contrats, key = { it.etablissement + "|" + it.contrat }) { c ->
            CarteContrat(c, lignes.filter { it.p.etablissement == c.etablissement && it.p.contrat == c.contrat }, vm::enregistrerContrat)
        }
    }
}

@Composable
private fun CarteContrat(c: Contrat, lignes: List<Ligne>, enregistrer: (Contrat) -> Unit) {
    val assurance = Frais.estAssurance(c.enveloppe)
    val crypto = c.enveloppe.equals("Crypto", true)
    var uc by remember(c) { mutableStateOf(fractionEnPct(c.fraisGestionUc.takeIf { it > 0 })) }
    var euro by remember(c) { mutableStateOf(fractionEnPct(c.fraisGestionEuro.takeIf { it > 0 })) }
    var bareme by remember(c) { mutableStateOf(c.droitsDeGarde) }
    var fixe by remember(c) { mutableStateOf(if (c.droitsFixesParLigne > 0) String.format(Locale.FRANCE, "%.2f", c.droitsFixesParLigne) else "") }
    var plafond by remember(c) { mutableStateOf(fractionEnPct(c.plafondGarde)) }
    var releve by remember(c) { mutableStateOf(c.dateReleve?.let { LocalDate.ofEpochDay(it).format(JJMMAAAA) } ?: "") }
    var verse by remember(c) { mutableStateOf(c.verse?.let { String.format(Locale.FRANCE, "%.2f", it) } ?: "") }
    val coutAn = lignes.sumOf { it.fraisContratAn + it.gardeAn }
    val valeur = lignes.sumOf { it.valeur }
    Carte(titre = "${c.etablissement} · ${c.contrat}") {
        Text("${c.enveloppe} · ${Portfolio.eur(valeur)} · frais estimés ${Portfolio.eur(coutAn)}/an" +
            if (valeur > 0) " (${Portfolio.pctAbs(coutAn / valeur, 2)})" else "", color = C.Or, fontSize = 13.sp)
        Spacer(Modifier.padding(top = 8.dp))
        val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        // Montant versé : base de la plus-value du contrat (vide = somme des montants investis par ligne)
        OutlinedTextField(verse, { verse = it }, label = { Text("Montant versé (€) — base de la plus-value") }, singleLine = true, keyboardOptions = dec,
            placeholder = { Text(Portfolio.eur(lignes.sumOf { it.investi }) + " (calculé)") }, modifier = Modifier.fillMaxWidth())
        when {
            crypto -> Text("Pas de frais récurrents modélisés pour la crypto.", color = C.Discret, fontSize = 13.sp)
            assurance -> {
                OutlinedTextField(uc, { uc = it }, label = { Text("Frais de gestion UC (%/an)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(euro, { euro = it }, label = { Text("Frais fonds euros (%/an, informatif)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(releve, { releve = it }, label = { Text("Date du relevé des parts (jj/mm/aaaa)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                OutlinedTextField(bareme, { bareme = it }, label = { Text("Droits de garde (barème)") }, singleLine = true,
                    placeholder = { Text("0,40% jusqu'à 50000 ; 0,20% jusqu'à 150000 ; 0,10%") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fixe, { fixe = it }, label = { Text("€/ligne/an") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                    OutlinedTextField(plafond, { plafond = it }, label = { Text("Plafond (%)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                }
            }
        }
        if (c.source.isNotBlank()) Text(c.source, color = C.Discret, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        if (c.modifie) Text("Modifié par vous", color = C.Or, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Button(
            onClick = {
                enregistrer(c.copy(
                    fraisGestionUc = pctEnFraction(uc) ?: 0.0,
                    fraisGestionEuro = pctEnFraction(euro) ?: 0.0,
                    droitsDeGarde = bareme.trim(),
                    droitsFixesParLigne = fixe.replace(",", ".").trim().toDoubleOrNull() ?: 0.0,
                    plafondGarde = pctEnFraction(plafond),
                    dateReleve = runCatching { LocalDate.parse(releve.trim(), JJMMAAAA).toEpochDay() }.getOrNull() ?: c.dateReleve,
                    verse = verse.replace(" ", "").replace("\u00A0", "").replace("€", "").replace(",", ".").trim().toDoubleOrNull()
                ))
            },
            shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 10.dp)
        ) { Text("Enregistrer") }
    }
}

@Composable
private fun FraisFonds(vm: PortfolioViewModel) {
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val fonds = lignes.filter { it.estCote && it.p.type != "Crypto" && it.p.type != "Action" }.sortedByDescending { it.valeur }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Frais courants de chaque fonds ou ETF (indiqués dans son document d'information, le DICI). " +
                "Ils sont déjà inclus dans le cours : l'appli les affiche en euros, sans les retirer une deuxième fois.",
                color = C.Discret, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text("Total : ${Portfolio.eur(fonds.sumOf { it.fraisFondsAn })}/an", color = C.Or, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(fonds, key = { it.p.id }) { l ->
            var saisie by remember(l.p.fraisAnnuels) { mutableStateOf(fractionEnPct(l.p.fraisAnnuels)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.p.libelle, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${l.p.contrat} · ${Portfolio.eur(l.valeur)}" + if (l.fraisFondsAn > 0) " · ${Portfolio.eur(l.fraisFondsAn)}/an" else "",
                        color = C.Discret, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(saisie, { saisie = it }, label = { Text("%/an") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(96.dp))
                IconButton(onClick = { vm.enregistrerFraisFonds(l.p, pctEnFraction(saisie)) }) { Icon(Icons.Default.Check, "Enregistrer les frais de ${l.p.libelle}", tint = C.Or) }
            }
        }
    }
}

@Composable
private fun ReglagesAssistant(vm: PortfolioViewModel) {
    val s = vm.settings
    var url by remember { mutableStateOf(s.baseUrl) }
    var cle by remember { mutableStateOf(s.apiKey) }
    var modele by remember { mutableStateOf(s.model) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API compatible « chat/completions » (Mistral, Claude, OpenAI…). Sans clé, l'assistant répond aux questions simples hors ligne.",
            color = C.Discret, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = url.contains("mistral"), onClick = { url = "https://api.mistral.ai/v1"; modele = "mistral-small-latest" }, label = { Text("Mistral") })
            FilterChip(selected = url.contains("anthropic"), onClick = { url = "https://api.anthropic.com/v1"; modele = "claude-sonnet-5-5" }, label = { Text("Claude") })
            FilterChip(selected = url.contains(":11434"), onClick = { url = "http://192.168.1.10:11434/v1"; modele = "qwen3:8b"; cle = "" }, label = { Text("Ollama (PC)") })
        }
        if (url.contains(":11434")) Text("Remplacez 192.168.1.10 par l'adresse IP de votre PC sur le Wi-Fi (commande ipconfig). Aucune clé n'est nécessaire.",
            color = C.Or, fontSize = 12.sp)
        OutlinedTextField(url, { url = it }, label = { Text("URL de base") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cle, { cle = it }, label = { Text("Clé d'API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modele, { modele = it }, label = { Text("Modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { s.baseUrl = url; s.apiKey = cle; s.model = modele }, shape = RoundedCornerShape(12.dp)) { Text("Enregistrer") }
    }
}
