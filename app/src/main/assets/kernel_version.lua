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

-- The kernel release and version identify the exact firmware build, like 6.6.50-android15-8-g8adecb593e9b,
-- also in /proc/sys/kernel/osrelease and version and /proc/version: they become the release and version of Os.uname
-- (see uname). SELinux denies these files to untrusted apps, while privileged apps can read /proc/version on
-- Android 8.1 and system apps on later versions, like Settings.
-- /proc/version also names who built the kernel on which host with which compiler, which can show the vendor or a
-- custom kernel: these become those of the AOSP build of the same kernel version (see kernelbuild).
-- Also by other paths, like /proc/self/root/proc/..., see XParam.opens. Not covered is native code.
function filter(hook)
    return { '*kernel/osrelease*', '*kernel/version*', '*proc/version*' }
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    local osrelease = param:opens('/proc/sys/kernel/osrelease')
    local proc = param:opens('/proc/version')
    if not osrelease and not proc and not param:opens('/proc/sys/kernel/version') then
        return false
    end
    local text = param:getFileText()
    if string.len(text) == 0 then
        return false
    end

    local release = string.match(text, '^Linux version (%S+)') or string.match(text, '^%S+') or ''
    local version = '#1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970'
    local fake
    if osrelease then
        fake = kernelrelease(release) .. '\n'
    elseif proc then
        local builder, compiler = kernelbuild(release)
        fake = 'Linux version ' .. kernelrelease(release) .. ' (' .. builder .. ') (' .. compiler .. ') ' .. version .. '\n'
    else
        fake = version .. '\n'
    end
    if fake == text then
        return false
    end
    param:setFileText(fake)
    return true
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

-- The builder and compiler of AOSP's own kernel builds of each kernel version, as the /proc/version of the emulator
-- kernels shows them: GCC 4.9 for the old ones, with its trailing space, Android's clang later, and Kleaf as the builder
-- from 6.6 on.
-- shortcut: a version between two builds, like 4.4, 4.9 and 4.19, or newer than 6.12, gets the compiler of the older
-- build, the heads of 6.1 and 6.18 (see kernelrelease) were built with r487747c and r596125 instead of r487747 and
-- r536225, and the last releases of 3.18 to 5.4 get the compiler of an older emulator kernel of the same version,
-- upgrade when a banner of each is known.
-- shortcut: kernels before 4.14 get the GCC of the x86 emulator kernel, whose builder was android-build at a host of
-- Google, with the builder of the later ones, which no arm64 build of that time had; only privileged apps can read
-- /proc/version on Android 8.1, upgrade when an arm64 banner of 3.18 to 4.9 is known.
function kernelbuild(release)
    local major, minor = string.match(release, '^(%d+)%.(%d+)')
    local version = major and tonumber(major) * 1000 + tonumber(minor) or 6012
    local builds = {
        { 0, 'build-user@build-host', 'gcc version 4.9 20140827 (prerelease) (GCC) ' },
        { 4014, 'build-user@build-host', 'Android (5900059 based on r365631c) clang version 9.0.8 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project 207d7abc1a2abf3ef8d4301736d6a7ebc224a290) ' ..
                '(based on LLVM 9.0.8svn)' },
        { 5004, 'build-user@build-host', 'Android (7284624, based on r416183b) clang version 12.0.5 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project c935d99d7cf2016289302412d708641d52d2f7ee), ' ..
                'LLD 12.0.5 (/buildbot/src/android/llvm-toolchain/out/llvm-project/lld ' ..
                'c935d99d7cf2016289302412d708641d52d2f7ee)' },
        { 5015, 'build-user@build-host', 'Android (8508608, based on r450784e) clang version 14.0.7 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project 4c603efb0cca074e9238af8b4106c30add4418f6), ' ..
                'LLD 14.0.7' },
        { 6001, 'build-user@build-host', 'Android (9796371, based on r487747) clang version 17.0.0 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project d9f89f4d16663d5012e5c09495f3b30ece3d2362), ' ..
                'LLD 17.0.0' },
        { 6006, 'kleaf@build-host', 'Android (11368308, +pgo, +bolt, +lto, +mlgo, based on r510928) clang version 18.0.0 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project 477610d4d0d988e69dbc3fae4fe86bff3f07f2b5), ' ..
                'LLD 18.0.0' },
        { 6012, 'kleaf@build-host', 'Android (14043575, +pgo, +bolt, +lto, +mlgo, based on r536225) clang version 19.0.1 ' ..
                '(https://android.googlesource.com/toolchain/llvm-project b3a530ec6537146650e42be89f1089e9a3588460), ' ..
                'LLD 19.0.1' }
    }
    local build = builds[1]
    for i = 2, #builds do
        if version >= builds[i][1] then
            build = builds[i]
        end
    end
    return build[2], build[3]
end
