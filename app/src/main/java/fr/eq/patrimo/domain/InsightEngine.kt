package fr.eq.patrimo.domain

import fr.eq.patrimo.domain.Portfolio.eur
import fr.eq.patrimo.domain.Portfolio.pct
import fr.eq.patrimo.domain.Portfolio.pctAbs

enum class Niveau { INFO, ATTENTION, ALERTE, POSITIF }

/** Un constat. [cle] sert à ne notifier qu'une fois le même constat. */
data class Insight(val cle: String, val niveau: Niveau, val titre: String, val texte: String)

data class Seuils(
    val variationJour: Double = 0.03,     // ±3 % sur la séance
    val concentration: Double = 0.25,     // une ligne > 25 % du total
    val moinsValue: Double = -0.15,
    val plusValue: Double = 0.25,
    val retraitPlusHaut: Double = 0.20,   // -20 % sous le plus haut 52 semaines
    val fraisEleves: Double = 0.015,
    val inflation: Double = 0.015,
    val plafondPea: Double = 150_000.0
)

/**
 * Règles simples et explicables. Ce ne sont pas des conseils en investissement :
 * l'appli signale, l'utilisateur décide.
 */
object InsightEngine {
    fun analyse(lignes: List<Ligne>, s: Seuils = Seuils()): List<Insight> {
        val total = lignes.sumOf { it.valeur }.takeIf { it > 0 } ?: return emptyList()
        val out = ArrayList<Insight>()
        for (l in lignes) {
            val nom = l.p.libelle
            val id = l.p.isin ?: nom
            l.varJour?.let { v ->
                if (kotlin.math.abs(v) >= s.variationJour) out += Insight(
                    "jour-$id-${System.currentTimeMillis() / 86_400_000}",
                    if (v < 0) Niveau.ALERTE else Niveau.POSITIF,
                    if (v < 0) "Forte baisse aujourd'hui" else "Forte hausse aujourd'hui",
                    "$nom : ${pct(v)} sur la séance (${eur(l.valeur * v / (1 + v))})."
                )
            }
            val poids = l.valeur / total
            if (poids > s.concentration) out += Insight("conc-$id", Niveau.ATTENTION, "Concentration",
                "$nom pèse ${pctAbs(poids, 0)} du patrimoine. Au-delà de ${pctAbs(s.concentration, 0)}, une seule ligne fait la météo du portefeuille.")
            if (l.estCote && l.perf <= s.moinsValue) out += Insight("mv-$id", Niveau.ALERTE, "Moins-value marquée",
                "$nom est à ${pct(l.perf)} depuis l'achat (${eur(l.gain)}). La raison de l'achat est-elle toujours valable ?")
            if (l.estCote && l.perf >= s.plusValue && l.p.type.equals("Action", true)) out += Insight("pv-$id", Niveau.POSITIF, "Belle plus-value",
                "$nom est à ${pct(l.perf)}. Une prise de bénéfice partielle permettrait de rééquilibrer.")
            val haut = l.p.plusHaut52s
            val cours = l.p.dernierCours
            if (haut != null && cours != null && cours < haut * (1 - s.retraitPlusHaut)) out += Insight("dd-$id", Niveau.ATTENTION, "Loin de son plus haut",
                "$nom cote ${pctAbs(1 - cours / haut, 0)} sous son plus haut sur 52 semaines.")
            if (l.tauxFraisTotal > s.fraisEleves) out += Insight("frais-$id", Niveau.ATTENTION, "Frais élevés",
                "$nom coûte ${pctAbs(l.tauxFraisTotal, 2)} par an tout compris, soit environ ${eur(l.fraisTotauxAn)} " +
                "(fonds ${eur(l.fraisFondsAn)}, contrat ${eur(l.fraisContratAn)}, garde ${eur(l.gardeAn)}).")
            if (l.partsPerdues > 0 && l.p.quantite > 0 && l.partsPerdues / l.p.quantite > 0.005) out += Insight("parts-$id", Niveau.INFO, "Parts prélevées par le contrat",
                "$nom : environ ${String.format(java.util.Locale.FRANCE, "%.3f", l.partsPerdues)} parts prélevées depuis le relevé (frais de gestion du contrat). Mettez à jour le nombre de parts depuis votre relevé de temps en temps.")
            val taux = l.p.tauxAnnuel
            if (l.p.type.contains("euro", true) && taux != null && taux < s.inflation) out += Insight("reel-$id", Niveau.ALERTE, "Rendement réel négatif",
                "$nom sert ${pctAbs(taux)}, moins que l'inflation de référence (${pctAbs(s.inflation)}).")
        }
        val versePea = lignes.filter { it.p.enveloppe.equals("PEA", true) }.sumOf { it.investi }
        if (versePea > 0) out += Insight("pea", Niveau.INFO, "Plafond PEA",
            "Versements estimés ${eur(versePea)} sur ${eur(s.plafondPea)} : il reste ${eur((s.plafondPea - versePea).coerceAtLeast(0.0))} de capacité.")
        val fraisTotaux = lignes.sumOf { it.fraisTotauxAn }
        out += Insight("frais-total", Niveau.INFO, "Frais annuels, tout compris",
            "Environ ${eur(fraisTotaux)} par an, soit ${pctAbs(fraisTotaux / total, 2)} du patrimoine : " +
            "fonds/ETF ${eur(lignes.sumOf { it.fraisFondsAn })}, contrats d'assurance vie ${eur(lignes.sumOf { it.fraisContratAn })}, " +
            "droits de garde ${eur(lignes.sumOf { it.gardeAn })}.")
        lignes.filter { it.gardeAn > 0 }.groupBy { it.p.etablissement + " " + it.p.contrat }.forEach { (compte, l) ->
            val g = l.sumOf { it.gardeAn }
            out += Insight("garde-$compte", Niveau.ATTENTION, "Droits de garde",
                "$compte : environ ${eur(g)} par an (${pctAbs(g / l.sumOf { it.valeur }, 2)} du compte), débités sur les espèces.")
        }
        return out.sortedBy { it.niveau.ordinal.let { o -> listOf(3, 1, 0, 2)[o] } }
    }
}
