/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import java.util.regex.Matcher
import java.util.regex.Pattern

// Colors Lua keywords, strings, numbers, method calls and comments while editing
internal class LuaHighlighter(private val editText: EditText) {
    companion object {
        private val KEYWORD = Pattern.compile(
            "\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b"
        )
        private val NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
        private val CALL = Pattern.compile(
            "[:.]([A-Za-z_][A-Za-z0-9_]*)\\s*\\(|\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)"
        )

        // Strings and comments, in one pattern, so quotes in comments and dashes in strings are handled;
        // long brackets with any level, like --[==[ ... ]==] and [[it's]]
        private val STRING_COMMENT = Pattern.compile(
            "--\\[(=*)\\[[\\s\\S]*?(?:\\]\\1\\]|$)|--[^\\n]*|\\[(=*)\\[[\\s\\S]*?(?:\\]\\2\\]|$)|\"" +
                "(\\\\.|[^\"\\\\\\n])*\"?|'(\\\\.|[^'\\\\\\n])*'?"
        )

        private fun lineStart(text: CharSequence, index: Int): Int {
            var start = index
            while (start > 0 && text[start - 1] != '\n')
                start--
            return start
        }

        private fun lineEnd(text: CharSequence, index: Int): Int {
            var end = index
            while (end < text.length && text[end] != '\n')
                end++
            return end
        }

        // Word boundaries look past the lines
        private fun matcher(pattern: Pattern, text: CharSequence, start: Int, end: Int): Matcher =
            pattern.matcher(text).region(start, end).useTransparentBounds(true).useAnchoringBounds(false)

        private fun color(text: Editable, pattern: Pattern, color: Int, start: Int, end: Int) {
            val matcher = matcher(pattern, text, start, end)
            while (matcher.find())
                set(text, matcher.start(), matcher.end(), color)
        }

        private fun set(text: Editable, start: Int, end: Int, color: Int) {
            if (start < end)
                text.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val colorKeyword: Int
    private val colorString: Int
    private val colorNumber: Int
    private val colorFunction: Int
    private val colorComment: Int

    // The changed text not colored yet: its start, and its end as the length after it, which stays right
    // when text before it changes; -1 when nothing changed
    private var changedStart = -1
    private var changedAfter = 0

    private val highlight = Runnable { highlightChanged() }

    init {
        val context = editText.context
        colorKeyword = context.getColor(R.color.c_code_keyword)
        colorString = context.getColor(R.color.c_code_string)
        colorNumber = context.getColor(R.color.c_code_number)
        colorFunction = context.getColor(R.color.c_code_function)
        colorComment = context.getColor(R.color.c_code_comment)
        // Highlighting while typing would be slow for long scripts
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val after = s.length - (start + count)
                if (changedStart < 0) {
                    changedStart = start
                    changedAfter = after
                } else {
                    changedStart = minOf(changedStart, start)
                    changedAfter = minOf(changedAfter, after)
                }
            }

            override fun afterTextChanged(s: Editable) {
                handler.removeCallbacks(highlight)
                handler.postDelayed(highlight, 250)
            }
        })
    }

    // All text, like after filling in a script
    fun highlight() {
        changedStart = -1
        highlight(0, editText.text.length)
    }

    // Recoloring all of a long script after each pause in typing takes too long
    private fun highlightChanged() {
        if (changedStart < 0)
            return
        val text = editText.text
        val length = text.length
        val start = minOf(changedStart, length)
        val end = maxOf(start, length - changedAfter)
        changedStart = -1
        highlight(start, end)
    }

    // The lines from start to end, and the strings and comments these are part of or were before the change
    private fun highlight(startIndex: Int, endIndex: Int) {
        val text = editText.text
        var start = lineStart(text, startIndex)
        var end = lineEnd(text, endIndex)
        for (span in text.getSpans(start, end, ForegroundColorSpan::class.java)) {
            start = minOf(start, text.getSpanStart(span))
            end = maxOf(end, text.getSpanEnd(span))
        }
        var matcher = STRING_COMMENT.matcher(text)
        while (matcher.find() && matcher.start() < end)
            if (matcher.end() > start) {
                start = minOf(start, matcher.start())
                end = maxOf(end, matcher.end())
            }
        start = lineStart(text, start)
        end = lineEnd(text, end)

        for (span in text.getSpans(start, end, ForegroundColorSpan::class.java))
            text.removeSpan(span)

        color(text, KEYWORD, colorKeyword, start, end)
        color(text, NUMBER, colorNumber, start, end)
        val call = matcher(CALL, text, start, end)
        while (call.find()) {
            val group = if (call.group(1) == null) 2 else 1
            set(text, call.start(group), call.end(group), colorFunction)
        }

        // Last, so these replace the colors of words inside strings and comments; from the start of the text,
        // since a quote or bracket before the lines can open a string
        matcher = STRING_COMMENT.matcher(text)
        while (matcher.find() && matcher.start() < end) {
            if (matcher.end() <= start)
                continue
            val s = matcher.start()
            val e = matcher.end()
            for (span in text.getSpans(s, e, ForegroundColorSpan::class.java))
                if (text.getSpanStart(span) >= s && text.getSpanEnd(span) <= e)
                    text.removeSpan(span)
            set(text, s, e, if (text[s] == '-') colorComment else colorString)
        }
    }

    fun stop() {
        handler.removeCallbacks(highlight)
    }
}
