XPrivacyLua
===========

Frequently Asked Questions
--------------------------

<a name="faq1"></a>
**(1) How can I clear all data?**

Primary users can clear all data of all users by uninstalling XPrivacyLua *while it is running*.
Secondary users can clear their own data by uninstalling XPrivacyLua *while it is running*.

All data is stored in the system folder */data/system/xlua* and can therefore not be backed up by regular backup apps.
You can backup and restore all restrictions, settings and custom hook definitions in *Settings* > *Backup* and *Restore*.
*Settings* > *Clear restrictions and settings* removes all restrictions and settings, including the fake values, of the current user
and keeps the custom hook definitions.

<a name="faq2"></a>
**(2) Can I run XPrivacy and XPrivacyLua side by side?**

Since XPrivacyLua is [in many aspects better](#user-content-faq7) than XPrivacy, running XPrivacy and XPrivacyLua side by side isn't supported.

<a name="faq3"></a>
**(3) How can I fix 'Module not running or updated'?**

This message means either that:

* The Xposed framework is not running: check if the framework is active in its manager app (like LSPosed).
* The XPrivacyLua module is not running: check if XPrivacyLua is enabled in the manager app, with *System Framework* in its scope
  (with the legacy Xposed API, like with EdXposed, also *Settings Storage*, see [here](README.md#installation)),
  and make sure you restarted your device (hard reboot) after installing/updating XPrivacyLua.
* There is a problem with the XPrivacyLua service: check the log of the manager app for XPrivacyLua problems.

Rebooting too soon after updating an Xposed module (before the manager app shows the update notification) is known to cause problems.
Disable and enable the module in the manager app and hard reboot again to fix this problem.

<a name="faq4"></a>
**(4) Can you add ...?**

* *Network and storage restrictions*: access to the internet and to the device storage can only be prevented by revoking Linux permission from an app, which will often result in the app crashing. Therefore this will not be added.
* *User interface features* like *templates*: I want to limit the time I put into this project and I want to keep things simple, so don't expect anything more than basic restriction management.
* *On demand restricting*: It is not really possible to add on demand restricting so that it works stable and can be supported on the long term, so this will not be added. See also [here](https://forum.xda-developers.com/showpost.php?p=75419161&postcount=49). However, you can use *Notify on restriction* in combination with restricting by default.
* *App specific*: anything specific for an app will not be added.
* *Security specific*: features related to security only will not be added.
* *User choice*: if you can already control the data, like selecting an account, no restriction is needed.
* *Crowd sourced restrictions*: there are not enough users for this to be useful.
* *An app settings button*: see [here](https://forum.xda-developers.com/showpost.php?p=75745469&postcount=2071) why this won't be added.
* *Select contacts groups to allow/block*: the Android contacts provider doesn't support contact groups at all hierarchy levels and working around this has appeared to be not possible reliably.

If you want to confine apps to their own folder, you can install the hook definition *BlockGuardOs.open*
with *Downloads* on the *Hooks* tab.

Apps having access to the IP address generally have access to the internet and therefore can get your IP address in a simple way,
see for example [here](https://www.privateinternetaccess.com/pages/whats-my-ip/). Therefore an IP address restriction doesn't make sense.

Revoking internet permission will result in apps crashing
and faking offline state doesn't prevent apps from accessing the internet.
Therefore internet restriction cannot properly be implemented.
You are advised to use a firewall app to control internet access, for example [NetGuard](https://forum.xda-developers.com/android/apps-games/app-netguard-root-firewall-t3233012).

If you still want to fake offline state, you can install the hook definition *NetworkInfo.createFromParcel*
with *Downloads* on the *Hooks* tab.

MAC addresses are restricted with *Read identifiers*: the addresses of Wi-Fi, Bluetooth and the network interfaces.
Android [hides](https://developer.android.com/training/articles/user-data-ids.html#version_specific_details_identifiers_in_m) the Wi-Fi and Bluetooth addresses from apps since Android 6
and the addresses of the network interfaces since Android 11, but only from apps targeting Android 11 or later.

You can enable status bar notifications on applying restrictions with *Notify on restriction* on the screen of an app.
This can be used as a replacement for on demand restricting by removing a restriction when needed.

You can ask for new restrictions, but you'll need to explain how it would improve your privacy as well.

See also [question 7](#user-content-faq7).

<a name="faq5"></a>
**(5) How can I fix 'There is a Problem Parsing the Package'?**

This error could mean that the downloaded file is corrupt, which could for example be caused by a connection problem or by a virus scanner.

It could also mean that you are trying to install XPrivacyLua on an unsupported Android version.
See [here](README.md#compatibility) for which Android versions are supported.

<a name="faq6"></a>
**(6) Why does a switch show a dash?**

A group is a set of hooks (see [here](app/src/main/assets/hooks.json)).
The switch of a group shows a dash when only some of its hooks are applied to the app,
and the *Restrict all* switch shows a dash when not all hooks of all groups are applied.
Tap a group to see its hooks; each hook has its own switch.

Hooks added by a new version are applied automatically to the apps which restrict their group.
A dash means that hooks were switched off, for example because an app did not work with them.
Tapping a switch with a dash applies all hooks of its group, like the *Restrict all* switch does for all groups;
tapping it again removes the restrictions of the group.

<a name="faq7"></a>
**(7) How does XPrivacyLua compare to XPrivacy?**

* XPrivacy supports Android 4.0.3 Ice Cream Sandwich to Android 5.1.1 Lollipop and XPrivacyLua supports Android version 6.0 Marshmallow and later, see [here](README.md#compatibility) about compatibility
* The user interface of XPrivacyLua is simpler than of XPrivacy, see also [question 4](#user-content-faq4)
* The restrictions of XPrivacyLua are designed to prevent apps from crashing, while a number of XPrivacy restrictions can cause apps to crash, see also [question 4](#user-content-faq4)
* XPrivacyLua has no on demand restricting for stability and maintenance reasons, see also [question 4](#user-content-faq4)
* XPrivacyLua can unlike XPrivacy restrict analytics services like [Firebase Analytics](https://firebase.google.com/docs/analytics/) and [Firebase Crashlytics](https://firebase.google.com/docs/crashlytics)
* XPrivacyLua [is user extensible](DEFINE.md) while XPrivacy is not
* XPrivacyLua is easier to maintain than XPrivacy, so XPrivacyLua is easier to update for new Android versions

In general XPrivacyLua and XPrivacy are comparable in protecting your privacy.
For a detailed comparison with XPrivacy see [here](XPRIVACY.md).

<a name="faq8"></a>
**(8) How can I define custom restrictions?**

See [here](DEFINE.md) for the documentation on defining hooks.

<a name="faq9"></a>
**(9) Why can an app still access my accounts?**

If you see an app accessing the list of accounts while the accounts restriction is being applied,
it is likely the Android account selector dialog you are seeing.
The app will see only the account you actually select.

<a name="faq10"></a>
**(10) Can applying a restriction let an app crash?**

XPrivacyLua is designed to let apps not crash.
However, sometimes an app will crash because of a restriction because there is a bug in the app.
For example XPrivacyLua can return no data to an app while the app is not expecting this but should be prepared to handle this because the Android API documentation says this might happen.

If you suspect that a restriction is causing a crash because there is a bug in the restriction, please provide a logcat and I will check the restriction.

<a name="faq11"></a>
**(11) How can I filter on ...?**

The chips *User*, *System* and *All* above the list select which apps are shown.
You can filter these apps on restricted apps, on not restricted apps, on system apps and on user apps by typing special characters into the search field:

* Filter on restricted apps: exclamation mark (!)
* Filter on not restricted apps: question mark (?)
* Filter on system apps: hash character (#)
* Filter on user apps: at character (@)

The special search characters should be the first characters in the search field (it is possible to combine special characters)
and can be followed by additional characters to refine the search result.
Since the search filters the apps of the selected chip, select *All* or *System* to find system apps with #.

You can also search for the uid of an app, like 10123.

<a name="faq12"></a>
**(12) Are there paid features?**

No, all features are free. The features of the former pro companion app, like fake values, backup and restore,
notifications on restriction and editing hook definitions, are part of XPrivacyLua itself.

<a name="faq13"></a>
**(13) Will XPrivacyLua slow down my apps?**

Depending on the number of applied restrictions, you might notice a slight delay when starting apps,
but you will generally not notice delays when using apps.

<a name="faq14"></a>
**(14) Will XPrivacyLua keep running after updating?**

Yes, if XPrivacyLua was running before the update, it will keep running after the update,
even though the user interface and new features will not be available until after a reboot.

<a name="faq15"></a>
**(15) Can I get a refund?**

There is nothing to buy, XPrivacyLua is free.

<a name="faq16"></a>
**(16) Can apps with root access be restricted?**

Apps with root permissions can do whatever they like, so they can circumvent any restriction.
So, be careful which apps you grant root permissions.
There is no support on restricting apps with root access.

<a name="faq17"></a>
**(17) Can I import my XPrivacy settings?**

XPrivacy and XPrivacyLua work differently, so this is not possible.

<a name="faq18"></a>
**(18) How do I selectively block/allow contacts?**

Select which contacts are visible with *Contacts* in *Fake values* (for all apps or for one app):

* Default (hide all): hide all contacts
* Only starred contacts: make starred contacts visible
* Only not starred contacts: make not starred contacts visible

These settings can be applied to one app or globally to all apps.

You'll need to apply the contacts restriction as well to apply these settings.

You can star contacts (make contacts favorite) in the Android contacts app.
Mostly the 'star' is in the upper right corner in the contact data.

Due to limitations of the Android contacts provider it is not possible to block/allow contacts by contacts group in a reliable way.

<a name="faq19"></a>
**(19) Why can I not import, export, backup or restore?**

These functions select files with the [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider).
This is an important Android component to select files and folders.
If you removed it yourself, you'll need to restore it, else you'll have to ask your ROM developer to add it.

<a name="faq20"></a>
**(20) Why can some incoming SMSes not be restricted?**

Likely because the app is using [this API](https://developers.google.com/identity/sms-retriever/request).
The app will only see the content of verification SMSes intended for the app, so there is no restriction for this needed.

Also, apps with permission to *receive* SMSes (in contrary to read SMSes) cannot be restricted.
Normally, there is just one app that can receive SMSes, that should not be restricted, else no SMSes can be received anymore.
If you really don't want an app to receive SMSes, you can revoke the SMS receive permission.

<br>

If you have another question, you can open an [issue](https://github.com/akano12/XPrivacyLua/issues).
