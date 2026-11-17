package br.escritorio.central

import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File

internal object LeitorQr {
    private const val LIMITE_FOTO = 2400
    private const val ERRO = "QR ilegível. Fotografe o quadrado inteiro, ocupe boa parte da tela e confirme a foto. Você também pode colar o código."

    fun lerFoto(arquivo: File): String {
        val dimensoes = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arquivo.path, dimensoes)
        if (dimensoes.outWidth <= 0 || dimensoes.outHeight <= 0) throw IllegalArgumentException(ERRO)
        val escala = BitmapFactory.Options().apply {
            var maior = maxOf(dimensoes.outWidth, dimensoes.outHeight)
            while (maior > LIMITE_FOTO) {
                inSampleSize = maxOf(1, inSampleSize) * 2
                maior /= 2
            }
        }
        val imagem = BitmapFactory.decodeFile(arquivo.path, escala) ?: throw IllegalArgumentException(ERRO)
        val fonte = try {
            val largura = imagem.width
            val altura = imagem.height
            val pixels = IntArray(largura * altura)
            imagem.getPixels(pixels, 0, largura, 0, 0, largura, altura)
            RGBLuminanceSource(largura, altura, pixels)
        } finally {
            imagem.recycle()
        }
        return ler(fonte)
    }

    fun ler(fonte: LuminanceSource): String {
        val dicas = mapOf(DecodeHintType.TRY_HARDER to true)
        val leitor = QRCodeReader()
        val imagem = BinaryBitmap(HybridBinarizer(fonte))
        val tentativas = buildList {
            add(imagem)
            if (imagem.isCropSupported && fonte.width >= 200 && fonte.height >= 200) {
                val margemX = fonte.width / 8
                val margemY = fonte.height / 8
                add(imagem.crop(margemX, margemY, fonte.width - 2 * margemX, fonte.height - 2 * margemY))
            }
            add(BinaryBitmap(GlobalHistogramBinarizer(fonte)))
        }
        for (tentativa in tentativas) {
            try {
                return leitor.decode(tentativa, dicas).text
            } catch (_: ReaderException) {
                leitor.reset()
            }
        }
        throw IllegalArgumentException(ERRO)
    }
}
