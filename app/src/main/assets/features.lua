-- This file is part of Doppel.

-- Doppel is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- Doppel is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2026 akano12

-- The features of a device (android.hardware.* and android.software.*, some with a version, and the ones
-- of its maker, like com.google.android.feature.* or com.samsung.feature.*) tell its model. Apps get none:
-- hasSystemFeature answers false and getSystemAvailableFeatures lists only the OpenGL ES version, which has
-- no name, except the features of setting features.keep (comma separated; a name ending in * matches the
-- features starting with it, like android.hardware.nfc*), for apps which need a feature to offer something,
-- the features of hardware another group hides (see OWNED), the features Android adds on every device
-- (see EVERY) and the ones apps see working anyway, like the touch screen, Wi-Fi, telephony and WebView (see SHOWN). The
-- framework, which checks features itself in the process of the app, gets the real answer (see calledbyframework), and
-- the managers and adapters it makes with it are hidden from the app like on a device without the feature (see
-- SERVICES), with their broadcasts (see BROADCASTS).
-- The shared libraries of the system carry vendor names too, like com.google.android.dialer.support;
-- the libraries of the Android platform and the ones of Google on every device stay. Libraries of apps
-- (static and SDK libraries) stay too, apps use them, in the list of names as well as in the list
-- of SharedLibraryInfo. Apps can still load a library they know the name of.

local PLATFORM = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { ['com.google.android.maps'] = true, ['com.google.android.media.effects'] = true }
-- SharedLibraryInfo: of the system (in its configuration or declared by a system app), static and SDK libraries
-- are of apps
local TYPE_BUILTIN = 0
local TYPE_DYNAMIC = 1

-- The features of hardware which the group hiding that hardware hides, with hooks of this script in its group;
-- Read.Features leaves them, so an app which may use the camera finds one. The biometric ones are hidden by
-- the hooks of biometrics, with the same names.
local OWNED = {
    ['Use.Camera'] = { 'android.hardware.camera*' },
    ['Record.Audio'] = { 'android.hardware.microphone' },
    ['Get.Sensors'] = { 'android.hardware.sensor.*' },
    ['Read.Biometrics'] = { 'android.hardware.fingerprint', 'android.hardware.biometrics.face',
                            'android.hardware.biometrics.iris' },
}

-- The features Android itself adds on every device, whatever its configuration says: exactly one of the RAM
-- classes, the one ActivityManager.isLowRamDevice tells anyway, and from Android 11 app enumeration. No device
-- is without them, so hiding them would show a device no Android is, and the real answer tells nothing more.
local EVERY = { 'android.hardware.ram.low', 'android.hardware.ram.normal', 'android.software.app_enumeration' }

-- The features apps see working anyway, so hiding them would only show that something hides them, while nearly every
-- handheld has them: the touch screen and its levels of multitouch, which every touch event and the input devices show
-- (see input_devices), like Configuration.touchscreen and the pointers of a gesture; Wi-Fi, of which ConnectivityManager
-- shows the networks (TRANSPORT_WIFI, TYPE_WIFI), so its manager stays too; telephony, of which ConnectivityManager
-- shows the mobile networks (TRANSPORT_CELLULAR, TYPE_MOBILE) and TelephonyManager the phone type, the SIM and whether
-- it calls and sends messages (getPhoneType, getSimState, isVoiceCapable, isSmsCapable), and which Android 15 and later
-- want for those methods of apps targeting them (PhoneInterfaceManager.enforceTelephonyFeatureWithException), with its
-- GSM or CDMA radio, data, subscriptions, calls and messages, each the device's own answer; and WebView, which works in
-- the app. The other features of Wi-Fi (Direct, Aware, RTT) and of telephony (eSIM, IMS, MBMS, carrier lock, satellite)
-- stay hidden.
local SHOWN = { 'android.hardware.touchscreen*', 'android.hardware.faketouch*', 'android.hardware.wifi',
                'android.hardware.telephony', 'android.hardware.telephony.gsm', 'android.hardware.telephony.cdma',
                'android.hardware.telephony.data', 'android.hardware.telephony.radio.access',
                'android.hardware.telephony.subscription', 'android.hardware.telephony.calling',
                'android.hardware.telephony.messaging', 'android.software.webview' }

-- The managers and adapters Android gives only on a device with a feature, else null: by the name of the service of
-- Context.getSystemService and by the class of the method which gives the adapter, each with the features Android
-- checks and the version from which it does. The manager of Wi-Fi stays, its feature is shown (see SHOWN); the managers
-- of Bluetooth and NFC exist on every device, without an adapter. The NFC adapter exists with any of its features
-- (NfcAdapter.getNfcAdapter), Android 14 and 15 check more of them. SystemServer starts the service of MIDI only with
-- its feature, and the one of consumer IR from Android 14; before, every handheld has the IR manager, which tells there
-- is no emitter without the hardware (ConsumerIrService). WifiManager tells Wi-Fi Direct and RTT too (see SUPPORTED),
-- false on a device without them: the chip of one without Wi-Fi Direct has no P2P, so also no Wi-Fi Display R2, which
-- runs over it, the Wi-Fi service clears the RTT of the chip without the feature of RTT, which exists from Android 9,
-- and the direct links without infrastructure of Android 15 are of Wi-Fi Direct and Aware, off in AOSP.
-- shortcut: the hidden ways to the same objects stay, like the system services wifiscanner and rttmanager,
-- NfcAdapter.getNfcAdapter, getDefaultAdapter of NfcAdapter without a context, BluetoothAdapter.createAdapter and
-- the binders of ServiceManager, upgrade when an app is seen to use one.
local SERVICES = {
    wifip2p = { ['android.hardware.wifi.direct'] = 27 },
    wifiaware = { ['android.hardware.wifi.aware'] = 27 },
    wifirtt = { ['android.hardware.wifi.rtt'] = 28 },
    midi = { ['android.software.midi'] = 27 },
    consumer_ir = { ['android.hardware.consumerir'] = 34 },
}
local IR = { ['android.hardware.consumerir'] = 27 }
local BLUETOOTH = { ['android.hardware.bluetooth'] = 27 }
local NFC = { ['android.hardware.nfc'] = 27, ['android.hardware.nfc.hce'] = 27, ['android.hardware.nfc.hcef'] = 27,
              ['android.hardware.nfc.uicc'] = 34, ['android.hardware.nfc.ese'] = 34, ['android.hardware.nfc.charging'] = 35 }
local SUPPORTED = {
    isP2pSupported = SERVICES.wifip2p,
    isDeviceToApRttSupported = SERVICES.wifirtt,
    isWifiDisplayR2Supported = SERVICES.wifip2p,
    isD2dSupportedWhenInfraStaDisabled = { ['android.hardware.wifi.direct'] = 35, ['android.hardware.wifi.aware'] = 35 },
    hasIrEmitter = IR,
}
local ADAPTERS = {
    ['android.bluetooth.BluetoothAdapter'] = BLUETOOTH,
    ['android.bluetooth.BluetoothManager'] = BLUETOOTH,
    ['android.nfc.NfcAdapter'] = NFC,
    ['android.nfc.NfcManager'] = NFC,
}

-- The broadcasts of the hardware of SERVICES and ADAPTERS, by the start of their actions, which Android sends only on a
-- device with it, sticky ones too, like the state of Wi-Fi Direct (WifiP2pServiceImpl.sendP2pStateChangedBroadcast),
-- which any app gets by registerReceiver without a receiver. While the features are hidden the app gets none of them,
-- like on a device without the hardware: a broadcast is marked when the app reads it (Intent.CREATOR), so it reaches
-- no receiver, also not as the first sticky broadcast of one which registers (see receiver_skip), and registerReceiver
-- gives no sticky one.
-- shortcut: a receiver the framework registers in the process of the app gets none either, and registerReceiver with a
-- filter which matches another sticky broadcast too gives none instead of that one, since Android gives only the first;
-- upgrade when an app is seen to need them
local BROADCASTS = {
    ['android.net.wifi.p2p.'] = SERVICES.wifip2p,
    ['android.net.wifi.aware.'] = SERVICES.wifiaware,
    ['android.net.wifi.rtt.'] = SERVICES.wifirtt,
    ['android.bluetooth.'] = BLUETOOTH,
    ['android.nfc.'] = NFC,
}

-- hasSystemFeature and Context.getSystemService are called very often, so the hooks of the groups of OWNED run for
-- their features only, and the one of getSystemService for the services of SERVICES
function filter(hook)
    local method = hook:getMethodName()
    if method == 'hasSystemFeature' then
        return OWNED[hook:getGroup()]
    end
    if method == 'getSystemService' then
        local names = {}
        for name in pairs(SERVICES) do
            names[#names + 1] = name
        end
        return names
    end
    return nil
end

-- Whether a feature is one of the names; a name ending in * matches the features starting with it
function matches(name, names)
    for _, feature in ipairs(names) do
        if string.sub(feature, -1) == '*' then
            local prefix = string.sub(feature, 1, -2)
            if string.sub(name, 1, string.len(prefix)) == prefix then
                return true
            end
        elseif feature == name then
            return true
        end
    end
    return false
end

-- Whether a feature is one of setting features.keep (comma separated)
function kept(name, param)
    local keep = param:getSetting('features.keep')
    if keep == nil then
        return false
    end
    local names = {}
    for feature in string.gmatch(keep, '[^,%s]+') do
        names[#names + 1] = feature
    end
    return matches(name, names)
end

-- Whether the group of the hook hides a feature: a group of OWNED its own features, Read.Features the others
-- but the ones of every device (see EVERY), the shown ones (see SHOWN) and the kept ones; the OpenGL ES version has no
-- name
function hides(group, name, param)
    if name == nil then
        return false
    end
    if OWNED[group] ~= nil then
        return matches(name, OWNED[group])
    end
    for _, names in pairs(OWNED) do
        if matches(name, names) then
            return false
        end
    end
    if matches(name, EVERY) or matches(name, SHOWN) then
        return false
    end
    return not kept(name, param)
end

-- Whether Android gives no manager or adapter without the features hidden from the app: it checks at least one of
-- them on this version, and all those are hidden
function absent(features, group, param)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local checked = false
    for name, since in pairs(features) do
        if sdk >= since then
            if not hides(group, name, param) then
                return false
            end
            checked = true
        end
    end
    return checked
end

-- The classes on the stack, which tell the calls of the framework from the ones of the app
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- The framework checks features itself, also in the process of the app, like when it creates a system service
-- (see SERVICES), and WebView counts the touch points of pages by them (see webviewclass). The framework gets the real answer,
-- the code of the app, its libraries included, gets false. The caller is the class on the stack below the deepest
-- method with the name of the hooked method: the hook engine can replace the frame of the hooked method by one of a
-- class of its own, with a random name, like LSPosed does, and the package manager calls the hooked method itself too,
-- like hasSystemFeature without a version, which calls the one with a version. A class of the framework is one of
-- the boot class path, not one by its name, since an app can name its own classes android.* too.
-- WebView is part of the framework too, with classes of its own package (see webviewclass).
-- A service the framework looks up itself while it makes one the app asked for counts as asked for by the app, being
-- below the same deepest frame, like WifiRttManager for RttManager. So does a call through a method of the app with the
-- name of the hooked method, like getSystemService of its own wrapper of a context which the framework calls, like
-- LayoutInflater.from does: a frame with the name which is not of the framework is of the app when it called the
-- next frame with the name itself, the frames of the hook engine are between the ones it replaces and their callees.
-- shortcut: a method of the app with the name which calls the hooked method through another method of its own counts
-- as the hook engine; looking its class up in the class loader of the app would tell them apart, but a class loader the
-- app makes itself (AppComponentFactory.instantiateClassLoader) would then see the names of the classes of the hook
-- engine, which tell Doppel is there; upgrade when the classes of the frames can be told apart without a lookup, like by
-- java.lang.StackWalker.
local FRAMEWORK = { 'android.', 'com.android.internal.', 'com.android.server.', 'java.', 'javax.', 'dalvik.', 'libcore.' }
local BOOT = {} -- Whether a class is of the boot class path, by name
local WEBVIEW = {} -- Whether a class is of WebView, by name, once WebView is loaded
local FIELDS = {} -- The static fields of loaderin, by class and name, false when not listed

-- Whether a class loader finds a class, without initializing it; the boot class path for a class loader of null
function finds(class, loader)
    local ok = pcall(function()
        return luajava.bindClass('java.lang.Class'):forName(class, false, loader)
    end)
    return ok
end

-- The class loader of the object in a static field of a class, nil when the field is null or not listed. The fields
-- are listed like LuaJ lists them, which Android does not report as a use of a hidden API, unlike getDeclaredField
-- (see location.lua); a version which hides the field leaves it out.
function loaderin(class, name)
    local key = class .. '.' .. name
    if FIELDS[key] == nil then
        FIELDS[key] = false
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass(class))
        for index = 1, fields['length'] do
            if fields[index]:getName() == name then
                fields[index]:setAccessible(true)
                FIELDS[key] = fields[index]
            end
        end
    end
    local object = FIELDS[key] and FIELDS[key]:get(nil)
    if not object then
        return nil
    end
    -- Through Object, so LuaJ does not list the methods of the class of the object, like the provider of WebView
    return luajava.bindClass('java.lang.Class').getClassLoader(luajava.bindClass('java.lang.Object').getClass(object))
end

-- WebView runs in the process of the app with classes of its own package, which are not of the boot class path, like
-- org.chromium.ui.base.TouchDevice, which counts the touch points pages see (navigator.maxTouchPoints) by the
-- touchscreen features, and gets the features it checks like the framework. A class of WebView is one the class loader of
-- the provider of WebView finds and the one of the app does not, since an app can have classes of the same names, like
-- Chromium or Cronet of its own. The provider is the one WebViewFactory keeps once WebView is loaded (in every version
-- from 8.1), since WebView.getWebViewClassLoader would load WebView, which takes long and after which the app can no
-- longer set the folder of its data (WebView.setDataDirectorySuffix); before that, and while the provider is made,
-- WebView gets false like the app.
-- shortcut: code the app loads itself, with a class loader of its own, can name a class like one of WebView and get the
-- real features, and a class loader of the app of its own sees the lookup of the classes of WebView in it, upgrade when
-- the classes of the frames can be told apart, like by java.lang.StackWalker of Android 14.
function webviewclass(class, param)
    if WEBVIEW[class] == nil then
        local ok, of = pcall(function()
            local loader = loaderin('android.webkit.WebViewFactory', 'sProviderInstance')
            if loader == nil then
                return nil
            end
            return finds(class, loader) and not finds(class, param:getApplicationContext():getClassLoader())
        end)
        if not ok or of == nil then
            return false
        end
        WEBVIEW[class] = of
    end
    return WEBVIEW[class]
end

function framework(class, param)
    -- The support library is part of apps, like androidx
    if string.sub(class, 1, 16) == 'android.support.' then
        return false
    end
    for _, prefix in ipairs(FRAMEWORK) do
        if string.sub(class, 1, string.len(prefix)) == prefix then
            -- The services of the system server are not of the boot class path, but only it has them
            if prefix == 'com.android.server.' and param:getPackageName() == 'android' then
                return true
            end
            if BOOT[class] == nil then
                BOOT[class] = finds(class, nil)
            end
            return BOOT[class]
        end
    end
    return false
end

-- Reflection is passed over, like Method.invoke and method handles, else an app calling the method by reflection
-- would count as the framework.
function calledbyframework(method, param)
    local frames = callers()
    local caller = nil
    for index = 1, frames['length'] - 1 do
        if frames[index]:getMethodName() == method then
            local class = frames[index]:getClassName()
            if index > 1 and frames[index - 1]:getMethodName() == method and
                    not framework(class, param) and not webviewclass(class, param) then
                return false
            end
            local next = index + 1
            while next < frames['length'] and (string.sub(frames[next]:getClassName(), 1, 18) == 'java.lang.reflect.' or
                    string.sub(frames[next]:getClassName(), 1, 17) == 'java.lang.invoke.') do
                next = next + 1
            end
            caller = frames[next]:getClassName()
        end
    end
    return (caller ~= nil and (framework(caller, param) or webviewclass(caller, param)))
end

-- Whether a feature is hidden from the app, see hides
function hidden(group, name, param, method)
    return hides(group, name, param) and not calledbyframework(method, param)
end

-- Whether an intent is a broadcast of hardware whose features are hidden from the app (see BROADCASTS)
function broadcasthidden(intent, group, param)
    local action = intent:getAction()
    if action == nil then
        return false
    end
    for prefix, features in pairs(BROADCASTS) do
        if string.sub(action, 1, string.len(prefix)) == prefix then
            return absent(features, group, param)
        end
    end
    return false
end

-- Whether a shared library of the system is not part of the Android platform
function vendor(name)
    if name == nil or GOOGLE[name] then
        return false
    end
    for _, prefix in ipairs(PLATFORM) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return false
        end
    end
    return true
end

-- Whether a shared library is a vendor library of the system, see vendor
function vendorlibrary(info)
    local kind = info:getType()
    return ((kind == TYPE_BUILTIN or kind == TYPE_DYNAMIC) and vendor(info:getName()))
end

-- The names of the libraries of apps, which getSystemSharedLibraryNames lists too, but without the type
function applibraries(param)
    local names = {}
    -- The lookup is hooked itself (see after), which would restrict it and report it as a call of the app
    local list = param:unhooked(function()
        local pm = param:getApplicationContext():getPackageManager()
        local ok, result = pcall(pm.getSharedLibraries, pm, 0)
        return (ok and result or nil)
    end)
    if list ~= nil then
        local items = list:toArray()
        for index = 1, items['length'] do
            local kind = items[index]:getType()
            if kind ~= TYPE_BUILTIN and kind ~= TYPE_DYNAMIC then
                names[items[index]:getName()] = true
            end
        end
    end
    return names
end

-- Get.Applications: a shared library names the apps which use it (getDependentPackages, and on Android 15 and later
-- getOptionalDependentPackages for an SDK library), with their version codes, and a static or SDK library names
-- the app which declares it; a device without the hidden apps has neither. The lists of the dependents are those
-- of the objects, which the app reads from a parcel, so they are filtered in place, and a list left empty becomes
-- none again, like Android sends for a library without dependents. The builtin and dynamic libraries stay, only
-- Android and system apps declare them, and Android lists them also when its visibility rules hide the system app
-- from the app. A static or SDK library stays when the app can see the package which declares it or an app which
-- uses it, like the app itself: the ApplicationInfo of that app names the library anyway.
-- shortcut: a static or SDK library of the system which no visible app uses is hidden too, Android does not let
-- the app look up the package which declares it; upgrade when the service can tell that package is of the system.
-- shortcut: Android 11 lists all dependents, also the system apps its visibility rules hide from the app, which are
-- hidden too, the app cannot look them up to tell them from hidden apps; upgrade with a lookup by the service.

-- Removes the hidden apps from a list of dependents, through the methods of the list, which reflection on its
-- iterator, a private class, may not reach; returns how many it had
function hidedependents(list, param)
    local items = list:toArray()
    local shown = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if param:isPackageVisible(items[index]:getPackageName()) then
            shown:add(items[index])
        end
    end
    if shown:size() < items['length'] then
        list:clear()
        list:addAll(shown)
    end
    return items['length']
end

function hidelibraries(result, param)
    local items = result:toArray()
    local kept = luajava.newInstance('java.util.ArrayList')
    -- The packages the libraries name: the one declaring each and its dependents
    local named, shown = 0, 0
    for index = 1, items['length'] do
        local item = items[index]
        local dependents = item:getDependentPackages()
        local count = hidedependents(dependents, param)
        if count > 0 and dependents:isEmpty() then
            pcall(function() item.mDependentPackages = nil end)
        end
        local ok, optional = pcall(function() return item:getOptionalDependentPackages() end)
        if ok then
            hidedependents(optional, param)
        end
        named = named + 1 + count
        local kind = item:getType()
        local declaring = item:getDeclaringPackage()
        if kind == TYPE_BUILTIN or kind == TYPE_DYNAMIC or not dependents:isEmpty() or
                param:isPackageVisible(declaring and declaring:getPackageName()) then
            kept:add(item)
            shown = shown + 1 + dependents:size()
        end
    end
    if shown == named then
        return false
    end
    if kept:size() ~= items['length'] then
        param:setResult(kept)
    end
    return true, tostring(named), tostring(shown)
end

-- ConsumerIrService of Android 8.1 to 13 checks a pattern to transmit after the permission, then refuses it without an
-- emitter; the hook runs before the call, which would send by the real emitter, so it makes the same checks and lets
-- the call go where the service throws first: without the permission, for a null pattern, a slice which is not
-- positive or a pattern over two seconds
function before(hook, param)
    if hook:getMethodName() ~= 'transmit' or not absent(IR, hook:getGroup(), param) or
            calledbyframework('transmit', param) then
        return false
    end
    local pattern = param:getArgument(1)
    if pattern == nil or param:getApplicationContext():checkSelfPermission('android.permission.TRANSMIT_IR') ~= 0 then
        return false
    end
    local total = 0
    for index = 1, pattern['length'] do
        if pattern[index] <= 0 then
            return false
        end
        total = total + pattern[index]
    end
    if total > 2000000 then
        return false
    end
    param:setResult(luajava.newInstance('java.lang.UnsupportedOperationException', 'IR emitter not available'))
    return true, 'transmit', nil
end

function after(hook, param)
    local method = hook:getMethodName()
    -- A device without consumer IR has an IR manager up to Android 13, whose service throws once the permission is
    -- checked; the hook runs after the call of the app passed that check
    -- shortcut: the exceptions of getCarrierFrequencies and transmit have no frames of Parcel.readException, upgrade
    -- when an app is seen to compare them
    if method == 'getCarrierFrequencies' then
        if param:getException() ~= nil or not absent(IR, hook:getGroup(), param) or calledbyframework(method, param) then
            return false
        end
        param:setResult(luajava.newInstance('java.lang.UnsupportedOperationException', 'IR emitter not available'))
        return true, method, nil
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    -- A broadcast of hidden hardware, which is marked (see BROADCASTS), many intents are read, so the check comes first
    if method == 'CREATOR:createFromParcel' then
        if broadcasthidden(result, hook:getGroup(), param) then
            param:putValue('features.hidden', true, result)
        end
        return false
    end
    if method == 'registerReceiverInternal' then
        if not broadcasthidden(result, hook:getGroup(), param) then
            return false
        end
        param:setResult(nil)
        return true, result:getAction(), nil
    end
    if (method == 'getSharedLibraries' or method == 'getSharedLibrariesAsUser') and hook:getGroup() == 'Get.Applications' then
        return hidelibraries(result, param)
    end
    if method == 'getSystemSharedLibraryNames' or method == 'getSharedLibraries' then
        -- A string array of names, or a list of SharedLibraryInfo
        local names = (method == 'getSystemSharedLibraryNames')
        local items = (names and result or result:toArray())
        local kept = luajava.newInstance('java.util.ArrayList')
        local apps
        for index = 1, items['length'] do
            local item = items[index]
            if names then
                if vendor(item) and apps == nil then
                    apps = applibraries(param)
                end
                if not vendor(item) or apps[item] then
                    kept:add(item)
                end
            elseif not vendorlibrary(item) then
                kept:add(item)
            end
        end
        if kept:size() == items['length'] then
            return false
        end
        if names then
            local stringClass = luajava.bindClass('java.lang.String')
            local arrayClass = luajava.bindClass('java.lang.reflect.Array')
            param:setResult(kept:toArray(arrayClass:newInstance(stringClass, 0)))
        else
            param:setResult(kept)
        end
        return true, tostring(items['length']), tostring(kept:size())
    end

    -- What WifiManager and ConsumerIrManager tell of a feature which is hidden (see SUPPORTED)
    if SUPPORTED[method] ~= nil then
        if not result or not absent(SUPPORTED[method], hook:getGroup(), param) or calledbyframework(method, param) then
            return false
        end
        param:setResult(false)
        return true, method, 'false'
    end

    -- A manager or adapter which Android gives only with a feature (see SERVICES)
    if method == 'getSystemService' or method == 'getAdapter' or method == 'getDefaultAdapter' then
        local features = (method == 'getSystemService' and SERVICES[param:getArgument(0)]) or ADAPTERS[hook:getClassName()]
        if features == nil or not absent(features, hook:getGroup(), param) or calledbyframework(method, param) then
            return false
        end
        param:setResult(nil)
        return true, (method == 'getSystemService' and param:getArgument(0) or hook:getClassName()), nil
    end

    if method == 'hasSystemFeature' then
        local name = param:getArgument(0)
        if result and hidden(hook:getGroup(), name, param, method) then
            param:setResult(false)
            return true, name, 'false'
        end
        return false
    end

    -- getSystemAvailableFeatures, the OpenGL ES version has no name;
    -- one stack walk for all features, the caller is the same for each
    if calledbyframework(method, param) then
        return false
    end
    local shown = luajava.newInstance('java.util.ArrayList')
    for index = 1, result['length'] do
        local name = result[index].name
        if not hides(hook:getGroup(), name, param) then
            shown:add(result[index])
        end
    end
    if shown:size() == result['length'] then
        return false
    end
    local featureClass = luajava.bindClass('android.content.pm.FeatureInfo')
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    param:setResult(shown:toArray(arrayClass:newInstance(featureClass, 0)))
    return true, tostring(result['length']), tostring(shown:size())
end
