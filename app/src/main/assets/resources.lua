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
-- caches and before writing files, the sizes are rounded: totals up to the sizes devices are sold with,
-- free space down to two digits, so apps never think there is more free space than there is.
-- Small file systems keep their exact sizes. StatFs is hooked itself, because the framework,
-- which is compiled ahead of time, calls Os.statvfs inlined, so its hook only applies to apps.

local GB = 1000000000
local GIB = 1073741824

-- Memory sizes devices are sold with, in GiB
local MEMORY = { 1, 1.5, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32, 48, 64 }

function memorysize(value)
    for _, size in ipairs(MEMORY) do
        if value <= size * GIB then
            return size * GIB
        end
    end
    return value
end

-- Storage sizes devices are sold with, powers of two GB
function storagesize(value)
    if value < GB then
        return value
    end
    local size = GB
    while size < value do
        size = size * 2
    end
    return size
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

-- Opening files, of which only /proc/meminfo has the sizes, see XCore.getFilter; also by other paths,
-- like /proc/self/root/proc/meminfo, by java.nio (UnixNativeDispatcher.open), see XParam.opens, and by
-- Process.readProcLines and readProcFile, which the framework calls for other files of /proc too.
-- Not covered are links of apps themselves to the file, which have paths without meminfo. openat has no path the filter
-- could compare, see openedat.
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
-- state keeps what MemTotal and MemFree, which come first, changed, for the lines after them
function memsize(state, name, size)
    if name == 'MemTotal:' then
        local fake = memorysize(size)
        state.scale, state.more = fake / size, fake - size
        return fake
    elseif name == 'MemFree:' or name == 'MemAvailable:' then
        local fake = freesize(size)
        if name == 'MemFree:' then
            -- What the line lost, in the whole kB of the line
            state.less = size - math.floor(fake / 1024) * 1024
        end
        return fake
    elseif name == 'SwapTotal:' or name == 'CommitLimit:' then
        -- SwapTotal, a share of the memory with zram, and CommitLimit, half of it plus SwapTotal, tell the total,
        -- so they grow with it, like on a device with the faked total
        return math.floor(size * (state.scale or 1) / 1024) * 1024
    elseif name == 'HighTotal:' then
        -- 32-bit kernels with high memory: HighTotal and LowTotal add up to MemTotal, HighFree and LowFree to MemFree.
        -- The low memory is what the kernel keeps mapped, which its address space limits, so on a device with more
        -- memory only the high memory is larger; the free memory lost is taken from the free high memory first
        return size + (state.more or 0)
    elseif name == 'HighFree:' or name == 'LowFree:' then
        local taken = math.min(size, state.less or 0)
        state.less = (state.less or 0) - taken
        return size - taken
    end
    return nil
end

-- The sizes of /proc/meminfo like the ones of ActivityManager.getMemoryInfo, which come from this file:
-- the total memory rounded up, the free and available memory down, in the format of the kernel
function meminfo(param)
    local changed = false
    local old, new = {}, {}
    local lines = {}
    local state = {}
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
    meminfo(file)
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
-- MemAvailable from Android 16, here of the faked file, so they agree with it; the real size rounded down when the file
-- cannot be read
function freememory(param, real)
    local text = fakedmeminfo(param)
    if text == nil then
        return freesize(real)
    end
    local fields = sdk() >= 36 and { 'MemAvailable:' } or { 'MemFree:', 'Cached:' }
    local sum = 0
    for _, size in ipairs(procsizes(text, fields, #fields)) do
        sum = sum + size
    end
    return sum * 1024
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
    parcel:readInt()
    -- Of the cached apps, the secondary server, the visible and the foreground app
    local thresholds = { parcel:readLong(), parcel:readLong(), parcel:readLong(), parcel:readLong() }
    -- The low memory thresholds are shares of the total, so they grow with it, like SwapTotal
    local fake = memorysize(total)
    home = math.floor(home * (fake / total))
    for i = 1, 4 do
        thresholds[i] = math.floor(thresholds[i] * (fake / total))
    end
    avail = freememory(param, avail)
    parcel:setDataPosition(0)
    if advertised then
        -- The size the device is sold with: not smaller than the total, like on a device
        parcel:writeLong(advertised > 0 and fake or advertised)
    end
    parcel:writeLong(avail)
    parcel:writeLong(fake)
    parcel:writeLong(home)
    parcel:writeInt(lowmemory(avail, home, thresholds[1]) and 1 or 0)
    for i = 1, 4 do
        parcel:writeLong(thresholds[i])
    end
    parcel:setDataPosition(0)
    info:readFromParcel(parcel)
    parcel:recycle()
    return true
end

-- MemoryInfo faked through its fields, for a subclass of the app and a parcel of a maker; the hidden thresholds are nil
-- when the runtime does not show them, and then low memory is at least below the threshold of the home app, which
-- Android gives the cached apps too (ProcessList.getMemLevel), so it is the middle.
-- shortcut: when the runtime hides the thresholds of the cached apps, the secondary server and the visible apps, they
-- stay real in the parcel of such an info, and low memory follows the threshold of the home app, which differs from the
-- middle only where a maker changed the levels; read them by reflection past the hidden API rules when apps are seen
-- to subclass the info
function memoryfields(param, info)
    local total = info.totalMem
    local fake = memorysize(total)
    for _, name in ipairs({ 'threshold', 'hiddenAppThreshold', 'secondaryServerThreshold',
                            'visibleAppThreshold', 'foregroundAppThreshold' }) do
        if info[name] ~= nil then
            info[name] = math.floor(info[name] * (fake / total))
        end
    end
    info.totalMem = fake
    info.availMem = freememory(param, info.availMem)
    -- The size the device is sold with, Android 14 and later: not smaller than the total, like on a device
    if info.advertisedMem ~= nil and info.advertisedMem > 0 then
        info.advertisedMem = fake
    end
    if info.hiddenAppThreshold ~= nil then
        info.lowMemory = lowmemory(info.availMem, info.threshold, info.hiddenAppThreshold)
    else
        info.lowMemory = info.lowMemory or info.availMem < info.threshold
    end
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
        return meminfo(param)
    end

    -- A disk, made of its parcel (see DiskInfo.createFromParcel): its size rounded like the other totals, so it agrees
    -- with getTotalBytes of a volume on it, which Android rounds of the size of the disk (FileUtils.roundStorageSize)
    -- shortcut: an app which transacts with the storage service itself and reads the reply parcel by hand gets the
    -- exact size, upgrade by rounding it in the parcel if apps do so.
    if method == 'CREATOR:createFromParcel' then
        local disk = param:getResult()
        if disk == nil then
            return false
        end
        local size = disk.size
        local fake = storagesize(size)
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
        local lines = { { 1, 'MemTotal:' }, { 2, 'MemFree:' }, { 9, 'SwapTotal:' } }
        if sdk() >= 34 then
            table.insert(lines, { 20, 'MemAvailable:' })
        end
        local state, changed = {}, false
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
        -- Os.sysconf, the pages of memory: the sizes of getMemoryInfo in pages of the device. Only these names are
        -- rounded; the free pages are rounded up to a page, or the second hook of one call, of Os and ForwardingOs,
        -- would round them down once more
        local constants = luajava.bindClass('android.system.OsConstants')
        local name = param:getArgument(0)
        if name ~= constants._SC_PHYS_PAGES and name ~= constants._SC_AVPHYS_PAGES then
            return false
        end
        local size = param:unhooked(function()
            return luajava.bindClass('android.system.Os'):sysconf(constants._SC_PAGESIZE)
        end)
        if name == constants._SC_PHYS_PAGES then
            fake = math.floor(memorysize(result * size) / size)
        else
            fake = math.ceil(freesize(result * size) / size)
        end
    elseif method == 'statvfs' or method == 'fstatvfs' then
        -- Sizes in fragments, like StatFs uses them
        local unit = result.f_frsize
        if unit <= 0 or result.f_blocks * unit < GB then
            return false
        end
        local blocks = math.floor(storagesize(result.f_blocks * unit) / unit)
        local free = math.floor(freesize(result.f_bfree * unit) / unit)
        local avail = math.floor(freesize(result.f_bavail * unit) / unit)
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
        return true, tostring(result.f_blocks * unit) .. ' ' .. tostring(result.f_bavail * unit),
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
    elseif method == 'getTotalSpace' or method == 'getTotalBytes' then
        fake = storagesize(result)
    elseif method == 'getBlockCountLong' or method == 'getBlockCount' then
        -- StatFs, in blocks
        local size = param:getThis():getBlockSizeLong()
        fake = math.floor(storagesize(result * size) / size)
    elseif string.find(method, 'Blocks') then
        -- StatFs getAvailableBlocks(Long), getFreeBlocks(Long)
        local size = param:getThis():getBlockSizeLong()
        fake = math.floor(freesize(result * size) / size)
    else
        -- getFreeSpace, getUsableSpace, getFreeBytes and getAvailableBytes
        fake = freesize(result)
    end

    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
