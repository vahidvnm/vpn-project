package com.vpnproject.app.core

import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.EnumMap

/** Decodes a QR code from a user-selected image without loading unbounded image data. */
object QrCodeImageDecoder {
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_DECODED_PIXELS = 4_000_000

    fun decode(input: InputStream): String {
        val bytes = readBounded(input)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a readable image." }

        var sampleSize = 1
        while (bounds.outWidth.toLong() / sampleSize * (bounds.outHeight.toLong() / sampleSize) > MAX_DECODED_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("The selected image could not be decoded.")
        try {
            val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
            require(pixelCount in 1..MAX_DECODED_PIXELS.toLong()) { "The selected image is too large to scan safely." }
            val pixels = IntArray(pixelCount.toInt())
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.CHARACTER_SET, "UTF-8")
            }
            return QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                hints
            ).text
        } finally {
            bitmap.recycle()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= MAX_IMAGE_BYTES) { "The selected image is too large to scan safely." }
            output.write(buffer, 0, read)
        }
        require(total > 0) { "The selected image is empty." }
        return output.toByteArray()
    }
}
