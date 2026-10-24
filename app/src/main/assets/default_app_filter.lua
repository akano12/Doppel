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

-- Copyright 2026 akano12

-- The default SMS app and the default phone app are only told when they are of the system or the app itself,
-- else it is the one of the system, like for an app which is hidden by the package visibility rules
-- (see package_probe); apps compare the name without checking for null, a phone always has these.
-- The app itself can still tell whether it is the default.

-- The default app of a device on which the user did not choose one: the dialer of the system, or the SMS app
-- the configuration of the system names, else a system app which can be the default; nil when there is none.
-- The same function as in settingssecure_getstring, which tells it for the settings of the default apps;
-- a test compares them.
function systemdefault(param, dialer)
    -- Its lookups are hooked themselves (see package_probe and generic_filter_by_uid),
    -- which would restrict them and report them as calls of the app
    return param:unhooked(function()
        local context = param:getApplicationContext()
        local names = {}
        if dialer then
            -- Public as of Android 10, hidden before
            local telecom = context:getSystemService('telecom')
            if telecom ~= nil then
                local ok, name = pcall(telecom.getSystemDialerPackage, telecom)
                names[#names + 1] = (ok and name or nil)
            end
        else
            -- The role default of Android 10 and later, the default of SmsApplication before
            local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
            for _, config in ipairs({ 'config_defaultSms', 'default_sms_application' }) do
                local id = resources:getIdentifier(config, 'string', 'android')
                if id ~= 0 then
                    names[#names + 1] = resources:getString(id)
                end
            end
        end

        -- The configured app need not be installed or enabled
        local pm = context:getPackageManager()
        local infos = {}
        for _, name in ipairs(names) do
            local ok, ai = pcall(pm.getApplicationInfo, pm, name, 0)
            if ok then
                infos[#infos + 1] = ai
            end
        end
        local intent = luajava.newInstance('android.content.Intent',
                dialer and 'android.intent.action.DIAL' or 'android.provider.Telephony.SMS_DELIVER')
        local ok, list = pcall(dialer and pm.queryIntentActivities or pm.queryBroadcastReceivers, pm, intent, 0)
        if ok and list ~= nil then
            local items = list:toArray()
            for index = 1, items['length'] do
                infos[#infos + 1] = items[index].activityInfo.applicationInfo
            end
        end
        for _, ai in ipairs(infos) do
            if ai ~= nil and ai.enabled ~= false and ai.packageName ~= param:getPackageName() and param:isVisible(ai) then
                return ai.packageName
            end
        end
        return nil
    end)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or result == param:getPackageName() then
        return false
    end

    -- The package manager does not tell about apps hidden by the package visibility rules
    if param:isInstalledVisible(result) then
        return false
    end
    local fake = systemdefault(param, hook:getMethodName() == 'getDefaultDialerPackage')
    param:setResult(fake)
    return true, result, fake
end
