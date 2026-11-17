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

-- Fake languages of the device: setting value.locale, a list like en-US,de-DE, default en-US.
-- The defaults of the process are set once to the fake ones by LocaleList.setDefault, which sets all of them:
-- the default list, Locale.getDefault of both categories, android.icu.util.ULocale.getDefault and the ICU of native
-- code; the getters themselves, called for each String.format and toLowerCase, are not hooked. The setters keep the
-- fake, when the system sets the defaults again on a configuration change; what the app sets itself stays, like on
-- a device without the hooks, except a list with all the languages of the device and the first of them as the locale
-- (Locale.setDefault, see holds); on a device with one language that is any list of that language alone, also one
-- which the app sets to test the hooks or the user picks in the app, on every version. The languages the user chose for
-- the app (Android 13) and languages which are not languages of the device stay first. The languages of the
-- keyboard (subtypes of input methods) are only those of the fake languages, also in the secure settings (see
-- settingssecure_getstring). The configuration of the resources, which apps read (getConfiguration, its locale and
-- getLocales, also of Resources.getSystem) and which selects the language of the app's texts, has the same languages,
-- so the texts are in a fake language unless a language was chosen for the app, like on a device with the fake
-- languages; so has the configuration of a change the app is told of (onConfigurationChanged of the application,
-- services, providers, activities and registered callbacks, and the changes of the multi-window and picture-in-picture
-- modes); so do the configurations Activity.dump prints (see dumped); the broadcast of a change of the languages does
-- not reach the app (see receiver_skip).
-- shortcut: the configuration of the process, which only hidden methods give, the one of a change a window context
-- (Context.createWindowContext) is told of, and the one an activity was last told of until its first change (see
-- reported), read by reflection, keep the languages of the device; a compiled caller can have the small
-- performConfigurationChanged inline, which leaves the application, services and providers with them too; upgrade by
-- the configuration the system sends to the process when a probe shows either.
-- shortcut: the hooks of the configuration of a change and of Activity.dump run the app's own code
-- (onConfigurationChanged, onMultiWindowModeChanged, onPictureInPictureModeChanged, the dump of its fragments) below
-- frames of Doppel, which a stack trace taken there shows, like the hooks of handleReceiver; the dump of the app's
-- fragments and dumpables gets a PrintWriter of Doppel instead of the writer given, which gets the whole dump at once,
-- by one print at the end; the lists of the languages of the device are replaced in the whole dump, also in the text
-- the app prints itself there (its fragments, dumpables, views and messages), so an app which prints a list it guesses
-- gets the fake one back, which tells the hooks and confirms the guess, also when it labels it like a line of the
-- framework; and after the first change of an activity the system finds a change of the languages on each change
-- (freeTextLayoutCachesIfNeeded), which only costs the time to clear the caches of text layouts; upgrade when a place
-- is found where the configuration is reported before the app's code runs, for activities too.

-- The fake languages, normalized, without tags which are no language (und) and without repeats,
-- which LocaleList does not allow
function fakes(param)
    local locale = luajava.bindClass('java.util.Locale')
    local fake = {}
    local seen = { und = true }
    for _, item in ipairs(split(param:getSetting('value.locale') or '')) do
        local tag = locale:forLanguageTag(item):toLanguageTag()
        if not seen[tag] then
            seen[tag] = true
            fake[#fake + 1] = tag
        end
    end
    if #fake == 0 then
        fake = { 'en-US' }
    end
    return fake
end

-- The items of a comma separated list
function split(text)
    local items = {}
    for item in string.gmatch(text, '[^,%s]+') do
        items[#items + 1] = item
    end
    return items
end

function tagsof(list)
    return split(list:toLanguageTags())
end

function listof(tags)
    return luajava.bindClass('android.os.LocaleList'):forLanguageTags(table.concat(tags, ','))
end

-- The languages of the device, without the ones the user chose for the app, which can be languages of the device
-- too and stay first (Android 13); nil when not known
function systemtags(param)
    local ok, tags = pcall(function()
        return param:unhooked(function()
            if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 33 then
                local manager = param:getApplicationContext():getSystemService('locale')
                local known, app = pcall(function()
                    return tagsof(manager:getApplicationLocales())
                end)
                local chosen = {}
                for _, tag in ipairs(known and app or {}) do
                    chosen[tag] = true
                end
                local device = {}
                for _, tag in ipairs(tagsof(manager:getSystemLocales())) do
                    if not chosen[tag] then
                        device[#device + 1] = tag
                    end
                end
                return device
            end
            -- Before Android 13 the configuration of the process has the languages of the device, which the one of the
            -- resources of the system had until it was faked (see configured)
            local known = param:getValue('locale.device', nil)
            if known ~= nil and known ~= '' then
                return split(known)
            end
            local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
            return tagsof(resources:getConfiguration():getLocales())
        end)
    end)
    return (ok and tags or nil)
end

-- The languages of the app first, which are not languages of the device, then the fake ones
function merge(current, system, fake)
    local skip = {}
    for _, tag in ipairs(system or current) do
        skip[tag] = true
    end
    for _, tag in ipairs(fake) do
        skip[tag] = true
    end
    local merged = {}
    for _, tag in ipairs(current) do
        if not skip[tag] then
            skip[tag] = true
            merged[#merged + 1] = tag
        end
    end
    for _, tag in ipairs(fake) do
        merged[#merged + 1] = tag
    end
    return merged
end

function indexof(tags, tag)
    for index, item in ipairs(tags) do
        if item == tag then
            return index
        end
    end
    return nil
end

-- The tags with one of the fake ones first, the language of the app's texts which the system puts first
-- (updateLocaleListFromAppContext), so Locale.getDefault is the first language of the configuration, like on a device
-- with the fake languages; the tags as they are for another one
function front(tags, tag, fake)
    local index = indexof(tags, tag)
    if index == nil or index == 1 or indexof(fake, tag) == nil then
        return tags
    end
    local moved = { tag }
    for at, item in ipairs(tags) do
        if at ~= index then
            moved[#moved + 1] = item
        end
    end
    return moved
end

-- System properties of the language (from the first fake language) and of the region of sale, the country the device
-- was sold in (Samsung, Xiaomi); these are empty, like on a device without them
local PROPERTIES = {
    ['persist.sys.locale'] = 'tag', ['ro.product.locale'] = 'tag',
    ['persist.sys.language'] = 'language', ['ro.product.locale.language'] = 'language',
    ['persist.sys.country'] = 'region', ['ro.product.locale.region'] = 'region',
    ['ro.csc.country_code'] = '', ['ro.csc.countryiso_code'] = '', ['ro.csc.sales_code'] = '',
    ['ro.miui.region'] = '', ['ro.vendor.miui.cust_variant'] = '', ['ro.omc.region'] = '',
}

function fakeproperty(param, key)
    local kind = PROPERTIES[key]
    if kind == nil or kind == '' then
        return kind
    end
    local tag = fakes(param)[1]
    if kind == 'tag' then
        return tag
    end
    local locale = luajava.bindClass('java.util.Locale'):forLanguageTag(tag)
    return (kind == 'language' and locale:getLanguage() or locale:getCountry())
end

-- What get gives for a property which is not set or empty: the default of get(key, default) (the variants with
-- .default/), else ''. Like systemproperties_get, which a test compares.
function unset(hook, param)
    if string.find(hook:getName(), '.default/', 1, true) then
        return param:getArgument(1)
    end
    return ''
end

-- Whether a property is set: the getters with a default give it for a property which is not set, but also when the
-- property has that value, so an app could confirm a guess, like of the model or ro.debuggable, by passing it as the
-- default. get(key) without the hooks gives '' when it is not set. An error counts as set, which fakes the value.
function isset(param, key)
    local ok, value = pcall(param.unhooked, param, function()
        return luajava.bindClass('android.os.SystemProperties'):get(key)
    end)
    return not ok or value ~= ''
end

-- Only the properties run the script of the property hooks and the property areas the one of the hooks of opening files
-- (see XCore.getFilter)
function filter(hook)
    if hook:getMethodName() == 'open' then
        return { '/dev/__properties__/*' }
    elseif hook:getClassName() ~= 'android.os.SystemProperties' then
        return nil
    end
    local keys = {}
    for key, _ in pairs(PROPERTIES) do
        keys[#keys + 1] = key
    end
    return keys
end

-- The classes on the stack (see bysystem)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Whether the system sets the defaults: once the hooks are installed it does so only in updateLocaleListFromAppContext,
-- of ActivityThread up to Android 11 and of ConfigurationController from Android 12, with the list of the
-- configuration; Locale.setDefault it calls through LocaleList.setDefault(list, index) only. An app which sets a
-- language gets it back from getDefault, like on a device without the hooks, which would else tell the hooks and,
-- by the languages they replace, the languages of the device.
function bysystem()
    return onstack('updateLocaleListFromAppContext', { ['android.app.ActivityThread'] = true,
        ['android.app.ConfigurationController'] = true })
end

-- Whether a method of one of the classes is on the stack. A hooked method which runs, like LocaleList.setDefault when
-- it calls Locale.setDefault, has a frame of its own class; only the frame of a hook can have a class of the hook
-- engine (see features.lua).
function onstack(method, classes)
    local frames = callers()
    for index = 1, frames['length'] do
        if frames[index]:getMethodName() == method and classes[frames[index]:getClassName()] then
            return true
        end
    end
    return false
end

-- Whether a list has all the languages of the device, like the lists apps make from their configuration, which has
-- them all: AppCompat before Android 13 sets the languages chosen in the app followed by those of the configuration,
-- or those of the configuration alone when none are chosen. Such a list is faked like one of the system, else the
-- default would have the languages of the device; so is Locale.setDefault of the first language of the device, like
-- apps set the locale of their configuration, else LocaleList.getDefault, which starts with Locale.getDefault, would
-- have it first. The same for the configurations of the resources (see configured). shortcut: an app which sets all the
-- languages of the device, or the first one as its locale, gets the fake ones back, which tells the hooks; on a device
-- with one language any list of that language alone is faked, also one the app sets to test the hooks and one of a
-- language picker of the app (not AppCompat) on every version, which then formats numbers and dates and shows its
-- texts in the fake language; upgrade by telling the lists of the system by the configuration it sends.
function holds(tags, system)
    if system == nil or #system == 0 then
        return false
    end
    local has = {}
    for _, tag in ipairs(tags) do
        has[tag] = true
    end
    for _, tag in ipairs(system) do
        if not has[tag] then
            return false
        end
    end
    return true
end

-- The setters of the defaults: LocaleList.setDefault(list) and setDefault(list, index) of the system, with the index
-- of the language the app has texts for, and Locale.setDefault(locale) and setDefault(category, locale), which keep the
-- fake when the hook of setDefault(list, index), a hidden method, is not there
function before(hook, param)
    local method = hook:getMethodName()
    if method == 'updateConfiguration' or method == 'updateConfigurationImpl' then
        return configured(param, 0)
    elseif method == 'performConfigurationChanged' or method == 'dispatchMultiWindowModeChanged' or
            method == 'dispatchPictureInPictureModeChanged' then
        return configured(param, 1)
    elseif method == 'performActivityConfigurationChanged' then
        if not reported(hook, param) then
            return false
        end
        return configured(param, 1)
    elseif method == 'dumpInner' then
        return dumping(param)
    elseif method ~= 'setDefault' then
        return false
    end
    local fake = fakes(param)
    local count = hook:getParameterTypes()['length']

    if hook:getClassName() == 'android.os.LocaleList' then
        local list = param:getArgument(0)
        if list == nil then
            return false
        end
        local current = tagsof(list)
        local old = table.concat(current, ',')
        -- Without asking the system for the list, which LocaleList.setDefault sets itself; an index out of the list
        -- throws, like on a device without the hooks
        if old == table.concat(fake, ',') or (count == 2 and current[param:getArgument(1) + 1] == nil) then
            return false
        end
        local system = systemtags(param)
        local merged = merge(current, system, fake)
        if count == 1 then
            merged = front(merged, current[1], fake)
        end
        local new = table.concat(merged, ',')
        if old == new or not (bysystem() or holds(current, system)) then
            return false
        end
        param:setArgument(0, listof(merged))
        if count == 2 then
            local index = indexof(merged, current[param:getArgument(1) + 1])
            param:setArgument(1, index == nil and 0 or index - 1)
        end
        return true, old, new
    end

    local locale = param:getArgument(count - 1)
    if locale == nil then
        return false
    end
    local tag = locale:toLanguageTag()
    if indexof(fake, tag) ~= nil then
        return false
    end
    -- The first language of the device as the locale of the app is faked like a list with all of them (see holds),
    -- but not when LocaleList.setDefault sets the locale of the list it is given, which its own hook chose
    local system = systemtags(param)
    local fakeTag = merge({ tag }, system, fake)[1]
    local first = (system ~= nil and tag == system[1] and
            not onstack('setDefault', { ['android.os.LocaleList'] = true }))
    if fakeTag == tag or not (first or bysystem()) then
        return false
    end
    param:setArgument(count - 1, luajava.bindClass('java.util.Locale'):forLanguageTag(fakeTag))
    return true, tag, fakeTag
end

-- The configuration a ResourcesImpl gets, when made and on configuration changes, with the languages of the defaults
-- (see merge), as a copy, since the caller keeps its own, like the configuration of the process; it then puts the
-- language of the app's texts first and gives it to the AssetManager. Only a list with all the languages of the device
-- (see holds), which the configurations of the system have, so a language the app chose for a context stays. Before
-- Android 13 the system gives the configuration of the process to the resources of the system first
-- (Resources.updateSystemConfiguration), which keeps its languages as those of the device (see systemtags), but not one
-- without languages, like the one of a change of the assets. shortcut: when the languages of the device are not known
-- the configuration stays, since a language the app chose could not be told apart; upgrade when that is seen.
-- The same for the configuration of a change the app is told of, the argument at the index.
function configured(param, index)
    local config = param:getArgument(index)
    if config == nil then
        return false
    end
    local old = table.concat(tagsof(config:getLocales()), ',')
    if old ~= '' and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 and
            onstack('updateSystemConfiguration', { ['android.content.res.Resources'] = true }) then
        param:putValue('locale.device', old, nil)
    end
    local copy, new = fakeconfig(param, config)
    if copy == nil then
        return false
    end
    param:setArgument(index, copy)
    return true, old, new
end

-- A copy of a configuration with the languages of the defaults and those languages, nil when it keeps its own
function fakeconfig(param, config)
    local current = tagsof(config:getLocales())
    local old = table.concat(current, ',')
    local fake = fakes(param)
    if old == table.concat(fake, ',') then
        return nil
    end
    local system = systemtags(param)
    local merged = merge(current, system, fake)
    local new = table.concat(merged, ',')
    if old == new or not holds(current, system) then
        return nil
    end
    local copy = luajava.newInstance('android.content.res.Configuration', config)
    copy:setLocales(listof(merged))
    return copy, new
end

-- The configuration an activity was last told of (Activity.mCurrentConfig), which the system compares with the one of
-- a change to tell whether to tell the activity, gets the languages of the defaults too: from the launch it has those
-- of the device, so each change would seem to change the languages as well, and an activity which does not handle that
-- would no longer be told of any change. The fields are listed like LuaJ lists them, which Android does not report as
-- a use of a hidden API, unlike getDeclaredField (see features.lua). Whether the field is there; without it the
-- configuration of the change stays, like the activity's.
function reported(hook, param)
    local ok, found = pcall(function()
        local activity = param:getArgument(0)
        if hook:getParameterTypes()[1] ~= 'android.app.Activity' then
            -- From Android 14 the record of the activity
            local field = declared(luajava.bindClass('java.lang.Object').getClass(activity), 'activity')
            activity = field and field:get(activity)
        end
        local field = activity and declared(luajava.bindClass('android.app.Activity'), 'mCurrentConfig')
        if not field then
            return false
        end
        local config = field:get(activity)
        local copy = config and fakeconfig(param, config)
        if copy ~= nil then
            field:set(activity, copy)
        end
        return true
    end)
    return ok and found
end

-- Activity.dump prints the configurations of the activity: the one it was last told of, with the languages of the
-- device until its first change (see reported), and from Android 14 the ones of its window (ViewRootImpl), of which
-- the one from the system has them always. The dump goes to a buffer first, then to the writer with each list of the
-- languages of the device, as LocaleList prints it, replaced by the fake ones, which makes a list with a language
-- chosen for the app before them the same as the configuration of the resources (see merge). The buffer is kept with
-- the writer, which is left when it cannot be made.
function dumping(param)
    local writer = param:getArgument(2)
    if writer == nil then
        return false
    end
    pcall(function()
        local buffer = luajava.newInstance('java.io.StringWriter')
        local printer = luajava.newInstance('java.io.PrintWriter', buffer)
        param:putValue('locale.writer', writer, printer)
        param:putValue('locale.buffer', buffer, printer)
        param:setArgument(2, printer)
    end)
    return false
end

-- After the dump, also one which failed: the buffer to the writer, as it is when the languages cannot be replaced
function dumped(param)
    local printer = param:getArgument(2)
    local writer = printer and param:getValue('locale.writer', printer)
    if writer == nil then
        return false
    end
    printer:flush()
    local text = param:getValue('locale.buffer', printer):toString()
    local ok, new, old, fake = pcall(dumptext, param, text)
    writer:print(ok and new or text)
    if not ok or new == text then
        return false
    end
    return true, old, fake
end

-- What Configuration.toString prints between the list of the languages and the direction of the layout: nothing, or
-- from Android 14 the grammatical gender
local GENDERS = { '', ' neuter', ' feminine', ' masculine', ' ?grgend' }

-- The text with each whole list of the languages of the device, or the end of a list which starts with others,
-- replaced by the fake ones, and both lists as LocaleList prints them (Locale.toString, like de_DE). A whole list
-- gets the direction of the layout of the first fake language too, which Configuration.toString prints after it and
-- setLocales sets from its first language; after a language chosen for the app it stays that of the chosen one.
function dumptext(param, text)
    local system = systemtags(param)
    if system == nil or #system == 0 then
        return text
    end
    local locale = luajava.bindClass('java.util.Locale')
    local function printed(tags)
        local names = {}
        for _, tag in ipairs(tags) do
            names[#names + 1] = locale:forLanguageTag(tag):toString()
        end
        return table.concat(names, ',')
    end
    local tags = fakes(param)
    local old, fake = printed(system), printed(tags)
    if old == fake then
        return text
    end
    local direction = (rtl(tags[1]) and ' ldrtl' or ' ldltr')
    local parts, from = {}, 1
    while true do
        local first, last = string.find(text, old, from, true)
        if first == nil then
            break
        end
        local left, right = text:sub(first - 1, first - 1), text:sub(last + 1, last + 1)
        local whole = (left == '[' or left == ',') and right == ']'
        parts[#parts + 1] = text:sub(from, first - 1) .. (whole and fake or old)
        from = last + 1
        if whole and left == '[' then
            for _, gender in ipairs(GENDERS) do
                local at = last + 2 + #gender
                local token = text:sub(at, at + 5)
                if text:sub(last + 2, at - 1) == gender and (token == ' ldltr' or token == ' ldrtl') then
                    parts[#parts + 1] = ']' .. gender .. direction
                    from = at + 6
                    break
                end
            end
        end
    end
    parts[#parts + 1] = text:sub(from)
    return table.concat(parts), old, fake
end

-- Whether the layout of a language goes from right to left, as Configuration.setLayoutDirection takes it, also when
-- the developer option forces it
function rtl(tag)
    local locale = luajava.bindClass('java.util.Locale'):forLanguageTag(tag)
    return luajava.bindClass('android.text.TextUtils'):getLayoutDirectionFromLocale(locale) == 1
end

-- A field the class declares, accessible, nil when it is not listed
function declared(class, name)
    -- Lua sees a Class as its static members, so the methods of Class get the class as the object
    local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(class)
    for index = 1, fields['length'] do
        if fields[index]:getName() == name then
            fields[index]:setAccessible(true)
            return fields[index]
        end
    end
    return nil
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == '#sLastExplicitlySetLocaleList' then
        -- When the hooks are installed: the system set the defaults already
        return push(param)
    elseif method == 'dumpInner' then
        return dumped(param)
    elseif method == 'getSystemLocales' then
        -- LocaleManager.getSystemLocales, the languages of the device without the one of the app (Android 13)
        local result = param:getResult()
        local fake = fakes(param)
        if result == nil or table.concat(tagsof(result), ',') == table.concat(fake, ',') then
            return false
        end
        param:setResult(listof(fake))
        return true, result:toLanguageTags(), table.concat(fake, ',')
    elseif method == 'getClientDefaultLanguage' then
        -- The default language of text to speech, which the engine, an app which is not restricted, gives from its
        -- own default language, one of the device, unless the user chose one for it in the settings; it is
        -- what TextToSpeech.getDefaultLanguage and getDefaultVoice give and the language it speaks when the app
        -- sets none. An engine with an empty language, which "Use system language" in the settings stores, gives the
        -- language of the device too. shortcut: a language chosen for any engine keeps the answer, also of an engine
        -- without one, which gives the language of the device then; upgrade by the engine the app is bound to.
        local result = param:getResult()
        if result == nil or result['length'] ~= 3 or string.find(ttslocales(param), ':[^,]') ~= nil then
            return false
        end
        local fake = oldformat(fakes(param)[1])
        local old = tostring(result[1]) .. '-' .. tostring(result[2]) .. '-' .. tostring(result[3])
        local new = table.concat(fake, '-')
        if old == new then
            return false
        end
        local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.String'), 3)
        for index = 1, 3 do
            array[index] = fake[index]
        end
        param:setResult(array)
        return true, old, new
    elseif method == 'getEnabledInputMethodSubtypeList' then
        -- The languages of a keyboard, which the user enabled, or which the system chose by the language of the device;
        -- the secure settings which name them leave out the same ones (see settingssecure_getstring)
        local result = param:getResult()
        if result == nil then
            return false
        end
        local kept, count = typedof(result, fakes(param))
        if kept:size() == count then
            return false
        end
        param:setResult(kept)
        return true, tostring(count), tostring(kept:size())
    elseif method == 'getCurrentInputMethodSubtype' or method == 'getLastInputMethodSubtype' then
        -- The language of the keyboard in use, and the one used last. The one in use becomes the first enabled one of
        -- the keyboard with a fake language: the system gives null only for a keyboard without subtypes, which has
        -- no enabled ones either. Without one, and for the last one, null, like a keyboard without subtypes.
        local result = param:getResult()
        local fake = fakes(param)
        if result == nil or typed(result, fake) then
            return false
        end
        local other = nil
        if method == 'getCurrentInputMethodSubtype' then
            -- A failed lookup gives null, not the real language
            local manager = param:getThis()
            local ok, kept = pcall(function()
                return typedof(param:unhooked(function()
                    return manager:getEnabledInputMethodSubtypeList(nil, true)
                end), fake)
            end)
            if ok and kept:size() > 0 then
                other = kept:get(0)
            end
        end
        param:setResult(other)
        return true, subtypetag(result), (other ~= nil and subtypetag(other) or nil)
    elseif method == 'getShortcutInputMethodsAndSubtypes' then
        -- The voice keyboards and their languages: up to Android 9 a HashMap of lists, which never has a keyboard
        -- without a language, later a map of one keyboard and one language, else an empty map (Collections)
        local result = param:getResult()
        if result == nil then
            return false
        end
        local fake = fakes(param)
        local map = luajava.newInstance('java.util.HashMap')
        local total, count = 0, 0
        local entries = result:entrySet():toArray()
        for index = 1, entries['length'] do
            local kept, all = typedof(entries[index]:getValue(), fake)
            total, count = total + all, count + kept:size()
            if kept:size() > 0 then
                map:put(entries[index]:getKey(), kept)
            end
        end
        if count == total then
            return false
        end
        if map:isEmpty() and luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 29 then
            map = luajava.bindClass('java.util.Collections'):emptyMap()
        end
        param:setResult(map)
        return true, tostring(total), tostring(count)
    elseif method == 'getStringForUser' then
        -- Settings.System system_locales, the languages the user chose in the settings
        local result = param:getResult()
        if param:getArgument(1) ~= 'system_locales' or result == nil or result == '' then
            return false
        end
        local fake = table.concat(fakes(param), ',')
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif method == 'get' or method == 'native_get' then
        -- Properties which are not set stay empty, or the default of get(key, default), a fake value would tell they
        -- are faked; an empty fake is what get gives for a property which is not set
        local result = param:getResult()
        local empty = unset(hook, param)
        local fake = fakeproperty(param, param:getArgument(0))
        if fake == nil or result == nil or result == '' or (result == empty and not isset(param, param:getArgument(0))) then
            return false
        end
        if fake == '' then
            fake = empty
        end
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif method == 'open' then
        -- The same properties in the property area apps can read as a file, like get: a property with an empty value
        -- stays (see XParam.setPropertyValues); persist.sys.locale is in locale_prop from Android 14
        local keys, values = {}, {}
        for key, _ in pairs(PROPERTIES) do
            keys[#keys + 1] = key
            values[#values + 1] = fakeproperty(param, key)
        end
        local olds = param:setPropertyValues(keys, values)
        local old, new = {}, {}
        for index = 1, #keys do
            if olds[index] ~= nil then
                old[#old + 1] = olds[index]
                new[#new + 1] = values[index]
            end
        end
        if #old == 0 then
            return false
        end
        return true, table.concat(old, ','), table.concat(new, ',')
    end
    return false
end

-- The language of a subtype of a keyboard: its language tag (Android 7), else its locale like en_US; '' for none
function subtypetag(subtype)
    local tag = subtype:getLanguageTag()
    if tag == nil or tag == '' then
        tag = string.gsub(subtype:getLocale() or '', '_', '-')
    end
    return tag
end

-- Whether a subtype of a keyboard has a fake language, or none, like one for handwriting; a tag of a language alone,
-- like uk, is one of the fake ones with that language
function typed(subtype, fake)
    local tag = subtypetag(subtype)
    if tag == '' then
        return true
    end
    local locale = luajava.bindClass('java.util.Locale')
    tag = locale:forLanguageTag(tag):toLanguageTag()
    for _, item in ipairs(fake) do
        if tag == item or tag == locale:forLanguageTag(item):getLanguage() then
            return true
        end
    end
    return false
end

-- The subtypes of a list with a fake language, in an ArrayList like the lists of the system, and the size of the list
function typedof(list, fake)
    local items = list:toArray()
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if typed(items[index], fake) then
            kept:add(items[index])
        end
    end
    return kept, items['length']
end

-- A language like the engines of text to speech give it (TtsEngines.toOldLocaleStringFormat): the ISO 639-2 language,
-- the ISO 3166 country and the variant, eng USA for a language without such codes
function oldformat(tag)
    local locale = luajava.bindClass('java.util.Locale'):forLanguageTag(tag)
    local ok, codes = pcall(function()
        return { locale:getISO3Language(), locale:getISO3Country(), locale:getVariant() }
    end)
    return (ok and codes or { 'eng', 'USA', '' })
end

-- The languages the user chose for text to speech, engine:language for each engine, '' when none
function ttslocales(param)
    local ok, value = pcall(param.unhooked, param, function()
        local secure = luajava.bindClass('android.provider.Settings$Secure')
        return secure:getString(param:getApplicationContext():getContentResolver(), 'tts_default_locale')
    end)
    return (ok and value or '')
end

-- The configurations of the resources made before the hooks were installed, of the system and of the app, like
-- configured makes them, by the public method apps change their language with, without the hooks; the first language
-- of the app's then, the one of its texts, nil when not known. Not in system server, where the hooks of the
-- configurations are left out too, so its resources keep the languages of the device.
function pushconfig(param, system, fake)
    if param:getPackageName() == 'android' then
        return nil
    end
    local ok, first = pcall(param.unhooked, param, function()
        local all = { luajava.bindClass('android.content.res.Resources'):getSystem(),
                      param:getApplicationContext():getResources() }
        for _, resources in ipairs(all) do
            local config = resources:getConfiguration()
            local current = tagsof(config:getLocales())
            local merged = merge(current, system, fake)
            if holds(current, system) and table.concat(merged, ',') ~= table.concat(current, ',') then
                local copy = luajava.newInstance('android.content.res.Configuration', config)
                copy:setLocales(listof(merged))
                resources:updateConfiguration(copy, nil)
            end
        end
        return tagsof(all[2]:getConfiguration():getLocales())[1]
    end)
    return (ok and first or nil)
end

-- The defaults of the process to the fake ones, and the property user.locale, which the runtime has
-- from the start of the device (persist.sys.locale); first the configurations of the resources, of which the first
-- language of the app's becomes the locale, like the system sets it, and the languages of the device they had
function push(param)
    local fake = fakes(param)
    local device = systemtags(param)
    if device ~= nil and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 then
        param:putValue('locale.device', table.concat(device, ','), nil)
    end
    local first = pushconfig(param, device, fake)
    local localeList = luajava.bindClass('android.os.LocaleList')
    local current = tagsof(localeList:getDefault())
    local merged = front(merge(current, device, fake), first, fake)
    local old, new = table.concat(current, ','), table.concat(merged, ',')
    local system = luajava.bindClass('java.lang.System')
    local property = system:getProperty('user.locale')
    if property ~= nil and property ~= '' and property ~= fake[1] then
        system:setProperty('user.locale', fake[1])
    elseif old == new then
        return false
    end
    if old ~= new then
        localeList:setDefault(listof(merged))
    end
    return true, old, new
end
