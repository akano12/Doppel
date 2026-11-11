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
-- service of ColorOS, which only the identifier service is denied, and which PackageManager does not find (see after)
-- shortcut: PackageManager still finds the packages of the rows without an action and their services, which binding
-- fails for, upgrade when the user decides to hide such packages as a whole
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

-- The interfaces of the identifier services (their Binder descriptors), so a service of another preinstalled package
-- or a new device maker with one of them is not reached either; the binder of such a connection is left out, like a
-- service giving none (onNullBinding). From the stubs of the MSA SDK 2.2, Android_CN_OAID and the SDKs of Huawei and
-- Honor.
-- shortcut: identifier services which the user installs and SERVICES does not list are reached, upgrade when one
-- turns up outside the table
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
-- shortcut: change observers and notifications (ContentService) and getType (ActivityManager) find the provider in
-- the system, which on a device without it throws "Failed to find provider" to apps targeting Android 8 and later and
-- answers no type, and getProviderInfo and queryContentProviders of PackageManager still find it, upgrade when SDKs
-- check these
local PROVIDERS = {
    ['cn.nubia.identity'] = true, -- Nubia
}

-- Whether the class of a component of the package of a service of the table offers its action, so it is the
-- identifier service bound by its class, like with the class from resolveService; asked once per process, with the
-- hooks off, else the hooks of Get.Applications would report a call the app did not make. Also disabled services and
-- before the user unlocks the device (MATCH_DISABLED_COMPONENTS, MATCH_DIRECT_BOOT_AWARE and UNAWARE), so the answer
-- kept stays right; Android fails the binding of a service which is not available itself, like the denial.
function offers(param, className, service)
    local context = param:getApplicationContext()
    local key = 'identifiers.' .. service.package .. '/' .. className
    local offered = param:getValue(key, context)
    if offered == nil then
        local ok, found = pcall(function()
            return param:unhooked(function()
                local intent = luajava.newInstance('android.content.Intent', service.action)
                intent:setPackage(service.package)
                local all = context:getPackageManager():queryIntentServices(intent, 0x200 + 0x40000 + 0x80000)
                for index = 0, all:size() - 1 do
                    if all:get(index).serviceInfo.name == className then
                        return true
                    end
                end
                return false
            end)
        end)
        if not ok then
            return false
        end
        offered = found
        param:putValue(key, offered, context)
    end
    return offered
end

-- Whether a package is preinstalled, also updated since (ApplicationInfo.FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP), as
-- the identifier services of device makers are, so the interface of the services of apps of the user is not asked,
-- which a device without Doppel never does; asked once per process with the hooks off, like offers. A lookup which
-- fails is not kept and counts as an app of the user.
function preinstalled(param, packageName)
    local context = param:getApplicationContext()
    local key = 'identifiers.system.' .. packageName
    local system = param:getValue(key, context)
    if system == nil then
        local ok, flags = pcall(function()
            return param:unhooked(function()
                return context:getPackageManager():getApplicationInfo(packageName, 0).flags
            end)
        end)
        if not ok then
            return false
        end
        system = (bit32.band(flags, 0x1 + 0x80) ~= 0)
        param:putValue(key, system, context)
    end
    return system
end

-- Whether a class of a package is the identifier service of a row of SERVICES with an action: the class of the row
-- or a class offering the action (see offers)
function identifier(param, packageName, className)
    for _, service in ipairs(SERVICES) do
        if service.action ~= nil and packageName == service.package and
                (className == service.class or offers(param, className, service)) then
            return true
        end
    end
    return false
end

-- Whether a provider has an authority of PROVIDERS; a provider can have several, separated by ;
function provider(info)
    for authority in string.gmatch(info.authority or '', '[^;]+') do
        if PROVIDERS[authority] ~= nil then
            return true
        end
    end
    return false
end

-- A PackageInfo without the identifier services and the provider in its arrays of services and providers, which
-- Android fills for GET_SERVICES and GET_PROVIDERS, null instead of empty when none is left, like Android answers
-- for a package without one (ArrayUtils.trimToSize); true when it left out any
function strip(param, info)
    local changed = false
    local listed = false
    for _, service in ipairs(SERVICES) do
        listed = listed or (service.action ~= nil and service.package == info.packageName)
    end
    for _, field in ipairs({ 'services', 'providers' }) do
        local items = info[field]
        if items ~= nil and (field == 'providers' or listed) then
            local kept = {}
            for index = 1, items['length'] do
                local item = items[index]
                if field == 'services' and not identifier(param, item.packageName, item.name) or
                        field == 'providers' and not provider(item) then
                    kept[#kept + 1] = item
                end
            end
            if #kept < items['length'] then
                -- LuaJ makes an array of the type of the field of the list
                info[field] = (#kept > 0 and kept or nil)
                changed = true
            end
        end
    end
    return changed
end

-- The suffix of the class names which no app has, random for the process, so an app cannot ask for a name which
-- looks like a substitute; the same function and suffix as in package_probe, of the app and new each boot
function suffixof(param)
    local suffix = param:getSetting('package_probe.suffix')
    if suffix ~= nil then
        return suffix
    end
    local context = param:getApplicationContext()
    suffix = param:getValue('package_probe.suffix', context)
    if suffix == nil then
        suffix = '.x' .. math.random(100000000, 999999999)
        param:putValue('package_probe.suffix', suffix, context)
    end
    return suffix
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

-- The service of the table an intent binds, nil when none. An intent naming no package (implicit) is resolved by
-- Android to the app offering the action for apps targeting Android 4.4 or earlier only, so it is denied for the
-- actions of the table; for the others Android throws itself, "Service Intent must be explicit", which stays.
-- A component names the service by its class, also without the action, so a class offering the action is denied too.
function serviceof(param, intent, target)
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
                (component ~= nil and (component:getClassName() == service.class or
                        offers(param, component:getClassName(), service)))) then
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
    if hook:getClassName() == 'android.content.pm.PackageManager' then
        -- getServiceInfo of an identifier service looks up a class which no app has, so Android itself throws
        -- NameNotFoundException, with its stack trace; after gives it the name the app asked for. The others: after.
        local component = (method == 'getServiceInfo' and param:getArgument(0) or nil)
        if component == nil or not identifier(param, component:getPackageName(), component:getClassName()) then
            return false
        end
        param:setArgument(0, luajava.newInstance('android.content.ComponentName', component:getPackageName(),
                component:getClassName() .. suffixof(param)))
        return true, component:getPackageName()
    elseif method == 'getService' or method == 'checkService' then
        -- Not found, like on other devices; the filter passes the services of SYSTEM only
        param:setResult(nil)
        return true, param:getArgument(0)
    elseif method == 'doConnected' then
        -- ServiceDispatcher.doConnected(name, service, [session,] dead): the binder of any service of a preinstalled
        -- package with an interface of DESCRIPTORS, but not of the services of the app itself, which answer without a
        -- call to another process, nor of apps of the user, which would see the call asking for the interface
        local name = param:getArgument(0)
        local service = param:getArgument(1)
        if name == nil or service == nil or name:getPackageName() == param:getPackageName() or
                not preinstalled(param, name:getPackageName()) then
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
        local service = serviceof(param, intent, target)
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

    -- acquireContentProviderClient, acquireUnstableContentProviderClient, the hidden acquireProvider and
    -- acquireUnstableProvider, ActivityThread.acquireProvider(context, authority, user, stable) under them and, on
    -- Android 8.1 and 9, IActivityManager.getContentProvider(thread, authority, user, stable) under that: nothing
    -- found; call: Android throws, with the words of the form of the call (see provider_hidden)
    local target = param:getArgument((hook:getClassName() == 'android.app.ActivityThread' or
            method == 'getContentProvider') and 1 or 0)
    local authority = authorityof(target)
    if authority == nil or PROVIDERS[authority] == nil then
        return false
    end
    if method == 'call' then
        -- A call without a method throws NullPointerException("method"), before Android looks for the provider
        if param:getArgument(1) == nil then
            return false
        end
        local what = (type(target) == 'string' and 'authority ' or 'URI ') .. tostring(target)
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', 'Unknown ' .. what))
    else
        param:setResult(nil)
    end
    return true, authority
end

-- PackageManager finds neither the identifier services nor the provider, like on a device without them: lists leave
-- them out, a lookup of a service resolves to the next service of the same query, like Android, which takes the first
-- (resolveServiceInternal), and of the provider finds none; the overloads of Android 13 and later call the others,
-- which leave out nothing more then
function after(hook, param)
    if hook:getClassName() ~= 'android.content.pm.PackageManager' then
        return false
    end
    local method = hook:getMethodName()
    if method == 'getServiceInfo' then
        -- The exception of Android for the class which no app has, with the name the app asked for: the same class,
        -- stack trace and cause, like package_probe
        local ex, component, suffix = param:getException(), param:getArgument(0), suffixof(param)
        local name = (component ~= nil and component:getClassName() or '')
        if ex == nil or ex:getMessage() == nil or string.len(name) <= string.len(suffix) or
                string.sub(name, -string.len(suffix)) ~= suffix then
            return false
        end
        local message = replace(ex:getMessage(), name, string.sub(name, 1, -string.len(suffix) - 1))
        if message == ex:getMessage() then
            return false
        end
        -- An exception without a constructor with a message gets the message itself (see package_probe)
        local ok, fake = pcall(function()
            local made = luajava.new(ex:getClass(), message)
            if made:getMessage() ~= message then
                error('No constructor with a message')
            end
            made:setStackTrace(ex:getStackTrace())
            if ex:getCause() ~= nil then
                made:initCause(ex:getCause())
            end
            return made
        end)
        if ok then
            param:setResult(fake)
        else
            pcall(function()
                param:setMessage(ex, message)
            end)
        end
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end
    if method == 'resolveContentProvider' or method == 'resolveContentProviderAsUser' then
        if not provider(result) then
            return false
        end
        param:setResult(nil)
        return true, result.authority
    elseif method == 'resolveService' or method == 'resolveServiceAsUser' then
        local info = result.serviceInfo
        if not identifier(param, info.packageName, info.name) then
            return false
        end
        local pm, intent, flags = param:getThis(), param:getArgument(0), param:getArgument(1)
        local ok, all = pcall(function()
            return param:unhooked(function()
                if method == 'resolveService' then
                    return pm:queryIntentServices(intent, flags)
                end
                return pm:queryIntentServicesAsUser(intent, flags, param:getArgument(2))
            end)
        end)
        local fake = nil
        for index = 0, (ok and all ~= nil) and all:size() - 1 or -1 do
            local other = all:get(index).serviceInfo
            if not identifier(param, other.packageName, other.name) then
                fake = all:get(index)
                break
            end
        end
        param:setResult(fake)
        return true, info.packageName
    elseif method == 'queryIntentServices' or method == 'queryIntentServicesAsUser' then
        local removed = nil
        for index = result:size() - 1, 0, -1 do
            local info = result:get(index).serviceInfo
            if identifier(param, info.packageName, info.name) then
                result:remove(index)
                removed = info.packageName
            end
        end
        return removed ~= nil, removed
    elseif method == 'getInstalledPackages' or method == 'getInstalledPackagesAsUser' then
        local changed = nil
        for index = 0, result:size() - 1 do
            if strip(param, result:get(index)) then
                changed = result:get(index).packageName
            end
        end
        return changed ~= nil, changed
    end
    -- getPackageInfo and getPackageInfoAsUser
    return strip(param, result), result.packageName
end
