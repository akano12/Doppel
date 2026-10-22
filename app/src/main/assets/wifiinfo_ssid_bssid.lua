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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- The values Android shows without location permission and when not connected stay, they hide the network already;
-- the access point is hidden like Android does, see also network_tostring
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake = '"private"'
    local hidden = '<unknown ssid>'
    if hook:getMethodName() == 'getBSSID' then
        fake = '02:00:00:00:00:00'
        hidden = fake
    end
    if result == hidden then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
