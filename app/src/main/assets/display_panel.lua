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
-- rates it supports, like 60, 90 and 120 Hz. The density becomes the logical density (densityDpi), the value of
-- displays without calibration, like of emulators, and only the active mode is supported. The sizes, the
-- densities layouts use and the active refresh rate, which apps use to pace frames, stay.
-- The metrics of Resources stay too: Android converts sizes in pt, mm and in with their xdpi, so faking it would
-- change layouts; up to Android 11 Resources gets its metrics from Display.getMetrics, which is why the
-- framework gets the real metrics there.

-- Resources and their manager on the stack, see above
function framework()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if class == 'android.app.ResourcesManager' or string.find(class, 'android.content.res.', 1, true) == 1 then
            return true
        end
    end
    return false
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

    -- Display.getMetrics and getRealMetrics fill the metrics of the caller
    if method == 'getMetrics' or method == 'getRealMetrics' then
        local metrics = param:getArgument(0)
        local dpi = metrics.densityDpi
        -- Resources are filled through getMetrics on Android 8.1 to 11 only, so only there the stack is checked
        if (metrics.xdpi == dpi and metrics.ydpi == dpi) or
                (luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 and framework()) then
            return false
        end
        local real = metrics.xdpi .. ' ' .. metrics.ydpi
        metrics.xdpi = dpi
        metrics.ydpi = dpi
        return true, real, tostring(dpi)
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
        -- getSupportedRefreshRates
        fake = array(float, { param:getThis():getRefreshRate() })
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(fake['length'])
end
