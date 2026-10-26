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
-- of the SD card or the storage (/sys/block/mmcblk0/device/cid and serial, /sys/class/sec/sdinfo/data), the
-- calibration of the light sensor, the files of the touchscreen (/sys/class/sec/tsp) and of the fingerprint
-- sensor (/sys/class/fingerprint/fingerprint). For restricted apps the files do not exist: they cannot be opened,
-- read, listed or found, with java.io, android.system.Os and java.nio.file. The paths are resolved first,
-- so the links of /sys do not lead to the files either, like /sys/bus/mmc/devices/mmc0:0001/cid.
-- Each variant hides the files of its group; cat run by the app fails for them too (see shell_commands), and
-- folders listed with java.nio.file leave the names of hidden files out. Not covered is native code.

-- The files of each group, as patterns of the path; a pattern ending in / covers the files of a folder
local FILES = {
    ['Read.Identifiers'] = {
        '/chip%-id/unique_id$', '/chip%-id/lot_id$',
        '/mmc%d+:%x+/cid$', '/mmc%d+:%x+/serial$', '/sec/sdinfo/data$' },
    ['Use.Tracking'] = {
        '/chip%-id$', '/chip%-id/',
        '/mmc%d+:%x+/date$', '/mmc%d+:%x+/manfid$', '/mmc%d+:%x+/oemid$', '/mmc%d+:%x+/name$',
        '/sensors/light_sensor/light_cal$', '/sec/tsp$', '/sec/tsp/' },
    ['Read.Biometrics'] = {
        '/fingerprint/fingerprint$', '/fingerprint/fingerprint/', '/sec/tsp/fod_info$', '/sec/tsp/fod_pos$' },
}

-- The error of a file which does not exist, OsConstants.ENOENT, the same on every Linux
local ENOENT = 2

-- The script runs for the files of /sys only, see XCore.getFilter
function filter(hook)
    return { '/sys/*' }
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
    local patterns = FILES[hook:getGroup()]
    if patterns == nil then
        return false
    end
    local resolved = resolve(path)
    for _, pattern in ipairs(patterns) do
        if string.find(path, pattern) or string.find(resolved, pattern) then
            return true
        end
    end
    return false
end

-- The path of the first argument: a text, a java.io.File or a path of java.nio, which print their path
function pathof(param)
    return tostring(param:getArgument(0))
end

-- Files.newDirectoryStream, list and walk: the names of hidden files are left out by a filter in front of
-- the one of the app; a hidden folder is not opened at all (see opendir). Listing a folder of /sys is not
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
    local class = hook:getClassName()
    if class == 'sun.nio.fs.UnixFileSystemProvider' then
        return listing(hook, param, path)
    end
    if not hidden(hook, path) then
        return false
    end

    local method = hook:getMethodName()
    if class == 'libcore.io.BlockGuardOs' then
        -- Os.open, stat, lstat and access, which FileInputStream, File.length and File.canRead use
        param:setResult(luajava.newInstance('android.system.ErrnoException', method, ENOENT))
    elseif class == 'java.io.UnixFileSystem' then
        if method == 'list0' then
            -- File.list of a hidden folder: like of a folder which does not exist
            param:setResult(nil)
        else
            -- File.exists, isFile and isDirectory: no attributes, like of a file which does not exist
            param:setResult(0)
        end
    else
        -- java.nio.file: Files.exists, the mode of Files.isRegularFile, the rest throws like the kernel
        if method == 'exists' then
            param:setResult(false)
        elseif method == 'stat' and hook:getReturnType() == 'int' then
            param:setResult(0)
        else
            param:setResult(luajava.newInstance('sun.nio.fs.UnixException', ENOENT))
        end
    end
    return true, path
end

-- File.list of a folder with hidden files: without their names
function after(hook, param)
    if hook:getMethodName() ~= 'list0' or param:getException() ~= nil then
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
