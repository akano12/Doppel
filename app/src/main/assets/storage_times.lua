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

-- The folders of the shared storage are made when the device is set up, and their times stay, to the nanosecond,
-- until it is reset, so all apps read the same time, which links them to each other, like the times of folders
-- changed later, like of Download. Their times become those of the files of reproducible builds
-- (2009-01-01 00:00 UTC), like the install times of other apps (see packageinfo_createfromparcel).
-- The files of other apps in /data/app, like their APKs, have the time they were installed or updated, which
-- apps read by applicationInfo.sourceDir: their times become the install times their package info gives (see there),
-- the time of the firmware for updates of apps of the system and FAKE for the others; /data/app itself changes with
-- each install. The files of the app itself keep their times. The folders at the top of /data are made when the
-- device is set up too, and all apps can read their times (SELinux allows getattr of system_data_file folders).
-- The times are read with Os.stat and Os.lstat, Os.fstat of a descriptor, File.lastModified and java.nio.file.Files
-- (also the time the folder was made), also by the links of the descriptors of the app, like /proc/self/fd/9 and
-- /dev/fd/9 of the opened DCIM; not covered are the times of files in the folders, the attributes of entries
-- of a SecureDirectoryStream of java.nio (fstatat, like Files.newDirectoryStream of the storage gives) and native
-- code.
-- shortcut: Libcore.os.fstat by reflection keeps the real times, since Android calls it for every file it opens
-- (IoBridge.open) and the script would run for all of them; upgrade if an app reads the times that way.
-- shortcut: /data/app itself gets the fake time for all its times, while up to Android 10 init sets its mode on each
-- boot, which gives it a change of status of the boot; upgrade to the rule of the folders of DATA if an app compares
-- it with the boot.
local FAKE = 1230768000
local FLAG_SYSTEM = 1

-- The folders the system makes, which apps can read the times of
local FOLDERS = { 'Alarms', 'Android', 'Android/data', 'Android/media', 'Android/obb', 'Audiobooks', 'DCIM',
                  'Documents', 'Download', 'Movies', 'Music', 'Notifications', 'Pictures', 'Podcasts', 'Recordings',
                  'Ringtones' }

-- The folders at the top of /data which Android 8.1 to 17 make, besides /data/app (see after); not /data/data, which
-- is /data/user/0 of the data of the app itself and changes with each app installed. A folder the app cannot read
-- the times of answers as before, so the list has the folders of all versions. /data itself has the time a folder at
-- its top was last made or removed; its inode is that of the root of a file system, the same on all devices, which
-- stays. A time of a folder from the current boot stays, since it tells the boot, as the clocks do, not the setup:
-- the change of /data from Android 11, which removes and makes /data/per_boot on each boot, and up to Android 10 the
-- change of status of the folders init makes, since it sets their mode again on each boot (see after).
-- shortcut: folders device makers add keep their times; upgrade when one is known to all devices of a maker.
local DATA = { ['/data'] = true }
for _, name in ipairs({ 'adb', 'anr', 'apex', 'app-asec', 'app-ephemeral', 'app-lib', 'app-metadata', 'app-private',
                        'app-staging', 'backup', 'bootanim', 'bootchart', 'cache', 'dalvik-cache', 'drm', 'fonts', 'gsi',
                        'incremental', 'local', 'lost+found', 'media', 'mediadrm', 'misc', 'misc_ce', 'misc_de', 'nfc',
                        'ota', 'ota_package', 'per_boot', 'pkg_staging', 'preloads', 'property', 'resource-cache',
                        'rollback', 'rollback-history', 'rollback-observer', 'server_configurable_flags', 'ss',
                        'storage_area', 'system', 'system_ce', 'system_de', 'tombstones', 'unencrypted', 'user',
                        'user_de', 'vendor', 'vendor_ce', 'vendor_de' }) do
    DATA['/data/' .. name] = true
end

-- The paths of the shared storage of the user
function tops()
    local root = luajava.bindClass('android.os.Environment'):getExternalStorageDirectory():getPath()
    return { root, '/sdcard', '/storage/self/primary' }
end

-- The paths of the folders, with the paths of the shared storage of the user, see XCore.getFilter. The shared
-- storage finds names in any case, so the folders in lower and upper case too, like /sdcard/download; paths with
-- /./, // or /../ run the script too, which compares them resolved (see after). Not covered are other mixes of
-- case, like /sdcard/DownLoad: the script would have to run for every file of the storage. Paths through a
-- descriptor, with /fd/, like /proc/self/fd/9, run the script too, which resolves them (see after). Os.fstat has a
-- descriptor, not a path, so the script runs for each of its calls.
function filter(hook)
    if hook ~= nil and hook:getMethodName() == 'fstat' then
        return nil
    end
    local paths = { '/storage/emulated', '/storage/emulated/', '*/./*', '*//*', '*/../*' }
    for _, top in ipairs(tops()) do
        table.insert(paths, top)
        table.insert(paths, top .. '/')
        for _, folder in ipairs(FOLDERS) do
            for _, name in ipairs({ folder, string.lower(folder), string.upper(folder) }) do
                table.insert(paths, top .. '/' .. name)
                table.insert(paths, top .. '/' .. name .. '/')
                table.insert(paths, top .. '/' .. name .. '/.')
            end
        end
    end
    table.insert(paths, '*/fd/*')
    for path in pairs(DATA) do
        table.insert(paths, path)
        table.insert(paths, path .. '/')
        table.insert(paths, path .. '/.')
    end
    table.insert(paths, '/data/app')
    table.insert(paths, '/data/app/*')
    return paths
end

-- A path without ., .. and double slashes, like /sdcard/Android/../Download/. is /sdcard/Download
function normal(path)
    local parts = {}
    for part in string.gmatch(path, '[^/]+') do
        if part == '..' then
            table.remove(parts)
        elseif part ~= '.' then
            table.insert(parts, part)
        end
    end
    -- Without the links of /proc to the root, also of a thread and of the working directory, which is the root for
    -- apps, like the filter compares the paths (XParam.plainPath), so the times of the folders are faked by them too
    local plain = '/' .. table.concat(parts, '/')
    repeat
        local before = plain
        for _, link in ipairs({ 'root', 'cwd' }) do
            plain = string.gsub(plain, '^/proc/[%w_%-]+/task/%d+/' .. link .. '/', '/')
            plain = string.gsub(plain, '^/proc/[%w_%-]+/' .. link .. '/', '/')
        end
    until plain == before
    return plain
end

-- The folders of the shared storage in lower case, for paths in any case
local STORAGE = nil

function storage(path)
    if STORAGE == nil then
        local known = { ['/storage/emulated'] = true }
        for _, top in ipairs(tops()) do
            known[string.lower(top)] = true
            for _, folder in ipairs(FOLDERS) do
                known[string.lower(top .. '/' .. folder)] = true
            end
        end
        STORAGE = known
    end
    return STORAGE[string.lower(path)] == true
end

-- The time of a path of /data/app, in milliseconds, by the package in it (/data/app/~~...==/com.example-...==/
-- since Android 11, /data/app/com.example-.../ before); nil for the paths of the app itself, which keep their time.
-- Per path, since files are read often and the packages do not change while the app runs.
local TIMES = {}

function apptime(param, path)
    local known = TIMES[path]
    if known ~= nil then
        return known or nil
    end
    local time = FAKE * 1000
    local context = param:getApplicationContext()
    local own = (context ~= nil and context:getApplicationInfo().sourceDir or nil)
    if context == nil or param:isSystem() then
        time = false
    elseif own ~= nil then
        local folder = string.match(own, '^(.*)/[^/]*$')
        local root = string.match(folder, '^(.*)/[^/]*$')
        if path == folder or string.sub(path, 1, #folder + 1) == folder .. '/' or
                (root ~= '/data/app' and path == root) then
            time = false
        end
    end
    -- Names of packages have no -, the random part of the folder can
    local name = string.match(path, '^/data/app/~~[^/]+/([^/%-]+)%-') or string.match(path, '^/data/app/([^/~][^/%-]*)%-')
    if time and name ~= nil then
        -- Without the hook of package infos, which would fake the times the same way; updates of apps of the system
        -- have the time of the firmware there
        local ok, info = pcall(function()
            return param:unhooked(function()
                return context:getPackageManager():getPackageInfo(name, 0)
            end)
        end)
        if ok and info ~= nil and math.floor(info.applicationInfo.flags / FLAG_SYSTEM) % 2 == 1 then
            time = info.firstInstallTime
        end
    end
    -- Not before the app has a context, which tells its own files
    if context ~= nil then
        TIMES[path] = time
    end
    return time or nil
end

-- Runs a read of the script itself without the hooks and without telling StrictMode of a disk read the app did not
-- make; nil when it fails
function quietly(param, read)
    local ok, result = pcall(function()
        return param:unhooked(function()
            local strict = luajava.bindClass('android.os.StrictMode')
            local policy = strict:allowThreadDiskReads()
            local done, value = pcall(read)
            strict:setThreadPolicy(policy)
            return done and value or nil
        end)
    end)
    return ok and result or nil
end

-- The path of the file of the descriptor of Os.fstat by its link in /proc/self/fd, like /storage/emulated/0/DCIM; nil
-- when it fails, like for a file which was deleted. The number is read from the descriptor of the app itself, not of a
-- copy: closing a copy of a file would release the locks of the app on it (FileChannel.lock takes fcntl locks). LuaJ
-- finds the hidden getInt$ by listing the methods, which Android does not report as a use of a hidden API.
function descriptor(param)
    local fd = param:getArgument(0)
    return quietly(param, function()
        return luajava.bindClass('android.system.Os'):readlink('/proc/self/fd/' .. fd['getInt$'](fd))
    end)
end

-- Whether a folder at the top of /data is on the file system of /data itself, per path, since mounts do not change
-- while the app runs. Not with the data of apps kept apart (Android 11 and later, for apps for Android 11 and later):
-- Zygote mounts a tmpfs on /data/user and /data/user_de for each process, like on /data/data, which has the time the
-- process started, as without Doppel.
local MOUNTS = {}

function ondata(param, path)
    if MOUNTS[path] == nil then
        MOUNTS[path] = (quietly(param, function()
            local unix = luajava.bindClass('android.system.Os')
            return unix:stat(path).st_dev == unix:stat('/data').st_dev
        end) == true)
    end
    return MOUNTS[path]
end

-- The time the device booted, in seconds, by the clocks without the hooks of the app, each time, since the time of
-- the device can be set; nil when it cannot be read
function booted(param)
    local boot = quietly(param, function()
        return luajava.bindClass('java.lang.System'):currentTimeMillis() -
                luajava.bindClass('android.os.SystemClock'):elapsedRealtime()
    end)
    return boot and math.floor(boot / 1000) or nil
end

-- The mode and the times, in seconds, of what the hooked method read: the attributes of java.nio, a StructStat, or
-- the milliseconds of File.lastModified, which are the time of the last change only, without a mode
function stated(hook, param)
    if hook:getClassName() == 'sun.nio.fs.UnixNativeDispatcher' then
        local attributes = param:getArgument(1)
        if attributes == nil then
            return nil, {}
        end
        return attributes.st_mode, { atime = attributes.st_atime_sec, mtime = attributes.st_mtime_sec,
                                     ctime = attributes.st_ctime_sec }
    end
    local result = param:getResult()
    if result == nil then
        return nil, {}
    elseif hook:getMethodName() == 'getLastModifiedTime' then
        return nil, { mtime = math.floor(result / 1000) }
    end
    return result.st_mode, { atime = result.st_atime, mtime = result.st_mtime, ctime = result.st_ctime }
end

-- Whether lstat gave a symbolic link: the type bits of st_mode are 0xA000
function islink(method, mode)
    return method == 'lstat' and mode ~= nil and math.floor(mode / 4096) % 16 == 10
end

-- The key of the inode of a path: the spellings of a folder of the shared storage have one, like Android gives them
-- one inode (Files.isSameFile compares them); a link keeps its own, like lstat of /sdcard
function inodekey(path, link)
    if link or not storage(path) then
        return path
    end
    local root = tops()
    local lower = string.lower(path)
    for _, alias in ipairs({ root[2], root[3] }) do
        local prefix = string.lower(alias)
        if lower == prefix or string.sub(lower, 1, #prefix + 1) == prefix .. '/' then
            return string.lower(root[1]) .. string.sub(lower, #prefix + 1)
        end
    end
    return lower
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local path
    if method == 'fstat' then
        -- Only a folder or a file is one of them: the type bits of st_mode are 0x4000 or 0x8000; the links of sockets
        -- and pipes are not read
        local result = param:getResult()
        local kind = (result ~= nil and math.floor(result.st_mode / 4096) % 16 or nil)
        path = ((kind == 4 or kind == 8) and descriptor(param) or nil)
        if path == nil then
            return false
        end
    else
        local argument = tostring(param:getArgument(0))
        path = normal(argument)
        -- Through a descriptor of the app, like /proc/self/fd/9 or /dev/fd/9 of the opened DCIM, or /proc/self/fd/9/DCIM
        -- of the opened storage: the path the file system leads to, by File.getCanonicalPath, like hardware_files
        -- resolves them. Its realpath reads the links by lstat and readlink up to Android 10, and by a descriptor of
        -- O_PATH from Android 11, closing which releases no locks of the app, unlike closing a copy of its descriptor.
        -- lstat of the link itself gives the link in /proc, which keeps its answer.
        if string.find(argument, '/fd/', 1, true) then
            if islink(method, (stated(hook, param))) then
                return false
            end
            path = quietly(param, function()
                return luajava.newInstance('java.io.File', argument):getCanonicalPath()
            end)
            if path == nil then
                return false
            end
        end
    end
    local time = FAKE * 1000
    local kept = {}
    if path == '/data/app' or string.sub(path, 1, 10) == '/data/app/' then
        time = apptime(param, path)
        if time == nil then
            return false
        end
    elseif DATA[path] then
        if not ondata(param, path) then
            return false
        end
        -- Each time of the current boot stays, the others and the inode, of the folder made at setup, are faked; /data
        -- keeps its inode, so its answer stays when all its times do, and File.lastModified has the one time only
        local _, times = stated(hook, param)
        local boot = booted(param)
        for name, real in pairs(times) do
            kept[name] = (boot ~= nil and real >= boot)
        end
        if kept.mtime and (method == 'getLastModifiedTime' or (path == '/data' and kept.atime and kept.ctime)) then
            return false
        end
    elseif not storage(path) then
        -- Other paths with /./, //, /../ or /fd/
        return false
    end
    local seconds = math.floor(time / 1000)
    local nanos = (time % 1000) * 1000000
    -- The inode of a folder stays too, so apps would read the same number: one of its own for each app, of its fake
    -- Android ID (see Fake values), which lets file systems tell folders apart as before; the mode decides the key.
    -- The root of a file system has the same inode on all devices, which /data keeps.
    local function inode(link, real)
        if path == '/data' then
            return real
        end
        local key = (param:getSetting('value.android_id') or '') .. inodekey(path, link)
        return tonumber(string.sub(param:uuidOf(key), 1, 7), 16) + 1000
    end

    -- java.nio reads the times into the attributes of its argument; the boot image has the methods calling this
    -- one (UnixFileAttributes.get) compiled inline, so their hooks would not apply
    if hook:getClassName() == 'sun.nio.fs.UnixNativeDispatcher' then
        local attributes = param:getArgument(1)
        if attributes == nil or (attributes.st_atime_sec == seconds and attributes.st_mtime_sec == seconds and
                attributes.st_ctime_sec == seconds) then
            return false
        end
        local old = attributes.st_mtime_sec
        attributes.st_ino = inode(islink(method, attributes.st_mode), attributes.st_ino)
        for _, name in ipairs({ 'atime', 'mtime', 'ctime' }) do
            if not kept[name] then
                attributes['st_' .. name .. '_sec'] = seconds
                attributes['st_' .. name .. '_nsec'] = nanos
            end
        end
        -- Recent versions have the time a file was made; a field the class does not have is nil
        if attributes.st_birthtime_sec ~= nil then
            attributes.st_birthtime_sec = seconds
        end
        return true, tostring(old), tostring(attributes.st_mtime_sec)
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    if method == 'getLastModifiedTime' then
        -- File.lastModified, 0 when there is no such file
        if result == 0 or result == time then
            return false
        end
        param:setResult(time)
        return true, tostring(result), tostring(time)
    end

    -- Os.stat, Os.lstat and Os.fstat, with times in nanoseconds
    if result.st_atime == seconds and result.st_mtime == seconds and result.st_ctime == seconds then
        return false
    end
    local spec = luajava.newInstance('android.system.StructTimespec', seconds, nanos)
    local fake = luajava.newInstance('android.system.StructStat',
            result.st_dev, inode(islink(method, result.st_mode), result.st_ino), result.st_mode, result.st_nlink, result.st_uid,
            result.st_gid, result.st_rdev, result.st_size, kept.atime and result.st_atim or spec,
            kept.mtime and result.st_mtim or spec, kept.ctime and result.st_ctim or spec, result.st_blksize, result.st_blocks)
    param:setResult(fake)
    return true, tostring(result.st_mtime), tostring(kept.mtime and result.st_mtime or seconds)
end
