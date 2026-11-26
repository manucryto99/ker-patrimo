package fr.eq.patrimo.domain

import fr.eq.patrimo.data.Contrat

/**
 * Les trois familles de frais, et comment chacune touche votre épargne :
 *
 * 1. Frais courants du fonds / de l'ETF (colonne « Frais annuels (%) » des Positions) :
 *    déjà déduits chaque jour de la valeur liquidative. Le cours en ligne est NET de ces frais :
 *    on ne les retire pas une 2e fois, on les affiche seulement en euros.
 * 2. Frais de gestion d'un contrat d'assurance vie / PER sur les UC :
 *    prélevés en NOMBRE DE PARTS. Les parts fondent avec le temps : on estime les parts actuelles
 *    à partir du nombre relevé à la « date du relevé ».
 *    (Sur le fonds euros, le taux annoncé est en général déjà net de frais de gestion.)
 * 3. Droits de garde d'un PEA / compte-titres : débités sur les ESPÈCES du compte, pas sur les parts.
 *    La valeur des titres ne bouge pas, mais votre gain réel baisse : on cumule ces frais depuis l'achat.
 */
object Frais {

    /** Barème « 0,40% jusqu'à 50000 ; 0,20% jusqu'à 150000 ; 0,10% » -> [(plafond tranche, taux)]. */
    fun parseBareme(txt: String): List<Pair<Double, Double>> =
        txt.split(';').mapNotNull { seg ->
            val taux = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*%").find(seg)?.groupValues?.get(1)
                ?.replace(',', '.')?.toDoubleOrNull()?.div(100) ?: return@mapNotNull null
            val borne = Regex("(?:jusqu'?[aà]|<=?|≤)\\s*([0-9  .]+)").find(seg)?.groupValues?.get(1)
                ?.replace(Regex("[  .]"), "")?.toDoubleOrNull() ?: Double.MAX_VALUE
            borne to taux
        }

    /** Droits de garde annuels d'un compte, barème par tranches + fixe par ligne, plafonnés. */
    fun droitsDeGardeCompte(c: Contrat, valeurCompte: Double, nbLignes: Int): Double {
        var reste = valeurCompte
        var bas = 0.0
        var total = 0.0
        for ((haut, taux) in parseBareme(c.droitsDeGarde)) {
            if (reste <= 0) break
            val tranche = minOf(reste, haut - bas)
            total += tranche * taux
            reste -= tranche
            bas = haut
        }
        total += c.droitsFixesParLigne * nbLignes
        c.plafondGarde?.let { total = minOf(total, valeurCompte * it) }
        return total
    }

    fun estAssurance(enveloppe: String) =
        enveloppe.contains("assurance", true) || enveloppe.contains("PER", false) || enveloppe.contains("capitalisation", true)

    /**
     * Barèmes par défaut quand l'onglet « Contrats » est absent ou incomplet.
     * Relevés sur les sites des établissements en novembre 2026 : à vérifier sur vos relevés.
     */
    fun parDefaut(etablissement: String, contrat: String, enveloppe: String): Contrat {
        val e = (etablissement + " " + contrat).lowercase()
        val base = Contrat(etablissement, contrat, enveloppe)
        return when {
            "linxea" in e && "spirit" in e -> base.copy(fraisGestionUc = 0.005, fraisGestionEuro = 0.02,
                source = "Linxea Spirit 2 : 0,50 %/an sur UC (site Linxea, nov. 2026)")
            "afer" in e && "génération" in e || "afer" in e && "generation" in e -> base.copy(fraisGestionUc = 0.01175, fraisGestionEuro = 0.00475,
                source = "Afer Génération : 1,175 %/an sur UC (unités de compte « clean share »)")
            "linxea" in e -> base.copy(fraisGestionUc = 0.005,
                source = "Linxea : 0,50 %/an sur UC (valeur de Linxea Spirit 2, à confirmer)")
            "afer" in e -> base.copy(fraisGestionUc = 0.00475, fraisGestionEuro = 0.00475,
                source = "Multisupport Afer : 0,475 %/an sur UC en gestion libre et sur le Fonds Garanti")
            "bourso" in e && estAssurance(enveloppe) -> base.copy(fraisGestionUc = 0.0075, fraisGestionEuro = 0.0075,
                source = "BoursoVie : 0,75 %/an sur UC, prélevés chaque trimestre en parts (0,50 % BoursoFirst)")
            "bourso" in e -> base.copy(source = "BoursoBank : pas de droits de garde")
            "cardif" in e || ("bnp" in e && estAssurance(enveloppe)) -> base.copy(fraisGestionUc = 0.0085, fraisGestionEuro = 0.007,
                source = "BNP Paribas Multiplacements 2 : 0,85 %/an sur UC (autres contrats Cardif : à vérifier)")
            ("bnp" in e || "hello" in e) && !estAssurance(enveloppe) -> base.copy(
                droitsDeGarde = "0,40% jusqu'à 50000 ; 0,20% jusqu'à 150000 ; 0,10%",
                droitsFixesParLigne = 5.0,
                plafondGarde = if (enveloppe.startsWith("PEA", true)) 0.004 else null,
                source = "Barème public BNP 2026, plafond PEA 0,40 %. Banque Privée : mettez 0 si votre convention inclut les droits de garde. Valeurs BNP exonérées.")
            "revolut" in e -> base.copy(source = "Revolut Securities : pas de droits de garde")
            "trade republic" in e -> base.copy(source = "Trade Republic : pas de droits de garde")
            enveloppe.equals("Crypto", true) -> base.copy(source = "Crypto : pas de frais récurrents modélisés")
            else -> base.copy(plafondGarde = if (enveloppe.startsWith("PEA", true)) 0.004 else null)
        }
    }
}
