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

-- The kernel release and version identify the exact firmware build, like 6.6.50-android15-8-g8adecb593e9b,
-- the host name can be set by users. The architecture stays, because apps select native code with it.
-- The release becomes the one of the unstamped build of the newest AOSP kernel of the same version (see kernelrelease).
-- The version becomes the one the kernel build of AOSP gives without a build stamp, build 1 at time 0
-- (KBUILD_BUILD_VERSION and SOURCE_DATE_EPOCH), in the form every kernel has, with the time of its build at the end.
-- Also beneath Os.uname, by Libcore.os, which apps reach by reflection.
-- shortcut: native uname and VintfRuntimeInfo keep the real values; VintfRuntimeInfo is a hidden test API, which apps
-- reach on Android 8.1, without hidden API rules, and else only where those rules let them, upgrade when apps are seen
-- to use them.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local release = result.release
    local fake = kernelrelease(release)
    param:setResult(luajava.newInstance('android.system.StructUtsname',
            result.sysname, 'localhost', fake, '#1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970', result.machine))
    return true, release, fake
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
