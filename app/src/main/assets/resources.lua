-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

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

function after(hook, param)
    local method = hook:getMethodName()

    if method == 'getMemoryInfo' then
        local info = param:getArgument(0)
        if info == nil then
            return false
        end
        local old = info.totalMem .. ' ' .. info.availMem
        info.totalMem = memorysize(info.totalMem)
        info.availMem = freesize(info.availMem)
        local new = info.totalMem .. ' ' .. info.availMem
        return old ~= new, old, new
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake
    if method == 'statvfs' or method == 'fstatvfs' then
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
        fake = luajava.newInstance('android.system.StructStatVfs',
                result.f_bsize, result.f_frsize, blocks, free, avail,
                result.f_files, result.f_ffree, result.f_favail, result.f_fsid, result.f_flag, result.f_namemax)
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
