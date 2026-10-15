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
    private static final Map<String, Integer> TITLE = new HashMap<>();
    private static final Set<String> VALUES = new HashSet<>(Arrays.asList(
            "Get.Location", "Get.Contacts", "Read.Account", "Read.Features",
            "Read.Identifiers", "Read.Telephony", "Read.Timezone"));

    static {
        TITLE.put("Determine.Activity", R.string.group_determine_activity);
        TITLE.put("Get.Applications", R.string.group_get_applications);
        TITLE.put("Get.Calendars", R.string.group_get_calendars);
        TITLE.put("Get.Call.log", R.string.group_get_call_log);
        TITLE.put("Get.Contacts", R.string.group_get_contacts);
        TITLE.put("Get.Location", R.string.group_get_location);
        TITLE.put("Get.Messages", R.string.group_get_messages);
        TITLE.put("Get.Sensors", R.string.group_get_sensors);
        TITLE.put("Read.Account", R.string.group_read_account);
        TITLE.put("Read.Clipboard", R.string.group_read_clipboard);
        TITLE.put("Read.Features", R.string.group_read_features);
        TITLE.put("Read.Identifiers", R.string.group_read_identifiers);
        TITLE.put("Read.Network", R.string.group_read_network);
        TITLE.put("Read.Notifications", R.string.group_read_notifications);
        TITLE.put("Read.Sync", R.string.group_read_sync);
        TITLE.put("Read.Telephony", R.string.group_read_telephony);
        TITLE.put("Read.Timezone", R.string.group_read_timezone);
        TITLE.put("Read.VPN", R.string.group_read_vpn);
        TITLE.put("Record.Audio", R.string.group_record_audio);
        TITLE.put("Record.Video", R.string.group_record_video);
        TITLE.put("Send.Messages", R.string.group_send_messages);
        TITLE.put("Use.Analytics", R.string.group_use_analytics);
        TITLE.put("Use.Camera", R.string.group_use_camera);
        TITLE.put("Use.Tracking", R.string.group_use_tracking);

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
        return getTitle(context.getResources(), group);
    }

    // The resources of the app, also in the service, which runs in another app
    static String getTitle(Resources resources, String group) {
        Integer id = TITLE.get(group);
        return (id == null ? group : resources.getString(id));
    }
}
