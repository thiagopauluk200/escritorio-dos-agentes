package br.escritorio.central.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class CoresEstado(val ok: Color, val aviso: Color, val erro: Color, val neutro: Color, val info: Color, val serie: List<Color>)

private val claro = CoresEstado(
    ok = Color(0xFF1E8E5A), aviso = Color(0xFFB26A00), erro = Color(0xFFC62828), neutro = Color(0xFF6B7280), info = Color(0xFF3F51D9),
    serie = listOf(Color(0xFF3F51D9), Color(0xFFD9480F), Color(0xFF1E8E5A), Color(0xFF8E24AA), Color(0xFF00838F),
        Color(0xFFB26A00), Color(0xFFAD1457), Color(0xFF5D4037), Color(0xFF546E7A)),
)
private val escuro = CoresEstado(
    ok = Color(0xFF3DDC97), aviso = Color(0xFFFFB547), erro = Color(0xFFFF6B6B), neutro = Color(0xFF93A0BD), info = Color(0xFF8A9BFF),
    serie = listOf(Color(0xFF8A9BFF), Color(0xFFFF8A5B), Color(0xFF3DDC97), Color(0xFFCE93D8), Color(0xFF4DD0E1),
        Color(0xFFFFB547), Color(0xFFF48FB1), Color(0xFFBCAAA4), Color(0xFFB0BEC5)),
)

val LocalCores = staticCompositionLocalOf { claro }

@Composable
fun TemaCentral(preferencia: String, conteudo: @Composable () -> Unit) {
    val escuroAtivo = when (preferencia) { "claro" -> false; "escuro" -> true; else -> isSystemInDarkTheme() }
    val esquema = if (escuroAtivo) darkColorScheme(
        primary = Color(0xFF8A9BFF), secondary = Color(0xFF3DDC97), background = Color(0xFF0E1320),
        surface = Color(0xFF101627), surfaceVariant = Color(0xFF1A2238), onSurfaceVariant = Color(0xFFB7C0D8),
    ) else lightColorScheme(
        primary = Color(0xFF3F51D9), secondary = Color(0xFF1E8E5A), background = Color(0xFFF7F8FC),
        surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9ECF5), onSurfaceVariant = Color(0xFF4A5170),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalCores provides if (escuroAtivo) escuro else claro) {
        MaterialTheme(colorScheme = esquema, content = conteudo)
    }
}
