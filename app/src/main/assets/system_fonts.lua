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

-- SystemFonts.getAvailableFonts lists the fonts of the system with their files: the fonts which are not of every
-- Android, like SamsungOne, MiSans and Google Sans, name the maker and the ROM and are left out, like the files of
-- hardware_files (FONTS, which a test compares). Fonts without a file stay.
local FONTS = { 'Roboto', 'Noto', 'DroidSans', 'CutiveMono', 'ComingSoon', 'DancingScript', 'CarroisGothicSC',
                'SourceSansPro', 'AndroidClock' }

-- Whether the name of a font file is of every Android
function common(name)
    for _, prefix in ipairs(FONTS) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end
    -- From Android 12 the set Android keeps, which cannot be changed, the same for each call; before a new HashSet
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local scope = luajava.bindClass('android.graphics.fonts.SystemFonts')
    if sdk >= 31 then
        local cached = param:getValue('fonts', scope)
        if cached ~= nil then
            param:setResult(cached)
            return true
        end
    end
    local kept = luajava.newInstance(sdk >= 31 and 'java.util.LinkedHashSet' or 'java.util.HashSet')
    local iterator = result:iterator()
    while iterator:hasNext() do
        local font = iterator:next()
        local file = font:getFile()
        if file == nil or common(file:getName()) then
            kept:add(font)
        end
    end
    if kept:size() == result:size() then
        return false
    end
    if sdk >= 31 then
        kept = luajava.bindClass('java.util.Collections'):unmodifiableSet(kept)
        param:putValue('fonts', kept, scope)
    end
    param:setResult(kept)
    return true, tostring(result:size()), tostring(kept:size())
end
