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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- The camera list of the app is empty, so every camera id is one the device does not have: the error of Android for
-- that, with the message of Android for the id the app asked for. Android words the message by version, so it is
-- taken from getCameraCharacteristics once per process, for an id no device has.
-- shortcut: getTorchStrengthLevel gets the message of getCameraCharacteristics, Android's own differs; take its own when an app is seen comparing them
local PROBE = '1234567' -- a number, since Android 8.1 and 9 parse the ids as numbers

function before(hook, param)
    local scope = param:getApplicationContext()
    local message = param:getValue('camera.unknown', scope)
    if message == nil then
        local manager = param:getThis()
        local ok, err = pcall(function()
            return param:unhooked(function()
                return manager:getCameraCharacteristics(PROBE)
            end)
        end)
        local text = nil
        if not ok then
            text = string.match(tostring(err), 'java%.lang%.IllegalArgumentException: (.*)$')
        end
        message = text or ('Camera id ' .. PROBE .. ' does not match any currently connected camera device')
        param:putValue('camera.unknown', message, scope)
    end

    -- The id is replaced by plain text, so ids with % or other pattern characters are safe
    local id = tostring(param:getArgument(0))
    local at, to = string.find(message, PROBE, 1, true)
    if at ~= nil then
        message = string.sub(message, 1, at - 1) .. id .. string.sub(message, to + 1)
    end
    param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', message))
    return true
end
