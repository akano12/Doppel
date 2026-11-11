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

-- The panel of the display tells the model of the screen: its exact density, like 403.411 dpi, and the refresh
-- rates it supports, like 60, 90 and 120 Hz. The density becomes the stable density of the device, the value of
-- displays without calibration, like of emulators, and only the active mode is supported. The sizes, the
-- densities layouts use and the active refresh rate, which apps use to pace frames, stay.
-- The metrics of Resources (getDisplayMetrics, which is not hooked, it is called very often) get the same density
-- where Android fills them: ResourcesImpl.updateConfiguration(Impl), when made and on configuration changes like
-- rotation, and once when the hooks are installed (the field Resources.mSystem) for the ones made before, of the
-- app and of Resources.getSystem(). Sizes in pt, mm and in (TypedValue) follow xdpi, so they change by the ratio,
-- about 1 to 2%; sizes in dp and sp, the ones layouts use, stay.
-- The luminance of HDR panels, like 1000, 500 and 0.0005 nits, becomes unknown (-1, INVALID_LUMINANCE), like of
-- displays which do not tell it, the HDR types stay, since apps choose the videos to play by them; the maker and
-- product of the panel (DeviceProductInfo, of its EDID) are not known, like for displays without EDID.

-- The metrics with the density of the device as the exact one, the one of displays without calibration: the stable
-- one (DENSITY_DEVICE_STABLE), which does not follow the display size the user sets, like the panel does not; the
-- old ones, or nil when they are already
function generic(metrics)
    local dpi = luajava.bindClass('android.util.DisplayMetrics').DENSITY_DEVICE_STABLE
    if metrics.xdpi == dpi and metrics.ydpi == dpi then
        return nil
    end
    local real = metrics.xdpi .. ' ' .. metrics.ydpi
    metrics.xdpi = dpi
    metrics.ydpi = dpi
    return real
end

-- HdrCapabilities without the luminance; it has no public constructor, so it is read from a parcel, written like
-- HdrCapabilities writes itself; nil when it has no luminance, or should the parcel differ, which the luminance read
-- back shows
function hdr(result)
    local invalid = -1
    if result == nil or (result:getDesiredMaxLuminance() == invalid and
            result:getDesiredMaxAverageLuminance() == invalid and result:getDesiredMinLuminance() == invalid) then
        return nil
    end
    local ok, fake = pcall(function()
        local types = result:getSupportedHdrTypes()
        local parcel = luajava.bindClass('android.os.Parcel'):obtain()
        parcel:writeInt(types['length'])
        for index = 1, types['length'] do
            parcel:writeInt(types[index])
        end
        for _ = 1, 3 do
            parcel:writeFloat(invalid)
        end
        parcel:setDataPosition(0)
        local made = luajava.bindClass('android.view.Display$HdrCapabilities').CREATOR:createFromParcel(parcel)
        parcel:recycle()
        return made
    end)
    if not ok or fake == nil or fake:getDesiredMaxLuminance() ~= invalid or fake:getDesiredMinLuminance() ~= invalid then
        return nil
    end
    return fake
end

-- A Java array of the class with the values
function array(class, values)
    local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(class, #values)
    for index = 1, #values do
        fake[index] = values[index]
    end
    return fake
end

-- The text with the part which ends at stop as another
function spliced(text, stop, old, new)
    return string.sub(text, 1, stop - #old) .. new .. string.sub(text, stop + 1)
end

-- The text with the list after the label, like ", modes [{id=1, ...}, {id=2, ...}]", as only the active mode, which
-- starts with its id, like getSupportedModes gives it; the text of the active mode too, also when the list stays
function onlymode(text, label, id, active)
    local _, stop, list = string.find(text, ', ' .. label .. ' (%b[])')
    if stop == nil then
        return text, active
    end
    local count = 0
    for mode in string.gmatch(list, '%b{}') do
        count = count + 1
        if string.find(mode, '{id=' .. id .. ',', 1, true) == 1 then
            active = mode
        end
    end
    if count < 2 or active == nil then
        return text, active
    end
    return spliced(text, stop, list, '[' .. active .. ']'), active
end

-- The text of a display (Display.toString), which DisplayInfo and DisplayMetrics make from their fields, not by the
-- hooked getters, with the same values as the getters: the stable density, only the active mode, its refresh rate and
-- the ids of the default and preferred mode, and the luminance unknown. Up to Android 10 DisplayInfo has the exact
-- density, the HdrCapabilities as the text of Object, with the hash of its values, and on Android 10 the id and address
-- of the panel, which tell the model of its EDID; from Android 11 these are only in the text for the system.
function displaytext(param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local display = param:getThis()
    local found = param:unhooked(function()
        local real = display:getHdrCapabilities()
        return { display:getMode():getModeId(), real, hdr(real) }
    end)
    local id = found[1]
    local dpi = luajava.bindClass('android.util.DisplayMetrics').DENSITY_DEVICE_STABLE .. '.0'
    local text = string.gsub(result, 'xdpi=[^,]*, ydpi=[^}]*', function()
        return 'xdpi=' .. dpi .. ', ydpi=' .. dpi
    end)
    text = string.gsub(text, '(, density %d+ )%([^)]*%)( dpi)', function(before, after)
        return before .. '(' .. dpi .. ' x ' .. dpi .. ')' .. after
    end)

    -- The modes, from Android 15 those of the system and of apps, which getSupportedModes gives
    local active
    text, active = onlymode(text, 'modes', id, nil)
    text, active = onlymode(text, 'supportedModes', id, active)
    text, active = onlymode(text, 'appsSupportedModes', id, active)
    if active ~= nil then
        text = string.gsub(text, '(, defaultMode )%d+', function(label) return label .. id end)
        text = string.gsub(text, '(, userPreferredModeId )%d+', function(label) return label .. id end)
        -- Android 16: the refresh rates of the active mode, like getSupportedRefreshRates gives them.
        -- shortcut: frameRateCategoryRate in the text, like getSuggestedFrameRate, still has the highest rate of the
        -- panel, until getSuggestedFrameRate is hooked too
        local _, stop, rates = string.find(text, ', supportedRefreshRates (%b[])')
        local fps = string.match(active, ', fps=([^,}]*)')
        if stop ~= nil and fps ~= nil and string.find(rates, ',', 1, true) ~= nil then
            text = spliced(text, stop, rates, '[' .. fps .. ']')
        end
    end
    text = string.gsub(text, ', alternativeRefreshRates=%[[^%]]*%]', ', alternativeRefreshRates=[]')

    local real, fake = found[2], found[3]
    if fake ~= nil then
        local start, stop = string.find(text, tostring(real), 1, true)
        if start ~= nil then
            text = spliced(text, stop, tostring(real), tostring(fake))
        end
    end
    -- From Android 16 the text has the luminance also when the getter gives none, for a display forced to SDR
    if fake ~= nil or real == nil then
        text = string.gsub(text, '(m[%a]*Luminance=)[^,}]*', '%1-1.0')
    end

    -- Android 10: a panel without EDID has only its port, like local:129 and {port=129}, like getAddress gives it
    local port = string.match(text, ', address {port=(%d+), model=0x%x+}')
    if port ~= nil then
        text = string.gsub(text, '(, address {port=%d+), model=0x%x+}', '%1}')
        text = string.gsub(text, 'uniqueId "local:%d+"', 'uniqueId "local:' .. port .. '"')
    end

    if text == result then
        return false
    end
    param:setResult(text)
    return true, result, text
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()

    -- Display.getMetrics and getRealMetrics fill the metrics of the caller, ResourcesImpl its own
    local metrics
    if method == 'getMetrics' or method == 'getRealMetrics' then
        metrics = param:getArgument(0)
    elseif method == 'updateConfiguration' or method == 'updateConfigurationImpl' then
        metrics = param:getThis():getDisplayMetrics()
    elseif method == '#mSystem' then
        -- When the hooks are installed: the resources of the system and of the app are made already
        local system = param:getResult()
        metrics = param:getApplicationContext():getResources():getDisplayMetrics()
        local real = generic(metrics)
        if system ~= nil then
            real = generic(system:getDisplayMetrics()) or real
        end
        if real == nil then
            return false
        end
        return true, real, tostring(metrics.xdpi)
    end
    if metrics ~= nil then
        local real = generic(metrics)
        if real == nil then
            return false
        end
        return true, real, tostring(metrics.xdpi)
    end

    if method == 'toString' and hook:getClassName() == 'android.view.Display' then
        return displaytext(param)
    elseif method == 'toString' then
        -- The text of Display.Mode, with the other rates of the same size like getAlternativeRefreshRates gives them
        local result = param:getResult()
        if result == nil then
            return false
        end
        local fake = string.gsub(result, ', alternativeRefreshRates=%[[^%]]*%]', ', alternativeRefreshRates=[]')
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif method == 'getDeviceProductInfo' then
        if param:getResult() == nil then
            return false
        end
        param:setResult(nil)
        return true
    elseif method == 'getHdrCapabilities' then
        local result = param:getResult()
        local fake = hdr(result)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true, tostring(result:getDesiredMaxLuminance()), tostring(fake:getDesiredMaxLuminance())
    elseif method == 'getAddress' then
        -- Android 10: apps reach the address by reflection; like the text of the display it has only the port, not the
        -- model of the EDID. DisplayAddress, its getModel and its CREATOR are hidden from apps (and scripts), so its
        -- text tells the port and the model, and the address is read from a parcel, written like Physical writes
        -- itself, of which Parcel finds the CREATOR; should the parcel differ, which the text read back shows, it stays
        local result = param:getResult()
        local port = result ~= nil and string.match(tostring(result), '^{port=(%d+), model=0x%x+}$')
        if not port then
            return false
        end
        local parcel = luajava.bindClass('android.os.Parcel'):obtain()
        parcel:writeString('android.view.DisplayAddress$Physical')
        parcel:writeLong(tonumber(port))
        parcel:setDataPosition(0)
        local fake = parcel:readParcelable(nil)
        parcel:recycle()
        if fake == nil or tostring(fake) ~= '{port=' .. port .. '}' then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    local result = param:getResult()
    if result == nil or result['length'] == 0 then
        return false
    end
    local float = luajava.bindClass('java.lang.Float').TYPE
    local fake
    if method == 'getAlternativeRefreshRates' then
        -- Display.Mode: the other rates of the same size
        fake = array(float, {})
    elseif result['length'] == 1 then
        return false
    elseif method == 'getSupportedModes' then
        fake = array(luajava.bindClass('android.view.Display$Mode'), { param:getThis():getMode() })
    else
        -- getSupportedRefreshRates: the rate of the mode, like getSupportedModes gives it, not getRefreshRate, which
        -- follows the frame rate of the app
        fake = array(float, { param:getThis():getMode():getRefreshRate() })
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(fake['length'])
end
