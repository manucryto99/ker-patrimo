package fr.eq.patrimo.quotes

import fr.eq.patrimo.data.PortfolioDao
import fr.eq.patrimo.data.PricePoint
import fr.eq.patrimo.data.Snapshot
import fr.eq.patrimo.domain.Portfolio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class RefreshResult(val ok: Int, val failed: List<String>)

/** Met à jour les cours de toutes les lignes cotées, en essayant les sources dans l'ordre. */
class QuoteService(private val dao: PortfolioDao) {
    private val yahoo = YahooProvider()
    private val providers: List<QuoteProvider> = listOf(yahoo, BoursoramaProvider())
    private val fxCache = HashMap<String, Double>()

    private fun toEur(value: Double, currency: String): Double? {
        if (currency == "EUR") return value
        val rate = fxCache.getOrPut(currency) { yahoo.eurRate(currency) ?: return null }
        return value / rate
    }

    private companion object { val historiqueLongCharge: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet()) }

    suspend fun refreshAll(withHistory: Boolean, range: String = "5y", avance: (Int, Int) -> Unit = { _, _ -> }): RefreshResult = withContext(Dispatchers.IO) {
        fxCache.clear()
        var ok = 0
        val failed = ArrayList<String>()
        val toutes = dao.positions()
        for ((rang, p) in toutes.withIndex()) {
            avance(rang, toutes.size)
            val isin = p.isin ?: continue
            // 1. Source déjà connue pour cet ISIN, sinon recherche automatique
            val cached = p.symbole?.let { s -> providers.firstOrNull { it.name == p.source }?.let { it to Listing(s, it.name) } }
            // Crypto : cotation directe en euros sur Yahoo (BTC-EUR, ETH-EUR…)
            val crypto = if (p.type.equals("Crypto", true)) Listing("${isin.uppercase()}-EUR", yahoo.name) else null
            var found: Triple<QuoteProvider, Listing, Quote>? = (crypto?.let { yahoo to it } ?: cached)
                ?.let { (prov, l) -> prov.quote(l)?.let { Triple(prov, l, it) } }
            if (found == null && crypto != null) { failed.add(p.libelle); continue }
            if (found == null) {
                for (prov in providers) {
                    val l = prov.resolve(isin) ?: continue
                    val q = prov.quote(l) ?: continue
                    found = Triple(prov, l, q); break
                }
            }
            if (found == null) { failed.add(p.libelle); continue }
            val (prov, listing, q) = found
            val price = toEur(q.price, q.currency)
            if (price == null) { failed.add(p.libelle); continue }
            dao.update(
                p.copy(
                    symbole = listing.symbol, source = prov.name,
                    dernierCours = price,
                    coursVeille = q.previousClose?.let { toEur(it, q.currency) },
                    plusHaut52s = q.high52w?.let { toEur(it, q.currency) },
                    majCours = System.currentTimeMillis()
                )
            )
            ok++
            // Ligne sans historique long (ex. titre ajouté à surveiller) : on charge 5 ans une fois par session
            val court = isin !in historiqueLongCharge &&
                (dao.oldestPrice(isin)?.let { it > LocalDate.now().toEpochDay() - 300 } ?: true)
            if (withHistory || court) {
                val range = if (court) "5y" else range
                historiqueLongCharge.add(isin)
                // Mise à l'échelle sur la dernière clôture de l'historique : gère la devise (taux du jour)
                // et les unités différentes (ex. historique en pence, cours en livres)
                val h = prov.history(listing, range)
                val k = h.lastOrNull()?.second?.takeIf { it > 0 }?.let { last ->
                    val r = price / last
                    // si l'écart vient d'une unité (×100) on le corrige, sinon on garde le taux de change
                    val fx = price / q.price
                    if (r / fx > 20 || r / fx < 0.05) r else fx
                } ?: (price / q.price)
                val pts = h.map { (d, c) -> PricePoint(isin, d, c * k) }
                if (pts.isNotEmpty()) dao.upsertPrices(pts)
            }
            // cours du jour dans l'historique, pour que la courbe finisse sur la dernière valeur
            dao.upsertPrices(listOf(PricePoint(isin, LocalDate.now().toEpochDay(), price)))
        }
        // Photo du jour (utile pour les supports sans historique en ligne)
        val lignes = Portfolio.lignes(dao.positions().filter { !it.aSurveiller }, dao.contrats())
        dao.upsertSnapshot(Snapshot(LocalDate.now().toEpochDay(), lignes.sumOf { it.valeur }, lignes.sumOf { it.investi }))
        RefreshResult(ok, failed)
    }
}
