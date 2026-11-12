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

-- Bluetooth LE scans do not start, like in a place without devices: the start answers like Android when it starts
-- a scan (nothing, NO_ERROR for a PendingIntent, true of startLeScan), and no device is found. Where Android is
-- certain to throw or refuse before it scans, the call runs, so the app gets the answer of Android itself, with its
-- message and stack trace: without the callback, the PendingIntent or the settings (IllegalArgumentException of the
-- scanner, false of startLeScan), with Bluetooth off for LE (IllegalStateException of the scanner, false of
-- startLeScan), without the permission of the scan (SecurityException), for settings the chip cannot do
-- (SCAN_FAILED_FEATURE_UNSUPPORTED or OUT_OF_HARDWARE_RESOURCES of the scanner, to onScanFailed or returned for a
-- PendingIntent) and, for a PendingIntent, while Bluetooth is not fully on and for settings which need
-- BLUETOOTH_PRIVILEGED (SecurityException). A lookup which fails skips the scan, because running the call would
-- start it.
-- shortcut: a second start with a callback which already scans is not refused (SCAN_FAILED_ALREADY_STARTED of the
-- scanner, false of startLeScan), which needs hooks of the stops and of Bluetooth turning off; add them when an app
-- relies on it.
-- shortcut: refusals which public calls cannot tell answer a started scan: found or lost callbacks when the chip has
-- no tracking filters left (OUT_OF_HARDWARE_RESOURCES, by the hidden isHardwareTrackingFiltersAvailable), and for a
-- PendingIntent from Android 12 filters with an address type or IRK of the system API, or a null filter, which the
-- service refuses; add them when an app relies on it.
local STATE_TURNING_ON = 11
local STATE_ON = 12
local STATE_TURNING_OFF = 13
local CALLBACK_TYPE_ALL_MATCHES = 1
local CALLBACK_TYPES_FOUND_LOST = 6 -- CALLBACK_TYPE_FIRST_MATCH and CALLBACK_TYPE_MATCH_LOST
local SCAN_MODE_AMBIENT_DISCOVERY = 3
local SCAN_RESULT_TYPE_ABBREVIATED = 1

function before(hook, param)
    local ok, refused = pcall(refuses, hook, param)
    if ok and refused then
        return false
    end
    local returnType = hook:getReturnType()
    if returnType == 'boolean' then
        param:setResult(true)
    elseif returnType == 'int' then
        param:setResult(0) -- ScanCallback.NO_ERROR
    else
        param:setResult(nil)
    end
    return true
end

-- Whether Android refuses the scan before it starts it, and so the call can run
function refuses(hook, param)
    local types = hook:getParameterTypes()
    local count = types['length']
    local pending = (types[count] == 'android.app.PendingIntent')
    -- The callback or the PendingIntent is the last argument of each variant; null settings Android refuses with
    -- a callback, while for a PendingIntent it takes the default settings
    if param:getArgument(count - 1) == nil or
            (types[count] == 'android.bluetooth.le.ScanCallback' and count == 3 and param:getArgument(1) == nil) then
        return true
    end

    -- The permission Android checks first: of the scan from Android 12; before, BLUETOOTH for the state of
    -- Bluetooth, which every variant reads first (Android 8.1 to 11 check it in getState), and for a PendingIntent
    -- also BLUETOOTH_ADMIN; the lookups below would fail without BLUETOOTH
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local context = param:getApplicationContext()
    local function denied(name)
        return context:checkSelfPermission('android.permission.' .. name) ~= 0
    end
    if sdk >= 31 then
        if denied('BLUETOOTH_SCAN') then
            return true
        end
    elseif (pending and denied('BLUETOOTH_ADMIN')) or denied('BLUETOOTH') then
        return true
    end

    local adapter = param:getThis()
    local scanner = (hook:getClassName() == 'android.bluetooth.le.BluetoothLeScanner')
    if scanner then
        -- The state of Bluetooth is the same for every adapter
        adapter = luajava.bindClass('android.bluetooth.BluetoothAdapter'):getDefaultAdapter()
    end
    -- Android gives the scanner while Bluetooth is on, also for LE only, and startLeScan refuses without it;
    -- the scanner itself refuses while LE is off (isLeEnabled), which is on also while Bluetooth turns on or off
    -- from Android 11
    local on = (adapter:getBluetoothLeScanner() ~= nil)
    if not on and scanner and sdk >= 30 then
        local state = adapter:getState()
        on = (state == STATE_TURNING_ON or state == STATE_TURNING_OFF)
    end
    if not on then
        -- Without the callback the call cannot scan, also when Bluetooth turns on in the meantime
        param:setArgument(count - 1, nil)
        return true
    end

    -- The scanner refuses settings the chip cannot do before it asks Bluetooth: callbacks other than of all
    -- matches, and batches, without filtering on the chip, and found or lost callbacks without filters which match
    -- something (isSettingsConfigAllowedForScan, isSettingsAndFilterComboAllowed); the variants without settings
    -- scan with the default settings, which every chip can do
    local settings = (scanner and count == 3) and param:getArgument(1) or nil
    if settings ~= nil then
        local callbackType = settings:getCallbackType()
        if (callbackType ~= CALLBACK_TYPE_ALL_MATCHES or settings:getReportDelayMillis() ~= 0) and
                not adapter:isOffloadedFilteringSupported() then
            return true
        end
        if bit32.band(callbackType, CALLBACK_TYPES_FOUND_LOST) ~= 0 then
            local filters = param:getArgument(0)
            if filters == nil then
                return true
            end
            -- ScanFilter.isAllFieldsEmpty, which is hidden, compares with a filter of nothing
            local empty = luajava.newInstance('android.bluetooth.le.ScanFilter$Builder'):build()
            for index = 0, filters:size() - 1 do
                local filter = filters:get(index)
                if filter == nil or filter:equals(empty) then
                    return true
                end
            end
        end
    end

    -- For a PendingIntent the service refuses what needs BLUETOOTH_PRIVILEGED (needsPrivilegedPermissionForScan):
    -- a scan while Bluetooth is not fully on (getState, which is off for LE only), ambient discovery from Android 12
    -- and batches of abbreviated results
    if pending and denied('BLUETOOTH_PRIVILEGED') then
        local state = adapter:getState()
        if state ~= STATE_ON then
            -- Only while Bluetooth turns on can it be on by the time the service checks, and ambient discovery
            -- keeps the refusal then, with the same exception
            -- shortcut: Android 11 has no ambient discovery, so there such a PendingIntent scans when Bluetooth is on
            -- by then, a window of milliseconds, and from Android 12 found or lost callbacks the chip has no tracking
            -- filters for get the SecurityException instead of OUT_OF_HARDWARE_RESOURCES; refine it if an app hits it.
            if sdk >= 31 and state == STATE_TURNING_ON then
                local builder = luajava.newInstance('android.bluetooth.le.ScanSettings$Builder')
                param:setArgument(1, builder:setScanMode(SCAN_MODE_AMBIENT_DISCOVERY):build())
            end
            return true
        end
        return settings ~= nil and ((sdk >= 31 and settings:getScanMode() == SCAN_MODE_AMBIENT_DISCOVERY) or
                (settings:getReportDelayMillis() ~= 0 and settings:getScanResultType() == SCAN_RESULT_TYPE_ABBREVIATED))
    end
    return false
end
