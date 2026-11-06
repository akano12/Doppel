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
-- like /proc/self/root/proc/meminfo, and by java.nio (UnixNativeDispatcher.open), see XParam.opens.
-- Not covered are links of apps themselves to the file, which have paths without meminfo.
function filter(hook)
    if hook:getMethodName() == 'open' then
        return { '*meminfo*' }
    end
    return nil
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

-- The sizes of /proc/meminfo like the ones of ActivityManager.getMemoryInfo, which come from this file:
-- the total memory rounded up, the free and available memory down, in the format of the kernel
function meminfo(param)
    local changed = false
    local old, new = {}, {}
    local lines = {}
    local scale = 1 -- Of the total, which is the first line
    for line in string.gmatch(param:getFileText(), '[^\n]*\n') do
        if not MEMINFO[string.match(line, '^[^:]*:')] then
            changed = true
            line = ''
        end
        local name, value = string.match(line, '^(%w+:)%s*(%d+) kB\n$')
        -- SwapTotal, a share of the memory with zram, and CommitLimit, half of it plus SwapTotal, tell the total,
        -- so they grow with it, like on a device with the faked total
        if name == 'MemTotal:' or name == 'MemFree:' or name == 'MemAvailable:' or
                name == 'SwapTotal:' or name == 'CommitLimit:' then
            local size = tonumber(value) * 1024
            local fake
            if name == 'MemTotal:' then
                fake = memorysize(size)
                scale = fake / size
            elseif name == 'SwapTotal:' or name == 'CommitLimit:' then
                fake = math.floor(size * scale / 1024) * 1024
            else
                fake = freesize(size)
            end
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

function after(hook, param)
    local method = hook:getMethodName()

    if method == 'open' then
        if param:getException() ~= nil or param:getResult() == nil or not param:opens('/proc/meminfo') then
            return false
        end
        return meminfo(param)
    end

    if method == 'getMemoryInfo' then
        local info = param:getArgument(0)
        if info == nil then
            return false
        end
        local old = info.totalMem .. ' ' .. info.availMem
        -- The low memory thresholds are shares of the total, so they grow with it, like SwapTotal;
        -- the hidden ones are nil when the runtime does not show them
        local total = info.totalMem
        local fake = memorysize(total)
        if total > 0 then
            for _, name in ipairs({ 'threshold', 'hiddenAppThreshold', 'secondaryServerThreshold',
                                    'visibleAppThreshold', 'foregroundAppThreshold' }) do
                if info[name] ~= nil then
                    info[name] = math.floor(info[name] * (fake / total))
                end
            end
        end
        info.totalMem = fake
        info.availMem = freesize(info.availMem)
        -- The size the device is sold with, Android 14 and later: not smaller than the total, like on a device
        if info.advertisedMem ~= nil and info.advertisedMem > 0 then
            info.advertisedMem = fake
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
        -- getFreeSpace, getUsableSpace, getFreeBytes, getAvailableBytes
        fake = freesize(result)
    end

    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
