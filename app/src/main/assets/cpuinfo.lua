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

-- Every app can read /proc/cpuinfo. Kernels of vendors add lines naming the chip, like
-- Hardware : Qualcomm Technologies, Inc SM8550, which tells the model the other hooks fake, and some a Serial
-- or Revision of the board. The app reads only the lines the kernel of Linux itself prints for each processor
-- (arch/*/kernel of arm, arm64, x86 and riscv) which do not tell the cores: the instruction sets (Features, flags,
-- isa) and the architecture stay; the cores (CPU part, implementer, variant and revision, model and its name, the
-- speed, the caches, BogoMIPS of the clock) go. Like Runtime.availableProcessors (see cpu_count) there are 8
-- processors, the first one repeated; older kernels (3.x) print the lines of all processors in one block, under
-- Processor : AArch64 Processor rev 4 (aarch64) with the revision of the core, which goes too.
-- Lines of newer or other kernels are left out until they are known.
-- Also by other paths, see XParam.opens. Not covered are native code and links of apps themselves to the file.
local COUNT = 8
local KEPT = {}
for _, key in ipairs({
    -- arm and arm64
    'processor', 'Features', 'CPU architecture',
    -- x86
    'vendor_id', 'cpu family', 'physical id', 'siblings', 'core id', 'cpu cores', 'apicid', 'initial apicid', 'fpu',
    'fpu_exception', 'cpuid level', 'wp', 'flags', 'vmx flags', 'bugs', 'address sizes', 'power management',
    -- riscv
    'hart', 'isa', 'mmu', 'hart isa' }) do
    KEPT[key] = true
end

-- The text with 8 processors: the block of the first one, numbered 0 to 7, like the blocks the kernel prints, and
-- the other lines as they are; text without numbered processors stays
function processors(text)
    -- Older kernels: the lines of all processors in one block, which become the lines of 8 processors
    local _, lines = string.gsub(text, '\nprocessor%s*:%s*%d+', '')
    if string.match(text, '^processor%s*:%s*%d+') then
        lines = lines + 1
    end
    local _, blocked = string.gsub('\n\n' .. text, '\n\nprocessor%s*:%s*%d+', '')
    if lines > blocked then
        if lines == COUNT then
            return text, false
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
        return table.concat(result), true
    end

    local blocks = {}
    for block in string.gmatch(text .. '\n\n', '(.-)\n\n+') do
        if block ~= '' then
            blocks[#blocks + 1] = block
        end
    end
    local first
    local others = {}
    local count = 0
    for _, block in ipairs(blocks) do
        if string.match(block, '^processor%s*:%s*%d+') then
            count = count + 1
            first = first or block
        else
            others[#others + 1] = block
        end
    end
    if first == nil or count == COUNT then
        return text, false
    end
    local result = {}
    for index = 0, COUNT - 1 do
        result[#result + 1] = (string.gsub(first, '^(processor%s*:%s*)%d+', '%1' .. index))
    end
    for _, block in ipairs(others) do
        result[#result + 1] = block
    end
    return table.concat(result, '\n\n') .. '\n\n', true
end

function filter(hook)
    return { '*cpuinfo*' }
end

-- The text without the lines of unknown keys; the empty lines between the processors stay
function kept(text)
    local result = {}
    local left = 0
    for line in string.gmatch(text, '[^\n]*\n?') do
        local key = string.match(line, '^([^:]-)%s*:')
        if string.match(line, '^%s*$') or (key ~= nil and KEPT[key]) then
            result[#result + 1] = line
        elseif line ~= '' then
            left = left + 1
        end
    end
    return table.concat(result), left
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil or not param:opens('/proc/cpuinfo') then
        return false
    end
    local text, left = kept(param:getFileText())
    local counted
    text, counted = processors(text)
    if left == 0 and not counted then
        return false
    end
    param:setFileText(text)
    return true, tostring(left)
end
