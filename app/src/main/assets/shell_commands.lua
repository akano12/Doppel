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

-- Apps run commands to read what the other hooks fake, like FingerprintJS runs getprop:
-- getprop reads the system properties behind the fields of android.os.Build, the serial and the operator,
-- uname and /proc/version the kernel build of Os.uname. These commands print the same fake values:
-- a single faked property is printed right away, other commands run in a shell with functions
-- of the same names in front, which run the real command and replace the values, so the output keeps
-- its format. Commands without them are not touched. Runtime.exec starts processes with
-- ProcessBuilder.start, so one hook covers both. Shells reading commands from their input are not
-- covered, filtering their input could break apps which wait for output.

local SH = '/system/bin/sh'

-- Shell patterns of property names and their fake values, like the fields of android.os.Build
-- and the hooks of SystemProperties.get
function rules(param)
    local serial = param:getSetting('value.serial')
    if serial == nil then
        serial = 'unknown'
    end
    return {
        { serial, { 'ro.serialno', 'ro.boot.serialno', 'ril.serialnumber', 'ro.boot.ap_serial', 'sys.serialnumber',
                    'ro.ril.oem.sno', 'ro.ril.oem.psno', 'gsm.serial' } },
        { 'unknown', { 'ro.product.model', 'ro.product.*.model', 'ro.product.brand', 'ro.product.*.brand',
                       'ro.product.manufacturer', 'ro.product.*.manufacturer', 'ro.product.device',
                       'ro.product.*.device', 'ro.product.name', 'ro.product.*.name', 'ro.product.board',
                       'ro.product.*.board', 'ro.soc.model', 'ro.soc.manufacturer', 'ro.hardware',
                       'ro.boot.hardware.sku', 'ro.boot.product.hardware.sku', 'ro.boot.vbmeta.digest',
                       'ro.bootloader', 'ro.boot.bootloader', 'gsm.version.baseband' } },
        { 'unknown', { 'ro.build.fingerprint', 'ro.*.build.fingerprint', 'ro.build.id', 'ro.*.build.id',
                       'ro.build.display.id', 'ro.build.version.incremental', 'ro.*.build.version.incremental',
                       'ro.build.version.base_os', 'ro.build.host', 'ro.build.user', 'ro.build.tags',
                       'ro.*.build.tags', 'ro.build.type', 'ro.*.build.type', 'ro.build.description',
                       'ro.build.flavor', 'ro.build.product', 'ro.build.date', 'ro.*.build.date' } },
        { '0', { 'ro.build.date.utc', 'ro.*.build.date.utc' } },
        { 'unknown', { 'gsm.operator.alpha', 'gsm.sim.operator.alpha' } },
        { '00101', { 'gsm.operator.numeric', 'gsm.sim.operator.numeric' } }, -- test network
        { 'xx', { 'gsm.operator.iso-country', 'gsm.sim.operator.iso-country' } },
    }
end

-- The fake value of a property, nil when it is not faked
function fakeproperty(param, key)
    for _, rule in ipairs(rules(param)) do
        for _, pattern in ipairs(rule[2]) do
            local lua = '^' .. string.gsub(pattern, '[%.%-%*]', { ['.'] = '%.', ['-'] = '%-', ['*'] = '.*' }) .. '$'
            if string.match(key, lua) then
                return rule[1]
            end
        end
    end
    return nil
end

-- A literal for the shell
function quote(value)
    return "'" .. (string.gsub(value, "'", "'\\''")) .. "'"
end

-- A sed command replacing the value of matching lines of getprop like [ro.product.model]: [Pixel 9]
function sedcommand(pattern, value)
    local regex = string.gsub(pattern, '[%.%*]', { ['.'] = '\\.', ['*'] = '[^]]*' })
    local replacement = string.gsub(value, '[\\&/\n]', { ['\\'] = '\\\\', ['&'] = '\\&', ['/'] = '\\/', ['\n'] = ' ' })
    return 's/^\\[\\(' .. regex .. '\\)\\]: \\[.*\\]$/[\\1]: [' .. replacement .. ']/'
end

-- getprop without arguments lists all properties, with a name prints its value
function getpropfunction(param)
    local cases = {}
    local seds = {}
    for _, rule in ipairs(rules(param)) do
        cases[#cases + 1] = '  ' .. table.concat(rule[2], '|') .. ') __xlua_value=' .. quote(rule[1]) .. ' ;;'
        for _, pattern in ipairs(rule[2]) do
            seds[#seds + 1] = '-e ' .. quote(sedcommand(pattern, rule[1]))
        end
    end
    return table.concat({
        '__xlua_property() {',
        ' case "$1" in',
        table.concat(cases, '\n'),
        '  *) return 1 ;;',
        ' esac',
        '}',
        'getprop() {',
        ' if [ $# -eq 0 ]; then',
        '  /system/bin/getprop | /system/bin/sed ' .. table.concat(seds, ' '),
        ' elif [ $# -le 2 ] && [ "${1#-}" = "$1" ] && __xlua_property "$1"; then',
        "  /system/bin/printf '%s\\n' \"$__xlua_value\"",
        ' else',
        '  /system/bin/getprop "$@"',
        ' fi',
        '}',
        ''
    }, '\n')
end

-- The kernel release is shortened to its version number, the build to #1 SMP PREEMPT and the host name
-- to localhost, like Os.uname; /proc/version contains the same and the compiler, which is left out.
-- The architecture stays, apps select native code with it.
function kernelfunctions()
    return table.concat({
        '__xlua_kernel() {',
        ' __xlua_release=$(/system/bin/uname -r)',
        ' __xlua_short=${__xlua_release%%[!0-9.]*}',
        ' [ -n "$__xlua_short" ] || __xlua_short=unknown',
        '}',
        -- A basic regular expression matching the text exactly
        '__xlua_regex() {',
        " /system/bin/printf '%s' \"$1\" | /system/bin/sed -e 's/[]|$*.^\\\\[]/\\\\&/g'",
        '}',
        'uname() {',
        ' __xlua_kernel',
        ' /system/bin/uname "$@" | /system/bin/sed' ..
                ' -e "s|$(__xlua_regex "$(/system/bin/uname -v)")|#1 SMP PREEMPT|"' ..
                ' -e "s|$(__xlua_regex "$__xlua_release")|$__xlua_short|"' ..
                ' -e "s|^\\([^ ]* \\)$(__xlua_regex "$(/system/bin/uname -n)") |\\1localhost |"' ..
                ' -e "s|^$(__xlua_regex "$(/system/bin/uname -n)")\\$|localhost|"',
        '}',
        -- Apps cannot read /proc/version on recent versions, then the error stays
        'cat() {',
        ' if [ $# -eq 1 ] && [ "$1" = /proc/version ] && /system/bin/cat /proc/version >/dev/null 2>&1; then',
        '  __xlua_kernel',
        "  /system/bin/printf 'Linux version %s #1 SMP PREEMPT\\n' \"$__xlua_short\"",
        ' else',
        '  /system/bin/cat "$@"',
        ' fi',
        '}',
        ''
    }, '\n')
end

-- The shell functions for the commands in the text and the names of the functions, nil when there are none
function functions(param, text)
    local result = ''
    local names = {}
    if string.find(text, 'getprop', 1, true) then
        result = result .. getpropfunction(param)
        names[#names + 1] = 'getprop'
    end
    if string.find(text, 'uname', 1, true) or string.find(text, '/proc/version', 1, true) then
        result = result .. kernelfunctions()
        names[#names + 1] = 'uname'
        names[#names + 1] = 'cat'
    end
    if result == '' then
        return nil
    end
    return result, names
end

function basename(path)
    return string.match(path, '[^/]*$')
end

function before(hook, param)
    local builder = param:getThis()
    local command = builder:command()
    if command == nil or command:isEmpty() then
        return false
    end

    local args = command:toArray()
    local words = {}
    for index = 1, args['length'] do
        if args[index] == nil then
            return false
        end
        words[index] = args[index]
    end
    local line = table.concat(words, ' ')
    local prelude, names = functions(param, line)
    if prelude == nil then
        return false
    end

    local program = basename(words[1])
    local fake = luajava.newInstance('java.util.ArrayList')
    if program == 'getprop' and (#words == 2 or #words == 3) and string.sub(words[2], 1, 1) ~= '-' then
        -- getprop name [default]: a faked value is printed without a shell, other properties stay
        local value = fakeproperty(param, words[2])
        if value == nil then
            return false
        end
        fake:add('/system/bin/printf')
        fake:add('%s\n')
        fake:add(value)
    elseif program == 'getprop' or program == 'uname' or
            (program == 'cat' and #words == 2 and words[2] == '/proc/version') then
        -- getprop -> sh -c '<functions> getprop "$@"' getprop
        fake:add(SH)
        fake:add('-c')
        fake:add(prelude .. program .. ' "$@"')
        for index = 1, #words do
            fake:add(index == 1 and program or words[index])
        end
    elseif (program == 'sh' or program == 'mksh' or program == 'bash') and #words >= 3 and words[2] == '-c' then
        -- Scripts can run the commands with their path, which would skip the functions
        local script = words[3]
        for _, name in ipairs(names) do
            script = string.gsub(script, '/system/bin/' .. name, name)
        end
        for index = 1, #words do
            fake:add(index == 3 and (prelude .. script) or words[index])
        end
    else
        return false
    end

    -- The builder gets its own command back after the start, apps can start it again
    param:putValue('shell.command', command, builder)
    builder:command(fake)
    return true, line
end

function after(hook, param)
    local builder = param:getThis()
    local command = param:getValue('shell.command', builder)
    if command ~= nil then
        builder:command(command)
        param:putValue('shell.command', nil, builder)
    end
    return false
end
