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
-- the time zone and the language, uname, /proc/version, /proc/sys/kernel/osrelease and version the kernel build of
-- Os.uname, sysctl the same and boot_id, pm the installed packages and the features of the device, date the time
-- zone, cat the files of which apps read another text, like /proc/meminfo and boot_id, and cat, head, dd and other
-- commands reading files fail for the files of /sys which do not exist for the app (see hardware_files), named in
-- the command or the script, also by a link, or relative to the working directory of the builder; recursive reads,
-- like grep -R, ls -R, find -exec, cp -r and tar, fail for the folders above these files (see nearpatterns).
-- Not covered are redirections of the shell, like read x < file, and commands which show that these files exist
-- without reading them, like ls, stat, find and test (or [ -e file ]), and recursive reads of other folders
-- between /sys and these files, like /sys/devices/platform/<device>.
-- stat, ls -l, find with times, date -r, touch -r, cp -p and tar fail for the folders of which storage_times fakes
-- the times, in any case (see timesfunctions).
-- Not covered either are df and free, which print the exact sizes that Read.Resources rounds (see resources).
-- For Read.Network ip, ifconfig, netstat, ss and arp fail and the files of /proc/net do not exist (see NETWORK).
-- For Read.Debugging su is not there, and which and the commands reading files do not find it or Magisk.
-- These commands print the same fake values: they run
-- in a shell with functions of the same names in front, which run the real command and replace the values,
-- so the output keeps its format; date gets the fake time zone in its environment (TZ).
-- The functions cover commands run directly, also with their path, with toybox, toolbox, env, nohup, nice or
-- timeout in front, the pm script run with sh, and scripts of sh -c naming the commands, also quoted in parts,
-- like g''etprop, with toybox, toolbox, env, nohup, nice, timeout, setsid, xargs or exec in front, and in scripts of
-- sh -c in the script (see routefunctions).
-- Not covered are shells reading commands from their input (filtering their input could break apps
-- which wait for output), script files, command in scripts, programs run by other programs, like find -exec in
-- other folders and xargs with -0, -d or -I, names and paths made while the script runs (like "$dir"/bin/getprop)
-- and native code. Runtime.exec starts processes with ProcessBuilder.start.
-- The variant commands (Run.Commands) fakes nothing: it refuses commands running a program which is not allowed,
-- see refuse.
-- Each other variant fakes the values of its group. The hooks of all variants run for the same command:
-- each one adds its variant to the variants of the command so far and makes the command again from
-- the original one, so the last one makes the command with the functions of all variants.

local SH = '/system/bin/sh'

-- The security patch level per quarter, like 2025-04-01 for 2025-05-05 (see systemproperties_get)
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
                   'ro.boot.vbmeta.public_key_digest', 'ro.bootloader', 'ro.boot.bootloader', 'ro.build.expect.bootloader',
                   'gsm.version.baseband', 'ro.build.expect.baseband', 'ro.product.marketname',
                   'ro.product.*.marketname', 'ro.config.marketing_name', 'ro.vendor.oplus.market.name',
                   'ro.oppo.market.name',
                   'ro.*.product.model', 'ro.*.product.manufacturer', 'ro.*.product.brand', 'ro.*.product.device',
                   'ro.*.product.name', 'persist.sys.exif.model',
                   'ro.*.soc.*', 'ro.chipname', 'ro.hardware.chipname', 'ro.vendor.qti.soc_model',
                   'ro.vendor.qti.soc_name', 'ro.mediatek.platform', 'ro.vendor.mediatek.platform' } },
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
                 'ro.ril.oem.sno', 'ro.ril.oem.psno', 'gsm.serial',
                 'ro.boot.em.did', 'persist.sys.lite.uid', 'persist.radio.serialno', 'persist.sys.oneplus.serialno' }

-- The serial number of the SIM card (ICC ID) in properties of vendors, empty, for each name containing iccid in any
-- case, also at the end, like persist.sys.tctPowerIccid (see systemproperties_get)
local ICCID = { '*[Ii][Cc][Cc][Ii][Dd]*', '*[Ii][Cc][Cc][Ii][Dd]' }

-- The properties of USB debugging, which agree with adb_enabled 0 of Read.Debugging (see settingssecure_getstring):
-- adbd stopped, and adb left out of the USB functions, like mtp,adb, which adb alone makes none (see systemproperties_get)
local NOADB = {}
local DEBUGGING = {
    { 'stopped', { 'init.svc.adbd' } },
    { NOADB, { 'sys.usb.config', 'sys.usb.state', 'persist.sys.usb.config' } },
    -- Not debuggable, like a user build, and the log buffer sizes not set (see systemproperties_get)
    { '0', { 'ro.debuggable' } },
    { '1', { 'ro.secure' } },
    { '', { 'persist.logd.size', 'persist.logd.size.main', 'persist.logd.size.crash' } },
}

-- The variants faking properties, which getprop prints
local PROPERTIES = { 'tracking', 'identifiers', 'timezone', 'debugging', 'locale' }

-- The files of the variants of which apps read another text, which cat prints (see catfunction),
-- like the hooks opening files: the id of boot_id per app, /proc/cpuinfo without the lines naming the chip,
-- and the rounded sizes of /proc/meminfo
local FILES = { tracking = { '/proc/sys/kernel/random/boot_id', '/proc/cpuinfo' },
                resources = { '/proc/meminfo' } }

-- The files of /sys which do not exist for the variants, like for the hooks opening files (see hardware_files),
-- as patterns of the shell for the path and the path its links lead to, which a test compares with the patterns
-- of hardware_files; commands reading them fail like for a file which does not exist
local HIDDEN = {
    identifiers = { '*/chip-id/unique_id', '*/chip-id/lot_id', '*/chip-id/lot_id2', '*/mmc[0-9]*:*/cid',
                    '*/mmc[0-9]*:*/serial', '*/sec/sdinfo/data', '*/scsi_host/host[0-9]*/unique_number',
                    '*/string_descriptors/serial_number', '*/soc[0-9]*/serial_number' },
    tracking = { '*/chip-id', '*/chip-id/*', '*/soc[0-9]*', '*/soc[0-9]*/*', '*/mmc[0-9]*:*/date',
                 '*/mmc[0-9]*:*/manfid', '*/mmc[0-9]*:*/oemid',
                 '*/mmc[0-9]*:*/name', '*/scsi_host/host[0-9]*/man_id', '*/string_descriptors/manufacturer_name',
                 '*/string_descriptors/product_name', '*/string_descriptors/oem_id',
                 '*/block/sd[a-z]*/device/model', '*/block/sd[a-z]*/device/vendor', '*/block/sd[a-z]*/device/rev',
                 '*/[0-9]*:[0-9]*:[0-9]*:[0-9]*/model', '*/[0-9]*:[0-9]*:[0-9]*:[0-9]*/vendor',
                 '*/[0-9]*:[0-9]*:[0-9]*:[0-9]*/rev',
                 '*/sensors/light_sensor/light_cal', '*/sec/tsp', '*/sec/tsp/*', '*/class/camera/*',
                 '*/virtual/camera/*', '*/proc/scsi/*', '*/proc/bus/input/devices',
                 '*/devices/system/cpu', '*/devices/system/cpu/*' },
    biometrics = { '*/fingerprint/fingerprint', '*/fingerprint/fingerprint/*', '*/sec/tsp/fod_info',
                   '*/sec/tsp/fod_pos' },
    -- The files of /proc/net, like for Read.Network, which can be opened in hardware_files only (see there)
    network = { '*/proc/net/*', '*/proc/[0-9]*/net/*', '*/proc/self/net/*', '*/proc/thread-self/net/*' },
    -- su and Magisk, of which which leaves out the paths too (see whichfunction)
    debugging = { '/system/bin/su', '/system/xbin/su', '/sbin/su', '/su', '/su/*', '/system/bin/failsafe/su',
                  '/system/sd/xbin/su', '/system/bin/.ext/su', '/system/usr/we-need-root/su', '/data/local/su',
                  '/data/local/bin/su', '/data/local/xbin/su', '/cache/su', '/dev/su', '/vendor/bin/su',
                  '/vendor/xbin/su', '/product/bin/su', '/system_ext/bin/su', '/odm/bin/su',
                  '/system/app/Superuser.apk', '/system/app/Superuser/*', '/system/app/SuperSU/*',
                  '/system/bin/magisk*', '/system/bin/resetprop', '/system/bin/supolicy', '/sbin/magisk*',
                  '/sbin/.magisk*', '/debug_ramdisk/*', '/data/adb/*' },
    -- The files of the features and of the permissions of system apps (Read.Features, Get.Applications)
    features = { '*/etc/permissions', '*/etc/permissions/*', '*/etc/sysconfig', '*/etc/sysconfig/*' },
    packages = { '*/etc/permissions/privapp-permissions-*' },
}
local HIDING = { 'identifiers', 'tracking', 'biometrics', 'network', 'debugging', 'features', 'packages' }
-- Parts of the texts which can name a file of HIDDEN
local SPECIAL = { 'sys/', 'net/', 'proc/', 'su', 'magisk', 'debug_ramdisk', 'Super', 'adb/', 'etc/' }

-- The commands listing the addresses, routes and neighbours of the interfaces, from netlink and /proc/net, which
-- apps can run on Android 10 and before and apps for those versions later; for Read.Network they fail like there
local NETWORK = { 'ip', 'ifconfig', 'netstat', 'ss', 'arp' }

-- The commands which read files and get the error of a missing file for these files; dd takes if=file
local READERS = { 'cat', 'head', 'tail', 'dd', 'od', 'xxd', 'hexdump', 'strings', 'base64', 'md5sum', 'sha1sum',
                  'sha224sum', 'sha256sum', 'sha384sum', 'sha512sum', 'cksum', 'wc', 'grep', 'egrep', 'fgrep', 'cut',
                  'sed', 'awk', 'cp', 'cmp', 'diff', 'sort', 'tac', 'nl', 'rev', 'uniq', 'paste', 'fold', 'comm',
                  'expand', 'more', 'zcat', 'gzip', 'tar', 'cpio', 'iconv', 'uuencode', 'install', 'split' }
local READER = {}
for _, name in ipairs(READERS) do
    READER[name] = true
end
-- The commands reading each file apart, which go on with the other files after a missing one, like cat; the others,
-- like dd, cp and cmp, fail without doing anything, and grep, sed and awk with -f too, whose file is no input
local GOESON = { 'cat', 'head', 'tail', 'od', 'xxd', 'hexdump', 'strings', 'md5sum', 'sha1sum', 'sha224sum',
                 'sha256sum', 'sha384sum', 'sha512sum', 'cksum', 'wc', 'grep', 'egrep', 'fgrep', 'cut', 'sed', 'awk',
                 'sort', 'tac', 'nl', 'rev', 'fold', 'expand', 'more', 'zcat', 'gzip', 'iconv' }

-- Commands reading folders recursively, with their long and short options doing so, true for always, which fail
-- for the folders above hidden files (see nearpatterns); find runs other programs on the files with -exec, which do
-- not have the functions
local DEEP = { grep = { '--recursive|--dereference-recursive', '-*[rR]*' }, egrep = { '--recursive', '-*[rR]*' },
               fgrep = { '--recursive', '-*[rR]*' }, ls = { '--recursive', '-*R*' },
               find = { '-exec|-execdir|-ok|-okdir' }, cp = { '--recursive|--archive', '-*[rRa]*' }, tar = true }
-- The folders of /sys above the folders of the patterns of HIDDEN for /sys, like /sys/devices/system of chip-id
local SYSFOLDERS = { '/sys', '/sys/devices', '/sys/devices/system', '/sys/devices/platform', '/sys/devices/virtual',
                     '/sys/bus', '/sys/bus/mmc', '/sys/bus/mmc/devices', '/sys/bus/soc', '/sys/bus/soc/devices',
                     '/sys/bus/scsi', '/sys/bus/scsi/devices', '/sys/class/mmc_host', '/sys/class/mmc_host/mmc[0-9]*',
                     '/proc', '/proc/self', '/proc/thread-self', '/proc/[0-9]*' }

-- The biometric sensors, like biometrics (FEATURES)
local BIOMETRIC = { 'android.hardware.fingerprint', 'android.hardware.biometrics.face',
                    'android.hardware.biometrics.iris' }

-- The shared libraries of the system which are part of the Android platform, like features (PLATFORM and GOOGLE)
local LIBRARIES = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { 'com.google.android.maps', 'com.google.android.media.effects' }

-- Programs running a command of their arguments: multiplexers run the command named by their first argument,
-- env the command after its options and variables
local MULTIPLEXERS = { toybox = true, toolbox = true, busybox = true }
-- Programs running the rest of the command, which stay in front of the shell of the functions, with their
-- options taking a value
local RUNNERS = { env = { ['-u'] = true, ['-C'] = true }, nohup = {}, nice = { ['-n'] = true },
                  timeout = { ['-s'] = true, ['-k'] = true }, setsid = {} }
local SHELLS = { sh = true, mksh = true, bash = true }

-- Reserved words of the shell which a command follows, like if /system/bin/getprop ... or do /system/bin/getprop,
-- and eval, which runs its arguments as a command; not exec and command, which skip functions
local RESERVED = { ['if'] = true, ['then'] = true, ['else'] = true, ['elif'] = true, ['while'] = true,
                   ['until'] = true, ['do'] = true, ['time'] = true, ['!'] = true, ['{'] = true, ['eval'] = true }

-- Words of the shell which are no programs, like those of RESERVED and the words ending them
local KEYWORDS = { ['if'] = true, ['then'] = true, ['else'] = true, ['elif'] = true, ['fi'] = true,
                   ['while'] = true, ['until'] = true, ['do'] = true, ['done'] = true, ['for'] = true,
                   ['case'] = true, ['esac'] = true, ['time'] = true, ['!'] = true, ['{'] = true, ['}'] = true,
                   ['eval'] = true }

-- Builtins of the shell which run no program and read no file, which scripts can always run
local BUILTINS = { echo = true, printf = true, cd = true, test = true, ['['] = true, ['[['] = true,
                   ['true'] = true, ['false'] = true, [':'] = true, export = true, set = true, unset = true,
                   exit = true, ['return'] = true, shift = true, ['local'] = true, wait = true, ['break'] = true,
                   continue = true }

-- Programs and builtins running the program of their arguments, after their options
local WRAPPERS = { toybox = true, toolbox = true, busybox = true, env = true, exec = true, command = true,
                   nohup = true, nice = true, timeout = true, xargs = true, setsid = true }

-- The id of the fake time zone, like fakeid of timezone
function fakeid(param)
    local zone = param:getSetting('value.timezone')
    if zone == nil or zone == '' then
        zone = 'UTC'
    end
    return zone
end

-- The fake languages, normalized, without tags which are no language (und) and without repeats,
-- which LocaleList does not allow
function fakes(param)
    local locale = luajava.bindClass('java.util.Locale')
    local fake = {}
    local seen = { und = true }
    for _, item in ipairs(split(param:getSetting('value.locale') or '')) do
        local tag = locale:forLanguageTag(item):toLanguageTag()
        if not seen[tag] then
            seen[tag] = true
            fake[#fake + 1] = tag
        end
    end
    if #fake == 0 then
        fake = { 'en-US' }
    end
    return fake
end

-- The items of a comma separated list
function split(text)
    local items = {}
    for item in string.gmatch(text, '[^,%s]+') do
        items[#items + 1] = item
    end
    return items
end

-- Shell patterns of property names and their fake values for a variant, like the fields of
-- android.os.Build and the hooks of SystemProperties.get
function rules(param, variant)
    if variant == 'timezone' then
        return { { fakeid(param), { 'persist.sys.timezone' } } }
    elseif variant == 'identifiers' then
        local serial = param:getSetting('value.serial')
        if serial == nil then
            serial = 'unknown'
        end
        return { { serial, SERIAL }, { '', ICCID } }
    elseif variant == 'debugging' then
        return DEBUGGING
    elseif variant == 'locale' then
        -- The first fake language, its language and region, like PROPERTIES of locale; the region of sale empty
        local tag = fakes(param)[1]
        local locale = luajava.bindClass('java.util.Locale'):forLanguageTag(tag)
        return { { tag, { 'persist.sys.locale', 'ro.product.locale' } },
                 { locale:getLanguage(), { 'persist.sys.language', 'ro.product.locale.language' } },
                 { locale:getCountry(), { 'persist.sys.country', 'ro.product.locale.region' } },
                 { '', { 'ro.csc.country_code', 'ro.csc.countryiso_code', 'ro.csc.sales_code', 'ro.miui.region',
                         'ro.vendor.miui.cust_variant', 'ro.omc.region' } } }
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

-- sed commands leaving adb out of a list of USB functions, like mtp,adb, like systemproperties_get;
-- adb alone becomes none
function noadbcommands(pattern)
    local property = '\\[\\(' .. propertyregex(pattern) .. '\\)\\]: \\['
    return { 's/^' .. property .. 'adb\\]$/[\\1]: [none]/',
             's/^' .. property .. 'adb,\\(.*\\)\\]$/[\\1]: [\\2]/',
             's/^' .. property .. '\\(.*\\),adb,\\(.*\\)\\]$/[\\1]: [\\2,\\3]/',
             's/^' .. property .. '\\(.*\\),adb\\]$/[\\1]: [\\2]/' }
end

-- getprop without arguments lists all properties, with a name prints its value;
-- the value goes through the same sed as the list, so both are the same
function getpropfunction(rules)
    local patterns = {}
    local seds = {}
    for _, rule in ipairs(rules) do
        for _, pattern in ipairs(rule[2]) do
            patterns[#patterns + 1] = (string.gsub(pattern, '%*', '?*'))
            local commands
            if rule[1] == QUARTER then
                commands = quartercommands(pattern)
            elseif rule[1] == NOADB then
                commands = noadbcommands(pattern)
            else
                commands = { sedcommand(pattern, rule[1]) }
            end
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
                ' -e "s|^$__xlua_node\\$|localhost|"' ..
                -- Also first or last of several fields, like uname -n -r or uname -s -n
                ' -e "s|^$__xlua_node |localhost |"' ..
                ' -e "s|^\\([^ ]* \\)$__xlua_node\\$|\\1localhost|"',
        '}',
        ''
    }, '\n')
end

-- sysctl reads the files of /proc/sys: kernel.osrelease and kernel.version are the kernel like uname, by their
-- values, also with -n or -a, and kernel.random.boot_id the id of boot_id per app; the status stays the one of sysctl
function sysctlfunction(param)
    local boot = string.match(param:readFile('/proc/sys/kernel/random/boot_id') or '', '^%s*([%w%-]+)%s*$')
    local seds = ' -e "s|$(__xlua_regex "$(/system/bin/uname -v)")|#1 SMP PREEMPT|"' ..
            ' -e "s|$(__xlua_regex "$__xlua_release")|$__xlua_short|"' ..
            " -e 's|^kernel\\.hostname = .*$|kernel.hostname = localhost|'"
    local lines = { 'sysctl() {', ' __xlua_kernel' }
    if boot ~= nil then
        lines[#lines + 1] = ' __xlua_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)'
        lines[#lines + 1] = ' [ -n "$__xlua_boot" ] || __xlua_boot=__xlua_none'
        seds = seds .. ' -e "s|$(__xlua_regex "$__xlua_boot")|' .. boot .. '|"'
    end
    -- The shell writes the output itself, print of mksh, the shell of Android, has no limit of 128 KB like printf
    lines[#lines + 1] = ' __xlua_out=$(/system/bin/sysctl "$@")'
    lines[#lines + 1] = ' __xlua_rc=$?'
    lines[#lines + 1] = ' if [ -n "$__xlua_out" ]; then'
    lines[#lines + 1] = '  if [ -n "${KSH_VERSION-}" ]; then print -r -- "$__xlua_out"; else printf \'%s\\n\' "$__xlua_out"; fi |'
    lines[#lines + 1] = '   /system/bin/sed' .. seds
    lines[#lines + 1] = ' fi'
    lines[#lines + 1] = ' return $__xlua_rc'
    lines[#lines + 1] = '}'
    lines[#lines + 1] = ''
    return table.concat(lines, '\n')
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

-- The path without a link to the root folder in front, like /proc/self/root/system/xbin/su, also where the
-- links cannot be resolved (like hardware_files)
function unrooted(path)
    for _, prefix in ipairs({ '^/proc/self/root/', '^/proc/thread%-self/root/', '^/proc/%d+/root/' }) do
        path = string.gsub(path, prefix, '/')
    end
    return path
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

-- Whether a text can name a file of HIDDEN, like of /sys or /proc/net
function special(text)
    for _, part in ipairs(SPECIAL) do
        if string.find(text, part, 1, true) then
            return true
        end
    end
    return false
end

-- Whether a file does not exist for the variants, by its path or the path it leads to; dd takes if=file
function hiddenfile(file, variants)
    file = unrooted((string.gsub(file, '^if=', '')))
    if not special(file) then
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

-- Whether a script or command names a file which does not exist for the variants, also by a path relative to a
-- directory of /sys it changes to or by a pattern, which the functions resolve when they run; not paths made while
-- the script runs, like "$dir"/cid, and files of /sys which exist are read without functions
function hiddenin(text, variants)
    if not special(text) then
        return false
    end
    if hasword(text, 'cd') or string.find(text, '[%*%?%[]') then
        return true
    end
    for word in string.gmatch(text, '[^%s;&|<>()\'"`]+') do
        if hiddenfile(word, variants) then
            return true
        end
    end
    return false
end

-- The commands reading files in the text, when it names a file which does not exist for the variant, and the
-- commands reading folders recursively, when it can name a folder above such a file (see guardfunctions)
function readers(text, variant, names)
    if hiddenin(text, { [variant] = true }) then
        for _, name in ipairs(READERS) do
            if hasword(text, name) then
                names[#names + 1] = name
            end
        end
    end
    if special(text) then
        for _, name in ipairs({ 'grep', 'egrep', 'fgrep', 'ls', 'find', 'cp', 'tar' }) do
            if hasword(text, name) then
                names[#names + 1] = name
            end
        end
    end
end

-- The functions of the commands reading files: files which do not exist for the variants get the error
-- of a missing file, the others are read by the commands going on after a missing file (GOESON), which do not
-- read their input then, like cat and head would without files; the other commands fail like for the missing file
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
        -- The status of a missing file: 2 for grep, cmp and diff, 1 for the others
        '   case $__xlua_program in grep|egrep|fgrep|cmp|diff) __xlua_rc=2 ;; *) __xlua_rc=1 ;; esac',
        '  else',
        '   set -- "$@" "$__xlua_arg"',
        '  fi',
        ' done',
        ' if [ $__xlua_rc -eq 0 ]; then',
        '  "/system/bin/$__xlua_program" "$@"',
        '  return',
        ' fi',
        ' case $__xlua_program in',
        '  grep|egrep|fgrep|sed|awk) for __xlua_arg in "$@"; do',
        '    case "$__xlua_arg" in -f*|--file*) return $__xlua_rc ;; esac',
        '   done ;;',
        '  ' .. table.concat(GOESON, '|') .. ') ;;',
        '  *) return $__xlua_rc ;;',
        ' esac',
        ' if [ $# -gt 0 ]; then "/system/bin/$__xlua_program" "$@" </dev/null; fi',
        ' return $__xlua_rc',
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

-- which without the paths of su and Magisk, with the status of a program it did not find, like RootBeer runs
-- which su; type and command -v of the shell itself are not covered
function whichfunction()
    return table.concat({
        'which() {',
        ' __xlua_out=$(/system/bin/which "$@")',
        ' __xlua_rc=$?',
        ' __xlua_ifs=$IFS',
        " IFS='",
        "'",
        ' for __xlua_line in $__xlua_out; do',
        '  case "$__xlua_line" in',
        '   ' .. table.concat(HIDDEN.debugging, '|') .. ') __xlua_rc=1 ;;',
        "   *) /system/bin/printf '%s\\n' \"$__xlua_line\" ;;",
        '  esac',
        ' done',
        ' IFS=$__xlua_ifs',
        ' return $__xlua_rc',
        '}',
        '' }, '\n')
end

-- The folders of the shared storage of which storage_times fakes the times, a copy which a test compares
local FOLDERS = { 'Alarms', 'Android', 'Android/data', 'Android/media', 'Android/obb', 'Audiobooks', 'DCIM',
                  'Documents', 'Download', 'Movies', 'Music', 'Notifications', 'Pictures', 'Podcasts', 'Recordings',
                  'Ringtones' }
-- The commands which print or copy the times of files, with their long and short options doing so, true for always
local TIMES = { stat = true, ls = { '--full-time|--time-style*', '-*[lnoge]*' },
                find = { '-printf|-fprintf|-ls|-fls|-newer*|-[acm]time|-[acm]min|-exec|-execdir|-ok|-okdir' },
                date = { '--reference*', '-*r*' }, touch = { '--reference*', '-*r*' },
                cp = { '--preserve*|--archive', '-*[pa]*' }, tar = true }
local TIMERS = { 'stat', 'ls', 'find', 'date', 'touch', 'cp', 'tar' }

-- Whether a text can name the shared storage or /data/app
function timedin(text)
    return string.find(text, 'sdcard', 1, true) ~= nil or string.find(text, 'storage/', 1, true) ~= nil or
            string.find(text, 'data/app', 1, true) ~= nil
end

-- A shell pattern of a name in any case, since the shared storage finds Download also as download
function anycase(name)
    return (string.gsub(name, '%a', function(char)
        return '[' .. string.upper(char) .. string.lower(char) .. ']'
    end))
end

-- Shell patterns of the folders of storage_times and of /data/app, by the paths of the storage, /sdcard and the
-- folders of users (/storage/emulated/0, which readlink -f gives); the folders in any case
function timedpatterns()
    local patterns = { '/storage/emulated', '/data/app', '/data/app/*' }
    for _, top in ipairs({ '/sdcard', '/storage/self/primary', '/storage/emulated/[0-9]',
                           '/storage/emulated/[0-9][0-9]', '/storage/emulated/[0-9][0-9][0-9]' }) do
        patterns[#patterns + 1] = top
        for _, folder in ipairs(FOLDERS) do
            patterns[#patterns + 1] = top .. '/' .. anycase(folder)
        end
    end
    return patterns
end

-- Shell patterns of the folders above the files of HIDDEN for the variants, where recursive reads would find them:
-- the folders of the patterns, like */scsi_host/host[0-9]* and /system/xbin, /, the folders of SYSFOLDERS for the
-- files of /sys and /proc and the partitions for those of */etc
function nearpatterns(variants)
    local result = {}
    local seen = {}
    local function add(pattern)
        if not seen[pattern] then
            seen[pattern] = true
            result[#result + 1] = pattern
        end
    end
    add('/')
    for _, variant in ipairs(HIDING) do
        if variants[variant] then
            for _, pattern in ipairs(HIDDEN[variant]) do
                local dir = string.match(pattern, '^(.+)/[^/]*$')
                while dir ~= nil and dir ~= '*' do
                    add(dir)
                    dir = string.match(dir, '^(.+)/[^/]*$')
                end
            end
            if variant == 'features' or variant == 'packages' then
                for _, partition in ipairs({ '/system', '/system_ext', '/product', '/vendor', '/odm' }) do
                    add(partition)
                end
            elseif variant ~= 'debugging' then
                for _, folder in ipairs(SYSFOLDERS) do
                    add(folder)
                end
            end
        end
    end
    return result
end

-- The checks of the functions of the commands of TIMES and DEEP (see guardfunctions): __xlua_timed is true for the
-- folders storage_times fakes and /data/app, whose real times the commands would print or copy, __xlua_near for
-- the folders above the files which do not exist for the variants, both by the path, also with // and ./, and by
-- the path it leads to; __xlua_check fails like for a folder the app cannot read when an argument is such a folder
function checkfunctions(variants)
    local near = {}
    for _, pattern in ipairs(nearpatterns(variants)) do
        near[#near + 1] = (pattern == '/' and pattern or pattern .. '/')
    end
    return table.concat({
        '__xlua_clean() {',
        ' __xlua_clean=$1',
        ' while :; do',
        '  case "$__xlua_clean" in',
        '   *//*) __xlua_clean="${__xlua_clean%%//*}/${__xlua_clean#*//}" ;;',
        '   */./*) __xlua_clean="${__xlua_clean%%/./*}/${__xlua_clean#*/./}" ;;',
        '   */.) __xlua_clean=${__xlua_clean%/.} ;;',
        '   *) return 0 ;;',
        '  esac',
        ' done',
        '}',
        '__xlua_timed() {',
        ' __xlua_clean "$1"',
        ' for __xlua_path in "$__xlua_clean" "$(/system/bin/readlink -f -- "$1" 2>/dev/null)"; do',
        '  case "${__xlua_path%/}" in',
        '   ' .. table.concat(timedpatterns(), '|') .. ') return 0 ;;',
        '  esac',
        ' done',
        ' return 1',
        '}',
        '__xlua_near() {',
        ' __xlua_clean "$1"',
        ' for __xlua_path in "$__xlua_clean" "$(/system/bin/readlink -f -- "$1" 2>/dev/null)"; do',
        '  [ -n "$__xlua_path" ] || continue',
        '  case "${__xlua_path%/}/" in',
        '   ' .. table.concat(near, '|') .. ') return 0 ;;',
        '  esac',
        ' done',
        ' return 1',
        '}',
        '__xlua_check() {',
        ' __xlua_test=$1',
        ' __xlua_program=$2',
        ' shift 2',
        ' for __xlua_arg in "$@"; do',
        '  case "$__xlua_arg" in -*) continue ;; esac',
        '  if $__xlua_test "$__xlua_arg"; then',
        "   /system/bin/printf '%s: %s: Permission denied\\n' \"$__xlua_program\" \"$__xlua_arg\" >&2",
        '   return 1',
        '  fi',
        ' done',
        ' return 0',
        '}',
        ''
    }, '\n')
end

-- Lines setting the variable to 1 when an argument is one of the long or short options, or always for true
function optioncases(options, variable)
    if options == true then
        return { ' ' .. variable .. '=1' }
    end
    local result = { ' ' .. variable .. '=0', ' for __xlua_arg in "$@"; do', '  case "$__xlua_arg" in',
                     '   ' .. options[1] .. ') ' .. variable .. '=1 ;;', '   --*) ;;' }
    if options[2] ~= nil then
        result[#result + 1] = '   ' .. options[2] .. ') ' .. variable .. '=1 ;;'
    end
    result[#result + 1] = '  esac'
    result[#result + 1] = ' done'
    return result
end

-- The commands of TIMES fail for the folders of storage_times with the options printing or copying times, for
-- Use.Tracking, and those of DEEP for the folders above hidden files with the options reading recursively; else
-- they run like without these checks (runs: the functions reading files of hidingfunctions and catfunction)
function guardfunctions(names, runs, variants)
    local times = (variants['tracking'] ~= nil)
    local deep = (#hiddenpatterns(variants) > 0)
    local result = {}
    for _, name in ipairs(names) do
        local timing = (times and TIMES[name])
        local deeply = (deep and DEEP[name])
        if timing or deeply then
            result[#result + 1] = name .. '() {'
            if timing then
                for _, line in ipairs(optioncases(timing, '__xlua_t')) do
                    result[#result + 1] = line
                end
                result[#result + 1] = ' if [ $__xlua_t = 1 ]; then __xlua_check __xlua_timed ' .. name ..
                        ' "$@" || return; fi'
            end
            if deeply then
                for _, line in ipairs(optioncases(deeply, '__xlua_d')) do
                    result[#result + 1] = line
                end
                result[#result + 1] = ' if [ $__xlua_d = 1 ]; then __xlua_check __xlua_near ' .. name ..
                        ' "$@" || return; fi'
            end
            result[#result + 1] = ' ' .. (runs[name] or '/system/bin/' .. name) .. ' "$@"'
            result[#result + 1] = '}'
        end
    end
    if #result == 0 then
        return ''
    end
    return checkfunctions(variants) .. table.concat(result, '\n') .. '\n'
end

-- Whether cat of the file prints another text, with the functions of the variants; also by another path, like
-- //proc/meminfo or /proc/self/root/proc/meminfo
function catfile(file, variants)
    file = resolve(file)
    if file == '/proc/version' or file == '/proc/sys/kernel/osrelease' or file == '/proc/sys/kernel/version' then
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

-- cat of files prints the text the app reads itself, with the hooks opening files, at the start of the command,
-- for each file by the path it leads to, like //proc/meminfo or /proc/self/root/proc/meminfo; /proc/version and
-- /proc/sys/kernel/osrelease and version the kernel like uname (see kernelfunctions). Files the app cannot read keep
-- their error. Files of /sys which do not exist for the variants get the error of a missing file (see
-- hidingfunctions). The other commands reading files, and cat with options, read that text from their input in
-- place of the first such file, like grep MemTotal -, and from a descriptor of its own in place of the others, like
-- /proc/self/fd/3 (see __xlua_fakeread); dd reads its input in place of if=.
function catfunction(param, text, variants, kernel, hiding, names)
    local cases = {}
    if kernel then
        -- Apps cannot read /proc/version on recent versions
        cases[#cases + 1] = '  /proc/version) if /system/bin/cat /proc/version >/dev/null 2>&1; then __xlua_kernel;' ..
                " /system/bin/printf 'Linux version %s #1 SMP PREEMPT\\n' \"$__xlua_short\"; return 0; fi ;;"
        cases[#cases + 1] = '  /proc/sys/kernel/osrelease) if /system/bin/cat /proc/sys/kernel/osrelease >/dev/null 2>&1;' ..
                " then __xlua_kernel; /system/bin/printf '%s\\n' \"$__xlua_short\"; return 0; fi ;;"
        cases[#cases + 1] = '  /proc/sys/kernel/version) if /system/bin/cat /proc/sys/kernel/version >/dev/null 2>&1;' ..
                " then /system/bin/printf '#1 SMP PREEMPT\\n'; return 0; fi ;;"
    end
    for variant, files in pairs(FILES) do
        if variants[variant] then
            for _, file in ipairs(files) do
                local content = (namesfile(text, file) and param:readFile(file) or nil)
                if content ~= nil then
                    cases[#cases + 1] = '  ' .. file .. ") /system/bin/printf '%s' " .. quote(content) .. '; return 0 ;;'
                end
            end
        end
    end
    local read = (hiding and '__xlua_files cat' or '/system/bin/cat')
    local run = (hiding and '__xlua_files "$__xlua_reader"' or '"/system/bin/$__xlua_reader"')
    local result = {
        '__xlua_faked() {',
        ' case "$(/system/bin/readlink -f -- "$1" 2>/dev/null)" in',
        table.concat(cases, '\n'),
        ' esac',
        ' return 1',
        '}',
        '__xlua_fakeread() {',
        ' __xlua_reader=$1',
        ' shift',
        ' __xlua_source=',
        ' __xlua_fd=2',
        ' __xlua_n=$#',
        ' while [ $__xlua_n -gt 0 ]; do',
        '  __xlua_arg=$1',
        '  shift',
        '  __xlua_n=$((__xlua_n - 1))',
        '  if ! __xlua_faked "${__xlua_arg#if=}" >/dev/null; then',
        '   set -- "$@" "$__xlua_arg"',
        '  elif [ -z "$__xlua_source" ]; then',
        '   __xlua_source=${__xlua_arg#if=}',
        '   case "$__xlua_arg" in if=*) ;; *) set -- "$@" - ;; esac',
        -- shortcut: the descriptors go up to 8, more faked files in one command are read as they are
        '  elif [ $__xlua_fd -lt 8 ]; then',
        '   __xlua_fd=$((__xlua_fd + 1))',
        '   eval "__xlua_source$__xlua_fd=\\$__xlua_arg"',
        '   set -- "$@" "/proc/self/fd/$__xlua_fd"',
        '  else',
        '   set -- "$@" "$__xlua_arg"',
        '  fi',
        ' done',
        ' if [ -z "$__xlua_source" ]; then ' .. run .. ' "$@"; return; fi',
        ' __xlua_faked "$__xlua_source" | __xlua_feed 3 "$@"',
        '}',
        -- The text of each further file on its own descriptor, from 3, while the input stays the text of the first
        -- one, which 9 keeps meanwhile
        '__xlua_feed() {',
        ' __xlua_at=$1',
        ' shift',
        ' if [ $__xlua_at -gt $__xlua_fd ]; then ' .. run .. ' "$@"; return; fi',
        ' eval "{ __xlua_faked \\"\\$__xlua_source$__xlua_at\\" | { __xlua_feed $((__xlua_at + 1)) \\"\\$@\\"' ..
                ' $__xlua_at<&0 0<&9 9<&-; }; } 9<&0"',
        '}',
        'cat() {',
        ' case "$*" in',
        '  -*|*" -"*) __xlua_fakeread cat "$@"; return ;;',
        ' esac',
        ' [ $# -gt 0 ] || { ' .. read .. '; return; }',
        -- Not __xlua_rc, which __xlua_files sets for each file
        ' __xlua_catrc=0',
        ' for __xlua_cat in "$@"; do',
        '  __xlua_faked "$__xlua_cat" && continue',
        '  ' .. read .. ' "$__xlua_cat" || __xlua_catrc=1',
        ' done',
        ' return $__xlua_catrc',
        '}',
    }
    for _, name in ipairs(names) do
        if name ~= 'cat' and READER[name] then
            result[#result + 1] = name .. '() {'
            result[#result + 1] = ' __xlua_fakeread ' .. name .. ' "$@"'
            result[#result + 1] = '}'
        end
    end
    result[#result + 1] = ''
    return table.concat(result, '\n')
end

-- The commands reading files in the text, for files of which they print another text: cat always, see catfunction
function fakereaders(text, names)
    names[#names + 1] = 'cat'
    for _, name in ipairs(READERS) do
        if name ~= 'cat' and hasword(text, name) then
            names[#names + 1] = name
        end
    end
end

-- A basic regular expression matching the text exactly
function regex(text)
    return (string.gsub(text, '[%^%$%.%*%[%]\\/]', '\\%0'))
end

-- A sed leaving out of pm list permissions and permission-groups what hidden apps define (see package_resolve), like
-- the permission every app for Android 13 or later gets for its receivers, <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION,
-- in each format: names (permission:, group:, permission group:), the blocks of -f, the groups of -g with their
-- permissions up to the empty line, and the summary of -s by the labels of the groups; nil when nothing is hidden.
-- The names of all hidden apps go in one expression each, a script of sh -c is one argument of at most 128 KB.
-- shortcut: the labels of permissions of hidden apps in groups of the system stay in the summary, -s is rare.
function permissionfilter(param)
    local pm = param:getApplicationContext():getPackageManager()
    -- GET_PERMISSIONS, without the hook of Get.Applications, which leaves out the packages which stay hidden
    local installed = param:unhooked(function()
        return pm:getInstalledPackages(4096)
    end):toArray()
    local permissions = {}
    local groups = {}
    local labels = {}
    for index = 1, installed['length'] do
        local info = installed[index]
        if not param:isPackageVisible(info.packageName) then
            local defined = info.permissions
            for at = 1, (defined == nil and 0 or defined['length']) do
                permissions[#permissions + 1] = extendedregex(defined[at].name)
            end
            local grouped = info.permissionGroups
            for at = 1, (grouped == nil and 0 or grouped['length']) do
                groups[#groups + 1] = extendedregex(grouped[at].name)
                labels[#labels + 1] = extendedregex(tostring(grouped[at]:loadLabel(pm)))
            end
        end
    end
    if #permissions == 0 and #groups == 0 then
        return nil
    end
    -- Extended expressions. A block of -f ends at a header ("+ " also indented), a line of the list itself (not
    -- indented, like ungrouped:) or the empty line after a group, which stay: the hold space is x in a block to
    -- leave out, else empty
    local expressions = { "-e '/^( *\\+ |[^ ]|$)/{x;s/.*//;x;}'" }
    if #permissions > 0 then
        expressions[#expressions + 1] = '-e ' .. quote('/^ *(\\+ )?permission:(' .. table.concat(permissions, '|') ..
                ')$/{/^ *\\+ /{x;s/.*/x/;x;};d;}')
    end
    expressions[#expressions + 1] = "-e x -e '/^x$/{x;d;}' -e x"
    if #groups > 0 then
        local names = table.concat(groups, '|')
        expressions[#expressions + 1] = '-e ' .. quote('/^permission group:(' .. names .. ')$/d')
        expressions[#expressions + 1] = '-e ' .. quote('/^(\\+ )?group:(' .. names .. ')$/,/^$/d')
        expressions[#expressions + 1] = '-e ' .. quote('/^(' .. table.concat(labels, '|') .. '): /,/^$/d')
    end
    return '/system/bin/sed -r ' .. table.concat(expressions, ' ')
end

-- An extended regular expression of a text, like regex
function extendedregex(text)
    return (string.gsub(text, '[%^%$%.%*%[%]\\/%+%?%(%)%{%}|]', '\\%0'))
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
-- activities, services and receivers and resolve-activity find nothing when they find other apps than these, like
-- the queries of PackageManager, which keep the system apps too (see package_resolve). The arguments are parsed
-- like PackageManagerShellCommand does, with the short forms -l, -lf and -p of former versions.
function applicationfunctions(param, lists, cases, text)
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
    local permissions = (hasword(text, 'permissions') or string.find(text, 'permission-groups', 1, true)) and
            permissionfilter(param) or nil
    if permissions ~= nil then
        lists[#lists + 1] = '   permissions|permission-groups) __xlua_package "$@" | ' .. permissions .. ' ;;'
    end
    cases[#cases + 1] = '  path) __xlua_path "$@" ;;'
    cases[#cases + 1] = '  -[lL]|-[lL][fF]) if [ $# -eq 1 ]; then __xlua_package "$@" | __xlua_packages;' ..
            ' else __xlua_package "$@"; fi ;;'
    cases[#cases + 1] = '  -[pP]) if [ $# -eq 2 ] && ! __xlua_visible "$2"; then return 1; fi; __xlua_package "$@" ;;'
    cases[#cases + 1] = '  query-activities) __xlua_query __xlua_visible \'No activities found\' "$@" ;;'
    cases[#cases + 1] = '  query-services) __xlua_query __xlua_visible \'No services found\' "$@" ;;'
    cases[#cases + 1] = '  query-receivers) __xlua_query __xlua_visible \'No receivers found\' "$@" ;;'
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
    cases[#cases + 1] = "  has-feature) if [ -n \"${2-}\" ] && __xlua_hiddenfeature \"$2\"; then echo false; return 1; fi;" ..
            ' __xlua_package "$@" ;;'
    local result = {
        '__xlua_features() {',
        ' /system/bin/sed ' .. table.concat(seds, ' '),
        '}',
        '__xlua_hiddenfeature() {',
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
function packagefunctions(param, variants, text)
    local result = {}
    local lists = {}
    local cases = {}
    if variants['packages'] then
        result[#result + 1] = applicationfunctions(param, lists, cases, text)
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

-- Programs running a program of their arguments, with their options taking a value; xargs runs it with the words
-- of its input, not with the options which make other arguments of them
local ROUTERS = { { 'nohup' }, { 'nice', '-n' }, { 'timeout', '-s|-k' }, { 'setsid' }, { 'env', '-u|-C' },
                  { 'xargs', '-n|-L|-s|-P' } }

-- Scripts can run the commands with the programs of ROUTERS in front, with exec in front (see unexec) or in a
-- script of sh -c, which would skip the functions: these run the functions in this shell, without the priority,
-- session, time limit or environment the program would give; xargs with the other options fails like a program
-- which cannot be run, and other programs run as they are
function routefunctions(text, names)
    local result = {}
    local routers = {}
    local known = {}
    for _, name in ipairs(names) do
        known[#known + 1] = name
    end
    if hasword(text, 'sh') then
        routers[#routers + 1] = 'sh'
        known[#known + 1] = 'sh'
        result[#result + 1] = table.concat({
            'sh() {',
            ' if [ "${1-}" != -c ]; then /system/bin/sh "$@"; return; fi',
            ' if [ $# -ge 2 ]; then __xlua_script=$2; shift 2; else __xlua_script=; shift; fi',
            -- The first argument after the script is $0
            ' [ $# -eq 0 ] || shift',
            ' ( eval "$__xlua_script" )',
            '}' }, '\n')
    end
    if hasword(text, 'exec') then
        result[#result + 1] = table.concat({
            '__xlua_exec() {',
            ' __xlua_name=$1',
            ' case "$1" in /system/bin/*|/bin/*) __xlua_name=${1##*/} ;; esac',
            ' shift',
            ' "$__xlua_name" "$@"',
            ' exit',
            '}' }, '\n')
    end
    for _, router in ipairs(ROUTERS) do
        local name = router[1]
        if hasword(text, name) then
            routers[#routers + 1] = name
            local lines = { name .. '() {', ' __xlua_i=0', ' __xlua_skip=0', ' __xlua_refuse=0',
                            ' for __xlua_word in "$@"; do', '  if [ $__xlua_skip = 1 ]; then', '   __xlua_skip=0',
                            '  else', '   case "$__xlua_word" in' }
            if router[2] ~= nil then
                lines[#lines + 1] = '    ' .. router[2] .. ') __xlua_skip=1 ;;'
            end
            if name == 'xargs' then
                lines[#lines + 1] = '    -0|-d*|-I*|-i*|-a*|-E*|-e*|--null|--delimiter*|--arg-file*|--replace*)' ..
                        ' __xlua_refuse=1 ;;'
            end
            for _, line in ipairs({
                '    -*|*=*|[0-9]*) ;;',
                '    *)',
                '     __xlua_name=$__xlua_word',
                '     case "$__xlua_word" in /system/bin/*|/bin/*) __xlua_name=${__xlua_word##*/} ;; esac',
                '     case "$__xlua_name" in',
                '      ' .. table.concat(known, '|') .. ')' }) do
                lines[#lines + 1] = line
            end
            if name == 'xargs' then
                lines[#lines + 1] = '       if [ $__xlua_refuse = 1 ]; then'
                lines[#lines + 1] = "        /system/bin/printf 'xargs: %s: Permission denied\\n' \"$__xlua_word\" >&2"
                lines[#lines + 1] = '        return 126'
                lines[#lines + 1] = '       fi'
            end
            lines[#lines + 1] = '       shift $((__xlua_i + 1))'
            lines[#lines + 1] = (name == 'xargs' and '       ( set -f; "$__xlua_name" "$@" $(/system/bin/cat) )' or
                    '       "$__xlua_name" "$@"')
            for _, line in ipairs({ '       return ;;', '     esac', '     break ;;', '   esac', '  fi',
                                    '  __xlua_i=$((__xlua_i + 1))', ' done', ' /system/bin/' .. name .. ' "$@"',
                                    '}' }) do
                lines[#lines + 1] = line
            end
            result[#result + 1] = table.concat(lines, '\n')
        end
    end
    result[#result + 1] = ''
    return table.concat(result, '\n'), routers
end

-- exec in front of a command of the functions, which would skip them, runs the function and exits, like exec;
-- only in the place of a command and not exec with options, like exec -a name
function unexec(script, names)
    local known = {}
    for _, name in ipairs(names) do
        known[name] = true
    end
    local parts = {}
    local from = 1
    for at, after, word in string.gmatch(script, '()exec[ \t]+()([^%s;&|)`<>]+)') do
        local program = unquote(word)
        if string.find(program, '^/system/bin/[^/]*$') or string.find(program, '^/bin/[^/]*$') then
            program = basename(program)
        end
        if known[program] and commandplace(script, at) then
            parts[#parts + 1] = string.sub(script, from, at - 1) .. '__xlua_exec '
            from = after
        end
    end
    parts[#parts + 1] = string.sub(script, from)
    return table.concat(parts)
end

-- Whether the text contains the word, like a command in a script
function hasword(text, word)
    return string.find(' ' .. text .. ' ', '[^%w_.-]' .. word .. '[^%w_.-]') ~= nil
end

-- Whether the text can name the file of /proc, also by another path, like /proc//meminfo, /proc/self/../meminfo
-- or meminfo after cd /proc; __xlua_faked decides by the path it leads to
function namesfile(text, file)
    return string.find(text, 'proc', 1, true) ~= nil and hasword(text, basename(file))
end

-- The commands of a variant in the text, nil when there are none
function commands(text, variant)
    if variant == 'tracking' then
        local names = {}
        if string.find(text, 'getprop', 1, true) then
            names[#names + 1] = 'getprop'
        end
        local wantscat = false
        -- Also /proc/sys/kernel/osrelease and version, and version in /proc
        if string.find(text, 'uname', 1, true) or (string.find(text, 'proc', 1, true) and
                (hasword(text, 'version') or hasword(text, 'osrelease'))) then
            names[#names + 1] = 'uname'
            wantscat = true
        end
        if hasword(text, 'sysctl') then
            names[#names + 1] = 'sysctl'
        end
        for _, file in ipairs(FILES[variant]) do
            if namesfile(text, file) then
                wantscat = true
            end
        end
        if wantscat then
            fakereaders(text, names)
        end
        readers(text, variant, names)
        if timedin(text) then
            for _, name in ipairs(TIMERS) do
                if hasword(text, name) then
                    names[#names + 1] = name
                end
            end
        end
        if #names > 0 then
            return names
        end
    elseif variant == 'resources' then
        for _, file in ipairs(FILES[variant]) do
            if namesfile(text, file) then
                local names = {}
                fakereaders(text, names)
                return names
            end
        end
    elseif variant == 'network' then
        local names = {}
        for _, name in ipairs(NETWORK) do
            if hasword(text, name) then
                names[#names + 1] = name
            end
        end
        readers(text, variant, names)
        if #names > 0 then
            return names
        end
    elseif variant == 'identifiers' or variant == 'timezone' or variant == 'debugging' or variant == 'locale' then
        local names = {}
        if string.find(text, 'getprop', 1, true) then
            names[#names + 1] = 'getprop'
        end
        if variant == 'identifiers' or variant == 'debugging' then
            readers(text, variant, names)
        end
        if variant == 'debugging' then
            for _, name in ipairs({ 'su', 'which' }) do
                if hasword(text, name) then
                    names[#names + 1] = name
                end
            end
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
        readers(text, variant, names)
        if #names > 0 then
            return names
        end
    end
    return nil
end

-- The shell functions of the variants and the names of the commands; scripts get functions for toybox, toolbox
-- and the programs running others too (see routefunctions)
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
    if seen['uname'] or seen['sysctl'] then
        result = result .. kernelfunctions()
    end
    if seen['sysctl'] then
        result = result .. sysctlfunction(param)
    end
    for _, name in ipairs(NETWORK) do
        if seen[name] then
            result = result .. name .. "() {\n /system/bin/printf '%s: Permission denied\\n' " .. name ..
                    " >&2\n return 1\n}\n"
        end
    end
    if seen['su'] then
        -- Like a program which is not there, in the words of the shell
        result = result .. "su() {\n /system/bin/printf '%s: su: inaccessible or not found\\n' \"$0\" >&2\n" ..
                " return 127\n}\n"
    end
    if seen['which'] then
        result = result .. whichfunction()
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
        result = result .. catfunction(param, text, variants, seen['uname'], hiding, reading)
    end
    -- The commands checking times or recursive reads first, which run the functions reading files after that
    local runs = {}
    for _, name in ipairs(reading) do
        runs[name] = (seen['cat'] and '__xlua_fakeread ' .. name or hiding and '__xlua_files ' .. name or nil)
    end
    result = result .. guardfunctions(names, runs, variants)
    if seen['pm'] then
        result = result .. packagefunctions(param, variants, text)
    end
    if script then
        local multiplexers, more = multiplexerfunctions(text, names)
        result = result .. multiplexers
        for _, name in ipairs(more) do
            names[#names + 1] = name
        end
        local routes, routers = routefunctions(text, names)
        result = result .. routes
        for _, name in ipairs(routers) do
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

-- The index of the command in the words, after multiplexers and env, nohup, nice and timeout with their options,
-- variables and duration, and the words in front which stay: env runs the shell of the functions with the same
-- environment, the others with the same priority and time
function commandof(words)
    local at = 1
    local prefix = {}
    while at < #words do
        local name = basename(words[at])
        if MULTIPLEXERS[name] then
            at = at + 1
        elseif RUNNERS[name] then
            prefix[#prefix + 1] = words[at]
            at = at + 1
            while at < #words and (string.sub(words[at], 1, 1) == '-' or
                    (name == 'env' and string.find(words[at], '=', 1, true))) do
                local option = words[at]
                prefix[#prefix + 1] = option
                at = at + 1
                if option == '--' then
                    break
                elseif RUNNERS[name][option] and at < #words then
                    prefix[#prefix + 1] = words[at]
                    at = at + 1
                end
            end
            if name == 'timeout' and at < #words then
                prefix[#prefix + 1] = words[at]
                at = at + 1
            end
        else
            break
        end
    end
    return at, prefix
end

-- The index of the first operand of a shell after its options, like sh -c script or sh -ec script,
-- whether the operand is a script (option c) and whether the shell reads its commands from its input (s or i)
function shelloperand(words, at)
    local index = at + 1
    local script = false
    local input = false
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
            if string.sub(word, 1, 1) == '-' and string.find(word, '[si]', 2) then
                input = true
            end
            index = index + 1
        else
            break
        end
    end
    return index, script, input
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
    -- An assignment in front of a command, also with quotes or $ in its value; a value with a blank leaves a
    -- word which counts as a program, so the command is refused rather than run
    if RESERVED[word] or string.match(word, '^[%a_][%w_]*=') then
        return commandplace(text, first)
    end
    return false
end

-- Scripts can run the commands with their path, which would skip the functions; only paths in the place of
-- a command are replaced, also when quoted, also in parts, others are arguments like [ -x /system/bin/getprop ],
-- which stay. Also paths with repeated slashes or ./, like /system/bin//getprop or /system/./bin/getprop.
function unpath(script, name)
    local parts = {}
    local from = 1
    for at, path, after in string.gmatch(script, '()(/[^%s;&|)`<>]*)()') do
        local clean = unquote(path)
        repeat
            local last = clean
            clean = string.gsub(string.gsub(clean, '/%./', '/'), '//+', '/')
        until clean == last
        if (clean == '/system/bin/' .. name or clean == '/bin/' .. name) and
                string.find(string.sub(script, after, after), '^[%s;&|)`<>]?$') and commandplace(script, at) then
            -- The quotes of the path stay after the name, so quoted parts still end: /system/bin/"getprop" becomes
            -- getprop"" and "/system/bin/getprop" becomes "getprop"
            parts[#parts + 1] = string.sub(script, from, at - 1) .. name .. string.gsub(path, '[^"\']', '')
            from = after
        end
    end
    parts[#parts + 1] = string.sub(script, from)
    return table.concat(parts)
end

-- date prints the time in the time zone of its environment, which is the real one,
-- unless the app sets one; whether the fake time zone was set
function settimezone(param, builder)
    local environment = builder:environment()
    if environment:get('TZ') ~= nil then
        return false
    end
    environment:put('TZ', fakeid(param))
    param:putValue('shell.timezone', fakeid(param), builder)
    return true
end

-- A word of a script without its quotes
function unquote(word)
    return (string.gsub(word, '["\'\\]', ''))
end

-- The program a command runs, after wrappers like exec, env and busybox with their variables and the duration
-- of timeout, and its index in the words; nil when it is not known before the command runs, like $cmd, or when
-- a wrapper has options other than -i and --, of which the value could be taken as the program, like env -u getprop id
function programin(words, at)
    while true do
        local word = unquote(words[at])
        if word == '' or string.find(word, '[$`]') or not systemprogram(word) then
            return nil, at
        end
        local name = basename(word)
        if not WRAPPERS[name] then
            return name, at
        end
        local next = at + 1
        while next <= #words and (string.find(words[next], '^[-+]') or string.find(words[next], '^[%a_][%w_]*=') or
                (name == 'timeout' and string.find(words[next], '^[%d.]+[smhd]?$'))) do
            local option = unquote(words[next])
            if string.find(option, '^[-+]') and option ~= '-i' and option ~= '-' and option ~= '--' then
                return nil, at
            end
            next = next + 1
        end
        if next > #words then
            -- Like exec with redirections only, or env printing the environment
            return name, at
        end
        at = next
    end
end

-- Whether a path is of a partition of the system, which the app cannot write, without . or .. to leave it
function systempath(path)
    -- /sbin is in PATH up to Android 9, on the root file system
    for _, dir in ipairs({ '/system/', '/system_ext/', '/product/', '/vendor/', '/odm/', '/apex/', '/bin/', '/sbin/' }) do
        if string.sub(path .. '/', 1, #dir) == dir then
            return not string.find(path .. '/', '/%.%.?/')
        end
    end
    return false
end

-- Whether a program is one of the system: by its name, which the shell finds in the folders of PATH (see loaders),
-- or by a path of the system; not a file of the app with an allowed name, like ./getprop
function systemprogram(word)
    return not string.find(word, '/', 1, true) or systempath(word)
end

-- Whether the app chose where programs are found or which code they load, so it could run its own files by an
-- allowed name: PATH or LD_ variables in the command, also quoted in parts, like PATH=files getprop or
-- export "PA""TH=files", or in the environment of the builder, which the app can change, also for its own process,
-- or PATH of the process, which finds the program (Os.setenv); an empty folder of PATH is the working directory
function loaders(builder, line)
    local plain = unquote(line)
    if hasword(plain, 'PATH') or string.find(' ' .. plain, '[^%w_]LD_[%w_]') then
        return true
    end
    local function foreign(path)
        if path == nil then
            return false
        end
        if string.find(':' .. path .. ':', '::', 1, true) then
            return true
        end
        for dir in string.gmatch(path, '[^:]+') do
            if not systempath(dir) then
                return true
            end
        end
        return false
    end
    local environment = builder:environment()
    local ok, own = pcall(function()
        return luajava.bindClass('android.system.Os'):getenv('PATH')
    end)
    return foreign(environment:get('PATH')) or (ok and foreign(own)) or
            environment:get('LD_PRELOAD') ~= nil or environment:get('LD_LIBRARY_PATH') ~= nil
end

-- The programs a script runs: the words in the place of a command (see commandplace) with the words of their
-- command, but not builtins like echo; nil when one is not known before the script runs. Separators in quotes
-- are taken as separators, which can refuse a script running only allowed programs, not run another program.
function scriptprograms(text)
    local tokens = {}
    for at, word in string.gmatch(text, '()([^%s;&|()`<>]+)') do
        tokens[#tokens + 1] = { at = at, word = word }
    end
    local programs = {}
    for index, token in ipairs(tokens) do
        local word = unquote(token.word)
        if commandplace(text, token.at) and not KEYWORDS[word] and not string.find(word, '^[%a_][%w_]*=') then
            -- The words of the command: the next tokens with only blanks in front, also continued lines
            local words = { token.word }
            local last = token
            for next = index + 1, #tokens do
                local gap = string.sub(text, last.at + #last.word, tokens[next].at - 1)
                if not string.find(gap, '^[ \t]*$') and not string.find(gap, '^[ \t]*\\\n[ \t]*$') then
                    break
                end
                words[#words + 1] = tokens[next].word
                last = tokens[next]
            end
            local program = programin(words, 1)
            if program == nil then
                return nil
            end
            if not BUILTINS[program] then
                programs[#programs + 1] = program
            end
        end
    end
    return programs
end

-- The programs a command of the app runs, nil when one is not known before it runs: the program, after
-- wrappers, else the programs of the script of a shell (sh -c), or the script file of the system a shell runs
-- (sh /system/bin/pm); a shell reading the commands of its input (sh, sh -s) is a program of its own, and
-- other script files are not known, the app can write them, like ./getprop
function commandprograms(words)
    local program, at = programin(words, 1)
    if program == nil or not SHELLS[program] then
        return (program and { program })
    end
    local operand, option, input = shelloperand(words, at)
    if operand > #words or input then
        return { program }
    elseif option then
        return scriptprograms(words[operand])
    elseif string.find(words[operand], '^/system/') and not string.find(words[operand], '/%.%.?/') then
        return { basename(words[operand]) }
    end
    return nil
end

-- Only commands running allowed programs can run, like the setting commands.allowed: getprop,uname, or the
-- commands matching the regular expression of commands.regex as a whole; others fail like a program which
-- cannot be run. The command of the app is checked, also when the hooks of other variants changed it already.
function refuse(param)
    local builder = param:getThis()
    local command = param:getValue('shell.command', builder) or builder:command()
    if command == nil then
        return false
    end
    local words = wordsof(command:toArray())
    if words == nil or #words == 0 then
        return false
    end
    local line = table.concat(words, ' ')

    local expression = param:getSetting('commands.regex')
    if expression ~= nil and expression ~= '' then
        -- An invalid expression matches nothing
        local pattern = luajava.bindClass('java.util.regex.Pattern')
        local valid, matches = pcall(function()
            return pattern:matches(expression, line)
        end)
        if valid and matches then
            return false
        end
    end

    local allowed = {}
    for name in string.gmatch(param:getSetting('commands.allowed') or '', '[^%s,]+') do
        allowed[basename(name)] = true
    end
    local programs = commandprograms(words)
    local refused = (programs == nil or loaders(builder, line))
    for _, program in ipairs(programs or {}) do
        if not allowed[program] then
            refused = true
        end
    end
    if not refused then
        return false
    end

    local exception = luajava.bindClass('java.io.IOException')
    param:setResult(luajava.new(exception, 'Cannot run program "' .. words[1] .. '": error=13, Permission denied'))
    return true, line
end

function before(hook, param)
    -- Hook names are like Function/variant
    local variant = string.match(hook:getName(), '/(.*)$')
    if variant == nil then
        variant = 'tracking'
    end
    if variant == 'commands' then
        return refuse(param)
    end

    -- The command of the app in the builder, also when the hook of another variant changed it already
    local builder = param:getThis()
    local command = param:getValue('shell.command', builder)
    if command == nil then
        command = builder:command()
    end
    if command == nil then
        return false
    end
    local words = wordsof(command:toArray())
    if words == nil or #words == 0 then
        return false
    end
    local line = table.concat(words, ' ')
    -- Relative paths are files of the working directory of the builder, like cat cid in a directory of /sys:
    -- the text changes to it first, like a script would (see hiddenin)
    local dir = builder:directory()
    local text = (dir and 'cd ' .. dir:getPath() .. '; ' .. line or line)
    local function file(word)
        word = string.gsub(word, '^if=', '')
        return ((dir and string.sub(word, 1, 1) ~= '/') and dir:getPath() .. '/' .. word or word)
    end

    -- The commands and files are found in the text without quotes, which the shell removes before it runs them,
    -- like g''etprop or boot"_id"
    local plain = unquote(text)

    local zone = (variant == 'timezone' and hasword(plain, 'date') and settimezone(param, builder))
    local names = commands(plain, variant)
    if names == nil then
        return zone, line
    end

    local at, prefix = commandof(words)
    local program = basename(words[at])
    if variant == 'debugging' and program == 'su' then
        -- Like a program which is not there
        local exception = luajava.bindClass('java.io.IOException')
        param:setResult(luajava.new(exception, 'Cannot run program "' .. words[1] .. '": error=2, No such file or directory'))
        return true, line
    end
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
                if READER[name] and not TIMES[name] and not DEEP[name] then
                    -- Commands reading files which exist run as they are
                    for index = at + 1, #words do
                        if catfile(file(words[index]), own) or hiddenfile(file(words[index]), own) then
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
    local list = param:getValue('shell.variants', builder)
    if list ~= nil then
        for name in string.gmatch(list, '[^,]+') do
            variants[name] = true
        end
    end
    variants[variant] = true

    local prelude, functionnames = functions(param, plain, variants, script ~= nil)
    local fake = luajava.newInstance('java.util.ArrayList')
    if script ~= nil then
        local text = words[script]
        for _, name in ipairs(functionnames) do
            text = unpath(text, name)
        end
        text = unexec(text, functionnames)
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
    -- The builder gets its own command back after the start, apps can start it again; the variant commands
    -- checks this original command too (see refuse)
    param:putValue('shell.command', command, builder)
    param:putValue('shell.variants', table.concat(all, ','), builder)
    builder:command(fake)
    return true, line
end

function after(hook, param)
    -- The filter changes nothing
    if string.match(hook:getName(), '/commands$') then
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
