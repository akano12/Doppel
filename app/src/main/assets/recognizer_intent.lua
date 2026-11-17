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

-- The screen of a speech recognizer (RecognizerIntent) or of a sound recorder (RECORD_SOUND_ACTION of MediaStore)
-- records in the process of its own app, with the permission of that app, and gives the app the text or the recording,
-- in the result or, for a recognizer, through the PendingIntent of EXTRA_RESULTS_PENDINGINTENT. It is not found, like
-- on a device without one: starting it throws ActivityNotFoundException, sending a PendingIntent of it starts nothing,
-- like one of a missing screen (send() throws CanceledException from Android 9 and returns on Android 8.1, which
-- ignores the result of the start), a chooser around it offers none, and resolveActivity, queryIntentActivities and
-- queryIntentActivityOptions find none. An intent with a selector counts by its own action too, because Android
-- resolves the selector but delivers the intent. Android itself gets a copy of the intent which only the app itself
-- can handle, like component_hidden does for hidden apps, so it answers with its own exception and stack trace: an
-- intent which names no app gets the package of the app, so a screen of the app itself still starts, and one which
-- names another app gets a name which cannot exist, with a colon. The exception then gets the text of the intent of
-- the app. A fill-in (Intent.fillIn), which the app gives when it sends a PendingIntent or starts one by
-- startIntentSender, gets the package of the app and no selector when it has the action of such a screen, so the
-- filled intent stays in the app, and a PendingIntent of such a screen takes no component, package or selector of a
-- fill-in.
-- shortcut: the hidden lookups for a user, getActivityInfo of a recognizer named by its component, an explicit intent
-- of a recognizer without its action, the chooser targets of direct share, a PendingIntent which names another app
-- (also by its selector) and gets the action of a recognizer by a fill-in, a fill-in by another app,
-- and a PendingIntent of a recognizer made before the app was restricted, which Android still gives for the copy with
-- the selector, because a PendingIntent is found without its selector (FLAG_CANCEL_CURRENT would cancel the app's own
-- earlier one), still reach or find the recognizers, and a recognizer of the app itself sees the package of the app
-- in the intent; upgrade when an app is seen using one of these
-- shortcut: the recognition services (SpeechRecognizer.isRecognitionAvailable, queryIntentServices of
-- RecognitionService) are still found while their screens are not, which a device with a service and no screen shows
-- as well; hide them too when SpeechRecognizer answers like a device without a recognition service
local ACTIONS = {
    ['android.speech.action.RECOGNIZE_SPEECH'] = true,
    ['android.speech.action.WEB_SEARCH'] = true,
    ['android.speech.action.VOICE_SEARCH_HANDS_FREE'] = true,
    ['android.provider.MediaStore.RECORD_SOUND'] = true
}

-- The argument with the intent, or the intents, where it is not the first one
local INTENT = { execStartActivity = 4, execStartActivitiesAsUser = 4, startActivity = 1, queryIntentActivityOptions = 2,
    getActivity = 2, getActivities = 2, startIntentSenderForResultInner = 3, startIntentSender = 1, send = 2,
    sendAndReturnResult = 2, sendIntent = 2 }

-- The calls which take several intents, and those which make a PendingIntent, whose implicit intents stay implicit
local SEVERAL = { execStartActivitiesAsUser = true, getActivities = true }
local PENDING = { getActivity = true, getActivities = true }

-- The calls which send a PendingIntent with a fill-in
local FILLIN = { startIntentSenderForResultInner = true, startIntentSender = true, send = true,
    sendAndReturnResult = true, sendIntent = true }

-- The flags of Intent.fillIn which let a fill-in name another app: FILL_IN_COMPONENT, FILL_IN_PACKAGE, FILL_IN_SELECTOR
local NAMES = 8 + 16 + 64

-- The extras of a chooser (Intent.createChooser) with the screens it offers: the one intent, and the arrays
local CHOOSER = 'android.intent.action.CHOOSER'
local OFFERED = 'android.intent.extra.INTENT'
local OFFERS = { 'android.intent.extra.INITIAL_INTENTS', 'android.intent.extra.ALTERNATE_INTENTS' }
local REFERRER = 'android.intent.extra.REFERRER'

-- The changes of the texts of the exceptions of the calls in progress on this thread, for after, which comes in
-- reverse order, with the intent of a screen and its copy
local calls = {}

-- The intent Android resolves: the selector of an intent without a component, with the package of the selector
-- (PackageManagerService.queryIntentActivitiesInternal and IntentResolver.buildResolveList, the same on Android 8.1
-- and 14)
function resolved(intent)
    if intent:getComponent() == nil and intent:getSelector() ~= nil then
        return intent:getSelector()
    end
    return intent
end

-- A copy of an intent of a recognizer of another app which Android finds nothing for, with the changes which give the
-- text of the exception of Android the intent of the app; nil for other intents, also for those of the app itself
-- and the copies of component_hidden, named with :hidden, which find nothing already. An implicit intent which stays
-- implicit gets the package in a selector instead, because Android refuses a mutable PendingIntent of an implicit
-- intent from Android 14 (PendingIntent.isNewMutableDisallowedImplicitPendingIntent) and would no longer.
function renamed(param, intent, implicit)
    local asked = (intent ~= nil and resolved(intent) or nil)
    if asked ~= nil and asked:getAction() == CHOOSER then
        return chooser(param, intent)
    end
    if asked == nil or not (ACTIONS[asked:getAction()] or ACTIONS[intent:getAction()]) then
        return nil
    end
    local own = param:getPackageName()
    local component = asked:getComponent()
    local name = (component ~= nil and component:getPackageName() or asked:getPackage())
    if name == own or (name ~= nil and string.sub(name, -7) == ':hidden') then
        return nil
    end

    local copy = intent:clone()
    local target = resolved(copy)
    if name == nil then
        if implicit and target == copy then
            local selector = copy:cloneFilter()
            selector:setPackage(own)
            copy:setSelector(selector)
            return copy, nil
        end
        target:setPackage(own)
        -- The package comes without a space in the text of a selector with nothing else (Intent.toShortString)
        return copy, { { 'sel=pkg=' .. own, 'sel=' }, { ' pkg=' .. own, '' } }
    end
    if component ~= nil then
        target:setComponent(luajava.newInstance('android.content.ComponentName', name .. ':hidden',
                component:getClassName()))
    else
        target:setPackage(name .. ':hidden')
    end
    -- The text of an intent leaves out the package in front of a class of the package of its component
    -- (ComponentName.flattenToShortString), the text of an explicit screen does not
    return copy, { { 'cmp=' .. name .. ':hidden/' .. name .. '.', 'cmp=' .. name .. '/.' }, { name .. ':hidden', name } }
end

-- A copy of a fill-in with the action of a recognizer, also in its selector, with the package of the app and no
-- selector, because Intent.fillIn takes the package only into an intent without a package and a selector, and a
-- component of another app which cannot exist, because the component of a fill-in comes before the package; nil for
-- other fill-ins and for those which stay in the app already, like the copy of a call which another hooked one makes
function filled(param, intent)
    local selector = (intent ~= nil and intent:getSelector() or nil)
    if intent == nil or not (ACTIONS[intent:getAction()] or (selector ~= nil and ACTIONS[selector:getAction()])) then
        return nil
    end
    local own = param:getPackageName()
    local component = intent:getComponent()
    local name = (component ~= nil and component:getPackageName() or nil)
    local stays = (name == nil or name == own or string.sub(name, -7) == ':hidden')
    if selector == nil and intent:getPackage() == own and stays then
        return nil
    end

    local copy = intent:clone()
    copy:setSelector(nil)
    copy:setPackage(own)
    if not stays then
        copy:setComponent(luajava.newInstance('android.content.ComponentName', name .. ':hidden',
                component:getClassName()))
    end
    return copy
end

-- A copy of a chooser which offers recognizers of other apps, with copies of those which the chooser finds nothing
-- for, so it offers none of them, like on a device without one; nil for other choosers. Android starts the chooser
-- anyway, so no text changes. The extras are read from the copy, so the bundle of the app is not unparcelled, and
-- a chooser whose extras cannot be read, or which offers something other than intents, stays: ChooserActivity
-- refuses those itself.
function chooser(param, intent)
    local copy = intent:clone()
    local ok, offered = pcall(function()
        local extras = luajava.newInstance('android.os.Bundle')
        local screen = renamed(param, copy:getParcelableExtra(OFFERED))
        if screen ~= nil then
            extras:putParcelable(OFFERED, screen)
        end
        for _, name in ipairs(OFFERS) do
            local screens = copies(param, copy:getParcelableArrayExtra(name), 'android.os.Parcelable')
            if screens ~= nil then
                extras:putParcelableArray(name, screens)
            end
        end
        return extras
    end)
    if not ok or offered:isEmpty() then
        return nil
    end
    copy:putExtras(offered)
    return copy, nil
end

-- A copy of an array or a list of intents with a copy of each recognizer and the others as they are, and the
-- changes of the first one, which Android names in its exception; nil when none is a recognizer
function copies(param, intents, class, implicit)
    if intents == nil then
        return nil
    end
    local list = param:isInstance(intents, 'java.util.List')
    local length = (list and intents:size() or intents['length'])
    local made, first = nil, nil
    for position = 1, length do
        local intent = nil
        if list then
            intent = intents:get(position - 1)
        else
            intent = intents[position]
        end
        local copy, changes = renamed(param, intent, implicit)
        if copy ~= nil then
            if made == nil and list then
                made = luajava.newInstance('java.util.ArrayList', intents)
            elseif made == nil then
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                made = arrayClass:newInstance(luajava.bindClass(class), length)
                for other = 1, length do
                    made[other] = intents[other]
                end
            end
            if list then
                made:set(position - 1, copy)
            else
                made[position] = copy
            end
            if position == 1 then
                first = changes
            end
        end
    end
    return made, first
end

function before(hook, param)
    local method = hook:getMethodName()
    -- The frame of the call goes on the stack for after first, so after takes its own frame whatever fails below
    local frame = {}
    table.insert(calls, frame)
    local index = INTENT[method] or 0
    local argument = param:getArgument(index)

    -- A fill-in of a PendingIntent, for which Android names no intent in its exceptions
    if FILLIN[method] then
        local copy = filled(param, argument)
        if copy == nil then
            return false
        end
        param:setArgument(index, copy)
        return true
    end

    -- PackageManager.queryIntentActivityOptions: Android lists the specifics first, also one named by its component
    local restricted = false
    if method == 'queryIntentActivityOptions' then
        local specifics = copies(param, param:getArgument(1), 'android.content.Intent')
        if specifics ~= nil then
            param:setArgument(1, specifics)
            restricted = true
        end
    end

    -- Several screens (execStartActivitiesAsUser, PendingIntent.getActivities): a copy of the array
    if SEVERAL[method] then
        local screens, changes = copies(param, argument, 'android.content.Intent', PENDING[method])
        if screens == nil then
            return false
        end
        param:setArgument(index, screens)
        frame.changes = changes
        named(param, method)
        return true
    end

    local copy, changes = renamed(param, argument, PENDING[method])
    if copy == nil then
        return restricted
    end
    param:setArgument(index, copy)
    frame.changes, frame.intent, frame.copy = changes, argument, copy
    named(param, method)
    return true
end

-- A PendingIntent of a recognizer takes no name of another app from a fill-in, see NAMES
function named(param, method)
    if PENDING[method] then
        param:setArgument(3, bit32.band(param:getArgument(3), bit32.bnot(NAMES)))
    end
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped;
-- the same function as in package_probe
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

-- The intent of a screen gets the referrer Android put in its copy (Instrumentation.execStartActivity), and only
-- that, so the copies a chooser offers stay out of the intent of the app, and the exception of Android gets the text
-- of the intent of the app, with the same class, stack trace and cause, like in component_hidden
function after(hook, param)
    local frame = table.remove(calls) or {}
    -- Extras of a parcel which the app cannot read stop it, but Android could not change those either
    pcall(function()
        if frame.copy ~= nil and frame.copy:hasExtra(REFERRER) then
            local extras = luajava.newInstance('android.os.Bundle')
            extras:putParcelable(REFERRER, frame.copy:getParcelableExtra(REFERRER))
            frame.intent:putExtras(extras)
        end
    end)
    local ex = param:getException()
    if frame.changes == nil or ex == nil or ex:getMessage() == nil then
        return false
    end

    local message = ex:getMessage()
    for _, change in ipairs(frame.changes) do
        message = replace(message, change[1], change[2])
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
        -- An exception without a constructor with a message gets the message itself, like in component_hidden
        pcall(function()
            local field = luajava.bindClass('java.lang.Class').getDeclaredField(
                    luajava.bindClass('java.lang.Throwable'), 'detailMessage')
            field:setAccessible(true)
            field:set(ex, message)
        end)
    end
    return false
end
