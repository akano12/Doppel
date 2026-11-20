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

-- Installed keyboards, spell checkers and accessibility services reveal installed apps and help to fingerprint
-- devices. Keyboards and spell checkers of other apps are hidden, like for checks for specific packages
-- (package_probe), those of the system and of the app itself stay. Spell checking in the app is not affected,
-- the framework does not use these methods for it. Accessibility services of all other apps are hidden, those of
-- system apps too, since they also tell how the user uses the device, like a preinstalled screen reader (see
-- ownservice). Accessibility and touch exploration are off for the app when only hidden services turn them on, like on
-- a device without them, but stay on for the framework, whose views send their events to the services only while
-- accessibility is on, and for AccessibilityManager itself, so an event the app sends itself reaches the services
-- instead of the IllegalStateException "Accessibility off" of Android 9 and later, which the app cannot expect, since
-- its listeners hear accessibility go on (see below).
-- shortcut: libraries in the app, like androidx (ExploreByTouchHelper), Compose and WebView, count as the app, so they
-- send no events of their own to a hidden service, also a preinstalled TalkBack, which then cannot read what they draw;
-- and the app can still tell that accessibility is on for hidden services: its AccessibilityDelegate and
-- onInitializeAccessibilityEvent run for the events of its views, AccessibilityManager.sendAccessibilityEvent and
-- interrupt on its main thread do not throw like on a device without the services, a hidden screen reader which
-- explores by touch turns the app's touches into hover events, the listeners the app adds
-- (addAccessibilityStateChangeListener, addTouchExplorationStateChangeListener and
-- addAccessibilityServicesStateChangeListener) hear the state change, since Android hands the framework's listeners the
-- same value, observers of the settings accessibility_enabled and touch_exploration_enabled hear them change, and while
-- a hidden service binds, which turns accessibility on before the service is in the enabled list
-- (AccessibilityUserState.isHandlingAccessibilityEventsLocked), and for up to a second after, the app gets it on;
-- upgrade by telling the classes of libraries apart like features.lua does for WebView, and by reading the services
-- which bind, like from enabled_accessibility_services, and then wrapping the listeners the app adds itself, since a
-- wrapper alone would pass on the state Android sends when the bind starts.

-- Whether a keyboard or spell checker is of a system app or of the app itself (see XParam.isVisible), and whether an
-- accessibility service is of the app itself (see ownservice). The app of a service is part of the service info, so
-- this works for apps which are hidden by the package visibility rules too.
function visible(hook, param, item)
    if hook:getClassName() == 'android.view.accessibility.AccessibilityManager' then
        return ownservice(param, item:getResolveInfo().serviceInfo.applicationInfo) -- AccessibilityServiceInfo
    end
    return param:isVisible(item:getServiceInfo().applicationInfo) -- InputMethodInfo, SpellCheckerInfo
end

-- Whether an accessibility service is of the app itself, the only ones the app sees: those of system apps tell how the
-- user uses the device too, like a preinstalled screen reader. Processes of the system see all, like for
-- XParam.isVisible. The same function as in settingssecure_getstring; a test compares them.
-- shortcut: this hides the service of "android" which autofill reports in its compatibility mode too, which tells that
-- the user has a password manager, so a browser the password manager fills in that mode may no longer be filled; keep
-- that one service if users want autofill there more than hiding it
function ownservice(param, ai)
    return (ai == nil or param:isSystem() or ai.uid == param:getUid())
end

-- Whether accessibility, or touch exploration for touch, is on only for services of hidden apps, so it would be off on a
-- device without them. Android turns accessibility on while it binds services or runs a UiAutomation, and touch
-- exploration for bound services which ask for it (AccessibilityManagerService.updateAccessibilityEnabledSettingLocked
-- and updateTouchExplorationLocked); the enabled services are the bound ones, so without any, like for a UiAutomation,
-- it stays. Kept for a second, the framework asks for each event, so a change shows within a second; with kept, only
-- what is kept, nil when the services are to be read again. The same function as in settingssecure_getstring, which
-- tells it for the settings; a test compares them.
function onlyhidden(param, touch, kept)
    local context = param:getApplicationContext()
    local cached = param:getValue('accessibility.onlyhidden', context)
    if cached == nil or os.time() - cached.at >= 1 then
        if kept then
            return nil
        end
        -- The list is hooked itself, which would filter it and report it as a call of the app
        local items = param:unhooked(function()
            return context:getSystemService('accessibility'):getEnabledAccessibilityServiceList(-1):toArray()
        end)
        cached = { at = os.time(), visible = {}, hidden = {} }
        for index = 1, items['length'] do
            local service = items[index]
            local hidden = not ownservice(param, service:getResolveInfo().serviceInfo.applicationInfo)
            local of = (hidden and cached.hidden or cached.visible)
            of.enabled = true
            -- FLAG_REQUEST_TOUCH_EXPLORATION_MODE turns it on with CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION, or for a service
            -- of an app for Android 4.2 or older with a grant of the user (canRequestAndRequestsTouchExplorationLocked); a
            -- hidden service counts by the flag alone and a visible one only with the capability, so neither keeps it on
            of.touch = (of.touch or bit32.band(service.flags, 4) ~= 0 and (hidden or bit32.band(service:getCapabilities(), 2) ~= 0))
        end
        param:putValue('accessibility.onlyhidden', cached, context)
    end
    local kind = (touch and 'touch' or 'enabled')
    return (cached.hidden[kind] == true and cached.visible[kind] ~= true)
end

-- The frames on the stack (see calledbyframework)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

local BOOT = {} -- Whether a class is of the boot class path, by name

-- Whether a class is of the boot class path, which only the framework is; an app can name its own classes android.* too
function boot(class)
    if BOOT[class] == nil then
        BOOT[class] = (pcall(function()
            return luajava.bindClass('java.lang.Class'):forName(class, false, nil)
        end))
    end
    return BOOT[class]
end

-- Whether the framework calls the hooked method, like a View sending an event, and whether AccessibilityManager calls it
-- itself. The caller is the class below the frame of the hooked method. From Android 9 AccessibilityManager calls
-- isEnabled only in sendAccessibilityEvent and interrupt, which throw "Accessibility off" for it, in setStateLocked, also
-- while it is made within the lookup of it by a context (SystemServiceRegistry.CachedServiceFetcher), and in
-- notifyAccessibilityStateChanged, none of which returns the answer to the app, though notifyAccessibilityStateChanged
-- passes it to the app's listeners (see the shortcut above), and isTouchExplorationEnabled nowhere; Android 8.1 calls
-- neither. A call by reflection is one of the app, also when a class of the framework makes it for the app, like
-- android.util.Property.of(AccessibilityManager.class, Boolean.class, "enabled"), since the framework calls these methods
-- itself. The frame is the first one from the top with the name of the method, since the hook engine can give it a class
-- of its own, with a random name, like LSPosed does, and further down the stack can be other methods of the name, like
-- View.isEnabled.
-- shortcut: while only hidden services are on, which includes a preinstalled screen reader, each call takes a whole stack
-- trace, also each call of the framework, like for each view of a list which scrolls, which was not measured; the walk
-- stops at the first frame below the hooked one, or past the frames of reflection; upgrade to a bounded check, like
-- java.lang.StackWalker of Android 14, if a device shows that it slows scrolling.
function calledbyframework(hook)
    local method, class = hook:getMethodName(), hook:getClassName()
    local frames = callers()
    for index = 1, frames['length'] - 1 do
        if frames[index]:getMethodName() == method then
            local next, reflected = index + 1, false
            while next < frames['length'] do
                local name = frames[next]:getClassName()
                if string.sub(name, 1, 18) == 'java.lang.reflect.' or string.sub(name, 1, 17) == 'java.lang.invoke.' then
                    reflected = true
                elseif name == class then
                    -- The hooked method itself, where a hook engine keeps its frame below its own
                    if frames[next]:getMethodName() ~= method then
                        return true, true
                    end
                else
                    break
                end
                next = next + 1
            end
            return (not reflected and boot(frames[next]:getClassName())), false
        end
    end
    return false, false
end

local FLAG_SYSTEM = 1

-- Whether a keyboard or spell checker is of a system app, like InputMethodInfo.isSystem
function system(info)
    return bit32.band(info:getServiceInfo().applicationInfo.flags, FLAG_SYSTEM) ~= 0
end

-- Whether a keyboard only adds to the others, like InputMethodInfo.isAuxiliaryIme, which apps may not call (hidden):
-- it has subtypes and all are auxiliary. shortcut: also the subtypes the keyboard added later, which isAuxiliaryIme
-- leaves out, upgrade when a keyboard is seen adding others than those of its kind.
function auxiliary(info)
    local count = info:getSubtypeCount()
    for index = 0, count - 1 do
        if not info:getSubtypeAt(index):isAuxiliary() then
            return false
        end
    end
    return count > 0
end

-- Whether a keyboard has a subtype of the keyboard mode, of English when asked, like containsSubtypeOf of
-- InputMethodUtils without the country
function typing(info, english)
    for index = 0, info:getSubtypeCount() - 1 do
        local subtype = info:getSubtypeAt(index)
        if string.lower(subtype:getMode()) == 'keyboard' and
                (not english or string.lower(string.match(subtype:getLocale(), '^[^_]*')) == 'en') then
            return true
        end
    end
    return false
end

-- The enabled keyboards of a device without the hidden apps, in their order: the ones which stay visible, and when one
-- is hidden and no keyboard is left which is not auxiliary (like voice typing), after them the installed system
-- keyboards, which InputMethodManagerService enables then (buildInputMethodListLocked).
-- shortcut: all visible system keyboards with a keyboard subtype, where Android enables those which are default for
-- the system language (getDefaultEnabledImes) and with none left voice typing too, upgrade when devices with more than
-- one system keyboard are seen. The same function as in settingssecure_getstring, which names them as the enabled ones.
function keyboards(param, items)
    -- The lists are hooked themselves, which would report them as calls of the app
    return param:unhooked(function()
        local manager = param:getApplicationContext():getSystemService('input_method')
        items = items or manager:getEnabledInputMethodList():toArray()
        local kept = {}
        local typed = false
        for index = 1, items['length'] do
            local info = items[index]
            if param:isVisible(info:getServiceInfo().applicationInfo) then
                kept[#kept + 1] = info
                typed = typed or not auxiliary(info)
            end
        end
        if #kept < items['length'] and not typed then
            local installed = manager:getInputMethodList():toArray()
            for index = 1, installed['length'] do
                local info = installed[index]
                if system(info) and param:isVisible(info:getServiceInfo().applicationInfo) and not auxiliary(info) and
                        typing(info, false) then
                    kept[#kept + 1] = info
                end
            end
        end
        return kept
    end)
end

-- The keyboard a device without the hidden apps would use, of its enabled ones (see keyboards), like
-- InputMethodManagerService chooses one (getMostApplicableDefaultIME): searching from the last one, the first system
-- keyboard which is not auxiliary and has an English keyboard subtype, else the first system keyboard which is not
-- auxiliary the search met, else the first enabled one; nil when none is left. The same function as in
-- settingssecure_getstring, which names it as default_input_method; a test compares them.
function keyboard(param)
    local items = keyboards(param)
    local found
    for index = #items, 1, -1 do
        local info = items[index]
        if system(info) and not auxiliary(info) then
            if typing(info, true) then
                return info
            end
            found = found or info
        end
    end
    return found or items[1]
end

-- The spell checker a device without the hidden apps would use: the first one of a system app which stays visible;
-- nil when none is left. shortcut: TextServicesManagerService takes the first one with a subtype of the system language
-- (findAvailSystemSpellCheckerLocked), upgrade when devices with more than one system spell checker are seen. The same
-- function as in settingssecure_getstring, which names it as selected_spell_checker.
function spellchecker(param)
    return param:unhooked(function()
        local manager = param:getApplicationContext():getSystemService('textservices')
        -- Public as of Android 12, hidden before, as an array which is null when there are none
        local ok, list = pcall(manager.getEnabledSpellCheckerInfos, manager)
        local items = (ok and list:toArray() or manager:getEnabledSpellCheckers())
        for index = 1, (items ~= nil and items['length'] or 0) do
            if system(items[index]) and param:isVisible(items[index]:getServiceInfo().applicationInfo) then
                return items[index]
            end
        end
        return nil
    end)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    -- Accessibility and touch exploration, checked in order of cost, since the framework asks for each event. The stack
    -- comes before the services are read again: AccessibilityManager asks while it is made, within the lookup of it by a
    -- context (SystemServiceRegistry.CachedServiceFetcher), and reading the services through that context would wait for
    -- that lookup forever.
    if method == 'isEnabled' or method == 'isTouchExplorationEnabled' then
        local touch = (method == 'isTouchExplorationEnabled')
        if result ~= true or onlyhidden(param, touch, true) == false then
            return false
        end
        local framework, internal = calledbyframework(hook)
        if internal or not onlyhidden(param, touch) or framework then
            return false
        end
        param:setResult(false)
        return true
    end

    -- The current service, also for a user and of a spell checker session, which names the current spell checker:
    -- of a hidden app the one Android would choose without it, like the settings name it (see settingssecure_getstring),
    -- else none. shortcut: the keyboard of another user is chosen from those of the app's own user (an app needs
    -- INTERACT_ACROSS_USERS_FULL to ask), upgrade when apps with it are seen asking. shortcut: the subtypes stay the
    -- ones of the hidden keyboard (getCurrentInputMethodSubtype, getLastInputMethodSubtype,
    -- getEnabledInputMethodSubtypeList of no keyboard, selected_input_method_subtype) unless Read.Locale fakes them,
    -- since Read.Locale hooks the same methods and replaces the subtype from the real list (unhooked), so whichever
    -- group runs second undoes the other, and the legacy backend leaves their order open (equal priorities); upgrade
    -- with the subtype Android keeps from input_methods_subtype_history for the keyboard chosen here, else -1, in one
    -- script which both groups use.
    if method == 'getCurrentInputMethodInfo' or method == 'getCurrentInputMethodInfoAsUser' or
            method == 'getCurrentSpellChecker' or method == 'getCurrentSpellCheckerInfo' or method == 'getSpellChecker' then
        if visible(hook, param, result) then
            return false
        end
        local ok, fake = pcall(string.find(method, 'InputMethod', 1, true) and keyboard or spellchecker, param)
        if not ok then
            fake = nil
        end
        param:setResult(fake)
        return true, result:getPackageName(), (fake ~= nil and fake:getPackageName() or nil)
    end

    -- getEnabledSpellCheckers returns an array, the others a list, getEnabledSpellCheckerInfos one over an array
    local array = (method == 'getEnabledSpellCheckers')
    local items = (array and result or result:toArray())
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if visible(hook, param, items[index]) then
            kept:add(items[index])
        end
    end
    if kept:size() == items['length'] then
        return false
    end
    if method == 'getEnabledInputMethodList' then
        -- After them the keyboards Android enables when none is left, like enabled_input_methods names them (see keyboards)
        local ok, infos = pcall(keyboards, param, items)
        for index = kept:size() + 1, (ok and #infos or 0) do
            kept:add(infos[index])
        end
    end
    if array or method == 'getEnabledSpellCheckerInfos' then
        local infoClass = luajava.bindClass('android.view.textservice.SpellCheckerInfo')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local infos = kept:toArray(arrayClass:newInstance(infoClass, 0))
        if array then
            param:setResult(infos)
        else
            -- A list of fixed size over the array, like TextServicesManager returns (Arrays.asList), so add and remove
            -- throw and toArray gives SpellCheckerInfo[]
            param:setResult(luajava.bindClass('java.util.Arrays'):asList(infos))
        end
    elseif hook:getClassName() == 'android.view.accessibility.AccessibilityManager' then
        -- Unmodifiable, like the lists AccessibilityManager returns
        param:setResult(luajava.bindClass('java.util.Collections'):unmodifiableList(kept))
    else
        param:setResult(kept)
    end
    return true, tostring(items['length']), tostring(kept:size())
end
