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
-- keyboard (subtypes of input methods) are only those of the fake languages. The configuration of the resources
-- stays, it selects the language of the app's texts.

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
            -- Before Android 13 the configuration of the process has the languages of the device
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
-- have it first. shortcut: an app which sets all the languages of the device, or the first one as its locale, gets
-- the fake ones back, which tells the hooks and the languages, as its configuration does already; on a device with
-- one language any list of that language alone is faked, also one the app sets to test the hooks and one of a
-- language picker of the app (not AppCompat) on every version, which then formats numbers and dates in the fake
-- language; upgrade when the configuration is faked.
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
    if hook:getMethodName() ~= 'setDefault' then
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

function after(hook, param)
    local method = hook:getMethodName()
    if method == '#sLastExplicitlySetLocaleList' then
        -- When the hooks are installed: the system set the defaults already
        return push(param)
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
        -- The languages of a keyboard, which the user enabled, or which the system chose by the language of the device.
        -- shortcut: the ids of the enabled and the selected subtypes in the secure settings (enabled_input_methods,
        -- selected_input_method_subtype) stay, which name them by the hashCode of InputMethodInfo.getSubtypeAt;
        -- upgrade by leaving them out in settingssecure_getstring.
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

-- The defaults of the process to the fake ones, and the property user.locale, which the runtime has
-- from the start of the device (persist.sys.locale)
function push(param)
    local fake = fakes(param)
    local localeList = luajava.bindClass('android.os.LocaleList')
    local current = tagsof(localeList:getDefault())
    local merged = merge(current, systemtags(param), fake)
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
