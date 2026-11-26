package fr.eq.patrimo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eq.patrimo.data.Position
import java.text.Normalizer

private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()

private enum class Statut(val label: String, val couleur: Color) {
    DETENU("Détenu", C.Hausse), SURVEILLE("Surveillé", C.Or), PROPOSE("Proposé", C.Discret)
}

private data class Entree(val isin: String?, val libelle: String, val statut: Statut, val position: Position?)

/** Onglet « Fonds » : tous les fonds connus de chaque contrat, par ordre alphabétique. */
@Composable
fun Catalogue(vm: PortfolioViewModel) {
    val contrats by vm.contrats.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val catalogue by vm.catalogue.collectAsStateWithLifecycle()
    val progression by vm.progression.collectAsStateWithLifecycle()
    val choix = contrats.filter { !it.enveloppe.contains("crypto", true) }.sortedWith(compareBy({ it.enveloppe }, { it.contrat }))
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    var recherche by rememberSaveable { mutableStateOf("") }
    var fiche by remember { mutableStateOf<Position?>(null) }
    var confirmerVider by remember { mutableStateOf(false) }
    val courant = choix.firstOrNull { it.contrat == sel } ?: choix.firstOrNull()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && courant != null) vm.importerCatalogue(uri, courant.etablissement, courant.contrat)
    }
    if (fiche != null) FicheLigne(fiche, contrats, fiche!!.aSurveiller, vm::enregistrerPosition, vm::supprimerPosition, vm::appliquer) { fiche = null }

    Column(Modifier.padding(horizontal = 16.dp)) {
        // Bandeau à deux niveaux : enveloppe (Assurance vie, PEA, CTO…) puis contrats de cette enveloppe
        val envs = choix.map { it.enveloppe }.distinct()
        val envCourante = courant?.enveloppe
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 10.dp)) {
            items(envs) { e ->
                FilterChip(selected = e == envCourante, onClick = {
                    if (e != envCourante) { sel = choix.first { it.enveloppe == e }.contrat; confirmerVider = false }
                }, label = { Text(e) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp)) {
            items(choix.filter { it.enveloppe == envCourante }) { c ->
                FilterChip(selected = c == courant, onClick = { sel = c.contrat; confirmerVider = false }, modifier = Modifier.height(30.dp),
                    label = { Text(c.contrat, fontSize = 12.sp, maxLines = 1) })
            }
        }
        if (courant == null) { Text("Aucun contrat : importe d'abord ton fichier de suivi.", color = C.Discret); return@Column }

        // Fonds connus pour ce contrat : lignes détenues, lignes surveillées, fonds déclarés disponibles, liste importée
        val n = norm(courant.contrat)
        val entrees = buildList {
            positions.filter { it.contrat == courant.contrat && it.etablissement == courant.etablissement }.forEach {
                add(Entree(it.isin, it.libelle, if (it.aSurveiller) Statut.SURVEILLE else Statut.DETENU, it))
            }
            positions.filter { p -> p.aSurveiller && p.contrat != courant.contrat &&
                p.disponibilites.orEmpty().split(',', ';').any { d -> norm(d).isNotEmpty() && (norm(d).contains(n) || n.contains(norm(d))) } }
                .forEach { add(Entree(it.isin, it.libelle, Statut.SURVEILLE, it)) }
            catalogue.filter { it.contrat == courant.contrat }.forEach { add(Entree(it.isin, it.libelle, Statut.PROPOSE, null)) }
        }.distinctBy { it.isin?.uppercase() ?: norm(it.libelle) }
            .sortedBy { norm(it.libelle) }
        val nCatalogue = catalogue.count { it.contrat == courant.contrat }
        val q = norm(recherche)
        val visibles = if (q.isEmpty()) entrees else entrees.filter { norm(it.libelle).contains(q) || it.isin.orEmpty().lowercase().contains(q) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${courant.etablissement} · ${courant.contrat}", fontWeight = FontWeight.SemiBold)
                Text("${entrees.size} fonds" + (if (nCatalogue == 0) " · liste complète non importée" else " · dont $nCatalogue de la liste importée"),
                    color = C.Discret, fontSize = 12.sp)
            }
            OutlinedButton(enabled = progression == null, onClick = { launcher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(12.dp)) { Text("Importer la liste", fontSize = 12.sp) }
        }
        progression?.let { pr ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(C.Carte, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Row {
                    Text("${(pr.fraction * 100).toInt()} %", fontWeight = FontWeight.Bold, color = C.Or, modifier = Modifier.weight(1f))
                    Text(pr.resteSec?.let { s -> if (s < 1) "moins d'1 s restante" else if (s < 60) "environ $s s restantes" else "environ ${s / 60} min ${s % 60} s restantes" }
                        ?: "estimation…", color = C.Discret, fontSize = 12.sp)
                }
                LinearProgressIndicator(progress = { pr.fraction }, color = C.Or, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text(pr.etape, color = C.Discret, fontSize = 12.sp)
            }
        }
        if (nCatalogue > 0) TextButton(onClick = { if (confirmerVider) { vm.viderCatalogue(courant.contrat); confirmerVider = false } else confirmerVider = true }) {
            Text(if (confirmerVider) "Confirmer : effacer la liste importée de ${courant.contrat}" else "Effacer la liste importée", fontSize = 12.sp, color = C.Discret)
        }
        OutlinedTextField(recherche, { recherche = it }, label = { Text("Rechercher un fonds ou un ISIN") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        if (nCatalogue == 0) Text("Pour voir tous les fonds du contrat, télécharge la liste des supports chez l'assureur ou le courtier " +
            "(Excel ou CSV), puis « Importer la liste ». Toute ligne contenant un ISIN est reprise.", color = C.Discret, fontSize = 12.sp)

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(visibles, key = { (it.isin ?: it.libelle) + it.statut }) { e ->
                Row(Modifier.fillMaxWidth().clickable(enabled = e.position != null) { fiche = e.position }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.libelle, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.isin ?: "—", color = C.Discret, fontSize = 11.sp)
                    }
                    if (e.statut == Statut.PROPOSE && e.isin != null)
                        TextButton(onClick = { vm.surveillerFonds(e.isin, e.libelle, courant) }) { Text("+ Surveiller", fontSize = 12.sp) }
                    else Box(Modifier.background(e.statut.couleur.copy(alpha = .15f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(e.statut.label, color = e.statut.couleur, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
