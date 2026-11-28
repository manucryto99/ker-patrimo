package fr.eq.patrimo.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.eq.patrimo.data.AppDatabase
import fr.eq.patrimo.domain.InsightEngine
import fr.eq.patrimo.domain.Portfolio
import fr.eq.patrimo.domain.Niveau
import fr.eq.patrimo.quotes.QuoteService
import java.util.concurrent.TimeUnit

/** Rafraîchit les cours en arrière-plan et notifie les nouveaux constats importants. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Tous les patrimoines existants (Emmanuel, Natalia, Estelle)
        val insights = listOf("patrimo.db", "patrimo_natalia.db", "patrimo_estelle.db")
            .filter { it == "patrimo.db" || applicationContext.getDatabasePath(it).exists() }
            .flatMap { nom ->
                val dao = AppDatabase.get(applicationContext, nom).dao()
                QuoteService(dao).refreshAll(withHistory = false)
                InsightEngine.analyse(Portfolio.lignes(dao.positions().filter { !it.aSurveiller }, dao.contrats()))
                    .filter { it.niveau == Niveau.ALERTE || it.niveau == Niveau.POSITIF }
                    .map { i -> if (nom == "patrimo.db") i else i.copy(cle = nom + ":" + i.cle) }
            }
        val prefs = applicationContext.getSharedPreferences("alertes", Context.MODE_PRIVATE)
        val deja = prefs.getStringSet("notifiees", emptySet())!!
        val nouvelles = insights.filter { it.cle !in deja }
        nouvelles.forEachIndexed { i, it -> notifier(i, it.titre, it.texte) }
        prefs.edit().putStringSet("notifiees", deja + nouvelles.map { it.cle }).apply()
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifier(id: Int, titre: String, texte: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Alertes placements", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titre).setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(1000 + id, n)
    }

    companion object {
        private const val CANAL = "alertes"
        fun planifier(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
