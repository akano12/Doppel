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
-- Each variant hides the files of its group, and folders listed with java.nio.file leave the names of hidden files out.
-- A SecureDirectoryStream of java.nio, which Files.newDirectoryStream gives, opens, removes and moves the files of its
-- folder and reads their attributes by the descriptor of the folder and their name (openat, unlinkat, renameat and
-- fstatat, see atpath), which only the groups which hook those calls cover. Not covered is native code. The property
-- areas of /dev/__properties__ have the fake values of the properties, see systemproperties_get.
-- shortcut: a SecureDirectoryStream of a folder in the one of the stream (its newDirectoryStream) has no filter, so it
-- lists the names of hidden files, which openat then does not find, hook its constructor to add a filter if apps do so.

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
        -- The speeds, layout, caches and idle states of the processors, like cpu7/cpufreq/cpuinfo_max_freq, and the
        -- features and flaws of their cores; of the folders of the processors cpu0 to cpu7 stay (see cpu_count), and
        -- a device with fewer processors keeps the ones it has, since folders cannot be made up
        '/devices/system/cpu/cpu[89]$', '/devices/system/cpu/cpu[89]/', '/devices/system/cpu/cpu%d%d+$',
        '/devices/system/cpu/cpu%d%d+/',
        '/devices/system/cpu/cpufreq$', '/devices/system/cpu/cpufreq/', '/devices/system/cpu/cpuidle$',
        '/devices/system/cpu/cpuidle/', '/devices/system/cpu/vulnerabilities$', '/devices/system/cpu/vulnerabilities/',
        '/devices/system/cpu/modalias$', '/devices/system/cpu/cpu%d+/cpufreq$', '/devices/system/cpu/cpu%d+/cpufreq/',
        '/devices/system/cpu/cpu%d+/topology$', '/devices/system/cpu/cpu%d+/topology/',
        '/devices/system/cpu/cpu%d+/cpuidle$', '/devices/system/cpu/cpu%d+/cpuidle/',
        '/devices/system/cpu/cpu%d+/cache$', '/devices/system/cpu/cpu%d+/cache/',
        '/devices/system/cpu/cpu%d+/cpu_capacity$', '/devices/system/cpu/cpu%d+/regs$',
        '/devices/system/cpu/cpu%d+/regs/',
        -- The model, clocks and load of the GPU: of Samsung and newer Qualcomm kernels (/sys/kernel/gpu, like
        -- gpu_model and gpu_clock), of Adreno (/sys/class/kgsl/kgsl-3d0, a link into its device, with devfreq)
        -- and of Mali (the device, like /sys/devices/platform/18500000.mali, and /sys/class/misc/mali0)
        '/kernel/gpu$', '/kernel/gpu/', '/class/kgsl$', '/class/kgsl/', '/kgsl/kgsl%-3d0$', '/kgsl/kgsl%-3d0/',
        'qcom,kgsl%-3d0$', 'qcom,kgsl%-3d0/', '%.mali$', '%.mali/', '/misc/mali0$', '/misc/mali0/',
        '/proc/scsi/', '/proc/bus/input/devices$',
        -- The certificates the user added, like of a VPN or an ad blocker, by name and content; the store of
        -- certificates of the app reads them too (see truststore)
        '^/data/misc/user/%d+/cacerts%-added$', '^/data/misc/user/%d+/cacerts%-added/',
        '^/data/misc/user/%d+/cacerts%-removed$', '^/data/misc/user/%d+/cacerts%-removed/' },
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
    ['Read.Features'] = {},
    ['Get.Applications'] = {},
}

-- The folders etc of the system, so a copy of a Linux system an app unpacks in its own folder keeps its files:
-- of the root, of each partition, like /vendor/etc, also /system/product/etc, and of APEX modules
local ETC = { '^/etc/', '^/[%w_]+/etc/', '^/system/[%w_]+/etc/', '^/apex/[^/]+/etc/' }

for group, names in pairs({
    -- The codecs of the chip by name (see media_codecs), like /vendor/etc/media_codecs_sm8550_vendor.xml, the
    -- properties of the partitions, of which those of /product and /system_ext can be read and name the device, and
    -- the file systems of the device, named after its hardware, like fstab.qcom
    ['Use.Tracking'] = { 'media_codecs[^/]*%.xml$', 'build%.prop$', 'fstab%.[^/]*$' },
    ['Read.Features'] = { 'permissions$', 'permissions/', 'sysconfig$', 'sysconfig/' },
    -- The permissions of the system apps, a file per app, like privapp-permissions-com.samsung.android.dialer.xml
    ['Get.Applications'] = { 'permissions/privapp%-permissions%-[^/]*$' } }) do
    for _, name in ipairs(names) do
        for _, etc in ipairs(ETC) do
            table.insert(FILES[group], etc .. name)
        end
    end
end
-- The properties at the root of a partition too, like /system/build.prop and /vendor/build.prop, and fstab at the
-- root of devices of Android 8.1 and 9 without system as root
table.insert(FILES['Use.Tracking'], '^/[%w_]+/build%.prop$')
table.insert(FILES['Use.Tracking'], '^/system/[%w_]+/build%.prop$')
table.insert(FILES['Use.Tracking'], '^/fstab%.[^/]*$')

-- The folders of the apps of the system, of which only the names are left out for Get.Applications: the files stay,
-- since the system loads apps from them, like WebView
local APPS = { '^/[%w_]+/app/?$', '^/[%w_]+/priv%-app/?$', '^/system/[%w_]+/app/?$', '^/system/[%w_]+/priv%-app/?$' }

-- The folders named after apps, of which those of hidden apps do not exist for Get.Applications: the private data of
-- apps, which Android 8.1 to 10 let other apps find (app data isolation hides it from Android 11), and the folders
-- of apps in the shared storage: data and obb, which scoped storage hides from Android 11, and media, which all
-- versions let apps list. Each pattern captures the name of the app, with the last version which needs it.
local APP_FOLDERS = {
    { '^/data/data/([^/]+)', 29 }, { '^/data/user/%d+/([^/]+)', 29 }, { '^/data/user_de/%d+/([^/]+)', 29 },
    { '^/data/misc/profiles/cur/%d+/([^/]+)', 29 }, { '^/data/misc/profiles/ref/([^/]+)', 29 },
}
for _, storage in ipairs({ '^/storage/emulated/%d+', '^/storage/self/primary', '^/sdcard', '^/mnt/sdcard',
                           '^/storage/%x%x%x%x%-%x%x%x%x' }) do
    table.insert(APP_FOLDERS, { storage .. '/Android/data/([^/]+)', 29 })
    table.insert(APP_FOLDERS, { storage .. '/Android/obb/([^/]+)', 29 })
    table.insert(APP_FOLDERS, { storage .. '/Android/media/([^/]+)', 999 })
end

-- The paths the script runs for, of the groups with files outside /sys (see filter); the filter also compares
-- absolute paths without /./, // and the links of /proc to the root (XCore.getFilter), relative paths with them run
-- it too, and so do paths with /../ to su and Magisk, like /vendor/../data/adb, which the prefixes do not match.
-- The folders of su and of the codec files are there for listing them, like File.list of /system/xbin, and so are
-- the folders of etc, which list the names the group hides, like fstab.* of /etc, a link to /system/etc.
local FILTERS = {
    ['Use.Tracking'] = { '*sys/*', '*proc/scsi/*', '*proc/bus/input/*', '*/fonts*', '*etc/media_codecs*',
                         '*/build.prop*', '*etc/fstab.*', '/fstab.*', '/', '*misc/user/*', '*/./*', '*//*',
                         '/system/etc', '/vendor/etc', '/odm/etc', '/product/etc', '/system_ext/etc', '/etc' },
    ['Read.Network'] = { '*net/*', '*/./*', '*//*' },
    ['Read.VPN'] = { '*net/*', '*/./*', '*//*' },
    ['Read.Debugging'] = { '/system/bin/*', '/system/xbin/*', '/system/sd/*', '/system/usr/*', '/system/app/Super*',
                           '/sbin/*', '/su*', '/data/local/*', '/cache/su', '/dev/su', '/vendor/bin/*',
                           '/vendor/xbin/*', '/product/bin/*', '/system_ext/bin/*', '/odm/bin/*', '/debug_ramdisk*',
                           '/data/adb*', '*bin/su*', '*magisk*', '*debug_ramdisk*', '*/./*', '*//*', '*/../*',
                           '/system/bin', '/system/xbin', '/sbin', '/data/local', '/cache', '/dev', '/vendor/bin',
                           '/vendor/xbin', '/product/bin', '/system_ext/bin', '/odm/bin', '/system/app' },
    ['Read.Features'] = { '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*', '/system/etc', '/vendor/etc',
                          '/odm/etc', '/product/etc', '/system_ext/etc', '/etc' },
    ['Get.Applications'] = { '*etc/permissions*', '*/./*', '*//*', '/system/app', '/system/priv-app', '/product/app',
                             '/product/priv-app', '/system_ext/app', '/system_ext/priv-app', '/vendor/app',
                             '/vendor/priv-app', '/odm/app', '/odm/priv-app', '/system/product/app',
                             '/system/product/priv-app', '/system/system_ext/app', '/system/system_ext/priv-app',
                             '/system/vendor/app', '/system/vendor/priv-app' },
}

-- Whether a network interface is of a VPN, like vpn and interface_entries, which a test compares
function isvpninterface(name)
    -- tunl0 is the tunnel of the kernel for IP in IP, which every device has, VPN or not
    if type(name) ~= 'string' or string.sub(name, 1, 4) == 'tunl' then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- The error of a file which does not exist, OsConstants.ENOENT, the error of making a file in a folder the app cannot
-- write, EACCES, and on a file system mounted read-only, EROFS, the flag of open which makes the file, O_CREAT, and
-- the flag of statvfs of a file system mounted read-only, ST_RDONLY, the same on every Linux
local ENOENT = 2
local EACCES = 13
local EROFS = 30
local O_CREAT = 64
local ST_RDONLY = 1

-- The script runs for the files of /sys only (with net/ in the path for Read.Network and Read.VPN, see FILTERS for
-- the others), also by other paths, like /proc/self/root/sys/..., /data/../sys/...
-- or sys/... relative to /, the folder apps run in; see XCore.getFilter. It also runs for the paths through the
-- descriptor of a folder the app opened, like /proc/self/fd/57/cid and /dev/fd/57/cid, which name no folder of
-- the group and which matched resolves. Not covered are links of apps themselves to the files, which have paths
-- without sys/. The calls by the descriptor of a folder, openat, unlinkat, renameat and fstatat, have no path the
-- filter could compare, and link, symlink, rename and renameat have a second path, which the filter does not see, so the
-- script compares their paths itself (see atpath and renaming).
local UNFILTERED = { openat = true, unlinkat = true, renameat = true, fstatat = true, link = true, symlink = true,
                     rename = true }

function filter(hook)
    if UNFILTERED[hook:getMethodName()] then
        return nil
    end
    return paths(hook:getGroup())
end

-- The paths the script runs for in a group, which the filter gives the hooks
function paths(group)
    local values = {}
    for _, value in ipairs(FILTERS[group] or { '*sys/*', '*/./*', '*//*' }) do
        values[#values + 1] = value
    end
    values[#values + 1] = '*/fd/*'
    if group ~= 'Get.Applications' then
        return values
    end
    -- The folders of APP_FOLDERS, only on the versions which need them, since apps use their own ones often
    values[#values + 1] = '*Android/media*'
    if sdkversion() <= 29 then
        for _, value in ipairs({ '/data/data/*', '/data/user/*', '/data/user_de/*', '/data/misc/profiles/*',
                                 '*Android/data*', '*Android/obb*' }) do
            values[#values + 1] = value
        end
    end
    return values
end

-- The version of Android, read once
local VERSION = nil

function sdkversion()
    if VERSION == nil then
        VERSION = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    end
    return VERSION
end

-- The app a path is in by APP_FOLDERS, nil when it is in no folder named after an app; like .nomedia, a name which
-- cannot be a package is of no app
function appfolder(path)
    for _, folder in ipairs(APP_FOLDERS) do
        if sdkversion() <= folder[2] then
            local name = string.match(path, folder[1])
            if name ~= nil then
                return (string.find(name, '^%a[%w_]*%.[%w_.]*[%w_]$') and name or nil)
            end
        end
    end
    return nil
end

-- Whether an app is installed and hidden: the folder of an app which is not installed stays, like on Android, also
-- one the app made itself, which an app could else make to tell the hook is there
function hiddenapp(param, name)
    if name == param:getPackageName() or param:isPackageVisible(name) then
        return false
    end
    local installed = pcall(function()
        return param:unhooked(function()
            -- MATCH_UNINSTALLED_PACKAGES, like XParam looks packages up
            return param:getApplicationContext():getPackageManager():getPackageInfo(name, 0x2000)
        end)
    end)
    return installed
end

-- Whether paths are hidden for a group, per thread, since a script runs once per thread (see docs/DEFINE.md): files
-- of /sys are read often, like the frequencies of the processors, and resolving their paths takes a system call.
-- A file which exists stays what it is while the app runs, but one which does not can come, like the cid of an SD card
-- the user inserts, so only the answers for files which exist are kept; the cache starts over when it is full.
local HIDDEN = {}
local cached = 0

-- The links of the descriptors of the app, like /proc/self/fd/9 of a file another app shared with it: the file is open
-- already, only a path on through the descriptor of a folder leads to files the app could not open
local DESCRIPTORS = { '^/?proc/[^/]+/fd/%d+/?$', '^/?proc/[^/]+/task/%d+/fd/%d+/?$', '^/?dev/fd/%d+/?$' }

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
-- links cannot be resolved
function unrooted(path)
    for _, prefix in ipairs({ '^/proc/self/root/', '^/proc/thread%-self/root/', '^/proc/%d+/root/' }) do
        path = string.gsub(path, prefix, '/')
    end
    return path
end

-- The path a path leads to, by the links of /sys, or the path as it is; without the hooks of the app, which give
-- the canonical path of a hidden file as of one which does not exist and would ask for this path again, see before
function resolve(path, param)
    local ok, resolved = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):getCanonicalPath()
        end)
    end)
    if ok and resolved ~= nil then
        return resolved
    end
    return path
end

-- Whether the app could reach a file: else the real call answers, since SELinux denies many paths whatever their
-- name (EACCES), like all of /sys/class/net, and a fake answer for only the hidden names would tell the hook is there,
-- and nothing is hidden for a file which does not exist, like su on a phone without root
-- Read.Network only hides opening its files (see FILES): of /proc/net apps can tell they exist, but not read them.
-- The property files of the partitions and fstab are there on every device, so the ones apps cannot read, like
-- /system/build.prop of Android 14 (EACCES), stay as they are: hiding them would turn EACCES into ENOENT. So do
-- the ids of Read.Identifiers which SELinux lets apps find but not read: the id stays unknown, and the driver
-- makes the file with the others of its folder, like cid with csd and date of an SD card.
function reachable(group, path, param)
    local ok, exists = pcall(function()
        return param:unhooked(function()
            local file = luajava.newInstance('java.io.File', path)
            if group == 'Read.Network' or group == 'Read.Identifiers' or string.find(path, 'build%.prop$') or
                    string.find(path, '/fstab%.[^/]*$') then
                return file:canRead()
            end
            return file:exists()
        end)
    end)
    return not ok or exists
end

-- Whether a file exists, without the hooks of the app; as if it did when that cannot be told, like reachable
function present(path, param)
    local ok, exists = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):exists()
        end)
    end)
    return not ok or exists
end

-- Whether a file is one of the group of the hook which the app could reach: its path or the path it leads to matches
-- a pattern. The interfaces of VPNs come and go, and so does the folder of a descriptor, which the app can close and
-- open again for another folder by the same number, and so do files which do not exist yet, see HIDDEN; the rest
-- stays while the app runs.
function hidden(hook, path, param)
    local group = about(hook).group
    if FILES[group] == nil then
        return false
    end
    local plain = unrooted(path)
    for _, pattern in ipairs(DESCRIPTORS) do
        if string.find(plain, pattern) then
            return false
        end
    end
    local key = group .. '|' .. path
    local known = HIDDEN[key]
    if known ~= nil then
        return known
    end
    local found = matched(group, path, param) and reachable(group, path, param)
    -- Nothing is under a hidden file either, like .../privapp-permissions-com.example.xml/x or its /., for which the
    -- kernel answers ENOTDIR, which would tell the file is there; a path with /../ counts by the file it leads to (see
    -- matched), the files of Read.Network stay there, and the patterns of Read.VPN cover what is under its folders.
    -- shortcut: a path on through a hidden file and back, like .../privapp-permissions-com.example.xml/../a.xml, still
    -- answers ENOTDIR, check each name of such paths if apps probe like that.
    if not found and group ~= 'Read.Network' and group ~= 'Read.VPN' and not string.find(path, '/../', 1, true) then
        local folder = string.match(path, '^(.+)/[^/]*$')
        found = folder ~= nil and hidden(hook, folder, param)
    end
    if group == 'Read.VPN' or string.find(path, '/fd/', 1, true) or (not found and not present(path, param)) then
        return found
    end
    if cached >= 1000 then
        HIDDEN = {}
        cached = 0
    end
    HIDDEN[key] = found
    cached = cached + 1
    return found
end

-- The fonts every Android has (Roboto, Noto and the families of AOSP); the other fonts of the system, like SamsungOne,
-- MiSans and Google Sans, name the maker and the ROM. They are left out of listings and do not exist for the app;
-- text still shows with them, which the system loads itself. Like system_fonts, which a test compares.
local FONTS = { 'Roboto', 'Noto', 'DroidSans', 'CutiveMono', 'ComingSoon', 'DancingScript', 'CarroisGothicSC',
                'SourceSansPro', 'AndroidClock' }

-- Whether a file is a font of the system which is not of every Android, in a fonts folder of a partition
function brandfont(path)
    local partition, name = string.match(path, '^/([^/]+)/fonts/([^/]+)$')
    if partition == nil then
        partition, name = string.match(path, '^/system/([^/]+)/fonts/([^/]+)$')
    end
    if partition == nil or not (partition == 'system' or partition == 'product' or partition == 'system_ext' or
            partition == 'vendor' or partition == 'odm' or partition == 'oem' or string.find(partition, '^my_')) then
        return false
    end
    for _, prefix in ipairs(FONTS) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return false
        end
    end
    return true
end

-- Whether a file is one of a group, without the cache, so other threads can ask too (see listing)
function matched(group, path, param)
    local plain = unrooted(path)
    -- Only the file it leads to counts: the filter passes all paths with /../, like ones through a folder of the app
    if string.find(plain, '/../', 1, true) then
        plain = resolve(plain, param)
    end
    -- Resolved once, when needed: getCanonicalPath reads the file system
    local resolved
    if group == 'Get.Applications' then
        -- A folder named after an app, by its path or the path it leads to, like /sdcard to /storage/emulated/0;
        -- the paths in the own folders of the app, which apps use often, do not read the file system
        local name = appfolder(plain)
        if name == nil then
            resolved = resolve(plain, param)
            name = appfolder(resolved)
        end
        if name ~= nil then
            return hiddenapp(param, name)
        end
        -- The permissions of the platform itself, which every AOSP build has, name no app of a maker
        if string.find(plain, '/privapp%-permissions%-platform%.xml$') then
            return false
        end
    end
    -- Also through the descriptor of a folder, like /proc/self/fd/57/SamsungOne.ttf of /system/fonts
    if group == 'Use.Tracking' and (string.find(path, 'fonts', 1, true) or string.find(path, '/fd/', 1, true)) then
        if brandfont(plain) then
            return true
        end
        resolved = resolve(plain, param)
        if brandfont(resolved) then
            return true
        end
    end
    local patterns = FILES[group]
    -- The patterns of Read.VPN capture the name of the interface
    local function matches(text, pattern)
        if group == 'Read.VPN' then
            return isvpninterface(string.match(text, pattern))
        end
        return string.find(text, pattern) ~= nil
    end
    local found = false
    for _, pattern in ipairs(patterns) do
        if matches(plain, pattern) then
            found = true
            break
        end
    end
    if not found then
        resolved = resolved or resolve(plain, param)
        for _, pattern in ipairs(patterns) do
            if matches(resolved, pattern) then
                found = true
                break
            end
        end
    end
    return found
end

-- The canonical path of a hidden file, like of a file which does not exist: its name in its folder, not the file
-- a link leads to, like /system/bin/magisk of su; the folder by its links, unless it is hidden too
function missing(hook, path, param)
    local folder, name = string.match(path, '^(.*)/([^/]+)$')
    if folder == nil or folder == '' then
        return path
    end
    if hidden(hook, folder, param) then
        folder = missing(hook, folder, param)
    else
        folder = resolve(folder, param)
    end
    return (folder == '/' and '' or folder) .. '/' .. name
end

-- The error of making a hidden file, which does not exist for the app, nil when the kernel would check the permissions:
-- ENOENT when its folder is hidden too, EROFS when the folder is on a file system mounted read-only, like the
-- partitions of the system and the APEX modules by any path, like /etc, a link to /system/etc, which the kernel
-- checks before the permissions; the real statvfs, which SELinux lets every app call
function making(hook, path, param)
    local folder = luajava.newInstance('java.io.File', path):getParent()
    if folder == nil then
        return nil
    end
    if hidden(hook, folder, param) then
        return ENOENT
    end
    local ok, readonly = pcall(function()
        return param:unhooked(function()
            return bit32.band(luajava.bindClass('android.system.Os'):statvfs(folder).f_flag, ST_RDONLY) ~= 0
        end)
    end)
    return (ok and readonly) and EROFS or nil
end

-- The error of a call on a hidden file: of making a file, a folder or a pipe by its name (open and openat with
-- O_CREAT, mkdir, mkfifo) like a missing name in a folder of /sys, which apps cannot write (EACCES), unless making
-- answers first, since Linux never gives ENOENT for a name it makes in a folder which exists; else of a name no
-- lookup finds
function failing(hook, path, param)
    local method = about(hook).method
    local flags = (method == 'open' and param:getArgument(1)) or (method == 'openat' and param:getArgument(2)) or 0
    if method == 'mkdir' or method == 'mkfifo' or bit32.band(flags, O_CREAT) ~= 0 then
        return making(hook, path, param) or EACCES
    end
    return ENOENT
end

-- The path of the first argument: a text, a java.io.File or a path of java.nio, which print their path
function pathof(param)
    return tostring(param:getArgument(0))
end

-- The path of openat, unlinkat, renameat and fstatat, by which a SecureDirectoryStream opens, removes and moves the
-- files of its folder, also to read or change their attributes, and reads their attributes: the name of an argument,
-- which is relative to the folder of the descriptor in the argument before it (its link in /proc/self/fd) unless it is
-- absolute; nil when the folder is not known or the filter of the other hooks would not run the script for the path,
-- like for the files of the app, which libraries remove this way
function atpath(hook, param, index)
    local path = luajava.newInstance('java.lang.String', param:getArgument(index))
    if string.sub(path, 1, 1) ~= '/' then
        local ok, folder = pcall(function()
            return param:unhooked(function()
                return luajava.bindClass('android.system.Os'):readlink('/proc/self/fd/' .. param:getArgument(index - 1))
            end)
        end)
        if not ok or folder == nil then
            return nil
        end
        path = (folder == '/' and '' or folder) .. '/' .. path
    end
    return filtered(about(hook).group, path) and path or nil
end

-- Whether the filter of a group runs the script for a path, like XCore.Filter compares it: a value ending in * is the
-- start of the path, starting with * too a part of it, other values the path, also of the path without a link to the
-- root in front and of a relative path from the root; paths with /.. always run the script
function filtered(group, path)
    local absolute = string.sub(path, 1, 1) == '/' and path or '/' .. path
    if string.find(absolute, '/../', 1, true) or string.find(absolute, '/%.%.$') then
        return true
    end
    for _, value in ipairs(paths(group)) do
        local part, start = string.match(value, '^%*(.+)%*$'), string.match(value, '^(.*)%*$')
        for _, text in ipairs({ path, unrooted(path), absolute }) do
            if text == value or (part ~= nil and string.find(text, part, 1, true)) or
                    (part == nil and start ~= nil and string.sub(text, 1, #start) == start) then
                return true
            end
        end
    end
    return false
end

-- A path the kernel answers like a hidden one, which does not exist for the app: a name of its folder which does not
-- exist, or that name in such a stand-in of its folder when the folder is hidden too
function standin(hook, path, param)
    local folder, name = string.match(path, '^(.*)/([^/]*)$')
    if folder ~= nil and folder ~= '' and hidden(hook, folder, param) then
        return standin(hook, folder, param) .. '/' .. name
    end
    local other = path .. '~'
    while present(other, param) and #other < #path + 8 do
        other = other .. '~'
    end
    return other
end

-- link, symlink, rename and renameat, which make a name or move a file: a hidden path, of the new name of link and
-- symlink (symlink does not look its first path up) or either path of rename and renameat, is replaced by a stand-in, so
-- the real call answers like for a missing name, in the order of Linux and with the exception and stack trace of
-- Android: of a name which exists link and symlink give EEXIST before the errors of a missing one, EROFS, EXDEV of link
-- to another file system, and EACCES in a folder of /sys; rename looks its first path up after EXDEV and EROFS (EACCES
-- when it exists), so a SecureDirectoryStream moving a hidden id to the folder of the app gets EXDEV, as without it.
-- The real call cannot make the stand-in, since apps cannot write the folders of the hidden names, or their file system
-- has no links, like the shared storage.
local RENAMED = { link = { 0, 1 }, symlink = { 1 }, rename = { 0, 1 }, renameat = { 1, 3 } }

function renaming(hook, param)
    local known = about(hook)
    local renamed
    for _, index in ipairs(RENAMED[known.method]) do
        local path
        if known.method == 'renameat' then
            path = atpath(hook, param, index)
        else
            path = tostring(param:getArgument(index))
            path = filtered(known.group, path) and path or nil
        end
        if path ~= nil and hidden(hook, path, param) then
            local other = standin(hook, path, param)
            -- The names of renameat are bytes, which XParam makes of a text (and absolute, so not of the descriptor)
            if known.class == 'android.system.Os' or known.method == 'renameat' then
                param:setArgument(index, other)
            else
                param:setArgument(index, luajava.newInstance('java.io.File', other):toPath())
            end
            renamed = renamed or path
        end
    end
    if renamed == nil then
        return false
    end
    return true, renamed
end

-- Files.newDirectoryStream, list and walk: the names of hidden files are left out by a filter in front of
-- the one of the app; a hidden folder is not opened at all (see the hooks of UnixNativeDispatcher.opendir). Listing a folder of /sys is not
-- reported, since nothing is hidden in most of them. The filter can run on another thread, which must not change
-- the tables of this one, so it does not use the cache. Without a filter Android throws NullPointerException.
function listing(hook, param, folder)
    local original = param:getArgument(1)
    if original == nil or hidden(hook, folder, param) then
        return false
    end
    local group = about(hook).group
    local none = unlisted(group, folder, param)
    param:setArgument(1, luajava.createProxy('java.nio.file.DirectoryStream$Filter', {
        accept = function(entry)
            -- Left out as File.list does, so a matched file the app cannot read stays listed, as Android lists it
            local path = tostring(entry)
            if none or (matched(group, path, param) and reachable(group, path, param)) then
                return false
            end
            return original:accept(entry)
        end
    }))
    return false
end

-- Whether the store of certificates of the app reads the certificates of the user, which runs in the app and must
-- find them, or TLS with them fails; what the app itself asks the store is filtered (see device_details)
function truststore()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if string.find(class, 'conscrypt', 1, true) or string.find(class, 'android.security.net.config', 1, true) then
            return true
        end
    end
    return false
end

-- Whether the names of a folder are left out of a listing, the files themselves stay (see APPS)
function unlisted(group, folder, param)
    if group ~= 'Get.Applications' then
        return false
    end
    -- The forms of a path which the file system lists the same, like /system/app/., /system//priv-app and /x/../app,
    -- also the descriptor of the folder, like /proc/self/fd/57 of /system/priv-app, which only its resolved path names
    local plain = unrooted(folder)
    if string.find(plain, '/../', 1, true) or string.find(plain, '/%.%.$') or string.find(plain, '/fd/', 1, true) then
        plain = resolve(plain, param)
    end
    local previous
    repeat
        previous = plain
        plain = string.gsub(string.gsub(plain, '/%./', '/'), '//+', '/')
    until plain == previous
    plain = string.gsub(plain, '/%.$', '')
    for _, pattern in ipairs(APPS) do
        if string.find(plain, pattern) then
            return true
        end
    end
    return false
end

function before(hook, param)
    local class, method = about(hook).class, about(hook).method
    local path
    if method == 'openat' or method == 'fstatat' or method == 'unlinkat' then
        -- unlinkat of a hidden id: ENOENT (see failing), which Linux gives for a missing name before it checks the
        -- permissions; the ids are in /sys, which is mounted read-write, so EROFS does not come first
        path = atpath(hook, param, 1)
        if path == nil then
            return false
        end
    elseif RENAMED[method] then
        return renaming(hook, param)
    else
        path = pathof(param)
    end
    if class == 'sun.nio.fs.UnixFileSystemProvider' and method == 'newDirectoryStream' then
        return listing(hook, param, path)
    end
    if not hidden(hook, path, param) or (string.find(path, 'cacerts', 1, true) and truststore()) then
        return false
    end

    if class == 'libcore.io.BlockGuardOs' or class == 'android.system.Os' then
        -- Os.open, stat, lstat, access and readlink, which FileInputStream uses, and the calls on the attributes, the
        -- mode, the owner and links; Os.access and the others of apps are hooked themselves, since the hooks of
        -- BlockGuardOs do not run for them (Android 14)
        local errno = failing(hook, path, param)
        if errno == ENOENT then
            -- The real call with an empty path, which no lookup finds, answers like for a missing file: with the
            -- exception Android makes, of which the stack trace is that of any missing file, without the frames of
            -- the script, and in the order of Android, since Linux 6.11 and later check the name, value and flags of
            -- setxattr and removexattr before they look the path up (EINVAL, ERANGE, E2BIG), 6.13 and later the name
            -- of getxattr (ERANGE), older kernels after, and libcore throws NullPointerException for a null name
            -- or value before the kernel runs
            param:setArgument(0, '')
        else
            -- shortcut: the other errors (EACCES, EROFS) are made here, so their stack trace has the frames of the
            -- script, make them by a real call of a path with the same error if apps compare traces
            param:setResult(luajava.newInstance('android.system.ErrnoException', method, errno))
        end
    elseif class == 'java.io.UnixFileSystem' then
        if method == 'canonicalize0' then
            -- File.getCanonicalPath and getCanonicalFile, which follow links: .. names another folder, . the
            -- folder itself, like /sys/class/net/tun0/., which is a link
            local name = string.match(path, '([^/]+)$')
            if name == '.' then
                local folder = string.match(path, '^(.+)/%.$')
                if folder == nil or not hidden(hook, folder, param) then
                    return false
                end
                path = folder
            elseif name == nil or name == '..' then
                return false
            end
            param:setResult(missing(hook, path, param))
        elseif method == 'createFileExclusively' then
            -- File.createNewFile: apps cannot make files in /sys, and not at all in a folder which does not exist
            -- or on a file system mounted read-only (see making)
            -- shortcut: the exception is made here, with the frames of the script in its stack trace (see the errors
            -- of Os above), make it by a real call too if apps compare traces
            local reasons = { [ENOENT] = 'No such file or directory', [EROFS] = 'Read-only file system' }
            param:setResult(luajava.newInstance('java.io.IOException',
                    reasons[making(hook, path, param)] or 'Permission denied'))
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
            -- File.isFile and isDirectory: no attributes; File.length, getTotalSpace, getFreeSpace and
            -- getUsableSpace (getLength and getSpace, which call stat and statvfs natively): 0, like of a file
            -- which does not exist
            param:setResult(0)
        end
    else
        -- java.nio.file: Files.exists, the mode of Files.isRegularFile, the rest throws like the kernel, with
        -- UnixException, which has no stack trace
        if method == 'exists' then
            param:setResult(false)
        elseif method == 'stat' and about(hook).returnType == 'int' then
            param:setResult(0)
        else
            param:setResult(luajava.newInstance('sun.nio.fs.UnixException', failing(hook, path, param)))
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
    if string.find(folder, 'cacerts', 1, true) and truststore() then
        return false
    end
    local kept = {}
    if not unlisted(about(hook).group, folder, param) then
        -- Of the root the path is /name, not //name, which the patterns of the root do not match
        for index = 1, names['length'] do
            if not hidden(hook, (folder == '/' and '' or folder) .. '/' .. names[index], param) then
                table.insert(kept, names[index])
            end
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
