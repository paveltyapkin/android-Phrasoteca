package com.paveltyapkin.phrasoteca

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Public, offline QR transfer for ONE Phrasoteca collection. Never for secrets. */
object CollectionQrCodec {
    private const val PREFIX = "PHRASOTECA:COLLECTION:1:"
    private const val MAX_PAYLOAD_CHARS = 1500
    private const val MAX_JSON_BYTES = 256 * 1024

    data class Preview(val name: String, val folders: Int, val cards: Int, val json: String)

    fun encode(json: String): String {
        val preview = inspect(json)
        require(preview.name.isNotBlank())
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        require(value.length <= MAX_PAYLOAD_CHARS) { "Collection too large for QR" }
        return value
    }

    fun decode(raw: String): Preview {
        require(raw.startsWith(PREFIX) && raw.length <= MAX_PAYLOAD_CHARS) { "Wrong QR type" }
        val encoded = raw.substring(PREFIX.length)
        val compressed = Base64.getUrlDecoder().decode(encoded)
        require(compressed.size <= MAX_JSON_BYTES)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_JSON_BYTES) { "QR data too large" }
                output.write(buffer, 0, count)
            }
        }
        return inspect(output.toString(Charsets.UTF_8.name()))
    }

    fun inspect(json: String): Preview {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Collection too large" }
        val root = JSONObject(json)
        require(root.getString("format") == "phrasoteca" && root.getInt("version") in 3..4)
        val collections = root.getJSONArray("collections")
        require(collections.length() == 1) { "Expected one collection" }
        val collection = collections.getJSONObject(0)
        require(collection.getString("defaultCardType") != "VAULT")
        val cards = collection.getJSONArray("cards")
        val folders = collection.getJSONArray("folders")
        require(cards.length() <= 1000 && folders.length() <= 1000)
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            require(card.optString("cardType") != "VAULT")
        }
        val name = collection.getString("name")
        require(name.isNotBlank() && name.length <= 200)
        return Preview(name, folders.length(), cards.length(), json)
    }
}
