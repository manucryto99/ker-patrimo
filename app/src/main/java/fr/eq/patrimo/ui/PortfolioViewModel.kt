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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortfolioViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
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
            withContext(Dispatchers.IO) {
                val depuis = LocalDate.now().toEpochDay() - p.jours
                Portfolio.courbe(l, dao.pricesSince(depuis), dao.snapshotsSince(depuis), depuis)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _info = MutableStateFlow<String?>(null)
    val info = _info.asStateFlow()
    private val _chat = MutableStateFlow(listOf(Message("assistant", "Bonjour ! Posez-moi une question sur vos placements.")))
    val chat = _chat.asStateFlow()

    init {
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
            val r = quotes.refreshAll(withHistory = sansHistorique)
            historyVersion.update { it + 1 }
            _busy.value = false
            if (!silencieux || r.failed.isNotEmpty()) _info.value =
                if (r.failed.isEmpty()) "${r.ok} cours mis à jour"
                else "${r.ok} cours mis à jour · introuvables : ${r.failed.joinToString()}"
        }
    }

    fun importer(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use(PortfolioImporter::lire)
                }
            }
            _busy.value = false
            res.onSuccess { list ->
                if (list.positions.isEmpty()) { _info.value = "Aucune ligne trouvée : le fichier doit contenir un onglet « portefeuille » (classeur de suivi) ou les onglets du modèle Ker Patrimo"; return@launch }
                // Les frais réglés à la main sont conservés : barèmes modifiés et frais des fonds déjà saisis
                val anciensContrats = dao.contrats().filter { it.modifie }.associateBy { it.etablissement to it.contrat }
                val anciensFrais = dao.positions().filter { it.fraisAnnuels != null && it.isin != null }
                    .associate { (it.isin to it.contrat) to it.fraisAnnuels }
                val contrats = list.contrats.map { anciensContrats[it.etablissement to it.contrat] ?: it }
                val positions = list.positions.map { p ->
                    if (p.fraisAnnuels == null) p.copy(fraisAnnuels = anciensFrais[p.isin to p.contrat]) else p
                }
                dao.replaceImport(positions, contrats)
                _info.value = "${list.positions.size} placements importés, recherche des cours…"
                quotes.refreshAll(withHistory = true).also { historyVersion.update { v -> v + 1 } }
                _info.value = "Import terminé"
            }.onFailure { _info.value = "Import impossible : ${it.message}" }
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
            val resultats = m.operations.map { fr.eq.patrimo.domain.Operations.appliquer(dao, it) }
            _chat.value = _chat.value + Message("assistant", resultats.joinToString("\n") { "✓ $it" })
            historyVersion.update { it + 1 }
        }
    }

    fun annuler(index: Int) {
        val m = _chat.value.getOrNull(index) ?: return
        _chat.value = _chat.value.toMutableList().also { it[index] = m.copy(etat = fr.eq.patrimo.assistant.Etat.ANNULEE) }
    }

    // --- Édition des lignes à l'écran ---
    fun enregistrerPosition(p: fr.eq.patrimo.data.Position) {
        viewModelScope.launch {
            // Un ISIN changé : on oublie la source de cotation mémorisée
            val ancien = dao.positions().firstOrNull { it.id == p.id }
            val q = if (ancien != null && ancien.isin != p.isin) p.copy(symbole = null, source = null, dernierCours = null) else p
            if (q.id == 0L) dao.insert(q) else dao.update(q)
            _info.value = "« ${p.libelle} » enregistré"
            if (q.isin != null && (ancien == null || ancien.isin != q.isin)) refresh(silencieux = true)
        }
    }

    fun supprimerPosition(p: fr.eq.patrimo.data.Position) {
        viewModelScope.launch { dao.delete(p); _info.value = "« ${p.libelle} » supprimé" }
    }

    fun appliquer(op: fr.eq.patrimo.domain.Operation) {
        viewModelScope.launch { _info.value = fr.eq.patrimo.domain.Operations.appliquer(dao, op) }
    }
}
