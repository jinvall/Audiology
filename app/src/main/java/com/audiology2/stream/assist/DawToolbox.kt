package com.audiology2.stream.assist

import com.audiology2.stream.assist.AssistantProtocol.ActionResult
import com.audiology2.stream.assist.AssistantProtocol.Tool.ArgSpec
import com.audiology2.stream.assist.AssistantProtocol.Tool

/**
 * Tool catalog + dispatch for the DAW assistant. MainActivity registers a
 * handler per tool (lambdas over the DAW API). [defaultTools] describes the
 * v1 command set.
 */
class DawToolbox {

    val tools = mutableListOf<Tool>()
    private val handlers = mutableMapOf<String, (AssistantProtocol.IntentCall) -> ActionResult>()

    fun register(tool: Tool, handler: (AssistantProtocol.IntentCall) -> ActionResult) {
        tools.removeAll { it.name == tool.name }
        tools.add(tool)
        handlers[tool.name] = handler
    }

    fun execute(call: AssistantProtocol.IntentCall): ActionResult {
        val handler = handlers[call.intent] ?: return ActionResult(
            false, "I don't know how to '${call.intent}' yet"
        )
        return runCatching { handler(call) }.getOrElse { e ->
            ActionResult(false, "Tool '${call.intent}' failed: ${e.message?.take(120)}")
        }
    }

    companion object {
        val defaultTools: List<Tool> = listOf(
            Tool("set_tempo", "Set the project tempo in BPM", mapOf(
                "bpm" to ArgSpec("number", "Target tempo in BPM (40-300)", required = true)
            )),
            Tool("add_track", "Add a new empty audio/MIDI track", mapOf(
                "name" to ArgSpec("string", "Track name (optional)")
            )),
            Tool("split_clip", "Split the selected (or named) clip at a position", mapOf(
                "clipName" to ArgSpec("string", "Clip name substring to target"),
                "atFraction" to ArgSpec("number", "Where to split as 0-1 fraction of the clip (default 0.5)")
            )),
            Tool("duplicate_clip", "Duplicate the selected (or named) clip", mapOf(
                "clipName" to ArgSpec("string", "Clip name substring to target")
            )),
            Tool("delete_clip", "Delete the selected (or named) clip", mapOf(
                "clipName" to ArgSpec("string", "Clip name substring to target")
            )),
            Tool("pitch_speed", "Change pitch and/or speed of the selected (or named) clip", mapOf(
                "clipName" to ArgSpec("string", "Clip name substring to target"),
                "semitones" to ArgSpec("number", "Pitch shift in semitones (-12..12)"),
                "speed" to ArgSpec("number", "Speed ratio (0.5-2.0)"),
                "stretch" to ArgSpec("number", "Time-stretch ratio (0.5-2.0)")
            )),
            Tool("make_sample", "Turn the selected (or named) clip into a beat sample", mapOf(
                "clipName" to ArgSpec("string", "Clip name substring to target")
            )),
            Tool("set_loop", "Set a loop region in seconds, or omit for the selection", mapOf(
                "startSec" to ArgSpec("number", "Loop start in seconds"),
                "endSec" to ArgSpec("number", "Loop end in seconds")
            )),
            Tool("add_midi_clip", "Add an empty MIDI clip on the Beats/first track and open the editor", mapOf(
                "name" to ArgSpec("string", "MIDI clip name (optional)")
            )),
            Tool("export_wav", "Export the project to WAV (Loudness adjustments etc are manual)", mapOf(
                "name" to ArgSpec("string", "Optional export file name")
            ))
        )
    }
}