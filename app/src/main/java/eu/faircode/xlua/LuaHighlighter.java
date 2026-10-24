/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

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

import androidx.core.content.ContextCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Colors Lua keywords, strings, numbers, method calls and comments while editing
class LuaHighlighter implements TextWatcher {
    private static final Pattern KEYWORD = Pattern.compile(
            "\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern CALL = Pattern.compile("[:.]([A-Za-z_][A-Za-z0-9_]*)\\s*\\(|\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)");
    // Strings and comments, in one pattern, so quotes in comments and dashes in strings are handled
    private static final Pattern STRING_COMMENT = Pattern.compile(
            "--\\[\\[[\\s\\S]*?(\\]\\]|$)|--[^\\n]*|\"(\\\\.|[^\"\\\\\\n])*\"?|'(\\\\.|[^'\\\\\\n])*'?");

    private final EditText editText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int colorKeyword;
    private final int colorString;
    private final int colorNumber;
    private final int colorFunction;
    private final int colorComment;

    private final Runnable highlight = this::highlight;

    LuaHighlighter(EditText editText) {
        this.editText = editText;
        Context context = editText.getContext();
        colorKeyword = ContextCompat.getColor(context, R.color.c_code_keyword);
        colorString = ContextCompat.getColor(context, R.color.c_code_string);
        colorNumber = ContextCompat.getColor(context, R.color.c_code_number);
        colorFunction = ContextCompat.getColor(context, R.color.c_code_function);
        colorComment = ContextCompat.getColor(context, R.color.c_code_comment);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    // Highlighting while typing would be slow for long scripts
    @Override
    public void afterTextChanged(Editable s) {
        handler.removeCallbacks(highlight);
        handler.postDelayed(highlight, 250);
    }

    void highlight() {
        Editable text = editText.getText();
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class))
            text.removeSpan(span);

        color(text, KEYWORD, colorKeyword);
        color(text, NUMBER, colorNumber);
        Matcher call = CALL.matcher(text);
        while (call.find()) {
            int group = (call.group(1) == null ? 2 : 1);
            set(text, call.start(group), call.end(group), colorFunction);
        }

        // Last, so these replace the colors of words inside strings and comments
        Matcher m = STRING_COMMENT.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            for (ForegroundColorSpan span : text.getSpans(start, end, ForegroundColorSpan.class))
                if (text.getSpanStart(span) >= start && text.getSpanEnd(span) <= end)
                    text.removeSpan(span);
            set(text, start, end, text.charAt(start) == '-' ? colorComment : colorString);
        }
    }

    private static void color(Editable text, Pattern pattern, int color) {
        Matcher m = pattern.matcher(text);
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
