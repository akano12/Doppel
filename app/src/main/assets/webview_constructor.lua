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

-- The user agent of a WebView contains the device model and firmware build, like
-- '(Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)', which is reduced like Chrome does:
-- '(Linux; Android 15; K; wv)', the same as WebSettings.getDefaultUserAgent (see webview_useragent).
-- It is set when the WebView is made, because pages see it (navigator.userAgent, the requests);
-- user agents the app sets later with the model and build are reduced too, and so is the default
-- the app brings back with null or an empty user agent. Other user agents of the app stay.

function reduce(ua)
    return (string.gsub(ua, '; [^;]*Build/[^;)]*', '; K', 1))
end

-- The parameter is not named hook, the function to hook a method is
function after(h, param)
    local this = param:getThis()
    if this == nil or param:getValue('hooked', this) then
        return false
    end
    param:putValue('hooked', true, this)

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
