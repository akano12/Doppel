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
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.res.Resources;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Presentation of hook groups: localized titles, the section a group is shown in
// and whether the scripts of a group use fake values
final class Groups {
    // Sections in display order, groups of custom definitions go into the last one
    static final List<Integer> SECTIONS = Arrays.asList(
            R.string.section_location,
            R.string.section_identity,
            R.string.section_personal,
            R.string.section_media,
            R.string.section_tracking,
            R.string.section_other);

    private static final Map<String, Integer> SECTION = new HashMap<>();
    private static final Set<String> VALUES = new HashSet<>(Arrays.asList(
            "Get.Location", "Get.Contacts", "Read.Account", "Read.Features",
            "Read.Identifiers", "Read.Telephony", "Read.Timezone"));

    static {
        put(R.string.section_location, "Get.Location", "Determine.Activity", "Get.Sensors");
        put(R.string.section_identity, "Read.Identifiers", "Read.Telephony", "Read.Network", "Read.VPN",
                "Read.Timezone", "Read.Features", "Read.Account", "Read.Sync");
        put(R.string.section_personal, "Get.Contacts", "Get.Call.log", "Get.Messages", "Send.Messages",
                "Get.Calendars", "Get.Applications", "Read.Clipboard", "Read.Notifications");
        put(R.string.section_media, "Use.Camera", "Record.Audio", "Record.Video");
        put(R.string.section_tracking, "Use.Analytics", "Use.Tracking");
    }

    private Groups() {
    }

    private static void put(int section, String... groups) {
        for (String group : groups)
            SECTION.put(group, section);
    }

    static int getSection(String group) {
        Integer section = SECTION.get(group);
        return (section == null ? R.string.section_other : section);
    }

    // Fake values on the values screen apply to this group
    static boolean hasValues(String group) {
        return VALUES.contains(group);
    }

    // Built-in groups have a translated title, custom groups are shown by name
    static String getTitle(Context context, String group) {
        Resources resources = context.getResources();
        String name = group.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "_");
        int id = resources.getIdentifier("group_" + name, "string", context.getPackageName());
        return (id > 0 ? resources.getString(id) : group);
    }
}
