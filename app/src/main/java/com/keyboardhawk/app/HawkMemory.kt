package com.keyboardhawk.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ══════════════════════════════════════════════════════════════════
//  HawkMemory — Persistent key/value memory for the AI agent
//  Stored in SharedPreferences as a JSON array.
//  Memories are injected into Groq planning prompts automatically.
//  Relevance is scored by keyword overlap (no embeddings needed).
// ══════════════════════════════════════════════════════════════════

class HawkMemory(private val context: Context) {

    data class Memory(
        val id: String,
        val content: String,
        val timestamp: Long,
        val source: String,      // "user" | "auto" (AI-extracted)
        val useCount: Int = 0
    )

    private val prefs = context.getSharedPreferences("hawk_memory", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("MMM d HH:mm", Locale.US)

    // ── CRUD ──────────────────────────────────────────────────────

    fun save(content: String, source: String = "user"): Memory {
        val all = getAll().toMutableList()
        // De-duplicate: skip if very similar entry already exists
        if (all.any { similarity(it.content, content) > 0.8f }) return all.first()
        val memory = Memory(
            id = UUID.randomUUID().toString().take(8),
            content = content.trim(),
            timestamp = System.currentTimeMillis(),
            source = source
        )
        all.add(0, memory)
        if (all.size > 50) { // cap at 50 memories
            // Remove oldest auto-generated ones first, keep user ones
            val pruned = (all.filter { it.source == "user" }.take(30) +
                         all.filter { it.source == "auto" }.take(20))
            persist(pruned)
        } else {
            persist(all)
        }
        return memory
    }

    fun delete(id: String) {
        persist(getAll().filter { it.id != id })
    }

    fun getAll(): List<Memory> {
        val raw = prefs.getString("memories", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Memory(
                    id = obj.optString("id", UUID.randomUUID().toString().take(8)),
                    content = obj.optString("content", ""),
                    timestamp = obj.optLong("timestamp", 0L),
                    source = obj.optString("source", "user"),
                    useCount = obj.optInt("useCount", 0)
                )
            }.filter { it.content.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    fun clear() { prefs.edit().remove("memories").apply() }

    // ── RELEVANCE SEARCH ──────────────────────────────────────────
    // Returns up to `limit` memories most relevant to the given query.
    // Scoring: word overlap between query and memory content (fast, no model needed).

    fun searchRelevant(query: String, limit: Int = 6): List<Memory> {
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (queryWords.isEmpty()) return getAll().take(limit)
        return getAll()
            .map { mem ->
                val memWords = mem.content.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
                val score = queryWords.count { it in memWords }.toFloat() / queryWords.size.toFloat()
                Pair(mem, score)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // Returns a formatted memory block ready to inject into prompts
    fun buildPromptBlock(query: String): String {
        val relevant = searchRelevant(query)
        if (relevant.isEmpty()) return ""
        return "\nUSER MEMORIES (apply these):\n" +
            relevant.joinToString("\n") { "  - ${it.content}" } + "\n"
    }

    // Format a memory for display in the UI
    fun formatDate(memory: Memory): String = dateFormat.format(Date(memory.timestamp))

    // ── PRIVATE ───────────────────────────────────────────────────

    private fun persist(memories: List<Memory>) {
        val arr = JSONArray()
        memories.forEach { mem ->
            arr.put(JSONObject().apply {
                put("id", mem.id)
                put("content", mem.content)
                put("timestamp", mem.timestamp)
                put("source", mem.source)
                put("useCount", mem.useCount)
            })
        }
        prefs.edit().putString("memories", arr.toString()).apply()
    }

    // Simple token-level Jaccard similarity (0..1)
    private fun similarity(a: String, b: String): Float {
        val tokA = a.lowercase().split(Regex("\\W+")).toSet()
        val tokB = b.lowercase().split(Regex("\\W+")).toSet()
        val intersection = tokA.intersect(tokB).size
        val union = (tokA + tokB).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }
}
