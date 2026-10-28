package com.audiology2.stream.assist

import com.audiology2.stream.assist.AssistantProtocol.Tool.ArgSpec
import com.audiology2.stream.assist.AssistantProtocol.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantProtocolTest {

    private val tools = listOf(
        Tool("set_tempo", "Set BPM", mapOf("bpm" to ArgSpec("number", "BPM", true))),
        Tool("add_track", "Add a track", mapOf("name" to ArgSpec("string", "Name")))
    )

    @Test
    fun parse_plainJsonObject() {
        val call = AssistantProtocol.parseAssistantJson(
            """{"intent":"set_tempo","args":{"bpm":128},"reply":"done"}"""
        )
        assertEquals("set_tempo", call.intent)
        assertEquals(128.0, call.argDouble("bpm"))
    }

    @Test
    fun parse_markdownFenced() {
        val call = AssistantProtocol.parseAssistantJson(
            "```json\n{\"intent\":\"add_track\",\"args\":{\"name\":\"drums\"},\"reply\":\"ok\"}\n```"
        )
        assertEquals("add_track", call.intent)
        assertEquals("drums", call.argString("name"))
    }

    @Test
    fun parse_garbageReturnsNone() {
        val call = AssistantProtocol.parseAssistantJson("not json at all")
        assertEquals("none", call.intent)
    }

    @Test
    fun systemPrompt_listsToolsAndSnapshot() {
        val prompt = AssistantProtocol.buildSystemPrompt(
            tools,
            AssistantProtocol.ProjectContext(name = "TestSong", tempo = 120.0, tracks = listOf())
        )
        assertTrue(prompt.contains("set_tempo"))
        assertTrue(prompt.contains("TestSong"))
        assertTrue(prompt.contains("120"))
    }

    @Test
    fun toolbox_dispatchesWithArgs() {
        val box = DawToolbox()
        var gotBpm: Double? = null
        box.register(Tool("set_tempo", "", mapOf("bpm" to ArgSpec("number", "", true)))) { call ->
            gotBpm = call.argDouble("bpm")
            AssistantProtocol.ActionResult(true, "ok")
        }
        val res = box.execute(AssistantProtocol.parseAssistantJson("""{"intent":"set_tempo","args":{"bpm":99}}"""))
        assertTrue(res.ok)
        assertEquals(99.0, gotBpm)
    }

    @Test
    fun toolbox_unknownIntentFailsGracefully() {
        val box = DawToolbox()
        val res = box.execute(
            AssistantProtocol.IntentCall(intent = "explode", args = JsonObject(emptyMap()), reply = "")
        )
        assertEquals(false, res.ok)
    }

    @Test
    fun projectSnapshot_encodesClips() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val ctx = AssistantProtocol.ProjectContext(
            name = "P", tempo = 100.0,
            tracks = listOf(AssistantProtocol.TrackCtx(
                id = "t1", name = "Bass",
                clips = listOf(AssistantProtocol.ClipCtx(id = "c1", name = "sub", startSec = 1.0, durationSec = 2.0))
            ))
        )
        val raw = json.encodeToString(AssistantProtocol.ProjectContext.serializer(), ctx)
        assertTrue(raw.contains("sub"))
        assertTrue(raw.contains("Bass"))
    }
}