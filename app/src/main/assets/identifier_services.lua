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

-- Manufacturers give apps an advertising identifier of the device (the OAID, the open anonymous device identifier
-- of the Mobile Security Alliance of China) through a service of their own, which the SDKs (MSA, the SDKs of Huawei
-- and Honor, and their ports) bind, or a provider they ask. These cannot be reached, like on a device without them:
-- binding the service fails and the provider is not found, so the SDKs report the identifier is not supported, for
-- each version of their interfaces. Huawei (HMS), Xiaomi, Vivo and Meizu, and Google, are covered by the hooks of
-- their identifiers (see value_device_id, contentresolver_query and adid_service).

-- The services, by package; with an action or a class when the package has other services too, like the push
-- service of ColorOS, which only the identifier service is denied
local SERVICES = {
    { package = 'com.samsung.android.deviceidservice' }, -- Samsung
    { package = 'com.zui.deviceidservice' }, -- Lenovo (ZUI)
    { package = 'com.asus.msa.SupplementaryDID' }, -- Asus
    { package = 'com.coolpad.deviceidsupport' }, -- Coolpad
    { package = 'com.mdid.msa' }, -- The service of the MSA itself, like on ZTE and Nubia
    { package = 'com.qiku.id' }, -- 360 (Qiku)
    { package = 'com.heytap.openid' }, -- OPPO, OnePlus and realme (HeyTap)
    { package = 'com.coloros.mcs', action = 'action.com.oplus.stdid.ID_SERVICE',
      class = 'com.oplus.stdid.IdentifyService' }, -- OPPO, OnePlus and realme with ColorOS 11 and later
    { package = 'com.android.creator', action = 'android.service.action.msa' }, -- Freeme OS
    { package = 'com.hihonor.id', action = 'com.hihonor.id.HnOaIdService' }, -- Honor
    { package = 'com.huawei.hwid', action = 'com.uodis.opendevice.OPENIDS_SERVICE' }, -- Huawei, without HMS
}

-- The providers, by authority; Nubia answers call(getOAID)
local PROVIDERS = {
    ['cn.nubia.identity'] = true, -- Nubia
}

-- The service of the table an intent binds, nil when none; an intent naming no package is resolved by Android
-- to any app which offers the action, so it is denied for the actions of the table
function serviceof(intent)
    if intent == nil then
        return nil
    end
    local component = intent:getComponent()
    local packageName = (component ~= nil and component:getPackageName() or intent:getPackage())
    local action = intent:getAction()
    for _, service in ipairs(SERVICES) do
        if packageName == nil then
            if service.action ~= nil and action == service.action then
                return service
            end
        elseif packageName == service.package and (service.action == nil or action == service.action or
                (component ~= nil and component:getClassName() == service.class)) then
            return service
        end
    end
    return nil
end

-- The authority of the first argument, a Uri or a name
function authorityof(value)
    if value == nil then
        return nil
    elseif type(value) == 'string' then
        return value
    end
    return value:getAuthority()
end

function before(hook, param)
    local method = hook:getMethodName()
    if string.find(method, '^bind') then
        -- bindService, bindIsolatedService and bindServiceAsUser: the intent is the first argument
        local service = serviceof(param:getArgument(0))
        if service == nil then
            return false
        end
        param:setResult(false)
        return true, service.package
    end

    -- acquireContentProviderClient, acquireUnstableContentProviderClient and call: nothing found
    local authority = authorityof(param:getArgument(0))
    if authority == nil or PROVIDERS[authority] == nil then
        return false
    end
    param:setResult(nil)
    return true, authority
end
