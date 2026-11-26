package fr.eq.patrimo.assistant

import android.content.Context
import fr.eq.patrimo.data.Position
import fr.eq.patrimo.domain.Insight
import fr.eq.patrimo.domain.Operation
import fr.eq.patrimo.domain.Operations
import fr.eq.patrimo.domain.Ligne
import fr.eq.patrimo.domain.Portfolio
import fr.eq.patrimo.quotes.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class Etat { AUCUNE, EN_ATTENTE, VALIDEE, ANNULEE }

/** role : "user" | "assistant". Un message de l'assistant peut proposer des [operations] à valider. */
data class Message(
    val role: String,
    val texte: String,
    val operations: List<Operation> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val etat: Etat = Etat.AUCUNE
)

/** Réglages de l'assistant : toute API compatible « chat/completions » (Mistral, OpenAI, Ollama local…). */
class AssistantSettings(context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("baseUrl", "https://api.mistral.ai/v1")!!
        set(v) = prefs.edit().putString("baseUrl", v.trim().trimEnd('/')).apply()
    var apiKey: String
        get() = prefs.getString("apiKey", "")!!
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = prefs.getString("model", "mistral-small-latest")!!
        set(v) = prefs.edit().putString("model", v.trim()).apply()
}

class Assistant(private val settings: AssistantSettings) {

    private fun contexte(lignes: List<Ligne>, insights: List<Insight>, surveilles: List<Position>): String {
        val arr = JSONArray()
        lignes.forEach { l ->
            arr.put(JSONObject().apply {
                put("libelle", l.p.libelle); put("enveloppe", l.p.enveloppe); put("contrat", l.p.contrat)
                put("isin", l.p.isin ?: ""); put("type", l.p.type)
                put("parts", l.p.quantite); put("pru", l.p.pru); put("cours", l.p.dernierCours ?: JSONObject.NULL)
                put("investi", l.investi.round2()); put("valeur", l.valeur.round2()); put("gain", l.gain.round2())
                put("perf_pct", (l.perf * 100).round2()); put("frais_fonds_eur_an", l.fraisFondsAn.round2()); put("frais_contrat_eur_an", l.fraisContratAn.round2())
                put("droits_garde_eur_an", l.gardeAn.round2()); put("droits_garde_cumules_eur", l.gardeCumulee.round2())
                put("parts_relevees", l.p.quantite); put("parts_estimees", l.parts)
                l.p.tauxAnnuel?.let { put("taux_annuel_pct", (it * 100).round2()) }
            })
        }
        return """
Tu es l'assistant financier personnel de l'utilisateur, dans l'application Patrimo.
Réponds en français, brièvement et précisément, en t'appuyant UNIQUEMENT sur les données ci-dessous.
Montre les calculs quand c'est utile.
Les gains sont nets : frais des fonds déjà dans les cours, frais de contrat AV déduits en parts, droits de garde cumulés déduits. Si une donnée manque, dis-le.
Tu peux proposer des pistes d'amélioration (frais, diversification, fiscalité PEA/assurance vie),
en rappelant que ce ne sont pas des conseils en investissement personnalisés.

Total : ${Portfolio.eur(lignes.sumOf { it.valeur })} — investi : ${Portfolio.eur(lignes.sumOf { it.investi })}
Positions (JSON) : $arr
Constats automatiques : ${insights.joinToString(" | ") { it.titre + " : " + it.texte }}
Lignes « À surveiller » (hors patrimoine) : ${surveilles.joinToString { (it.libelle) + (it.isin?.let { i -> " ($i)" } ?: "") }}

Pour toute modification du portefeuille (achat, vente, ajout, modification, suppression, mise en surveillance),
appelle l'outil correspondant au lieu de répondre en texte : l'utilisateur validera chaque proposition dans l'appli.
Désigne une ligne par son ISIN quand tu le connais, sinon par son libellé, et précise le contrat en cas de doute.
""".trimIndent()
    }

    private fun propose(ops: List<Operation>, positions: List<Position>, texte: String?) = Message(
        "assistant", texte?.takeIf { it.isNotBlank() } ?: "Voici ce que je propose. Validez pour l'appliquer :",
        operations = ops, descriptions = ops.map { Operations.decrire(it, positions) }, etat = Etat.EN_ATTENTE
    )

    suspend fun repondre(historique: List<Message>, lignes: List<Ligne>, insights: List<Insight>, positions: List<Position>): Message {
        val surveilles = positions.filter { it.aSurveiller }
        // Serveur local (Ollama sur le PC, en http://) : pas de clé nécessaire
        val local = settings.baseUrl.startsWith("http://")
        if (settings.apiKey.isBlank() && !local) {
            Operations.comprendre(historique.last().texte)?.let { return propose(listOf(it), positions, null) }
            return Message("assistant", reponseLocale(historique.last().texte, lignes, insights))
        }
        return withContext(Dispatchers.IO) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", contexte(lignes, insights, surveilles)))
            historique.takeLast(12).dropWhile { it.role == "assistant" }.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.texte)) }
            val claude = settings.baseUrl.contains("anthropic.com")
            val body = JSONObject().put("model", settings.model).put("messages", msgs)
                .put("tools", Outils.definitions()).put("tool_choice", "auto")
                .apply { if (claude) put("max_tokens", 2048) else put("temperature", 0.2) } // Claude 5 refuse une température < 1
                .toString()
            val req = Request.Builder().url("${settings.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey.ifBlank { "ollama" }}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            runCatching {
                Http.client.newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (r.code == 429) return@use Message("assistant", "Limite gratuite atteinte (trop de questions en une minute). Réessayez dans une minute.")
                    if (!r.isSuccessful) {
                        // On affiche la raison donnée par le service, et on répond quand même hors ligne
                        val raison = runCatching { JSONObject(txt).let { j -> j.optString("message").ifBlank { j.optJSONObject("error")?.optString("message") ?: "" }.ifBlank { j.optString("detail") } } }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: txt.take(200)
                        val conseil = when (r.code) {
                            401 -> "Clé refusée : vérifiez qu'elle est copiée en entier."
                            403 -> "Accès refusé : clé pas encore active (attendez quelques minutes), ou modèle non autorisé pour votre offre (essayez mistral-small-2603)."
                            404 -> "Modèle ou adresse introuvable : vérifiez le nom du modèle et l'URL."
                            else -> "Vérifiez la clé, l'URL et le nom du modèle dans les réglages."
                        }
                        val repLocale = Operations.comprendre(historique.last().texte)?.let { return@use propose(listOf(it), positions, null) }
                            ?: reponseLocale(historique.last().texte, lignes, insights)
                        return@use Message("assistant", "$repLocale\n\n— Assistant en ligne indisponible (erreur ${r.code}) : $raison\n$conseil")
                    }
                    val m = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    val contenu = m.optString("content").takeIf { it != "null" }
                    val ops = Outils.lire(m.optJSONArray("tool_calls"))
                    if (ops.isNotEmpty()) propose(ops, positions, contenu) else Message("assistant", contenu ?: "")
                }
            }.getOrElse { Message("assistant", "Assistant injoignable : ${it.message}") }
        }
    }

    /** Sans clé d'API : quelques réponses calculées localement. */
    private fun reponseLocale(q0: String, lignes: List<Ligne>, insights: List<Insight>): String {
        if (lignes.isEmpty()) return "Importez d'abord votre fichier Excel."
        val q = q0.lowercase()
        val tri = lignes.sortedByDescending { it.perf }
        val env = lignes.map { it.p.enveloppe }.distinct().firstOrNull { q.contains(it.lowercase()) }
        return when {
            env != null -> lignes.filter { it.p.enveloppe == env }.let { l ->
                val v = l.sumOf { it.valeur }; val i = l.sumOf { it.investi }
                "$env : ${Portfolio.eur(v)} pour ${Portfolio.eur(i)} investis (${Portfolio.pct(if (i > 0) (v - i) / i else 0.0)}), ${l.size} lignes."
            }
            Regex("pire|perd|baisse").containsMatchIn(q) -> tri.last().let { "Votre pire ligne : ${it.p.libelle}, ${Portfolio.pct(it.perf)} (${Portfolio.signe(it.gain)})." }
            Regex("meilleur|gagn|hausse").containsMatchIn(q) -> tri.first().let { "Votre meilleure ligne : ${it.p.libelle}, ${Portfolio.pct(it.perf)} (${Portfolio.signe(it.gain)})." }
            Regex("conseil|amélior|suggest").containsMatchIn(q) -> insights.take(3).joinToString("\n") { "• " + it.texte }
            Regex("répart|alloc").containsMatchIn(q) -> Portfolio.parEnveloppe(lignes).joinToString(" · ") { "${it.first} ${Portfolio.eur(it.second)}" }
            else -> lignes.let { val v = it.sumOf { l -> l.valeur }; val i = it.sumOf { l -> l.investi }
                "Patrimoine : ${Portfolio.eur(v)} pour ${Portfolio.eur(i)} investis. Ajoutez une clé d'API dans les réglages pour des réponses libres." }
        }
    }

    private fun Double.round2() = Math.round(this * 100) / 100.0
}
