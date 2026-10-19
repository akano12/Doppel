-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- MAC addresses are fixed hardware ids. Recent Android versions hide them from apps,
-- older versions and apps targeting them still get the real ones.
-- The fake values are those of Android: 02:00:00:00:00:00 for text, nothing for the bytes of an interface.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'getHardwareAddress' then
        param:setResult(nil)
        return true
    end

    local fake = '02:00:00:00:00:00'
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
