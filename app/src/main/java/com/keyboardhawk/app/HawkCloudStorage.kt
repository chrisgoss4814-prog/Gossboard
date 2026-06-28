package com.keyboardhawk.app

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════
//  HawkCloudStorage — Google Drive + Sheets via Apps Script proxy
//
//  Architecture:
//    Android app ──REST──▶ Apps Script Web App ──▶ Google Drive / Sheets
//
//  This avoids OAuth in the Android app entirely. The script runs
//  as the user's Google account and has full Drive/Sheets access.
//
//  Setup (one-time — see README for full instructions):
//    1. Create a Google Apps Script project
//    2. Paste the HawkScript code from README
//    3. Deploy as Web App (Execute as: Me, Access: Anyone)
//    4. Copy deployment URL → paste into SCRIPT_URL below
//    5. Set a secret token → paste into SCRIPT_TOKEN below
//
//  Config (fill these in):
// ══════════════════════════════════════════════════════════════════

object HawkCloudConfig {
    const val SCRIPT_URL   = "https://script.google.com/macros/s/PASTE_YOUR_DEPLOYMENT_ID/exec"
    const val SCRIPT_TOKEN = "PASTE_YOUR_SECRET_TOKEN"
    const val SHEETS_ID    = "PASTE_YOUR_GOOGLE_SHEET_ID"
    const val DRIVE_FOLDER = "PASTE_YOUR_GOOGLE_DRIVE_FOLDER_ID"
    val isConfigured get() = !SCRIPT_URL.contains("PASTE") && !SCRIPT_TOKEN.contains("PASTE")
}

class HawkCloudStorage {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val TAG = "HawkCloud"

    // ── MEMORY (Google Sheets) ────────────────────────────────────

    // Save a memory entry to Sheets.
    // Row format: [id, content, timestamp, source, useCount]
    fun saveMemory(
        id: String, content: String, timestamp: Long,
        source: String, callback: (Boolean) -> Unit
    ) {
        if (!HawkCloudConfig.isConfigured) { callback(false); return }
        val body = JSONObject().apply {
            put("action", "saveMemory")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("id", id)
            put("content", content)
            put("timestamp", timestamp)
            put("source", source)
        }
        post(body) { ok, _ -> callback(ok) }
    }

    // Fetch all memory rows from Sheets.
    fun loadMemories(callback: (List<Map<String, String>>) -> Unit) {
        if (!HawkCloudConfig.isConfigured) { callback(emptyList()); return }
        val body = JSONObject().apply {
            put("action", "loadMemories")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
        }
        post(body) { ok, resp ->
            if (!ok || resp == null) { callback(emptyList()); return@post }
            try {
                val rows = resp.optJSONArray("rows") ?: JSONArray()
                val result = mutableListOf<Map<String, String>>()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    result.add(mapOf(
                        "id"        to (row.optString(0)),
                        "content"   to (row.optString(1)),
                        "timestamp" to (row.optString(2)),
                        "source"    to (row.optString(3)),
                        "useCount"  to (row.optString(4))
                    ))
                }
                callback(result)
            } catch (e: Exception) {
                Log.e(TAG, "loadMemories parse error: ${e.message}")
                callback(emptyList())
            }
        }
    }

    // Search memories by keyword via script
    fun searchMemories(query: String, callback: (List<Map<String, String>>) -> Unit) {
        if (!HawkCloudConfig.isConfigured) { callback(emptyList()); return }
        val body = JSONObject().apply {
            put("action", "searchMemories")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("query", query)
        }
        post(body) { ok, resp ->
            if (!ok || resp == null) { callback(emptyList()); return@post }
            try {
                val rows = resp.optJSONArray("rows") ?: JSONArray()
                val result = mutableListOf<Map<String, String>>()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    result.add(mapOf(
                        "id"      to row.optString(0),
                        "content" to row.optString(1),
                        "source"  to row.optString(3)
                    ))
                }
                callback(result)
            } catch (e: Exception) { callback(emptyList()) }
        }
    }

    fun deleteMemory(id: String, callback: (Boolean) -> Unit) {
        if (!HawkCloudConfig.isConfigured) { callback(false); return }
        val body = JSONObject().apply {
            put("action", "deleteMemory")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("id", id)
        }
        post(body) { ok, _ -> callback(ok) }
    }

    // ── NODE TREE STORAGE (Google Drive) ──────────────────────────

    // Upload a full node tree JSON to Drive.
    // Returns the Drive file ID (or null on failure).
    fun uploadNodeTree(
        taskId: String,
        step: Int,
        fullTreeJson: String,
        callback: (String?) -> Unit
    ) {
        if (!HawkCloudConfig.isConfigured) { callback(null); return }
        val body = JSONObject().apply {
            put("action", "uploadNodeTree")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("taskId", taskId)
            put("step", step)
            put("content", fullTreeJson)
        }
        post(body) { ok, resp ->
            if (!ok || resp == null) { callback(null); return@post }
            callback(resp.optString("fileId").ifEmpty { null })
        }
    }

    // Fetch a node tree from Drive by file ID.
    fun downloadNodeTree(fileId: String, callback: (String?) -> Unit) {
        if (!HawkCloudConfig.isConfigured) { callback(null); return }
        val body = JSONObject().apply {
            put("action", "downloadNodeTree")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("fileId", fileId)
        }
        post(body) { ok, resp ->
            if (!ok || resp == null) { callback(null); return@post }
            callback(resp.optString("content").ifEmpty { null })
        }
    }

    // ── TASK LOG (Google Drive) ───────────────────────────────────

    // Append a completed task summary to a Drive log file.
    fun logTask(taskSummary: String, callback: (Boolean) -> Unit = {}) {
        if (!HawkCloudConfig.isConfigured) { callback(false); return }
        val body = JSONObject().apply {
            put("action", "logTask")
            put("token", HawkCloudConfig.SCRIPT_TOKEN)
            put("summary", taskSummary)
            put("timestamp", System.currentTimeMillis())
        }
        post(body) { ok, _ -> callback(ok) }
    }

    // ── HTTP ──────────────────────────────────────────────────────

    private fun post(body: JSONObject, callback: (Boolean, JSONObject?) -> Unit) {
        val reqBody = body.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(HawkCloudConfig.SCRIPT_URL)
            .post(reqBody)
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Cloud request failed: ${e.message}")
                callback(false, null)
            }
            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Cloud HTTP ${response.code}: ${raw.take(80)}")
                    callback(false, null)
                    return
                }
                try {
                    val json = JSONObject(raw)
                    callback(json.optString("status") == "ok", json)
                } catch (_: Exception) {
                    callback(false, null)
                }
            }
        })
    }
}
