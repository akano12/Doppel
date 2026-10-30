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

package eu.faircode.xlua;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Colors Lua keywords, strings, numbers, method calls and comments while editing
class LuaHighlighter {
    private static final Pattern KEYWORD = Pattern.compile(
            "\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern CALL = Pattern.compile("[:.]([A-Za-z_][A-Za-z0-9_]*)\\s*\\(|\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)");
    // Strings and comments, in one pattern, so quotes in comments and dashes in strings are handled;
    // long brackets with any level, like --[==[ ... ]==] and [[it's]]
    private static final Pattern STRING_COMMENT = Pattern.compile(
            "--\\[(=*)\\[[\\s\\S]*?(?:\\]\\1\\]|$)|--[^\\n]*|\\[(=*)\\[[\\s\\S]*?(?:\\]\\2\\]|$)|" +
                    "\"(\\\\.|[^\"\\\\\\n])*\"?|'(\\\\.|[^'\\\\\\n])*'?");

    private final EditText editText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int colorKeyword;
    private final int colorString;
    private final int colorNumber;
    private final int colorFunction;
    private final int colorComment;

    // The changed text not colored yet: its start, and its end as the length after it, which stays right
    // when text before it changes; -1 when nothing changed
    private int changedStart = -1;
    private int changedAfter;

    private final Runnable highlight = this::highlightChanged;

    LuaHighlighter(EditText editText) {
        this.editText = editText;
        Context context = editText.getContext();
        colorKeyword = context.getColor(R.color.c_code_keyword);
        colorString = context.getColor(R.color.c_code_string);
        colorNumber = context.getColor(R.color.c_code_number);
        colorFunction = context.getColor(R.color.c_code_function);
        colorComment = context.getColor(R.color.c_code_comment);
        // Highlighting while typing would be slow for long scripts
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int after = s.length() - (start + count);
                if (changedStart < 0) {
                    changedStart = start;
                    changedAfter = after;
                } else {
                    changedStart = Math.min(changedStart, start);
                    changedAfter = Math.min(changedAfter, after);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(highlight);
                handler.postDelayed(highlight, 250);
            }
        });
    }

    // All text, like after filling in a script
    void highlight() {
        changedStart = -1;
        highlight(0, editText.getText().length());
    }

    // Recoloring all of a long script, like shell_commands, after each pause in typing takes about a second
    private void highlightChanged() {
        if (changedStart < 0)
            return;
        Editable text = editText.getText();
        int length = text.length();
        int start = Math.min(changedStart, length);
        int end = Math.max(start, length - changedAfter);
        changedStart = -1;
        highlight(start, end);
    }

    // The lines from start to end, and the strings and comments these are part of or were before the change
    private void highlight(int start, int end) {
        Editable text = editText.getText();
        start = lineStart(text, start);
        end = lineEnd(text, end);
        for (ForegroundColorSpan span : text.getSpans(start, end, ForegroundColorSpan.class)) {
            start = Math.min(start, text.getSpanStart(span));
            end = Math.max(end, text.getSpanEnd(span));
        }
        Matcher m = STRING_COMMENT.matcher(text);
        while (m.find() && m.start() < end)
            if (m.end() > start) {
                start = Math.min(start, m.start());
                end = Math.max(end, m.end());
            }
        start = lineStart(text, start);
        end = lineEnd(text, end);

        for (ForegroundColorSpan span : text.getSpans(start, end, ForegroundColorSpan.class))
            text.removeSpan(span);

        color(text, KEYWORD, colorKeyword, start, end);
        color(text, NUMBER, colorNumber, start, end);
        Matcher call = matcher(CALL, text, start, end);
        while (call.find()) {
            int group = (call.group(1) == null ? 2 : 1);
            set(text, call.start(group), call.end(group), colorFunction);
        }

        // Last, so these replace the colors of words inside strings and comments; from the start of the text,
        // since a quote or bracket before the lines can open a string
        m = STRING_COMMENT.matcher(text);
        while (m.find() && m.start() < end) {
            if (m.end() <= start)
                continue;
            int s = m.start();
            int e = m.end();
            for (ForegroundColorSpan span : text.getSpans(s, e, ForegroundColorSpan.class))
                if (text.getSpanStart(span) >= s && text.getSpanEnd(span) <= e)
                    text.removeSpan(span);
            set(text, s, e, text.charAt(s) == '-' ? colorComment : colorString);
        }
    }

    private static int lineStart(CharSequence text, int index) {
        while (index > 0 && text.charAt(index - 1) != '\n')
            index--;
        return index;
    }

    private static int lineEnd(CharSequence text, int index) {
        while (index < text.length() && text.charAt(index) != '\n')
            index++;
        return index;
    }

    // Word boundaries look past the lines
    private static Matcher matcher(Pattern pattern, CharSequence text, int start, int end) {
        return pattern.matcher(text).region(start, end).useTransparentBounds(true).useAnchoringBounds(false);
    }

    private static void color(Editable text, Pattern pattern, int color, int start, int end) {
        Matcher m = matcher(pattern, text, start, end);
        while (m.find())
            set(text, m.start(), m.end(), color);
    }

    private static void set(Editable text, int start, int end, int color) {
        if (start < end)
            text.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    void stop() {
        handler.removeCallbacks(highlight);
    }
}
