package com.audiology2.stream.assist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat completion client used by the DAW assistant.
 * Works against OpenAI, OpenRouter, Ollama, LM Studio, or any endpoint
 * exposing /chat/completions. Runs on IO dispatcher.
 */
class AssistantEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Ask the model for an intent given the conversation history.
     * [history] is alternating user/assistant text (assistant = previous replies).
     */
    suspend fun requestIntent(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Double = 0.2
    ): AssistantProtocol.IntentCall = withContext(Dispatchers.IO) {
        val messages = mutableListOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", history.joinToString("\n") { (u, a) -> "USER: $u\nA: $a" }.ifEmpty { "Help me get started." })
        )
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                for (m in messages) {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
            put("temperature", temperature)
            putJsonObject("response_format") { put("type", "json_object") }
        }

        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                }
                resp.body?.string() ?: ""
            }
        } catch (e: IOException) {
            """{"intent":"none","reply":"Network error contacting the assistant: ${e.message?.take(120)}"}"""
        } catch (e: Exception) {
            """{"intent":"none","reply":"Assistant request failed: ${e.message?.take(120)}"}"""
        }

        if (raw.isBlank()) {
            return@withContext AssistantProtocol.IntentCall(intent = "none", reply = "Assistant returned an empty response.")
        }
        val content = runCatching {
            val root = json.parseToJsonElement(raw)
            val choices = (root as? JsonObject)?.get("choices") as? kotlinx.serialization.json.JsonArray
            val msg = choices?.firstOrNull()?.let { (it as? JsonObject)?.get("message") }
            val c = (msg as? JsonObject)?.get("content")
            (c as? JsonPrimitive)?.contentOrNull ?: c?.toString()?.trim('"')
        }.getOrNull()

        if (content != null) {
            AssistantProtocol.parseAssistantJson(content)
        } else {
            AssistantProtocol.IntentCall(intent = "none", reply = "Assistant returned an unreadable response.")
        }
    }
}