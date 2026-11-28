package fr.eq.patrimo.importer

import fr.eq.patrimo.data.PointHistorique
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Lit un historique de valeur (premier onglet). Deux formats acceptés :
 *  - « long » : colonnes Date | Portefeuille | Valeur (| Investi facultatif) ;
 *  - « large » : colonne Date puis une colonne par portefeuille (TOTAL, Assurance vie, CARDIF…).
 * Dates : date Excel, jj/mm/aaaa, jj-mm-aaaa ou aaaa-mm-jj. Montants : 12345,67 ou 12 345.67 €.
 */
object HistoriqueImporter {
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()

    fun date(s: String): Long? {
        val t = s.trim()
        t.toDoubleOrNull()?.let { n -> if (n in 20000.0..80000.0) return LocalDate.of(1899, 12, 30).plusDays(n.toLong()).toEpochDay() }
        for (f in listOf("d/M/yyyy", "d-M-yyyy", "yyyy-M-d", "d.M.yyyy", "d/M/yy")) {
            runCatching { return LocalDate.parse(t.take(10).trim(), DateTimeFormatter.ofPattern(f)).toEpochDay() }
        }
        return null
    }

    fun montant(s: String): Double? = s.replace("€", "").replace("\u00A0", "").replace("\u202F", "").replace(" ", "")
        .let { if (it.count { c -> c == ',' } == 1 && !it.contains('.')) it.replace(',', '.') else it.replace(",", "") }
        .toDoubleOrNull()

    fun lire(input: InputStream): List<PointHistorique> {
        val bytes = input.readBytes()
        val lignes: List<List<String>> = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            XlsxReader.ouvrir(bytes.inputStream()).premierOngletBrut().map { r -> (0..(r.keys.maxOrNull() ?: -1)).map { r[it] ?: "" } }
        } else String(bytes, Charsets.UTF_8).lines().map { l -> l.split(';', '\t').map { it.trim().trim('"') } }
        val iEntete = lignes.indexOfFirst { l -> l.any { norm(it) == "date" } }.takeIf { it >= 0 } ?: return emptyList()
        val entete = lignes[iEntete].map { norm(it) }
        val cDate = entete.indexOf("date")
        val cPort = entete.indexOfFirst { it.startsWith("portefeuille") || it == "compte" || it == "contrat" || it == "categorie" }
        val cVal = entete.indexOfFirst { it.startsWith("valeur") || it == "montant" }
        val cInv = entete.indexOfFirst { it.startsWith("investi") || it.startsWith("verse") }
        val res = ArrayList<PointHistorique>()
        for (l in lignes.drop(iEntete + 1)) {
            val d = l.getOrNull(cDate)?.let(::date) ?: continue
            if (cPort >= 0 && cVal >= 0) {
                val p = l.getOrNull(cPort)?.trim().orEmpty().ifEmpty { "TOTAL" }
                val v = l.getOrNull(cVal)?.let(::montant) ?: continue
                res += PointHistorique(d, if (norm(p) == "total") "TOTAL" else p, v, l.getOrNull(cInv)?.let(::montant), "import")
            } else {
                lignes[iEntete].forEachIndexed { i, nom ->
                    if (i == cDate || nom.isBlank() || i == cInv) return@forEachIndexed
                    val v = l.getOrNull(i)?.let(::montant) ?: return@forEachIndexed
                    res += PointHistorique(d, if (norm(nom) == "total") "TOTAL" else nom.trim(), v, null, "import")
                }
            }
        }
        return res.distinctBy { it.epochDay to it.portefeuille }
    }
}
