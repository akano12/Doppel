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

-- The app set id which the library of Play services makes itself when it cannot bind the service of Play services
-- (see identifier_services), kept in its preferences app_set_id_storage, nil when there is none; read with the hooks
-- off, else the hooks of the preferences would report a read the app did not make, and without telling StrictMode of
-- the disk read or of making the folder of the preferences, which the getter itself does not do (allowThreadDiskWrites
-- allows both)
function stored(param)
    local ok, id = pcall(function()
        return param:unhooked(function()
            local strict = luajava.bindClass('android.os.StrictMode')
            local policy = strict:allowThreadDiskWrites()
            local done, value = pcall(function()
                local preferences = param:getApplicationContext():getSharedPreferences('app_set_id_storage', 0)
                return preferences:getString('app_set_id', nil)
            end)
            strict:setThreadPolicy(policy)
            return done and value or nil
        end)
    end)
    return ok and id or nil
end

-- The app set id of Play services (AppSetIdInfo) is the zero id. The advertising ids of Play services and microG
-- (adid_service) and the ids of AdServices (adservices_id) are faked where the app gets them, and the one of Huawei is
-- not there (identifier_services and the settings and providers of identifiers), so an AdvertisingIdClient.Info, AdId
-- or AppSetId the app makes itself keeps its values.
-- shortcut: the getter also rewrites an AppSetIdInfo the app makes itself, which a device without Doppel does not,
-- upgrade by faking the reply of the app set service of Play services instead
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake = '00000000-0000-0000-0000-000000000000'
    -- Like after the user deleted the id
    if result == fake then
        return false
    end
    -- The app set id the library made itself stays, the id of the app on a device without Play services, which the
    -- app finds in its preferences too; an id of Play services is the zero id
    if hook:getClassName() == 'com.google.android.gms.appset.AppSetIdInfo' and result == stored(param) then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
