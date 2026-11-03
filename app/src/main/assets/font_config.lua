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
-- not of every Android (FONTS, which a test compares) and without the aliases of these families.
local FONTS = { 'Roboto', 'Noto', 'DroidSans', 'CutiveMono', 'ComingSoon', 'DancingScript', 'CarroisGothicSC',
                'SourceSansPro', 'AndroidClock' }
local FILES = { 'fonts.xml', 'font_fallback.xml', 'fonts_customization.xml' }

function filter(hook)
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
-- the number of families left out
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
            return ''
        end
        return nil
    end)
    return result, count
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

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil or not config(param) then
        return false
    end
    local text, count = filtered(param:getFileText())
    if count == 0 then
        return false
    end
    param:setFileText(text)
    return true, tostring(count)
end
