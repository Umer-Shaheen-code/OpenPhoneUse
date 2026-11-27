package com.phoneuse.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight on-device store for fast local notes, named lists (shopping, todo…), and contact aliases. */
class LocalAgentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_agent_store", Context.MODE_PRIVATE)

    data class Note(val text: String, val time: Long)

    @Synchronized fun notes(): List<Note> =
        arr("notes").map { Note(it.optString("t"), it.optLong("at")) }

    @Synchronized fun addNote(text: String) {
        val next = (arr("notes") + JSONObject().put("t", text).put("at", System.currentTimeMillis())).takeLast(MAX_NOTES)
        put("notes", next)
    }

    @Synchronized fun list(name: String): List<String> =
        (obj("lists").optJSONArray(name.lowercase())?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            .distinctBy { it.trim().lowercase() } // lists saved before addToList skipped repeats may hold them

    /** Adds [item] unless the list already has it (case and spaces ignored); returns whether it was added. */
    @Synchronized fun addToList(name: String, item: String): Boolean {
        if (list(name).any { it.trim().equals(item.trim(), ignoreCase = true) }) return false
        val o = obj("lists")
        val key = name.lowercase()
        val a = o.optJSONArray(key) ?: JSONArray()
        a.put(item)
        o.put(key, a)
        prefs.edit().putString("lists", o.toString()).apply()
        return true
    }

    @Synchronized fun aliases(): Map<String, String> =
        obj("aliases").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    @Synchronized fun learnAlias(heard: String, name: String) {
        if (heard.equals(name, ignoreCase = true)) return
        val o = obj("aliases")
        o.put(heard.lowercase(), name)
        prefs.edit().putString("aliases", o.toString()).apply()
    }

    @Synchronized fun wipeAll() {
        prefs.edit().clear().apply()
    }

    private fun arr(k: String): MutableList<JSONObject> {
        val a = runCatching { JSONArray(prefs.getString(k, "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }

    private fun obj(k: String): JSONObject =
        runCatching { JSONObject(prefs.getString(k, "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun put(k: String, items: List<JSONObject>) {
        prefs.edit().putString(k, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val MAX_NOTES = 200
    }
}
