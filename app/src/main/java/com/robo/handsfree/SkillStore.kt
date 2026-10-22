package com.robo.handsfree

import android.content.Context
import com.robo.handsfree.core.normalizeText
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything Robo remembers lives here, in plain SharedPreferences on the phone.
 * Nothing is uploaded anywhere.
 */
class SkillStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("robo_memory", Context.MODE_PRIVATE)

    /** Normalised phrase -> the command it should run. */
    fun learned(): Map<String, String> {
        val raw = prefs.getString(KEY_LEARNED, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    fun learn(phrase: String, action: String) {
        val updated = learned().toMutableMap()
        updated[normalizeText(phrase)] = action
        saveLearned(updated)
    }

    fun forget(phrase: String): Boolean {
        val key = normalizeText(phrase)
        val updated = learned().toMutableMap()
        val removed = updated.remove(key) != null
        if (removed) saveLearned(updated)
        return removed
    }

    fun clearLearned() {
        prefs.edit().remove(KEY_LEARNED).apply()
    }

    fun addNote(text: String) {
        val notes = notes().toMutableList()
        notes.add(0, text.trim())
        saveNotes(notes.take(100))
    }

    fun notes(): List<String> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun clearNotes() {
        prefs.edit().remove(KEY_NOTES).apply()
    }

    private fun saveLearned(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_LEARNED, json.toString()).apply()
    }

    private fun saveNotes(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs.edit().putString(KEY_NOTES, array.toString()).apply()
    }

    private companion object {
        const val KEY_LEARNED = "learned_commands"
        const val KEY_NOTES = "notes"
    }
}
