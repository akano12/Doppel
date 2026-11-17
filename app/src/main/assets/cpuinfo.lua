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

-- Every app can read /proc/cpuinfo. Kernels of vendors add lines naming the chip, like Hardware : Qualcomm
-- Technologies, Inc SM8550, which tells the model the other hooks fake, and some a Serial or Revision of the board. The
-- app reads only the lines the kernel of Linux itself prints for each processor (arch/*/kernel of arm, arm64, x86 and
-- riscv): on arm the lines of the core (CPU implementer, variant, part and revision, the revision in the model name and
-- BogoMIPS of the clock) stay with the values of a core many phones have, the Cortex-A53 r0p4 of ARM, or the Cortex-A55
-- r2p0 for the instruction sets of ARMv8.1 and later (atomics), like the little cores of the Snapdragon 888, and on
-- 32-bit kernels, which print CPU architecture 7 for every core, the Cortex-A7 r0p3 of the Snapdragon 200 and 400, each
-- with BogoMIPS 38.40 of the timer of 19.2 MHz of Qualcomm chips, and the board lines those kernels always print with
-- the values of a board which sets none (Hardware : Generic DT based system, Revision 0000 and Serial 0); Features keeps
-- only the instructions the fake core has, in their order, so the newer ones of ARMv9 (sve, i8mm, bf16) go. 32-bit apps
-- read the features of 32-bit code, which never have atomics, so on a device of ARMv8.1 and later they see a Cortex-A53
-- where 64-bit apps see a Cortex-A55; one process sees one core everywhere. The cores of x86 (model and its name, the
-- speed, the caches, bogomips) go. Like Runtime.availableProcessors (see cpu_count) there are 8 processors, the first
-- one repeated, on x86 each one a core of its own; older kernels (3.x) print the lines of all processors in one block,
-- under Processor : AArch64 Processor rev 4 (aarch64) with the revision of the core, which is the one of the fake core.
-- Lines of newer or other kernels are left out until they are known.
-- The uevent file of each processor in /sys/devices/system/cpu, which every app can read, names the same core: its node
-- of the device tree (OF_FULLNAME, the number of the core in the chip), the core (OF_COMPATIBLE, like arm,cortex-x1) and
-- the instructions of the kernel by their bit (MODALIAS), which become those of the fake core (see uevent), and so does
-- /sys/devices/system/cpu/modalias, which the kernel prints by the same function (see modalias).
-- shortcut: on x86 the real vendor_id, cpu family, cpuid level, flags, vmx flags, bugs and address sizes stay, which
-- with the cpu family narrow down the generation of the processor, as do the vendor, family and bits in MODALIAS;
-- on riscv the isa and hart isa stay and uarch, mvendorid, marchid and mimpid, which kernels 6.x always print, go; fake
-- them when a profile of the device gives the core.
-- shortcut: /proc/self/auxv still has the real AT_HWCAP and AT_HWCAP2, the bits of the real Features, since the file
-- is binary and the scripts read and write files as text; rewrite them when XParam gives scripts the bytes of a file.
-- shortcut: the of_node link of each processor still leads to its real node of the device tree, which has the real core
-- in its compatible file and a name uevent follows only for the common layouts, like cpu@700 of DynamIQ; on riscv the
-- node of uevent stays real, the number of the hart; fake them when the open filters resolve links and a profile of the
-- device gives the core.
-- shortcut: an arm64 kernel older than 3.17 prints no MODALIAS, so there the uevent of a 32-bit app names a Cortex-A7
-- while /proc/cpuinfo names a Cortex-A53; tell the kernel by its machine if apps of Android 8.1 on such kernels check.
-- Also by other paths, see XParam.opens, by links of apps themselves to /proc/cpuinfo (see XCore.Filter), and by the
-- descriptor of a folder and a name, like a SecureDirectoryStream opens them (see openedat). Not covered are native
-- code, links of apps themselves to the files of /sys by other names than uevent and modalias and, on Android 8.1 and
-- 9, FileInputStream, which opens natively there (see hardware_files).
local COUNT = 8
local KEPT = {}
for _, key in ipairs({
    -- arm and arm64
    'processor', 'CPU architecture',
    -- x86
    'vendor_id', 'cpu family', 'physical id', 'siblings', 'core id', 'cpu cores', 'apicid', 'initial apicid', 'fpu',
    'fpu_exception', 'cpuid level', 'wp', 'flags', 'vmx flags', 'bugs', 'address sizes', 'power management',
    -- riscv
    'hart', 'isa', 'mmu', 'hart isa' }) do
    KEPT[key] = true
end
-- The cores, with the instructions of each, as the kernels of arm64 and of 32-bit code name them
local A53 = { ['CPU implementer'] = '0x41', ['CPU variant'] = '0x0', ['CPU part'] = '0xd03', ['CPU revision'] = '4',
    BogoMIPS = '38.40' }
local A55 = { ['CPU implementer'] = '0x41', ['CPU variant'] = '0x2', ['CPU part'] = '0xd05', ['CPU revision'] = '0',
    BogoMIPS = '38.40' }
local A7 = { ['CPU implementer'] = '0x41', ['CPU variant'] = '0x0', ['CPU part'] = '0xc07', ['CPU revision'] = '3',
    BogoMIPS = '38.40', Hardware = 'Generic DT based system', Revision = '0000', Serial = '0000000000000000' }
local A53_FEATURES = 'fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid'
local A55_FEATURES = 'fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp'
local A53_32_FEATURES = 'half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt lpae evtstrm aes pmull sha1 sha2 crc32'
local A7_FEATURES = 'swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm'
-- The core in the device tree and the bits of the instructions of the fake cores in the hwcaps of the kernel (uapi
-- asm/hwcap.h of arm64 and arm), which MODALIAS lists
local COMPATIBLE = { [A53] = 'arm,cortex-a53', [A55] = 'arm,cortex-a55', [A7] = 'arm,cortex-a7' }
local BITS64 = { fp = 0, asimd = 1, evtstrm = 2, aes = 3, pmull = 4, sha1 = 5, sha2 = 6, crc32 = 7, atomics = 8,
    fphp = 9, asimdhp = 10, cpuid = 11, asimdrdm = 12, lrcpc = 15, dcpop = 16, asimddp = 20 }
local BITS32 = { swp = 0, half = 1, thumb = 2, fastmult = 4, vfp = 6, edsp = 7, neon = 12, vfpv3 = 13, tls = 15,
    vfpv4 = 16, idiva = 17, idivt = 18, vfpd32 = 19, lpae = 20, evtstrm = 21 }

-- The text with 8 processors: the block of the first one, numbered 0 to 7, like the blocks the kernel prints, on x86
-- each its own core of 8 in one package and on riscv its own hart, and the other lines as they are; text without
-- numbered processors stays
function processors(text)
    -- Older kernels and some of vendors start with the line of the core, before the processors
    local header, rest = string.match(text, '^(Processor%s*:[^\n]*\n)(processor.*)$')
    if header ~= nil then
        return header .. processors(rest)
    end

    -- Older kernels: the lines of all processors in one block, which become the lines of 8 processors
    local _, lines = string.gsub(text, '\nprocessor%s*:%s*%d+', '')
    if string.match(text, '^processor%s*:%s*%d+') then
        lines = lines + 1
    end
    local _, blocked = string.gsub('\n\n' .. text, '\n\nprocessor%s*:%s*%d+', '')
    if lines > blocked then
        if lines == COUNT then
            return text
        end
        local result = {}
        local done = false
        for line in string.gmatch(text, '[^\n]*\n?') do
            local name = string.match(line, '^(processor%s*:%s*)%d+')
            if name == nil then
                result[#result + 1] = line
            elseif not done then
                for index = 0, COUNT - 1 do
                    result[#result + 1] = name .. index .. '\n'
                end
                done = true
            end
        end
        return table.concat(result)
    end

    local blocks = {}
    for block in string.gmatch(text .. '\n\n', '(.-)\n\n+') do
        if block ~= '' then
            blocks[#blocks + 1] = block
        end
    end
    local first
    local others = {}
    for _, block in ipairs(blocks) do
        if string.match(block, '^processor%s*:%s*%d+') then
            first = first or block
        else
            others[#others + 1] = block
        end
    end
    if first == nil then
        return text
    end
    local result = {}
    for index = 0, COUNT - 1 do
        local block = string.gsub(first, '^(processor%s*:%s*)%d+', '%1' .. index)
        for _, key in ipairs({ 'core id', 'apicid', 'initial apicid', 'hart' }) do
            block = string.gsub(block, '\n(' .. key .. '%s*:%s*)%d+', '\n%1' .. index)
        end
        for _, key in ipairs({ 'siblings', 'cpu cores' }) do
            block = string.gsub(block, '\n(' .. key .. '%s*:%s*)%d+', '\n%1' .. COUNT)
        end
        result[#result + 1] = block
    end
    for _, block in ipairs(others) do
        result[#result + 1] = block
    end
    -- The end of the text stays, a single newline after the lines of the board of 32-bit kernels
    return table.concat(result, '\n\n') .. (#others > 0 and '\n' or '\n\n')
end

-- openat has no path the filter could compare, see openedat
function filter(hook)
    if hook:getMethodName() == 'openat' then
        return nil
    end
    return { '*cpuinfo*', '*/uevent*', '*/modalias*' }
end

-- The canonical path of the file the app opened, without the hooks of the app (see cpu_count); nil when it cannot be
-- resolved
function canonical(param)
    local path = tostring(param:getArgument(0))
    local ok, resolved = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):getCanonicalPath()
        end)
    end)
    return ok and resolved or nil
end

-- The path of the file openat, by which a SecureDirectoryStream opens a file by the descriptor of its folder and its
-- name, opened, for a name like the filter of open: the link of the descriptor it returned in /proc/self/fd, whatever
-- folder and name led to it, read without the hooks and without telling StrictMode of a disk read, which openat of
-- java.nio does not report (like openedat of resources); nil for other files
function openedat(param)
    local name = luajava.newInstance('java.lang.String', param:getArgument(1))
    if not (string.find(name, 'cpuinfo', 1, true) or string.find(name, 'uevent', 1, true) or
            string.find(name, 'modalias', 1, true)) then
        return nil
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
    return ok and link or nil
end

-- The fake core of the process for the value of MODALIAS, which the kernel prints by one function in the uevent of each
-- processor and in /sys/devices/system/cpu/modalias, like cpu:type:aarch64:feature:,0000,0001 (nil without it): the
-- Cortex-A55 for 64-bit code with atomics, the Cortex-A7 on 32-bit kernels, of which the type is the one of 32-bit code,
-- like v7l, else the Cortex-A53; the value with only the bits of the instructions of the fake core the real one has, and
-- on x86, which has no device tree, with the model 0, like /proc/cpuinfo has no model, a value of another kind staying;
-- and whether the 64-bit instructions of the kernel have atomics, of ARMv8.1 and later, of which the cores are numbered
-- like DynamIQ, also for a 32-bit app, whose own instructions have no atomics, like the of_node link of each processor
function modalias(value)
    local platform, features = string.match(value or '', '^cpu:type:([^:\n]*):feature:(.*)$')
    local wide = luajava.bindClass('android.os.Process'):is64Bit()
    local atomics = platform == 'aarch64' and string.find(features .. ',', ',0008,', 1, true) ~= nil
    if platform ~= nil and string.find(platform, '^x86') then
        return A53, (string.gsub(value, 'mod%x%x%x%x', 'mod0000', 1)), false
    end
    local core, names, bits = A53, A53_FEATURES, BITS64
    if (platform ~= nil and platform ~= 'aarch64') or (platform == nil and not wide) then
        core, names, bits = A7, A7_FEATURES, BITS32
    elseif wide and atomics then
        core, names = A55, A55_FEATURES
    end
    if platform == nil or (platform ~= 'aarch64' and not string.find(platform, '^v%d')) then
        return core, value, false
    end
    local has = {}
    for name in string.gmatch(names, '%S+') do
        has[bits[name]] = true
    end
    return core, (string.gsub(value, ',(%x%x%x%x)', function(bit)
        return has[tonumber(bit, 16)] and ',' .. bit or ''
    end)), atomics
end

-- The uevent of a processor with the node and core of the fake core, like /proc/cpuinfo has it for the process (see
-- modalias). The node is numbered like the cores of DynamIQ (cpu@0 to cpu@700) on kernels of ARMv8.1 and later and in
-- clusters of 4 (cpu@0 to cpu@3, cpu@100 to cpu@103) on others. riscv, whose cpu nodes are all compatible with riscv,
-- is only that, like the processors of QEMU, since /proc/cpuinfo names no core there. The other lines, like OF_NAME and
-- OF_TYPE, stay.
function uevent(param, path)
    local cpu = tonumber(string.match(path or '', '^/sys/devices/system/cpu/cpu(%d+)/uevent$'))
    if cpu == nil then
        return false
    end
    local real = param:getFileText()
    local riscv = string.find('\n' .. real, '\nOF_COMPATIBLE_%d+=riscv\n') ~= nil
    local core, value, atomics = modalias(string.match('\n' .. real, '\nMODALIAS=([^\n]*)'))
    local node = atomics and cpu * 256 or math.floor(cpu / 4) * 256 + cpu % 4
    local result = {}
    for line in string.gmatch(real, '[^\n]*\n?') do
        local key = string.match(line, '^([%w_]+)=')
        local ending = string.match(line, '\n?$')
        if key == 'OF_FULLNAME' and not riscv then
            line = string.format('OF_FULLNAME=/cpus/cpu@%x', node) .. ending
        elseif key == 'OF_COMPATIBLE_0' then
            line = 'OF_COMPATIBLE_0=' .. (riscv and 'riscv' or COMPATIBLE[core]) .. ending
        elseif key == 'OF_COMPATIBLE_N' then
            line = 'OF_COMPATIBLE_N=1' .. ending
        elseif key ~= nil and (string.find(key, '^OF_COMPATIBLE_%d+$') or string.find(key, '^OF_ALIAS_')) then
            line = ''
        elseif key == 'MODALIAS' and not riscv then
            line = 'MODALIAS=' .. value .. ending
        end
        result[#result + 1] = line
    end
    local text = table.concat(result)
    if text == real then
        return false
    end
    param:setFileText(text)
    return true
end

-- /sys/devices/system/cpu/modalias, the same text as MODALIAS of the uevent of each processor (see modalias)
function cpumodalias(param)
    local real = param:getFileText()
    local value, ending = string.match(real, '^([^\n]*)(.*)$')
    local _, faked = modalias(value)
    local text = faked .. ending
    if text == real then
        return false
    end
    param:setFileText(text)
    return true
end

-- The text without the lines of unknown keys, the lines of the cores of arm with the values of the fake core and the
-- features it has; the empty lines between the processors stay
function kept(text)
    local features = string.match('\n' .. text, '\nFeatures%s*:([^\n]*)') or ''
    local core, names = A53, A53_FEATURES
    if string.match('\n' .. text, '\nCPU architecture%s*:%s*(%w+)') == '7' then
        core, names = A7, A7_FEATURES
    elseif string.find(features .. ' ', ' atomics ', 1, true) then
        core, names = A55, A55_FEATURES
    elseif string.find(features .. ' ', ' thumb ', 1, true) then
        names = A53_32_FEATURES
    end
    local has = {}
    for name in string.gmatch(names, '%S+') do
        has[name] = true
    end
    local result = {}
    local left = 0
    for line in string.gmatch(text, '[^\n]*\n?') do
        local key = string.match(line, '^([^:]-)%s*:')
        if string.match(line, '^%s*$') or (key ~= nil and KEPT[key]) then
            result[#result + 1] = line
        elseif key == 'Features' then
            local name, value = string.match(line, '^([^:]*:)([^\n]*)')
            value = string.gsub(value, '(%s*)(%S+)', function(space, feature)
                return has[feature] and space .. feature or ''
            end)
            result[#result + 1] = name .. value .. string.match(line, '\n?$')
        elseif key ~= nil and core[key] ~= nil then
            result[#result + 1] = string.match(line, '^[^:]*: ?') .. core[key] .. string.match(line, '\n?$')
        elseif (key == 'Processor' or key == 'model name') and
                string.match(line, '^[^:]*:%s*%u%w+ Processor rev %d+ %(%w+%)\n?$') then
            result[#result + 1] = (string.gsub(line, 'rev %d+', 'rev ' .. core['CPU revision'], 1))
        elseif line ~= '' then
            left = left + 1
        end
    end
    return table.concat(result), left
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    local path
    if hook:getMethodName() == 'openat' then
        path = openedat(param)
    elseif param:opens('/proc/cpuinfo') then
        path = '/proc/cpuinfo'
    elseif string.find(tostring(param:getArgument(0)), '/uevent$') or
            string.find(tostring(param:getArgument(0)), '/modalias$') then
        path = canonical(param)
    end
    if path == '/sys/devices/system/cpu/modalias' then
        return cpumodalias(param)
    elseif path ~= '/proc/cpuinfo' then
        return uevent(param, path)
    end
    local real = param:getFileText()
    local text, left = kept(real)
    text = processors(text)
    if text == real then
        return false
    end
    param:setFileText(text)
    return true, tostring(left)
end
