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

-- Connected keyboards, mice, game controllers and styluses, with their names and vendor ids,
-- reveal what someone uses and help to recognize a device. External input devices are hidden: the lists of ids leave
-- them out, a lookup by id gives null and the listeners of the app hear nothing of them. Their events still reach the
-- app, so the device of an event (InputEvent.getDevice) and its key map stay (see ofevent).
-- Built-in devices, like the touch screen and the buttons, stay, because apps need them.
-- Neither has its name, like sec_touchscreen, fts_ts or Xbox Wireless Controller, or its ids, which tell the model of
-- the device and what someone uses: a generic name by kind, no vendor and product id and a descriptor of the app
-- (key maps use the device id), also in their parcel. The motion ranges stay, they are the size of the display.
-- The virtual keyboard is the same on all devices.
-- The configuration (Configuration.keyboard, navigation and whether they are hidden) and getDeviceConfigurationInfo
-- have the keys and the navigation of the built-in devices only, like without the external ones (see builtin): on
-- phones no keys and no navigation, both hidden. So do the resources the app had already when the hooks were
-- installed, the configuration of the process which every activity starts with (see launched), and the configurations
-- the app gets when a keyboard is connected or removed later. KeyCharacterMap.deviceHasKey and deviceHasKeys, and
-- hasKeys of the virtual keyboard, which asks the same, answer for the keys of the built-in devices only (see haskeys).
-- shortcut: system server still relaunches an activity which does not handle keyboard changes itself when a keyboard is
-- connected or removed, and still sends Intent.ACTION_CONFIGURATION_CHANGED to the receivers the app registered then,
-- and the configuration of the process (ActivityThread.mConfiguration and the one of ResourcesManager, which the app
-- can read only by reflection) keeps the real keys until the first change of the configuration, so the Application
-- and services get that change with the keys and the navigation in its difference too; upgrade by faking the
-- configuration of the app in system server.

local TOUCHSCREEN = 0x1002 -- InputDevice.SOURCE_TOUCHSCREEN
-- Configuration.KEYBOARD_NOKEYS and KEYBOARD_QWERTY, NAVIGATION_NONAV, NAVIGATION_DPAD and NAVIGATION_TRACKBALL,
-- and KEYBOARDHIDDEN_YES, HARDKEYBOARDHIDDEN_YES and NAVIGATIONHIDDEN_YES
local NOKEYS, QWERTY, NONAV, DPAD, TRACKBALL, HIDDEN = 1, 2, 1, 2, 3, 2
-- The kinds of external devices by their sources (InputDevice.SOURCE_GAMEPAD, SOURCE_JOYSTICK, SOURCE_STYLUS,
-- SOURCE_TOUCHPAD, SOURCE_MOUSE), the first one a device has: game controllers have keys too, and a touchpad moves
-- the pointer like a mouse
local KINDS = { { 0x401, 'gamepad' }, { 0x1000010, 'gamepad' }, { 0x4002, 'stylus' }, { 0x100008, 'touchpad' },
    { 0x2002, 'mouse' }, { TOUCHSCREEN, 'touchscreen' } }

-- The generic name of a device by its kind; an external one of none of the kinds is a keyboard
function fakename(device)
    local sources = device:getSources()
    if not device:isExternal() then
        return (bit32.band(sources, TOUCHSCREEN) == TOUCHSCREEN and 'touchscreen' or 'gpio-keys')
    end
    for _, kind in ipairs(KINDS) do
        if bit32.band(sources, kind[1]) == kind[1] then
            return kind[2]
        end
    end
    return 'keyboard'
end

-- A descriptor of the app, a hash in hex like the real one, a SHA-1 with no fixed digits, so the digits of the version
-- (4) and the variant (8 to b) of the UUIDs are left out; a fake one stays, like the one of a device the app
-- read back from a parcel (see parcelled), which would else get a fake of the fake
-- shortcut: the fakes are known in the process which made them, so a parcel read back in another process of the app
-- gets a fake of the fake; upgrade by comparing with the fake of the live device of the id.
function fakedescriptor(descriptor, param)
    local context = param:getApplicationContext()
    if param:getValue('input.descriptor.' .. descriptor, context) then
        return descriptor
    end
    local package = param:getPackageName()
    local unfixed = '^(%x+)%-(%x+)%-%x(%x+)%-%x(%x+)%-(%x+)$'
    local hex = string.gsub(param:uuidOf(descriptor .. package), unfixed, '%1%2%3%4%5') ..
        string.gsub(param:uuidOf(package .. descriptor), unfixed, '%1%2%3%4%5')
    local fake = string.sub(hex, 1, 40)
    param:putValue('input.descriptor.' .. fake, true, context)
    return fake
end

-- InputDevice.getName, getVendorId, getProductId, getDescriptor and toString, of built-in devices and of the external
-- ones of events and key maps (see ofevent); the virtual keyboard stays
function identity(method, device, result, param)
    if device == nil or device:getId() == -1 then
        return nil
    end
    if method == 'getName' then
        return fakename(device)
    elseif method == 'getVendorId' or method == 'getProductId' then
        return 0
    elseif method == 'getDescriptor' then
        return fakedescriptor(result, param)
    end
    -- toString starts with 'Input Device <id>: <name>' and the line '  Descriptor: <descriptor>'
    local text = string.gsub(result, '^(Input Device %-?%d+: )[^\n]*', function(label)
        return label .. fakename(device)
    end, 1)
    return (string.gsub(text, '(\n  Descriptor: )([^\n]*)', function(label, descriptor)
        return label .. fakedescriptor(descriptor, param)
    end, 1))
end

-- The classes on the stack (see ofevent)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Whether Android looks up the device of an event the app got: up to Android 13 KeyCharacterMap.load looks it up with
-- InputManager.getInputDevice, without which an external keyboard types with the US layout instead of its own, and
-- InputEvent.getDevice, which is never null for a device sending events, so the code of the guide to game controllers
-- (event.getDevice().getMotionRange) would crash.
-- shortcut: a lookup by the id of an event, like InputDevice.getDevice(event.getDeviceId()), still gives null, and an
-- event the app makes itself with the id of an external device gets the device; upgrade by keeping the ids of the
-- events the app got.
function ofevent()
    local frames = callers()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if class == 'android.view.KeyCharacterMap' or class == 'android.view.InputEvent' then
            return true
        end
    end
    return false
end

-- Whether a device is external; remembered, since a removed device can no longer be looked up, and Android never gives
-- the id of a device to another one (see InputReader)
function external(id, param)
    local context = param:getApplicationContext()
    local key = 'input.external.' .. id
    if param:getValue(key, context) then
        return true
    end
    local device = param:unhooked(function()
        return luajava.bindClass('android.view.InputDevice'):getDevice(id)
    end)
    if device == nil or not device:isExternal() then
        return false
    end
    param:putValue(key, true, context)
    return true
end

-- onInputDeviceAdded, onInputDeviceChanged and onInputDeviceRemoved of a listener of the app (see before)
-- shortcut: the methods are hooked for the class of the listener, so a call the app makes itself with the id of an
-- external device does nothing too; upgrade by looking for the delegate of InputManager on the stack.
function heard(when, param)
    if when == 'before' and external(param:getArgument(0), param) then
        param:setResult(nil)
    end
end

-- The parameter is not named hook, the function to hook a method is.
-- InputManager.registerInputDeviceListener: the listener hears nothing of external devices, also of those connected
-- already, which it hears of when they are removed; where a parcel of InputDevice.writeToParcel starts (see parcelled);
-- ResourcesImpl.updateConfiguration(Impl) gets a copy of the configuration without the external devices, so the
-- resources choose their values by it, also those made from a configuration read before the hooks were installed
function before(h, param)
    local method = h:getMethodName()
    if method == 'setTo' then
        return launched(param)
    end
    if method == 'updateConfiguration' or method == 'updateConfigurationImpl' then
        local config = param:getArgument(0)
        if config == nil then
            return false
        end
        local copy = param:unhooked(function()
            return luajava.newInstance('android.content.res.Configuration', config)
        end)
        local real = unplugged(copy, param)
        if real == nil then
            return false
        end
        param:setArgument(0, copy)
        return true, real, copy.keyboard .. ' ' .. copy.navigation
    elseif method == 'writeToParcel' then
        local parcel = param:getArgument(0)
        if parcel ~= nil then
            param:putValue('input.parcel', parcel:dataPosition(), parcel)
        end
        return false
    elseif method ~= 'registerInputDeviceListener' or param:getArgument(0) == nil then
        return false
    end
    local ids = param:unhooked(function()
        return luajava.bindClass('android.view.InputDevice'):getDeviceIds()
    end)
    for index = 1, ids['length'] do
        external(ids[index], param)
    end
    local listener = param:getArgument(0)
    hook(listener, 'onInputDeviceAdded', heard)
    hook(listener, 'onInputDeviceChanged', heard)
    hook(listener, 'onInputDeviceRemoved', heard)
    return true
end

-- A parcel of a device has the name, the vendor and product id and the descriptor of the fields, which
-- InputDevice.CREATOR reads back: after the id, the generation and the controller number, from Android 12 after the key
-- map, and from Android 14 QPR2 (still SDK 34) with the bus between the product id and the descriptor
-- (see InputDevice.writeToParcel).
-- They are written like the getters give them, only when the parcel has the real ones at their place, so another
-- layout stays as it is.
-- shortcut: Android 17 is taken to write the parcel of Android 15 and 16; check when its source is out.
function parcelled(param)
    local parcel = param:getArgument(0)
    local start = (parcel ~= nil and param:getValue('input.parcel', parcel) or nil)
    local device = param:getThis()
    if start == nil or param:getException() ~= nil or device:getId() == -1 then
        return false
    end
    local real = param:unhooked(function()
        return { name = device:getName(), vendor = device:getVendorId(), product = device:getProductId(),
                 descriptor = device:getDescriptor() }
    end)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local stop = parcel:dataPosition()
    local copy = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, from, to, bus = pcall(function()
        copy:appendFrom(parcel, start, parcel:dataSize() - start)
        copy:setDataPosition(0)
        if sdk >= 31 then
            luajava.bindClass('android.view.KeyCharacterMap').CREATOR:createFromParcel(copy)
        end
        copy:readInt()
        copy:readInt()
        copy:readInt()
        local from = copy:dataPosition()
        if copy:readString() ~= real.name or copy:readInt() ~= real.vendor or copy:readInt() ~= real.product then
            return nil
        end
        local at = copy:dataPosition()
        local bus = (sdk >= 34 and copy:readInt() or nil)
        -- On Android 14 before QPR2 the int is the length of the descriptor, which is then read as a string; the bus is
        -- not read as a string, since Parcel logs a string with a bad length (SafetyNet 172655291)
        -- shortcut: a bus of 40, the length of the descriptor, is taken for the layout without the bus, so the parcel
        -- stays; no Linux bus is 40 (BUS_SDW, 0x21, is the highest), upgrade by also checking the int after it.
        if sdk == 34 and bus == #real.descriptor then
            bus = nil
            copy:setDataPosition(at)
        end
        if copy:readString() ~= real.descriptor then
            return nil
        end
        return from, copy:dataPosition(), bus
    end)
    if ok and from ~= nil then
        local name = fakename(device)
        parcel:setDataSize(start + from)
        parcel:setDataPosition(start + from)
        parcel:writeString(name)
        parcel:writeInt(0)
        parcel:writeInt(0)
        if bus ~= nil then
            parcel:writeInt(bus)
        end
        parcel:writeString(fakedescriptor(real.descriptor, param))
        -- The rest is copied, also what the parcel had after the device, where its end moves with the fake
        local moved = parcel:dataPosition() - (start + to)
        parcel:appendFrom(copy, to, copy:dataSize() - to)
        parcel:setDataPosition(stop + moved)
        copy:recycle()
        return true, real.name, name
    end
    copy:recycle()
    return false
end

-- Whether the keyboard and navigation of a configuration can be those of an external device: keys of letters, a d-pad
-- or a trackball
function keyed(keyboard, navigation)
    return keyboard == QWERTY or navigation == DPAD or navigation == TRACKBALL
end

-- Configuration.keyboard and navigation as Android sets them without the external devices (see
-- DisplayContent.computeScreenConfiguration): keys of letters (QWERTY) and a trackball or d-pad only of the built-in
-- devices which are not virtual, else the d-pad of devices which always have one (config_hasPermanentDpad); nil when
-- the values show no external device. Values which are not set (0), like in the configuration of an activity, which
-- overrides only some, stay.
function builtin(keyboard, navigation, param)
    if not keyed(keyboard, navigation) then
        return nil
    end
    local found = param:unhooked(function()
        local inputDevice = luajava.bindClass('android.view.InputDevice')
        local ids = inputDevice:getDeviceIds()
        local letters, nav = false, NONAV
        for index = 1, ids['length'] do
            -- nil for devices which were removed meanwhile
            local device = inputDevice:getDevice(ids[index])
            if device ~= nil and not device:isVirtual() and not device:isExternal() then
                -- InputDevice.SOURCE_TRACKBALL, SOURCE_DPAD and KEYBOARD_TYPE_ALPHABETIC
                local sources = device:getSources()
                if bit32.band(sources, 0x10004) == 0x10004 then
                    nav = TRACKBALL
                elseif bit32.band(sources, 0x201) == 0x201 and nav == NONAV then
                    nav = DPAD
                end
                letters = letters or device:getKeyboardType() == 2
            end
        end
        if nav == NONAV then
            local system = luajava.bindClass('android.content.res.Resources'):getSystem()
            local id = system:getIdentifier('config_hasPermanentDpad', 'bool', 'android')
            if id ~= 0 and system:getBoolean(id) then
                nav = DPAD
            end
        end
        return { letters = letters, navigation = nav }
    end)
    local fakeKeyboard = ((keyboard == QWERTY and not found.letters) and NOKEYS or keyboard)
    local fakeNavigation = ((navigation == DPAD or navigation == TRACKBALL) and found.navigation or navigation)
    if fakeKeyboard == keyboard and fakeNavigation == navigation then
        return nil
    end
    return fakeKeyboard, fakeNavigation
end

-- A configuration without the external devices, with the keys and the navigation hidden when there are none, like
-- PhoneWindowManager.adjustConfigurationLw hides them, the keys of letters too when there is no keyboard on the screen
-- (no default input method); the real keyboard and navigation, or nil when it shows no external device.
-- shortcut: a built-in keyboard behind a closed lid shows as not hidden while an external keyboard is connected; upgrade
-- by reading the lid switch should such devices come back.
function unplugged(config, param)
    local keyboard, navigation = builtin(config.keyboard, config.navigation, param)
    if keyboard == nil then
        return nil
    end
    local real = config.keyboard .. ' ' .. config.navigation
    if keyboard ~= config.keyboard then
        config.keyboard = keyboard
        config.hardKeyboardHidden = HIDDEN
        local ime = param:unhooked(function()
            return luajava.bindClass('android.provider.Settings$Secure'):getString(
                param:getApplicationContext():getContentResolver(), 'default_input_method')
        end)
        if ime == nil or ime == '' then
            config.keyboardHidden = HIDDEN
        end
    end
    if navigation ~= config.navigation then
        config.navigation = navigation
        if navigation == NONAV then
            config.navigationHidden = HIDDEN
        end
    end
    return real
end

-- The configuration of the process, which ActivityThread got before the hooks were installed (bindApplication) and
-- keeps as its compatibility configuration: every activity starts with a copy of it, new Configuration(it), which
-- copies by setTo, in ActivityThread.performLaunchActivity on every version, and an activity whose configuration
-- changes gets it with its own values over it (handleActivityConfigurationChanged). setTo is hooked, not the
-- constructor, which is small enough for the boot image to compile into performLaunchActivity, a hidden method which
-- could not be named to run in the interpreter. It is unplugged in place at that copy, the first time
-- an activity starts, so the activity starts without the external devices like the configurations it gets later
-- (see readFromParcel), else the keys and the navigation of the difference would hide a change it handles itself
-- (configChanges, see ActivityThread.shouldReportChange). The configuration is reached as the argument of the public
-- copy, since the fields which hold it are hidden APIs (of ConfigurationController from Android 12), which the
-- rules refuse to apps. Only the copy of ActivityThread, the first framework class below the hook and the
-- constructor, called right by the constructor or the stub of the hook framework, which is named like the method: a
-- copy the app makes itself stays, also one in its own code which performLaunchActivity calls, like an
-- AppComponentFactory.
function launched(param)
    local config = param:getArgument(0)
    if config == nil or not keyed(config.keyboard, config.navigation) then
        return false
    end
    local frames = callers()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if string.sub(class, 1, 8) == 'android.' and class ~= 'android.content.res.Configuration' then
            if class ~= 'android.app.ActivityThread' or frames[index]:getMethodName() ~= 'performLaunchActivity' then
                return false
            end
            local above = frames[index - 1]
            if above:getClassName() ~= 'android.content.res.Configuration' and above:getMethodName() ~= 'setTo' then
                return false
            end
            local real = unplugged(config, param)
            if real == nil then
                return false
            end
            return true, real, config.keyboard .. ' ' .. config.navigation
        end
    end
    return false
end

-- Configuration.readFromParcel, of every configuration the system gives the app, the field Resources.mSystem, when
-- the hooks are installed, for the resources of the app and of the system made before with the real configuration,
-- and ActivityManager.getDeviceConfigurationInfo
function configured(method, param)
    if param:getException() ~= nil then
        return false
    end
    if method == 'readFromParcel' then
        local config = param:getThis()
        local real = unplugged(config, param)
        if real == nil then
            return false
        end
        return true, real, config.keyboard .. ' ' .. config.navigation
    elseif method == '#mSystem' then
        -- Updated with a copy, so the resources choose their values by it too, like those of the -qwerty folders
        local real
        for _, resources in ipairs({ param:getApplicationContext():getResources(), param:getResult() }) do
            local config = param:unhooked(function()
                return luajava.newInstance('android.content.res.Configuration', resources:getConfiguration())
            end)
            local unset = unplugged(config, param)
            if unset ~= nil then
                param:unhooked(function()
                    resources:updateConfiguration(config, nil)
                end)
                real = unset
            end
        end
        return real ~= nil, real
    end

    -- ConfigurationInfo has the keyboard and navigation of the configuration, and the features of them:
    -- INPUT_FEATURE_HARD_KEYBOARD (1) and INPUT_FEATURE_FIVE_WAY_NAV (2), see
    -- ActivityTaskManagerService.getDeviceConfigurationInfo
    local info = param:getResult()
    local keyboard, navigation
    if info ~= nil then
        keyboard, navigation = builtin(info.reqKeyboardType, info.reqNavigation, param)
    end
    if keyboard == nil then
        return false
    end
    local real = info.reqKeyboardType .. ' ' .. info.reqNavigation
    local features = bit32.band(info.reqInputFeatures, bit32.bnot(3))
    if keyboard ~= 0 and keyboard ~= NOKEYS then
        features = bit32.bor(features, 1)
    end
    if navigation == DPAD or navigation == TRACKBALL then
        features = bit32.bor(features, 2)
    end
    info.reqKeyboardType = keyboard
    info.reqNavigation = navigation
    info.reqInputFeatures = features
    return true, real, keyboard .. ' ' .. navigation
end

-- KeyCharacterMap.deviceHasKey and deviceHasKeys, and InputDevice.hasKeys of the virtual keyboard, of the id -1, ask
-- about the keys of all devices, the external ones too (InputManagerService.hasKeys of the device -1): the answer of
-- the built-in devices which are not virtual, each asked by InputDevice.hasKeys. The virtual keyboard adds no keys of
-- its own, since it has no keys of the kernel, so the device -1 is not asked again.
function haskeys(method, param)
    local result = param:getResult()
    if param:getException() ~= nil or result == nil then
        return false
    end
    local single = (method == 'deviceHasKey')
    local count = (single and 1 or result['length'])
    local any = (single and result)
    for index = 1, (single and 0 or count) do
        any = any or result[index]
    end
    if not any then
        return false
    end
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local codes = param:getArgument(0)
    if single then
        codes = arrayClass:newInstance(luajava.bindClass('java.lang.Integer').TYPE, 1)
        codes[1] = param:getArgument(0)
    end
    local found = param:unhooked(function()
        local inputDevice = luajava.bindClass('android.view.InputDevice')
        local ids = inputDevice:getDeviceIds()
        local found = {}
        for index = 1, ids['length'] do
            -- nil for devices which were removed meanwhile
            local device = inputDevice:getDevice(ids[index])
            if device ~= nil and not device:isVirtual() and not device:isExternal() then
                local has = device:hasKeys(codes)
                for code = 1, count do
                    found[code] = found[code] or has[code]
                end
            end
        end
        return found
    end)
    if single then
        if found[1] then
            return false
        end
        param:setResult(false)
        return true, 'true', 'false'
    end
    local fake = arrayClass:newInstance(luajava.bindClass('java.lang.Boolean').TYPE, count)
    local real, faked, changed = {}, {}, false
    for index = 1, count do
        fake[index] = (found[index] == true)
        changed = changed or fake[index] ~= result[index]
        real[index], faked[index] = tostring(result[index]), tostring(fake[index])
    end
    if not changed then
        return false
    end
    param:setResult(fake)
    return true, table.concat(real, ' '), table.concat(faked, ' ')
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'writeToParcel' then
        return parcelled(param)
    elseif method == 'deviceHasKey' or method == 'deviceHasKeys' then
        return haskeys(method, param)
    elseif method == 'hasKeys' then
        if param:getThis():getId() ~= -1 then
            return false
        end
        return haskeys(method, param)
    elseif method == 'readFromParcel' or method == '#mSystem' or method == 'getDeviceConfigurationInfo' then
        return configured(method, param)
    end
    local result = param:getResult()
    if result == nil then
        return false
    end

    if method == 'getName' or method == 'getVendorId' or method == 'getProductId' or method == 'getDescriptor' or
            method == 'toString' then
        local fake = identity(method, param:getThis(), result, param)
        if fake == nil or fake == result then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    -- InputManager.getInputDevice, InputDevice.getDevice
    if method == 'getInputDevice' or method == 'getDevice' then
        if not result:isExternal() or ofevent() then
            return false
        end
        param:setResult(nil)
        -- The real name, which the hooked getName would fake as if the app asked for it
        return true, param:unhooked(function()
            return result:getName()
        end), nil
    end

    -- InputManager.getInputDeviceIds, InputDevice.getDeviceIds; the lookups of the devices are hooked themselves
    -- (getInputDevice and getDevice), which would report them as calls of the app
    local kept = param:unhooked(function()
        local inputDevice = luajava.bindClass('android.view.InputDevice')
        local ids = {}
        for index = 1, result['length'] do
            -- nil for devices which were removed meanwhile
            local device = inputDevice:getDevice(result[index])
            if device ~= nil and not device:isExternal() then
                ids[#ids + 1] = result[index]
            end
        end
        return ids
    end)
    if #kept == result['length'] then
        return false
    end

    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local intClass = luajava.bindClass('java.lang.Integer')
    local fake = arrayClass:newInstance(intClass.TYPE, #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end
