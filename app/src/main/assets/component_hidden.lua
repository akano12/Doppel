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

-- Apps talk to each other through services, screens and broadcasts they name, like an app binding the service
-- of another app for the account signed in. The components of the apps which Get.Applications hides (see
-- package_probe and provider_hidden) cannot be reached, like for an app which is not installed: Android itself gets
-- the call, with the name of the package changed to one which cannot exist, a name with a colon, so it makes the
-- checks it makes first, like of protected and sticky broadcasts, file descriptors and file uris, and then answers
-- like for an app which is not installed: binding a service fails, starting one finds none, stopping one stops none,
-- starting a screen throws ActivityNotFoundException, with the name the app asked for, and a broadcast reaches no
-- receiver. Intents which name no package or component are resolved by Android, so they stay.

-- The packages renamed by the calls in progress on this thread, for after, which comes in reverse order, with the
-- intents of the screens and their copies
local calls = {}

-- The package an intent names, by its component or its package, nil when it names none; Android resolves an
-- intent which names none by its selector, if it has one, but not one of a service for an app targeting Android 5
-- or later, which it refuses with "Service Intent must be explicit" (ContextImpl.validateServiceIntent)
function packageof(intent, selector)
    if intent == nil then
        return nil
    end
    local component = intent:getComponent()
    if component ~= nil then
        return component:getPackageName()
    end
    local packageName = intent:getPackage()
    if packageName == nil and selector and intent:getSelector() ~= nil then
        return packageof(intent:getSelector(), true)
    end
    return packageName
end

-- Whether an intent names a package which is hidden; a name ending in :hidden is the copy of identifier_services for
-- the same call, which hooks bindService too (see before), not a package of an app
function hidden(param, intent, selector)
    local packageName = packageof(intent, selector)
    return packageName ~= nil and string.sub(packageName, -7) ~= ':hidden' and
            not param:isPackageVisible(packageName), packageName
end

-- A copy of an intent naming a package which cannot exist instead, where packageof found the package, so Android
-- finds nothing, like for an app which is not installed; the intent of the app stays as it is. Android refuses to set
-- a package next to a selector, which only an intent read from a parcel can have, so its copy has no selector.
-- shortcut: Android resolves such an intent by its selector, which the copy loses, so a selector naming a visible app
-- finds nothing too; upgrade when an app is seen sending one
function renamed(intent, packageName)
    local copy = intent:clone()
    local component = intent:getComponent()
    if component ~= nil then
        copy:setComponent(luajava.newInstance('android.content.ComponentName', packageName .. ':hidden',
                component:getClassName()))
    elseif intent:getPackage() ~= nil then
        copy:setSelector(nil)
        copy:setPackage(packageName .. ':hidden')
    else
        copy:setSelector(renamed(intent:getSelector(), packageName))
    end
    return copy
end

-- What Android changes in the intent of a screen while it starts it, which the app sees afterwards: the ClipData of
-- a share or a photo, with its grant flags and the uri of the photo, when the intent has no ClipData
-- (Intent.migrateExtraStreamToClipData), and the referrer of the activity (Instrumentation.execStartActivity).
-- shortcut: the app gets these when the call returns, and only these, so its own code which runs during the call,
-- like the getType of its provider for the data or a writeToParcel of an extra, sees the intent without them, and an
-- ActivityMonitor of the app (Instrumentation.addMonitor) gets the copy itself, with the name with :hidden; upgrade when
-- an app is seen looking at its intent during the call
function giveback(intent, copy)
    if intent:getClipData() == nil and copy:getClipData() ~= nil then
        intent:setClipData(copy:getClipData())
        intent:addFlags(copy:getFlags())
        intent:putExtras(copy)
    elseif copy:hasExtra('android.intent.extra.REFERRER') then
        intent:putExtras(copy)
    end
end

function before(hook, param)
    local method = hook:getMethodName()
    -- The frame of the call goes on the stack for after first, so after takes its own frame whatever fails below
    local names = { screens = {} }
    table.insert(calls, names)

    -- Instrumentation.execStartActivitiesAsUser, which execStartActivities calls: a copy of the array, with a copy of
    -- each screen of a hidden app, like a single screen (see below), so Android starts the screens before it and
    -- names the first screen in its exception
    if method == 'execStartActivitiesAsUser' then
        local intents = param:getArgument(4)
        local copies = nil
        for index = 1, (intents ~= nil and intents['length'] or 0) do
            local isHidden, packageName = hidden(param, intents[index], true)
            if isHidden then
                if copies == nil then
                    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                    copies = arrayClass:newInstance(luajava.bindClass('android.content.Intent'), intents['length'])
                    for copy = 1, intents['length'] do
                        copies[copy] = intents[copy]
                    end
                end
                copies[index] = renamed(intents[index], packageName)
                table.insert(names.screens, { intents[index], copies[index] })
                table.insert(names, packageName)
            end
        end
        if copies ~= nil then
            param:setArgument(4, copies)
        end
        return #names > 0, names[1]
    end

    -- The intent is the first argument of the methods of Context, the second of ActivityManager.AppTask.startActivity,
    -- the fifth of Instrumentation.execStartActivity
    local index = 0
    if method == 'execStartActivity' then
        index = 4
    elseif method == 'startActivity' then
        index = 1
    end
    local intent = param:getArgument(index)
    -- The selector of a service counts for an app targeting Android 4.4 or earlier only (see packageof); the methods
    -- of services, like bindIsolatedService and stopServiceAsUser, have Service in their name
    local selector = not string.find(method, 'Service') or
            param:getApplicationContext():getApplicationInfo().targetSdkVersion < 21
    local isHidden, packageName = hidden(param, intent, selector)
    if not isHidden then
        return false
    end
    table.insert(names, packageName)

    -- Android sends an APPWIDGET_UPDATE or APPWIDGET_CONFIGURE which names no component to the app itself instead of
    -- the package it names (ActivityManagerService.broadcastIntentLocked)
    local action = intent:getAction()
    local widget = intent:getComponent() == nil and (action == 'android.appwidget.action.APPWIDGET_UPDATE' or
            action == 'android.appwidget.action.APPWIDGET_CONFIGURE')
    if string.find(method, '^sendOrderedBroadcast') and not widget and
            (intent:getComponent() ~= nil or intent:getPackage() ~= nil) then
        -- sendOrderedBroadcast and sendOrderedBroadcastAsUser: the intent of the app, with a permission which no app
        -- holds, so Android skips the receivers of the hidden app and a result receiver gets the intent the app sent,
        -- like for an app which is not installed; the permission is the second argument, the third of
        -- sendOrderedBroadcastAsUser and of the overload with the initial code. An intent naming the app by its
        -- selector only also goes to the receivers registered for the intent itself, of any app, and a widget
        -- broadcast to the receivers of the app itself, which the permission would skip, so they are a copy.
        -- shortcut: a sticky broadcast cannot have a permission, so sendStickyOrderedBroadcast and a selector keep the
        -- copy, which their result receiver and the sticky broadcasts Android keeps get; a hook of the delivery to the
        -- result receiver (LoadedApk.ReceiverDispatcher) is needed for those.
        local permission = 1
        if method == 'sendOrderedBroadcastAsUser' or type(param:getArgument(1)) == 'number' then
            permission = 2
        end
        param:setArgument(permission, packageName .. ':hidden')
    else
        -- Screens, services and other broadcasts: a copy, the intent of the app stays as it is; after gives the app
        -- what Android changes in the intent of a screen (see giveback).
        -- shortcut: a sticky broadcast which Android keeps has the name with :hidden, which any app can read
        -- (registerReceiver without a receiver) until the app removes it (removeStickyBroadcast, which gets the same
        -- copy) or the device restarts; upgrade when an app is seen reading the sticky broadcasts of other apps.
        -- shortcut: Android matches the receivers of a broadcast by the intent, not its selector, and delivers the
        -- selector too, so the receivers of a broadcast naming the hidden app only by its selector, of the app itself
        -- and of other apps, also those Doppel does not restrict, get the selector with the name with :hidden; needs
        -- the hook of the delivery the shortcut of ordered broadcasts names (LoadedApk.ReceiverDispatcher)
        local copy = renamed(intent, packageName)
        param:setArgument(index, copy)
        if string.find(method, 'Activity') then
            table.insert(names.screens, { intent, copy })
        end
    end
    return true, packageName
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped;
-- the same function as in package_probe
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

-- Android answered for a package which cannot exist: an exception, like the ActivityNotFoundException of a screen
-- with the intent in its message (Instrumentation.checkStartActivityResult), has the name the app asked for, with
-- the same class, stack trace and cause, like in package_probe. The text of an intent leaves out the package
-- in front of a class of the package of its component (ComponentName.flattenToShortString). The intent of a screen
-- gets what Android changed in its copy first, whatever Android answered (see giveback); extras of a parcel which
-- the app cannot read stop it, but Android could not change those either.
function after(hook, param)
    local names = table.remove(calls) or {}
    for _, screen in ipairs(names.screens or {}) do
        pcall(giveback, screen[1], screen[2])
    end
    local ex = param:getException()
    if #names == 0 or ex == nil or ex:getMessage() == nil then
        return false
    end

    local message = ex:getMessage()
    for _, name in ipairs(names) do
        message = replace(message, 'cmp=' .. name .. ':hidden/' .. name .. '.', 'cmp=' .. name .. '/.')
        message = replace(message, name .. ':hidden', name)
    end
    if message == ex:getMessage() then
        return false
    end

    local ok, fake = pcall(function()
        local made = luajava.new(ex:getClass(), message)
        made:setStackTrace(ex:getStackTrace())
        if ex:getCause() ~= nil then
            made:initCause(ex:getCause())
        end
        return made
    end)
    if ok and fake:getMessage() == message then
        param:setResult(fake)
    else
        -- An exception without a constructor with a message, of which LuaJ calls the constructor without arguments
        -- instead, or of which the constructor sets the cause, gets the message itself; LuaJ sees only the fields
        -- a class declares itself, so the field of Throwable is looked up through java.lang.Class
        pcall(function()
            local field = luajava.bindClass('java.lang.Class').getDeclaredField(
                    luajava.bindClass('java.lang.Throwable'), 'detailMessage')
            field:setAccessible(true)
            field:set(ex, message)
        end)
    end
    return false
end
