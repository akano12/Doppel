XPrivacyLua
===========

Really simple to use privacy manager for Android 6 and later (successor of [XPrivacy](https://forum.xda-developers.com/xposed/modules/xprivacy-ultimate-android-privacy-app-t2320783)).

<br>

This is a maintained fork of [XPrivacyLua by M66B](https://github.com/M66B/XPrivacyLua), which is not supported anymore.

<br>

Revoking Android permissions from apps often let apps crash or malfunction.
XPrivacyLua solves this by feeding apps fake data instead of real data.

Features
--------

* Simple to use
* Manage any user or system app
* [Extensible](DEFINE.md)
* Multi-user support
* Free and open source

Restrictions
------------

* Determine activity (fake unknown activity, hide activity transitions, see [here](https://developers.google.com/location-context/activity-recognition/))
* Get applications (hide installed apps, launcher activities, broadcast receivers, [widgets](https://developer.android.com/reference/android/appwidget/AppWidgetManager.html), usage statistics, keyboards and accessibility services of other apps, also for lookups by user)
* Get calendars (hide calendars)
* Get call log (hide call log)
* Get contacts (hide contacts, optionally except (non) starred contacts, hide blocked numbers)
* Get location (fake location, block [NMEA](https://en.wikipedia.org/wiki/NMEA_0183) messages and raw GNSS measurements)
* Get messages (hide MMS, SMS, SIM, voicemail)
* Get sensors (hide all available sensors)
* Read [account](https://developer.android.com/reference/android/accounts/Account.html) name (fake name, mostly e-mail address)
* Read biometric sensors (no fingerprint sensor, face or iris unlock and no enrolled fingerprints; checks which include the screen lock stay)
* Read battery (level in steps of 10 percent, hide charge counter, current, energy, cycle count, voltage and temperature, battery saver appears off)
* Read clipboard (fake paste)
* Read debugging state (developer options and USB or wireless debugging appear off)
* Read memory and storage (total memory and storage rounded up to the sizes devices are sold with, free space rounded down to two digits)
* Read device settings (settings people change, like animation and font scale, screen timeout, proxy, alarm sound and automatic time zone, have the values of a new device)
* Read identifiers (fake build serial number, Android ID, advertising ID, Privacy Sandbox ad ID, app set ID, GSF ID, limit ad tracking, random SD card ids on each start of the app, Wi-Fi, Bluetooth and network interface MAC addresses)
* Read notifications (fake [status bar notifications](https://developer.android.com/reference/android/service/notification/StatusBarNotification.html))
* Read network data (hide cell info, Wi-Fi networks, paired and connected Bluetooth devices, fake Wi-Fi network name, also in the text of Wi-Fi and network info, block Bluetooth scans and discovery of devices in the local network)
* Read sync data (hide [sync data](https://developer.android.com/training/sync-adapters/creating-sync-adapter.html))
* Read telephony data (fake IMEI, MEID, SIM serial number, phone number, voicemail number, type allocation code, etc)
* Read time zone (fake time zone, setting *value.timezone*, default UTC)
* Read device features (hide the list of device features; single checks only hide features which are not part of the Android platform and features listed in setting *features.hide*; hide external input devices, like keyboards and game controllers)
* Read VPN state (hide VPN transports, networks and interfaces)
* Record audio (prevent recording and speech recognition)
* Record video (prevent recording)
* Send messages (prevent sending MMS, SMS, data)
* Use analytics ([Adjust](https://www.adjust.com/), [AppsFlyer](https://www.appsflyer.com/), [Facebook app events](https://developers.facebook.com/docs/reference/androidsdk/current/facebook/com/facebook/appevents/appeventslogger.html/), [Firebase Analytics](https://firebase.google.com/docs/analytics/), [Firebase Crashlytics](https://firebase.google.com/docs/crashlytics), [Mixpanel](https://mixpanel.com/), [Segment](https://segment.com/))
* Use camera (fake camera not available, hide cameras)
* Use tracking (fake user agent for [WebView](https://developer.android.com/reference/android/webkit/WebView.html) only, [Build properties](https://developer.android.com/reference/android/os/Build.html) including the security patch level, the same values for the shell commands getprop, uname and /proc/version, network/SIM country/operator, device and Bluetooth name)

The tracking restrictions will work only if the code of the target app was not [obfuscated](https://developer.android.com/studio/build/shrink-code.html).
The other restrictions will work always.

Management
----------

The *Apps* tab shows the apps by what needs attention: apps which need a review (a hook failed,
or a user app without restrictions, which can be restricted with one tap), apps which restricted something recently
and all others; tap a section to show or hide its apps.

Besides restricting apps, the tabs of the app offer:

* Light and dark theme, color themes and a pure black background for the dark theme (*Settings*)
* Fake values (*Settings*): values used for all apps, like the IMEI, Android ID, location and time zone;
  *Fake values for this app* on the screen of an app sets values for that app only
* Activity: which app triggered which restriction, when, with the real and the fake value, and hook errors
* Hooks: view, edit, enable, disable, add, import and export hook definitions,
  and install definitions shared by others with *Downloads*
* Backup and restore of settings, custom hook definitions and restrictions (*Settings*)

The (no longer available) pro companion app is not needed anymore.

Hide or fake?

* Hide: return empty list
* Fake: return empty or fake value

It is possible to add custom restriction definitions, see [this FAQ](FAQ.md#user-content-faq8) for details.

You can see all technical details [here](app/src/main/assets/hooks.json).

Notes
-----

* Some apps will start the camera app to take pictures. This cannot be restricted and there is no need for this, because only you can take pictures in this scenario, not the app.
* Some apps will use [OpenSL ES for Android](https://developer.android.com/ndk/guides/audio/opensl-for-android.html) to record audio, an example is WhatsApp. Xposed cannot hook into native code, so this cannot be prevented.
* The get applications restriction will not restrict getting information about individual apps for stability and performance reasons.
* The telephony data restriction will result in apps seeing a fake IMEI. However, this doesn't change the IMEI address of your device.

Compatibility
-------------

XPrivacyLua is supported on Android 6.0 Marshmallow and later.
For Android 4.0.3 KitKat to Android 5.1.1 Lollipop you can use [XPrivacy](https://github.com/M66B/XPrivacy/blob/master/README.md) (unsupported though).

XPrivacyLua is supported for smartphones and tablets only.

Installation
------------

* Install [Magisk](https://www.xda-developers.com/how-to-install-magisk/)
* Android 6-7: download, install, and activate [Xposed](http://forum.xda-developers.com/xposed)
* Android 8.1 and later: download, install, and activate [LSPosed](https://github.com/LSPosed/LSPosed) or a compatible fork
* Build XPrivacyLua from source (see *Source code* below), install, and activate it

XPrivacyLua uses the [modern Xposed API](https://github.com/libxposed/api) (API 101 or later) when the framework provides it,
and the legacy Xposed API otherwise, for example with Xposed or EdXposed.

LSPosed: the scope needs to include *System Framework*, which hosts the XPrivacyLua service in the settings provider,
and any app you want to restrict, see [here](https://github.com/LSPosed/LSPosed/wiki/How-to-use-it#install--activate-modules).
With the legacy API, *Settings Storage* (com.android.providers.settings) needs to be in the scope too.

Frequently Asked Questions
--------------------------

See [here](FAQ.md) for a list of often asked questions.

Support
-------

XPrivacyLua with [Island](http://forum.xda-developers.com/android/-t3366295), etc is not supported.

* For support on Xposed, please go [here](http://forum.xda-developers.com/xposed)
* For support on XPrivacyLua, please open an [issue](https://github.com/akano12/XPrivacyLua/issues)

Contributing
------------

*Documentation*

Contributions to this document and the frequently asked questions
are prefered in the form of [pull requests](https://help.github.com/articles/creating-a-pull-request/).

*Translations*

Translations are welcome as pull requests of *app/src/main/res/values-xx/strings.xml* (xx is the language code),
translated from *app/src/main/res/values/strings.xml*.

*Source code*

Building XPrivacyLua from source code is straightforward with [Android Studio](http://developer.android.com/sdk/)
or from the command line with JDK 17 or later and the Android SDK (platform 37):

```
./gradlew assembleDebug        # debug build
./gradlew assembleRelease      # release build, signed when keystore.properties exists
./gradlew testDebugUnitTest    # validates the built-in hook definitions and Lua scripts
```

The release build is signed with the key described in *keystore.properties* (storeFile, storePassword, keyAlias, keyPassword).
A build signed with another key than the official release needs to replace (uninstall) the official release.

It is expected that you can solve build problems yourself, so there is no support on building.

Source code contributions are prefered in the form of [pull requests](https://help.github.com/articles/creating-a-pull-request/).
Please open an [issue](https://github.com/akano12/XPrivacyLua/issues) first to tell what your plans are.

Please note that you agree to the license below by contributing, including the copyright.

Attribution
-----------

XPrivacyLua uses:

* [LuaJ](https://sourceforge.net/projects/luaj/). Copyright 2007-2013 LuaJ. All rights reserved. See [license](http://luaj.sourceforge.net/license.txt).
* [Glide](https://bumptech.github.io/glide/). Copyright 2014 Google, Inc. All rights reserved. See [license](https://raw.githubusercontent.com/bumptech/glide/master/LICENSE).
* [AndroidX](https://developer.android.com/jetpack/androidx). Copyright (C) 2011 The Android Open Source Project. See [license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt).
* [IBM Plex](https://github.com/IBM/plex) fonts. Copyright © 2017 IBM Corp. with Reserved Font Name "Plex". See [license](LICENSE-FONTS.txt) (SIL Open Font License 1.1).
* [Material Symbols](https://github.com/google/material-design-icons) icons. Copyright Google LLC. See [license](https://www.apache.org/licenses/LICENSE-2.0) (Apache License 2.0).

License
-------

[GNU General Public License version 3](https://www.gnu.org/licenses/gpl.txt)

Copyright (c) 2017-2021 Marcel Bokhorst. All rights reserved

XPrivacyLua is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

XPrivacyLua is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with XPrivacyLua. If not, see [https://www.gnu.org/licenses/](https://www.gnu.org/licenses/).

Trademarks
----------

*Android is a trademark of Google Inc. Google Play is a trademark of Google Inc*
