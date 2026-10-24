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

-- Java system properties with the kernel release (os.version) and with the device model and firmware build,
-- like 'Dalvik/2.1.0 (Linux; U; Android 15; Pixel 9 Build/AP4A.250105.002)' (http.agent), which HTTP clients send.
-- The properties are changed once, when the app starts, in the static fields of java.lang.System
-- which hold them, because hooking System.getProperty would slow down every call.

function after(hook, param)
    local props = param:getResult()
    if props == nil then
        return false
    end

    local restricted = false
    local old = {}
    local new = {}

    -- Only the own entries, os.version is in the defaults of the overridable properties
    local version = props:get('os.version')
    if version ~= nil then
        local fake = string.match(version, '^[%d%.]+')
        if fake == nil then
            fake = 'unknown'
        end
        if fake ~= version then
            props:put('os.version', fake)
            restricted = true
            old[#old + 1] = version
            new[#new + 1] = fake
        end
    end

    local agent = props:get('http.agent')
    if agent ~= nil then
        local fake = (string.gsub(agent, ';[^;]*Build/[^)]*%)', ')'))
        if fake ~= agent then
            props:put('http.agent', fake)
            restricted = true
            old[#old + 1] = agent
            new[#new + 1] = fake
        end
    end

    return restricted, table.concat(old, ' '), table.concat(new, ' ')
end
