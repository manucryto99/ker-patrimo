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
                      val mois3: Double? = null, val mois6: Double? = null, val an1: Double? = null, val an5: Double? = null,
                      val heure: Double? = null, val jours3: Double? = null) {
    fun selon(h: Horizon) = when (h) {
        Horizon.HEURE -> heure; Horizon.JOUR -> jour; Horizon.JOURS3 -> jours3; Horizon.SEMAINE -> semaine
        Horizon.MOIS -> mois; Horizon.MOIS3 -> mois3; Horizon.MOIS6 -> mois6
    }
    /** Les 6 horizons affichés sous chaque titre. */
    val toutes: List<Pair<String, Double?>> get() =
        listOf("J" to jour, "1S" to semaine, "1M" to mois, "3M" to mois3, "6M" to mois6, "1A" to an1)
    /** Tous les horizons, 5 ans compris (bloc Total). */
    val avec5ans: List<Pair<String, Double?>> get() = toutes + ("5A" to an5)
}

/** Performances d'un titre sur 1J / 1S / 1M / 6M / 1A / 5A (null = pas de données). */
data class Perfs(val valeurs: List<Double?>) {
    companion object {
        val LABELS = listOf("1J", "1S", "1M", "6M", "1A", "5A")
        val JOURS = listOf(1L, 7L, 30L, 182L, 365L, 1826L)
    }
    operator fun get(i: Int) = valeurs[i]
}

enum class Horizon(val label: String, val court: String, val detail: String) {
    HEURE("1 h", "1H", "Sur 1 heure glissante"), JOUR("1 jour", "J", "Sur 24 h glissantes"), JOURS3("3 jours", "3J", "Sur 3 jours glissants"),
    SEMAINE("1 sem.", "1S", "Sur 7 jours"), MOIS("1 mois", "1M", "Sur 30 jours"), MOIS3("3 mois", "3M", "Sur 3 mois"), MOIS6("6 mois", "6M", "Sur 6 mois")
}

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
     * Rapport « cours à l'instant t / cours actuel » d'une ligne, sur une fenêtre glissante :
     * - cours intraday (Yahoo) si disponible ;
     * - sinon VL quotidienne : à l'instant t on connaît la dernière VL d'un jour antérieur à celui de t
     *   (aujourd'hui : la VL actuelle) ;
     * - sinon (fonds euros, cash, FCPE non cotés) : constant.
     */
    fun rapportA(l: Ligne, t: Long, intraday: Map<String, List<Pair<Long, Double>>>,
                 quotidien: Map<String, List<Pair<Long, Double>>>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Double {
        if (!l.estCote) return 1.0
        l.p.symbole?.let { intraday[it] }?.takeIf { it.isNotEmpty() }?.let { s ->
            val dernier = s.last().second
            val prix = s.lastOrNull { it.first <= t }?.second ?: s.first().second
            return if (dernier > 0) prix / dernier else 1.0
        }
        val c = l.p.dernierCours ?: return 1.0
        val serie = l.p.isin?.let { quotidien[it] }?.takeIf { it.isNotEmpty() } ?: return 1.0
        val jour = java.time.Instant.ofEpochSecond(t).atZone(zone).toLocalDate().toEpochDay()
        if (jour >= LocalDate.now(zone).toEpochDay()) return 1.0
        val vl = serie.lastOrNull { it.first < jour }?.second ?: return 1.0
        return if (c > 0 && vl > 0) vl / c else 1.0
    }

    /**
     * Courbe glissante (1H / 1J) : valeur du portefeuille à chaque instant de [depuis, maintenant].
     * Le premier point est exactement « maintenant − durée », le dernier « maintenant » :
     * l'écart entre les deux est la variation affichée par l'indicateur.
     */
    fun courbeGlissante(lignes: List<Ligne>, intraday: Map<String, List<Pair<Long, Double>>>, quotidien: Map<String, List<Pair<Long, Double>>>,
                        depuis: Long, maintenant: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<PointCourbe> {
        val minuits = generateSequence(java.time.Instant.ofEpochSecond(depuis).atZone(zone).toLocalDate().plusDays(1)) { it.plusDays(1) }
            .map { it.atStartOfDay(zone).toEpochSecond() }.takeWhile { it < maintenant }.flatMap { sequenceOf(it - 1, it) }.toList()
        val grille = (intraday.values.flatMap { s -> s.map { it.first }.filter { it in depuis..maintenant } } + minuits + depuis + maintenant)
            .toSortedSet().toList()
        val pas = (grille.size / 240).coerceAtLeast(1)
        val g = grille.filterIndexed { i, t -> i % pas == 0 || i == grille.lastIndex || t in minuits }
        return g.map { t -> PointCourbe(t / 86_400, lignes.sumOf { it.valeur * rapportA(it, t, intraday, quotidien, zone) }, t) }
    }

    /** Variation en euros d'une valeur actuelle qui a varié de v (relatif). */
    // garde-fou : une baisse de plus de 95 % est presque toujours une donnée erronée
    fun deltaEur(valeur: Double, v: Double?): Double = if (v == null || v <= -0.95) 0.0 else valeur * v / (1 + v)

    /** Variation agrégée (€, %) d'un ensemble de lignes sur un horizon ; les lignes sans cours comptent pour 0. */
    fun variation(lignes: List<Ligne>, vars: Map<Long, Variations>, h: Horizon): Pair<Double, Double> {
        val d = lignes.sumOf { deltaEur(it.valeur, vars[it.p.id]?.selon(h)) }
        val avant = lignes.sumOf { it.valeur } - d
        return d to (if (avant > 0) d / avant else 0.0)
    }

    /**
     * Variation agrégée sur l'horizon n° i de [Variations.avec5ans], calculée sur les seules lignes qui ont un historique
     * suffisant : (€, %, part de la valeur couverte).
     */
    fun variationHorizon(lignes: List<Ligne>, vars: Map<Long, Variations>, i: Int): Triple<Double, Double, Double> {
        val avec = lignes.mapNotNull { l -> vars[l.p.id]?.avec5ans?.getOrNull(i)?.second?.let { l to it } }
        val d = avec.sumOf { (l, v) -> deltaEur(l.valeur, v) }
        val avant = avec.sumOf { it.first.valeur } - d
        val tot = lignes.sumOf { it.valeur }
        return Triple(d, if (avant > 0) d / avant else 0.0, if (tot > 0) avec.sumOf { it.first.valeur } / tot else 0.0)
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
