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
-- each install. The files of the app itself keep their times.
-- The times are read with Os.stat and Os.lstat, File.lastModified and java.nio.file.Files (also the time
-- the folder was made); not covered are the times of files in the folders, Os.fstat, the attributes of entries
-- of a SecureDirectoryStream of java.nio (fstatat, like Files.newDirectoryStream of the storage gives) and native
-- code.
local FAKE = 1230768000
local FLAG_SYSTEM = 1

-- The folders the system makes, which apps can read the times of
local FOLDERS = { 'Alarms', 'Android', 'Android/data', 'Android/media', 'Android/obb', 'Audiobooks', 'DCIM',
                  'Documents', 'Download', 'Movies', 'Music', 'Notifications', 'Pictures', 'Podcasts', 'Recordings',
                  'Ringtones' }

-- The paths of the shared storage of the user
function tops()
    local root = luajava.bindClass('android.os.Environment'):getExternalStorageDirectory():getPath()
    return { root, '/sdcard', '/storage/self/primary' }
end

-- The paths of the folders, with the paths of the shared storage of the user, see XCore.getFilter. The shared
-- storage finds names in any case, so the folders in lower and upper case too, like /sdcard/download; paths with
-- /./, // or /../ run the script too, which compares them resolved (see after). Not covered are other mixes of
-- case, like /sdcard/DownLoad: the script would have to run for every file of the storage.
function filter(hook)
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
    return '/' .. table.concat(parts, '/')
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

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local path = normal(tostring(param:getArgument(0)))
    local time = FAKE * 1000
    if path == '/data/app' or string.sub(path, 1, 10) == '/data/app/' then
        time = apptime(param, path)
        if time == nil then
            return false
        end
    elseif not storage(path) then
        -- Other paths with /./, // or /../
        return false
    end
    local seconds = math.floor(time / 1000)
    local nanos = (time % 1000) * 1000000

    -- java.nio reads the times into the attributes of its argument; the boot image has the methods calling this
    -- one (UnixFileAttributes.get) compiled inline, so their hooks would not apply
    if hook:getClassName() == 'sun.nio.fs.UnixNativeDispatcher' then
        local attributes = param:getArgument(1)
        if attributes == nil or (attributes.st_atime_sec == seconds and attributes.st_mtime_sec == seconds and
                attributes.st_ctime_sec == seconds) then
            return false
        end
        local old = attributes.st_mtime_sec
        for _, name in ipairs({ 'atime', 'mtime', 'ctime' }) do
            attributes['st_' .. name .. '_sec'] = seconds
            attributes['st_' .. name .. '_nsec'] = nanos
        end
        -- Recent versions have the time a file was made; a field the class does not have is nil
        if attributes.st_birthtime_sec ~= nil then
            attributes.st_birthtime_sec = seconds
        end
        return true, tostring(old), tostring(seconds)
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    if method == 'getLastModifiedTime' then
        -- File.lastModified, 0 when there is no such file
        if result == 0 or result == time then
            return false
        end
        param:setResult(time)
        return true, tostring(result), tostring(time)
    end

    -- Os.stat and Os.lstat, with times in nanoseconds
    if result.st_atime == seconds and result.st_mtime == seconds and result.st_ctime == seconds then
        return false
    end
    local spec = luajava.newInstance('android.system.StructTimespec', seconds, nanos)
    local fake = luajava.newInstance('android.system.StructStat',
            result.st_dev, result.st_ino, result.st_mode, result.st_nlink, result.st_uid, result.st_gid,
            result.st_rdev, result.st_size, spec, spec, spec, result.st_blksize, result.st_blocks)
    param:setResult(fake)
    return true, tostring(result.st_mtime), tostring(seconds)
end
