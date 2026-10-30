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

-- The strings of the OpenGL driver name the graphics chip, like Adreno (TM) 740, which narrows down the model of
-- the device, like Build.MODEL. The family stays, like Adreno or Mali, which games use for workarounds of drivers,
-- and the version keeps its number, like OpenGL ES 3.2, which they parse; the model and the build of the driver go.
-- Not covered is native code, which calls the driver itself, like most games and Vulkan.
local VENDOR = 7936 -- GL_VENDOR
local RENDERER = 7937 -- GL_RENDERER
local VERSION = 7938 -- GL_VERSION

-- The leading words of a name without digits, like Adreno for Adreno (TM) 740, Mali for Mali-G715 and
-- PowerVR Rogue for PowerVR Rogue GE8320; nil when the name starts with digits
function family(name)
    local words = {}
    for word in string.gmatch(name, '%S+') do
        if string.find(word, '^%(') then
            break
        end
        if string.find(word, '%d') then
            local before = string.match(word, '^(%a+)%-')
            if before ~= nil then
                words[#words + 1] = before
            end
            break
        end
        words[#words + 1] = word
    end
    return (#words > 0 and table.concat(words, ' ') or nil)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end

    local name = param:getArgument(0)
    local fake
    if name == RENDERER then
        fake = family(result) or 'unknown'
    elseif name == VENDOR then
        -- Like Google (NVIDIA Corporation) of emulators, which names the chip of the computer
        fake = string.gsub(result, '%s*%b()', '')
    elseif name == VERSION then
        fake = string.match(result, '^OpenGL ES[%-%a]* %d+%.%d+')
    end
    if fake == nil or fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
