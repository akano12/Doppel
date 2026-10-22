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

-- Apps check whether specific apps are installed, like banking apps, messengers or apps for rooted devices.
-- The package is reported as not installed, like the package lists of the group Get.Applications are filtered.
-- Own packages and system packages stay visible, because the Android framework looks them up in the app process.

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end

    local packageName = param:getArgument(0)
    if type(packageName) == 'userdata' then
        packageName = packageName:getPackageName() -- VersionedPackage
    end
    if param:isPackageVisible(packageName) then
        return false
    end

    local method = hook:getMethodName()
    if method == 'getLaunchIntentForPackage' or method == 'getLeanbackLaunchIntentForPackage' then
        -- No launch intent, like for an app which is not installed
        if param:getResult() == nil then
            return false
        end
        param:setResult(nil)
        return true, packageName
    end

    local ex
    if method == 'getInstallerPackageName' or method == 'getApplicationEnabledSetting' then
        ex = luajava.newInstance('java.lang.IllegalArgumentException', 'Unknown package: ' .. packageName)
    else
        ex = luajava.newInstance('android.content.pm.PackageManager$NameNotFoundException', packageName)
    end
    param:setResult(ex)
    return true, packageName
end
