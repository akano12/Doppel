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

-- Files of /sys name the hardware of the device, and Samsung phones let apps read files which name the device:
-- the id of the chip (/sys/devices/system/chip-id/unique_id, which stays until the device is scrapped), the id
-- of the SD card or the storage (/sys/block/mmcblk0/device/cid and serial, /sys/class/sec/sdinfo/data), the serial
-- number of UFS storage (/sys/class/scsi_host/host0/unique_number on Samsung, the serial_number of its string
-- descriptors on other devices) with its manufacturer and model, the
-- calibration of the light sensor, the files of the touchscreen (/sys/class/sec/tsp) and of the fingerprint
-- sensor (/sys/class/fingerprint/fingerprint), the chip and storage models (see FILES), the folders of VPN
-- interfaces and su and Magisk (Read.Debugging). For restricted apps the files do not exist: they cannot be opened,
-- read, listed or found, with java.io, android.system.Os and java.nio.file. The paths are resolved first,
-- so the links of /sys do not lead to the files either, like /sys/bus/mmc/devices/mmc0:0001/cid.
-- Each variant hides the files of its group; cat, head and other commands run by the app fail for them too
-- (see shell_commands), and folders listed with java.nio.file leave the names of hidden files out.
-- Not covered are native code and the entries of a listed folder opened by a SecureDirectoryStream of java.nio
-- (Files.newDirectoryStream gives one, of which newByteChannel and getFileAttributeView use openat and fstatat
-- with a name relative to the folder), like a hidden file of a folder which is not hidden itself.

-- The files of each group, as patterns of the path; a pattern ending in / covers the files of a folder
local FILES = {
    ['Read.Identifiers'] = {
        '/chip%-id/unique_id$', '/chip%-id/lot_id$', '/chip%-id/lot_id2$',
        '/mmc%d+:%x+/cid$', '/mmc%d+:%x+/serial$', '/sec/sdinfo/data$',
        '/scsi_host/host%d+/unique_number$', '/string_descriptors/serial_number$', '/soc%d+/serial_number$' },
    -- The folder of the chip of the bus of systems on a chip (/sys/devices/soc0, which Qualcomm lets apps read
    -- until Android 11, its soc_id and family later), the model, vendor and revision of SCSI storage, like UFS,
    -- (/sys/block/sda/device, a link to the device, h:c:t:l), the camera modules of Samsung (/sys/class/camera)
    -- and the input devices and storage of /proc
    ['Use.Tracking'] = {
        '/chip%-id$', '/chip%-id/', '/soc%d+$', '/soc%d+/',
        '/mmc%d+:%x+/date$', '/mmc%d+:%x+/manfid$', '/mmc%d+:%x+/oemid$', '/mmc%d+:%x+/name$',
        '/scsi_host/host%d+/man_id$', '/string_descriptors/manufacturer_name$',
        '/string_descriptors/product_name$', '/string_descriptors/oem_id$',
        '/block/sd%a+/device/model$', '/block/sd%a+/device/vendor$', '/block/sd%a+/device/rev$',
        '/%d+:%d+:%d+:%d+/model$', '/%d+:%d+:%d+:%d+/vendor$', '/%d+:%d+:%d+:%d+/rev$',
        '/sensors/light_sensor/light_cal$', '/sec/tsp$', '/sec/tsp/', '/class/camera/', '/virtual/camera/',
        '/proc/scsi/', '/proc/bus/input/devices$' },
    ['Read.Biometrics'] = {
        '/fingerprint/fingerprint$', '/fingerprint/fingerprint/', '/sec/tsp/fod_info$', '/sec/tsp/fod_pos$' },
    -- The files of /proc/net, a link to /proc/self/net, list the addresses, routes, neighbours and hardware addresses
    -- of the interfaces and the addresses of the sockets, which SELinux lets apps read on Android 9 and before and
    -- apps for those versions later; only opening them fails (hooks of open of Read.Network), that they exist tells nothing
    ['Read.Network'] = { '/proc/net/', '/proc/%d+/net/', '/proc/%d+/task/%d+/net/', '/proc/self/net/',
                         '/proc/thread%-self/net/' },
    -- The folders of the network interfaces of VPNs (see isvpninterface), of which apps could tell they exist on
    -- Android 8.1; SELinux denies apps the folders on later versions and listing them on all
    ['Read.VPN'] = { '^/sys/class/net/([^/]+)', '^/sys/devices/virtual/net/([^/]+)' },
    -- su of root managers, in the folders of programs of the system and the ones they used to install to,
    -- and the programs and folders of Magisk
    ['Read.Debugging'] = {
        '^/system/bin/su$', '^/system/xbin/su$', '^/sbin/su$', '^/su$', '^/su/', '^/system/bin/failsafe/su$',
        '^/system/sd/xbin/su$', '^/system/bin/%.ext/su$', '^/system/usr/we%-need%-root/su$', '^/data/local/su$',
        '^/data/local/bin/su$', '^/data/local/xbin/su$', '^/cache/su$', '^/dev/su$', '^/vendor/bin/su$',
        '^/vendor/xbin/su$', '^/product/bin/su$', '^/system_ext/bin/su$', '^/odm/bin/su$',
        '^/system/app/Superuser%.apk$', '^/system/app/Superuser/', '^/system/app/SuperSU/',
        '^/system/bin/magisk', '^/system/bin/resetprop$', '^/system/bin/supolicy$', '^/sbin/magisk', '^/sbin/%.magisk',
        '^/debug_ramdisk/', '^/data/adb/' },
    -- The files of the features, libraries and system apps of the device (/system/etc/permissions, also of /vendor,
    -- /product and the others, and sysconfig), like com.samsung.feature.*, which apps can read
    ['Read.Features'] = { '/etc/permissions$', '/etc/permissions/', '/etc/sysconfig$', '/etc/sysconfig/' },
    -- The permissions of the system apps, a file per app, like privapp-permissions-com.samsung.android.dialer.xml
    ['Get.Applications'] = { '/etc/permissions/privapp%-permissions%-[^/]*$' },
}

-- The paths the script runs for, of the groups with files outside /sys (see filter)
local FILTERS = {
    ['Use.Tracking'] = { '*sys/*', '*proc/scsi/*', '*proc/bus/input/*' },
    ['Read.Network'] = { '*net/*' },
    ['Read.VPN'] = { '*net/*' },
    ['Read.Debugging'] = { '/system/bin/*', '/system/xbin/*', '/system/sd/*', '/system/usr/*', '/system/app/Super*',
                           '/sbin/*', '/su*', '/data/local/*', '/cache/su', '/dev/su', '/vendor/bin/*',
                           '/vendor/xbin/*', '/product/bin/*', '/system_ext/bin/*', '/odm/bin/*', '/debug_ramdisk*',
                           '/data/adb*', '*bin/su*', '*magisk*', '*debug_ramdisk*' },
    ['Read.Features'] = { '*etc/permissions*', '*etc/sysconfig*' },
    ['Get.Applications'] = { '*etc/permissions/privapp-permissions-*' },
}

-- Whether a network interface is of a VPN, like vpn and interface_entries, which a test compares
function isvpninterface(name)
    if name == nil then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- The error of a file which does not exist, OsConstants.ENOENT, the same on every Linux
local ENOENT = 2

-- The script runs for the files of /sys only (with net/ in the path for Read.Network and Read.VPN, see FILTERS for
-- the others), also by other paths, like /proc/self/root/sys/..., /data/../sys/...
-- or sys/... relative to /, the folder apps run in; see XCore.getFilter. Not covered are links of apps themselves
-- to the files, which have paths without sys/.
function filter(hook)
    return FILTERS[hook:getGroup()] or { '*sys/*' }
end

-- Whether paths are hidden for a group, per thread, since a script runs once per thread (see docs/DEFINE.md): files
-- of /sys are read often, like the frequencies of the processors, and resolving their paths takes a system call.
-- The paths of /sys stay the same while the app runs; the cache starts over when it is full.
local HIDDEN = {}
local cached = 0

-- The group, class, method and return type of a hook, which do not change, without asking the hook each time
local HOOKS = {}

function about(hook)
    local known = HOOKS[hook]
    if known == nil then
        known = { group = hook:getGroup(), class = hook:getClassName(), method = hook:getMethodName(),
                  returnType = hook:getReturnType() }
        HOOKS[hook] = known
    end
    return known
end

-- The path without a link to the root folder in front, like /proc/self/root/system/xbin/su, also where the
-- links cannot be resolved (like shell_commands)
function unrooted(path)
    for _, prefix in ipairs({ '^/proc/self/root/', '^/proc/thread%-self/root/', '^/proc/%d+/root/' }) do
        path = string.gsub(path, prefix, '/')
    end
    return path
end

-- The path a path leads to, by the links of /sys, or the path as it is
function resolve(path)
    local ok, resolved = pcall(function()
        return luajava.newInstance('java.io.File', path):getCanonicalPath()
    end)
    if ok and resolved ~= nil then
        return resolved
    end
    return path
end

-- Whether a file is one of the group of the hook: its path or the path it leads to matches a pattern
function hidden(hook, path)
    local group = about(hook).group
    local patterns = FILES[group]
    if patterns == nil then
        return false
    end
    local key = group .. '|' .. path
    local known = HIDDEN[key]
    if known ~= nil then
        return known
    end

    -- The patterns of Read.VPN capture the name of the interface
    local function matches(text, pattern)
        if group == 'Read.VPN' then
            return isvpninterface(string.match(text, pattern))
        end
        return string.find(text, pattern) ~= nil
    end
    local found = false
    local plain = unrooted(path)
    for _, pattern in ipairs(patterns) do
        if matches(plain, pattern) then
            found = true
            break
        end
    end
    if not found then
        local resolved = resolve(plain)
        for _, pattern in ipairs(patterns) do
            if matches(resolved, pattern) then
                found = true
                break
            end
        end
    end

    if cached >= 1000 then
        HIDDEN = {}
        cached = 0
    end
    HIDDEN[key] = found
    cached = cached + 1
    return found
end

-- The path of the first argument: a text, a java.io.File or a path of java.nio, which print their path
function pathof(param)
    return tostring(param:getArgument(0))
end

-- Files.newDirectoryStream, list and walk: the names of hidden files are left out by a filter in front of
-- the one of the app; a hidden folder is not opened at all (see the hooks of UnixNativeDispatcher.opendir). Listing a folder of /sys is not
-- reported, since nothing is hidden in most of them.
function listing(hook, param, folder)
    if hidden(hook, folder) then
        return false
    end
    local original = param:getArgument(1)
    param:setArgument(1, luajava.createProxy('java.nio.file.DirectoryStream$Filter', {
        accept = function(entry)
            if hidden(hook, tostring(entry)) then
                return false
            end
            return original == nil or original:accept(entry)
        end
    }))
    return false
end

function before(hook, param)
    local path = pathof(param)
    local class = about(hook).class
    if class == 'sun.nio.fs.UnixFileSystemProvider' then
        return listing(hook, param, path)
    end
    if not hidden(hook, path) then
        return false
    end

    local method = about(hook).method
    if class == 'libcore.io.BlockGuardOs' or class == 'android.system.Os' then
        -- Os.open, stat, lstat, access and readlink, which FileInputStream and File.length use; Os.access and
        -- Os.readlink of apps are hooked themselves, since the hooks of BlockGuardOs do not run for them (Android 14)
        param:setResult(luajava.newInstance('android.system.ErrnoException', method, ENOENT))
    elseif class == 'java.io.UnixFileSystem' then
        if method == 'createFileExclusively' then
            -- File.createNewFile: apps cannot make files in /sys, and not at all in a folder which does not exist
            local folder = luajava.newInstance('java.io.File', path):getParent()
            local reason = ((folder ~= nil and hidden(hook, folder)) and 'No such file or directory' or 'Permission denied')
            param:setResult(luajava.newInstance('java.io.IOException', reason))
        elseif method == 'list0' then
            -- File.list of a hidden folder: like of a folder which does not exist
            param:setResult(nil)
        elseif method == 'checkAccess' then
            -- File.exists, canRead, canWrite and canExecute; they call Libcore.os.access, but the hook of
            -- BlockGuardOs.access does not run for them (Android 14)
            param:setResult(false)
        elseif method == 'getLastModifiedTime' then
            -- File.lastModified, 0 like of a file which does not exist
            param:setResult(0)
        else
            -- File.isFile and isDirectory: no attributes, like of a file which does not exist
            param:setResult(0)
        end
    else
        -- java.nio.file: Files.exists, the mode of Files.isRegularFile, the rest throws like the kernel
        if method == 'exists' then
            param:setResult(false)
        elseif method == 'stat' and about(hook).returnType == 'int' then
            param:setResult(0)
        else
            param:setResult(luajava.newInstance('sun.nio.fs.UnixException', ENOENT))
        end
    end
    return true, path
end

-- File.list of a folder with hidden files: without their names
function after(hook, param)
    if about(hook).method ~= 'list0' or param:getException() ~= nil then
        return false
    end
    local names = param:getResult()
    if names == nil then
        return false
    end

    local folder = pathof(param)
    local kept = {}
    for index = 1, names['length'] do
        if not hidden(hook, folder .. '/' .. names[index]) then
            table.insert(kept, names[index])
        end
    end
    if #kept == names['length'] then
        return false
    end

    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local fake = arrayClass:newInstance(param:findClass('java.lang.String'), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(names['length']), tostring(#kept)
end
