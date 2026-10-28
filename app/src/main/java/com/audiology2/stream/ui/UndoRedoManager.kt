package com.audiology2.stream.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import java.util.ArrayDeque

/**
 * Undo/Redo manager for editor operations.
 * Maintains a stack of reversible actions.
 */
class UndoRedoManager(
    private val maxHistory: Int = 100
) {

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()
    private var isExecuting = false

    /** Execute an action and add to undo stack */
    fun execute(action: Action) {
        if (isExecuting) {
            action.execute()
            return
        }
        
        isExecuting = true
        try {
            action.execute()
            undoStack.addLast(action)
            redoStack.clear()
            
            // Trim history
            while (undoStack.size > maxHistory) {
                undoStack.removeFirst()
            }
        } finally {
            isExecuting = false
        }
    }

    /** Undo last action */
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        
        val action = undoStack.removeLast()
        isExecuting = true
        try {
            action.undo()
            redoStack.addLast(action)
            return true
        } finally {
            isExecuting = false
        }
    }

    /** Redo last undone action */
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        
        val action = redoStack.removeLast()
        isExecuting = true
        try {
            action.execute()
            undoStack.addLast(action)
            return true
        } finally {
            isExecuting = false
        }
    }

    /** Check if undo available */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Check if redo available */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Clear history */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Get undo description */
    val undoDescription: String? get() = undoStack.lastOrNull()?.description

    /** Get redo description */
    val redoDescription: String? get() = redoStack.lastOrNull()?.description

    /** Base action class */
    abstract class Action(
        val description: String
    ) {
        abstract fun execute()
        abstract fun undo()
    }

    /** Composite action for grouping multiple actions */
    class CompoundAction(
        description: String,
        private val actions: List<Action>
    ) : Action(description) {
        override fun execute() {
            actions.forEach { it.execute() }
        }
        override fun undo() {
            actions.reversed().forEach { it.undo() }
        }
    }

    /** Clip action for add/remove/move */
    class ClipAction(
        description: String,
        private val project: AudioProject,
        private val clip: Clip,
        private val track: Track,
        private val oldStartTime: Double,
        private val oldTrackId: String,
        private val isAdd: Boolean
    ) : Action(description) {
        override fun execute() {
            if (isAdd) {
                track.appendClip(clip)
            } else {
                track.removeClip(clip.id)
            }
        }
        override fun undo() {
            if (isAdd) {
                track.removeClip(clip.id)
            } else {
                val originalTrack = project.getTrack(oldTrackId) ?: return
                clip.startTime = oldStartTime
                clip.trackId = oldTrackId
                originalTrack.appendClip(clip)
            }
        }
    }

    /** Clip property change action */
    class ClipPropertyAction(
        description: String,
        private val clip: Clip,
        private val property: KProperty<*>,
        private val oldValue: Any
    ) : Action(description) {
        override fun execute() {
            // Executed when property is set
        }
        override fun undo() {
            try {
                // Use reflection to set the field directly
                val field = clip.javaClass.getDeclaredField(property.name)
                field.isAccessible = true
                field.set(clip, oldValue)
            } catch (e: Exception) {
                // Ignore - property might not be a field
            }
        }
    }

    /** Track action for add/remove/reorder */
    class TrackAction(
        description: String,
        private val project: AudioProject,
        private val track: Track,
        private val oldIndex: Int,
        private val isAdd: Boolean
    ) : Action(description) {
        override fun execute() {
            if (isAdd) {
                project.tracks.add(track)
            } else {
                project.removeTrack(track.id)
            }
        }
        override fun undo() {
            if (isAdd) {
                project.removeTrack(track.id)
            } else {
                project.tracks.add(oldIndex, track)
            }
        }
    }
}

/** Extension for easy action creation */
inline fun <T : Any> T.recordChange(
    manager: UndoRedoManager,
    property: KProperty1<T, *>,
    oldValue: Any,
    description: String
) {
    val target = this
    val action = object : UndoRedoManager.Action(description) {
        override fun execute() {}
        override fun undo() {
            try {
                val clazz = target.javaClass
                val field = clazz.getDeclaredField(property.name)
                field.isAccessible = true
                field.set(target, oldValue)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    manager.execute(action)
}