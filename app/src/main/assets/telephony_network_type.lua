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

-- The network type of TelephonyManager.getNetworkType is unknown (0) for an app for Android 10 or later, which the phone
-- service of Android 10 to 12 checks like the type of the data network, while an app for Android 9 or earlier gets it
-- without a check (getNetworkTypeForSubscriber of PhoneInterfaceManager); the exception of a refused call stays
function after(hook, param)
    local result = param:getResult()
    if result == 0 or param:getException() ~= nil then
        return false
    end
    if param:getApplicationContext():getApplicationInfo().targetSdkVersion < 29 then
        return false
    end

    param:setResult(0)
    return true, result, 0
end
