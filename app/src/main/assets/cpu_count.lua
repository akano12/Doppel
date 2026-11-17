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

-- The number of processors narrows down the chip, with their speeds in /sys/devices/system/cpu, which do not exist
-- for the app (see hardware_files), and their layout. It becomes 8, which most phones have: apps size their threads
-- by it, which more or fewer threads than processors do not break. Runtime.availableProcessors asks
-- Os.sysconf(_SC_NPROCESSORS_CONF), which compiled code of the framework can call without the hook, so it is
-- hooked too, and the files of /sys/devices/system/cpu agree: the processors possible, present and online are 0-7,
-- none is offline, each of the 8 is online and the kernel is built for at least 8 (kernel_max, the highest number it
-- could have, 31 on most phones, which stays). They agree by each path the open filter lets through, which the script
-- resolves like the file system: with // or /./, with .. like cpu6/../online, which is the online of all of them,
-- by /proc/self/root, by the links of the processors in /sys/bus/cpu/devices and by links the app makes itself, and by
-- the descriptor of a folder and a name, by which a SecureDirectoryStream opens them (openat, see canonical). The
-- processors of /proc/cpuinfo are 8 too (see cpuinfo), and the folders of the processors after cpu7 do not exist (see
-- hardware_files). Not covered are native code (sysconf, nproc, sched_getaffinity) and devices with fewer than 8
-- processors, which keep their folders: there are no folders to show for the others.
local COUNT = 8
local RANGE = '0-7\n'
local FAKES = { possible = RANGE, present = RANGE, online = RANGE, offline = '\n', kernel_max = (COUNT - 1) .. '\n' }
-- The layout of each processor (its folder topology, which the kernel makes for each) is that of 8 equal cores in one
-- cluster, like the 8 of /proc/cpuinfo: a core of its own without other threads, in the package and cluster of all 8,
-- numbered from 0 like arch_topology.c of Linux numbers the cores of a cluster. Only the files the kernel has are
-- opened, and its other files stay as they are, like die_id of x86. A mask has a hex digit for each 4 processors, ff
-- for the 8. The capacity of each is the one of Linux without capacities in the device tree (1024).
-- shortcut: x86 numbers the cores of a package otherwise, like core id 0 of the processors of /proc/cpuinfo, which
-- repeat the first one; give x86 its own layout if apps compare the two there.
local ALL = string.format('%x', 2 ^ COUNT - 1)
for cpu = 0, COUNT - 1 do
    local own, folder = string.format('%02x', 2 ^ cpu), 'cpu' .. cpu .. '/topology/'
    FAKES['cpu' .. cpu .. '/online'] = '1\n'
    FAKES['cpu' .. cpu .. '/cpu_capacity'] = '1024\n'
    FAKES[folder .. 'core_id'] = cpu .. '\n'
    FAKES[folder .. 'physical_package_id'] = '0\n'
    FAKES[folder .. 'cluster_id'] = '0\n'
    for _, name in ipairs({ 'thread_siblings', 'core_cpus' }) do
        FAKES[folder .. name] = own .. '\n'
        FAKES[folder .. name .. '_list'] = cpu .. '\n'
    end
    for _, name in ipairs({ 'core_siblings', 'package_cpus', 'cluster_cpus' }) do
        FAKES[folder .. name] = ALL .. '\n'
        FAKES[folder .. name .. '_list'] = RANGE
    end
end
-- The names of the files of FAKES, which the path of a file opened must end in
local NAMES = {}
for path in pairs(FAKES) do
    NAMES[string.match(path, '[%a_]+$')] = true
end

function filter(hook)
    if hook:getMethodName() == 'open' then
        -- Also the links of the processors in /sys/bus/cpu/devices, like cpu6/online, which name no system/cpu
        return { '*system/cpu/*', '*/status*', '*bus/cpu/*' }
    end
    return nil
end

-- The text of the path the app opened: the path of open, or the name in the folder of its descriptor of openat
function opened(param, at)
    if at then
        return luajava.newInstance('java.lang.String', param:getArgument(1))
    end
    return tostring(param:getArgument(0))
end

-- The canonical path of the file the app opened, without the hooks of the app, which would else see the lookup and
-- could hide the file; nil when it cannot be resolved. Of openat the link of the descriptor it returned in
-- /proc/self/fd, whatever folder and name led to it, read without telling StrictMode of a disk read, which openat of
-- java.nio does not report (like openedat of cpuinfo)
function canonical(param, at)
    if at then
        local ok, link = pcall(function()
            return param:unhooked(function()
                local strict = luajava.bindClass('android.os.StrictMode')
                local policy = strict:allowThreadDiskReads()
                local done, value = pcall(function()
                    return luajava.bindClass('android.system.Os'):readlink('/proc/self/fd/' .. param:getResult())
                end)
                strict:setThreadPolicy(policy)
                return done and value or nil
            end)
        end)
        return ok and link or nil
    end
    local path = tostring(param:getArgument(0))
    local ok, resolved = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):getCanonicalPath()
        end)
    end)
    return ok and resolved or nil
end

-- Whether the filter let a path in by the file its links lead to, not by its text, like a link the app made itself to
-- /sys/devices/system/cpu/online (see XCore.Filter), which is resolved whatever its name; openat has no filter, so its
-- names count by their text only
function linked(path)
    return not (string.find(path, 'system/cpu/', 1, true) or string.find(path, '/status', 1, true) or
            string.find(path, 'bus/cpu/', 1, true))
end

-- The files of /sys/devices/system/cpu of FAKES with the text of the 8 processors, by their canonical path
function ranges(param, at)
    -- A path which opened a file ends in its name, since a / or /. after a file fails, so others are not resolved
    local path = opened(param, at)
    if not NAMES[string.match(path, '[%a_]+$')] and (at or not linked(path)) then
        return false
    end
    local name = string.match(canonical(param, at) or '', '^/sys/devices/system/cpu/(.+)$')
    local fake = name and FAKES[name]
    if fake == nil then
        return false
    end
    local text = param:getFileText()
    if name == 'kernel_max' and (tonumber(string.match(text, '%d+')) or 0) >= COUNT - 1 then
        return false
    end
    if text == fake then
        return false
    end
    param:setFileText(fake)
    return true, (string.gsub(text, '\n', '')), (string.gsub(fake, '\n', ''))
end

-- The processors a task of the app may run on in its status file of /proc, of the process or of a thread, also by
-- /proc/self, /proc/thread-self and other links: all of the 8, like the app in front has on most phones. Android keeps
-- the apps in the background on the cores of a cluster, which would tell the layout of the chip (see hardware_files),
-- so they read the 8 too, like on a phone without these limits. shortcut: from Android 11 the app reads the lines of
-- the moment it opened the file, also when it reads the descriptor again from the start, like for a TracerPid it
-- watches; before Android 11 the file is a pipe (see XParam.oldFile), so a seek or getFilePointer of RandomAccessFile
-- and the position of a FileChannel fail with ESPIPE (Illegal seek) and reading again finds the end of the file; a
-- thread the app narrowed itself reads the 8 too; upgrade when a hook can answer each read and setFileText gives small
-- files a file which can seek before Android 11.
function status(param, at)
    local path = opened(param, at)
    if string.sub('/' .. path, -7) ~= '/status' and (at or not linked(path)) then
        return false
    end
    local resolved = canonical(param, at)
    if not resolved or not (string.find(resolved, '^/proc/%d+/status$') or
            string.find(resolved, '^/proc/%d+/task/%d+/status$')) then
        return false
    end
    local text = param:getFileText()
    local real = string.match(text, '\nCpus_allowed_list:\t([^\n]*)')
    -- The mask has a hex digit for each 4 processors the kernel has, ff for 8
    local fake = string.gsub(text, '\nCpus_allowed:\t[^\n]*', '\nCpus_allowed:\t' .. string.format('%x', 2 ^ COUNT - 1))
    fake = string.gsub(fake, '\nCpus_allowed_list:\t[^\n]*', '\nCpus_allowed_list:\t' .. string.sub(RANGE, 1, -2))
    if fake == text then
        return false
    end
    param:setFileText(fake)
    return true, real, string.sub(RANGE, 1, -2)
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local at = (hook:getMethodName() == 'openat')
    if hook:getMethodName() == 'open' or at then
        if param:getResult() == nil then
            return false
        end
        local restricted, old, new = ranges(param, at)
        if restricted then
            return restricted, old, new
        end
        return status(param, at)
    end
    if hook:getMethodName() == 'sysconf' then
        local constants = luajava.bindClass('android.system.OsConstants')
        local name = param:getArgument(0)
        if name ~= constants._SC_NPROCESSORS_CONF and name ~= constants._SC_NPROCESSORS_ONLN then
            return false
        end
    end
    local result = param:getResult()
    if result == nil or result == COUNT then
        return false
    end
    param:setResult(COUNT)
    return true, tostring(result), tostring(COUNT)
end
