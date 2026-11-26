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

    private val canvas = Canvas(bitmap)
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var historyBytes = 0L

    fun capture(bounds: Rect): Bitmap {
        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
    }

    fun commit(bounds: Rect, before: Bitmap) {
        pushUndo(HistoryEntry(bounds, before, null))
    }

    fun clear() {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        val before = capture(bounds)
        bitmap.eraseColor(Color.TRANSPARENT)
        pushUndo(HistoryEntry(bounds, before, null, clearsBounds = true))
    }

    fun undo(): Boolean {
        val entry = undoStack.pollLast() ?: return false
        entry.after?.recycle()
        entry.after = null
        historyBytes -= entry.byteSize
        try {
            val after = capture(entry.bounds)
            entry.after = after
            historyBytes += after.byteCount.toLong()
        } catch (error: OutOfMemoryError) {
            undoStack.addLast(entry)
            return false
        }
        restoreRegion(entry.bounds, entry.before)
        redoStack.addLast(entry)
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.pollLast() ?: return false
        if (entry.clearsBounds) {
            clearRegion(entry.bounds)
        } else {
            restoreRegion(entry.bounds, checkNotNull(entry.after))
        }
        undoStack.addLast(entry)
        return true
    }

    fun reset() {
        undoStack.forEach(HistoryEntry::recycle)
        redoStack.forEach(HistoryEntry::recycle)
        undoStack.clear()
        redoStack.clear()
        historyBytes = 0L
    }

    private fun restoreRegion(bounds: Rect, patch: Bitmap) {
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(patch, bounds.left.toFloat(), bounds.top.toFloat(), null)
        canvas.restoreToCount(save)
    }

    private fun clearRegion(bounds: Rect) {
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

    private companion object {
        const val HISTORY_LIMIT = 20
        const val HISTORY_MEMORY_LIMIT = 64L * 1024L * 1024L
    }
}
