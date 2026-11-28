package fr.eq.patrimo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.eq.patrimo.assistant.Assistant
import fr.eq.patrimo.assistant.AssistantSettings
import fr.eq.patrimo.assistant.Message
import fr.eq.patrimo.data.AppDatabase
import fr.eq.patrimo.domain.Insight
import fr.eq.patrimo.domain.InsightEngine
import fr.eq.patrimo.domain.Ligne
import fr.eq.patrimo.domain.Periode
import fr.eq.patrimo.domain.PointCourbe
import fr.eq.patrimo.domain.Portfolio
import fr.eq.patrimo.importer.PortfolioImporter
import fr.eq.patrimo.quotes.QuoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortfolioViewModel(app: Application, val base: String = "patrimo.db") : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app, base).dao()

    /** Fabrique : un ViewModel par patrimoine (base de données distincte). */
    class Fabrique(private val app: Application, private val base: String) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = PortfolioViewModel(app, base) as T
    }
    private val quotes = QuoteService(dao)
    val settings = AssistantSettings(app)
    private val assistant = Assistant(settings)

    /** Toutes les lignes en base, y compris « À surveiller ». */
    val positions: StateFlow<List<fr.eq.patrimo.data.Position>> = dao.observePositions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Le patrimoine : lignes détenues (hors « À surveiller »), frais appliqués. */
    val lignes: StateFlow<List<Ligne>> = combine(dao.observePositions(), dao.observeContrats()) { p, c -> Portfolio.lignes(p.filter { !it.aSurveiller }, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Onglet « À surveiller ». */
    val surveilles: StateFlow<List<fr.eq.patrimo.data.Position>> = positions.map { l -> l.filter { it.aSurveiller }.sortedBy { it.libelle } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val insights: StateFlow<List<Insight>> = lignes.map { InsightEngine.analyse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _periode = MutableStateFlow(Periode.A1)
    val periode = _periode.asStateFlow()
    private val historyVersion = MutableStateFlow(0)
    /** Catégorie affichée sur l'accueil : null = tout le patrimoine, sinon « Assurance vie », « PEA », « CTO », « Crypto ». */
    private val _categorie = MutableStateFlow<String?>(null)
    val categorie = _categorie.asStateFlow()
    fun setCategorie(c: String?) { _categorie.value = c; _comptes.value = emptySet() }

    /** Contrats / comptes cochés dans la catégorie (vide = tous). Plusieurs possibles (ex. Revolut + Trade Republic). */
    private val _comptes = MutableStateFlow<Set<String>>(emptySet())
    val comptes = _comptes.asStateFlow()
    fun basculerCompte(c: String) { _comptes.update { if (c in it) it - c else it + c } }
    fun tousLesComptes() { _comptes.value = emptySet() }

    val courbe: StateFlow<List<PointCourbe>> = combine(lignes, _periode, historyVersion, _categorie, _comptes) { l, p, _, c, sel ->
            l.filter { (c == null || it.p.enveloppe == c) && (sel.isEmpty() || it.p.contrat in sel) } to p }
        .mapLatest { (l, p) ->
            if (p.intraday) return@mapLatest courbeIntraday(l, p)
            withContext(Dispatchers.IO) {
                val depuis = LocalDate.now().toEpochDay() - p.jours
                Portfolio.courbe(l, dao.pricesSince(depuis), dao.snapshotsSince(depuis), depuis)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val yahoo = fr.eq.patrimo.quotes.YahooProvider()
    /** Cache des séries intraday : (symbole, période) -> (heure de chargement, série). */
    private val cacheIntraday = java.util.concurrent.ConcurrentHashMap<Pair<String, Periode>, Pair<Long, List<Pair<Long, Double>>>>()

    /** Séries intraday (par symbole Yahoo) et VL quotidiennes récentes (par ISIN), pour les vues glissantes 1H / 1J. */
    private suspend fun seriesIntraday(l: List<Ligne>, p: Periode): Map<String, List<Pair<Long, Double>>> {
        val maintenant = System.currentTimeMillis() / 1000
        val symboles = l.filter { it.estCote && it.p.source == "Yahoo" }.mapNotNull { it.p.symbole }.distinct()
        val (range, interval) = if (p == Periode.H1) "1d" to "1m" else "5d" to "5m"
        return kotlinx.coroutines.coroutineScope {
            symboles.map { s ->
                async {
                    val c = cacheIntraday[s to p]
                    val serie = if (c != null && maintenant - c.first < 120) c.second
                        else runCatching { yahoo.intraday(s, range, interval) }.getOrDefault(emptyList()).also { cacheIntraday[s to p] = maintenant to it }
                    s to serie
                }
            }.awaitAll().toMap().filterValues { it.isNotEmpty() }
        }
    }

    private suspend fun seriesQuotidiennes(): Map<String, List<Pair<Long, Double>>> =
        dao.pricesSince(LocalDate.now().toEpochDay() - 12).groupBy { it.isin }
            .mapValues { (_, v) -> serieFiable(v.sortedBy { it.epochDay }).map { it.epochDay to it.close } }

    /** Courbe 1H / 1J : fenêtre glissante qui se termine maintenant (le week-end aussi). */
    private suspend fun courbeIntraday(l: List<Ligne>, p: Periode): List<PointCourbe> = withContext(Dispatchers.IO) {
        val maintenant = System.currentTimeMillis() / 1000
        Portfolio.courbeGlissante(l, seriesIntraday(l, p), seriesQuotidiennes(), maintenant - p.secondes, maintenant)
    }

    /** Variations glissantes de chaque ligne sur 1 h, 24 h et 3 jours : même calcul que les courbes 1H / 1J. */
    private suspend fun glissantes(l: List<Ligne>): Map<Long, Triple<Double?, Double?, Double?>> {
        val maintenant = System.currentTimeMillis() / 1000
        val intraH = seriesIntraday(l, Periode.H1); val intraJ = seriesIntraday(l, Periode.J1); val quotidien = seriesQuotidiennes()
        fun v(li: Ligne, r: Double) = if (li.estCote && r > 0) 1 / r - 1 else null
        return l.associate { li ->
            li.p.id to Triple(
                v(li, Portfolio.rapportA(li, maintenant - 3_600, intraH, quotidien)),
                v(li, Portfolio.rapportA(li, maintenant - 86_400, intraJ, quotidien)),
                v(li, Portfolio.rapportA(li, maintenant - 3 * 86_400, intraJ, quotidien)))
        }
    }

    /** Variations jour / semaine / mois par ligne (id de position). */
    val variations: StateFlow<Map<Long, fr.eq.patrimo.domain.Variations>> = combine(lignes, historyVersion) { l, _ -> l }
        .mapLatest { l ->
            withContext(Dispatchers.IO) {
                val auj = LocalDate.now().toEpochDay()
                val prix = dao.pricesSince(auj - 1835).groupBy { it.isin }.mapValues { (_, v) -> v.sortedBy { it.epochDay } }
                val gl = runCatching { glissantes(l) }.getOrDefault(emptyMap())
                l.filter { it.estCote && it.p.dernierCours != null }.associate { li ->
                    val c = li.p.dernierCours!!
                    val serie = serieFiable(li.p.isin?.let { prix[it] }.orEmpty())
                    val debut = serie.firstOrNull()?.epochDay
                    // pas de chiffre si l'historique ne remonte pas assez loin (tolérance 5 jours)
                    fun depuis(n: Long): Double? = if (debut == null || debut > auj - n + 5) null
                        else serie.lastOrNull { it.epochDay <= auj - n }?.close?.takeIf { it > 0 }?.let { c / it - 1 }
                    li.p.id to fr.eq.patrimo.domain.Variations(gl[li.p.id]?.second ?: li.varJour, depuis(7), depuis(30), depuis(91), depuis(182), depuis(365), depuis(1826),
                        heure = gl[li.p.id]?.first, jours3 = gl[li.p.id]?.third)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Performances 1J…5A des titres « À surveiller » (id de position). */
    val perfsSurveilles: StateFlow<Map<Long, fr.eq.patrimo.domain.Perfs>> = combine(surveilles, historyVersion) { s, _ -> s }
        .mapLatest { liste ->
            withContext(Dispatchers.IO) {
                val auj = LocalDate.now().toEpochDay()
                val prix = dao.pricesSince(auj - 1840).groupBy { it.isin }.mapValues { (_, v) -> v.sortedBy { it.epochDay } }
                liste.filter { it.dernierCours != null }.associate { p ->
                    val c = p.dernierCours!!
                    val serie = serieFiable(p.isin?.let { prix[it] }.orEmpty())
                    val debut = serie.firstOrNull()?.epochDay
                    val vals = fr.eq.patrimo.domain.Perfs.JOURS.mapIndexed { i, n ->
                        if (i == 0) p.coursVeille?.takeIf { it > 0 }?.let { c / it - 1 }
                        // pas de chiffre si l'historique ne remonte pas assez loin (tolérance 5 jours)
                        else if (debut == null || debut > auj - n + 5) null
                        else serie.lastOrNull { it.epochDay <= auj - n }?.close?.takeIf { it > 0 }?.let { c / it - 1 }
                    }
                    p.id to fr.eq.patrimo.domain.Perfs(vals)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Ne garde que la partie de l'historique postérieure au dernier saut anormal (cours ×3 ou ÷3 d'un jour à l'autre) :
     * changement d'unité (pence/livres), de devise ou de cotation, qui fausserait les variations longues.
     */
    private fun serieFiable(serie: List<fr.eq.patrimo.data.PricePoint>): List<fr.eq.patrimo.data.PricePoint> {
        var debut = 0
        for (i in 1 until serie.size) {
            val a = serie[i - 1].close; val b = serie[i].close
            if (a > 0 && b > 0 && (b / a > 3.0 || a / b > 3.0)) debut = i
        }
        return if (debut == 0) serie else serie.subList(debut, serie.size)
    }

    private val _horizon = MutableStateFlow(fr.eq.patrimo.domain.Horizon.JOUR)
    val horizon = _horizon.asStateFlow()
    fun setHorizon(h: fr.eq.patrimo.domain.Horizon) { _horizon.value = h }

    /** Listes de fonds proposées par chaque contrat (onglet « Fonds »). */
    val catalogue = dao.observeCatalogue().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Avancement d'un import de liste de fonds : fraction 0..1, étape, secondes restantes estimées. */
    data class Progression(val fraction: Float, val etape: String, val resteSec: Long?)
    private val _progression = MutableStateFlow<Progression?>(null)
    val progression = _progression.asStateFlow()

    fun importerCatalogue(uri: Uri, etablissement: String, contrat: String) {
        viewModelScope.launch {
            _busy.value = true
            val debut = System.currentTimeMillis()
            fun avance(f: Float, etape: String) {
                val ecoule = (System.currentTimeMillis() - debut) / 1000.0
                val reste = if (f > 0.03f) ((ecoule / f) * (1 - f)).toLong() else null
                _progression.value = Progression(f.coerceIn(0f, 1f), etape, reste)
            }
            avance(0f, "Lecture du fichier…")
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val taille = runCatching {
                        cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                    }.getOrNull() ?: -1L
                    // 1) lecture (0 → 30 %), au fil des octets lus
                    val bytes = cr.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var lu = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); lu += n
                            if (taille > 0) avance(0.30f * lu / taille, "Lecture du fichier…")
                        }
                        out.toByteArray()
                    }
                    // 2) analyse (30 → 50 %)
                    avance(0.30f, "Recherche des ISIN…")
                    val fonds = fr.eq.patrimo.importer.CatalogueImporter.lire(bytes)
                    avance(0.50f, "${fonds.size} fonds trouvés, enregistrement…")
                    // 3) enregistrement par paquets (50 → 100 %)
                    val items = fonds.map { (i, l) -> fr.eq.patrimo.data.FondsCatalogue(contrat, i, l, etablissement) }
                    items.chunked(50).forEachIndexed { k, paquet ->
                        dao.insertCatalogue(paquet)
                        val fait = minOf(items.size, (k + 1) * 50)
                        avance(0.50f + 0.50f * fait / items.size, "Enregistrement : $fait / ${items.size} fonds")
                    }
                    avance(1f, "Terminé")
                    fonds.size
                }
            }
            _progression.value = null
            _busy.value = false
            _info.value = res.fold({ n -> if (n == 0) "Aucun ISIN trouvé dans ce fichier (Excel, CSV ou texte attendu)" else "$n fonds ajoutés à $contrat" },
                { "Lecture impossible : ${it.message}" })
        }
    }

    fun viderCatalogue(contrat: String) { viewModelScope.launch { dao.viderCatalogue(contrat); _info.value = "Liste de $contrat effacée" } }

    /** Ajoute un fonds du catalogue à « À surveiller », avec le contrat qui le propose. */
    fun surveillerFonds(isin: String?, libelle: String, contrat: fr.eq.patrimo.data.Contrat) {
        viewModelScope.launch {
            val existant = dao.positions().firstOrNull { it.aSurveiller && (if (isin != null) it.isin.equals(isin, true) else it.libelle.equals(libelle, true)) }
            if (existant != null) {
                val d = (existant.disponibilites?.split(',')?.map { it.trim() }.orEmpty() + contrat.contrat).filter { it.isNotEmpty() }.distinct()
                dao.update(existant.copy(disponibilites = d.joinToString(", ")))
            } else dao.insert(fr.eq.patrimo.data.Position(enveloppe = contrat.enveloppe, etablissement = contrat.etablissement, contrat = contrat.contrat,
                libelle = libelle, isin = isin, type = if (contrat.enveloppe.contains("assurance", true)) "OPCVM/UC" else "ETF",
                quantite = 0.0, pru = 0.0, valeurManuelle = null, tauxAnnuel = null, dateAchat = null, fraisAnnuels = null, notes = null,
                aSurveiller = true))
            _info.value = "« $libelle » ajouté à « À surveiller »"
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _info = MutableStateFlow<String?>(null)
    val info = _info.asStateFlow()
    private val _chat = MutableStateFlow(listOf(Message("assistant", "Bonjour ! Posez-moi une question sur vos placements.")))
    val chat = _chat.asStateFlow()

    init {
        // Enveloppes d'épargne retraite / salariale : contrats créés s'ils n'existent pas (frais modifiables dans les Réglages)
        versesConnus()
        if (base == "patrimo.db") viewModelScope.launch {
            val existants = dao.contrats().map { it.etablissement to it.contrat }.toSet()
            val voulus = listOf(
                fr.eq.patrimo.data.Contrat("Amundi", "PER Amundi", "PER", source = "créé dans l'appli", modifie = true),
                fr.eq.patrimo.data.Contrat("Amundi", "PEE Amundi", "PEE", source = "créé dans l'appli", modifie = true),
                fr.eq.patrimo.data.Contrat("CIC", "PEE CIC", "PEE", source = "créé dans l'appli", modifie = true))
            val manquants = voulus.filter { (it.etablissement to it.contrat) !in existants }
            if (manquants.isNotEmpty()) dao.insertContrats(manquants)
        }
        refresh(silencieux = true)
        // Rafraîchissement automatique tant que l'appli est ouverte (cours différés ~15 min selon la place)
        viewModelScope.launch { while (true) { delay(5 * 60_000L); refresh(silencieux = true) } }
    }

    fun setPeriode(p: Periode) { _periode.value = p }
    fun clearInfo() { _info.value = null }

    fun refresh(silencieux: Boolean = false) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val sansHistorique = dao.priceCount() == 0
            // Historique complet la 1re fois, puis les 3 derniers mois rechargés deux fois par jour (variations semaine / mois sans trou)
            val prefs = getApplication<Application>().getSharedPreferences("cours", android.content.Context.MODE_PRIVATE)
            // Une fois : rechargement complet sur 5 ans pour remplacer les anciens historiques mal mis à l'échelle
            val complet = sansHistorique || !prefs.getBoolean("histo5ans_v26_$base", false)
            val recharger = complet || System.currentTimeMillis() - prefs.getLong("histo2_$base", 0) > 12 * 3_600_000L
            val r = quotes.refreshAll(withHistory = recharger, range = if (complet) "5y" else "3mo")
            if (recharger && r.ok > 0) prefs.edit().putLong("histo2_$base", System.currentTimeMillis()).putBoolean("histo5ans_v26_$base", true).apply()
            historyVersion.update { it + 1 }
            _busy.value = false
            if (!silencieux || r.failed.isNotEmpty()) _info.value =
                if (r.failed.isEmpty()) "${r.ok} cours mis à jour"
                else "${r.ok} cours mis à jour · introuvables : ${r.failed.joinToString()}"
        }
    }

    /** Historique de valeur (onglet Historique) : importé + relevé quotidien. */
    val historique = dao.observeHistorique().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun importerHistorique(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _progression.value = Progression(0.1f, "Lecture de l'historique…", null)
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val pts = getApplication<Application>().contentResolver.openInputStream(uri)!!.use(fr.eq.patrimo.importer.HistoriqueImporter::lire)
                    _progression.value = Progression(0.5f, "Enregistrement de ${pts.size} valeurs…", null)
                    // Un point importé ne remplace pas le relevé automatique du même jour pour le même portefeuille
                    val auto = dao.observeHistorique().first().filter { it.source == "auto" }.map { it.epochDay to it.portefeuille }.toSet()
                    val gardes = pts.filter { (it.epochDay to it.portefeuille) !in auto }
                    gardes.chunked(500).forEachIndexed { i, c ->
                        dao.upsertHistorique(c)
                        _progression.value = Progression(0.5f + 0.5f * minOf(1f, (i + 1) * 500f / gardes.size.coerceAtLeast(1)), "Enregistrement…", null)
                    }
                    pts to gardes.size
                }
            }
            _progression.value = null; _busy.value = false
            _bilan.value = res.fold({ (pts, n) ->
                if (pts.isEmpty()) "Aucune valeur trouvée.\n\nLe fichier doit avoir une colonne « Date », puis soit les colonnes « Portefeuille » et « Valeur », soit une colonne par portefeuille."
                else {
                    val f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    val d1 = LocalDate.ofEpochDay(pts.minOf { it.epochDay }).format(f); val d2 = LocalDate.ofEpochDay(pts.maxOf { it.epochDay }).format(f)
                    "$n valeurs importées, du $d1 au $d2.\n\nPortefeuilles : " + pts.map { it.portefeuille }.distinct().joinToString(", ")
                }
            }, { "Import impossible : ${it.message}" })
        }
    }

    /**
     * Reconstitue l'historique (Tout, catégories, contrats) à partir des achats : pour chaque jour depuis la plus ancienne
     * date d'achat, nombre de parts × cours de clôture du jour, pour les lignes déjà achetées ce jour-là.
     * Les relevés automatiques existants ne sont pas remplacés.
     */
    fun reconstituerHistorique() {
        viewModelScope.launch {
            _busy.value = true
            _progression.value = Progression(0.05f, "Préparation…", null)
            val msg = withContext(Dispatchers.IO) {
                val pos = dao.positions().filter { !it.aSurveiller && it.quantite > 0 && it.isin != null }
                val avec = pos.filter { it.dateAchat != null }
                val sans = pos.filter { it.dateAchat == null }
                if (avec.isEmpty()) return@withContext "Aucune ligne n'a de date d'achat.\n\nRenseigne-la dans la fiche de chaque titre (champ « Date d'achat »), puis relance."
                val auj = LocalDate.now().toEpochDay()
                val debut = avec.minOf { it.dateAchat!! }
                val lignes = Portfolio.lignes(avec, dao.contrats())
                val prix = dao.pricesSince(debut - 10).groupBy { it.isin }.mapValues { (_, v) -> serieFiable(v.sortedBy { it.epochDay }) }
                val jours = lignes.flatMap { l -> prix[l.p.isin].orEmpty().map { it.epochDay } }.filter { it in debut until auj }.distinct().sorted()
                val auto = dao.observeHistorique().first().filter { it.source == "auto" }.map { it.epochDay to it.portefeuille }.toSet()
                val pts = ArrayList<fr.eq.patrimo.data.PointHistorique>()
                jours.forEachIndexed { n, d ->
                    if (n % 50 == 0) _progression.value = Progression(0.1f + 0.7f * n / jours.size, "Reconstitution : ${n + 1} / ${jours.size} jours", null)
                    val vals = lignes.filter { it.p.dateAchat!! <= d }.mapNotNull { l ->
                        prix[l.p.isin]?.lastOrNull { it.epochDay <= d }?.close?.let { c -> Triple(l, l.p.quantite * c, l.investi) }
                    }
                    if (vals.isEmpty()) return@forEachIndexed
                    fun ajoute(nom: String, v: List<Triple<fr.eq.patrimo.domain.Ligne, Double, Double>>) {
                        if ((d to nom) !in auto) pts += fr.eq.patrimo.data.PointHistorique(d, nom, v.sumOf { it.second }, v.sumOf { it.third }, "reconstitue")
                    }
                    ajoute("TOTAL", vals)
                    vals.groupBy { it.first.p.enveloppe }.forEach { (e, v) -> ajoute(e, v) }
                    vals.groupBy { it.first.p.contrat }.forEach { (c, v) -> ajoute(c, v) }
                }
                pts.chunked(500).forEach { dao.upsertHistorique(it) }
                val f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                "Historique reconstitué du ${LocalDate.ofEpochDay(debut).format(f)} à hier : ${jours.size} jours, ${avec.size} ligne(s)." +
                    (if (sans.isNotEmpty()) "\n\nSans date d'achat, donc non comptées : " + sans.joinToString(", ") { it.libelle } else "") +
                    "\n\nHypothèse : nombre de parts actuel depuis la date d'achat (pas d'achats ou ventes intermédiaires). Fonds euros et lignes sans cotation non reconstitués."
            }
            _progression.value = null; _busy.value = false
            _bilan.value = msg
        }
    }

    fun viderHistoriqueImporte() { viewModelScope.launch { dao.viderHistoriqueImporte(); _info.value = "Historique importé effacé (relevés automatiques conservés)" } }

    /** Montants versés communiqués par l'utilisateur (une seule fois ; modifiables ensuite dans les Réglages). */
    private fun versesConnus() = viewModelScope.launch {
        val prefs = getApplication<Application>().getSharedPreferences("cours", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("verse_v52_$base", false)) return@launch
        val cs = dao.contrats(); if (cs.isEmpty()) return@launch
        val connus: Map<String, Double> = when (base) {
            "patrimo.db" -> mapOf("CARDIF" to 207_700.0, "AFER1 Multisupport" to 39_764.0, "AFER2 Génération" to 32_966.0, "BoursoVie" to 61_000.0,
                "LINXEA" to 20_000.0, "PEA" to 55_000.0, "REVOLUT" to 25_400.0, "TRADE REPUBLIC" to 195.0,
                "PEE Amundi" to 9_382.17 - 2_184.25, "PER Amundi" to 6_688.37 - 437.02)
            "patrimo_natalia.db" -> mapOf("CARDIF" to 9_500.0)
            else -> emptyMap()
        }
        var maj = cs.filter { it.verse == null && it.contrat in connus }.map { it.copy(verse = connus.getValue(it.contrat), modifie = true) }
        if (base == "patrimo.db") {
            // Crypto : 23 800 € de référence, répartis entre plateformes au prorata de leur montant investi
            val lignesCrypto = Portfolio.lignes(dao.positions().filter { !it.aSurveiller && it.enveloppe.equals("Crypto", true) }, cs)
            val parC = lignesCrypto.groupBy { it.p.etablissement to it.p.contrat }.mapValues { (_, l) -> l.sumOf { it.investi } }
            val tot = parC.values.sum()
            if (tot > 0) maj = maj + cs.filter { it.verse == null && (it.etablissement to it.contrat) in parC }
                .map { it.copy(verse = 23_800.0 * parC.getValue(it.etablissement to it.contrat) / tot, modifie = true) }
        }
        maj.forEach { dao.updateContrat(it) }
        if (maj.isNotEmpty() || base != "patrimo.db") prefs.edit().putBoolean("verse_v52_$base", true).apply()
    }

    /** Bilan du dernier import, affiché dans une fenêtre jusqu'à ce qu'on la ferme. */
    private val _bilan = MutableStateFlow<String?>(null)
    val bilan = _bilan.asStateFlow()
    fun fermerBilan() { _bilan.value = null }

    fun importer(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val debut = System.currentTimeMillis()
            fun avance(f: Float, etape: String) {
                val ecoule = (System.currentTimeMillis() - debut) / 1000.0
                val reste = if (f > 0.05f) ((ecoule / f) * (1 - f)).toLong() else null
                _progression.value = Progression(f.coerceIn(0f, 1f), etape, reste)
            }
            avance(0f, "Lecture du fichier…")
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val octets = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    avance(0.2f, "Analyse des onglets…")
                    PortfolioImporter.lire(octets.inputStream())
                }
            }
            res.onSuccess { list ->
                if (list.positions.isEmpty()) {
                    _progression.value = null; _busy.value = false
                    _bilan.value = "Aucune ligne trouvée.\n\nLe fichier doit contenir l'onglet « portefeuille » (classeur de suivi) ou des onglets du modèle Ker Patrimo : Assurances vie, PEA, Comptes-titres, Crypto, PER, PEE."
                    return@launch
                }
                avance(0.35f, "Enregistrement de ${list.positions.size} lignes…")
                // Les frais réglés à la main sont conservés : barèmes modifiés et frais des fonds déjà saisis
                val anciensContrats = dao.contrats().filter { it.modifie }.associateBy { it.etablissement to it.contrat }
                val anciensFrais = dao.positions().filter { it.fraisAnnuels != null && it.isin != null }
                    .associate { (it.isin to it.contrat) to it.fraisAnnuels }
                val contrats = list.contrats.map { anciensContrats[it.etablissement to it.contrat] ?: it }
                val positions = list.positions.map { p ->
                    if (p.fraisAnnuels == null) p.copy(fraisAnnuels = anciensFrais[p.isin to p.contrat]) else p
                }
                if (list.complet) dao.replaceImport(positions, contrats)
                else {
                    // Fichier partiel (ex. un PEE ou un PER) : on ne remplace que les contrats présents dans le fichier
                    val touches = positions.map { it.etablissement to it.contrat }.toSet()
                    val gardees = dao.positions().filter { (it.etablissement to it.contrat) !in touches }
                    val tousContrats = (dao.contrats().filter { (it.etablissement to it.contrat) !in touches } + contrats)
                        .distinctBy { it.etablissement to it.contrat }
                    dao.replaceImport(gardees + positions, tousContrats)
                }
                avance(0.45f, "Recherche des cours…")
                val r = quotes.refreshAll(withHistory = true) { i, n -> avance(0.45f + 0.55f * i / n.coerceAtLeast(1), "Cours en ligne : ${i + 1} / $n") }
                historyVersion.update { v -> v + 1 }
                _progression.value = null; _busy.value = false
                val detail = positions.groupBy { "${it.enveloppe} · ${it.contrat}" }.entries.joinToString("\n") { (k, v) -> "• $k : ${v.size} ligne(s)" }
                _bilan.value = (if (list.complet) "Patrimoine remplacé par le fichier." else "Ajouté au patrimoine (les autres contrats n'ont pas été modifiés).") +
                    "\n\n$detail\n\nCours trouvés en ligne : ${r.ok}" + (if (r.failed.isNotEmpty()) " · sans cotation : ${r.failed.size} (valeur du fichier conservée)" else "")
                versesConnus()
                // Fichier partiel : on affiche directement la catégorie importée
                if (!list.complet) positions.map { it.enveloppe }.distinct().singleOrNull()?.let { setCategorie(it) }
            }.onFailure { _progression.value = null; _busy.value = false; _bilan.value = "Import impossible : ${it.message}" }
        }
    }

    // --- Réglages des frais ---
    val contrats: StateFlow<List<fr.eq.patrimo.data.Contrat>> = dao.observeContrats()
        .map { l -> l.sortedWith(compareBy({ it.enveloppe }, { it.etablissement }, { it.contrat })) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun enregistrerContrat(c: fr.eq.patrimo.data.Contrat) {
        viewModelScope.launch { dao.updateContrat(c.copy(modifie = true)); _info.value = "Frais de « ${c.contrat} » enregistrés" }
    }

    fun enregistrerFraisFonds(p: fr.eq.patrimo.data.Position, frais: Double?) {
        viewModelScope.launch { dao.update(p.copy(fraisAnnuels = frais)) }
    }

    fun poser(question: String) {
        if (question.isBlank()) return
        val hist = _chat.value + Message("user", question.trim())
        _chat.value = hist + Message("assistant", "…")
        viewModelScope.launch {
            val rep = assistant.repondre(hist, lignes.value, insights.value, withContext(Dispatchers.IO) { dao.positions() })
            _chat.value = hist + rep
        }
    }

    /** L'utilisateur valide les modifications proposées par l'assistant dans le message [index]. */
    fun valider(index: Int) {
        val m = _chat.value.getOrNull(index)?.takeIf { it.etat == fr.eq.patrimo.assistant.Etat.EN_ATTENTE } ?: return
        _chat.value = _chat.value.toMutableList().also { it[index] = m.copy(etat = fr.eq.patrimo.assistant.Etat.VALIDEE) }
        viewModelScope.launch {
            val avant = dao.positions().associateBy { it.id }
            val resultats = m.operations.map { fr.eq.patrimo.domain.Operations.appliquer(dao, it) }
            tracerParts(avant, "Opération (assistant)")
            _chat.value = _chat.value + Message("assistant", resultats.joinToString("\n") { "✓ $it" })
            historyVersion.update { it + 1 }
        }
    }

    /** Après une opération : lignes dont le nombre de parts a changé → partsAu = aujourd'hui et entrée au journal. */
    private suspend fun tracerParts(avant: Map<Long, fr.eq.patrimo.data.Position>, motif: String) {
        val auj = LocalDate.now().toEpochDay()
        val j = dao.positions().mapNotNull { p ->
            val a = avant[p.id]
            if (a != null && a.quantite == p.quantite) return@mapNotNull null
            dao.update(p.copy(partsAu = auj))
            fr.eq.patrimo.data.JournalParts(epochDay = auj, positionId = p.id, libelle = p.libelle, contrat = p.contrat,
                avant = a?.quantite ?: 0.0, apres = p.quantite, motif = motif)
        }
        if (j.isNotEmpty()) dao.insertJournal(j)
    }

    /** Journal des ajustements de parts (300 derniers). */
    val journalParts = dao.observeJournal().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun annuler(index: Int) {
        val m = _chat.value.getOrNull(index) ?: return
        _chat.value = _chat.value.toMutableList().also { it[index] = m.copy(etat = fr.eq.patrimo.assistant.Etat.ANNULEE) }
    }

    // --- Édition des lignes à l'écran ---
    fun enregistrerPosition(p: fr.eq.patrimo.data.Position) {
        viewModelScope.launch {
            // Un ISIN changé : on oublie la source de cotation mémorisée
            val ancien = dao.positions().firstOrNull { it.id == p.id }
            var q = if (ancien != null && ancien.isin != p.isin) p.copy(symbole = null, source = null, dernierCours = null) else p
            val auj = LocalDate.now().toEpochDay()
            if (ancien == null || ancien.quantite != q.quantite) q = q.copy(partsAu = auj)
            if (q.id == 0L) dao.insert(q) else dao.update(q)
            if (ancien != null && ancien.quantite != q.quantite) dao.insertJournal(listOf(fr.eq.patrimo.data.JournalParts(epochDay = auj,
                positionId = q.id, libelle = q.libelle, contrat = q.contrat, avant = ancien.quantite, apres = q.quantite, motif = "Saisie manuelle")))
            _info.value = "« ${p.libelle} » enregistré"
            if (q.isin != null && (ancien == null || ancien.isin != q.isin)) refresh(silencieux = true)
        }
    }

    fun supprimerPosition(p: fr.eq.patrimo.data.Position) {
        viewModelScope.launch { dao.delete(p); _info.value = "« ${p.libelle} » supprimé" }
    }

    fun appliquer(op: fr.eq.patrimo.domain.Operation) {
        viewModelScope.launch {
            val avant = dao.positions().associateBy { it.id }
            _info.value = fr.eq.patrimo.domain.Operations.appliquer(dao, op)
            tracerParts(avant, "Opération")
        }
    }
}
