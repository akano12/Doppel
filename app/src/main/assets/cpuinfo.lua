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
-- (arch/*/kernel of arm, arm64, x86 and riscv), which libraries pick their code by, like Features and CPU part;
-- lines of newer or other kernels are left out until they are known. Also by other paths, see XParam.opens.
-- Not covered are native code and links of apps themselves to the file.
local KEPT = {}
for _, key in ipairs({
    -- arm and arm64
    'processor', 'Processor', 'BogoMIPS', 'Features', 'CPU implementer', 'CPU architecture', 'CPU variant',
    'CPU part', 'CPU revision', 'model name',
    -- x86
    'vendor_id', 'cpu family', 'model', 'stepping', 'microcode', 'cpu MHz', 'cache size', 'physical id',
    'siblings', 'core id', 'cpu cores', 'apicid', 'initial apicid', 'fpu', 'fpu_exception', 'cpuid level', 'wp',
    'flags', 'vmx flags', 'bugs', 'bogomips', 'TLB size', 'clflush size', 'cache_alignment', 'address sizes',
    'power management',
    -- riscv
    'hart', 'isa', 'mmu', 'uarch', 'mvendorid', 'marchid', 'mimpid', 'hart isa' }) do
    KEPT[key] = true
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
    if left == 0 then
        return false
    end
    param:setFileText(text)
    return true, tostring(left)
end
