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
-- fake, when the system sets the defaults again on a configuration change. Languages which are not languages of the
-- device stay first, like the language the user chose for the app (Android 13) or the app set itself.
-- The configuration of the resources stays, it selects the language of the app's texts.

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

-- The languages of the device, without the one the user chose for the app; nil when not known
function systemtags(param)
    local ok, tags = pcall(function()
        return param:unhooked(function()
            if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 33 then
                return tagsof(param:getApplicationContext():getSystemService('locale'):getSystemLocales())
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

-- Only the properties run the script of the property hooks (see XCore.getFilter)
function filter(hook)
    if hook:getClassName() ~= 'android.os.SystemProperties' then
        return nil
    end
    local keys = {}
    for key, _ in pairs(PROPERTIES) do
        keys[#keys + 1] = key
    end
    return keys
end

-- The setters of the defaults: LocaleList.setDefault(list) and setDefault(list, index) of the system, with the index
-- of the language the app has texts for, and Locale.setDefault(locale) and setDefault(category, locale)
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
        -- Without asking the system for the list, which LocaleList.setDefault sets itself
        if old == table.concat(fake, ',') then
            return false
        end
        local merged = merge(current, systemtags(param), fake)
        local new = table.concat(merged, ',')
        if old == new then
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
    local fakeTag = merge({ tag }, systemtags(param), fake)[1]
    if fakeTag == tag then
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
    end
    return false
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
