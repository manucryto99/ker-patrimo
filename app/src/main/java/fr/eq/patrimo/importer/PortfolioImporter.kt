package fr.eq.patrimo.importer

import fr.eq.patrimo.data.Contrat
import fr.eq.patrimo.data.Position
import fr.eq.patrimo.domain.Frais
import java.io.InputStream
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Transforme l'onglet « Positions » du modèle Excel en liste de [Position]. */
object PortfolioImporter {

    data class Resultat(val positions: List<Position>, val contrats: List<Contrat>)

    fun lire(input: InputStream): Resultat {
        val classeur = XlsxReader.ouvrir(input)
        // 1. Classeur de suivi personnel (.xlsm) : onglet « portefeuille »
        MesPlacementsImporter.lire(classeur)?.takeIf { it.isNotEmpty() }?.let { pos ->
            val contrats = pos.map { Triple(it.etablissement, it.contrat, it.enveloppe) }.distinct()
                .map { (e, c, env) -> Frais.parDefaut(e, c, env) }
            return Resultat(pos, contrats)
        }
        // 2. Modèle Ker Patrimo : un onglet par catégorie ; l'enveloppe est déduite de l'onglet.
        //    Ancien format : onglet unique « Positions ».
        val onglets = listOf("Assurances vie" to "Assurance vie", "PEA" to "PEA", "Comptes-titres" to "CTO", "Crypto" to "Crypto", "PER" to "PER", "PEE" to "PEE")
        var positions = onglets.flatMap { (nom, env) -> classeur.onglet(nom).orEmpty().mapNotNull { toPosition(it, env) } }
        if (positions.isEmpty()) positions = classeur.onglet("Positions", premierSiAbsent = true).orEmpty().mapNotNull { toPosition(it, null) }
        val saisis = classeur.onglet("Contrats").orEmpty().mapNotNull(::toContrat).associateBy { it.etablissement to it.contrat }
        // Chaque contrat cité dans les positions a un barème : celui saisi, sinon celui par défaut
        val contrats = positions.map { Triple(it.etablissement, it.contrat, it.enveloppe) }.distinct().map { (e, c, env) ->
            saisis[e to c] ?: Frais.parDefaut(e, c, env)
        } + saisis.values.filter { s -> positions.none { it.etablissement == s.etablissement && it.contrat == s.contrat } }
        return Resultat(positions, contrats)
    }

    private fun toContrat(r: Map<String, String>): Contrat? {
        val etab = r.col("etablissement") ?: return null
        val contrat = r.col("contrat") ?: return null
        val env = r.col("enveloppe") ?: "Autre"
        val defaut = Frais.parDefaut(etab, contrat, env)
        return Contrat(
            etablissement = etab, contrat = contrat, enveloppe = env,
            fraisGestionUc = rate(r.col("frais de gestion uc", "frais gestion uc")) ?: defaut.fraisGestionUc,
            fraisGestionEuro = rate(r.col("frais de gestion fonds euros", "frais gestion fonds euros")) ?: defaut.fraisGestionEuro,
            droitsDeGarde = r.col("droits de garde (bareme", "bareme droits de garde") ?: defaut.droitsDeGarde,
            droitsFixesParLigne = num(r.col("droits fixes")) ?: defaut.droitsFixesParLigne,
            plafondGarde = rate(r.col("plafond")) ?: defaut.plafondGarde,
            dateReleve = date(r.col("date du releve")),
            source = r.col("source") ?: defaut.source
        )
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().trim()

    /** Cherche une colonne par début de nom, sans tenir compte des accents ni de la casse. */
    private fun Map<String, String>.col(vararg names: String): String? {
        val wanted = names.map(::norm)
        return entries.firstOrNull { (k, _) -> val nk = norm(k); wanted.any { nk.startsWith(it) } }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun num(s: String?): Double? {
        if (s == null) return null
        val clean = s.replace(" ", "").replace(" ", "").replace("€", "").replace("%", "").replace(",", ".")
        val v = clean.toDoubleOrNull() ?: return null
        return if (s.contains("%")) v / 100 else v
    }

    /** Un taux saisi « 2,5 » au lieu de « 2,5 % » est ramené à 0,025. */
    private fun rate(s: String?): Double? = num(s)?.let { if (it > 1) it / 100 else it }

    private fun date(s: String?): Long? {
        if (s == null) return null
        s.toDoubleOrNull()?.let { return it.toLong() - 25569 } // numéro de série Excel -> epochDay
        return runCatching { LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toEpochDay() }.getOrNull()
    }

    private fun toPosition(r: Map<String, String>, envOnglet: String?): Position? {
        val libelle = r.col("libelle") ?: return null
        if (libelle.contains("EXEMPLE", ignoreCase = true)) return null
        val enveloppe = r.col("enveloppe") ?: envOnglet ?: "Autre"
        val crypto = enveloppe.equals("Crypto", true)
        // ISIN (12 caractères) ou, pour une crypto, son ticker (BTC, ETH…)
        val isin = r.col("code isin", "isin", "ticker")?.uppercase()?.replace(" ", "")?.replace("\u00A0", "")?.takeIf { it.isNotEmpty() }
        val qty = num(r.col("nombre de parts", "quantite")) ?: 0.0
        val investi = num(r.col("montant investi"))
        // Crypto : on saisit le montant total investi, le PRU s'en déduit
        val pru = (if (crypto && investi != null && qty > 0) investi / qty else null)
            ?: num(r.col("pru", "prix de revient"))
            ?: if (investi != null && qty > 0) investi / qty else 0.0
        val manuelle = num(r.col("valeur manuelle", "encours"))
        if ((qty <= 0 || pru <= 0) && manuelle == null) return null
        val etab = r.col("etablissement", "plateforme") ?: ""
        return Position(
            enveloppe = enveloppe,
            etablissement = etab,
            contrat = r.col("contrat") ?: etab,
            libelle = libelle,
            isin = isin,
            type = r.col("type") ?: when { crypto -> "Crypto"; isin == null -> "Fonds euros"; else -> "Autre" },
            quantite = qty,
            pru = pru,
            valeurManuelle = manuelle,
            tauxAnnuel = rate(r.col("taux")),
            dateAchat = date(r.col("date")),
            fraisAnnuels = rate(r.col("frais")),
            notes = r.col("notes"),
            dernierCours = num(r.col("dernier cours")) // affiché en attendant la 1re mise à jour en ligne
        )
    }
}
