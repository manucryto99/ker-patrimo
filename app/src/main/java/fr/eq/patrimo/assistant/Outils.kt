package fr.eq.patrimo.assistant

import fr.eq.patrimo.data.Position
import fr.eq.patrimo.domain.Operation
import org.json.JSONArray
import org.json.JSONObject

/** Outils proposés au modèle (« function calling », format compatible Mistral / OpenAI). */
object Outils {
    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun outil(nom: String, desc: String, props: Map<String, JSONObject>, requis: List<String>) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", nom).put("description", desc).put("parameters",
            JSONObject().put("type", "object").put("properties", JSONObject(props as Map<*, *>)).put("required", JSONArray(requis))))

    private val LIGNE = prop("string", "ISIN, ticker ou libellé de la ligne")
    private val CONTRAT = prop("string", "Contrat ou compte (ex. PEA, CARDIF, BoursoVie), si plusieurs lignes correspondent")

    fun definitions(): JSONArray = JSONArray()
        .put(outil("enregistrer_achat", "Achat de parts d'une ligne existante (renforcement). Recalcule le prix de revient.",
            mapOf("ligne" to LIGNE, "contrat" to CONTRAT, "quantite" to prop("number", "Nombre de parts achetées"),
                "prix_unitaire" to prop("number", "Prix par part en euros"), "frais" to prop("number", "Frais d'achat en euros")),
            listOf("ligne", "quantite", "prix_unitaire")))
        .put(outil("enregistrer_vente", "Vente de parts d'une ligne. Une ligne vendue en totalité passe dans « À surveiller ».",
            mapOf("ligne" to LIGNE, "contrat" to CONTRAT, "quantite" to prop("number", "Nombre de parts vendues"),
                "prix_unitaire" to prop("number", "Prix de vente par part en euros")),
            listOf("ligne", "quantite")))
        .put(outil("ajouter_ligne", "Nouvelle ligne de placement.",
            mapOf("libelle" to prop("string", "Nom du support"), "isin" to prop("string", "ISIN ou ticker crypto"),
                "enveloppe" to prop("string", "Assurance vie, PEA, CTO, Crypto, PER…"), "etablissement" to prop("string", "Banque ou assureur"),
                "contrat" to prop("string", "Nom du contrat ou compte"), "type" to prop("string", "Action, ETF, OPCVM/UC, Fonds euros, Liquidités, Crypto"),
                "quantite" to prop("number", "Nombre de parts"), "prix_revient" to prop("number", "Prix de revient unitaire en euros"),
                "valeur" to prop("number", "Valeur en euros, pour un support sans cotation (fonds euros, liquidités)")),
            listOf("libelle", "enveloppe", "contrat", "type")))
        .put(outil("modifier_ligne", "Corrige des champs d'une ligne existante.",
            mapOf("ligne" to LIGNE, "contrat" to CONTRAT, "champs" to JSONObject().put("type", "object").put("description",
                "Champs à modifier, valeurs en texte : parts, pru, libelle, isin, contrat, etablissement, enveloppe, type, valeur, taux (%), frais (%), notes")
                .put("additionalProperties", JSONObject().put("type", "string"))),
            listOf("ligne", "champs")))
        .put(outil("supprimer_ligne", "Supprime définitivement une ligne.", mapOf("ligne" to LIGNE, "contrat" to CONTRAT), listOf("ligne")))
        .put(outil("surveiller", "Place une ligne existante, ou un nouveau titre, dans « À surveiller ».",
            mapOf("ligne" to LIGNE, "libelle" to prop("string", "Nom à afficher si c'est un nouveau titre")), listOf("ligne")))

    /** tool_calls de la réponse -> opérations. Les appels mal formés sont ignorés. */
    fun lire(appels: JSONArray?): List<Operation> {
        if (appels == null) return emptyList()
        return (0 until appels.length()).mapNotNull { i ->
            runCatching {
                val f = appels.getJSONObject(i).getJSONObject("function")
                val a = when (val args = f.get("arguments")) { is JSONObject -> args; else -> JSONObject(args.toString()) }
                fun s(k: String) = a.optString(k).takeIf { it.isNotBlank() && it != "null" }
                fun d(k: String) = if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { !it.isNaN() } else null
                when (f.getString("name")) {
                    "enregistrer_achat" -> Operation.Achat(s("ligne")!!, s("contrat"), d("quantite")!!, d("prix_unitaire")!!, d("frais") ?: 0.0)
                    "enregistrer_vente" -> Operation.Vente(s("ligne")!!, s("contrat"), d("quantite")!!, d("prix_unitaire"))
                    "ajouter_ligne" -> Operation.Ajout(Position(
                        enveloppe = s("enveloppe") ?: "Autre", etablissement = s("etablissement") ?: "", contrat = s("contrat") ?: "",
                        libelle = s("libelle")!!, isin = s("isin")?.uppercase(), type = s("type") ?: "Autre",
                        quantite = d("quantite") ?: 0.0, pru = d("prix_revient") ?: 0.0, valeurManuelle = d("valeur"),
                        tauxAnnuel = null, dateAchat = null, fraisAnnuels = null, notes = null))
                    "modifier_ligne" -> {
                        val c = a.getJSONObject("champs")
                        Operation.Modification(s("ligne")!!, s("contrat"), c.keys().asSequence().associateWith { c.get(it).toString() })
                    }
                    "supprimer_ligne" -> Operation.Suppression(s("ligne")!!, s("contrat"))
                    "surveiller" -> Operation.Surveiller(s("ligne")!!, null, s("libelle"))
                    else -> null
                }
            }.getOrNull()
        }
    }
}
