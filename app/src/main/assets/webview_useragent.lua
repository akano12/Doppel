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
-- Copyright 2026 akano12

-- Apps send the user agent of WebView with their own requests. It contains the device model and firmware build,
-- like '(Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)', which is reduced like Chrome does: '(Linux; Android 15; K; wv)'

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake = (string.gsub(result, '; [^;]*Build/[^;)]*', '; K', 1))
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
