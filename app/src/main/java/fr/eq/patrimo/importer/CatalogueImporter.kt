package fr.eq.patrimo.importer

import java.io.InputStream

/**
 * Lit une liste de fonds (Excel .xlsx/.xlsm, CSV ou texte) : toute ligne contenant un ISIN est retenue,
 * le libellé est le texte le plus long de la ligne (hors ISIN, nombres et pourcentages).
 */
object CatalogueImporter {
    /** Préfixe de clé des fonds sans ISIN (la table impose une clé). */
    const val SANS_ISIN = "SANS-ISIN:"
    private val ISIN = Regex("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b")

    fun lire(bytes: ByteArray): List<Pair<String, String>> {
        val estZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val lignes: List<List<String>> = if (estZip) {
            XlsxReader.ouvrir(bytes.inputStream()).toutesLignes().map { r -> (0..(r.keys.maxOrNull() ?: -1)).map { r[it] ?: "" } }
        } else {
            val texte = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                .let { if (it.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else it }
            texte.lines().map { l -> l.split(';', '\t', ',', '|').map { it.trim().trim('"') } }
        }
        val res = LinkedHashMap<String, String>()
        // Si une ligne d'en-tête nomme la colonne du libellé, on l'utilise (sinon : texte le plus long de la ligne)
        val entete = lignes.take(30).firstOrNull { l -> l.any { it.lowercase().let { c -> c.startsWith("libell") || c == "nom" || c == "nom du fonds" || c == "support" } } }
        val colLib = entete?.indexOfFirst { it.lowercase().let { c -> c.startsWith("libell") || c == "nom" || c == "nom du fonds" || c == "support" } } ?: -1
        for (cells in lignes) {
            // Fonds sans ISIN (FCPE d'entreprise, ex. PEE / PER Thales) : repris par son libellé si la colonne Libellé est connue
            val sansIsin = cells.none { c -> ISIN.containsMatchIn(c.uppercase()) }
            if (sansIsin) {
                val lib = cells.getOrNull(colLib)?.trim().orEmpty()
                if (colLib >= 0 && cells !== entete && lib.length > 2 && lib.any { it.isLetter() }) {
                    val cle = SANS_ISIN + lib.uppercase()
                    if (cle !in res) res[cle] = lib
                }
                continue
            }
            val isin = cells.firstNotNullOfOrNull { c -> ISIN.find(c.uppercase())?.groupValues?.get(1) } ?: continue
            val libelle = cells.getOrNull(colLib)?.takeIf { colLib >= 0 && it.isNotBlank() && !ISIN.matches(it.trim()) } ?: cells.map { it.replace(ISIN, "").trim() }
                .filter { c -> c.length > 2 && c.any { it.isLetter() } && !c.endsWith("%") }
                .maxByOrNull { it.length } ?: isin
            if (isin !in res) res[isin] = libelle
        }
        return res.toList()
    }

    fun lire(input: InputStream): List<Pair<String, String>> = lire(input.readBytes())
}
