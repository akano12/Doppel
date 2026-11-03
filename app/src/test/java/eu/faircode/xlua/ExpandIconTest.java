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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

// The expand arrow is turned around when a row or group is expanded, so its glyph has to be centered in the icon,
// else it is off-center in its badge in one of the two states; optically: the center of the outline of the chevron
// (wide at the top, a point at the bottom) is 35 of 960 above the middle of its box, which is what the eye centers
public class ExpandIconTest {
    private static final float OPTICAL = 35;

    @Test
    public void glyphIsCentered() throws Exception {
        String xml = new String(Files.readAllBytes(new File("src/main/res/drawable/ic_expand.xml").toPath()),
                StandardCharsets.UTF_8);
        float size = number(xml, "android:viewportHeight=\"([^\"]+)\"");
        assertEquals(size, number(xml, "android:viewportWidth=\"([^\"]+)\""), 0);
        float dy = number(xml, "android:translateY=\"([^\"]+)\"");
        float[] box = bounds(match(xml, "android:pathData=\"([^\"]+)\""));
        assertEquals(size / 2, (box[0] + box[2]) / 2, 0.5);
        assertEquals(size / 2, (box[1] + box[3]) / 2 - OPTICAL + dy, 0.5);
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find())
            throw new AssertionError(regex);
        return m.group(1);
    }

    private static float number(String text, String regex) {
        return Float.parseFloat(match(text, regex));
    }

    // The box of the points of a path of M, L, H, V, Q, T and Z commands, absolute and relative;
    // shortcut: control points count as points, which is exact only while they lie within the glyph, as in ic_expand
    private static float[] bounds(String path) {
        Matcher m = Pattern.compile("[A-Za-z]|-?(\\d+\\.?\\d*|\\.\\d+)").matcher(path);
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 0;
        List<Float> args = new ArrayList<>();
        while (m.find()) {
            String token = m.group();
            if (Character.isLetter(token.charAt(0))) {
                cmd = token.charAt(0);
                args.clear();
                if (cmd == 'Z' || cmd == 'z') {
                    x = sx;
                    y = sy;
                }
                continue;
            }
            args.add(Float.parseFloat(token));
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            int n = (c == 'H' || c == 'V' ? 1 : c == 'Q' ? 4 : 2);
            if (args.size() < n)
                continue;
            float bx = (rel ? x : 0), by = (rel ? y : 0);
            for (int i = 0; i + 1 < n; i += 2)
                add(box, bx + args.get(i), by + args.get(i + 1));
            if (c == 'H')
                x = bx + args.get(0);
            else if (c == 'V')
                y = by + args.get(0);
            else {
                x = bx + args.get(n - 2);
                y = by + args.get(n - 1);
            }
            add(box, x, y);
            if (c == 'M') {
                sx = x;
                sy = y;
            }
            args.clear();
        }
        return box;
    }

    private static void add(float[] box, float x, float y) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x);
        box[3] = Math.max(box[3], y);
    }
}
