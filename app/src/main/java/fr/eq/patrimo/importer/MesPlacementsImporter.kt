package fr.eq.patrimo.importer

import fr.eq.patrimo.data.Position

/**
 * Import direct du classeur de suivi « Mesplacements_AV… .xlsm », onglet « portefeuille ».
 *
 * Structure lue (colonnes, à partir de A) :
 *  A BANQUE · B libellé · D ISIN (ou ticker crypto) · E ACTUEL (valeur €) · G TOTAL invest
 *  H VAL VEILLE (crypto : prix de référence en $) · I nb UC achetés · K zone · L fragile
 *  N TYPE · O cours (crypto : cours actuel en $) · Q VAR % ref
 * Un premier bloc « FONDS TOTAUX » (assurances vie, PEA, comptes-titres), puis un bloc
 * « CRYPTO PROJECT » qui se termine par la ligne TOTAL.
 *
 * Montant investi d'une ligne : la colonne Q vaut (TOTAL - investi) / investi,
 * donc investi = TOTAL / (1 + Q).
 * Crypto : investi = prix de référence $ × quantité × taux de l'onglet « eurusd » (B2, $ -> €).
 */
object MesPlacementsImporter {

    /** Code banque du classeur -> (établissement, contrat, enveloppe). */
    private val BANQUES = mapOf(
        "CARDIF" to Triple("BNP Paribas Cardif", "CARDIF", "Assurance vie"),
        "AFER1" to Triple("AFER", "AFER1 Multisupport", "Assurance vie"),
        "AFER2" to Triple("AFER", "AFER2 Génération", "Assurance vie"),
        "LINXEA" to Triple("Linxea", "LINXEA", "Assurance vie"),
        "BOURSORAMA" to Triple("Boursorama", "BoursoVie", "Assurance vie"),
        "PEA" to Triple("BNP Paribas Banque Privée", "PEA", "PEA"),
        "TRADE REPUBLIC" to Triple("Trade Republic", "TRADE REPUBLIC", "CTO"),
        "REVOLUT" to Triple("Revolut", "REVOLUT", "CTO")
    )
    private val ISIN_CORRIGES = mapOf("CVX" to "US1667641005") // Chevron saisi par son ticker
    private val ACTIONS = setOf("AIR LIQUIDE", "ACCOR", "SCHNEIDER ELECTRIC SE", "TOTALENERGIES SE", "ACTIONS CHEVRON", "MICRO STRATEGY")

    private fun num(s: String?): Double? =
        s?.replace(" ", "")?.replace(" ", "")?.replace("€", "")?.replace(",", ".")?.toDoubleOrNull()

    private fun titre(s: String) = s.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Null si le classeur n'a pas d'onglet « portefeuille ». */
    fun lire(classeur: XlsxReader.Classeur): List<Position>? {
        val lignes = classeur.lignesBrutes("portefeuille") ?: return null
        // Taux $ -> € : 2e ligne de l'onglet eurusd, colonne B (ex. « 0,9172 »)
        val usdEur = classeur.lignesBrutes("eurusd")?.getOrNull(1)?.get(1)?.let(::num)?.takeIf { it in 0.5..1.5 } ?: 0.92
        val out = ArrayList<Position>()
        var crypto = false
        for (r in lignes) {
            val banque = r[0]?.trim().orEmpty()
            val nom = r[1]?.trim().orEmpty()
            if (nom.equals("TOTAL", true)) { if (crypto) break else continue }
            if (banque.isEmpty() || nom.isEmpty()) continue
            if (banque.equals("BANQUE", true)) { crypto = nom.contains("CRYPTO", true); continue }

            val code = r[3]?.replace(" ", "")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val valeur = num(r[4]) ?: 0.0
            val total = num(r[6])
            val parts = num(r[8]) ?: 0.0
            val typeSource = r[13]?.trim().orEmpty()
            val notes = listOfNotNull(r[10]?.trim(), r[11]?.trim(), typeSource.ifEmpty { null })
                .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }

            if (crypto) {
                if (parts <= 0) {
                    out += Position(enveloppe = "Crypto", etablissement = titre(banque), contrat = titre(banque), libelle = nom, isin = code,
                        type = "Crypto", quantite = 0.0, pru = 0.0, valeurManuelle = null, tauxAnnuel = null, dateAchat = null,
                        fraisAnnuels = null, notes = notes, aSurveiller = true)
                    continue
                }
                val refUsd = num(r[7]); val coursUsd = num(r[14])
                val pru = (refUsd ?: coursUsd ?: 0.0) * usdEur
                if (pru <= 0) continue
                out += Position(
                    enveloppe = "Crypto", etablissement = titre(banque), contrat = titre(banque),
                    libelle = nom, isin = code, type = "Crypto", quantite = parts, pru = pru,
                    valeurManuelle = null, tauxAnnuel = null, dateAchat = null, fraisAnnuels = null,
                    notes = notes, dernierCours = coursUsd?.times(usdEur)
                )
                continue
            }

            val (etab, contrat, env) = BANQUES[banque.uppercase()] ?: Triple(titre(banque), banque, "Autre")
            when {
                // Fonds euros : pas de parts, on garde la valeur
                nom.contains("fonds garanti", true) || nom.contains("fonds euro", true) -> if (valeur > 0) out += Position(
                    enveloppe = env, etablissement = etab, contrat = contrat, libelle = "$nom ($contrat)", isin = null,
                    type = "Fonds euros", quantite = 0.0, pru = 0.0, valeurManuelle = valeur, tauxAnnuel = null,
                    dateAchat = null, fraisAnnuels = null, notes = notes
                )
                // Espèces et devises
                nom.uppercase().contains("CASH") || typeSource.equals("DEVISES", true) -> if (valeur > 0) out += Position(
                    enveloppe = env, etablissement = etab, contrat = contrat, libelle = nom, isin = null,
                    type = "Liquidités", quantite = 0.0, pru = 0.0, valeurManuelle = valeur, tauxAnnuel = null,
                    dateAchat = null, fraisAnnuels = null, notes = notes
                )
                // Ligne soldée (0 part, 0 €) : conservée dans « À surveiller »
                parts <= 0 -> if (nom.isNotEmpty()) out += Position(
                    enveloppe = env, etablissement = etab, contrat = contrat, libelle = nom,
                    isin = code?.let { ISIN_CORRIGES[it] ?: it },
                    type = if (typeSource.equals("ETF", true) || nom.contains("ETF", true)) "ETF" else if (nom.uppercase() in ACTIONS) "Action" else "OPCVM/UC",
                    quantite = 0.0, pru = 0.0, valeurManuelle = null, tauxAnnuel = null, dateAchat = null, fraisAnnuels = null,
                    notes = notes, aSurveiller = true
                )
                else -> {
                    val varRef = num(r[16])
                    val investi = if (total != null && varRef != null && varRef > -0.999) total / (1 + varRef) else valeur
                    val type = when {
                        nom.uppercase() in ACTIONS -> "Action"
                        typeSource.equals("ETF", true) || nom.contains("ETF", true) -> "ETF"
                        else -> "OPCVM/UC"
                    }
                    out += Position(
                        enveloppe = env, etablissement = etab, contrat = contrat, libelle = nom,
                        isin = code?.let { ISIN_CORRIGES[it] ?: it }, type = type,
                        quantite = parts, pru = investi / parts, valeurManuelle = null, tauxAnnuel = null,
                        dateAchat = null, fraisAnnuels = null, notes = notes,
                        dernierCours = if (valeur > 0) valeur / parts else null
                    )
                }
            }
        }
        return out
    }
}
