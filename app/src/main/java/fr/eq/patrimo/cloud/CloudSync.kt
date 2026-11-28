package fr.eq.patrimo.cloud

import android.content.Context
import fr.eq.patrimo.data.AppDatabase
import fr.eq.patrimo.data.Contrat
import fr.eq.patrimo.data.PointHistorique
import fr.eq.patrimo.data.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Synchronisation avec Supabase (projet « ker-patrimo »).
 *  - historique : fusion ligne par ligne (profil, jour, portefeuille), le dernier écrit l'emporte ;
 *  - état courant (positions + contrats) : un bloc par patrimoine ; le plus récemment modifié l'emporte,
 *    la version locale remplacée est gardée en sauvegarde sur le téléphone.
 * Seuls les comptes dont l'e-mail est dans la table « membres » peuvent lire ou écrire (RLS côté serveur).
 */
object CloudSync {
    val BASES = mapOf("patrimo.db" to "emmanuel", "patrimo_natalia.db" to "natalia", "patrimo_estelle.db" to "estelle")

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private val verrou = Mutex()
    private val silence = ConcurrentHashMap<String, Int>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    data class Config(val url: String, val cle: String, val email: String, val connecte: Boolean, val derniereSync: Long, val message: String)

    fun config(ctx: Context): Config = prefs(ctx).let {
        Config(it.getString("url", "")!!, it.getString("cle", "")!!, it.getString("email", "")!!,
            it.getString("refresh", null) != null, it.getLong("derniere", 0), it.getString("message", "")!!)
    }

    fun estConfigure(ctx: Context) = config(ctx).let { it.connecte && it.url.isNotBlank() && it.cle.isNotBlank() }

    // ---------------------------------------------------------------- Authentification

    suspend fun connecter(ctx: Context, url: String, cle: String, email: String, motDePasse: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = url.trim().trimEnd('/')
            val corps = JSONObject().put("email", email.trim()).put("password", motDePasse).toString()
            val req = Request.Builder().url("$base/auth/v1/token?grant_type=password")
                .header("apikey", cle.trim()).post(corps.toRequestBody(JSON)).build()
            val j = http.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) error(messageErreur(r.code, t))
                JSONObject(t)
            }
            prefs(ctx).edit().putString("url", base).putString("cle", cle.trim()).putString("email", email.trim().lowercase())
                .putString("acces", j.getString("access_token")).putString("refresh", j.getString("refresh_token"))
                .putLong("expire", j.optLong("expires_at", System.currentTimeMillis() / 1000 + 3000)).apply()
            "Connecté : ${email.trim()}"
        }
    }

    fun deconnecter(ctx: Context) {
        prefs(ctx).edit().remove("acces").remove("refresh").remove("expire").putString("message", "Déconnecté").apply()
    }

    private fun jeton(ctx: Context): String {
        val p = prefs(ctx)
        val exp = p.getLong("expire", 0)
        val acces = p.getString("acces", null)
        if (acces != null && exp - 120 > System.currentTimeMillis() / 1000) return acces
        val refresh = p.getString("refresh", null) ?: error("Non connecté")
        val req = Request.Builder().url("${p.getString("url", "")}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", p.getString("cle", "")!!)
            .post(JSONObject().put("refresh_token", refresh).toString().toRequestBody(JSON)).build()
        val j = http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (r.code in 400..401) deconnecter(ctx)
                error("Session expirée, reconnecte-toi dans Réglages → Cloud")
            }
            JSONObject(t)
        }
        p.edit().putString("acces", j.getString("access_token")).putString("refresh", j.getString("refresh_token"))
            .putLong("expire", j.optLong("expires_at", System.currentTimeMillis() / 1000 + 3000)).apply()
        return j.getString("access_token")
    }

    private fun messageErreur(code: Int, corps: String): String {
        val m = runCatching { JSONObject(corps).let { it.optString("msg").ifBlank { it.optString("message").ifBlank { it.optString("error_description") } } } }.getOrNull().orEmpty()
        return when {
            m.contains("Invalid login", true) -> "E-mail ou mot de passe incorrect"
            m.contains("row-level security", true) || code == 403 -> "Compte non autorisé : ajoute ton e-mail dans la table « membres »"
            code == 404 && m.contains("relation", true) -> "Tables absentes : exécute le script SQL dans Supabase"
            else -> "Erreur $code ${m.take(160)}"
        }
    }

    // ---------------------------------------------------------------- Appels REST

    private fun rest(ctx: Context, table: String, params: Map<String, String> = emptyMap()): HttpUrl =
        "${prefs(ctx).getString("url", "")}/rest/v1/$table".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private fun appel(ctx: Context, b: Request.Builder): String {
        val req = b.header("apikey", prefs(ctx).getString("cle", "")!!).header("Authorization", "Bearer ${jeton(ctx)}").build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) error(messageErreur(r.code, t))
            t
        }
    }

    private fun upsert(ctx: Context, table: String, lignes: JSONArray, retour: Boolean = false): String =
        appel(ctx, Request.Builder().url(rest(ctx, table))
            .header("Prefer", "resolution=merge-duplicates," + if (retour) "return=representation" else "return=minimal")
            .post(lignes.toString().toRequestBody(JSON)))

    // ---------------------------------------------------------------- Suivi des modifications locales

    /** Empreinte des champs saisis par l'utilisateur (les cours, rafraîchis en continu, n'en font pas partie). */
    fun empreinte(pos: List<Position>, cs: List<Contrat>): String {
        val s = StringBuilder()
        pos.sortedBy { it.id }.forEach { p ->
            s.append(listOf(p.id, p.enveloppe, p.etablissement, p.contrat, p.libelle, p.isin, p.type, p.quantite, p.pru, p.valeurManuelle,
                p.tauxAnnuel, p.dateAchat, p.fraisAnnuels, p.notes, p.aSurveiller, p.disponibilites, p.partsAu).joinToString("|")).append('\n')
        }
        cs.sortedBy { it.etablissement + "|" + it.contrat }.forEach { s.append(it.toString()).append('\n') }
        return java.security.MessageDigest.getInstance("SHA-256").digest(s.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Appelé à chaque changement des positions / contrats dans l'appli : note l'heure d'une modification de l'utilisateur. */
    fun noterModif(ctx: Context, base: String, pos: List<Position>, cs: List<Contrat>) {
        if ((silence[base] ?: 0) > 0) return
        val p = prefs(ctx)
        val e = empreinte(pos, cs)
        val avant = p.getString("emp_$base", null) ?: return   // jamais synchronisé : géré à la 1re synchro
        if (e != avant) p.edit().putString("emp_$base", e)
            .putLong("modif_$base", p.getLong("modif_$base", 0).takeIf { it > 0 } ?: System.currentTimeMillis()).apply()
    }

    /**
     * Exécute un traitement automatique (cours, frais déduits en parts) sans le compter comme une modification :
     * chaque téléphone déduit lui-même les frais depuis la date « parts exactes au », le résultat est identique.
     */
    suspend fun <T> sansModif(ctx: Context, base: String, bloc: suspend () -> T): T {
        silence.merge(base, 1, { a, b -> a + b })
        try {
            return bloc()
        } finally {
            val dao = AppDatabase.get(ctx, base).dao()
            runCatching {
                val p = prefs(ctx)
                if (p.getString("emp_$base", null) != null) p.edit().putString("emp_$base", empreinte(dao.positions(), dao.contrats())).apply()
            }
            silence.merge(base, -1, { a, b -> a + b })
        }
    }

    /** Historique importé, reconstitué ou effacé : tout renvoyer à la prochaine synchro. */
    fun historiqueModifie(ctx: Context, base: String, vider: Boolean = false) {
        prefs(ctx).edit().putBoolean("histo_complet_$base", false).apply { if (vider) putBoolean("histo_vider_$base", true) }.apply()
    }

    // ---------------------------------------------------------------- Synchronisation

    data class Bilan(val envoyes: Int, val recus: Int, val etats: List<String>)

    suspend fun synchroniserTout(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        if (!estConfigure(ctx)) return@withContext Result.failure(IllegalStateException("Cloud non configuré"))
        verrou.withLock {
            val r = runCatching {
                val bilans = BASES.keys.map { synchroniser(ctx, it) }
                val e = bilans.flatMap { it.etats }
                "Synchro OK · ${bilans.sumOf { it.envoyes }} valeurs envoyées, ${bilans.sumOf { it.recus }} reçues" +
                    (if (e.isNotEmpty()) " · " + e.joinToString(", ") else "")
            }
            prefs(ctx).edit().putString("message", r.getOrElse { "Échec : ${it.message}" })
                .apply { if (r.isSuccess) putLong("derniere", System.currentTimeMillis()) }.apply()
            r
        }
    }

    private suspend fun synchroniser(ctx: Context, base: String): Bilan {
        val profil = BASES.getValue(base)
        val existe = base == "patrimo.db" || ctx.getDatabasePath(base).exists()
        val dao = AppDatabase.get(ctx, base).dao()
        val p = prefs(ctx)
        val etats = mutableListOf<String>()

        // ---- 1. État courant (positions + contrats)
        val distant = JSONArray(appel(ctx, Request.Builder().url(rest(ctx, "etat_profil",
            mapOf("profil" to "eq.$profil", "select" to "maj,positions,contrats,appareil"))).get())).optJSONObject(0)
        val pos = dao.positions(); val cs = dao.contrats()
        val emp = empreinte(pos, cs)
        val empConnue = p.getString("emp_$base", null)
        val modif = p.getLong("modif_$base", 0)
        val localChange = empConnue == null || emp != empConnue || modif > 0
        val majDistante = distant?.getString("maj")
        val distantChange = distant != null && majDistante != p.getString("majd_$base", null)
        val localVide = pos.none { !it.aSurveiller }
        val distantVide = distant == null || distant.getJSONArray("positions").length() == 0

        val action = when {
            distant == null -> if (!localVide) "envoyer" else "rien"
            empConnue == null -> if (!distantVide || localVide) "recevoir" else "envoyer"   // 1re synchro : le cloud fait foi s'il a des données
            localChange && !distantChange -> "envoyer"
            !localChange && distantChange -> "recevoir"
            localChange && distantChange -> {
                val tLocal = if (modif > 0) modif else System.currentTimeMillis()
                val tDistant = OffsetDateTime.parse(majDistante!!).toInstant().toEpochMilli()
                if (tLocal >= tDistant) "envoyer" else "recevoir"
            }
            else -> "rien"
        }
        var majFinale = majDistante
        when (action) {
            "envoyer" -> {
                val ligne = JSONObject().put("profil", profil)
                    .put("positions", JSONArray(pos.map(::posJson))).put("contrats", JSONArray(cs.map(::contratJson)))
                    .put("appareil", android.os.Build.MODEL)
                majFinale = JSONArray(upsert(ctx, "etat_profil", JSONArray().put(ligne), retour = true)).getJSONObject(0).getString("maj")
                etats += "${nom(profil)} envoyé"
            }
            "recevoir" -> {
                if (!distantVide || !localVide) {
                    // Sauvegarde de la version locale remplacée
                    if (!localVide) p.edit().putString("sauvegarde_$base", JSONObject()
                        .put("date", System.currentTimeMillis()).put("positions", JSONArray(pos.map(::posJson)))
                        .put("contrats", JSONArray(cs.map(::contratJson))).toString()).apply()
                    val np = distant!!.getJSONArray("positions").let { a -> (0 until a.length()).map { jsonPos(a.getJSONObject(it)) } }
                    val nc = distant.getJSONArray("contrats").let { a -> (0 until a.length()).map { jsonContrat(a.getJSONObject(it)) } }
                    silence.merge(base, 1, { a, b -> a + b })
                    try { dao.replaceImport(np, nc) } finally { silence.merge(base, -1, { a, b -> a + b }) }
                    etats += "${nom(profil)} reçu (${distant.optString("appareil")})"
                }
            }
        }
        p.edit().putString("emp_$base", empreinte(dao.positions(), dao.contrats())).putLong("modif_$base", 0)
            .apply { if (majFinale != null) putString("majd_$base", majFinale) }.apply()

        // ---- 2. Historique
        if (p.getBoolean("histo_vider_$base", false)) {
            appel(ctx, Request.Builder().url(rest(ctx, "historique", mapOf("profil" to "eq.$profil", "source" to "neq.auto"))).delete())
            p.edit().putBoolean("histo_vider_$base", false).apply()
        }
        var envoyes = 0
        if (existe) {
            val complet = p.getBoolean("histo_complet_$base", false)
            val depuis = if (complet) LocalDate.now().toEpochDay() - 7 else Long.MIN_VALUE
            val aEnvoyer = dao.historiqueDepuis(depuis)
            aEnvoyer.chunked(1000).forEach { lot ->
                upsert(ctx, "historique", JSONArray(lot.map { h ->
                    JSONObject().put("profil", profil).put("jour", h.epochDay).put("portefeuille", h.portefeuille)
                        .put("valeur", h.valeur).put("investi", h.investi ?: JSONObject.NULL).put("source", h.source)
                }))
                envoyes += lot.size
            }
            p.edit().putBoolean("histo_complet_$base", true).apply()
        }
        // Réception : tout ce qui a changé depuis la dernière synchro (avec 2 min de recouvrement)
        val depuisMaj = p.getString("histo_majd_$base", null)
        var recus = 0; var offset = 0; var maxMaj = depuisMaj
        while (true) {
            val params = mutableMapOf("profil" to "eq.$profil", "select" to "jour,portefeuille,valeur,investi,source,maj",
                "order" to "maj.asc", "limit" to "1000", "offset" to "$offset")
            if (depuisMaj != null) params["maj"] = "gte." + OffsetDateTime.parse(depuisMaj).minusMinutes(2).toString()
            val a = JSONArray(appel(ctx, Request.Builder().url(rest(ctx, "historique", params)).get()))
            if (a.length() == 0) break
            val pts = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                maxMaj = o.getString("maj")
                PointHistorique(o.getLong("jour"), o.getString("portefeuille"), o.getDouble("valeur"),
                    if (o.isNull("investi")) null else o.getDouble("investi"), o.optString("source", "auto"))
            }
            dao.upsertHistorique(pts)
            recus += pts.size; offset += a.length()
            if (a.length() < 1000) break
        }
        if (maxMaj != null) p.edit().putString("histo_majd_$base", maxMaj).apply()
        return Bilan(envoyes, (recus - envoyes).coerceAtLeast(0), etats)
    }

    private fun nom(profil: String) = profil.replaceFirstChar { it.uppercase() }

    // ---------------------------------------------------------------- JSON

    private fun JSONObject.opt(k: String, v: Any?) = put(k, v ?: JSONObject.NULL)
    private fun JSONObject.d(k: String) = if (isNull(k) || !has(k)) null else getDouble(k)
    private fun JSONObject.l(k: String) = if (isNull(k) || !has(k)) null else getLong(k)
    private fun JSONObject.s(k: String) = if (isNull(k) || !has(k)) null else getString(k)

    private fun posJson(p: Position) = JSONObject().put("id", p.id).put("enveloppe", p.enveloppe).put("etablissement", p.etablissement)
        .put("contrat", p.contrat).put("libelle", p.libelle).opt("isin", p.isin).put("type", p.type).put("quantite", p.quantite)
        .put("pru", p.pru).opt("valeurManuelle", p.valeurManuelle).opt("tauxAnnuel", p.tauxAnnuel).opt("dateAchat", p.dateAchat)
        .opt("fraisAnnuels", p.fraisAnnuels).opt("notes", p.notes).opt("symbole", p.symbole).opt("source", p.source)
        .opt("dernierCours", p.dernierCours).opt("coursVeille", p.coursVeille).opt("plusHaut52s", p.plusHaut52s)
        .opt("majCours", p.majCours).put("aSurveiller", p.aSurveiller).opt("disponibilites", p.disponibilites).opt("partsAu", p.partsAu)

    private fun jsonPos(o: JSONObject) = Position(id = o.getLong("id"), enveloppe = o.getString("enveloppe"), etablissement = o.getString("etablissement"),
        contrat = o.getString("contrat"), libelle = o.getString("libelle"), isin = o.s("isin"), type = o.getString("type"),
        quantite = o.getDouble("quantite"), pru = o.getDouble("pru"), valeurManuelle = o.d("valeurManuelle"), tauxAnnuel = o.d("tauxAnnuel"),
        dateAchat = o.l("dateAchat"), fraisAnnuels = o.d("fraisAnnuels"), notes = o.s("notes"), symbole = o.s("symbole"), source = o.s("source"),
        dernierCours = o.d("dernierCours"), coursVeille = o.d("coursVeille"), plusHaut52s = o.d("plusHaut52s"), majCours = o.l("majCours"),
        aSurveiller = o.optBoolean("aSurveiller"), disponibilites = o.s("disponibilites"), partsAu = o.l("partsAu"))

    private fun contratJson(c: Contrat) = JSONObject().put("etablissement", c.etablissement).put("contrat", c.contrat).put("enveloppe", c.enveloppe)
        .put("fraisGestionUc", c.fraisGestionUc).put("fraisGestionEuro", c.fraisGestionEuro).put("droitsDeGarde", c.droitsDeGarde)
        .put("droitsFixesParLigne", c.droitsFixesParLigne).opt("plafondGarde", c.plafondGarde).opt("dateReleve", c.dateReleve)
        .put("source", c.source).put("modifie", c.modifie).opt("verse", c.verse)

    private fun jsonContrat(o: JSONObject) = Contrat(etablissement = o.getString("etablissement"), contrat = o.getString("contrat"),
        enveloppe = o.getString("enveloppe"), fraisGestionUc = o.optDouble("fraisGestionUc", 0.0), fraisGestionEuro = o.optDouble("fraisGestionEuro", 0.0),
        droitsDeGarde = o.optString("droitsDeGarde", ""), droitsFixesParLigne = o.optDouble("droitsFixesParLigne", 0.0),
        plafondGarde = o.d("plafondGarde"), dateReleve = o.l("dateReleve"), source = o.optString("source", ""),
        modifie = o.optBoolean("modifie"), verse = o.d("verse"))
}
