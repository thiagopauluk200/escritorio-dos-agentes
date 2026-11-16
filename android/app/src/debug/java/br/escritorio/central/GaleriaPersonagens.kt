package br.escritorio.central

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.escritorio.central.ui.comp.Energia
import br.escritorio.central.ui.comp.Humor
import br.escritorio.central.ui.comp.Original
import br.escritorio.central.ui.comp.Personagem

/** Galeria de conferência visual (somente debug): cada linha é um estado, cada coluna um personagem. */
class GaleriaPersonagens : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val casos = listOf(
            "Trabalhando · com tokens" to (Humor.TRABALHANDO to Energia.CHEIA),
            "Aguardando ordens · com tokens" to (Humor.AGUARDANDO to Energia.CHEIA),
            "Na fila · tokens acabando" to (Humor.FILA to Energia.BAIXA),
            "Descansando · sem limite definido" to (Humor.DESCANSANDO to Energia.SEM_ORCAMENTO),
            "Sem tokens" to (Humor.AGUARDANDO to Energia.VAZIA),
            "Sem dados" to (Humor.SEM_DADOS to Energia.DESCONHECIDA),
        )
        setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF0E1320)).verticalScroll(rememberScrollState()).padding(top = 36.dp, start = 8.dp, end = 8.dp)) {
                casos.forEach { (titulo, par) ->
                    Text(titulo, color = Color(0xFF93A0BD), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Original.entries.forEach { q -> Personagem(q, par.first, par.second, Modifier.weight(1f).aspectRatio(1.05f)) }
                    }
                }
            }
        }
    }
}
