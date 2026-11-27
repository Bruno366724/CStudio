package com.example.cstudio.drawing

// Guarda copias acotadas para deshacer y rehacer cambios del lienzo.
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import java.util.ArrayDeque

internal class History(private val bitmap: Bitmap) {
    private data class HistoryEntry(
        val target: Bitmap,
        val bounds: Rect,
        val before: Bitmap,
        var after: Bitmap?,
        val clearsBounds: Boolean = false
    ) {
        val byteSize: Long
            get() = before.byteCount.toLong() + (after?.byteCount?.toLong() ?: 0L)

        fun recycle() {
            before.recycle()
            after?.recycle()
        }
    }

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var historyBytes = 0L
    internal var lastChangedTarget: Bitmap? = null
        private set

    internal val trackedBytes: Long
        get() = historyBytes

    internal val liveEntryBytesForTesting: Long
        get() = (undoStack.asSequence() + redoStack.asSequence()).sumOf { it.byteSize }

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun capture(bounds: Rect, target: Bitmap = bitmap): Bitmap {
        return Bitmap.createBitmap(target, bounds.left, bounds.top, bounds.width(), bounds.height())
    }

    fun commit(bounds: Rect, before: Bitmap, target: Bitmap = bitmap) {
        pushUndo(HistoryEntry(target, Rect(bounds), before, null))
    }

    fun clear(target: Bitmap = bitmap) {
        val bounds = Rect(0, 0, target.width, target.height)
        val before = capture(bounds, target)
        target.eraseColor(Color.TRANSPARENT)
        pushUndo(HistoryEntry(target, bounds, before, null, clearsBounds = true))
    }

    fun undo(): Boolean {
        val entry = undoStack.peekLast() ?: return false
        if (entry.after == null) {
            val after = try {
                capture(entry.bounds, entry.target)
            } catch (error: OutOfMemoryError) {
                return false
            }
            entry.after = after
            historyBytes += after.byteCount.toLong()
        }
        restoreRegion(entry.target, entry.bounds, entry.before)
        undoStack.removeLast()
        redoStack.addLast(entry)
        lastChangedTarget = entry.target
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.pollLast() ?: return false
        if (entry.clearsBounds) {
            clearRegion(entry.target, entry.bounds)
        } else {
            restoreRegion(entry.target, entry.bounds, checkNotNull(entry.after))
        }
        undoStack.addLast(entry)
        lastChangedTarget = entry.target
        return true
    }

    fun removeEntriesFor(target: Bitmap) {
        removeEntries(undoStack, target)
        removeEntries(redoStack, target)
    }

    fun reset() {
        undoStack.forEach(HistoryEntry::recycle)
        redoStack.forEach(HistoryEntry::recycle)
        undoStack.clear()
        redoStack.clear()
        historyBytes = 0L
        lastChangedTarget = null
    }

    private fun restoreRegion(target: Bitmap, bounds: Rect, patch: Bitmap) {
        val canvas = Canvas(target)
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(patch, bounds.left.toFloat(), bounds.top.toFloat(), null)
        canvas.restoreToCount(save)
    }

    private fun clearRegion(target: Bitmap, bounds: Rect) {
        val canvas = Canvas(target)
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.restoreToCount(save)
    }

    private fun pushUndo(entry: HistoryEntry) {
        clearRedoHistory()
        undoStack.addLast(entry)
        historyBytes += entry.byteSize
        trimHistory(entry)
    }

    private fun trimHistory(newest: HistoryEntry) {
        while (
            undoStack.size + redoStack.size > HISTORY_LIMIT ||
            (historyBytes > HISTORY_MEMORY_LIMIT && undoStack.size > 1)
        ) {
            val oldest = undoStack.pollFirst() ?: redoStack.pollFirst() ?: break
            if (oldest === newest) {
                undoStack.addFirst(oldest)
                break
            }
            historyBytes -= oldest.byteSize
            oldest.recycle()
        }
    }

    private fun clearRedoHistory() {
        redoStack.forEach {
            historyBytes -= it.byteSize
            it.recycle()
        }
        redoStack.clear()
    }

    private fun removeEntries(stack: ArrayDeque<HistoryEntry>, target: Bitmap) {
        val iterator = stack.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.target === target) {
                historyBytes -= entry.byteSize
                entry.recycle()
                iterator.remove()
            }
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 20
        const val HISTORY_MEMORY_LIMIT = 64L * 1024L * 1024L
    }
}
