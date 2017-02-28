package org.wordpress.aztec

import android.text.Spannable
import android.text.Spanned
import org.wordpress.aztec.spans.AztecListItemSpan
import org.wordpress.aztec.spans.AztecListSpan

class ListHandler(private val text: Spannable) {
    interface TextDeleter {
        fun delete(start: Int, end: Int)
    }

    private enum class PositionType {
        LIST_START,
        EMPTY_ITEM_AT_LIST_END,
        TEXT_END,
        LIST_ITEM_BODY
    }

    private fun newListItem(start: Int, end: Int) {
        newListItem(text, start, end)
    }

    fun handleTextChangeForLists(text: Spannable, inputStart: Int, count: Int, textDeleter: TextDeleter) {
        // use charsNew to get the spans at the input point. It appears to be more reliable vs the whole Editable.
        var charsNew = text.subSequence(inputStart, inputStart + count) as Spanned

        val lists = charsNew.getSpans<AztecListSpan>(0, 0, AztecListSpan::class.java)
        if (lists == null || lists.isEmpty()) {
            // no lists so, bail.
            return
        }

        val list = SpanWrapper(text, lists[0]) // TODO: handle nesting

        val charsNewString = charsNew.toString()
        var newlineOffset = charsNewString.indexOf(Constants.NEWLINE)
        while (newlineOffset > -1 && newlineOffset < charsNew.length) {
            val newlineIndex = inputStart + newlineOffset

            // re-subsequence to get the newer state of the spans
            charsNew = text.subSequence(inputStart, inputStart + count) as Spanned
            val listItems = SpanWrapper.getSpans(text,
                    charsNew.getSpans<AztecListItemSpan>(newlineOffset, newlineOffset, AztecListItemSpan::class.java))
            listItems.firstOrNull()?.let {
                when (getNewlinePositionType(text, list, it, newlineIndex)) {
                    ListHandler.PositionType.LIST_START -> handleNewlineAtListStart(it, newlineIndex)
                    ListHandler.PositionType.EMPTY_ITEM_AT_LIST_END -> handleNewlineAtEmptyItemAtListEnd(list, it, newlineIndex, textDeleter)
                    ListHandler.PositionType.TEXT_END -> handleNewlineAtTextEnd()
                    ListHandler.PositionType.LIST_ITEM_BODY -> handleNewlineInListItemBody(it, newlineIndex)
                }
            }

            newlineOffset = charsNewString.indexOf(Constants.NEWLINE, newlineOffset + 1)
        }

        val gotEndOfBufferMarker = charsNew.length == 1 && charsNew[0] == Constants.END_OF_BUFFER_MARKER
        if (gotEndOfBufferMarker) {
            handleEndOfBufferInList(text, inputStart)
        }
    }

    private fun getNewlinePositionType(text: Spannable, list: SpanWrapper<AztecListSpan>,
                                       item: SpanWrapper<AztecListItemSpan>, newlineIndex: Int): PositionType {
        val atEndOfList = newlineIndex == list.end - 2 || newlineIndex == text.length - 1

        if (newlineIndex == item.start && !atEndOfList) {
            return PositionType.LIST_START
        }

        if (newlineIndex == item.start && atEndOfList) {
            return PositionType.EMPTY_ITEM_AT_LIST_END
        }

        if (newlineIndex == text.length - 1) {
            return PositionType.TEXT_END
        }

        // no special case applied so, newline is in the "body" of the bullet
        return PositionType.LIST_ITEM_BODY
    }

    private fun handleNewlineAtListStart(item: SpanWrapper<AztecListItemSpan>, newlineIndex: Int) {
        // newline added at start of bullet so, add a new bullet
        newListItem(newlineIndex, newlineIndex + 1)

        // push current bullet forward
        item.start = newlineIndex + 1
    }

    private fun handleNewlineAtEmptyItemAtListEnd(list: SpanWrapper<AztecListSpan>, item: SpanWrapper<AztecListItemSpan>,
                                                  newlineIndex: Int, textDeleter: TextDeleter) {
        // close the list when entering a newline on an empty item at the end of the list
        item.remove()

        if (list.end - list.start === 1) {
            // list only has the empty list item so, remove the list itself as well!
            list.remove()
        } else {
            // adjust the list end to only include the chars before the newline just added
            list.end = newlineIndex
        }

        // delete the newline
        textDeleter.delete(newlineIndex, newlineIndex + 1)
    }

    private fun handleNewlineAtTextEnd() {
        // got a newline while being at the end-of-text. We'll let the current list item engulf it and will wait
        //  for the end-of-text marker event in order to attach the new list item to it when that happens.

        // no-op here
    }

    private fun handleNewlineInListItemBody(item: SpanWrapper<AztecListItemSpan>, newlineIndex: Int) {
        // newline added at some position inside the bullet so, end the current bullet and append a new one
        newListItem(newlineIndex + 1, item.end)
        item.end = newlineIndex + 1
    }

    private fun handleEndOfBufferInList(text: Spannable, markerIndex: Int): Boolean {
        val listItems = text.getSpans<AztecListItemSpan>(markerIndex, markerIndex + 1, AztecListItemSpan::class.java)
        val item = if (listItems != null && listItems.isNotEmpty()) SpanWrapper(text, listItems[0]) else null

        if (item!!.start === markerIndex) {
            // ok, this list item has the marker as its first char so, nothing more to do. Bail.
            return false
        }

        // attach a new bullet around the end-of-text marker
        newListItem(markerIndex, markerIndex + 1)

        // the list item has bled over to the marker so, let's adjust its range to just before the marker. There's a
        //  newline there hopefully :)
        item!!.end = markerIndex

        return true
    }

    companion object {
        fun newList(text: Spannable, list: AztecListSpan, start: Int, end: Int) {
            text.setSpan(list, start, end, Spanned.SPAN_PARAGRAPH)
        }

        fun newListItem(text: Spannable, start: Int, end: Int) {
            text.setSpan(AztecListItemSpan(), start, end, Spanned.SPAN_PARAGRAPH)
        }
    }
}