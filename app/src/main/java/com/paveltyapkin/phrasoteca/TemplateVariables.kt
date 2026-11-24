package com.paveltyapkin.phrasoteca

import org.json.JSONObject

/** Variable identifiers are case-sensitive; braces doubled to escape literal braces.
 * Definitions persist as JSON, but runtime values are NEVER stored in Room.
 */
object TemplateVariables {
    private val token = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    private const val MAX_VARIABLES = 32

    /** Extract actual tokens, excluding escaped {{literal}} sequences. */
    fun names(text: String): List<String> = token.findAll(text)
        .filter { match ->
            val start = match.range.first
            val end = match.range.last
            (start == 0 || text[start - 1] != '{') &&
                (end + 1 >= text.length || text[end + 1] != '}')
        }
        .map { it.groupValues[1] }
        .distinct().take(MAX_VARIABLES).toList()

    fun names(front: String, back: String): List<String> =
        (names(front) + names(back)).distinct().take(MAX_VARIABLES)

    /** Retain recognized definitions when editing; newly typed tokens default to TEXT. */
    fun reconcile(previous: String, front: String, back: String): String {
        val old = try { JSONObject(previous) } catch (_: Exception) { JSONObject() }
        val result = JSONObject()
        names(front, back).forEach { name ->
            val entry = old.opt(name)
            val type = when (entry) {
                is JSONObject -> entry.optString("type", "TEXT")
                is String -> entry
                else -> "TEXT"
            }
            if (type == "CHOICE" && entry is JSONObject) {
                // Preserve configured choices when the phrase is edited.
                val choices = entry.optJSONArray("options")
                if (choices != null) {
                    result.put(name, JSONObject().put("type", "CHOICE").put("options", choices))
                } else result.put(name, "TEXT")
            } else {
                result.put(name, if (type == "NUMBER") "NUMBER" else "TEXT")
            }
        }
        return result.toString()
    }

    /** Old definitions are plain type strings; CHOICE uses an object with options. */
    fun typeOf(definitions: JSONObject, name: String): String {
        val value = definitions.opt(name)
        return when (value) {
            is JSONObject -> value.optString("type", "TEXT")
            is String -> value
            else -> "TEXT"
        }
    }

    fun choiceOptions(definitions: JSONObject, name: String): List<String> {
        val array = definitions.optJSONObject(name)?.optJSONArray("options") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    /** Returns an error-free, normalized choice list, or null for invalid input. */
    fun parseChoices(raw: String): List<String>? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size !in 2..20 || lines.any { it.length > 80 }) return null
        if (lines.distinct().size != lines.size) return null
        return lines
    }

    fun choiceDefinition(options: List<String>): JSONObject =
        JSONObject().put("type", "CHOICE").put("options", org.json.JSONArray(options))

    /** Unknown placeholders stay literal. Escaped braces lose one brace layer. */
    fun render(template: String, definitions: String, values: Map<String, String>): String {
        val allowed = try { JSONObject(definitions) } catch (_: Exception) { JSONObject() }
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template.startsWith("{{", i)) {
                val end = template.indexOf("}}", i + 2)
                if (end >= 0) {
                    out.append('{').append(template.substring(i + 2, end)).append('}')
                    i = end + 2
                    continue
                }
            }
            val match = token.find(template, i)
            if (match != null && match.range.first == i) {
                val name = match.groupValues[1]
                val replacement = values[name]
                if (allowed.has(name) && replacement != null && replacement.isNotEmpty()) {
                    out.append(replacement)
                } else out.append(match.value)
                i = match.range.last + 1
            } else {
                out.append(template[i]); i++
            }
        }
        return out.toString()
    }
}
