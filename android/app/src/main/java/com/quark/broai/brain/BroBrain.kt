package com.quark.broai.brain

import android.content.Context
import com.quark.broai.BroAIApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object BroBrain {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = ArrayDeque<Pair<String, String>>()
    val selfLearned = mutableListOf<String>()
    val goals       = mutableListOf<String>()
    val memory      = mutableListOf<String>()

    // Main ask — tries all providers in priority order
    suspend fun ask(ctx: Context, msg: String): String = withContext(Dispatchers.IO) {
        val p    = ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
        val name = p.getString(BroAIApp.K_NAME, "Boss") ?: "Boss"
        val sys  = buildSystem(name)
        val kG   = p.getString(BroAIApp.K_GEMINI,  "") ?: ""
        val kGr  = p.getString(BroAIApp.K_GROQ,    "") ?: ""
        val kM   = p.getString(BroAIApp.K_MISTRAL, "") ?: ""
        val kOR  = p.getString(BroAIApp.K_OR,      "") ?: ""
        val kHF  = p.getString(BroAIApp.K_HF,      "") ?: ""
        val kWG  = p.getString(BroAIApp.K_WG,      "") ?: ""
        val kGH  = p.getString(BroAIApp.K_GH,      "") ?: ""

        val reply =
            (if (kG.isNotEmpty())  gemini(kG, sys, msg)   else null) ?:
            (if (kGr.isNotEmpty()) groq(kGr, sys, msg)    else null) ?:
            (if (kM.isNotEmpty())  mistral(kM, sys, msg)  else null) ?:
            (if (kOR.isNotEmpty()) openrouter(kOR, sys, msg) else null) ?:
            (if (kHF.isNotEmpty()) huggingface(kHF, msg)  else null) ?:
            (if (kWG.isNotEmpty()) wisdomgate(kWG, sys, msg) else null) ?:
            (if (kGH.isNotEmpty()) github(kGH, sys, msg)  else null) ?:
            "No API key found. Add a Gemini or Groq key in Settings."

        history.addLast(Pair(msg, reply))
        if (history.size > 20) history.removeFirst()
        autoLearn(msg)
        reply
    }

    // Vision ask with camera frame
    suspend fun askVision(ctx: Context, msg: String, b64: String, coco: String): String =
        withContext(Dispatchers.IO) {
            val p  = ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
            val kG = p.getString(BroAIApp.K_GEMINI, "") ?: ""
            if (kG.isNotEmpty()) geminiVision(kG, b64, "$msg. COCO detects: $coco")
                ?: ask(ctx, "$msg. I can see: $coco")
            else ask(ctx, "$msg. I can see: $coco")
        }

    // Narration during auto-nav exploration
    suspend fun navNarrate(ctx: Context, saw: String): String = withContext(Dispatchers.IO) {
        ask(ctx, "You are a robot exploring a room autonomously. You detected: $saw. Give ONE short excited sentence about this discovery. No emojis.")
    }

    fun addGoal(g: String)      { if (!goals.contains(g)) goals.add(g) }
    fun completeGoal(kw: String) { goals.removeAll { it.contains(kw, true) } }
    fun addMemory(t: String)    { memory.add(0, t.take(100)); if (memory.size > 50) memory.removeAt(memory.size - 1) }

    private fun autoLearn(msg: String) {
        listOf(
            Regex("my name is ([A-Za-z ]+)", RegexOption.IGNORE_CASE),
            Regex("i (?:like|love|enjoy) (.+)", RegexOption.IGNORE_CASE),
            Regex("i (?:hate|dislike) (.+)", RegexOption.IGNORE_CASE),
            Regex("i (?:work|am) (?:at|as|a) (.+)", RegexOption.IGNORE_CASE),
            Regex("i live in ([A-Za-z ]+)", RegexOption.IGNORE_CASE),
            Regex("remember (?:that )?(.+)", RegexOption.IGNORE_CASE)
        ).forEach { p ->
            p.find(msg)?.let { m ->
                val f = m.value.take(80)
                if (!selfLearned.any { it.contains(f.take(20), true) }) {
                    selfLearned.add(0, f)
                    if (selfLearned.size > 50) selfLearned.removeAt(selfLearned.size - 1)
                }
            }
        }
    }

    private fun buildSystem(name: String): String {
        val h = history.takeLast(6).joinToString("\n") { (u, b) -> "User: $u\nBro: $b" }
        val m = if (memory.isNotEmpty()) "\nMemory: " + memory.take(8).joinToString("; ") else ""
        val l = if (selfLearned.isNotEmpty()) "\nKnown about user: " + selfLearned.take(8).joinToString("; ") else ""
        val g = if (goals.isNotEmpty()) "\nActive Goals: " + goals.joinToString(", ") else ""
        return "You are Bro AI, a Jarvis-style AI companion on Android.\n" +
               "Owner: $name\nPersonality: Confident, direct, like Jarvis.\n" +
               "Rules:\n- Keep replies SHORT: max 2 sentences\n- No emojis in speech\n" +
               "- Robot tags: [MOVE_FORWARD][MOVE_BACKWARD][TURN_LEFT][TURN_RIGHT][STOP]" +
               "[TURN_90L][TURN_90R][TURN_180][HEAD_LEFT][HEAD_RIGHT][HEAD_CENTER]\n" +
               "$m$l$g\nRecent:\n$h"
    }

    // Priority 1: Gemini — Gemma27B > Gemma12B > Gemini Flash
    private fun gemini(key: String, sys: String, msg: String): String? {
        for (model in listOf("gemma-3-27b-it", "gemma-3-12b-it", "gemini-2.0-flash")) {
            try {
                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", sys))))
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", msg)))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 300); put("temperature", 0.7)
                    })
                }
                val r = http.newCall(Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (r.code == 429 || !r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }

    private fun geminiVision(key: String, b64: String, prompt: String): String? {
        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg"); put("data", b64)
                            })
                        })
                        put(JSONObject().put("text", prompt))
                    })
                }))
            }
            val r = http.newCall(Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()).execute()
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string() ?: "")
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    // Priority 2: Groq — Llama70B > Llama8B > Mixtral > Gemma2
    private fun groq(key: String, sys: String, msg: String): String? {
        for (model in listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "gemma2-9b-it")) {
            try {
                val body = JSONObject().apply {
                    put("model", model); put("max_tokens", 300); put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", sys) })
                        put(JSONObject().apply { put("role", "user");   put("content", msg) })
                    })
                }
                val r = http.newCall(Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (r.code == 429 || !r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }

    // Priority 3: Mistral — Large > Small > Nemo
    private fun mistral(key: String, sys: String, msg: String): String? {
        for (model in listOf("mistral-large-latest", "mistral-small-latest", "open-mistral-nemo")) {
            try {
                val body = JSONObject().apply {
                    put("model", model); put("max_tokens", 300); put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", sys) })
                        put(JSONObject().apply { put("role", "user");   put("content", msg) })
                    })
                }
                val r = http.newCall(Request.Builder()
                    .url("https://api.mistral.ai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (r.code == 429 || !r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }

    // Priority 4: OpenRouter — free models
    private fun openrouter(key: String, sys: String, msg: String): String? {
        for (model in listOf("arcee-ai/trinity-large-preview:free", "stepfun/step-3-5-flash:free", "liquid/lfm-2.5-1.2b-instruct:free")) {
            try {
                val body = JSONObject().apply {
                    put("model", model); put("max_tokens", 300)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", sys) })
                        put(JSONObject().apply { put("role", "user");   put("content", msg) })
                    })
                }
                val r = http.newCall(Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("HTTP-Referer", "https://broai.app")
                    .addHeader("X-Title", "Bro AI")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (!r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }

    // Priority 5: HuggingFace
    private fun huggingface(key: String, msg: String): String? {
        for (model in listOf("cutycat2000x/MeowGPT-3.5", "xlelords/omriX")) {
            try {
                val body = JSONObject().apply {
                    put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", msg) }))
                    put("max_tokens", 200); put("stream", false)
                }
                val r = http.newCall(Request.Builder()
                    .url("https://api-inference.huggingface.co/models/$model/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (!r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }

    // Priority 6: WisdomGate
    private fun wisdomgate(key: String, sys: String, msg: String): String? = try {
        val body = JSONObject().apply {
            put("model", "minimax-m2.5:free"); put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", sys) })
                put(JSONObject().apply { put("role", "user");   put("content", msg) })
            })
        }
        val r = http.newCall(Request.Builder()
            .url("https://api.wisdomgate.io/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()).execute()
        if (!r.isSuccessful) null
        else JSONObject(r.body?.string() ?: "")
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim().takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    // Priority 7: GitHub AI — GPT-4o > Llama > DeepSeek > Phi-4
    private fun github(key: String, sys: String, msg: String): String? {
        for (model in listOf("gpt-4o", "gpt-4o-mini", "meta-llama-3.3-70b-instruct", "deepseek-r1", "phi-4")) {
            try {
                val body = JSONObject().apply {
                    put("model", model); put("max_tokens", 300)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", sys) })
                        put(JSONObject().apply { put("role", "user");   put("content", msg) })
                    })
                }
                val r = http.newCall(Request.Builder()
                    .url("https://models.inference.ai.azure.com/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute()
                if (!r.isSuccessful) continue
                val t = JSONObject(r.body?.string() ?: "")
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                if (t.isNotEmpty()) return t
            } catch (e: Exception) { continue }
        }
        return null
    }
}
