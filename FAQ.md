Doppel
======

Frequently Asked Questions
--------------------------

<a name="faq1"></a>
**(1) How can I clear all data?**

Uninstall Doppel *while it is running*: this clears the data of your user.
When no other user has Doppel installed anymore, all data is cleared, also the custom hook definitions.

*Settings* > *Clear restrictions and settings* removes all restrictions and settings, including the fake values, of the current user
and keeps the custom hook definitions.

All data is stored in the system folder */data/system/xlua*, so regular backup apps cannot back it up.
Use *Settings* > *Backup* and *Restore* for all restrictions, settings and custom hook definitions.
Restrictions of hook definitions which the device does not have, like custom definitions which are not in the backup, are not restored.

<a name="faq3"></a>
**(3) How can I fix 'Module not running or updated'?**

This message means that:

* The Xposed framework is not running: check that it is active in its manager app (like LSPosed).
* Doppel is not running: check that Doppel is enabled in the manager app, with *System Framework* in its scope
  (with the legacy Xposed API, like with EdXposed, also *Settings Storage*, see [here](README.md#installation)),
  and restart the device (hard reboot) after installing or updating Doppel.
* There is a problem with the Doppel service: check the log of the manager app for Doppel problems.

Rebooting too soon after updating, before the manager app shows that the module was updated, can cause this.
Disable and enable Doppel in the manager app and hard reboot again.

<a name="faq4"></a>
**(4) Can you add ...?**

* *Network and storage restrictions*: access to the internet and to the device storage can only be prevented by revoking Linux permission from an app, which will often result in the app crashing. Therefore this will not be added.
* *User interface features* like *templates*: Doppel keeps restriction management simple, so features like these will not be added.
* *On demand restricting*: it cannot be made to work stable and be supported on the long term, so this will not be added; see *Notify on restriction* below.
* *App specific*: anything specific for an app will not be added.
* *Security specific*: features related to security only will not be added.
* *User choice*: if you can already control the data, like selecting an account, no restriction is needed.
* *Select contacts groups to allow/block*: the Android contacts provider does not support contact groups at all levels, so this cannot be done reliably.

To block domains, *Downloads* on the *Hooks* tab has *InetAddress.getByName* and *InetAddress.getAllByName*.

An app with internet access can get your IP address in a simple way, like from a web page, so an IP address restriction makes no sense.

Revoking internet permission makes apps crash, and faking an offline state does not stop apps from using the internet,
so an internet restriction cannot be done properly. Use a firewall app instead, like [NetGuard](https://github.com/M66B/NetGuard).
To fake an offline state anyway, install the hook definition *NetworkInfo.createFromParcel* with *Downloads* on the *Hooks* tab.

MAC addresses are restricted with *Read identifiers*: the addresses of Wi-Fi, Bluetooth and the network interfaces.
Android [hides](https://developer.android.com/identity/user-data-ids) the Wi-Fi and Bluetooth addresses from apps,
and the addresses of the network interfaces since Android 11, from apps targeting Android 11 or later.

*Notify on restriction* on the screen of an app shows a notification the first time each group restricts something.
You can use it instead of on demand restricting, by removing a restriction when needed.

You can ask for new restrictions with an [issue](https://github.com/akano12/Doppel/issues), but you'll need to explain how it would improve your privacy as well.

<a name="faq5"></a>
**(5) How can I fix 'There is a Problem Parsing the Package'?**

The downloaded file may be corrupt, for example because of a connection problem or a virus scanner.

Or the Android version is not supported: Doppel needs Android 8.1 or later.

<a name="faq6"></a>
**(6) Why does a switch show a dash?**

A group is a set of hooks (see [hooks.json](app/src/main/assets/hooks.json)).
The switch of a group shows a dash when only some of its hooks are applied to the app (*Partially restricted*),
and the *Restrict all* switch shows a dash when not all hooks of all groups are applied.
Tap a group to see its hooks; each hook has its own switch.

Hooks added by a new version are applied automatically to the apps which restrict their group;
hooks which you switched off stay off after an update.
So a dash means that hooks were switched off, for example because an app did not work with them.
Tapping a switch with a dash applies all hooks of its group, like the *Restrict all* switch does for all groups;
tapping it again removes the restrictions of the group.

<a name="faq8"></a>
**(8) How can I define custom restrictions?**

See [here](docs/DEFINE.md) for the documentation on defining hooks.

<a name="faq9"></a>
**(9) Why can an app still access my accounts?**

*Read account name* does not hide the accounts: it fakes their names, with *Account name* in *Fake values*.
The accounts of the app itself keep their names.

<a name="faq10"></a>
**(10) Can applying a restriction let an app crash?**

Doppel is designed to let apps not crash.
Sometimes an app crashes because of a bug in the app: for example, Doppel returns no data where the Android documentation says
this can happen, but the app does not expect it.

If you think a restriction itself has a bug, open an [issue](https://github.com/akano12/Doppel/issues) with a logcat.

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

No, all features are free.

<a name="faq13"></a>
**(13) Will Doppel slow down my apps?**

With many restrictions you might notice a slight delay when starting apps, but generally not while using them.

<a name="faq14"></a>
**(14) Will Doppel keep running after updating?**

Yes, the restrictions keep working with the old version until you restart the device.
Until then the app shows *Module not running or updated* and new features are not available, so restart the device after updating.

<a name="faq16"></a>
**(16) Can apps with root access be restricted?**

They can be restricted, but apps with root access can get around any restriction, so be careful which apps you grant root.

<a name="faq18"></a>
**(18) How do I selectively block/allow contacts?**

Select which contacts are visible with *Contacts* in *Fake values*, for all apps or for one app:

* Default (hide all): hide all contacts
* Only starred contacts: make starred contacts visible
* Only not starred contacts: make not starred contacts visible

This needs the group *Get contacts* to be restricted too.
You can star contacts (make them favorite) in the contacts app.

Contacts groups cannot be used, see [above](#user-content-faq4).

<a name="faq19"></a>
**(19) Why can I not import, export, backup or restore?**

These functions select files with the [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider),
the Android component to select files and folders.
If you removed it, restore it; else ask the developer of your ROM to add it.

<a name="faq20"></a>
**(20) Why can some incoming SMSes not be restricted?**

*Get messages* empties the incoming SMS and MMS messages an app receives as a broadcast.
An app can still get verification SMSes with the [SMS Retriever API](https://developer.android.com/identity/sms-retriever)
of Google Play services, but only those meant for the app, so no restriction is needed for this.

Do not restrict your SMS app with *Get messages*, else it cannot show your messages.

<a name="faq21"></a>
**(21) Can apps detect Doppel?**

Apps you did not restrict cannot ask Doppel anything: they get the same answers as on a device without Doppel.
Apps you restricted can detect it. The hooks run in the process of the app, so the app can ask the Doppel service
for its own hooks and read the fake values for all apps, like the hooks do, or look for Xposed itself.
They cannot change your settings or restrictions, nor read the restrictions of other apps.

LSPosed loads into every app in the scope of Doppel, also when the app is not restricted,
and apps can look for LSPosed itself, so add only the apps you restrict to the scope.

<a name="faq22"></a>
**(22) Why can an app not run commands anymore?**

The group *Run commands* lets the commands an app runs, like *su*, *id* or *logcat*, fail,
except the programs you allow in *Fake values* with *Allowed commands*, like `getprop, uname`,
and the command lines matching *Allowed command lines* (a regular expression).
*Restrict all* restricts this group too, so if an app stops working after restricting all groups,
allow the commands it needs, or switch *Run commands* off for that app.
The commands an app tried are shown on the *Activity* tab.
Allowed commands still get the fake values of the other groups, like getprop.

<br>

If you have another question, you can open an [issue](https://github.com/akano12/Doppel/issues).
