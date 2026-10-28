<p align="center">
  <img src="fastlane/metadata/android/en-US/images/featureGraphic.png" alt="Doppel: fake data for apps, real privacy for you, next to a phone showing the Apps tab">
</p>

<p align="center">
  <a href="https://github.com/akano12/Doppel/releases"><img src="https://img.shields.io/github/v/release/akano12/Doppel?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-6.0%2B-3DDC84" alt="Android 6.0 and later">
  <img src="https://img.shields.io/badge/Xposed-LSPosed%20%7C%20Xposed-6E6E73" alt="Xposed module for LSPosed or Xposed">
  <a href="#license"><img src="https://img.shields.io/badge/license-GPLv3-blue" alt="License GPL version 3"></a>
  <a href="https://xdaforums.com/t/module-6-0-lsposed-doppel-fake-data-for-apps-maintained-continuation-of-xprivacylua.4806280/"><img src="https://img.shields.io/badge/XDA-Thread-EA7100?logo=xdadevelopers&logoColor=white" alt="XDA Forums thread"></a>
</p>

<p align="center">
  <a href="#screenshots">Screenshots</a> ·
  <a href="#features">Features</a> ·
  <a href="#installation">Installation</a> ·
  <a href="#restrictions">Restrictions</a> ·
  <a href="FAQ.md">FAQ</a> ·
  <a href="#building">Building</a>
</p>

Doppel is a simple privacy manager for Android 6 and later.
Revoking permissions often makes apps crash; Doppel feeds them fake data instead of real data, so they keep working.

> Doppel continues [XPrivacyLua](https://github.com/M66B/XPrivacyLua) by Marcel Bokhorst (M66B), which is not maintained anymore,
> with a new interface, updates for current Android versions and new restrictions, under the same license, the [GPL version 3](#license).

A doppelgänger is a person's ghostly twin: it looks like you, answers like you, and isn't you.
When an app asks for your location, contacts or device ID, your double answers in your place:
the answers look real, but the app learns nothing true about you.

Screenshots
-----------

<table>
  <tr><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="Apps, with the actions of an app swiped open"><br><sub>Apps</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="The screen of an app"><br><sub>An app</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="Fake values"><br><sub>Fake values</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="Activity"><br><sub>Activity</sub></td></tr>
  <tr><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="180" alt="Hooks"><br><sub>Hooks</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="180" alt="Downloads"><br><sub>Downloads</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width="180" alt="Settings"><br><sub>Settings</sub></td><td align="center"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.png" width="180" alt="The groups of an app in the dark theme"><br><sub>Dark theme</sub></td></tr>
</table>

Features
--------

* **Apps**: restrict any user or system app by group or by single hook; swipe an app for Restrict all,
  Remove all and its groups, with Undo
* **Fake values** for all apps or per app, like the IMEI, Android ID, location and time zone;
  the identifiers can be new each boot, a different one for each app, so apps cannot follow you across apps or restarts
* **Activity**: what each app tried, with the real and the fake value, and hook errors
* **Hooks**: view, edit, import and export hook definitions, [add your own](docs/DEFINE.md)
  and install definitions shared by others with *Downloads*, like a domain blocklist
* **Settings**: backup and restore, restricting new apps, collections, theme, accent color, pure black background and app icon
* Multi-user support, free and open source

Installation
------------

Doppel needs Android 6.0 or later and a rooted device with an Xposed framework.
It uses the [modern Xposed API](https://github.com/libxposed/api) when the framework has it, else the legacy API.

1. Install [Magisk](https://www.xda-developers.com/how-to-install-magisk/)
2. Install and activate an Xposed framework:
   [LSPosed](https://github.com/LSPosed/LSPosed) or a compatible fork on Android 8.1 and later,
   [Xposed](http://forum.xda-developers.com/xposed) on Android 6 and 7
3. Install Doppel from the [releases](https://github.com/akano12/Doppel/releases) and activate it
4. Reboot

In LSPosed, the scope needs *System Framework*, which hosts the Doppel service, and each app you want to restrict;
with the legacy API (like EdXposed) also *Settings Storage*.
Add only apps you restrict: LSPosed is loaded into every app in the scope.

Uninstall XPrivacyLua first: both use the same service and data.
A build signed with another key than the official release cannot update it; uninstall the official release first.

Restrictions
------------

A restriction hides data (an empty list) or fakes it (an empty or fake value).
The faked values are the same for the shell commands apps run, like getprop, pm and cat.

* **Determine activity**: fake unknown activity, no activity transitions
* **Get applications**: hide other apps, their components, widgets and usage statistics; hidden apps cannot be reached
* **Get calendars**, **Get call log**, **Get messages** (SMS, MMS, SIM, voicemail), **Get sensors**: hide them
* **Get contacts**: hide contacts, optionally except (non) starred ones, and blocked numbers
* **Get location**: fake location, no NMEA messages or raw GNSS measurements
* **Read account name**: fake name
* **Read battery**: level in steps of 10 percent, at least 15, no charge details, battery saver off
* **Read biometric sensors**: no fingerprint sensor, face or iris unlock
* **Read clipboard**: fake paste
* **Read debugging state**: developer options and USB or wireless debugging off
* **Read device features**: only the features in *features.keep*, no external keyboards and game controllers
* **Read device settings**: the values of a new device, like the animation scale and the screen timeout
* **Read identifiers**: fake serial, Android ID, advertising and app set ID, MAC addresses;
  the permanent ids of the chip, SD card and storage in /sys do not exist
* **Read memory and storage**: sizes rounded like devices are sold, free space rounded down
* **Read network data**: hide cells, Wi-Fi networks, Bluetooth devices and local network discovery, fake Wi-Fi name
* **Read notifications**: hide the notifications of other apps
* **Read sync data**: hide sync data
* **Read telephony data**: fake IMEI, MEID, SIM serial, phone number and more
* **Read time zone**: fake time zone, *value.timezone*, default UTC
* **Read VPN state**: no VPN
* **Record audio**, **Record video**: prevent recording, also speech recognition
* **Run commands**: commands fail, except the programs in *commands.allowed* (like `getprop,uname`)
  and the command lines matching *commands.regex*
* **Send messages**: prevent sending SMS, MMS and data messages
* **Use analytics**: Adjust, AppsFlyer, Facebook app events, Firebase Analytics and Crashlytics, Mixpanel, Segment,
  Amplitude, Branch, Microsoft Clarity, Singular
* **Use camera**: no camera
* **Use tracking**: fake device model and build, operator, device name, WebView user agent, install times of other apps,
  boot id, the boot time (each app sees its own, so the time since boot no longer links apps across restarts) and the
  folder times that tell when the device was set up; the hardware files in /sys do not exist

The details are in [hooks.json](app/src/main/assets/hooks.json); [here](docs/XPRIVACY.md) is a comparison with XPrivacy.

### Limits

* Xposed cannot hook native code: audio recorded with OpenSL ES, like WhatsApp does, and files read natively,
  like by shell commands such as stat and ls, are not restricted.
* *Use analytics* and some other hooks hook libraries inside apps, which only works when the app did not
  [obfuscate](https://developer.android.com/studio/build/shrink-code.html) their names.
* *Get applications* keeps the app itself, apps sharing its user id and system apps visible to lookups of single apps,
  because the Android code running in apps, like WebView, needs them.
* Discovery of local devices with the app's own sockets, like mDNS or SSDP, cannot be prevented.
* The paths of other apps, like `/data/app/~~…==/com.example-…==/base.apk`, stay: Android reads their icons and labels
  from these files inside the app. They differ per install, so restrict *Get applications* to hide other apps entirely.
* The fake boot time shifts only the time-since-boot clock (elapsedRealtime), by its own amount per app. The wall clock
  and the uptime clock are left alone, because faking them breaks input events and secure connections; so an app can
  still link itself within one awake session (until the device sleeps) from the wall clock minus the uptime. A location
  fix's time and elapsed-time alarms are corrected to the shift, so a fix reads as fresh and alarms still fire on time,
  but timestamps the system reports on that clock in other ways are not: sensor events and camera frames can read as
  the shift old, and a media app's progress bar on the lock screen can be off by the shift.
* Restricted apps can detect Doppel, but cannot change anything, see [the FAQ](FAQ.md#user-content-faq21).
* Apps with root access can get around any restriction.

Help
----

* Questions: see the [FAQ](FAQ.md)
* Problems with Doppel: open an [issue](https://github.com/akano12/Doppel/issues)
* Problems with Xposed: see the [Xposed forum](http://forum.xda-developers.com/xposed)

Doppel with [Island](http://forum.xda-developers.com/android/-t3366295) and similar apps is not supported.

Contributing
------------

Pull requests are welcome for the documentation and for translations
(*app/src/main/res/values-xx/strings.xml*, translated from *values/strings.xml*).
For code, please open an [issue](https://github.com/akano12/Doppel/issues) first.
Definitions to share go into Downloads, see [here](docs/DEFINE.md#sharing).
By contributing you agree to the license below.

Building
--------

With [Android Studio](http://developer.android.com/sdk/), or from the command line with JDK 17 or later and the Android SDK (platform 37):

```
./gradlew assembleDebug        # debug build
./gradlew assembleRelease      # release build, signed when keystore.properties exists
./gradlew testDebugUnitTest    # checks the hook definitions and scripts
scripts/test-on-device.sh      # on a device or emulator: the tests of shell commands, which Windows skips
scripts/check-service.sh       # on a rooted device or emulator with Doppel: whom the service answers
```

*keystore.properties* (storeFile, storePassword, keyAlias, keyPassword) goes next to this file.
Sign with the v1 and v2 schemes only, like the build does: LSPosed cannot read an APK with a v3 signature.

Attribution
-----------

Doppel is based on [XPrivacyLua](https://github.com/M66B/XPrivacyLua) by Marcel Bokhorst (M66B), the successor of XPrivacy.

Doppel uses:

* [LuaJ](https://sourceforge.net/projects/luaj/). Copyright 2007-2013 LuaJ. All rights reserved. See [license](http://luaj.sourceforge.net/license.txt).
* [Glide](https://bumptech.github.io/glide/). Copyright 2014 Google, Inc. All rights reserved. See [license](https://raw.githubusercontent.com/bumptech/glide/master/LICENSE).
* [AndroidX](https://developer.android.com/jetpack/androidx). Copyright (C) 2011 The Android Open Source Project. See [license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt).
* [IBM Plex](https://github.com/IBM/plex) fonts. Copyright © 2017 IBM Corp. with Reserved Font Name "Plex". See [license](LICENSE-FONTS.txt) (SIL Open Font License 1.1).
* [Material Symbols](https://github.com/google/material-design-icons) icons. Copyright Google LLC. See [license](https://www.apache.org/licenses/LICENSE-2.0) (Apache License 2.0).

License
-------

Copyright (c) 2017-2022 Marcel Bokhorst. All rights reserved.<br>
Copyright (c) 2026 akano12. All rights reserved.

Doppel is free software under the [GNU General Public License version 3](LICENSE) or (at your option) any later version,
without any warranty.

*Android and Google Play are trademarks of Google LLC.*
