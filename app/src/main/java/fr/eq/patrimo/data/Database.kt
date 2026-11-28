package fr.eq.patrimo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Une ligne de placement, telle qu'importée depuis l'onglet « Positions » du modèle Excel. */
@Entity(tableName = "positions")
data class Position(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enveloppe: String,          // PEA, Assurance vie, CTO, PER…
    val etablissement: String,
    val contrat: String,
    val libelle: String,
    val isin: String?,              // null pour fonds euros / livrets
    val type: String,               // Action, ETF, OPCVM/UC, Fonds euros…
    val quantite: Double,
    val pru: Double,
    val valeurManuelle: Double?,    // supports non cotés
    val tauxAnnuel: Double?,        // fraction : 0.025 = 2,5 %
    val dateAchat: Long?,           // epochDay
    val fraisAnnuels: Double?,      // fraction
    val notes: String?,
    // Renseigné automatiquement par le service de cotation
    val symbole: String? = null,    // ex. CW8.PA, ou URL Boursorama
    val source: String? = null,     // "Yahoo", "Boursorama"
    val dernierCours: Double? = null,      // en EUR
    val coursVeille: Double? = null,       // en EUR
    val plusHaut52s: Double? = null,       // en EUR
    val majCours: Long? = null,            // epoch millis
    val aSurveiller: Boolean = false,      // ligne soldée ou idée : suivie dans l'onglet « À surveiller », hors patrimoine
    val disponibilites: String? = null     // contrats où le support est proposé, séparés par des virgules (saisie libre)
)

/**
 * Un contrat / compte (onglet « Contrats » du modèle Excel) et son barème de frais.
 * Clé : établissement + contrat, comme dans l'onglet « Positions ».
 */
/** Fonds proposé par un contrat (liste importée depuis la liste des supports de l'assureur / courtier). */
@Entity(tableName = "catalogue", primaryKeys = ["contrat", "isin"])
data class FondsCatalogue(
    val contrat: String,
    val isin: String,
    val libelle: String,
    val etablissement: String
)

@Entity(tableName = "contrats", primaryKeys = ["etablissement", "contrat"])
data class Contrat(
    val etablissement: String,
    val contrat: String,
    val enveloppe: String,
    val fraisGestionUc: Double = 0.0,     // AV/PER : prélevés en nombre de parts (fraction/an)
    val fraisGestionEuro: Double = 0.0,   // AV : déjà déduits du taux net servi, informatif
    val droitsDeGarde: String = "",       // PEA/CTO : barème, ex. "0,40% jusqu'à 50000 ; 0,20% jusqu'à 150000 ; 0,10%"
    val droitsFixesParLigne: Double = 0.0,// €/an par ligne
    val plafondGarde: Double? = null,     // ex. 0.004 pour un PEA (plafond légal 0,40 %)
    val dateReleve: Long? = null,         // epochDay où les nombres de parts ont été relevés
    val source: String = "",              // d'où vient le barème
    val modifie: Boolean = false,         // modifié dans les Réglages : conservé lors d'un nouvel import
    val verse: Double? = null             // montant versé sur le contrat : base de la plus-value (sinon somme des montants investis par ligne)
)

/** Cours de clôture historique d'un ISIN, en EUR. */
@Entity(tableName = "prices", primaryKeys = ["isin", "epochDay"])
data class PricePoint(val isin: String, val epochDay: Long, val close: Double)

/**
 * Historique de valeur par portefeuille (« TOTAL », une catégorie ou un contrat), un point par jour.
 * source = "import" (fichier de l'utilisateur) ou "auto" (relevé quotidien de l'appli).
 */
@Entity(tableName = "historique", primaryKeys = ["epochDay", "portefeuille"])
data class PointHistorique(val epochDay: Long, val portefeuille: String, val valeur: Double, val investi: Double? = null, val source: String = "auto")

/** Valeur totale enregistrée chaque jour (sert aussi pour les supports sans historique). */
@Entity(tableName = "snapshots")
data class Snapshot(@PrimaryKey val epochDay: Long, val total: Double, val investi: Double)

@Dao
abstract class PortfolioDao {
    @Query("SELECT * FROM positions ORDER BY enveloppe, libelle")
    abstract fun observePositions(): Flow<List<Position>>

    @Query("SELECT * FROM positions")
    abstract suspend fun positions(): List<Position>

    @Insert
    abstract suspend fun insertAll(items: List<Position>)

    @Insert
    abstract suspend fun insert(item: Position): Long

    @androidx.room.Delete
    abstract suspend fun delete(item: Position)

    @Update
    abstract suspend fun update(item: Position)

    @Query("DELETE FROM positions")
    abstract suspend fun clearPositions()

    @Transaction
    open suspend fun replaceAll(items: List<Position>) {
        clearPositions()
        insertAll(items)
    }

    @Query("SELECT * FROM contrats")
    abstract fun observeContrats(): Flow<List<Contrat>>

    @Query("SELECT * FROM contrats")
    abstract suspend fun contrats(): List<Contrat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertContrats(items: List<Contrat>)

    @Update
    abstract suspend fun updateContrat(item: Contrat)

    @Query("DELETE FROM contrats")
    abstract suspend fun clearContrats()

    @Transaction
    open suspend fun replaceImport(positions: List<Position>, contrats: List<Contrat>) {
        clearPositions(); insertAll(positions)
        clearContrats(); insertContrats(contrats)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPrices(items: List<PricePoint>)

    @Query("SELECT * FROM prices WHERE epochDay >= :from ORDER BY epochDay")
    abstract suspend fun pricesSince(from: Long): List<PricePoint>

    @Query("SELECT MIN(epochDay) FROM prices WHERE isin = :isin")
    abstract suspend fun oldestPrice(isin: String): Long?

    @Query("SELECT * FROM catalogue ORDER BY libelle")
    abstract fun observeCatalogue(): kotlinx.coroutines.flow.Flow<List<FondsCatalogue>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun insertCatalogue(items: List<FondsCatalogue>)

    @Query("DELETE FROM catalogue WHERE contrat = :contrat")
    abstract suspend fun viderCatalogue(contrat: String)

    @Query("SELECT * FROM historique ORDER BY epochDay")
    abstract fun observeHistorique(): Flow<List<PointHistorique>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun upsertHistorique(items: List<PointHistorique>)

    @Query("DELETE FROM historique WHERE source = 'import'")
    abstract suspend fun viderHistoriqueImporte()

    @Query("SELECT COUNT(*) FROM prices")
    abstract suspend fun priceCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSnapshot(item: Snapshot)

    @Query("SELECT * FROM snapshots WHERE epochDay >= :from ORDER BY epochDay")
    abstract suspend fun snapshotsSince(from: Long): List<Snapshot>
}

@Database(entities = [Position::class, Contrat::class, PricePoint::class, Snapshot::class, FondsCatalogue::class, PointHistorique::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PortfolioDao

    companion object {
        /** Une base par patrimoine : patrimo.db (Emmanuel, base d'origine), patrimo_natalia.db, patrimo_estelle.db. */
        private val instances = java.util.concurrent.ConcurrentHashMap<String, AppDatabase>()
        fun get(context: Context, nom: String = "patrimo.db"): AppDatabase = instances[nom] ?: synchronized(this) {
            instances[nom] ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, nom)
                // v4 -> v5 : ajout d'une colonne, sans perdre les lignes saisies
                .addMigrations(object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE positions ADD COLUMN disponibilites TEXT")
                    }
                })
                .addMigrations(object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `catalogue` (`contrat` TEXT NOT NULL, `isin` TEXT NOT NULL, `libelle` TEXT NOT NULL, `etablissement` TEXT NOT NULL, PRIMARY KEY(`contrat`, `isin`))")
                    }
                })
                .addMigrations(object : androidx.room.migration.Migration(7, 8) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE contrats ADD COLUMN verse REAL")
                    }
                })
                .addMigrations(object : androidx.room.migration.Migration(6, 7) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `historique` (`epochDay` INTEGER NOT NULL, `portefeuille` TEXT NOT NULL, `valeur` REAL NOT NULL, `investi` REAL, `source` TEXT NOT NULL, PRIMARY KEY(`epochDay`, `portefeuille`))")
                    }
                })
                .fallbackToDestructiveMigration() // phase de développement : on réimporte l'Excel
                .build().also { instances[nom] = it }
        }
    }
}
