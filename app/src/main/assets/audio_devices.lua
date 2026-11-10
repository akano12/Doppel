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

-- The devices of audio tell what is connected, like a headset with the name of its owner: only the speaker and the
-- earpiece stay, since a device without an output does not exist and apps playing sound ask for them. The devices for
-- calls keep them too, so the speakerphone of calling apps works.
local TYPE_BUILTIN_EARPIECE = 1
local TYPE_BUILTIN_SPEAKER = 2
local GET_DEVICES_OUTPUTS = 2
local USAGE_VOICE_COMMUNICATION = 2

function builtin(device)
    return device ~= nil and device:isSink() and
            (device:getType() == TYPE_BUILTIN_SPEAKER or device:getType() == TYPE_BUILTIN_EARPIECE)
end

-- The built-in output of the type, like Android gives it to the app
function builtin_output(param, type)
    local manager = param:getThis()
    local devices = param:unhooked(function()
        return manager:getDevices(GET_DEVICES_OUTPUTS)
    end)
    for index = 1, devices['length'] do
        if devices[index]:getType() == type then
            return devices[index]
        end
    end
    return nil
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local method = hook:getMethodName()

    if method == 'getCommunicationDevice' then
        -- Without a device of its own, Android chooses the earpiece, else the first device for calls
        -- (AudioDeviceBroker), which is the speaker then
        if builtin(result) then
            return false
        end
        local fake = builtin_output(param, TYPE_BUILTIN_EARPIECE) or builtin_output(param, TYPE_BUILTIN_SPEAKER)
        param:setResult(fake)
        return true
    end

    if method == 'getAvailableCommunicationDevices' or method == 'getAudioDevicesForAttributes' then
        local kept = luajava.newInstance('java.util.ArrayList')
        for index = 0, result:size() - 1 do
            local device = result:get(index)
            if builtin(device) then
                kept:add(device)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        -- Sound which went to a hidden device goes to the speaker without it, calls to the earpiece
        if kept:isEmpty() and method == 'getAudioDevicesForAttributes' then
            local attributes = param:getArgument(0)
            local fake = nil
            if attributes ~= nil and attributes:getUsage() == USAGE_VOICE_COMMUNICATION then
                fake = builtin_output(param, TYPE_BUILTIN_EARPIECE)
            end
            fake = fake or builtin_output(param, TYPE_BUILTIN_SPEAKER)
            if fake ~= nil then
                kept:add(fake)
            end
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    end

    -- getDevices gives an array
    if result['length'] == 0 then
        return false
    end
    local kept = {}
    for index = 1, result['length'] do
        local device = result[index]
        if builtin(device) then
            kept[#kept + 1] = device
        end
    end
    if #kept == result['length'] then
        return false
    end
    local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(
            luajava.bindClass('android.media.AudioDeviceInfo'), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end
