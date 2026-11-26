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

    suspend fun refreshAll(withHistory: Boolean, range: String = "5y"): RefreshResult = withContext(Dispatchers.IO) {
        fxCache.clear()
        var ok = 0
        val failed = ArrayList<String>()
        for (p in dao.positions()) {
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
            if (withHistory) {
                val k = price / q.price // conversion au taux du jour (approximation)
                val pts = prov.history(listing, range).map { (d, c) -> PricePoint(isin, d, c * k) }
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
