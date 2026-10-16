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

-- The kernel release and version identify the exact firmware build, like 6.6.50-android15-8-g8adecb593e9b,
-- the host name can be set by users. The architecture stays, because apps select native code with it.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local release = result.release
    local short = string.match(release, '^[%d%.]+')
    if short == nil then
        short = 'unknown'
    end

    local fake = luajava.newInstance('android.system.StructUtsname',
            result.sysname, 'localhost', short, '#1 SMP PREEMPT', result.machine)
    param:setResult(fake)
    return true, release, short
end
