package com.audiology2.stream.assist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure protocol for the DAW assistant: tool catalog, system prompt
 * construction, and strict-JSON intent parsing. No Android/network deps -
 * unit testable.
 */
object AssistantProtocol {

    /** One callable DAW action exposed to the model. */
    data class Tool(
        val name: String,
        val description: String,
        /** JSON object schema of args, e.g. {"bpm": {"type":"number", "desc":"...", "required":true}} */
        val args: Map<String, ArgSpec>
    ) {
        data class ArgSpec(
            val type: String,
            val desc: String,
            val required: Boolean = false
        )
    }

    /** Parsed model reply: which tool to run, its args, and a label. */
    @Serializable
    data class IntentCall(
        val intent: String = "",
        val args: JsonObject = JsonObject(emptyMap()),
        val reply: String = ""
    ) {
        fun argString(key: String): String? = args[key]?.jsonPrimitive?.contentOrNull
        fun argDouble(key: String): Double? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        fun argInt(key: String): Int? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun argBoolean(key: String): Boolean? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.let { it.equals("true", true) }
    }

    /** Snapshot of the project fed to the model as context. */
    @Serializable
    data class ProjectContext(
        val name: String = "",
        val tempo: Double = 120.0,
        val timeSignature: String = "4/4",
        val tracks: List<TrackCtx> = emptyList()
    )

    @Serializable
    data class TrackCtx(
        val id: String,
        val name: String,
        val muted: Boolean = false,
        val solo: Boolean = false,
        val clips: List<ClipCtx> = emptyList(),
        val midiClips: List<String> = emptyList()
    )

    @Serializable
    data class ClipCtx(
        val id: String,
        val name: String,
        val startSec: Double,
        val durationSec: Double,
        val pitchSemitones: Double = 0.0,
        val speedRatio: Double = 1.0
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Build the system prompt for a given tool catalog + project snapshot. */
    fun buildSystemPrompt(tools: List<Tool>, project: ProjectContext, extra: String = ""): String {
        val toolBlock = tools.joinToString("\n") { t ->
            val argsBlock = t.args.entries.joinToString(", ") { (k, v) ->
                val req = if (v.required) " (required)" else ""
                "\"$k\": ${v.type}$req - ${v.desc}"
            }
            "- ${t.name}: ${t.description} | args: {$argsBlock}"
        }
        val ctx = json.encodeToString(ProjectContext.serializer(), project)
        return """
You are the assistant inside a mobile audio DAW. You help the user operate
the DAW. The DAW project state is provided as JSON below.

DAW PROJECT SNAPSHOT (JSON):
$ctx

AVAILABLE TOOLS:
$toolBlock

You must respond with ONLY a single JSON object, no markdown, no prose around it:
{"intent": "<tool name or 'none'>", "args": {<arguments>}, "reply": "<one short line telling the user what was done or the answer>"}

Rules:
- Choose the best tool for the request; fill required args from the snapshot.
- Use EXACT clip/track names from the snapshot when a name is needed.
- If the request is not DAW-related or impossible, use intent "none" and explain in reply.
$extra
""".trim()
    }

    /** Parse the model's JSON reply into an [IntentCall]. Tolerant of markdown fences. */
    fun parseAssistantJson(raw: String): IntentCall {
        val cleaned = raw
            .trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val trimmed = cleaned.trim()
            val obj = if (trimmed.startsWith("{")) {
                json.parseToJsonElement(trimmed).jsonObject
            } else {
                json.parseToJsonElement("{$trimmed}").jsonObject
            }
            IntentCall(
                intent = obj["intent"]?.jsonPrimitive?.contentOrNull ?: "",
                args = obj["args"] as? JsonObject ?: JsonObject(emptyMap()),
                reply = obj["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }.getOrElse { IntentCall(intent = "none", reply = "I could not understand that request.") }
    }

    /** Result of executing an intent against the DAW. */
    data class ActionResult(
        val ok: Boolean,
        val message: String
    )

    /**
     * Extract the assistant's text from an OpenAI-compatible chat-completion
     * response body. Tolerant of: error envelopes returned with HTTP 200,
     * reasoning models (reasoning / reasoning_content without content),
     * the legacy `text` field, and non-string content.
     * Returns null when nothing usable is present.
     */
    fun extractAssistantText(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
        val msg = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
        fun field(key: String): String? =
            (msg[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return (field("content")
            ?: field("reasoning")
            ?: field("reasoning_content")
            ?: field("text")
            ?: msg["content"]?.toString()?.takeIf { it != "null" && it != "\"\"" })?.trim()
    }

    /** Extract a gateway error message from a response body, if present. */
    fun extractError(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val err = root["error"] ?: return null
        return ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: err.toString()
    }

    /**
     * Parse a full chat-completion HTTP body into an [IntentCall].
     * Handles error envelopes and unreadable bodies with a readable reply
     * that includes a raw snippet for diagnosis.
     */
    fun parseChatCompletion(raw: String): IntentCall {
        if (raw.isBlank()) return IntentCall(intent = "none", reply = "Assistant returned an empty response.")
        extractError(raw)?.let { return IntentCall(intent = "none", reply = "Assistant endpoint error: ${it.take(200)}") }
        val content = extractAssistantText(raw)
        if (!content.isNullOrBlank()) return parseAssistantJson(content)
        val snippet = raw.replace(Regex("\\s+"), " ").take(240)
        return IntentCall(intent = "none", reply = "Assistant returned an unreadable response. Raw: $snippet")
    }
}