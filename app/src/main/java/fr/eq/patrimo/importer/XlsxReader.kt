package fr.eq.patrimo.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lecteur .xlsx minimal sans dépendance : un .xlsx est un zip de fichiers XML.
 * Retourne les lignes d'un onglet sous forme de Map « en-tête -> valeur texte ».
 * Les nombres sont rendus tels qu'Excel les stocke ("480.5"), les dates en numéro de série ("45000").
 */
object XlsxReader {

    /** Classeur ouvert en mémoire : on peut y lire plusieurs onglets. */
    class Classeur internal constructor(private val files: Map<String, ByteArray>) {
        private val shared by lazy { files["xl/sharedStrings.xml"]?.let { XlsxReader.parseSharedStrings(it) } ?: emptyList() }
        /** Lit un onglet ; si [premierSiAbsent], retombe sur le premier onglet. Null si absent. */
        fun onglet(nom: String, premierSiAbsent: Boolean = false): List<Map<String, String>>? {
            val path = XlsxReader.findSheetPath(files, nom, premierSiAbsent) ?: return null
            return XlsxReader.toRows(XlsxReader.parseSheet(files.getValue(path), shared))
        }
        /** Lignes brutes du premier onglet. */
        fun premierOngletBrut(): List<Map<Int, String>> {
            val path = XlsxReader.findSheetPath(files, "", true) ?: return emptyList()
            return XlsxReader.parseSheet(files.getValue(path), shared)
        }
        /** Lignes brutes de tous les onglets (pour les listes de fonds : on cherche les ISIN partout). */
        fun toutesLignes(): List<Map<Int, String>> =
            files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.sorted()
                .flatMap { XlsxReader.parseSheet(files.getValue(it), shared) }
        /** Lignes brutes d'un onglet (n° de colonne à partir de 0 -> texte), sans en-tête. Nom exact, casse ignorée. */
        fun lignesBrutes(nom: String): List<Map<Int, String>>? {
            val path = XlsxReader.findSheetPath(files, nom, false) ?: return null
            return XlsxReader.parseSheet(files.getValue(path), shared)
        }
    }

    fun ouvrir(input: InputStream): Classeur {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name.removePrefix("/")] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        if ("xl/workbook.xml" !in files) throw IllegalArgumentException("Ce fichier n'est pas un classeur .xlsx")
        return Classeur(files)
    }

    private fun toRows(rows: List<Map<Int, String>>): List<Map<String, String>> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).map { row ->
            header.mapNotNull { (col, name) -> if (name.isBlank()) null else name.trim() to (row[col] ?: "") }.toMap()
        }.filter { r -> r.values.any { it.isNotBlank() } }
    }

    // Lecteur SANS gestion des espaces de noms : les attributs gardent leur préfixe (« r:id »).
    // (Sur Android, Xml.newPullParser() l'active par défaut : « r:id » devenait introuvable.)
    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(bytes.inputStream(), "UTF-8")
        }

    /** Attribut par nom, avec ou sans préfixe (« id » trouve aussi « r:id »). */
    private fun attr(p: XmlPullParser, nom: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == nom || n.endsWith(":$nom")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(bytes)
        var sb: StringBuilder? = null
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inT = false // ignore les annotations phonétiques
                }
                XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "t" -> inT = false
                    "si" -> { out.add(sb?.toString() ?: ""); sb = null }
                }
            }
        }
        return out
    }

    private fun findSheetPath(files: Map<String, ByteArray>, sheetName: String, premierSiAbsent: Boolean): String? {
        val wb = files["xl/workbook.xml"] ?: return null
        val sheets = ArrayList<Pair<String, String>>() // nom -> rId
        val p = parser(wb)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "sheet") {
                sheets.add((attr(p, "name") ?: "") to (attr(p, "r:id") ?: attr(p, "id") ?: ""))
            }
        }
        val rid = (sheets.firstOrNull { it.first.equals(sheetName, ignoreCase = true) } ?: if (premierSiAbsent) sheets.firstOrNull() else null)?.second
            ?: return null
        val rels = files["xl/_rels/workbook.xml.rels"] ?: return null
        val r = parser(rels)
        while (r.next() != XmlPullParser.END_DOCUMENT) {
            if (r.eventType == XmlPullParser.START_TAG && r.name.substringAfter(':') == "Relationship" && attr(r, "Id") == rid) {
                val target = attr(r, "Target") ?: return null
                return if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            }
        }
        return null
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<Map<Int, String>> {
        val rows = ArrayList<Map<Int, String>>()
        val p = parser(bytes)
        var row: HashMap<Int, String>? = null
        var col = 0
        var type: String? = null
        val value = StringBuilder()
        var capture = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "row" -> row = HashMap()
                    "c" -> {
                        col = colIndex(attr(p, "r") ?: "A1")
                        type = attr(p, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "VRAI" else "FAUX"
                            else -> raw
                        }
                        if (text.isNotEmpty()) row?.put(col, text)
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null }
                }
            }
        }
        return rows
    }
}
