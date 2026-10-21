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
-- getprop reads the system properties behind the fields of android.os.Build, the serial, the operator
-- and the time zone, uname and /proc/version the kernel build of Os.uname, pm the installed packages
-- and the features of the device. These commands print the same fake values: they run in a shell with
-- functions of the same names in front, which run the real command and replace the values, so the output
-- keeps its format. Commands without them are not touched. Runtime.exec starts processes with
-- ProcessBuilder.start, so one hook covers both. Shells reading commands from their input are not
-- covered, filtering their input could break apps which wait for output.
-- Each variant fakes the values of its group. The hooks of all variants run for the same command:
-- each one adds its variant to the variants of the command so far and makes the command again from
-- the original one, so the last one makes the command with the functions of all variants.

local SH = '/system/bin/sh'

-- Shell patterns of property names and their fake values for a variant, like the fields of
-- android.os.Build and the hooks of SystemProperties.get (systemproperties_get has the same device properties)
function rules(param, variant)
    if variant == 'timezone' then
        local zone = param:getSetting('value.timezone')
        if zone == nil or zone == '' then
            zone = 'UTC'
        end
        return { { zone, { 'persist.sys.timezone' } } }
    end
    if variant ~= 'tracking' then
        return {}
    end

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
                       'ro.build.flavor', 'ro.build.product', 'ro.build.date', 'ro.*.build.date',
                       'ro.build.version.security_patch', 'ro.*.build.version.security_patch',
                       'ro.*.build.security_patch' } },
        { '0', { 'ro.build.date.utc', 'ro.*.build.date.utc' } },
        { 'unknown', { 'gsm.operator.alpha', 'gsm.sim.operator.alpha' } },
        { '00101', { 'gsm.operator.numeric', 'gsm.sim.operator.numeric' } }, -- test network
        { 'xx', { 'gsm.operator.iso-country', 'gsm.sim.operator.iso-country' } },
    }
end

-- A literal for the shell
function quote(value)
    return "'" .. (string.gsub(value, "'", "'\\''")) .. "'"
end

-- A sed command replacing the value of matching lines of getprop like [ro.product.model]: [Pixel 9];
-- empty values stay, like SystemProperties.get keeps them
function sedcommand(pattern, value)
    local regex = string.gsub(pattern, '[%.%*]', { ['.'] = '\\.', ['*'] = '[^]]*' })
    local replacement = string.gsub(value, '[\\&/\n]', { ['\\'] = '\\\\', ['&'] = '\\&', ['/'] = '\\/', ['\n'] = ' ' })
    return 's/^\\[\\(' .. regex .. '\\)\\]: \\[..*\\]$/[\\1]: [' .. replacement .. ']/'
end

-- getprop without arguments lists all properties, with a name prints its value
function getpropfunction(rules)
    local cases = {}
    local seds = {}
    for _, rule in ipairs(rules) do
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
        ' elif [ $# -le 2 ] && [ "${1#-}" = "$1" ] && __xlua_property "$1" &&',
        '   [ -n "$(/system/bin/getprop "$1")" ]; then',
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
        ' __xlua_node=$(__xlua_regex "$(/system/bin/uname -n)")',
        ' /system/bin/uname "$@" | /system/bin/sed' ..
                ' -e "s|$(__xlua_regex "$(/system/bin/uname -v)")|#1 SMP PREEMPT|"' ..
                ' -e "s|$(__xlua_regex "$__xlua_release")|$__xlua_short|"' ..
                ' -e "s|^\\([^ ]* \\)$__xlua_node |\\1localhost |"' ..
                ' -e "s|^$__xlua_node\\$|localhost|"',
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

-- A basic regular expression matching the text exactly
function regex(text)
    return (string.gsub(text, '[%^%$%.%*%[%]\\/]', '\\%0'))
end

-- The packages of the app
function ownpackages(param)
    local own = {}
    local packages = param:getApplicationContext():getPackageManager():getPackagesForUid(param:getUid())
    if packages ~= nil then
        for index = 1, packages['length'] do
            own[#own + 1] = packages[index]
        end
    end
    if #own == 0 then
        own[1] = param:getPackageName()
    end
    return own
end

-- Features hidden by a variant and whether only platform features stay: Read.Features hides features
-- outside android.* and the features of setting features.hide (see features), Read.Biometrics
-- the biometric sensors (see biometrics); nil when the variant hides none
function hiddenfeatures(param, variant)
    if variant == 'features' then
        local hidden = {}
        local extra = param:getSetting('features.hide')
        if extra ~= nil then
            for feature in string.gmatch(extra, '[^,%s]+') do
                hidden[#hidden + 1] = feature
            end
        end
        return hidden, true
    elseif variant == 'biometrics' then
        return { 'android.hardware.fingerprint', 'android.hardware.biometrics.face',
                 'android.hardware.biometrics.iris' }, false
    end
    return nil
end

-- pm and cmd package: list packages lists the packages of the app only, like the package lists of
-- Get.Applications, path finds the packages which an app can look up (see package_probe):
-- its own and system packages; list features lists the features hasSystemFeature finds
function packagefunctions(param, variants)
    local result = {}
    local branches = {}
    if variants['packages'] then
        -- Lines like package:com.example or package:/data/app/.../base.apk=com.example versionCode:1;
        -- one sed for all lines, starting a command for each line takes seconds
        local patterns = {}
        local seds = {}
        for _, name in ipairs(ownpackages(param)) do
            patterns[#patterns + 1] = quote(name)
            seds[#seds + 1] = '-e ' .. quote('/^package:\\([^ ]*=\\)\\{0,1\\}' .. regex(name) .. '\\( .*\\)\\{0,1\\}$/p')
        end
        result[#result + 1] = table.concat({
            '__xlua_packages() {',
            ' /system/bin/sed -n ' .. table.concat(seds, ' '),
            '}',
            '__xlua_visible() {',
            ' case "$1" in',
            '  ' .. table.concat(patterns, '|') .. ') return 0 ;;',
            ' esac',
            ' /system/bin/pm list packages -s | /system/bin/grep -qxF "package:$1"',
            '}',
        }, '\n')
        branches[#branches + 1] = ' if [ "$1" = list ] && [ "$2" = packages ]; then\n' ..
                '  __xlua_package "$@" | __xlua_packages'
        branches[#branches + 1] = ' elif [ "$1" = path ] && [ $# -eq 2 ] && ! __xlua_visible "$2"; then\n' ..
                '  return 1'
    end

    local hidden = {}
    local platform = false
    for _, variant in ipairs({ 'features', 'biometrics' }) do
        if variants[variant] then
            local names, only = hiddenfeatures(param, variant)
            for _, name in ipairs(names) do
                hidden[#hidden + 1] = name
            end
            platform = platform or only
        end
    end
    if variants['features'] or variants['biometrics'] then
        -- Lines like feature:android.hardware.wifi or feature:android.hardware.vulkan.level=1;
        -- the OpenGL ES version (feature:reqGlEsVersion=0x30000) and other lines stay
        local seds = { "-e '/^feature:/!b'", "-e '/^feature:reqGlEsVersion=/b'" }
        for _, name in ipairs(hidden) do
            seds[#seds + 1] = '-e ' .. quote('/^feature:' .. regex(name) .. '\\(=.*\\)\\{0,1\\}$/d')
        end
        if platform then
            seds[#seds + 1] = "-e '/^feature:android\\./!d'"
        end
        result[#result + 1] = table.concat({
            '__xlua_features() {',
            ' /system/bin/sed ' .. table.concat(seds, ' '),
            '}',
        }, '\n')
        branches[#branches + 1] = (#branches == 0 and ' if' or ' elif') ..
                ' [ "$1" = list ] && [ "$2" = features ]; then\n' ..
                '  __xlua_package "$@" | __xlua_features'
    end

    result[#result + 1] = table.concat({
        '__xlua_package() {',
        ' if [ "$__xlua_cmd" = 1 ]; then /system/bin/cmd package "$@"; else /system/bin/pm "$@"; fi',
        '}',
        '__xlua_pm() {',
        table.concat(branches, '\n'),
        ' else',
        '  __xlua_package "$@"',
        ' fi',
        '}',
        'pm() {',
        ' __xlua_cmd=0',
        ' __xlua_pm "$@"',
        '}',
        'cmd() {',
        ' if [ "$1" = package ]; then',
        '  shift',
        '  __xlua_cmd=1',
        '  __xlua_pm "$@"',
        ' else',
        '  /system/bin/cmd "$@"',
        ' fi',
        '}',
        ''
    }, '\n')
    return table.concat(result, '\n')
end

-- Whether the text contains the word, like a command in a script
function hasword(text, word)
    return string.find(' ' .. text .. ' ', '[^%w_.-]' .. word .. '[^%w_.-]') ~= nil
end

-- The commands of a variant in the text, nil when there are none
function commands(text, variant)
    if variant == 'tracking' then
        local names = {}
        if string.find(text, 'getprop', 1, true) then
            names[#names + 1] = 'getprop'
        end
        if string.find(text, 'uname', 1, true) or string.find(text, '/proc/version', 1, true) then
            names[#names + 1] = 'uname'
            names[#names + 1] = 'cat'
        end
        if #names > 0 then
            return names
        end
    elseif variant == 'timezone' then
        if string.find(text, 'getprop', 1, true) then
            return { 'getprop' }
        end
    elseif variant == 'packages' or variant == 'features' or variant == 'biometrics' then
        if hasword(text, 'pm') or hasword(text, 'cmd') then
            return { 'pm', 'cmd' }
        end
    end
    return nil
end

-- The shell functions of the variants and the names of the commands
function functions(param, text, variants)
    local result = ''
    local names = {}
    local seen = {}
    for variant, _ in pairs(variants) do
        for _, name in ipairs(commands(text, variant) or {}) do
            if not seen[name] then
                seen[name] = true
                names[#names + 1] = name
            end
        end
    end

    if seen['getprop'] then
        local all = {}
        for _, variant in ipairs({ 'tracking', 'timezone' }) do
            if variants[variant] then
                for _, rule in ipairs(rules(param, variant)) do
                    all[#all + 1] = rule
                end
            end
        end
        result = result .. getpropfunction(all)
    end
    if seen['uname'] then
        result = result .. kernelfunctions()
    end
    if seen['pm'] then
        result = result .. packagefunctions(param, variants)
    end
    return result, names
end

function basename(path)
    return string.match(path, '[^/]*$')
end

-- The words of a command, nil when one is missing
function wordsof(command)
    local args = command:toArray()
    local words = {}
    for index = 1, args['length'] do
        if args[index] == nil then
            return nil
        end
        words[index] = args[index]
    end
    return words
end

function before(hook, param)
    local builder = param:getThis()
    -- The command of the app, also when the hook of another variant changed it already
    local command = param:getValue('shell.command', builder)
    if command == nil then
        command = builder:command()
    end
    if command == nil or command:isEmpty() then
        return false
    end

    local words = wordsof(command)
    if words == nil then
        return false
    end
    local line = table.concat(words, ' ')

    -- Hook names are like Function/variant
    local variant = string.match(hook:getName(), '/(.*)$')
    if variant == nil then
        variant = 'tracking'
    end
    if commands(line, variant) == nil then
        return false
    end

    local program = basename(words[1])
    local shell = (program == 'sh' or program == 'mksh' or program == 'bash') and #words >= 3 and words[2] == '-c'
    local direct = false
    for _, name in ipairs(commands(line, variant)) do
        if program == name and (name ~= 'cat' or (#words == 2 and words[2] == '/proc/version')) then
            direct = true
        end
    end
    if not direct and not shell then
        return false
    end

    -- The variants of the hooks which ran before for this command
    local variants = {}
    local list = param:getValue('shell.variants', builder)
    if list ~= nil then
        for name in string.gmatch(list, '[^,]+') do
            variants[name] = true
        end
    end
    variants[variant] = true

    local prelude, names = functions(param, line, variants)
    local fake = luajava.newInstance('java.util.ArrayList')
    if shell then
        -- Scripts can run the commands with their path, which would skip the functions
        local script = words[3]
        for _, name in ipairs(names) do
            script = string.gsub(script, '/system/bin/' .. name, name)
        end
        for index = 1, #words do
            fake:add(index == 3 and (prelude .. script) or words[index])
        end
    else
        -- getprop -> sh -c '<functions> getprop "$@"' getprop
        fake:add(SH)
        fake:add('-c')
        fake:add(prelude .. program .. ' "$@"')
        for index = 1, #words do
            fake:add(index == 1 and program or words[index])
        end
    end

    -- The builder gets its own command back after the start, apps can start it again
    local all = {}
    for name, _ in pairs(variants) do
        all[#all + 1] = name
    end
    param:putValue('shell.command', command, builder)
    param:putValue('shell.variants', table.concat(all, ','), builder)
    builder:command(fake)
    return true, line
end

function after(hook, param)
    local builder = param:getThis()
    local command = param:getValue('shell.command', builder)
    if command ~= nil then
        builder:command(command)
        param:putValue('shell.command', nil, builder)
        param:putValue('shell.variants', nil, builder)
    end
    return false
end
