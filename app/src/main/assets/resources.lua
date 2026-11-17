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

-- The exact sizes of memory and storage, like 3919 MB, and the free space narrow down the device model,
-- its firmware and the device itself. Instead of fake values, which apps rely on, like for the size of
-- caches and before writing files, a total tells only the size the device would be sold with: it is a fixed
-- share of that size, like the kernel and the other partitions leave, so it is no sold size itself, and the
-- total of the device and its advertised memory are what Android derives from these totals. Free space is
-- rounded down to two digits and never above its total, so apps never think there is more free space than
-- there is, and free memory is in whole pages like the kernel counts it. The low memory thresholds are the ones
-- Android gives every device without the settings of a maker. Small file systems keep their exact sizes. StatFs is
-- hooked itself, because the framework, which is compiled ahead of time, calls Os.statvfs inlined, so its hook only
-- applies to apps.

local GB = 1000000000
local GIB = 1073741824

-- Memory sizes devices are sold with, in GiB
local MEMORY = { 1, 1.5, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32, 48, 64 }

-- The total memory: the share of the memory a device is sold with which the kernel gives (MemTotal), without the
-- kernel itself, the reserved memory and the memory of the modem and graphics, like 3.72 GiB of 4; above 0.9, so the
-- total stays in its size, at most the size in GB, so Android rounds it to the same advertised size whether it compares
-- with powers of GB or of GiB, and in whole pages of 16 KiB, which are whole pages of 4 KiB too
local MEMORYSHARE = 0.93
local PAGE = 16384

function memorysize(value)
    for _, size in ipairs(MEMORY) do
        if value <= size * GIB then
            return math.floor(size * GIB * MEMORYSHARE / PAGE) * PAGE
        end
    end
    return value
end

-- The size a disk is sold with, like Android rounds it (FileUtils.roundStorageSize): a power of two of MB, GB and from
-- 1000 GB on of TB, which only the first release of Android 14 takes up to the same power of MiB, GiB and TiB, like 130
-- GB to 128 GB; its quarterly releases and Android 15 and later compare with powers of MB, GB and TB again.
-- shortcut: the quarterly releases of Android 14 have the same SDK_INT and round a size between a power of two of GB
-- and the same power of GiB, like 64.1 GB, up to the next power, where this rounds it down; only real disk sizes fall
-- there, the faked totals stay at or below their power of GB, and FileUtils.roundStorageSize, which would tell, is
-- hidden without the mark of the APIs apps may still use, so the scripts in the app may not call it; tell the releases
-- apart by another sign if one is found.
function storagesize(value)
    local binary = sdk() == 34
    local size, kilo, kibi = 1, 1, 1
    while size * (binary and kibi or kilo) < value do
        size = size * 2
        if size > 512 then
            size, kilo, kibi = 1, kilo * 1000, kibi * 1024
        end
    end
    return size * kilo
end

-- File systems in whole MiB, which every block size up to 1 MiB divides, so File and FileStore, which do not know the
-- block size, give whole blocks like StatFs and statvfs.
-- shortcut: a file system with blocks above 1 MiB, like an exFAT card formatted so, gets byte sizes which are no whole
-- blocks of its StatFs, take a larger unit if such cards are seen.
local UNIT = 1048576

-- The total of a file system: a share of the size it would be sold with, like a partition of a disk, the same for all
-- file systems of that size; above 0.5, so the total stays in its size
local SHARE = 0.875

function partitionsize(value)
    -- Small file systems keep their size
    if value < GB then
        return value
    end
    return math.floor(storagesize(value) * SHARE / UNIT) * UNIT
end

-- Down to two significant digits, like 7917 MB to 7900 MB
function freesize(value)
    if value < 100 then
        return value
    end
    local step = 1
    while value >= step * 100 do
        step = step * 10
    end
    return math.floor(value / step) * step
end

-- Free memory down to two significant digits and up to a whole page again, like the kernel counts it: still not above
-- the real size, which is whole pages too, and kept by a second rounding
function freememsize(value, page)
    return math.ceil(freesize(value) / page) * page
end

-- The size of a page of memory, read without the hooks
function pagesize(param)
    return param:unhooked(function()
        local constants = luajava.bindClass('android.system.OsConstants')
        return luajava.bindClass('android.system.Os'):sysconf(constants._SC_PAGESIZE)
    end)
end

-- Free space of a file system down to two significant digits of whole MiB, which a second rounding keeps, and not above
-- the faked total of its real total, when known, rounded the same way, so a nearly empty file system has less free
-- space than its total, like on a device, which a second rounding keeps too.
-- shortcut: free space does not follow writes smaller than its step, like 1 GB at 50 GB free, which an app that
-- writes and measures again sees; the exact free space would tell more, follow writes from a rounded start only if
-- apps are seen to measure so.
function spacesize(value, total)
    local fake = freesize(math.floor(value / UNIT)) * UNIT
    if total ~= nil and total > 0 then
        fake = math.min(fake, freesize(math.floor(partitionsize(total) / UNIT)) * UNIT)
    end
    return fake
end

-- Opening files, of which only /proc/meminfo has the sizes, see XCore.getFilter; also by other paths,
-- like /proc/self/root/proc/meminfo, by java.nio (UnixNativeDispatcher.open), see XParam.opens, and by
-- Process.readProcLines and readProcFile, which the framework calls for other files of /proc too.
-- Also by links of apps themselves to the file, which the filter compares by the path they lead to. openat has no path
-- the filter could compare, see openedat.
function filter(hook)
    local method = hook:getMethodName()
    if method == 'open' or method == 'readProcLines' or method == 'readProcFile' then
        return { '*meminfo*' }
    end
    return nil
end

-- Whether openat, by which a SecureDirectoryStream opens a file by the descriptor of its folder and its name, opened
-- /proc/meminfo: for a name with meminfo, like the filter of open, the link of the descriptor it returned in
-- /proc/self/fd, which is the path of the file, whatever folder and name led to it; read without the hooks and without
-- telling StrictMode of a disk read, which openat of java.nio does not report
function openedat(param)
    local name = luajava.newInstance('java.lang.String', param:getArgument(1))
    if not string.find(name, 'meminfo', 1, true) then
        return false
    end
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
    return ok and link == '/proc/meminfo'
end

-- The lines of /proc/meminfo of the Linux kernel (fs/proc/meminfo.c of 4.4 to 6.x, the hugetlb pages and DirectMap
-- of x86); the lines makers add, like RbinTotal, ZeroedFree and HugepagePool of Samsung or ION and GPU memory, name
-- the maker and are left out
local MEMINFO = {}
for name in string.gmatch('MemTotal MemFree MemAvailable Buffers Cached SwapCached Active Inactive Active(anon) ' ..
        'Inactive(anon) Active(file) Inactive(file) Unevictable Mlocked HighTotal HighFree LowTotal LowFree MmapCopy ' ..
        'SwapTotal SwapFree Zswap Zswapped Dirty Writeback AnonPages Mapped Shmem KReclaimable Slab SReclaimable ' ..
        'SUnreclaim KernelStack ShadowCallStack PageTables SecPageTables Quicklists NFS_Unstable Bounce WritebackTmp ' ..
        'CommitLimit Committed_AS VmallocTotal VmallocUsed VmallocChunk Percpu HardwareCorrupted AnonHugePages ' ..
        'ShmemHugePages ShmemPmdMapped FileHugePages FilePmdMapped CmaTotal CmaFree Unaccepted Balloon GPUActive ' ..
        'GPUReclaim HugePages_Total HugePages_Free HugePages_Rsvd HugePages_Surp Hugepagesize Hugetlb DirectMap4k ' ..
        'DirectMap2M DirectMap4M DirectMap1G', '%S+') do
    MEMINFO[name .. ':'] = true
end

-- The faked size of a line of /proc/meminfo which tells the total or the free memory, nil for the other lines;
-- state has the size of a page and keeps what MemTotal and MemFree, which come first, changed, for the lines after them
function memsize(state, name, size)
    if name == 'MemTotal:' then
        local fake = memorysize(size)
        state.scale, state.more, state.total = fake / size, fake - size, fake
        return fake
    elseif name == 'MemFree:' or name == 'MemAvailable:' then
        -- Not above the faked total, which can be below the real one
        local fake = math.min(freememsize(size, state.page), state.total or size)
        if name == 'MemFree:' then
            -- What the line lost, in the whole kB of the line
            state.less = size - math.floor(fake / 1024) * 1024
        end
        return fake
    elseif name == 'SwapTotal:' or name == 'SwapFree:' or name == 'CommitLimit:' then
        -- SwapTotal, a share of the memory with zram, and CommitLimit, half of it plus SwapTotal, tell the total,
        -- so they grow with it, like on a device with the faked total, in whole pages; SwapFree with them, so it stays
        -- below SwapTotal like the kernel counts it
        return math.floor(size * (state.scale or 1) / state.page) * state.page
    elseif name == 'HighTotal:' then
        -- 32-bit kernels with high memory: HighTotal and LowTotal add up to MemTotal, HighFree and LowFree to MemFree.
        -- The low memory is what the kernel keeps mapped, which its address space limits, so on a device with more or
        -- less memory only the high memory differs; the free memory lost is taken from the free high memory first
        return size + (state.more or 0)
    elseif name == 'HighFree:' or name == 'LowFree:' then
        local taken = math.min(size, state.less or 0)
        state.less = (state.less or 0) - taken
        return size - taken
    end
    return nil
end

-- The sizes of /proc/meminfo like the ones of ActivityManager.getMemoryInfo, which come from this file:
-- the total memory rounded up, the free and available memory down, in the format of the kernel; the free sizes in
-- whole pages of page bytes
function meminfo(param, page)
    local changed = false
    local old, new = {}, {}
    local lines = {}
    local state = { page = page }
    for line in string.gmatch(param:getFileText(), '[^\n]*\n') do
        if not MEMINFO[string.match(line, '^[^:]*:')] then
            changed = true
            line = ''
        end
        local name, value = string.match(line, '^(%w+:)%s*(%d+) kB\n$')
        local size = value and tonumber(value) * 1024
        local fake = name and memsize(state, name, size)
        if fake ~= nil then
            -- The name in 16 characters, the size in 8, like the kernel; padded by hand, LuaJ pads no texts (%s)
            -- and its %d wraps at 2^31
            local kb = tostring(math.floor(fake / 1024))
            local faked = name .. string.rep(' ', 16 - #name) .. string.rep(' ', math.max(0, 8 - #kb)) .. kb .. ' kB\n'
            changed = changed or faked ~= line
            line = faked
            -- Like the log of ActivityManager.getMemoryInfo: total and available memory
            if name == 'MemTotal:' or name == 'MemAvailable:' then
                table.insert(old, tostring(size))
                table.insert(new, tostring(fake))
            end
        end
        table.insert(lines, line)
    end
    if not changed then
        return false
    end
    param:setFileText(table.concat(lines))
    return true, table.concat(old, ' '), table.concat(new, ' ')
end

-- The version of Android
function sdk()
    return luajava.bindClass('android.os.Build$VERSION').SDK_INT
end

-- The text of /proc/meminfo faked like for the app, for the natives which read the file themselves and the free memory of
-- getMemoryInfo: read without the hooks of the app and without StrictMode, which the native reads do not tell; nil when
-- it cannot be read
function fakedmeminfo(param)
    local text = param:unhooked(function()
        local strict = luajava.bindClass('android.os.StrictMode')
        local policy = strict:allowThreadDiskReads()
        local real = param:readFile('/proc/meminfo')
        strict:setThreadPolicy(policy)
        return real
    end)
    if text == nil then
        return nil
    end
    local file = { text = text,
                   getFileText = function(self) return self.text end,
                   setFileText = function(self, value) self.text = value end }
    meminfo(file, pagesize(param))
    return file.text
end

-- The sizes of fields of /proc/meminfo, parsed like Process.readProcLines does: each line gives the first field it starts
-- with the number after it and its spaces or tabs, or 0, until each field has one
function procsizes(text, fields, count)
    local found, values = 0, {}
    for i = 1, count do
        values[i] = 0
    end
    for line in string.gmatch(text, '[^\n]*\n') do
        if found == count then
            break
        end
        for i = 1, count do
            local field = fields[i]
            if string.sub(line, 1, #field) == field then
                values[i] = tonumber(string.match(line, '^[ \t]*(%d*)', #field + 1)) or 0
                found = found + 1
                break
            end
        end
    end
    return values
end

-- Process.getFreeMemory, which also gives MemoryInfo.availMem in the system server: MemFree and Cached of /proc/meminfo,
-- MemAvailable from Android 16, here of the faked file, so they agree with it; the real size rounded like MemFree when
-- the file cannot be read
function freememory(param, real)
    local text = fakedmeminfo(param)
    if text == nil then
        return freememsize(real, pagesize(param))
    end
    local fields = sdk() >= 36 and { 'MemAvailable:' } or { 'MemFree:', 'Cached:' }
    local sum = 0
    for _, size in ipairs(procsizes(text, fields, #fields)) do
        sum = sum + size
    end
    return sum * 1024
end

-- The low memory thresholds of MemoryInfo in the order of its parcel, of the home app, the cached apps, the secondary
-- server, the visible and the foreground app: the levels of ProcessList.updateOomLevels for 700 MB or more, which every
-- faked total is, without the settings of a maker (config_lowMemoryKillerMinFreeKbytesAbsolute and Adjust), so they
-- tell neither the maker nor the real total; in kB times 1024 like getMemLevel, with the levels of the cached apps
-- raised on devices with 64-bit ABIs
local THRESHOLDS = { 'threshold', 'hiddenAppThreshold', 'secondaryServerThreshold', 'visibleAppThreshold',
                     'foregroundAppThreshold' }

function thresholds()
    local home = luajava.bindClass('android.os.Build').SUPPORTED_64_BIT_ABIS.length > 0 and 221184 or 147456
    return { home * 1024, home * 1024, home * 1024, 92160 * 1024, 73728 * 1024 }
end

-- Low memory like ProcessList.getMemoryInfo computes it: below the middle between the thresholds of the home app and of
-- the cached apps, of which the difference is halved like a Java long
function lowmemory(avail, home, cached)
    return avail < home + math.modf((cached - home) / 2)
end

-- MemoryInfo faked through its parcel, which has the hidden thresholds too, which the runtime does not show the scripts
-- of apps that target Android 12 or later, while these apps can read them from a parcel themselves; the advertised size
-- comes first from Android 14. Only for the class MemoryInfo itself, since a subclass of the app would run its own
-- writeToParcel and readFromParcel, which could count the calls or ignore the parcel. False when the class or the layout
-- of the parcel, of a maker, is another one.
function memoryparcel(param, info)
    if info:getClass() ~= luajava.bindClass('android.app.ActivityManager$MemoryInfo') then
        return false
    end
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    info:writeToParcel(parcel, 0)
    parcel:setDataPosition(0)
    local advertised = sdk() >= 34 and parcel:readLong()
    local avail, total, home = parcel:readLong(), parcel:readLong(), parcel:readLong()
    if total ~= info.totalMem or home ~= info.threshold then
        parcel:recycle()
        return false
    end
    local fake, levels = memorysize(total), thresholds()
    avail = freememory(param, avail)
    parcel:setDataPosition(0)
    if advertised then
        -- The advertised memory of the faked total, like Process.getAdvertisedMem gives it on a device without
        -- ro.boot.ddr_size, which reads as not set (see systemproperties_get): the total rounded like a disk, like
        -- 4 GB of 3.72 GiB; Android never gives a power of two of GiB
        parcel:writeLong(advertised > 0 and storagesize(fake) or advertised)
    end
    parcel:writeLong(avail)
    parcel:writeLong(fake)
    parcel:writeLong(levels[1])
    parcel:writeInt(lowmemory(avail, levels[1], levels[2]) and 1 or 0)
    for i = 2, 5 do
        parcel:writeLong(levels[i])
    end
    parcel:setDataPosition(0)
    info:readFromParcel(parcel)
    parcel:recycle()
    return true
end

-- MemoryInfo faked through its fields, for a subclass of the app and a parcel of a maker; the hidden thresholds are nil
-- when the runtime does not show them.
-- shortcut: when the runtime hides the thresholds of the cached apps, the secondary server and the visible apps, they
-- stay real in the parcel of such an info; set them by reflection past the hidden API rules when apps are seen to
-- subclass the info
function memoryfields(param, info)
    local fake, levels = memorysize(info.totalMem), thresholds()
    for i, name in ipairs(THRESHOLDS) do
        if info[name] ~= nil then
            info[name] = levels[i]
        end
    end
    info.totalMem = fake
    info.availMem = freememory(param, info.availMem)
    -- The advertised memory of Android 14 and later, like memoryparcel
    if info.advertisedMem ~= nil and info.advertisedMem > 0 then
        info.advertisedMem = storagesize(fake)
    end
    info.lowMemory = lowmemory(info.availMem, levels[1], levels[2])
end

-- What fn gives, run without the hooks and without telling StrictMode of a disk read, which the call of the app told
-- already; nil when it fails
function quietly(param, fn)
    return param:unhooked(function()
        local strict = luajava.bindClass('android.os.StrictMode')
        local policy = strict:allowThreadDiskReads()
        local ok, value = pcall(fn)
        strict:setThreadPolicy(policy)
        return ok and value or nil
    end)
end

-- Whether a call of StorageManager or StorageStatsManager is of the internal storage
function internal(param)
    return luajava.bindClass('android.os.storage.StorageManager').UUID_DEFAULT:equals(param:getArgument(0))
end

-- The real total of the storage of a free size, for its limit: of the StatFs, file or file store, of the internal
-- storage for StorageManager and StorageStatsManager; nil for another volume and when it cannot be read.
-- shortcut: a StatFs or File of a subclass of the app gets no limit, since asking it for its total would run the code
-- of the app, which could count the calls; read the fields of the object if apps subclass them.
-- shortcut: the free space of another volume by StorageManager and StorageStatsManager gets no limit, so on a nearly
-- empty card it can be above the total of its file system, find the path of the volume if apps compare them.
function realtotal(hook, param)
    local name = hook:getName()
    return quietly(param, function()
        local this = param:getThis()
        if string.find(name, '^StatFs%.') then
            return this:getClass():getName() == 'android.os.StatFs' and this:getTotalBytes() or nil
        elseif string.find(name, '^File%.') then
            return this:getClass():getName() == 'java.io.File' and this:getTotalSpace() or nil
        elseif string.find(name, '^UnixFileStore%.') then
            return this:getTotalSpace()
        elseif internal(param) then
            return luajava.bindClass('android.os.Environment'):getDataDirectory():getTotalSpace()
        end
        return nil
    end)
end

-- The size of the internal storage, primary true, else of another volume, of StorageStatsManager.getTotalBytes: what
-- Android derives of the totals of /data and, from Android 9, /system (StorageManager.getPrimaryStorageSize), here of
-- their faked totals, so these add up to it like on a device; of other volumes the size of the disk, which Android
-- rounded already. The hidden getPrimaryStorageSize itself adds up the totals of File in the app, so it gets the faked
-- ones. The block device of the internal storage, whose size the storage service gives without a permission from Android
-- 14 QPR3 and uses for the total above 512 GB from Android 15, has the same size, so the total agrees with it like on a
-- device sold with that size.
-- shortcut: where the boot image inlined File.getTotalSpace into getPrimaryStorageSize, that one gives the real sum,
-- hook it with devicesize if apps read it.
-- shortcut: an app which transacts with the storage service itself gets the exact block device size, round it in the
-- reply parcel if apps do so.
function devicesize(param, result, primary)
    local totals = primary and quietly(param, function()
        local environment = luajava.bindClass('android.os.Environment')
        return { environment:getDataDirectory():getTotalSpace(), environment:getRootDirectory():getTotalSpace() }
    end)
    if not totals or totals[1] <= 0 then
        return storagesize(result)
    end
    return storagesize(partitionsize(totals[1]) + (sdk() < 28 and 0 or partitionsize(totals[2])))
end

function after(hook, param)
    local method = hook:getMethodName()

    if method == 'open' or method == 'openat' then
        if param:getException() ~= nil or param:getResult() == nil then
            return false
        end
        if not (method == 'open' and param:opens('/proc/meminfo') or method == 'openat' and openedat(param)) then
            return false
        end
        return meminfo(param, pagesize(param))
    end

    -- A disk, made of its parcel (see DiskInfo.createFromParcel): its size rounded to the size it is sold with, so it
    -- agrees with getTotalBytes of a volume on it, which Android rounds of the size of the disk (FileUtils.roundStorageSize),
    -- also of a small disk, like 512 MB, but on the quarterly releases of Android 14 (see storagesize)
    -- shortcut: an app which transacts with the storage service itself and reads the reply parcel by hand gets the
    -- exact size, upgrade by rounding it in the parcel if apps do so.
    if method == 'CREATOR:createFromParcel' then
        local disk = param:getResult()
        if disk == nil then
            return false
        end
        local size = disk.size
        -- A disk whose size is not known has none, which Android leaves
        local fake = size > 0 and storagesize(size) or size
        if fake == size then
            return false
        end
        disk.size = fake
        return true, tostring(size), tostring(fake)
    end

    -- Process.readProcLines of /proc/meminfo, which reads the file natively, its first 2047 bytes on Android 8.1 and 4095
    -- from Android 9: the sizes of the faked file, cut there too, since it is the file the app can read; a line the cut
    -- ends gives the digits before the cut, like the parser at the end of the bytes
    if method == 'readProcLines' then
        local text = param:getException() == nil and param:opens('/proc/meminfo') and fakedmeminfo(param)
        if not text then
            return false
        end
        text = string.sub(text, 1, sdk() < 28 and 2047 or 4095)
        if string.sub(text, -1) ~= '\n' then
            text = text .. '\n'
        end
        local fields, sizes = param:getArgument(1), param:getArgument(2)
        local values, changed = procsizes(text, fields, fields.length), false
        for i = 1, fields.length do
            if sizes[i] ~= values[i] then
                sizes[i] = values[i]
                changed = true
            end
        end
        return changed
    end

    -- Process.readProcFile of /proc/meminfo, which reads the file natively, before Android 10 only its first 255 bytes:
    -- the faked file parsed again by the parser of Android, Process.parseProcLine, with the format and arrays of the app;
    -- the bytes end with a 0, like the read leaves them, where the parser can end the last field.
    -- shortcut: the parser fills only the fields it reaches, so the fields after the end of the faked file, which is
    -- shorter without the lines of makers, keep what the real file gave, and the parser returns false where the real
    -- file gave true; parse in before() when an app reads that far
    if method == 'readProcFile' then
        local text = param:getException() == nil and param:opens('/proc/meminfo') and fakedmeminfo(param)
        if not text then
            return false
        end
        if sdk() < 29 then
            text = string.sub(text, 1, 255)
        end
        param:setResult(luajava.bindClass('android.os.Process'):parseProcLine(text .. '\0', 0, #text,
                param:getArgument(1), param:getArgument(2), param:getArgument(3), param:getArgument(4)))
        return true
    end

    -- Debug.getMemInfo, which reads /proc/meminfo natively, in kB at the indexes of Debug.MEMINFO_* (plus one, LuaJ
    -- counts from 1): the sizes like in the faked file; the available memory is there from Android 14
    if method == 'getMemInfo' then
        local sizes = param:getArgument(0)
        if param:getException() ~= nil or sizes == nil or sizes.length == 0 or sizes[1] <= 0 then
            return false
        end
        local lines = { { 1, 'MemTotal:' }, { 2, 'MemFree:' }, { 9, 'SwapTotal:' }, { 10, 'SwapFree:' } }
        if sdk() >= 34 then
            table.insert(lines, { 20, 'MemAvailable:' })
        end
        local state, changed = { page = pagesize(param) }, false
        for _, line in ipairs(lines) do
            local index = line[1]
            if index <= sizes.length then
                local fake = math.floor(memsize(state, line[2], sizes[index] * 1024) / 1024)
                if fake ~= sizes[index] then
                    sizes[index] = fake
                    changed = true
                end
            end
        end
        return changed
    end

    -- ActivityManager.getMemoryInfo and the binder proxy behind it, which apps reach by reflection; a call of
    -- ActivityManager runs both, and the second one finds the faked total and thresholds, which it keeps, and reads the
    -- free memory of the faked file again. A call which threw, like when the system server died, did not fill the info,
    -- nor did a call into a subclass of the app whose readFromParcel reads nothing, which keeps its total of 0.
    if method == 'getMemoryInfo' then
        local info = param:getArgument(0)
        if info == nil or param:getException() ~= nil or info.totalMem <= 0 then
            return false
        end
        local old = info.totalMem .. ' ' .. info.availMem
        if not memoryparcel(param, info) then
            memoryfields(param, info)
        end
        local new = info.totalMem .. ' ' .. info.availMem
        return old ~= new, old, new
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake
    if method == 'sysconf' then
        -- Os.sysconf, the pages of memory: the total of getMemoryInfo in pages of the device, and the free pages like
        -- bionic counts them (get_avphys_pages), MemFree and Buffers, of the faked /proc/meminfo, so they agree with it,
        -- else rounded like MemFree; the second hook of one call, of Os and ForwardingOs, keeps both. Only these names
        -- are faked.
        local constants = luajava.bindClass('android.system.OsConstants')
        local name = param:getArgument(0)
        if name ~= constants._SC_PHYS_PAGES and name ~= constants._SC_AVPHYS_PAGES then
            return false
        end
        local size = pagesize(param)
        if name == constants._SC_PHYS_PAGES then
            fake = math.floor(memorysize(result * size) / size)
        else
            local text = fakedmeminfo(param)
            if text == nil then
                fake = freememsize(result * size, size) / size
            else
                local sizes = procsizes(text, { 'MemFree:', 'Buffers:' }, 2)
                fake = math.floor((sizes[1] + sizes[2]) * 1024 / size)
            end
        end
    elseif method == 'statvfs' or method == 'fstatvfs' then
        -- Sizes in fragments, like StatFs uses them; a small file system keeps its blocks, its free space is rounded
        -- like by StatFs and File
        local unit = result.f_frsize
        if unit <= 0 then
            return false
        end
        local total = result.f_blocks * unit
        local blocks = math.floor(partitionsize(total) / unit)
        local free = math.floor(spacesize(result.f_bfree * unit, total) / unit)
        local avail = math.floor(spacesize(result.f_bavail * unit, total) / unit)
        if blocks == result.f_blocks and free == result.f_bfree and avail == result.f_bavail then
            return false
        end
        -- The inodes grow with the size: ext4 makes a fixed number per size, so the total would tell the exact size
        local scale = blocks / result.f_blocks
        fake = luajava.newInstance('android.system.StructStatVfs',
                result.f_bsize, result.f_frsize, blocks, free, avail,
                math.floor(result.f_files * scale), math.floor(result.f_ffree * scale), math.floor(result.f_favail * scale),
                result.f_fsid, result.f_flag, result.f_namemax)
        param:setResult(fake)
        return true, tostring(total) .. ' ' .. tostring(result.f_bavail * unit),
                tostring(blocks * unit) .. ' ' .. tostring(avail * unit)
    elseif method == 'getFreeMemory' then
        -- Process.getFreeMemory, which gives -1 when it fails
        if result <= 0 then
            return false
        end
        fake = freememory(param, result)
    elseif method == 'getTotalMemory' then
        -- Process.getTotalMemory, which gives -1 when it fails
        if result <= 0 then
            return false
        end
        fake = memorysize(result)
    elseif method == 'getTotalBytes' and string.find(hook:getName(), '^StorageStatsManager%.') then
        fake = devicesize(param, result, internal(param))
    elseif method == 'getInternalStorageBlockDeviceSize' then
        if result <= 0 then
            return false
        end
        fake = devicesize(param, result, true)
    elseif method == 'getTotalSpace' or method == 'getTotalBytes' then
        fake = partitionsize(result)
    elseif method == 'getBlockCountLong' or method == 'getBlockCount' then
        -- StatFs, in blocks
        local size = param:getThis():getBlockSizeLong()
        fake = math.floor(partitionsize(result * size) / size)
    elseif string.find(method, 'Blocks') then
        -- StatFs getAvailableBlocks(Long), getFreeBlocks(Long)
        local size = param:getThis():getBlockSizeLong()
        fake = math.floor(spacesize(result * size, realtotal(hook, param)) / size)
    else
        -- getFreeSpace, getUsableSpace, getUnallocatedSpace, getFreeBytes, getAvailableBytes and getAllocatableBytes
        fake = spacesize(result, realtotal(hook, param))
    end

    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
