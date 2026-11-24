package com.paveltyapkin.phrasoteca

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.paveltyapkin.phrasoteca.data.local.CardEntity
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Offline, versioned, public single-card QR transfer. Not for credentials or checklists. */
object CardQrCodec {
    private const val PREFIX = "PHRASOTECA:1:"
    private const val MAX_QR_TEXT = 1500
    private const val MAX_JSON_BYTES = 8192
    private const val MAX_FIELD_CHARS = 3000
    data class Payload(val front: String, val back: String)

    fun encode(card: CardEntity, collection: CollectionEntity): String {
        require((card.cardType ?: collection.defaultCardType) != "CHECKLIST")
        require((card.cardType ?: collection.defaultCardType) != "VAULT")
        require(card.frontText.isNotBlank())
        require(card.frontText.length <= MAX_FIELD_CHARS && card.backText.length <= MAX_FIELD_CHARS)
        val obj = JSONObject().apply {
            put("v", 1)
            put("front", card.frontText)
            put("back", if (collection.sideMode == "ONE_SIDED") "" else card.backText)
        }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        require(code.length <= MAX_QR_TEXT) { "QR payload too long" }
        return code
    }

    fun decode(value: String): Payload {
        require(value.startsWith(PREFIX) && value.length <= MAX_QR_TEXT)
        val zipped = Base64.getUrlDecoder().decode(value.substring(PREFIX.length))
        require(zipped.size <= MAX_JSON_BYTES)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(zipped)).use { input ->
            val buffer = ByteArray(2048)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= MAX_JSON_BYTES) { "QR data too large" }
                output.write(buffer, 0, n)
            }
        }
        val obj = JSONObject(output.toString(Charsets.UTF_8.name()))
        require(obj.getInt("v") == 1)
        val front = obj.getString("front")
        val back = obj.getString("back")
        require(front.isNotBlank() && front.length <= MAX_FIELD_CHARS && back.length <= MAX_FIELD_CHARS)
        return Payload(front, back)
    }

    fun qrBitmap(payload: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 768, 768, hints)
        val pixels = IntArray(768 * 768)
        for (y in 0 until 768) for (x in 0 until 768) {
            pixels[y * 768 + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, 768, 768, Bitmap.Config.ARGB_8888)
    }

    fun decodeImage(resolver: ContentResolver, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Cannot open QR image")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            return MultiFormatReader().decode(binary, mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )).text
        } finally {
            bitmap.recycle()
        }
    }

    fun saveToGallery(resolver: ContentResolver, bitmap: Bitmap): Uri {
        val data = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "phrasoteca-card-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Phrasoteca")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, data)
            ?: throw IllegalStateException("Could not create gallery image")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            } ?: error("Could not open gallery image")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
