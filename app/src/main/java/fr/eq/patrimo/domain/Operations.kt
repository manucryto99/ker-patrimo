package fr.eq.patrimo.domain

import fr.eq.patrimo.data.PortfolioDao
import fr.eq.patrimo.data.Position
import java.text.Normalizer
import java.util.Locale

/**
 * Une modification du portefeuille. Elle peut venir de l'écran ou de l'assistant ;
 * dans le second cas elle est d'abord montrée à l'utilisateur, qui la valide.
 */
sealed class Operation {
    /** [ref] : ISIN, ticker ou morceau du libellé ; [contrat] : facultatif, pour lever une ambiguïté. */
    data class Achat(val ref: String, val contrat: String?, val quantite: Double, val prix: Double, val frais: Double = 0.0) : Operation()
    data class Vente(val ref: String, val contrat: String?, val quantite: Double, val prix: Double?) : Operation()
    data class Ajout(val position: Position) : Operation()
    data class Modification(val ref: String, val contrat: String?, val champs: Map<String, String>) : Operation()
    data class Suppression(val ref: String, val contrat: String?) : Operation()
    data class Surveiller(val ref: String, val contrat: String?, val libelle: String?) : Operation()
}

object Operations {
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
    private fun n(v: Double, d: Int = 4) = String.format(Locale.FRANCE, "%.${d}f", v).trimEnd('0').trimEnd(',')

    /** Trouve la ligne visée. Retourne la ligne, ou un message d'erreur clair. */
    fun trouver(positions: List<Position>, ref: String, contrat: String?): Result<Position> {
        val r = norm(ref)
        var c = positions.filter { it.isin?.let { i -> norm(i) == r } == true }
        if (c.isEmpty()) c = positions.filter { norm(it.libelle) == r }
        if (c.isEmpty()) c = positions.filter { norm(it.libelle).contains(r) || (r.length >= 3 && r.split(' ').all { m -> norm(it.libelle).contains(m) }) }
        if (contrat != null && c.size > 1) c.filter { norm(it.contrat).contains(norm(contrat)) || norm(it.etablissement).contains(norm(contrat)) }.takeIf { it.isNotEmpty() }?.let { c = it }
        return when (c.size) {
            1 -> Result.success(c.first())
            0 -> Result.failure(IllegalArgumentException("Aucune ligne ne correspond à « $ref »."))
            else -> Result.failure(IllegalArgumentException("« $ref » correspond à plusieurs lignes : " +
                c.take(5).joinToString { "${it.libelle} (${it.contrat})" } + ". Précisez le contrat."))
        }
    }

    fun decrire(op: Operation, positions: List<Position>): String {
        fun cible(ref: String, contrat: String?) = trouver(positions, ref, contrat).fold({ "${it.libelle} (${it.contrat})" }, { "« $ref » (introuvable)" })
        return when (op) {
            is Operation.Achat -> "Achat de ${n(op.quantite)} part(s) de ${cible(op.ref, op.contrat)} à ${Portfolio.eur(op.prix, true)}" +
                if (op.frais > 0) " + ${Portfolio.eur(op.frais, true)} de frais" else ""
            is Operation.Vente -> "Vente de ${n(op.quantite)} part(s) de ${cible(op.ref, op.contrat)}" + (op.prix?.let { " à ${Portfolio.eur(it, true)}" } ?: "")
            is Operation.Ajout -> with(op.position) {
                "Nouvelle ligne : $libelle (${isin ?: type}) dans $contrat — " +
                    if (quantite > 0) "${n(quantite)} part(s) à ${Portfolio.eur(pru, true)}" else "valeur ${Portfolio.eur(valeurManuelle ?: 0.0)}"
            }
            is Operation.Modification -> "Modification de ${cible(op.ref, op.contrat)} : " + op.champs.entries.joinToString { "${it.key} = ${it.value}" }
            is Operation.Suppression -> "Suppression de ${cible(op.ref, op.contrat)}"
            is Operation.Surveiller -> "Mettre ${op.libelle ?: op.ref} dans « À surveiller »"
        }
    }

    private fun d(s: String?) = s?.replace(" ", "")?.replace(" ", "")?.replace("€", "")?.replace("%", "")?.replace(",", ".")?.toDoubleOrNull()

    /** Applique l'opération ; retourne le message à afficher. */
    suspend fun appliquer(dao: PortfolioDao, op: Operation): String {
        val positions = dao.positions()
        return when (op) {
            is Operation.Achat -> {
                val p = trouver(positions, op.ref, op.contrat).getOrElse { return it.message!! }
                val q = p.quantite + op.quantite
                val pru = (p.quantite * p.pru + op.quantite * op.prix + op.frais) / q
                dao.update(p.copy(quantite = q, pru = pru, aSurveiller = false, dernierCours = p.dernierCours ?: op.prix))
                "${p.libelle} : ${n(q)} parts, PRU ${Portfolio.eur(pru, true)}."
            }
            is Operation.Vente -> {
                val p = trouver(positions, op.ref, op.contrat).getOrElse { return it.message!! }
                if (op.quantite > p.quantite + 1e-9) return "Vous ne détenez que ${n(p.quantite)} part(s) de ${p.libelle}."
                val q = (p.quantite - op.quantite).let { if (it < 1e-9) 0.0 else it }
                val plusValue = op.prix?.let { (it - p.pru) * op.quantite }
                // Ligne entièrement vendue : elle passe dans « À surveiller »
                dao.update(p.copy(quantite = q, aSurveiller = q == 0.0))
                "${p.libelle} : ${if (q == 0.0) "ligne soldée, déplacée dans « À surveiller »" else "${n(q)} parts restantes"}" +
                    (plusValue?.let { " · plus-value réalisée ${Portfolio.signe(it)}" } ?: "") + "."
            }
            is Operation.Ajout -> { dao.insert(op.position); "${op.position.libelle} ajouté dans ${op.position.contrat}." }
            is Operation.Suppression -> {
                val p = trouver(positions, op.ref, op.contrat).getOrElse { return it.message!! }
                dao.delete(p); "${p.libelle} supprimé."
            }
            is Operation.Surveiller -> {
                val p = trouver(positions, op.ref, op.contrat).getOrNull()
                if (p != null) { dao.update(p.copy(aSurveiller = true)); "${p.libelle} est dans « À surveiller »." }
                else {
                    dao.insert(Position(enveloppe = "À surveiller", etablissement = "", contrat = "", libelle = op.libelle ?: op.ref,
                        isin = op.ref.uppercase().takeIf { it.length in 2..12 && !it.contains(' ') }, type = "Autre", quantite = 0.0, pru = 0.0,
                        valeurManuelle = null, tauxAnnuel = null, dateAchat = null, fraisAnnuels = null, notes = null, aSurveiller = true))
                    "${op.libelle ?: op.ref} ajouté à « À surveiller »."
                }
            }
            is Operation.Modification -> {
                val p = trouver(positions, op.ref, op.contrat).getOrElse { return it.message!! }
                var m = p
                for ((k, v) in op.champs) m = when (norm(k)) {
                    "parts", "quantite", "nombre de parts" -> m.copy(quantite = d(v) ?: m.quantite)
                    "pru", "prix de revient" -> m.copy(pru = d(v) ?: m.pru)
                    "libelle", "nom" -> m.copy(libelle = v)
                    "isin", "code" -> m.copy(isin = v.uppercase().trim(), symbole = null, source = null)
                    "contrat" -> m.copy(contrat = v)
                    "etablissement" -> m.copy(etablissement = v)
                    "enveloppe" -> m.copy(enveloppe = v)
                    "type" -> m.copy(type = v)
                    "valeur", "valeur manuelle" -> m.copy(valeurManuelle = d(v))
                    "taux" -> m.copy(tauxAnnuel = d(v)?.div(100))
                    "frais", "frais courants" -> m.copy(fraisAnnuels = d(v)?.div(100))
                    "notes" -> m.copy(notes = v)
                    "disponibilites", "disponible", "dispo" -> m.copy(disponibilites = v)
                    else -> return "Champ inconnu : « $k »."
                }
                dao.update(m); "${m.libelle} mis à jour."
            }
        }
    }

    /**
     * Compréhension locale de phrases simples (sans clé d'API) :
     * « achat de 5 parts de CW8 à 548 sur PEA », « vente 10 Air Liquide à 172 »,
     * « supprime Kering », « surveille Nvidia ».
     */
    fun comprendre(phrase: String): Operation? {
        val s = phrase.trim().trimEnd('.', '!')
        val nb = "([0-9]+(?:[.,][0-9]+)?)"
        val rx = Regex("(?i)^(?:j'ai\\s+)?(achat|achète|acheter|acheté|vente|vends|vendre|vendu)\\s+(?:de\\s+)?$nb\\s+(?:parts?\\s+)?(?:de\\s+|d')?(.+?)(?:\\s+(?:à|a|au prix de)\\s+$nb\\s*€?)?(?:\\s+(?:sur|dans)\\s+(?:le |l'|mon |ma )?(.+))?$")
        rx.find(s)?.let { m ->
            val (verbe, q, ref, prix, contrat) = m.destructured
            val qte = q.replace(',', '.').toDouble()
            val px = prix.replace(',', '.').toDoubleOrNull()
            val c = contrat.ifBlank { null }
            return if (verbe.lowercase().startsWith("ach")) px?.let { Operation.Achat(ref.trim(), c, qte, it) }
            else Operation.Vente(ref.trim(), c, qte, px)
        }
        Regex("(?i)^(?:supprime|supprimer|efface|retire)\\s+(?:la ligne\\s+)?(.+?)(?:\\s+(?:sur|dans)\\s+(.+))?$").find(s)?.let { m ->
            return Operation.Suppression(m.groupValues[1].trim(), m.groupValues[2].ifBlank { null })
        }
        if (s.contains("surveil", true)) {
            val nom = s.replace(Regex("(?i)(à|a|en|dans)\\s+(la liste\\s+)?(«\\s*)?(surveiller|surveillance)(\\s*»)?"), "")
                .replace(Regex("(?i)\\b(surveille[rz]?|mets?|ajoute[rz]?|place[rz]?)\\b"), "").trim()
            if (nom.isNotEmpty()) return Operation.Surveiller(nom, null, null)
        }
        return null
    }
}
