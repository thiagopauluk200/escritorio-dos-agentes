package br.escritorio.central

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LeitorQrTest {
    @Test
    fun leCodigoComMargemEIluminacaoVariavel() {
        val codigo = "RN1." + "Ab29Xz-7_".repeat(20)
        val tamanhoQr = 320
        val qr = QRCodeWriter().encode(codigo, BarcodeFormat.QR_CODE, tamanhoQr, tamanhoQr,
            mapOf(EncodeHintType.MARGIN to 5))
        val largura = 960
        val altura = 720
        val esquerda = (largura - tamanhoQr) / 2
        val topo = (altura - tamanhoQr) / 2
        val pixels = IntArray(largura * altura) { i ->
            val x = i % largura
            val y = i / largura
            val dentro = x in esquerda until esquerda + tamanhoQr && y in topo until topo + tamanhoQr
            val luz = (50 * x) / largura
            val luminancia = when {
                !dentro -> 55 + luz
                qr[x - esquerda, y - topo] -> 23 + luz
                else -> 180 + luz
            }
            (luminancia shl 16) or (luminancia shl 8) or luminancia
        }

        assertEquals(codigo, LeitorQr.ler(RGBLuminanceSource(largura, altura, pixels)))
    }

    @Test
    fun rejeitaImagemSemQrComMensagemParaTentarDeNovo() {
        assertThrows(IllegalArgumentException::class.java) {
            LeitorQr.ler(RGBLuminanceSource(320, 240, IntArray(320 * 240) { 0xffffff }))
        }
    }
}
