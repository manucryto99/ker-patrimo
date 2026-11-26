package fr.eq.patrimo.quotes

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Listing(val symbol: String, val source: String)
data class Quote(val price: Double, val previousClose: Double?, val currency: String, val high52w: Double?)

/**
 * Une source de cotation. L'appli essaie les sources dans l'ordre jusqu'à en trouver une
 * qui connaît l'ISIN : l'utilisateur n'a jamais à indiquer où chercher.
 */
interface QuoteProvider {
    val name: String
    fun resolve(isin: String): Listing?
    fun quote(listing: Listing): Quote?
    /** Historique journalier (epochDay -> clôture, devise de cotation). Vide si non disponible. */
    fun history(listing: Listing, range: String): List<Pair<Long, Double>>
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /** Retourne (URL finale après redirections, corps) ou null. */
    fun get(url: String): Pair<String, String>? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.request.url.toString() to (r.body?.string() ?: "")
        }
    }.getOrNull()
}

/**
 * Yahoo Finance (API publique non officielle) : couvre la plupart des actions, ETF et
 * de nombreux OPCVM, avec historique. Peut changer sans préavis : d'où la source de secours.
 */
class YahooProvider : QuoteProvider {
    override val name = "Yahoo"
    private val preferredSuffixes = listOf(".PA", ".AS", ".DE", ".MI", ".BR", ".F")

    override fun resolve(isin: String): Listing? {
        val (_, body) = Http.get("https://query2.finance.yahoo.com/v1/finance/search?q=$isin&quotesCount=8&newsCount=0") ?: return null
        val quotes = runCatching { JSONObject(body).getJSONArray("quotes") }.getOrNull() ?: return null
        val symbols = (0 until quotes.length()).mapNotNull { quotes.optJSONObject(it)?.optString("symbol")?.takeIf(String::isNotBlank) }
        val best = preferredSuffixes.firstNotNullOfOrNull { suf -> symbols.firstOrNull { it.endsWith(suf) } } ?: symbols.firstOrNull()
        return best?.let { Listing(it, name) }
    }

    private fun chart(symbol: String, range: String): JSONObject? {
        val s = URLEncoder.encode(symbol, "UTF-8")
        val (_, body) = Http.get("https://query1.finance.yahoo.com/v8/finance/chart/$s?range=$range&interval=1d") ?: return null
        return runCatching { JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0) }.getOrNull()
    }

    override fun quote(listing: Listing): Quote? {
        // range=1d : chartPreviousClose = clôture de la veille (avec 5d c'était la clôture d'il y a 5 séances)
        val meta = chart(listing.symbol, "1d")?.optJSONObject("meta") ?: return null
        val price = meta.optDouble("regularMarketPrice").takeIf { !it.isNaN() } ?: return null
        val prev = meta.optDouble("previousClose").takeIf { !it.isNaN() && it > 0 }
            ?: meta.optDouble("chartPreviousClose").takeIf { !it.isNaN() && it > 0 }
        val high = meta.optDouble("fiftyTwoWeekHigh").takeIf { !it.isNaN() }
        var cur = meta.optString("currency", "EUR")
        // Les cotations londoniennes sont en pence
        val k = if (cur == "GBp" || cur == "GBX") { cur = "GBP"; 0.01 } else 1.0
        return Quote(price * k, prev?.times(k), cur, high?.times(k))
    }

    override fun history(listing: Listing, range: String): List<Pair<Long, Double>> {
        val res = chart(listing.symbol, range) ?: return emptyList()
        val ts = res.optJSONArray("timestamp") ?: return emptyList()
        val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return emptyList()
        return (0 until ts.length()).mapNotNull { i ->
            val c = closes.optDouble(i)
            if (c.isNaN()) null else ts.getLong(i) / 86_400 to c
        }
    }

    /** Cours intraday (timestamp en secondes, clôture de chaque intervalle). */
    fun intraday(symbol: String, range: String, interval: String): List<Pair<Long, Double>> {
        val s = URLEncoder.encode(symbol, "UTF-8")
        val (_, body) = Http.get("https://query1.finance.yahoo.com/v8/finance/chart/$s?range=$range&interval=$interval") ?: return emptyList()
        val res = runCatching { JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0) }.getOrNull() ?: return emptyList()
        val ts = res.optJSONArray("timestamp") ?: return emptyList()
        val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return emptyList()
        return (0 until ts.length()).mapNotNull { i ->
            val c = closes.optDouble(i)
            if (c.isNaN()) null else ts.getLong(i) to c
        }
    }

    /** Taux EUR -> devise (ex. 1,08 pour USD). */
    fun eurRate(currency: String): Double? =
        if (currency == "EUR") 1.0 else quote(Listing("EUR$currency=X", name))?.price
}

/**
 * Boursorama, en secours (notamment pour certains OPCVM / UC d'assurance vie).
 * Pas d'API : on lit la page de cotation. Fragile si le site change sa mise en page.
 */
class BoursoramaProvider : QuoteProvider {
    override val name = "Boursorama"

    override fun resolve(isin: String): Listing? {
        val (finalUrl, body) = Http.get("https://www.boursorama.com/recherche/?query=$isin") ?: return null
        if (finalUrl.contains("/cours/")) return Listing(finalUrl, name)
        // Page de résultats : premier lien vers une fiche de cotation
        val m = Regex("href=\"(/(?:cours|bourse/[a-z]+/cours)/[^\"]+)\"").find(body) ?: return null
        return Listing("https://www.boursorama.com" + m.groupValues[1], name)
    }

    private fun frNumber(s: String) = s.replace(" ", "").replace(" ", "").replace(",", ".").toDoubleOrNull()

    override fun quote(listing: Listing): Quote? {
        val (_, html) = Http.get(listing.symbol) ?: return null
        val last = Regex("data-ist-last[^>]*>\\s*([0-9  .,]+)").find(html)?.groupValues?.get(1)?.let(::frNumber) ?: return null
        val prev = Regex("data-ist-previousclose[^>]*>\\s*([0-9  .,]+)").find(html)?.groupValues?.get(1)?.let(::frNumber)
        val cur = Regex("c-instrument--last[^<]*</span>\\s*<span[^>]*>\\s*([A-Z]{3})").find(html)?.groupValues?.get(1) ?: "EUR"
        return Quote(last, prev, cur, null)
    }

    override fun history(listing: Listing, range: String): List<Pair<Long, Double>> = emptyList()
}
