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
    val modifie: Boolean = false          // modifié dans les Réglages : conservé lors d'un nouvel import
)

/** Cours de clôture historique d'un ISIN, en EUR. */
@Entity(tableName = "prices", primaryKeys = ["isin", "epochDay"])
data class PricePoint(val isin: String, val epochDay: Long, val close: Double)

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

    @Query("SELECT COUNT(*) FROM prices")
    abstract suspend fun priceCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSnapshot(item: Snapshot)

    @Query("SELECT * FROM snapshots WHERE epochDay >= :from ORDER BY epochDay")
    abstract suspend fun snapshotsSince(from: Long): List<Snapshot>
}

@Database(entities = [Position::class, Contrat::class, PricePoint::class, Snapshot::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PortfolioDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "patrimo.db")
                // v4 -> v5 : ajout d'une colonne, sans perdre les lignes saisies
                .addMigrations(object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE positions ADD COLUMN disponibilites TEXT")
                    }
                })
                .fallbackToDestructiveMigration() // phase de développement : on réimporte l'Excel
                .build().also { instance = it }
        }
    }
}
