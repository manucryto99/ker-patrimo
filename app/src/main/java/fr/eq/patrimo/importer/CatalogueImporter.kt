package fr.eq.patrimo.importer

import java.io.InputStream

/**
 * Lit une liste de fonds (Excel .xlsx/.xlsm, CSV ou texte) : toute ligne contenant un ISIN est retenue,
 * le libellé est le texte le plus long de la ligne (hors ISIN, nombres et pourcentages).
 */
object CatalogueImporter {
    private val ISIN = Regex("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b")

    fun lire(bytes: ByteArray): List<Pair<String, String>> {
        val estZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val lignes: List<List<String>> = if (estZip) {
            XlsxReader.ouvrir(bytes.inputStream()).toutesLignes().map { r -> r.toSortedMap().values.toList() }
        } else {
            val texte = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                .let { if (it.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else it }
            texte.lines().map { l -> l.split(';', '\t', ',', '|').map { it.trim().trim('"') } }
        }
        val res = LinkedHashMap<String, String>()
        for (cells in lignes) {
            val isin = cells.firstNotNullOfOrNull { c -> ISIN.find(c.uppercase())?.groupValues?.get(1) } ?: continue
            val libelle = cells.map { it.replace(ISIN, "").trim() }
                .filter { c -> c.length > 2 && c.any { it.isLetter() } && !c.endsWith("%") }
                .maxByOrNull { it.length } ?: isin
            if (isin !in res) res[isin] = libelle
        }
        return res.toList()
    }

    fun lire(input: InputStream): List<Pair<String, String>> = lire(input.readBytes())
}
