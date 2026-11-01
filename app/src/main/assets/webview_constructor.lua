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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- The user agent of a WebView contains the device model and firmware build, like
-- '(Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)', which is reduced like Chrome does:
-- '(Linux; Android 15; K; wv)', also by WebSettings.getDefaultUserAgent, which apps send with their own requests.
-- It is set when the WebView is made, because pages see it (navigator.userAgent, the requests);
-- user agents the app sets later with the model and build are reduced too, and so is the default
-- the app brings back with null or an empty user agent. Other user agents of the app stay.
-- So do user agents with the model and build the app sends as a header, which it can have stored before,
-- with HttpURLConnection or OkHttp, of which Request.Builder.header and addHeader call Headers.Builder.set and add.

function reduce(ua)
    return (string.gsub(ua, '; [^;]*Build/[^;)]*', '; K', 1))
end

local HEADERS = { set = true, add = true, addUnsafeNonAscii = true, setRequestProperty = true,
    addRequestProperty = true }

-- The value of a User-Agent header, the name is not case sensitive
function before(h, param)
    if not HEADERS[h:getMethodName()] then
        return false
    end
    local name = param:getArgument(0)
    local ua = param:getArgument(1)
    if name == nil or ua == nil or string.lower(name) ~= 'user-agent' then
        return false
    end
    local fake = reduce(ua)
    if fake == ua then
        return false
    end
    param:setArgument(1, fake)
    return true, ua, fake
end

-- The parameter is not named hook, the function to hook a method is
function after(h, param)
    if h:getMethodName() == 'getDefaultUserAgent' then
        local result = param:getResult()
        if result == nil then
            return false
        end
        local fake = reduce(result)
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    end

    local this = param:getThis()
    -- The hooked constructor is the one all others call, so this runs once per WebView
    if this == nil then
        return false
    end

    local settings = this:getSettings()
    if settings == nil then
        return false
    end
    hook(settings, 'setUserAgentString', setUserAgentString)

    local ua = settings:getUserAgentString()
    if ua == nil then
        return false
    end
    local fake = reduce(ua)
    if fake == ua then
        return false
    end
    settings:setUserAgentString(fake)
    return true, ua, fake
end

function setUserAgentString(when, param)
    if when ~= 'before' then
        return
    end
    local ua = param:getArgument(0)
    if ua == nil or ua == '' then
        local webSettings = luajava.bindClass('android.webkit.WebSettings')
        ua = webSettings:getDefaultUserAgent(param:getApplicationContext())
        if ua == nil then
            return
        end
    end
    local fake = reduce(ua)
    if fake ~= param:getArgument(0) then
        param:setArgument(0, fake)
    end
end
