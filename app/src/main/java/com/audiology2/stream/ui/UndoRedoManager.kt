package com.audiology2.stream.ui

import kotlin.reflect.KMutableProperty1
import java.util.ArrayDeque

/**
 * Undo/Redo manager for editor operations.
 * Maintains a stack of reversible actions. Actions operate on the current
 * project snapshot via [ProjectManager] so edits persist and the UI can
 * refresh through [onChange].
 */
class UndoRedoManager(
    private val maxHistory: Int = 100
) {

    /** Invoked after an action mutates the current project (undo or redo). */
    var onChange: ((AudioProject) -> Unit)? = null

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
        final override fun equals(other: Any?) = other === this
        final override fun hashCode() = System.identityHashCode(this)
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

    /** Clip action for add/remove/move. Mutates the current project via
     *  [ProjectManager.updateCurrentProject] so edits persist and re-render. */
    class ClipAction(
        description: String,
        private val clip: Clip,
        private val trackId: String,
        private val oldStartTime: Double,
        private val oldTrackId: String,
        private val isAdd: Boolean
    ) : Action(description) {
        override fun execute() {
            val p = ProjectManager.getCurrentProject() ?: return
            if (isAdd) {
                val t = p.getTrack(trackId)?.appendClip(clip)
                if (t != null) ProjectManager.updateCurrentProject { it.updateTrack(t) }
            } else {
                val t = p.getTrack(trackId)?.removeClip(clip.id)
                if (t != null) ProjectManager.updateCurrentProject { it.updateTrack(t) }
            }
        }

        override fun undo() {
            val p = ProjectManager.getCurrentProject() ?: return
            if (isAdd) {
                val t = p.getTrack(trackId)?.removeClip(clip.id)
                if (t != null) ProjectManager.updateCurrentProject { it.updateTrack(t) }
            } else {
                val t = p.getTrack(oldTrackId)?.appendClip(clip.with(startTime = oldStartTime, trackId = oldTrackId))
                if (t != null) ProjectManager.updateCurrentProject { it.updateTrack(t) }
            }
        }
    }

    /** Track action for add/remove/reorder. */
    class TrackAction(
        description: String,
        private val track: Track,
        private val oldIndex: Int,
        private val isAdd: Boolean
    ) : Action(description) {
        private fun insertTrack(p: AudioProject): AudioProject {
            if (p.getTrack(track.id) != null) return p
            val list = p.tracks.toMutableList()
            list.add(oldIndex.coerceIn(0, list.size), track)
            return p.copy(tracks = list)
        }

        override fun execute() {
            if (isAdd) {
                ProjectManager.updateCurrentProject { insertTrack(it) }
            } else {
                ProjectManager.updateCurrentProject { it.removeTrack(track.id) }
            }
        }

        override fun undo() {
            if (isAdd) {
                ProjectManager.updateCurrentProject { it.removeTrack(track.id) }
            } else {
                ProjectManager.updateCurrentProject { insertTrack(it) }
            }
        }
    }
}

/** Extension for easy action creation: records a property change so undo
 *  restores the previous value. The target object is expected to be a mutable
 *  holder such as a [Clip]. */
inline fun <T, V> T.recordChange(
    manager: UndoRedoManager,
    property: KMutableProperty1<T, V>,
    oldValue: V,
    description: String
) {
    val target = this
    val action = object : UndoRedoManager.Action(description) {
        override fun execute() {
            // No-op: the change was already applied by the caller.
        }

        override fun undo() {
            property.set(target, oldValue)
        }
    }
    manager.execute(action)
}
