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

-- Apps run commands to read what the other hooks fake, like FingerprintJS runs getprop:
-- getprop reads the system properties behind the fields of android.os.Build, the serial, the operator
-- and the time zone, uname and /proc/version the kernel build of Os.uname, pm the installed packages
-- and the features of the device, date the time zone, cat the files of which apps read another text, like
-- /proc/meminfo and boot_id, and cat, head, dd and other commands reading files fail for the files of /sys which
-- do not exist for the app (see hardware_files), named in the command or the script, also by a link; not
-- covered are redirections of the shell, like read x < file.
-- These commands print the same fake values: they run
-- in a shell with functions of the same names in front, which run the real command and replace the values,
-- so the output keeps its format; date gets the fake time zone in its environment (TZ).
-- The functions cover commands run directly, also with their path, with toybox, toolbox or env in front,
-- the pm script run with sh, and scripts of sh -c naming the commands, also with toybox or toolbox.
-- Not covered are shells reading commands from their input (filtering their input could break apps
-- which wait for output), script files, command and env in scripts, names and paths made while the script runs
-- (like "$dir"/bin/getprop) and native code. Runtime.exec starts processes with ProcessBuilder.start from Android 7,
-- on Android 6 its hooks change the arguments of Runtime.exec(String[], String[], File) instead.
-- Each variant fakes the values of its group. The hooks of all variants run for the same command:
-- each one adds its variant to the variants of the command so far and makes the command again from
-- the original one, so the last one makes the command with the functions of all variants.

local SH = '/system/bin/sh'

-- The security patch level per quarter, like 2025-04-01 for 2025-05-05 (see security_patch)
local QUARTER = {}

-- Shell patterns of the properties behind the fields of android.os.Build with their fake values, the same
-- as systemproperties_get (DEVICE), which a unit test checks; a * matches the partition, like ro.vendor.build.id
local DEVICE = {
    { 'unknown', { 'ro.product.model', 'ro.product.*.model', 'ro.product.brand', 'ro.product.*.brand',
                   'ro.product.manufacturer', 'ro.product.*.manufacturer', 'ro.product.device',
                   'ro.product.*.device', 'ro.product.name', 'ro.product.*.name', 'ro.product.board',
                   'ro.product.*.board', 'ro.soc.model', 'ro.soc.manufacturer', 'ro.hardware',
                   'ro.boot.hardware', 'ro.boot.hardware.platform', 'ro.board.platform',
                   'ro.boot.hardware.sku', 'ro.boot.product.hardware.sku', 'ro.boot.vbmeta.digest',
                   'ro.bootloader', 'ro.boot.bootloader', 'ro.build.expect.bootloader',
                   'gsm.version.baseband', 'ro.build.expect.baseband' } },
    { 'unknown', { 'ro.build.fingerprint', 'ro.*.build.fingerprint', 'ro.build.id', 'ro.*.build.id',
                   'ro.build.display.id', 'ro.build.version.incremental', 'ro.*.build.version.incremental',
                   'ro.build.version.base_os', 'ro.build.host', 'ro.build.user', 'ro.build.tags',
                   'ro.*.build.tags', 'ro.build.type', 'ro.*.build.type', 'ro.build.description',
                   'ro.build.flavor', 'ro.build.product', 'ro.build.date', 'ro.*.build.date' } },
    { QUARTER, { 'ro.build.version.security_patch', 'ro.*.build.version.security_patch',
                 'ro.*.build.security_patch' } },
    { '0', { 'ro.build.date.utc', 'ro.*.build.date.utc' } },
}

-- The operator of the test network, like TelephonyManager.getNetworkOperator (see test_network)
local OPERATOR = {
    { 'unknown', { 'gsm.operator.alpha', 'gsm.sim.operator.alpha' } },
    { '00101', { 'gsm.operator.numeric', 'gsm.sim.operator.numeric' } },
    { 'xx', { 'gsm.operator.iso-country', 'gsm.sim.operator.iso-country' } },
}

-- The properties of the serial, like Build.SERIAL (see systemproperties_get)
local SERIAL = { 'ro.serialno', 'ro.boot.serialno', 'ril.serialnumber', 'ro.boot.ap_serial', 'sys.serialnumber',
                 'ro.ril.oem.sno', 'ro.ril.oem.psno', 'gsm.serial' }

-- The variants faking properties, which getprop prints
local PROPERTIES = { 'tracking', 'identifiers', 'timezone' }

-- The files of the variants of which apps read another text, which cat prints (see catfunction),
-- like the hooks opening files: the id of boot_id per app and the rounded sizes of /proc/meminfo
local FILES = { tracking = { '/proc/sys/kernel/random/boot_id' }, resources = { '/proc/meminfo' } }

-- The files of /sys which do not exist for the variants, like for the hooks opening files (see hardware_files),
-- as patterns of the shell for the path and the path its links lead to, which a test compares with the patterns
-- of hardware_files; commands reading them fail like for a file which does not exist
local HIDDEN = {
    identifiers = { '*/chip-id/unique_id', '*/chip-id/lot_id', '*/mmc[0-9]*:*/cid', '*/mmc[0-9]*:*/serial',
                    '*/sec/sdinfo/data' },
    tracking = { '*/chip-id', '*/chip-id/*', '*/mmc[0-9]*:*/date', '*/mmc[0-9]*:*/manfid', '*/mmc[0-9]*:*/oemid',
                 '*/mmc[0-9]*:*/name', '*/sensors/light_sensor/light_cal', '*/sec/tsp', '*/sec/tsp/*' },
    biometrics = { '*/fingerprint/fingerprint', '*/fingerprint/fingerprint/*', '*/sec/tsp/fod_info',
                   '*/sec/tsp/fod_pos' },
}
local HIDING = { 'identifiers', 'tracking', 'biometrics' }

-- The commands which read files and get the error of a missing file for these files; dd takes if=file
local READERS = { 'cat', 'head', 'tail', 'dd', 'od', 'xxd', 'hexdump', 'strings', 'base64', 'md5sum', 'sha1sum',
                  'sha224sum', 'sha256sum', 'sha384sum', 'sha512sum', 'cksum', 'wc', 'grep', 'cut', 'sed', 'awk',
                  'cp', 'cmp', 'diff' }
local READER = {}
for _, name in ipairs(READERS) do
    READER[name] = true
end

-- The biometric sensors, like biometrics (FEATURES)
local BIOMETRIC = { 'android.hardware.fingerprint', 'android.hardware.biometrics.face',
                    'android.hardware.biometrics.iris' }

-- The shared libraries of the system which are part of the Android platform, like features (PLATFORM and GOOGLE)
local LIBRARIES = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { 'com.google.android.maps', 'com.google.android.media.effects' }

-- Programs running a command of their arguments: multiplexers run the command named by their first argument,
-- env the command after its options and variables
local MULTIPLEXERS = { toybox = true, toolbox = true, busybox = true }
local SHELLS = { sh = true, mksh = true, bash = true }

-- Reserved words of the shell which a command follows, like if /system/bin/getprop ... or do /system/bin/getprop,
-- and eval, which runs its arguments as a command; not exec and command, which skip functions
local RESERVED = { ['if'] = true, ['then'] = true, ['else'] = true, ['elif'] = true, ['while'] = true,
                   ['until'] = true, ['do'] = true, ['time'] = true, ['!'] = true, ['{'] = true, ['eval'] = true }

-- The fake time zone, like timezone
function fakezone(param)
    local zone = param:getSetting('value.timezone')
    if zone == nil or zone == '' then
        zone = 'UTC'
    end
    return zone
end

-- Shell patterns of property names and their fake values for a variant, like the fields of
-- android.os.Build and the hooks of SystemProperties.get
function rules(param, variant)
    if variant == 'timezone' then
        return { { fakezone(param), { 'persist.sys.timezone' } } }
    elseif variant == 'identifiers' then
        local serial = param:getSetting('value.serial')
        if serial == nil then
            serial = 'unknown'
        end
        return { { serial, SERIAL } }
    else
        -- tracking
        local result = {}
        for _, rule in ipairs(DEVICE) do
            result[#result + 1] = rule
        end
        for _, rule in ipairs(OPERATOR) do
            result[#result + 1] = rule
        end
        return result
    end
end

-- A literal for the shell
function quote(value)
    return "'" .. (string.gsub(value, "'", "'\\''")) .. "'"
end

-- A basic regular expression of a property pattern, where * matches the partition like the Lua pattern
-- of systemproperties_get does (one or more characters)
function propertyregex(pattern)
    return (string.gsub(pattern, '[%.%*]', { ['.'] = '\\.', ['*'] = '[^]][^]]*' }))
end

-- A sed command replacing the value of matching lines of getprop like [ro.product.model]: [Pixel 9];
-- empty values stay, like SystemProperties.get keeps them
function sedcommand(pattern, value)
    local replacement = string.gsub(value, '[\\&/\n]', { ['\\'] = '\\\\', ['&'] = '\\&', ['/'] = '\\/', ['\n'] = ' ' })
    return 's/^\\[\\(' .. propertyregex(pattern) .. '\\)\\]: \\[..*\\]$/[\\1]: [' .. replacement .. ']/'
end

-- sed commands replacing a date like 2025-05-05 by the first day of its quarter, like systemproperties_get;
-- other values stay, like there
function quartercommands(pattern)
    local result = {}
    for _, quarter in ipairs({ { '01', '0[123]' }, { '04', '0[456]' }, { '07', '0[789]' }, { '10', '1[012]' } }) do
        result[#result + 1] = 's/^\\[\\(' .. propertyregex(pattern) .. '\\)\\]: \\[\\([0-9]\\{4\\}\\)-' ..
                quarter[2] .. '-[0-9][0-9]\\]$/[\\1]: [\\2-' .. quarter[1] .. '-01]/'
    end
    return result
end

-- getprop without arguments lists all properties, with a name prints its value;
-- the value goes through the same sed as the list, so both are the same
function getpropfunction(rules)
    local patterns = {}
    local seds = {}
    for _, rule in ipairs(rules) do
        for _, pattern in ipairs(rule[2]) do
            patterns[#patterns + 1] = (string.gsub(pattern, '%*', '?*'))
            local commands = (rule[1] == QUARTER and quartercommands(pattern) or { sedcommand(pattern, rule[1]) })
            for _, command in ipairs(commands) do
                seds[#seds + 1] = '-e ' .. quote(command)
            end
        end
    end
    return table.concat({
        '__xlua_property() {',
        ' case "$1" in',
        '  ' .. table.concat(patterns, '|') .. ') return 0 ;;',
        ' esac',
        ' return 1',
        '}',
        '__xlua_properties() {',
        ' /system/bin/sed ' .. table.concat(seds, ' '),
        '}',
        'getprop() {',
        ' if [ $# -eq 0 ]; then',
        '  /system/bin/getprop | __xlua_properties',
        ' elif [ $# -le 2 ] && [ "${1#-}" = "$1" ] && __xlua_property "$1" &&',
        '   __xlua_value=$(/system/bin/getprop "$1") && [ -n "$__xlua_value" ]; then',
        "  /system/bin/printf '[%s]: [%s]\\n' \"$1\" \"$__xlua_value\" | __xlua_properties |",
        "   /system/bin/sed -e 's/^\\[[^]]*\\]: \\[\\(.*\\)\\]$/\\1/'",
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
        ''
    }, '\n')
end

-- The patterns of the shell of the files of /sys which do not exist for the variants
function hiddenpatterns(variants)
    local patterns = {}
    for _, variant in ipairs(HIDING) do
        if variants[variant] then
            for _, pattern in ipairs(HIDDEN[variant]) do
                patterns[#patterns + 1] = pattern
            end
        end
    end
    return patterns
end

-- A Lua pattern of a pattern of the shell, of which the patterns of HIDDEN use * and [...]
function luapattern(glob)
    local result = '^'
    local index = 1
    while index <= #glob do
        local char = string.sub(glob, index, index)
        if char == '*' then
            result = result .. '.*'
        elseif char == '[' then
            local last = string.find(glob, ']', index, true)
            result = result .. string.sub(glob, index, last)
            index = last
        elseif string.find(char, '[%^%$%(%)%%%.%]%+%-%?]') then
            result = result .. '%' .. char
        else
            result = result .. char
        end
        index = index + 1
    end
    return result .. '$'
end

-- The path a path leads to, by the links of /sys, or the path as it is (like hardware_files)
function resolve(path)
    local ok, resolved = pcall(function()
        return luajava.newInstance('java.io.File', path):getCanonicalPath()
    end)
    if ok and resolved ~= nil then
        return resolved
    end
    return path
end

-- Whether a file does not exist for the variants, by its path or the path it leads to; dd takes if=file
function hiddenfile(file, variants)
    file = string.gsub(file, '^if=', '')
    if not string.find(file, 'sys/', 1, true) then
        return false
    end
    local patterns = {}
    for _, pattern in ipairs(hiddenpatterns(variants)) do
        patterns[#patterns + 1] = luapattern(pattern)
    end
    for _, pattern in ipairs(patterns) do
        if string.find(file, pattern) then
            return true
        end
    end
    local resolved = resolve(file)
    for _, pattern in ipairs(patterns) do
        if string.find(resolved, pattern) then
            return true
        end
    end
    return false
end

-- Whether a script or command names a file which does not exist for the variants; not paths made while
-- the script runs, like "$dir"/cid, and files of /sys which exist are read without functions
function hiddenin(text, variants)
    if not string.find(text, 'sys/', 1, true) then
        return false
    end
    for word in string.gmatch(text, '[^%s;&|<>()\'"`]+') do
        if hiddenfile(word, variants) then
            return true
        end
    end
    return false
end

-- The commands reading files in the text, when it names a file which does not exist for the variant
function readers(text, variant, names)
    if hiddenin(text, { [variant] = true }) then
        for _, name in ipairs(READERS) do
            if hasword(text, name) then
                names[#names + 1] = name
            end
        end
    end
end

-- The functions of the commands reading files: files which do not exist for the variants get the error
-- of a missing file, the others are read; after leaving out a file a command does not read its input,
-- like cat and head would without files
function hidingfunctions(variants, names)
    local result = {
        '__xlua_hidden() {',
        ' for __xlua_path in "$1" "$(/system/bin/readlink -f -- "$1" 2>/dev/null)"; do',
        '  case "$__xlua_path" in',
        '   ' .. table.concat(hiddenpatterns(variants), '|') .. ') return 0 ;;',
        '  esac',
        ' done',
        ' return 1',
        '}',
        '__xlua_files() {',
        ' __xlua_program=$1',
        ' shift',
        ' __xlua_rc=0',
        ' __xlua_n=$#',
        ' while [ $__xlua_n -gt 0 ]; do',
        '  __xlua_arg=$1',
        '  shift',
        '  __xlua_n=$((__xlua_n - 1))',
        '  __xlua_file=${__xlua_arg#if=}',
        '  if __xlua_hidden "$__xlua_file"; then',
        "   /system/bin/printf '%s: %s: No such file or directory\\n' \"$__xlua_program\" \"$__xlua_file\" >&2",
        '   __xlua_rc=1',
        '  else',
        '   set -- "$@" "$__xlua_arg"',
        '  fi',
        ' done',
        ' if [ $__xlua_rc -eq 0 ]; then',
        '  "/system/bin/$__xlua_program" "$@"',
        '  return',
        ' fi',
        ' if [ $# -gt 0 ]; then "/system/bin/$__xlua_program" "$@" </dev/null; fi',
        ' return 1',
        '}',
    }
    for _, name in ipairs(names) do
        if name ~= 'cat' then
            result[#result + 1] = name .. '() {'
            result[#result + 1] = ' __xlua_files ' .. name .. ' "$@"'
            result[#result + 1] = '}'
        end
    end
    result[#result + 1] = ''
    return table.concat(result, '\n')
end

-- Whether cat of the file prints another text, with the functions of the variants
function catfile(file, variants)
    if file == '/proc/version' then
        return variants['tracking'] ~= nil
    end
    for variant, files in pairs(FILES) do
        if variants[variant] then
            for _, name in ipairs(files) do
                if file == name then
                    return true
                end
            end
        end
    end
    return false
end

-- cat of one file prints the text the app reads itself, with the hooks opening files, at the start of the command;
-- /proc/version the kernel like uname (see kernelfunctions). Files the app cannot read keep their error.
-- Files of /sys which do not exist for the variants get the error of a missing file (see hidingfunctions).
function catfunction(param, text, variants, kernel, hiding)
    local cases = {}
    if kernel then
        -- Apps cannot read /proc/version on recent versions
        cases[#cases + 1] = '  /proc/version) if /system/bin/cat /proc/version >/dev/null 2>&1; then __xlua_kernel;' ..
                " /system/bin/printf 'Linux version %s #1 SMP PREEMPT\\n' \"$__xlua_short\"; return; fi ;;"
    end
    for variant, files in pairs(FILES) do
        if variants[variant] then
            for _, file in ipairs(files) do
                local content = (string.find(text, file, 1, true) and param:readFile(file) or nil)
                if content ~= nil then
                    cases[#cases + 1] = '  ' .. file .. ") /system/bin/printf '%s' " .. quote(content) .. '; return ;;'
                end
            end
        end
    end
    local result = {
        'cat() {',
        ' if [ $# -eq 1 ]; then',
        '  case "$1" in',
        table.concat(cases, '\n'),
        '  esac',
        ' fi',
    }
    result[#result + 1] = (hiding and ' __xlua_files cat "$@"' or ' /system/bin/cat "$@"')
    result[#result + 1] = '}'
    result[#result + 1] = ''
    return table.concat(result, '\n')
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

-- The features which Read.Features shows, of setting features.keep; a name ending in * matches the features
-- starting with it (see features)
function keptfeatures(param)
    local kept = {}
    local keep = param:getSetting('features.keep')
    if keep ~= nil then
        for feature in string.gmatch(keep, '[^,%s]+') do
            kept[#kept + 1] = feature
        end
    end
    return kept
end

-- The functions of Get.Applications for pm and cmd package and the cases of __xlua_pm they add:
-- the package lists list the packages of the app only, like the package lists of Get.Applications, path finds
-- the packages which an app can look up (see package_probe): its own and system packages; the queries of
-- activities, services and receivers find nothing when they find other apps, like the queries of PackageManager,
-- resolve-activity finds nothing for other apps than these (see package_resolve). The arguments are parsed
-- like PackageManagerShellCommand does, with the short forms -l, -lf and -p of former versions.
function applicationfunctions(param, lists, cases)
    -- Lines like package:com.example or package:/data/app/.../base.apk=com.example versionCode:1, and
    -- instrumentation:com.example.test/androidx.test.runner.AndroidJUnitRunner (target=com.example);
    -- one sed for all lines, starting a command for each line takes seconds
    local patterns = {}
    local packages = {}
    local instrumentations = {}
    for _, name in ipairs(ownpackages(param)) do
        patterns[#patterns + 1] = quote(name)
        packages[#packages + 1] = '-e ' ..
                quote('/^package:\\([^ ]*=\\)\\{0,1\\}' .. regex(name) .. '\\( .*\\)\\{0,1\\}$/p')
        instrumentations[#instrumentations + 1] = '-e ' ..
                quote('/^instrumentation:\\([^ ]*=\\)\\{0,1\\}' .. regex(name) .. '\\//p')
    end

    lists[#lists + 1] = '   package|packages) __xlua_package "$@" | __xlua_packages ;;'
    lists[#lists + 1] = '   instrumentation) __xlua_package "$@" | __xlua_instrumentations ;;'
    cases[#cases + 1] = '  path) __xlua_path "$@" ;;'
    cases[#cases + 1] = '  -[lL]|-[lL][fF]) if [ $# -eq 1 ]; then __xlua_package "$@" | __xlua_packages;' ..
            ' else __xlua_package "$@"; fi ;;'
    cases[#cases + 1] = '  -[pP]) if [ $# -eq 2 ] && ! __xlua_visible "$2"; then return 1; fi; __xlua_package "$@" ;;'
    cases[#cases + 1] = '  query-activities) __xlua_query __xlua_own \'No activities found\' "$@" ;;'
    cases[#cases + 1] = '  query-services) __xlua_query __xlua_own \'No services found\' "$@" ;;'
    cases[#cases + 1] = '  query-receivers) __xlua_query __xlua_own \'No receivers found\' "$@" ;;'
    cases[#cases + 1] = '  resolve-activity) __xlua_query __xlua_visible \'No activity found\' "$@" ;;'

    return table.concat({
        '__xlua_packages() {',
        ' /system/bin/sed -n ' .. table.concat(packages, ' '),
        '}',
        '__xlua_instrumentations() {',
        ' /system/bin/sed -n ' .. table.concat(instrumentations, ' '),
        '}',
        '__xlua_own() {',
        ' case "$1" in',
        '  ' .. table.concat(patterns, '|') .. ') return 0 ;;',
        ' esac',
        ' return 1',
        '}',
        -- A text of any length, like the dump of a query: the shell writes it itself, with print in mksh, the shell
        -- of Android, else with printf; one argument of a program like /system/bin/printf can have 128 KB only
        '__xlua_print() {',
        ' if [ -n "${KSH_VERSION-}" ]; then print -r -- "$1"; else printf \'%s\\n\' "$1"; fi',
        '}',
        '__xlua_visible() {',
        ' __xlua_own "$1" && return 0',
        ' [ -n "${__xlua_system-}" ] || __xlua_system=$(/system/bin/pm list packages -s)',
        ' __xlua_print "$__xlua_system" | /system/bin/grep -qxF "package:$1"',
        '}',
        -- path [--user USER] PACKAGE: one option, like -u or --user with its value, then the package
        '__xlua_path() {',
        ' case "${2-}" in',
        '  --user) __xlua_name=${4-} ;;',
        '  -|--|--*|-?) __xlua_name=${3-} ;;',
        '  -*) __xlua_name=${2#-?} ;;',
        '  *) __xlua_name=${2-} ;;',
        ' esac',
        ' if [ -n "$__xlua_name" ] && ! __xlua_visible "$__xlua_name"; then',
        '  return 1',
        ' fi',
        ' __xlua_package "$@"',
        '}',
        -- The packages a query found: of dumps like packageName=com.example and of components like com.example/.Main
        '__xlua_found() {',
        " __xlua_print \"$1\" | /system/bin/sed -n -e 's/^ *packageName=\\(.*\\)$/\\1/p'" ..
                " -e 's|^ *\\([A-Za-z0-9_.]*\\)/[^ ]*$|\\1|p'",
        '}',
        -- A query printing what it found when the check allows all packages, else the answer of nothing found;
        -- the lines of one result do not tell which result they belong to, so all of them or none stay
        '__xlua_query() {',
        ' __xlua_check=$1',
        ' __xlua_none=$2',
        ' shift 2',
        ' __xlua_status=0',
        ' __xlua_out=$(__xlua_package "$@") || __xlua_status=$?',
        ' if __xlua_found "$__xlua_out" |',
        '   (while read -r __xlua_name; do $__xlua_check "$__xlua_name" || exit 1; done); then',
        '  [ -z "$__xlua_out" ] || __xlua_print "$__xlua_out"',
        '  return $__xlua_status',
        ' fi',
        ' __xlua_print "$__xlua_none"',
        '}',
    }, '\n')
end

-- The functions of Read.Features and Read.Biometrics for pm and cmd package: list features and has-feature
-- find the features which hasSystemFeature finds, list libraries the libraries of getSystemSharedLibraryNames
-- (see features and biometrics)
function featurefunctions(param, variants, lists, cases)
    -- Lines like feature:android.hardware.wifi or feature:android.hardware.vulkan.level=1;
    -- the OpenGL ES version (feature:reqGlEsVersion=0x30000) and other lines stay
    local seds = { "-e '/^feature:/!b'", "-e '/^feature:reqGlEsVersion=/b'" }
    local checks = {}
    if variants['biometrics'] then
        -- The biometric features go first, also when Read.Features keeps them
        local names = {}
        for _, name in ipairs(BIOMETRIC) do
            seds[#seds + 1] = '-e ' .. quote('/^feature:' .. regex(name) .. '\\(=.*\\)\\{0,1\\}$/d')
            names[#names + 1] = quote(name)
        end
        checks[#checks + 1] = '  ' .. table.concat(names, '|') .. ') return 0 ;;'
    end
    if variants['features'] then
        -- The kept features stay, all others go; a name ending in * matches the features starting with it
        for _, name in ipairs(keptfeatures(param)) do
            if string.sub(name, -1) == '*' then
                local prefix = string.sub(name, 1, -2)
                seds[#seds + 1] = '-e ' .. quote('/^feature:' .. regex(prefix) .. '/b')
                checks[#checks + 1] = '  ' .. quote(prefix) .. '*) return 1 ;;'
            else
                seds[#seds + 1] = '-e ' .. quote('/^feature:' .. regex(name) .. '\\(=.*\\)\\{0,1\\}$/b')
                checks[#checks + 1] = '  ' .. quote(name) .. ') return 1 ;;'
            end
        end
        seds[#seds + 1] = "-e '/^feature:/d'"
        checks[#checks + 1] = '  *) return 0 ;;'
    else
        checks[#checks + 1] = '  *) return 1 ;;'
    end

    lists[#lists + 1] = '   features) __xlua_package "$@" | __xlua_features ;;'
    cases[#cases + 1] = "  has-feature) if [ -n \"${2-}\" ] && __xlua_hidden \"$2\"; then echo false; return 1; fi;" ..
            ' __xlua_package "$@" ;;'
    local result = {
        '__xlua_features() {',
        ' /system/bin/sed ' .. table.concat(seds, ' '),
        '}',
        '__xlua_hidden() {',
        ' case "$1" in',
        table.concat(checks, '\n'),
        ' esac',
        '}',
    }

    if variants['features'] then
        -- Lines like library:com.google.android.dialer.support or library:android.test.base path:/system/...
        local libraries = { "-e '/^library:/!b'" }
        for _, prefix in ipairs(LIBRARIES) do
            libraries[#libraries + 1] = '-e ' .. quote('/^library:' .. regex(prefix) .. '/b')
        end
        for _, name in ipairs(GOOGLE) do
            libraries[#libraries + 1] = '-e ' .. quote('/^library:' .. regex(name) .. '\\( .*\\)\\{0,1\\}$/b')
        end
        libraries[#libraries + 1] = '-e d'
        lists[#lists + 1] = '   libraries) __xlua_package "$@" | __xlua_libraries ;;'
        result[#result + 1] = '__xlua_libraries() {'
        result[#result + 1] = ' /system/bin/sed ' .. table.concat(libraries, ' ')
        result[#result + 1] = '}'
    end
    return table.concat(result, '\n')
end

-- pm and cmd package (also cmd -w package) with the cases of the variants, other commands stay
function packagefunctions(param, variants)
    local result = {}
    local lists = {}
    local cases = {}
    if variants['packages'] then
        result[#result + 1] = applicationfunctions(param, lists, cases)
    end
    if variants['features'] or variants['biometrics'] then
        result[#result + 1] = featurefunctions(param, variants, lists, cases)
    end
    if #lists > 0 then
        lists[#lists + 1] = '   *) __xlua_package "$@" ;;'
        table.insert(cases, 1, '  list) case "${2-}" in\n' .. table.concat(lists, '\n') .. '\n  esac ;;')
    end
    cases[#cases + 1] = '  *) __xlua_package "$@" ;;'

    result[#result + 1] = table.concat({
        '__xlua_package() {',
        ' if [ "$__xlua_cmd" = 1 ]; then /system/bin/cmd $__xlua_wait package "$@"; else /system/bin/pm "$@"; fi',
        '}',
        '__xlua_pm() {',
        ' case "${1-}" in',
        table.concat(cases, '\n'),
        ' esac',
        '}',
        'pm() {',
        ' __xlua_cmd=0',
        ' __xlua_wait=',
        ' __xlua_pm "$@"',
        '}',
        'cmd() {',
        ' __xlua_wait=',
        ' if [ "${1-}" = -w ]; then',
        '  __xlua_wait=-w',
        '  shift',
        ' fi',
        ' if [ "${1-}" = package ]; then',
        '  shift',
        '  __xlua_cmd=1',
        '  __xlua_pm "$@"',
        ' else',
        '  /system/bin/cmd $__xlua_wait "$@"',
        ' fi',
        '}',
        ''
    }, '\n')
    return table.concat(result, '\n')
end

-- Scripts can run the commands with toybox or toolbox, which would skip the functions
function multiplexerfunctions(text, names)
    local result = ''
    local multiplexers = {}
    for _, multiplexer in ipairs({ 'toybox', 'toolbox' }) do
        if hasword(text, multiplexer) then
            multiplexers[#multiplexers + 1] = multiplexer
            result = result .. table.concat({
                multiplexer .. '() {',
                ' case "${1-}" in',
                '  ' .. table.concat(names, '|') .. ') "$@" ;;',
                '  *) /system/bin/' .. multiplexer .. ' "$@" ;;',
                ' esac',
                '}',
                ''
            }, '\n')
        end
    end
    return result, multiplexers
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
        elseif string.find(text, FILES[variant][1], 1, true) then
            names[#names + 1] = 'cat'
        end
        readers(text, variant, names)
        if #names > 0 then
            return names
        end
    elseif variant == 'resources' then
        for _, file in ipairs(FILES[variant]) do
            if string.find(text, file, 1, true) then
                return { 'cat' }
            end
        end
    elseif variant == 'identifiers' or variant == 'timezone' then
        local names = {}
        if string.find(text, 'getprop', 1, true) then
            names[#names + 1] = 'getprop'
        end
        if variant == 'identifiers' then
            readers(text, variant, names)
        end
        if #names > 0 then
            return names
        end
    else
        -- packages, features, biometrics
        local names = {}
        if hasword(text, 'pm') or hasword(text, 'cmd') then
            names[#names + 1] = 'pm'
            names[#names + 1] = 'cmd'
        end
        if variant == 'biometrics' then
            readers(text, variant, names)
        end
        if #names > 0 then
            return names
        end
    end
    return nil
end

-- The shell functions of the variants and the names of the commands; scripts get functions for toybox and
-- toolbox too
function functions(param, text, variants, script)
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
        for _, variant in ipairs(PROPERTIES) do
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
    local reading = {}
    for _, name in ipairs(READERS) do
        if seen[name] then
            reading[#reading + 1] = name
        end
    end
    local hiding = (#reading > 0 and hiddenin(text, variants))
    if hiding then
        result = result .. hidingfunctions(variants, reading)
    end
    if seen['cat'] then
        result = result .. catfunction(param, text, variants, seen['uname'], hiding)
    end
    if seen['pm'] then
        result = result .. packagefunctions(param, variants)
    end
    if script then
        local multiplexers, more = multiplexerfunctions(text, names)
        result = result .. multiplexers
        for _, name in ipairs(more) do
            names[#names + 1] = name
        end
    end
    return result, names
end

function basename(path)
    return string.match(path, '[^/]*$')
end

-- The words of a command (a Java array), nil when one is missing
function wordsof(args)
    local words = {}
    for index = 1, args['length'] do
        if args[index] == nil then
            return nil
        end
        words[index] = args[index]
    end
    return words
end

-- The index of the command in the words, after multiplexers and env with its options and variables,
-- and the words in front which stay: env runs the shell of the functions with the same environment
function commandof(words)
    local at = 1
    local prefix = {}
    while at < #words do
        local name = basename(words[at])
        if MULTIPLEXERS[name] then
            at = at + 1
        elseif name == 'env' then
            prefix[#prefix + 1] = words[at]
            at = at + 1
            while at < #words and (string.sub(words[at], 1, 1) == '-' or string.find(words[at], '=', 1, true)) do
                local option = words[at]
                prefix[#prefix + 1] = option
                at = at + 1
                if option == '--' then
                    break
                elseif (option == '-u' or option == '-C') and at < #words then
                    prefix[#prefix + 1] = words[at]
                    at = at + 1
                end
            end
        else
            break
        end
    end
    return at, prefix
end

-- The index of the first operand of a shell after its options, like sh -c script or sh -ec script,
-- and whether the operand is a script (option c)
function shelloperand(words, at)
    local index = at + 1
    local script = false
    while index <= #words do
        local word = words[index]
        if word == '--' or word == '-' then
            index = index + 1
            break
        elseif word == '-o' or word == '+o' then
            index = index + 2
        elseif string.match(word, '^[-+]%a+$') then
            if string.sub(word, 1, 1) == '-' and string.find(word, 'c', 2, true) then
                script = true
            end
            index = index + 1
        else
            break
        end
    end
    return index, script
end

-- Whether the ) at the index ends a case pattern like *) or (a), which a command follows, or a function like f();
-- not a command substitution like $(dirname x) or arithmetic like $((1)), which arguments follow
function closespattern(text, index)
    local depth = 1
    local open = index
    while depth > 0 do
        open = open - 1
        if open == 0 then
            -- Without a ( before it
            return true
        end
        local char = string.sub(text, open, open)
        if char == ')' then
            depth = depth + 1
        elseif char == '(' then
            depth = depth - 1
        end
    end
    return (string.sub(text, open - 1, open - 1) ~= '$')
end

-- Whether the word at the index is in the place of a command: first in the script, after an operator like ;, &&,
-- | or $(, after a reserved word like then or do, after a case pattern like *), after variables like A=1 in such
-- a place, or first in a quoted string in such a place, like eval "/system/bin/getprop ...". Not the arguments
-- of commands, like those of [ -x, ls and for, and not words with something in front, like ${root}/bin/getprop.
function commandplace(text, at)
    local index = at - 1
    while index > 0 do
        local char = string.sub(text, index, index)
        if char == ' ' or char == '\t' then
            index = index - 1
        elseif char == '\n' and string.sub(text, index - 1, index - 1) == '\\' then
            -- A continued line
            index = index - 2
        else
            break
        end
    end
    if index == 0 then
        return true
    end
    local char = string.sub(text, index, index)
    local before = string.sub(text, index - 1, index - 1)
    if string.find(char, '[;(`\n]') then
        return true
    elseif char == ')' then
        -- Also the body of a function like f() { ...
        return closespattern(text, index)
    elseif char == '&' or char == '|' then
        -- Not after redirections like 2>&1 and >|
        return not string.find(before, '[<>]')
    elseif index == at - 1 then
        -- The first word of a string, which eval runs as a command, else something in front of the word
        return ((char == '"' or char == "'") and commandplace(text, index))
    end
    local first = index
    while first > 1 and not string.find(string.sub(text, first - 1, first - 1), '[%s;&|()`]') do
        first = first - 1
    end
    local word = string.sub(text, first, index)
    if RESERVED[word] or string.match(word, '^[%a_][%w_]*=[^\'"`$\\()]*$') then
        return commandplace(text, first)
    end
    return false
end

-- Scripts can run the commands with their path, which would skip the functions; only paths in the place of
-- a command are replaced, also when quoted, others are arguments like [ -x /system/bin/getprop ], which stay
function unpath(script, name)
    local text = script
    for _, dir in ipairs({ '/system/bin/', '/bin/' }) do
        local path = dir .. name
        local parts = {}
        local from = 1
        local at = string.find(text, path, 1, true)
        while at ~= nil do
            local first = at
            local after = at + #path
            local quote = string.sub(text, at - 1, at - 1)
            if (quote == '"' or quote == "'") and string.sub(text, after, after) == quote then
                first = at - 1
                after = after + 1
            end
            if string.find(string.sub(text, after, after), '^[%s;&|)`<>]?$') and commandplace(text, first) then
                parts[#parts + 1] = string.sub(text, from, at - 1) .. name
                from = at + #path
            end
            at = string.find(text, path, at + 1, true)
        end
        parts[#parts + 1] = string.sub(text, from)
        text = table.concat(parts)
    end
    return text
end

-- The environment of Runtime.exec with the fake time zone, nil when it has a time zone already
function runtimeenvironment(envp, zone)
    local list = luajava.newInstance('java.util.ArrayList')
    if envp == nil then
        -- The environment of the app
        local env = luajava.bindClass('java.lang.System'):getenv()
        if env:containsKey('TZ') then
            return nil
        end
        local entries = env:entrySet():toArray()
        for index = 1, entries['length'] do
            list:add(entries[index]:getKey() .. '=' .. entries[index]:getValue())
        end
    else
        for index = 1, envp['length'] do
            local entry = envp[index]
            if entry ~= nil and string.sub(entry, 1, 3) == 'TZ=' then
                return nil
            end
            list:add(entry)
        end
    end
    list:add('TZ=' .. zone)
    local stringClass = luajava.bindClass('java.lang.String')
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    return list:toArray(arrayClass:newInstance(stringClass, 0))
end

-- date prints the time in the time zone of its environment, which is the real one,
-- unless the app sets one; whether the fake time zone was set
function settimezone(param, runtime, builder)
    local zone = fakezone(param)
    if runtime then
        local envp = runtimeenvironment(param:getArgument(1), zone)
        if envp == nil then
            return false
        end
        param:setArgument(1, envp)
    else
        local environment = builder:environment()
        if environment:get('TZ') ~= nil then
            return false
        end
        environment:put('TZ', zone)
        param:putValue('shell.timezone', zone, builder)
    end
    return true
end

function before(hook, param)
    -- Hook names are like Function/variant
    local variant = string.match(hook:getName(), '/(.*)$')
    if variant == nil then
        variant = 'tracking'
    end

    -- The command of the app, also when the hook of another variant changed it already:
    -- ProcessBuilder.start has it in the builder, Runtime.exec of Android 6 as its first argument
    local runtime = (hook:getMethodName() == 'exec')
    local scope
    local command
    local words
    if runtime then
        scope = param:getArgument(0)
        if scope == nil then
            return false
        end
        command = param:getValue('shell.command', scope)
        if command == nil then
            command = scope
        end
        words = wordsof(command)
    else
        scope = param:getThis()
        command = param:getValue('shell.command', scope)
        if command == nil then
            command = scope:command()
        end
        if command == nil then
            return false
        end
        words = wordsof(command:toArray())
    end
    if words == nil or #words == 0 then
        return false
    end
    local line = table.concat(words, ' ')

    local zone = (variant == 'timezone' and hasword(line, 'date') and settimezone(param, runtime, scope))
    local names = commands(line, variant)
    if names == nil then
        return zone, line
    end

    local at, prefix = commandof(words)
    local program = basename(words[at])
    local script
    if SHELLS[program] then
        local operand, option = shelloperand(words, at)
        if option and operand <= #words then
            script = operand
        elseif not option and operand <= #words and basename(words[operand]) == 'pm' then
            -- sh /system/bin/pm runs the script of pm
            at = operand
            program = 'pm'
        end
    end
    if script == nil then
        local direct = false
        local own = { [variant] = true }
        for _, name in ipairs(names) do
            if program == name then
                if name == 'cat' and #words == at + 1 and catfile(words[at + 1], own) then
                    direct = true
                elseif READER[name] then
                    -- Commands reading files which exist run as they are
                    for index = at + 1, #words do
                        if hiddenfile(words[index], own) then
                            direct = true
                        end
                    end
                else
                    direct = true
                end
            end
        end
        if not direct then
            return zone, line
        end
    end

    -- The variants of the hooks which ran before for this command
    local variants = {}
    local list = param:getValue('shell.variants', scope)
    if list ~= nil then
        for name in string.gmatch(list, '[^,]+') do
            variants[name] = true
        end
    end
    variants[variant] = true

    local prelude, functionnames = functions(param, line, variants, script ~= nil)
    local fake = luajava.newInstance('java.util.ArrayList')
    if script ~= nil then
        local text = words[script]
        for _, name in ipairs(functionnames) do
            text = unpath(text, name)
        end
        for index = 1, #words do
            fake:add(index == script and (prelude .. text) or words[index])
        end
    else
        -- getprop -> sh -c '<functions> getprop "$@"' getprop, after env and its variables
        for _, word in ipairs(prefix) do
            fake:add(word)
        end
        fake:add(SH)
        fake:add('-c')
        fake:add(prelude .. program .. ' "$@"')
        fake:add(program)
        for index = at + 1, #words do
            fake:add(words[index])
        end
    end

    local all = {}
    for name, _ in pairs(variants) do
        all[#all + 1] = name
    end
    if runtime then
        -- The other variants find the original command with the new arguments
        local stringClass = luajava.bindClass('java.lang.String')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local args = fake:toArray(arrayClass:newInstance(stringClass, 0))
        param:putValue('shell.command', command, args)
        param:putValue('shell.variants', table.concat(all, ','), args)
        param:setArgument(0, args)
    else
        -- The builder gets its own command back after the start, apps can start it again; the command filter
        -- of Downloads (Fif ProcessBuilder.start) matches this original command too
        param:putValue('shell.command', command, scope)
        param:putValue('shell.variants', table.concat(all, ','), scope)
        scope:command(fake)
    end
    return true, line
end

function after(hook, param)
    -- Runtime.exec got new arguments, which the app does not see
    if hook:getMethodName() == 'exec' then
        return false
    end

    local builder = param:getThis()
    local command = param:getValue('shell.command', builder)
    if command ~= nil then
        builder:command(command)
        param:putValue('shell.command', nil, builder)
        param:putValue('shell.variants', nil, builder)
    end
    if param:getValue('shell.timezone', builder) ~= nil then
        builder:environment():remove('TZ')
        param:putValue('shell.timezone', nil, builder)
    end
    return false
end
