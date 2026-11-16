package br.escritorio.central

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.escritorio.central.data.Api
import br.escritorio.central.data.BancoLocal
import br.escritorio.central.data.Cofre
import br.escritorio.central.data.Preferencias
import br.escritorio.central.data.Repositorio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Composição manual das dependências (sem framework de DI: o app é pequeno). */
class Container(ctx: Context) {
    val prefs = Preferencias(ctx)
    val cofre = Cofre()
    val db = BancoLocal.criar(ctx)
    lateinit var repo: Repositorio
    val api = Api { repo.sessao() }

    init { repo = Repositorio(api, db, prefs, cofre) }
}

class AppCentral : Application(), Configuration.Provider {
    lateinit var c: Container
        private set
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        c = Container(this)
        escopo.launch { c.repo.iniciar() }  // lê token (Keystore) e cache (Room) fora da thread principal
        criarCanais()
    }

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    private fun criarCanais() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_TAREFAS, getString(R.string.canal_tarefas), NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_ALERTAS, getString(R.string.canal_alertas), NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
    }

    companion object {
        const val CANAL_TAREFAS = "tarefas"
        const val CANAL_ALERTAS = "alertas"
        const val TRABALHO = "verificar_central"

        /**
         * Notificações optativas: verificação periódica pelo WorkManager (mínimo do Android: 15 min, sujeito ao Doze).
         * NÃO é entrega instantânea; não há canal push. Desligado por padrão.
         */
        fun agendar(ctx: Context, ligado: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!ligado) { wm.cancelUniqueWork(TRABALHO); return }
            val req = PeriodicWorkRequestBuilder<VerificadorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(TRABALHO, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notificar(ctx: Context, canal: String, id: Int, titulo: String, texto: String, publico: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            // na tela bloqueada aparece só a versão pública, sem detalhes sensíveis
            val pub = NotificationCompat.Builder(ctx, canal).setSmallIcon(R.drawable.ic_frente)
                .setContentTitle(ctx.getString(R.string.app_name)).setContentText(publico).build()
            val n = NotificationCompat.Builder(ctx, canal).setSmallIcon(R.drawable.ic_frente)
                .setContentTitle(titulo).setContentText(texto).setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(pub)
                .setContentIntent(pi).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }
    }
}

/** Verifica tarefas finalizadas e alertas de orçamento desde a última execução; evita notificações repetidas. */
class VerificadorWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AppCentral
        val repo = app.c.repo
        val prefs = app.c.prefs
        if (!prefs.atual().notificacoes) return Result.success()
        if (repo.sessaoValida.value == null) repo.iniciar()
        if (repo.sessaoValida.value != true) return Result.success()
        val est = runCatching { repo.atualizarEstado() }.getOrElse { return Result.retry() }
        val enviados = prefs.atual().alertasEnviados.split(",").filter { it.isNotBlank() }.toMutableSet()
        val novos = mutableListOf<String>()
        val nomes = est.agentes.associate { it.id to it.nome }
        for (t in est.tarefas.filter { it.status in setOf("concluida", "falhou") && (it.tempos?.total_tarefa_ms != null || it.fim != null) }.take(20)) {
            val chave = "t:${t.id}"
            if (chave in enviados) continue
            novos += chave
            // tarefas antigas da primeira execução não geram enxurrada de notificações
            if (enviados.isEmpty()) continue
            AppCentral.notificar(applicationContext, AppCentral.CANAL_TAREFAS, t.id.hashCode(),
                "Tarefa ${if (t.status == "concluida") "concluída" else "com falha"}: ${nomes[t.agente] ?: "agente"}",
                t.texto.take(140), "Uma tarefa terminou")
        }
        val tk = runCatching { repo.tokens(null, null, null, null).valor }.getOrNull()
        tk?.agentes?.forEach { a ->
            val lim = a.orcamento_local.alerta_atingido ?: return@forEach
            val chave = "o:${a.agente}:${a.orcamento_local.inicio_periodo}:$lim"
            if (chave in enviados) return@forEach
            novos += chave
            AppCentral.notificar(applicationContext, AppCentral.CANAL_ALERTAS, chave.hashCode(),
                "Orçamento local de ${a.nome}: $lim% atingido",
                "Consumo ${a.orcamento_local.consumo ?: 0} de ${a.orcamento_local.limite_tokens} tokens no período (${a.orcamento_local.periodo}).",
                "Alerta de orçamento de tokens")
        }
        if (novos.isNotEmpty()) prefs.alertasEnviados((enviados + novos).joinToString(","))
        if (enviados.isEmpty() && novos.isEmpty()) prefs.alertasEnviados("inicio")
        return Result.success()
    }
}
