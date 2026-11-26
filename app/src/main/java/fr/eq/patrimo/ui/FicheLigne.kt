package fr.eq.patrimo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.eq.patrimo.data.Contrat
import fr.eq.patrimo.data.Position
import fr.eq.patrimo.domain.Operation
import java.util.Locale

private val TYPES = listOf("Action", "ETF", "OPCVM/UC", "Fonds euros", "Liquidités", "Crypto", "Autre")
private fun txt(v: Double?, d: Int = 4) = v?.takeIf { it != 0.0 }?.let { String.format(Locale.FRANCE, "%.${d}f", it).trimEnd('0').trimEnd(',') } ?: ""
private fun dbl(s: String) = s.replace(" ", "").replace(" ", "").replace("€", "").replace("%", "").replace(",", ".").trim().toDoubleOrNull()

/**
 * Fiche d'une ligne : création ([initiale] null), modification, achat/vente rapide, suppression.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FicheLigne(
    initiale: Position?,
    contrats: List<Contrat>,
    surveillanceParDefaut: Boolean,
    enregistrer: (Position) -> Unit,
    supprimer: (Position) -> Unit,
    operation: (Operation) -> Unit,
    fermer: () -> Unit
) {
    val p0 = initiale
    var libelle by remember { mutableStateOf(p0?.libelle ?: "") }
    var isin by remember { mutableStateOf(p0?.isin ?: "") }
    var type by remember { mutableStateOf(p0?.type ?: "ETF") }
    var etab by remember { mutableStateOf(p0?.etablissement ?: contrats.firstOrNull()?.etablissement ?: "") }
    var contrat by remember { mutableStateOf(p0?.contrat ?: contrats.firstOrNull()?.contrat ?: "") }
    var enveloppe by remember { mutableStateOf(p0?.enveloppe ?: contrats.firstOrNull()?.enveloppe ?: "Assurance vie") }
    var parts by remember { mutableStateOf(txt(p0?.quantite, 6)) }
    var pru by remember { mutableStateOf(txt(p0?.pru)) }
    var valeur by remember { mutableStateOf(txt(p0?.valeurManuelle, 2)) }
    var taux by remember { mutableStateOf(txt(p0?.tauxAnnuel?.times(100), 3)) }
    var frais by remember { mutableStateOf(txt(p0?.fraisAnnuels?.times(100), 3)) }
    var notes by remember { mutableStateOf(p0?.notes ?: "") }
    var dispo by remember { mutableStateOf(p0?.disponibilites ?: "") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var surveiller by remember { mutableStateOf(p0?.aSurveiller ?: surveillanceParDefaut) }
    var confirmeSuppr by remember { mutableStateOf(false) }
    // Opération rapide
    var sens by remember { mutableStateOf("Achat") }
    var opQte by remember { mutableStateOf("") }
    var opPrix by remember { mutableStateOf("") }
    var opFrais by remember { mutableStateOf("") }

    val sansCotation = type == "Fonds euros" || type == "Liquidités"
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = fermer,
        title = { Text(if (p0 == null) "Nouvelle ligne" else "Modifier la ligne") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(libelle, { libelle = it }, label = { Text("Libellé") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(isin, { isin = it.uppercase() }, label = { Text(if (type == "Crypto") "Ticker (BTC, ETH…)" else "Code ISIN") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Type", fontSize = 12.sp, color = C.Discret)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
                Text("Contrat / compte", fontSize = 12.sp, color = C.Discret)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    contrats.forEach { c ->
                        FilterChip(selected = c.contrat == contrat && c.etablissement == etab,
                            onClick = { etab = c.etablissement; contrat = c.contrat; enveloppe = c.enveloppe },
                            label = { Text(c.contrat) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(etab, { etab = it }, label = { Text("Établissement") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(contrat, { contrat = it }, label = { Text("Contrat") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(enveloppe, { enveloppe = it }, label = { Text("Enveloppe (Assurance vie, PEA, CTO, Crypto…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (sansCotation) {
                    OutlinedTextField(valeur, { valeur = it }, label = { Text("Valeur (€)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth())
                    if (type == "Fonds euros") OutlinedTextField(taux, { taux = it }, label = { Text("Taux annuel net (%)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(parts, { parts = it }, label = { Text("Nb de parts") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                        OutlinedTextField(pru, { pru = it }, label = { Text("PRU (€)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                    }
                    if (type != "Crypto" && type != "Action")
                        OutlinedTextField(frais, { frais = it }, label = { Text("Frais courants du fonds (%/an)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                if (type != "Crypto") {
                    OutlinedTextField(dispo, { dispo = it }, label = { Text("Disponible aussi dans (contrats, séparés par des virgules)") },
                        modifier = Modifier.fillMaxWidth())
                    if (isin.isNotBlank()) TextButton(onClick = {
                        val q = java.net.URLEncoder.encode("${isin.trim()} assurance vie contrats disponible", "UTF-8")
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$q"))) }
                    }) { Text("Chercher les contrats qui le proposent ↗") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("À surveiller (hors patrimoine)", modifier = Modifier.weight(1f))
                    Switch(checked = surveiller, onCheckedChange = { surveiller = it })
                }

                // Achat / vente sur une ligne existante : parts et PRU recalculés
                if (p0 != null && !sansCotation) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Enregistrer une opération", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Achat", "Vente").forEach { s -> FilterChip(selected = sens == s, onClick = { sens = s }, label = { Text(s) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(opQte, { opQte = it }, label = { Text("Parts") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                        OutlinedTextField(opPrix, { opPrix = it }, label = { Text("Prix (€)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                        if (sens == "Achat") OutlinedTextField(opFrais, { opFrais = it }, label = { Text("Frais €") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = {
                        val q = dbl(opQte) ?: return@OutlinedButton
                        val ref = p0.isin ?: p0.libelle
                        operation(if (sens == "Achat") Operation.Achat(ref, p0.contrat, q, dbl(opPrix) ?: return@OutlinedButton, dbl(opFrais) ?: 0.0)
                            else Operation.Vente(ref, p0.contrat, q, dbl(opPrix)))
                        fermer()
                    }) { Text("Enregistrer l'${sens.lowercase()}") }
                }

                if (p0 != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    TextButton(onClick = { if (confirmeSuppr) { supprimer(p0); fermer() } else confirmeSuppr = true }) {
                        Text(if (confirmeSuppr) "Confirmer la suppression définitive" else "Supprimer cette ligne", color = C.Baisse)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = libelle.isNotBlank(), onClick = {
                val base = p0 ?: Position(enveloppe = enveloppe, etablissement = etab, contrat = contrat, libelle = libelle, isin = null,
                    type = type, quantite = 0.0, pru = 0.0, valeurManuelle = null, tauxAnnuel = null, dateAchat = null, fraisAnnuels = null, notes = null)
                enregistrer(base.copy(
                    libelle = libelle.trim(), isin = isin.trim().ifEmpty { null }, type = type,
                    etablissement = etab.trim(), contrat = contrat.trim(), enveloppe = enveloppe.trim(),
                    quantite = if (sansCotation) 0.0 else dbl(parts) ?: 0.0,
                    pru = if (sansCotation) 0.0 else dbl(pru) ?: 0.0,
                    valeurManuelle = if (sansCotation) dbl(valeur) else null,
                    tauxAnnuel = dbl(taux)?.div(100),
                    fraisAnnuels = dbl(frais)?.div(100),
                    notes = notes.trim().ifEmpty { null },
                    disponibilites = dispo.trim().ifEmpty { null },
                    aSurveiller = surveiller
                ))
                fermer()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = fermer) { Text("Annuler") } }
    )
}
