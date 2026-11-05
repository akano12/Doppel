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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The action of a snackbar has the primary of the other theme, a copy in each theme, which a new primary has to change
public class PalettesTest {
    @Test
    public void inversePrimaryIsThePrimaryOfTheOtherTheme() throws Exception {
        String light = read("values/palettes.xml");
        String dark = read("values-night/palettes.xml");
        for (String palette : new String[]{"Berry", "Sky", "Teal"}) {
            assertEquals(palette, item(dark, palette, "colorPrimary"), item(light, palette, "colorPrimaryInverse"));
            assertEquals(palette, item(light, palette, "colorPrimary"), item(dark, palette, "colorPrimaryInverse"));
        }

        String lightColors = read("values/colors.xml");
        String darkColors = read("values-night/colors.xml");
        assertEquals(color(darkColors, "c_primary"), color(lightColors, "c_primary_inverse"));
        assertEquals(color(lightColors, "c_primary"), color(darkColors, "c_primary_inverse"));
    }

    private static String item(String xml, String palette, String name) {
        String style = xml.substring(xml.indexOf("ThemeOverlay.XLua." + palette + "\""));
        return match(style.substring(0, style.indexOf("</style>")), "<item name=\"" + name + "\">(#\\w+)<");
    }

    private static String color(String xml, String name) {
        return match(xml, "<color name=\"" + name + "\">(#\\w+)<");
    }

    private static String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(regex, matcher.find());
        return matcher.group(1);
    }

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/res/" + name).toPath()), StandardCharsets.UTF_8);
    }
}
