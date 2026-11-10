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
-- HdrCapabilities writes itself; should the parcel differ, which the luminance read back shows, it stays
function hdr(param)
    local result = param:getResult()
    local invalid = -1
    if result == nil or (result:getDesiredMaxLuminance() == invalid and
            result:getDesiredMaxAverageLuminance() == invalid and result:getDesiredMinLuminance() == invalid) then
        return false
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
        return false
    end
    param:setResult(fake)
    return true, tostring(result:getDesiredMaxLuminance()), tostring(invalid)
end

-- A Java array of the class with the values
function array(class, values)
    local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(class, #values)
    for index = 1, #values do
        fake[index] = values[index]
    end
    return fake
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

    if method == 'getDeviceProductInfo' then
        if param:getResult() == nil then
            return false
        end
        param:setResult(nil)
        return true
    elseif method == 'getHdrCapabilities' then
        return hdr(param)
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
