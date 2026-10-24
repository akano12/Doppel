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

-- FirebaseAnalytics and FirebaseCrashlytics
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local name = 'setAnalyticsCollectionEnabled'
    if hook:getClassName() == 'com.google.firebase.crashlytics.FirebaseCrashlytics' then
        name = 'setCrashlyticsCollectionEnabled'
    end

    local set = result[name]
    if set == nil or type(set) ~= 'function' then
        return false
    end

    set(result, false)
    return true
end
