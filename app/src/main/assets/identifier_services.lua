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
    -- Freeme OS too: the MSA SDK 2.2 asks for these packages next to com.android.creator
    { package = 'com.zhuoyi.security.lite', action = 'android.service.action.msa' },
    { package = 'com.freeme.secureguard', action = 'android.service.action.msa' },
    { package = 'com.android.secureguard', action = 'android.service.action.msa' },
    { package = 'com.hihonor.id', action = 'com.hihonor.id.HnOaIdService' }, -- Honor
    -- Huawei, without HMS: the SDK binds the first of these which is installed
    { package = 'com.huawei.hwid', action = 'com.uodis.opendevice.OPENIDS_SERVICE' },
    { package = 'com.huawei.hwid.tv', action = 'com.uodis.opendevice.OPENIDS_SERVICE' },
    { package = 'com.huawei.hms', action = 'com.uodis.opendevice.OPENIDS_SERVICE' },
}

-- The interfaces of the identifier services (their Binder descriptors), so a service of another package or a new
-- device maker with one of them is not reached either; the binder of such a connection is left out, like a service
-- giving none (onNullBinding). From the stubs of the MSA SDK 2.2, Android_CN_OAID and the SDKs of Huawei and Honor.
local DESCRIPTORS = {
    ['com.android.creator.IdsSupplier'] = true, -- Freeme OS
    ['com.bun.lib.MsaIdInterface'] = true, -- The service of the MSA itself
    ['com.asus.msa.SupplementaryDID.IDidAidlInterface'] = true, -- Asus
    ['com.coolpad.deviceidsupport.IDeviceIdManager'] = true, -- Coolpad
    ['com.heytap.openid.IOpenID'] = true, -- OPPO (HeyTap)
    ['com.oplus.stdid.IStdID'] = true, -- OPPO with ColorOS 11 and later
    ['com.samsung.android.deviceidservice.IDeviceIdService'] = true, -- Samsung
    ['com.zui.deviceidservice.IDeviceidInterface'] = true, -- Lenovo (ZUI)
    ['com.qiku.id.IOAIDInterface'] = true, -- 360 (Qiku)
    ['com.uodis.opendevice.aidl.OpenDeviceIdentifierService'] = true, -- Huawei
    ['com.hihonor.cloudservice.oaid.IOAIDService'] = true, -- Honor
}

-- The system services of identifiers, which apps get from the ServiceManager: qikuid of 360 OS
local SYSTEM = { 'qikuid' }

-- The script runs for the system services of identifiers only, apps and the framework get many others
function filter(hook)
    local method = hook:getMethodName()
    if method == 'getService' or method == 'checkService' then
        return SYSTEM
    end
    return nil
end

-- The providers, by authority; Nubia answers call(getOAID)
local PROVIDERS = {
    ['cn.nubia.identity'] = true, -- Nubia
}

-- The service of the table an intent binds, nil when none. An intent naming no package (implicit) is resolved by
-- Android to the app offering the action for apps targeting Android 4.4 or earlier only, so it is denied for the
-- actions of the table; for the others Android throws itself, "Service Intent must be explicit", which stays.
function serviceof(intent, target)
    if intent == nil then
        return nil
    end
    local component = intent:getComponent()
    local packageName = (component ~= nil and component:getPackageName() or intent:getPackage())
    local action = intent:getAction()
    for _, service in ipairs(SERVICES) do
        if packageName == nil then
            if target < 21 and service.action ~= nil and action == service.action then
                return service
            end
        elseif packageName == service.package and (service.action == nil or action == service.action or
                (component ~= nil and component:getClassName() == service.class)) then
            return service
        end
    end
    return nil
end

-- The authority of the first argument, a Uri or a name, without a user in front, like 0@cn.nubia.identity, which
-- the provider of the user of the app answers too (like contentresolver_query, which a unit test checks)
function authorityof(value)
    if value == nil then
        return nil
    end
    local authority = (type(value) == 'string' and value or value:getAuthority())
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^.*@', ''))
end

function before(hook, param)
    local method = hook:getMethodName()
    if method == 'getService' or method == 'checkService' then
        -- Not found, like on other devices; the filter passes the services of SYSTEM only
        param:setResult(nil)
        return true, param:getArgument(0)
    elseif method == 'doConnected' then
        -- ServiceDispatcher.doConnected(name, service, [session,] dead): the binder of any service with an interface of
        -- DESCRIPTORS, but not of the services of the app itself, which answer without a call to another process
        local name = param:getArgument(0)
        local service = param:getArgument(1)
        if name == nil or service == nil or name:getPackageName() == param:getPackageName() then
            return false
        end
        local ok, descriptor = pcall(service.getInterfaceDescriptor, service)
        if not ok or descriptor == nil or DESCRIPTORS[descriptor] == nil then
            return false
        end
        param:setArgument(1, nil)
        return true, descriptor
    elseif string.find(method, '^bind') then
        -- bindService, bindIsolatedService and bindServiceAsUser: the intent is the first argument
        local target = param:getApplicationContext():getApplicationInfo().targetSdkVersion
        local intent = param:getArgument(0)
        local service = serviceof(intent, target)
        if service == nil then
            return false
        end
        -- A copy to a package which cannot exist, so Android itself fails the binding with the connection registered,
        -- else unbindService throws; an isolated service needs a component (see component_hidden)
        local copy = intent:clone()
        copy:setSelector(nil)
        copy:setComponent(nil)
        copy:setPackage(service.package .. ':hidden')
        if string.find(method, '^bindIsolated') and intent:getComponent() ~= nil then
            copy:setComponent(luajava.newInstance('android.content.ComponentName', service.package .. ':hidden',
                    intent:getComponent():getClassName()))
        end
        param:setArgument(0, copy)
        return true, service.package
    end

    -- acquireContentProviderClient and acquireUnstableContentProviderClient: nothing found;
    -- call: Android throws, with the words of the form of the call (see provider_hidden)
    local target = param:getArgument(0)
    local authority = authorityof(target)
    if authority == nil or PROVIDERS[authority] == nil then
        return false
    end
    if method == 'call' then
        local what = (type(target) == 'string' and 'authority ' or 'URI ') .. tostring(target)
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', 'Unknown ' .. what))
    else
        param:setResult(nil)
    end
    return true, authority
end
