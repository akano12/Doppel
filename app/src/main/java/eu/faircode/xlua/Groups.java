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
import android.content.res.Resources;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Presentation of hook groups: localized titles, descriptions and icons and the section a group is shown in
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
    private static final Map<String, Integer> DESCRIPTION = new HashMap<>();
    private static final Map<String, Integer> ICON = new HashMap<>();

    static {
        group("Determine.Activity", R.string.group_determine_activity, R.string.group_desc_determine_activity,
                R.drawable.ic_group_activity);
        group("Get.Applications", R.string.group_get_applications, R.string.group_desc_get_applications, R.drawable.ic_apps);
        group("Get.Calendars", R.string.group_get_calendars, R.string.group_desc_get_calendars, R.drawable.ic_group_calendars);
        group("Get.Call.log", R.string.group_get_call_log, R.string.group_desc_get_call_log, R.drawable.ic_group_call_log);
        group("Get.Contacts", R.string.group_get_contacts, R.string.group_desc_get_contacts, R.drawable.ic_group_contacts);
        group("Get.Location", R.string.group_get_location, R.string.group_desc_get_location, R.drawable.ic_group_location);
        group("Get.Messages", R.string.group_get_messages, R.string.group_desc_get_messages, R.drawable.ic_group_messages);
        group("Get.Sensors", R.string.group_get_sensors, R.string.group_desc_get_sensors, R.drawable.ic_group_sensors);
        group("Read.Account", R.string.group_read_account, R.string.group_desc_read_account, R.drawable.ic_group_account);
        group("Read.Battery", R.string.group_read_battery, R.string.group_desc_read_battery, R.drawable.ic_group_battery);
        group("Read.Biometrics", R.string.group_read_biometrics, R.string.group_desc_read_biometrics, R.drawable.ic_group_biometrics);
        group("Read.Clipboard", R.string.group_read_clipboard, R.string.group_desc_read_clipboard, R.drawable.ic_group_clipboard);
        group("Read.Debugging", R.string.group_read_debugging, R.string.group_desc_read_debugging, R.drawable.ic_group_debugging);
        group("Read.Features", R.string.group_read_features, R.string.group_desc_read_features, R.drawable.ic_group_features);
        group("Read.Identifiers", R.string.group_read_identifiers, R.string.group_desc_read_identifiers, R.drawable.ic_group_identifiers);
        group("Read.Network", R.string.group_read_network, R.string.group_desc_read_network, R.drawable.ic_group_network);
        group("Read.Notifications", R.string.group_read_notifications, R.string.group_desc_read_notifications,
                R.drawable.ic_group_notifications);
        group("Read.Resources", R.string.group_read_resources, R.string.group_desc_read_resources, R.drawable.ic_group_resources);
        group("Read.Settings", R.string.group_read_settings, R.string.group_desc_read_settings, R.drawable.ic_group_settings);
        group("Read.Sync", R.string.group_read_sync, R.string.group_desc_read_sync, R.drawable.ic_group_sync);
        group("Read.Telephony", R.string.group_read_telephony, R.string.group_desc_read_telephony, R.drawable.ic_group_telephony);
        group("Read.Timezone", R.string.group_read_timezone, R.string.group_desc_read_timezone, R.drawable.ic_group_timezone);
        group("Read.VPN", R.string.group_read_vpn, R.string.group_desc_read_vpn, R.drawable.ic_group_vpn);
        group("Record.Audio", R.string.group_record_audio, R.string.group_desc_record_audio, R.drawable.ic_group_audio);
        group("Record.Video", R.string.group_record_video, R.string.group_desc_record_video, R.drawable.ic_group_video);
        group("Send.Messages", R.string.group_send_messages, R.string.group_desc_send_messages, R.drawable.ic_group_send);
        group("Use.Analytics", R.string.group_use_analytics, R.string.group_desc_use_analytics, R.drawable.ic_group_analytics);
        group("Use.Camera", R.string.group_use_camera, R.string.group_desc_use_camera, R.drawable.ic_group_camera);
        group("Use.Tracking", R.string.group_use_tracking, R.string.group_desc_use_tracking, R.drawable.ic_group_tracking);

        put(R.string.section_location, "Get.Location", "Determine.Activity", "Get.Sensors");
        put(R.string.section_identity, "Read.Identifiers", "Read.Telephony", "Read.Network", "Read.VPN",
                "Read.Timezone", "Read.Features", "Read.Battery", "Read.Resources", "Read.Settings",
                "Read.Biometrics", "Read.Debugging", "Read.Account", "Read.Sync");
        put(R.string.section_personal, "Get.Contacts", "Get.Call.log", "Get.Messages", "Send.Messages",
                "Get.Calendars", "Get.Applications", "Read.Clipboard", "Read.Notifications");
        put(R.string.section_media, "Use.Camera", "Record.Audio", "Record.Video");
        put(R.string.section_tracking, "Use.Analytics", "Use.Tracking");
    }

    private Groups() {
    }

    private static void group(String group, int title, int description, int icon) {
        TITLE.put(group, title);
        DESCRIPTION.put(group, description);
        ICON.put(group, icon);
    }

    private static void put(int section, String... groups) {
        for (String group : groups)
            SECTION.put(group, section);
    }

    static int getSection(String group) {
        Integer section = SECTION.get(group);
        return (section == null ? R.string.section_other : section);
    }

    // What a built-in group protects, null for custom groups
    static String getDescription(Context context, String group) {
        Integer id = DESCRIPTION.get(group);
        return (id == null ? null : context.getString(id));
    }

    static int getIcon(String group) {
        Integer id = ICON.get(group);
        return (id == null ? R.drawable.ic_group_custom : id);
    }

    // Built-in groups have a translated title, custom groups are shown by name
    static String getTitle(Context context, String group) {
        return getTitle(context.getResources(), group);
    }

    // In the order of the titles shown, ignoring case and accents
    static void sortByTitle(Context context, List<String> groups) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(groups, (g1, g2) -> collator.compare(getTitle(context, g1), getTitle(context, g2)));
    }

    // The resources of the app, also in the service, which runs in another app
    static String getTitle(Resources resources, String group) {
        Integer id = TITLE.get(group);
        return (id == null ? group : resources.getString(id));
    }
}
