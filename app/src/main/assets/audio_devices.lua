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

-- The devices of audio tell what is connected, like a headset with the name of its owner: only the devices built into
-- the phone stay, since a device without an output does not exist, apps playing sound ask for them and Android lists
-- them on a phone without a microphone too. The devices for calls keep the speaker and the earpiece, so the
-- speakerphone of calling apps works.
local TYPE_BUILTIN_EARPIECE = 1
local TYPE_BUILTIN_SPEAKER = 2
local TYPE_REMOTE_SUBMIX = 25
local GET_DEVICES_INPUTS = 1
local GET_DEVICES_OUTPUTS = 2
local USAGE_VOICE_COMMUNICATION = 2

-- The types which stay, by whether the device is a sink: the earpiece, the speaker, the speaker of sonification
-- (24, from Android 11) and the line of calls both ways (18, Telephony Tx and Rx), the FM tuner (16) and the input of
-- the remote submix every audio policy attaches (25, from Android 11, only at its default address); the microphones
-- and every type of a device which connects are left out.
-- shortcut: other built-in inputs some devices attach, like an echo reference or a TV tuner, and the bus devices of
-- cars stay hidden; upgrade when a device which Doppel supports shows them
local KEPT = {
    [true] = { [TYPE_BUILTIN_EARPIECE] = true, [TYPE_BUILTIN_SPEAKER] = true, [18] = true, [24] = true },
    [false] = { [16] = true, [18] = true, [TYPE_REMOTE_SUBMIX] = true }
}

function builtin(device)
    if device == nil or KEPT[device:isSink()][device:getType()] ~= true then
        return false
    end
    -- An app which captures the sound of other apps or casts it connects a remote submix of its own at the address of
    -- its mix, while the attached one has the default address 0 (DeviceDescriptorBase)
    return device:getType() ~= TYPE_REMOTE_SUBMIX or device:getAddress() == '0'
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

-- The listener of the device for calls gets it by its port id, which a hidden device has too: it gets the device of
-- getCommunicationDevice instead, the earpiece, else the speaker, and no call for the device it got last, since Android
-- tells only a change (AudioDeviceBroker)
-- shortcut: the first call of a listener for a hidden device still comes, with the earpiece, where a phone without the
-- device would make none; upgrade by keeping the device of getCommunicationDevice at the registration
function before(hook, param)
    if hook:getMethodName() ~= 'dispatchCommunicationDeviceChanged' then
        return false
    end
    local id = param:getArgument(0)
    local manager = param:getApplicationContext():getSystemService('audio')
    local devices = param:unhooked(function()
        return manager:getDevices(GET_DEVICES_OUTPUTS)
    end)
    local device, earpiece, speaker
    for index = 1, devices['length'] do
        local each = devices[index]
        if each:getId() == id then
            device = each
        end
        if each:getType() == TYPE_BUILTIN_EARPIECE then
            earpiece = earpiece or each
        elseif each:getType() == TYPE_BUILTIN_SPEAKER then
            speaker = speaker or each
        end
    end
    local fake = id
    if device ~= nil and not builtin(device) then
        local kept = earpiece or speaker
        fake = (kept == nil and 0 or kept:getId())
    end
    local stub = param:getThis()
    local last = param:getValue('communication', stub)
    param:putValue('communication', fake, stub)
    if fake == last then
        param:setResult(nil)
        return true
    end
    if fake == id then
        return false
    end
    param:setArgument(0, fake)
    return true
end

function after(hook, param)
    local result = param:getResult()
    local method = hook:getMethodName()

    -- These pick from the devices of infoListFromPortList, which leave out the hidden ones already, so a hidden device
    -- for calls or sound comes as none; the answer of Android tells it from no device at all
    if (method == 'getCommunicationDevice' and result == nil) or
            (method == 'getAudioDevicesForAttributes' and result ~= nil and result:isEmpty()) then
        local manager = param:getThis()
        result = param:unhooked(function()
            if method == 'getCommunicationDevice' then
                return manager:getCommunicationDevice()
            end
            return manager:getAudioDevicesForAttributes(param:getArgument(0))
        end)
    end
    if result == nil then
        return false
    end

    -- The types of devices which could be connected: the built-in microphone would contradict the missing microphone
    -- feature and input devices, so only the input types of the inputs getDevices keeps stay; the output types are the
    -- same with nothing connected and stay. Android gives a new HashSet.
    if method == 'getSupportedDeviceTypes' then
        if param:getArgument(0) ~= GET_DEVICES_INPUTS then
            return false
        end
        local kept = luajava.newInstance('java.util.HashSet')
        local types = result:iterator()
        while types:hasNext() do
            local type = types:next()
            if KEPT[false][type] then
                kept:add(type)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    end

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
        -- Android gives the devices for attributes unmodifiable, the devices for calls in an ArrayList
        if method == 'getAudioDevicesForAttributes' then
            param:setResult(luajava.bindClass('java.util.Collections'):unmodifiableList(kept))
        else
            param:setResult(kept)
        end
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
