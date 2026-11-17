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

-- Java system properties with the kernel release (os.version) and with the device model and firmware build,
-- like 'Dalvik/2.1.0 (Linux; U; Android 15; Pixel 9 Build/AP4A.250105.002)' (http.agent), which HTTP clients send.
-- The properties are changed once, when the app starts, in the static fields of java.lang.System
-- which hold them, because hooking System.getProperty would slow down every call.

function after(hook, param)
    local props = param:getResult()
    if props == nil then
        return false
    end

    local restricted = false
    local old = {}
    local new = {}

    -- Only the own entries, os.version is in the defaults of the overridable properties
    local version = props:get('os.version')
    if version ~= nil then
        local fake = kernelrelease(version)
        if fake ~= version then
            props:put('os.version', fake)
            restricted = true
            old[#old + 1] = version
            new[#new + 1] = fake
        end
    end

    local agent = props:get('http.agent')
    if agent ~= nil then
        -- Like RuntimeInit makes it of the fake Build.MODEL and Build.ID (unknown, see generic_unknown_value), with
        -- the model only for a release build, so the agent agrees with Build
        local fake, count = string.gsub(agent, '(; Android [^;)]*); [^;)]* Build/[^)]*%)$', '%1; unknown Build/unknown)')
        if count == 0 then
            fake = (string.gsub(agent, ' Build/[^)]*%)$', ' Build/unknown)'))
        end
        if fake ~= agent then
            props:put('http.agent', fake)
            restricted = true
            old[#old + 1] = agent
            new[#new + 1] = fake
        end
    end

    return restricted, table.concat(old, ' '), table.concat(new, ' ')
end

-- The release like the unstamped build of the newest AOSP kernel of the same kernel version gives it, so neither the
-- build nor the sublevel of the device's kernel shows: the head of the GKI branch of its first Android release with its
-- KMI generation, like 6.6.144-android15-8-maybe-dirty, the last release of the AOSP branch of older kernels, which
-- keep only their digits like a build without git, else the first release of the version, like 7.1.0.
-- The arm64 GKI kernels from 6.6 on end in their page size, -4k or -16k, which stays, as Os.sysconf shows it anyway.
-- Used by uname, /proc (see kernel_version) and os.version (see system_properties).
-- shortcut: the heads of the branches as of November 2026, upgrade when a branch gets a new KMI generation.
-- shortcut: a page size followed by more text, like by a vendor build, is lost, upgrade when such a build is known.
-- shortcut: kernels newer than 6.18 become their first release without a GKI part, upgrade when the GKI branch of
-- Android 18 is known.
function kernelrelease(release)
    local version = string.match(release, '^%d+%.%d+')
    if version == nil then
        return 'unknown'
    end
    local heads = { ['3.18'] = '3.18.140', ['4.4'] = '4.4.302', ['4.9'] = '4.9.337', ['4.14'] = '4.14.336',
                    ['4.19'] = '4.19.325', ['5.4'] = '5.4.302', ['5.10'] = '5.10.270-android12-9-maybe-dirty',
                    ['5.15'] = '5.15.220-android13-8-maybe-dirty', ['6.1'] = '6.1.177-android14-11-maybe-dirty',
                    ['6.6'] = '6.6.144-android15-8-maybe-dirty', ['6.12'] = '6.12.96-android16-7-maybe-dirty',
                    ['6.18'] = '6.18.33-android17-6-maybe-dirty' }
    local head = heads[version]
    if head == nil then
        return version .. '.0'
    end
    if version == '6.6' or version == '6.12' or version == '6.18' then
        head = head .. (string.match(release, '%-16k$') or string.match(release, '%-4k$') or '')
    end
    return head
end
