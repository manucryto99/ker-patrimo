package fr.eq.patrimo.domain

import fr.eq.patrimo.data.Contrat
import fr.eq.patrimo.data.Position
import fr.eq.patrimo.data.PricePoint
import fr.eq.patrimo.data.Snapshot
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Vue calculée d'une position, frais compris. Construite par [Portfolio.lignes]. */
data class Ligne(
    val p: Position,
    val contrat: Contrat?,
    val parts: Double,            // parts estimées aujourd'hui (érodées par les frais de contrat AV)
    val valeur: Double,
    val fraisFondsAn: Double,     // frais courants du fonds/ETF, déjà dans le cours (informatif)
    val fraisContratAn: Double,   // frais de gestion AV prélevés en parts
    val gardeAn: Double,          // droits de garde PEA/CTO, prélevés en espèces
    val gardeCumulee: Double      // droits de garde estimés depuis l'achat
) {
    val estCote: Boolean get() = p.quantite > 0 && p.pru > 0
    val investi: Double get() = if (estCote) p.quantite * p.pru else p.valeurManuelle ?: 0.0
    val partsPerdues: Double get() = p.quantite - parts
    /** Gain affiché : valeur (déjà nette des frais de fonds et de contrat) - investi - droits de garde payés. */
    val gain: Double get() = valeur - investi - gardeCumulee
    val perf: Double get() = if (investi > 0) gain / investi else 0.0
    val fraisTotauxAn: Double get() = fraisFondsAn + fraisContratAn + gardeAn
    val tauxFraisTotal: Double get() = if (valeur > 0) fraisTotauxAn / valeur else 0.0
    val varJour: Double? get() = p.dernierCours?.let { c -> p.coursVeille?.takeIf { it > 0 }?.let { c / it - 1 } }
}

enum class Periode(val label: String, val jours: Long, val secondes: Long = 0) {
    H1("1H", 0, 3_600), J1("1J", 1, 86_400),
    S1("1S", 7), M1("1M", 31), M6("6M", 183), A1("1A", 365), A5("5A", 1826);
    /** 1H et 1J : courbe tracée avec les cours intraday (minute / 5 minutes). */
    val intraday: Boolean get() = secondes > 0
}

/** Variations relatives d'une ligne cotée (null = pas de cours / pas d'historique). */
data class Variations(val jour: Double?, val semaine: Double?, val mois: Double?,
                      val mois3: Double? = null, val mois6: Double? = null, val an1: Double? = null) {
    fun selon(h: Horizon) = when (h) { Horizon.JOUR -> jour; Horizon.SEMAINE -> semaine; Horizon.MOIS -> mois }
    /** Les 6 horizons affichés sous chaque titre. */
    val toutes: List<Pair<String, Double?>> get() =
        listOf("J" to jour, "1S" to semaine, "1M" to mois, "3M" to mois3, "6M" to mois6, "1A" to an1)
}

/** Performances d'un titre sur 1J / 1S / 1M / 6M / 1A / 5A (null = pas de données). */
data class Perfs(val valeurs: List<Double?>) {
    companion object {
        val LABELS = listOf("1J", "1S", "1M", "6M", "1A", "5A")
        val JOURS = listOf(1L, 7L, 30L, 182L, 365L, 1826L)
    }
    operator fun get(i: Int) = valeurs[i]
}

enum class Horizon(val label: String, val court: String) { JOUR("Jour", "J"), SEMAINE("1 semaine", "1S"), MOIS("1 mois", "1M") }

data class PointCourbe(val epochDay: Long, val valeur: Double, val epochSec: Long? = null)

object Portfolio {

    private fun annees(depuis: Long?, aujourdhui: Long): Double =
        if (depuis == null) 0.0 else ((aujourdhui - depuis).coerceAtLeast(0)) / 365.25

    /** Applique les frais de chaque contrat aux positions. */
    fun lignes(positions: List<Position>, contrats: List<Contrat>, aujourdhui: Long = LocalDate.now().toEpochDay()): List<Ligne> {
        val parCle = contrats.associateBy { it.etablissement to it.contrat }
        fun contratDe(p: Position) = parCle[p.etablissement to p.contrat] ?: Frais.parDefaut(p.etablissement, p.contrat, p.enveloppe)

        // 1er passage : parts et valeur
        val brutes = positions.map { p ->
            val c = contratDe(p)
            val cote = p.quantite > 0 && p.pru > 0
            val assurance = Frais.estAssurance(p.enveloppe)
            val t = annees(c.dateReleve ?: p.dateAchat, aujourdhui)
            val parts = if (cote && assurance && c.fraisGestionUc > 0) p.quantite * (1 - c.fraisGestionUc).pow(t) else p.quantite
            val valeur = when {
                cote -> parts * (p.dernierCours ?: p.pru)
                // fonds euros : taux net servi, capitalisé depuis la date du relevé
                else -> (p.valeurManuelle ?: 0.0) * (1 + (p.tauxAnnuel ?: 0.0)).pow(annees(c.dateReleve, aujourdhui))
            }
            Triple(p, c, parts to valeur)
        }

        // 2e passage : droits de garde calculés au niveau du compte, puis répartis au prorata
        val gardeParCompte = brutes.filter { !Frais.estAssurance(it.first.enveloppe) }
            .groupBy { it.second.etablissement to it.second.contrat }
            .mapValues { (_, l) -> Frais.droitsDeGardeCompte(l.first().second, l.sumOf { it.third.second }, l.size) }
        val valeurParCompte = brutes.groupBy { it.second.etablissement to it.second.contrat }
            .mapValues { (_, l) -> l.sumOf { it.third.second } }

        return brutes.map { (p, c, pv) ->
            val (parts, valeur) = pv
            val cle = c.etablissement to c.contrat
            val assurance = Frais.estAssurance(p.enveloppe)
            val garde = if (assurance) 0.0 else (gardeParCompte[cle] ?: 0.0) * valeur / (valeurParCompte[cle] ?: valeur).coerceAtLeast(1.0)
            Ligne(
                p = p, contrat = c, parts = parts, valeur = valeur,
                fraisFondsAn = valeur * (p.fraisAnnuels ?: 0.0),
                fraisContratAn = if (assurance && p.quantite > 0) valeur * c.fraisGestionUc else 0.0,
                gardeAn = garde,
                gardeCumulee = garde * annees(p.dateAchat, aujourdhui)
            )
        }
    }

    /**
     * Courbe « à composition constante » : valeur qu'aurait eue le portefeuille actuel chaque jour passé
     * (parts estimées d'aujourd'hui). Les supports sans historique comptent pour leur valeur actuelle.
     */
    fun courbe(lignes: List<Ligne>, prix: List<PricePoint>, snapshots: List<Snapshot>, depuis: Long): List<PointCourbe> {
        val parIsin = prix.groupBy { it.isin }.mapValues { (_, v) -> v.sortedBy { it.epochDay } }
        val jours = prix.map { it.epochDay }.filter { it >= depuis }.toSortedSet()
        if (jours.isEmpty()) return snapshots.map { PointCourbe(it.epochDay, it.total) }
        val curseurs = HashMap<String, Int>()
        val dernier = HashMap<String, Double>()
        return jours.map { jour ->
            var total = 0.0
            for (l in lignes) {
                val isin = l.p.isin
                val serie = isin?.let { parIsin[it] }
                if (isin == null || serie == null || !l.estCote) { total += l.valeur; continue }
                var i = curseurs[isin] ?: 0
                while (i < serie.size && serie[i].epochDay <= jour) { dernier[isin] = serie[i].close; i++ }
                curseurs[isin] = i
                total += l.parts * (dernier[isin] ?: serie.first().close)
            }
            PointCourbe(jour, total)
        }
    }

    /**
     * Courbe intraday : chaque ligne cotée suit la variation relative de son cours (dernier point = valeur actuelle),
     * les autres (fonds euros, UC à VL quotidienne, cash) restent constantes.
     */
    fun courbeIntraday(lignes: List<Ligne>, series: Map<String, List<Pair<Long, Double>>>, depuis: Long, maintenant: Long): List<PointCourbe> {
        val utiles = series.filterValues { it.isNotEmpty() }
        if (utiles.isEmpty()) return emptyList()
        val grille = (utiles.values.flatMap { s -> s.map { it.first }.filter { it >= depuis } } + maintenant).toSortedSet().toList()
        val pas = (grille.size / 240).coerceAtLeast(1)
        val g = grille.filterIndexed { i, _ -> i % pas == 0 || i == grille.lastIndex }
        return g.map { t ->
            var total = 0.0
            for (l in lignes) {
                val s = l.p.symbole?.let { utiles[it] }
                if (s == null || !l.estCote) { total += l.valeur; continue }
                val dernier = s.last().second
                val prix = s.lastOrNull { it.first <= t }?.second ?: s.first().second
                total += if (dernier > 0) l.valeur * prix / dernier else l.valeur
            }
            PointCourbe(t / 86_400, total, t)
        }
    }

    /** Variation en euros d'une valeur actuelle qui a varié de v (relatif). */
    fun deltaEur(valeur: Double, v: Double?): Double = if (v == null || v <= -1) 0.0 else valeur * v / (1 + v)

    /** Variation agrégée (€, %) d'un ensemble de lignes sur un horizon ; les lignes sans cours comptent pour 0. */
    fun variation(lignes: List<Ligne>, vars: Map<Long, Variations>, h: Horizon): Pair<Double, Double> {
        val d = lignes.sumOf { deltaEur(it.valeur, vars[it.p.id]?.selon(h)) }
        val avant = lignes.sumOf { it.valeur } - d
        return d to (if (avant > 0) d / avant else 0.0)
    }

    fun parEnveloppe(lignes: List<Ligne>): List<Pair<String, Double>> =
        lignes.groupBy { it.p.enveloppe }.mapValues { (_, v) -> v.sumOf { it.valeur } }
            .toList().sortedByDescending { it.second }

    private val eur0 = NumberFormat.getCurrencyInstance(Locale.FRANCE).apply { maximumFractionDigits = 0 }
    private val eur2 = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    fun eur(v: Double, dec: Boolean = false): String = (if (dec) eur2 else eur0).format(v)
    fun signe(v: Double, dec: Boolean = false) = (if (v >= 0) "+" else "") + eur(v, dec)
    fun pct(v: Double): String = (if (v >= 0) "+" else "") + String.format(Locale.FRANCE, "%.1f %%", v * 100)
    fun pctAbs(v: Double, d: Int = 1): String = String.format(Locale.FRANCE, "%.${d}f %%", abs(v) * 100)
}
