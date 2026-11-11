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

-- The configuration of the fonts (fonts.xml, font_fallback.xml of Android 15 and fonts_customization.xml of device
-- makers) names the families of the fonts of the device maker, like sec-roboto-light of Samsung, which the font files
-- of hardware_files and SystemFonts (system_fonts) leave out: the app reads it without the families of fonts which are
-- not of every Android (FONTS, which a test compares) and without the aliases of these families. Typeface finds
-- these families by their names in its font map, which Android builds of the configuration outside the app (in the
-- zygote, from Android 12 in system server), so its lookups by name answer like for a family which does not exist.
local FONTS = { 'Roboto', 'Noto', 'DroidSans', 'CutiveMono', 'ComingSoon', 'DancingScript', 'CarroisGothicSC',
                'SourceSansPro', 'AndroidClock' }
local FILES = { 'fonts.xml', 'font_fallback.xml', 'fonts_customization.xml' }
-- The configuration Android builds the font map of: fonts.xml, font_fallback.xml from Android 15 and
-- fonts_customization.xml from Android 10; a name which is not in the map does not change a lookup.
-- shortcut: the paths of AOSP only, not the partitions of makers which config filters too, add the path a maker reads
-- shortcut: apps still read the map by reflection (sSystemFontMap is greylisted), hide it when an app does
local MAP = { '/system/etc/fonts.xml', '/system/etc/font_fallback.xml', '/product/etc/fonts_customization.xml' }
-- The families and aliases of AOSP (fonts.xml and font_fallback.xml of Android 8.1 to 17, roboto from Android 16):
-- a maker which puts a font of its own in one, like in sans-serif, is not told by the name, so these keep their answer
local AOSP = { 'sans-serif', 'arial', 'helvetica', 'tahoma', 'verdana', 'sans-serif-thin', 'sans-serif-light',
               'sans-serif-medium', 'sans-serif-black', 'sans-serif-condensed', 'sans-serif-condensed-light',
               'sans-serif-condensed-medium', 'serif', 'times', 'times new roman', 'palatino', 'georgia', 'baskerville',
               'goudy', 'fantasy', 'ITC Stone Serif', 'serif-bold', 'monospace', 'sans-serif-monospace', 'monaco',
               'serif-monospace', 'courier', 'courier new', 'casual', 'cursive', 'sans-serif-smallcaps',
               'source-sans-pro', 'source-sans-pro-semi-bold', 'roboto-flex', 'roboto' }
-- The names of the families the app reads without, once per thread (the script runs per thread)
local FAMILIES = nil

function filter(hook)
    -- A lookup of Typeface by name runs the script for every name, a family name is never a path
    if hook:getMethodName() ~= 'open' then
        return nil
    end
    return { '*etc/font*' }
end

-- Whether the name of a font file is of every Android
function common(name)
    for _, prefix in ipairs(FONTS) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- The text without the families with a font which is not of every Android, and without their aliases;
-- the number of families left out; the names of the families and aliases left out
function filtered(text)
    local removed = {}
    local count = 0
    local result = string.gsub(text, '%s*<family[%s>].-</family>', function(family)
        for name in string.gmatch(family, '>%s*([^<>%s]+%.[ot]t[fc])%s*<') do
            if not common(name) then
                local key = string.match(family, '<family[^>]-%sname="([^"]+)"')
                if key ~= nil then
                    removed[key] = true
                end
                count = count + 1
                return ''
            end
        end
        return nil
    end)
    result = string.gsub(result, '%s*<alias[^>]-/>', function(alias)
        local name = string.match(alias, '%sname="([^"]+)"')
        local to = string.match(alias, '%sto="([^"]+)"')
        if (to ~= nil and removed[to]) or (name ~= nil and removed[name]) then
            if name ~= nil then
                removed[name] = true
            end
            return ''
        end
        return nil
    end)
    return result, count, removed
end

-- Whether the hooked method opened one of the files in a etc folder of a partition, also by another path, like
-- XParam.opens: with /./ or //, by the links of /proc to the root and by other links, like /etc to /system/etc
-- (opens takes a single file, the partitions of makers are many)
function config(param)
    local path = '/' .. tostring(param:getArgument(0))
    while string.find(path, '/./', 1, true) do
        path = string.gsub(path, '/%./', '/')
    end
    path = string.gsub(path, '//+', '/')
    path = string.gsub(path, '^/proc/[%w_-]+/root/', '/')
    if configpath(path) then
        return true
    end
    local ok, resolved = pcall(function()
        return luajava.newInstance('java.io.File', path):getCanonicalPath()
    end)
    return ok and resolved ~= nil and resolved ~= path and configpath(resolved)
end

-- Whether a plain path is of one of the files in a etc folder of a partition
function configpath(path)
    -- Of the system only, not a configuration an app keeps itself
    local partition = string.match(path, '^/([^/]+)/')
    if partition == nil or not (partition == 'system' or partition == 'product' or partition == 'system_ext' or
            partition == 'vendor' or partition == 'odm' or partition == 'oem' or string.find(partition, '^my_')) then
        return false
    end
    for _, file in ipairs(FILES) do
        if string.sub(path, -string.len(file) - 5) == '/etc/' .. file or
                string.match(path, '/etc/fonts/' .. string.gsub(file, '%.', '%%.') .. '$') then
            return true
        end
    end
    return false
end

-- The families of the font map which the app reads without, read without the hooks of the app and without
-- StrictMode, since a lookup of Typeface does not read a file
function families(param)
    if FAMILIES == nil then
        local names = {}
        param:unhooked(function()
            local strict = luajava.bindClass('android.os.StrictMode')
            local policy = strict:allowThreadDiskReads()
            pcall(function()
                for _, path in ipairs(MAP) do
                    local text = param:readFile(path)
                    if text ~= nil then
                        local _, _, removed = filtered(text)
                        for name in pairs(removed) do
                            names[name] = true
                        end
                    end
                end
            end)
            strict:setThreadPolicy(policy)
        end)
        for _, name in ipairs(AOSP) do
            names[name] = nil
        end
        FAMILIES = names
    end
    return FAMILIES
end

-- Typeface.create(String, int), Typeface.Builder.setFallback, CustomFallbackBuilder.setSystemFallback and
-- Typeface.hasFontFamily (the system family of a font resource, from Android 12) look up a family of the font map by
-- its name. A family the app reads without is looked up by a name no family has, so Android itself answers like for a
-- family which does not exist: the default family, or the downloadable font of a resource. The name keeps the one of
-- the app, with a NUL which XML does not allow, so two names stay apart in the cache of Typeface.Builder.
function before(hook, param)
    if hook:getMethodName() == 'open' then
        return false
    end
    local name = param:getArgument(0)
    if name == nil or not families(param)[name] then
        return false
    end
    param:setArgument(0, name .. '\0')
    return true, name
end

function after(hook, param)
    if hook:getMethodName() ~= 'open' or param:getException() ~= nil or param:getResult() == nil or
            not config(param) then
        return false
    end
    local original = param:getFileText()
    local text, count = filtered(original)
    if count == 0 then
        return false
    end
    -- As long as the file, which stat and File.length give, by spaces after the root element, which XML allows
    if #text < #original then
        text = text .. string.rep(' ', #original - #text)
    end
    param:setFileText(text)
    return true, tostring(count)
end
