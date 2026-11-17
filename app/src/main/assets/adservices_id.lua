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

-- The ids of AdServices come in the results the service sends, which AdIdManager and AppSetIdManager make the AdId and
-- AppSetId of: the zero id, with ad tracking limited for the AdId, which AdServices answers a caller its provider
-- refuses and Play services answers after the user deleted the id (see adid_service), and the scope of the app
-- (SCOPE_APP) for the AppSetId, which AdServices answers without a provider, so the getters, toString, hashCode and
-- equals of the id agree. Without a provider AdServices answers the zero id with ad tracking not limited; limited
-- agrees with the advertising id of Play services and asks ads not to track. An AdId or AppSetId the app makes itself
-- keeps its values, like on a device without Doppel. A result of an error stays, the managers read no id of it.
local ZERO = '00000000-0000-0000-0000-000000000000'
local SCOPE_APP = 1

-- Whether the service sent the result: the callback of the manager unparcels it in onTransact, by
-- Parcel.readTypedObject or by the creator itself, while a result the app wrote itself and unparcels, by
-- Parcel.readParcelable or the creator of Parcel.readParcelableCreator, keeps its values like on a device without
-- Doppel. The caller is the frame below the frames named like the hooked method, which the hook engine can replace by
-- one of a class of its own with a random name, and below the methods of Parcel. A stack of another form gets the
-- zero id, so a caller of AdServices this does not know does not get the real id.
-- shortcut: frames carry only class names, so a result an app unparcels from a class it named like one of AdServices
-- gets the zero id too; upgrade by asking the class loader of the caller, which is the boot class path only from
-- Android 13
function fromservice()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    local hooked, parcel = false, false
    for index = 1, frames['length'] do
        local frame = frames[index]
        local class = frame:getClassName()
        local method = frame:getMethodName()
        if not hooked then
            hooked = (method == 'createFromParcel')
        elseif class == 'android.os.Parcel' then
            parcel = true
        elseif parcel or method ~= 'createFromParcel' then
            return string.find(class, 'android.adservices.', 1, true) == 1
        end
    end
    return true
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or not result:isSuccess() or not fromservice() then
        return false
    end

    local adid = (hook:getClassName() == 'android.adservices.adid.GetAdIdResult')
    local id
    if adid then
        id = result:getAdId()
        if id == ZERO and result:isLatEnabled() then
            return false
        end
    else
        id = result:getAppSetId()
        if id == ZERO and result:getAppSetIdScope() == SCOPE_APP then
            return false
        end
    end

    -- The fields are final, so a result of the same status is built, like the service builds its own
    local builder = luajava.new(param:findClass(hook:getClassName() .. '$Builder'))
    builder:setStatusCode(result:getStatusCode())
    builder:setErrorMessage(result:getErrorMessage())
    if adid then
        builder:setAdId(ZERO)
        builder:setLatEnabled(true)
    else
        builder:setAppSetId(ZERO)
        builder:setAppSetIdScope(SCOPE_APP)
    end
    param:setResult(builder:build())
    return true, id, ZERO
end
