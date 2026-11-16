package br.escritorio.central.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ======================================================= cofre (Android Keystore)
/**
 * Cifra o token do dispositivo com AES-256-GCM usando uma chave não exportável do Android Keystore.
 * O texto cifrado fica no DataStore; sem a chave deste aparelho ele é inútil (e não vai para backup).
 */
class Cofre {
    private val alias = "central_token_v1"

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun cifrar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave())
        val dados = c.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(dados, Base64.NO_WRAP)
    }

    fun decifrar(s: String): String? = runCatching {
        val (iv, dados) = s.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(dados, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun apagarChave() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

// =========================================================== preferências
private val Context.ds by preferencesDataStore(name = "preferencias")

data class Config(
    val principal: String = "",
    val alternativo: String = "",
    val tokenCifrado: String = "",
    val dispositivoId: String = "",
    val ultimaRota: String = "inicio",
    val notificacoes: Boolean = false,
    val reenvioAutomatico: Boolean = false,
    val tema: String = "sistema",
    val alertasEnviados: String = "",
)

class Preferencias(private val ctx: Context) {
    private object K {
        val principal = stringPreferencesKey("principal")
        val alternativo = stringPreferencesKey("alternativo")
        val token = stringPreferencesKey("token_cifrado")
        val disp = stringPreferencesKey("dispositivo_id")
        val rota = stringPreferencesKey("ultima_rota")
        val notif = booleanPreferencesKey("notificacoes")
        val reenvio = booleanPreferencesKey("reenvio_automatico")
        val tema = stringPreferencesKey("tema")
        val alertas = stringPreferencesKey("alertas_enviados")
    }

    val fluxo: Flow<Config> = ctx.ds.data.map { p: Preferences ->
        Config(
            principal = p[K.principal] ?: "", alternativo = p[K.alternativo] ?: "", tokenCifrado = p[K.token] ?: "",
            dispositivoId = p[K.disp] ?: "", ultimaRota = p[K.rota] ?: "inicio", notificacoes = p[K.notif] ?: false,
            reenvioAutomatico = p[K.reenvio] ?: false, tema = p[K.tema] ?: "sistema", alertasEnviados = p[K.alertas] ?: "",
        )
    }

    suspend fun atual(): Config = fluxo.first()
    suspend fun servidores(principal: String, alternativo: String) = ctx.ds.edit { it[K.principal] = principal; it[K.alternativo] = alternativo }
    suspend fun alternativo(url: String) = ctx.ds.edit { it[K.alternativo] = url }
    suspend fun sessao(tokenCifrado: String, dispositivo: String) = ctx.ds.edit { it[K.token] = tokenCifrado; it[K.disp] = dispositivo }
    suspend fun limparSessao() = ctx.ds.edit { it.remove(K.token); it.remove(K.disp) }
    suspend fun rota(r: String) = ctx.ds.edit { it[K.rota] = r }
    suspend fun notificacoes(v: Boolean) = ctx.ds.edit { it[K.notif] = v }
    suspend fun reenvio(v: Boolean) = ctx.ds.edit { it[K.reenvio] = v }
    suspend fun tema(v: String) = ctx.ds.edit { it[K.tema] = v }
    suspend fun alertasEnviados(v: String) = ctx.ds.edit { it[K.alertas] = v.takeLast(4000) }
}

// ===================================================================== Room
/** Último estado recebido de cada consulta (exibido offline com a idade). */
@Entity(tableName = "cache")
data class CacheEntrada(@PrimaryKey val chave: String, val json: String, val atualizadoEm: Long)

/**
 * Envio de tarefa com identificador estável. É gravado ANTES da requisição; se a resposta se perder,
 * o reenvio usa o MESMO idem e a Central devolve a tarefa já criada (sem duplicar).
 */
@Entity(tableName = "envios")
data class EnvioPendente(
    @PrimaryKey val idem: String,
    val agente: String,
    val texto: String,
    val registrarNota: Boolean,
    val criadoEm: Long,
    val estado: String,          // enviando | nao_confirmado | confirmado | recusado
    val tarefaId: String? = null,
    val erro: String? = null,
    val tentativas: Int = 0,
)

@Entity(tableName = "rascunhos")
data class Rascunho(@PrimaryKey val chave: String, val texto: String, val extra: String = "", val atualizadoEm: Long)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE chave = :k") suspend fun obter(k: String): CacheEntrada?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun salvar(e: CacheEntrada)
    @Query("DELETE FROM cache") suspend fun limpar()
}

@Dao
interface EnvioDao {
    @Query("SELECT * FROM envios WHERE estado != 'confirmado' ORDER BY criadoEm DESC") fun pendentes(): Flow<List<EnvioPendente>>
    @Query("SELECT * FROM envios WHERE estado = 'nao_confirmado'") suspend fun naoConfirmados(): List<EnvioPendente>
    @Query("SELECT * FROM envios WHERE idem = :idem") suspend fun obter(idem: String): EnvioPendente?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun salvar(e: EnvioPendente)
    @Query("DELETE FROM envios WHERE idem = :idem") suspend fun apagar(idem: String)
    @Query("DELETE FROM envios WHERE estado = 'confirmado' AND criadoEm < :antes") suspend fun limparAntigos(antes: Long)
    @Query("DELETE FROM envios") suspend fun limpar()
}

@Dao
interface RascunhoDao {
    @Query("SELECT * FROM rascunhos WHERE chave = :k") suspend fun obter(k: String): Rascunho?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun salvar(r: Rascunho)
    @Query("DELETE FROM rascunhos WHERE chave = :k") suspend fun apagar(k: String)
}

@Database(entities = [CacheEntrada::class, EnvioPendente::class, Rascunho::class], version = 1, exportSchema = true)
abstract class BancoLocal : RoomDatabase() {
    abstract fun cache(): CacheDao
    abstract fun envios(): EnvioDao
    abstract fun rascunhos(): RascunhoDao

    companion object {
        fun criar(ctx: Context) = Room.databaseBuilder(ctx, BancoLocal::class.java, "central.db").build()
    }
}
