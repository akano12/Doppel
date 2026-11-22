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

-- Files of /sys name the hardware of the device, and Samsung phones let apps read files which name the device:
-- the id of the chip (/sys/devices/system/chip-id/unique_id, which stays until the device is scrapped), the id
-- of the SD card or the storage (/sys/block/mmcblk0/device/cid and serial, /sys/class/sec/sdinfo/data), the serial
-- number of UFS storage (/sys/class/scsi_host/host0/unique_number on Samsung, the serial_number of its string
-- descriptors on other devices) with its manufacturer and model, the
-- calibration of the light sensor, the files of the touchscreen (/sys/class/sec/tsp) and of the fingerprint
-- sensor (/sys/class/fingerprint/fingerprint), the chip and storage models (see FILES), the folders of VPN
-- interfaces and su, Magisk and Xposed and the property files with the type of the build (Read.Debugging). For
-- restricted apps the files do not exist: they cannot be opened, read, listed or found, with java.io, android.system.Os
-- and java.nio.file. The paths are resolved first,
-- so the links of /sys do not lead to the files either, like /sys/bus/mmc/devices/mmc0:0001/cid.
-- Each variant hides the files of its group, and folders listed with java.nio.file leave the names of hidden files out.
-- The feature files of the system which stay are read without the features the group hides (see FEATURES), but by
-- FileInputStream and FileReader of Android 8.1 and 9 (see featuretext).
-- A SecureDirectoryStream of java.nio, which Files.newDirectoryStream gives, opens, removes and moves the files of its
-- folder and reads their attributes by the descriptor of the folder and their name (openat, unlinkat, renameat and
-- fstatat, see atpath), which only the groups which hook those calls cover. Not covered is native code. FileInputStream,
-- which FileReader opens too, and FileOutputStream of Android 8.1 and 9 open natively (open0), not by the hooks of open;
-- Read.VPN, Read.Debugging, Read.Biometrics and Get.Applications hook open0 to hide their files (see before), but not
-- to rewrite them (see tables, featuretext and packagetext). The property areas of /dev/__properties__ have the fake
-- values of the properties, see systemproperties_get.
-- shortcut: the other groups do not hook open0, so on Android 8.1 and 9 FileInputStream and FileReader still open their
-- files, add the hooks of Read.VPN to them if apps read the files that way.
-- shortcut: a SecureDirectoryStream of a folder in the one of the stream (its newDirectoryStream) has no filter, so it
-- lists the names of hidden files, which openat then does not find, hook its constructor to add a filter if apps do so.
-- For Use.Tracking the tables of the process which every app reads of itself name the hardware too (see tables): the
-- maps (/proc/self/maps and smaps) leave out the mappings of the libraries of the vendor, like the GPU driver and the
-- graphics HAL of the board (android.hardware.graphics.mapper@3.0-impl-ranchu.so), and of the device of the GPU; the
-- mount tables (/proc/mounts, mountinfo and mountstats) and the names of the file stores of java.nio, which reads
-- /proc/mounts natively (see storename), name the partitions by /dev/block/by-name, not by the storage controller of
-- the chip, like /dev/block/platform/soc/1d84000.ufshc/by-name.
-- shortcut: the mount points of makers, like /efs of Samsung, and the numbers of the block devices, which tell UFS
-- from eMMC, stay in the mount tables; leave them out once the mounts of AOSP of Android 8.1 to 17 are known.
-- For Read.Debugging the mount tables leave out the mounts of Magisk (see magiskmount), like its tmpfs on
-- /debug_ramdisk, where its files do not exist.

-- The files of each group, as patterns of the path; a pattern ending in / covers the files of a folder
local FILES = {
    ['Read.Identifiers'] = {
        '/chip%-id/unique_id$', '/chip%-id/lot_id$', '/chip%-id/lot_id2$',
        '/mmc%d+:%x+/cid$', '/mmc%d+:%x+/serial$', '/sec/sdinfo/data$',
        '/scsi_host/host%d+/unique_number$', '/string_descriptors/serial_number$', '/soc%d+/serial_number$' },
    -- The folder of the chip of the bus of systems on a chip (/sys/devices/soc0, which Qualcomm lets apps read
    -- until Android 11, its soc_id and family later), the model, vendor and revision of SCSI storage, like UFS,
    -- (/sys/block/sda/device, a link to the device, h:c:t:l), the camera modules of Samsung (/sys/class/camera)
    -- and the input devices and storage of /proc
    ['Use.Tracking'] = {
        '/chip%-id$', '/chip%-id/', '/soc%d+$', '/soc%d+/',
        '/mmc%d+:%x+/date$', '/mmc%d+:%x+/manfid$', '/mmc%d+:%x+/oemid$', '/mmc%d+:%x+/name$',
        '/scsi_host/host%d+/man_id$', '/string_descriptors/manufacturer_name$',
        '/string_descriptors/product_name$', '/string_descriptors/oem_id$',
        '/block/sd%a+/device/model$', '/block/sd%a+/device/vendor$', '/block/sd%a+/device/rev$',
        '/%d+:%d+:%d+:%d+/model$', '/%d+:%d+:%d+:%d+/vendor$', '/%d+:%d+:%d+:%d+/rev$',
        '/sensors/light_sensor/light_cal$', '/sec/tsp$', '/sec/tsp/', '/class/camera/', '/virtual/camera/',
        -- The speeds, caches and idle states of the processors, like cpu7/cpufreq/cpuinfo_max_freq, and the features
        -- and flaws of their cores: the cpufreq and cpuidle folders of each processor like a kernel without the drivers
        -- of the speeds and idle states, and its regs like older kernels without them; of the folders of the processors
        -- cpu0 to cpu7 stay (see cpu_count), and a device with fewer processors keeps the ones it has, since folders
        -- cannot be made up. The layout (topology) and capacity of each, which every kernel of arm64 has, stay and are
        -- those of 8 equal cores (see cpu_count), and the uevent of each processor and modalias stay with the
        -- instructions of a common core (see cpuinfo).
        -- shortcut: cpufreq and cpuidle of /sys/devices/system/cpu itself are missing, which the kernel makes whenever
        -- it is built with them, without a driver too, and so is vulnerabilities, which kernels of arm64 from 5.x
        -- always have, so no stock kernel of arm64 with these answers like this; show them as empty folders and the
        -- flaws as a kernel without known flaws if apps look for them.
        -- shortcut: the caches of the processors stay hidden, which kernels of arm64 and x86 always list, like
        -- cache/index0; show them as a kernel without their sizes does if apps look for the folder.
        '/devices/system/cpu/cpu[89]$', '/devices/system/cpu/cpu[89]/', '/devices/system/cpu/cpu%d%d+$',
        '/devices/system/cpu/cpu%d%d+/',
        '/devices/system/cpu/cpufreq$', '/devices/system/cpu/cpufreq/', '/devices/system/cpu/cpuidle$',
        '/devices/system/cpu/cpuidle/', '/devices/system/cpu/vulnerabilities$', '/devices/system/cpu/vulnerabilities/',
        '/devices/system/cpu/cpu%d+/cpufreq$', '/devices/system/cpu/cpu%d+/cpufreq/',
        '/devices/system/cpu/cpu%d+/cpuidle$', '/devices/system/cpu/cpu%d+/cpuidle/',
        '/devices/system/cpu/cpu%d+/cache$', '/devices/system/cpu/cpu%d+/cache/',
        '/devices/system/cpu/cpu%d+/regs$', '/devices/system/cpu/cpu%d+/regs/',
        -- The model, clocks and load of the GPU: of Samsung and newer Qualcomm kernels (/sys/kernel/gpu, like
        -- gpu_model and gpu_clock), of Adreno (/sys/class/kgsl/kgsl-3d0, a link into its device, with devfreq)
        -- and of Mali (the device, like /sys/devices/platform/18500000.mali, and /sys/class/misc/mali0)
        '/kernel/gpu$', '/kernel/gpu/', '/class/kgsl$', '/class/kgsl/', '/kgsl/kgsl%-3d0$', '/kgsl/kgsl%-3d0/',
        'qcom,kgsl%-3d0$', 'qcom,kgsl%-3d0/', '%.mali$', '%.mali/', '/misc/mali0$', '/misc/mali0/',
        '/proc/scsi/', '/proc/bus/input/devices$',
        -- The certificates the user added, like of a VPN or an ad blocker, by name and content; the store of
        -- certificates of the app reads them too, but does not list them (see truststore)
        '^/data/misc/user/%d+/cacerts%-added$', '^/data/misc/user/%d+/cacerts%-added/',
        '^/data/misc/user/%d+/cacerts%-removed$', '^/data/misc/user/%d+/cacerts%-removed/' },
    ['Read.Biometrics'] = {
        '/fingerprint/fingerprint$', '/fingerprint/fingerprint/', '/sec/tsp/fod_info$', '/sec/tsp/fod_pos$' },
    -- The files of /proc/net, a link to /proc/self/net, list the addresses, routes, neighbours and hardware addresses
    -- of the interfaces and the addresses of the sockets, which SELinux lets all apps read on Android 9 and before, and
    -- from Android 10 not the apps the user installs; they stay there, but reading them is denied (see denied)
    ['Read.Network'] = { '/proc/net/', '/proc/%d+/net/', '/proc/%d+/task/%d+/net/', '/proc/self/net/',
                         '/proc/thread%-self/net/' },
    -- The folders of the network interfaces of VPNs (see isvpninterface), of which apps could tell they exist on
    -- Android 8.1; SELinux denies apps the folders on later versions and listing them on all. The files of /proc/net,
    -- which name the interface of a VPN too, like tun0 in /proc/net/dev, are denied like for Read.Network (see before),
    -- and the names of the interfaces in there and in /proc/sys/net, which apps can list and find on Android 9 and
    -- before, do not exist: the file of each interface with IPv6 in dev_snmp6, its folders of xt_qtaguid/iface_stat
    -- and of the settings of IPv4 and IPv6 (conf and neigh).
    -- shortcut: Read.VPN hooks fewer calls than the other groups, so File.lastModified, createNewFile, mkdir, readlink
    -- and the calls of a SecureDirectoryStream by the descriptor of its folder (openat, fstatat) still find the names,
    -- add their hooks of the other groups to Read.VPN if apps probe the interfaces of VPNs like that
    ['Read.VPN'] = { '^/sys/class/net/([^/]+)', '^/sys/devices/virtual/net/([^/]+)',
                     '^/proc/net/dev_snmp6/([^/]+)', '^/proc/[^/]+/net/dev_snmp6/([^/]+)',
                     '^/proc/[^/]+/task/%d+/net/dev_snmp6/([^/]+)', '^/proc/net/xt_qtaguid/iface_stat/([^/]+)',
                     '^/proc/[^/]+/net/xt_qtaguid/iface_stat/([^/]+)',
                     '^/proc/[^/]+/task/%d+/net/xt_qtaguid/iface_stat/([^/]+)',
                     '^/proc/sys/net/ipv[46]/conf/([^/]+)', '^/proc/sys/net/ipv[46]/neigh/([^/]+)' },
    -- su of root managers, in the folders of programs of the system and the ones they used to install to,
    -- and the programs and folders of Magisk; the folders /data/adb and /debug_ramdisk themselves stay, which a device
    -- without root has too: init.usb.rc of AOSP makes /data/adb (oreo-mr1-release), and /debug_ramdisk is an empty
    -- folder of the root from Android 10 (rootdir/Android.mk of system/core, android10-release).
    -- shortcut: from Android 11 /debug_ramdisk is the tmpfs of Magisk, which can be written, not the empty folder of the
    -- read-only root, so making and removing names in it (EACCES, ENOENT) and its statvfs differ from a device without
    -- root (EROFS); answer for it like for the root if apps probe that.
    ['Read.Debugging'] = {
        '^/system/bin/su$', '^/system/xbin/su$', '^/sbin/su$', '^/su$', '^/su/', '^/system/bin/failsafe/su$',
        '^/system/sd/xbin/su$', '^/system/bin/%.ext/su$', '^/system/usr/we%-need%-root/su$', '^/data/local/su$',
        '^/data/local/bin/su$', '^/data/local/xbin/su$', '^/cache/su$', '^/dev/su$', '^/vendor/bin/su$',
        '^/vendor/xbin/su$', '^/product/bin/su$', '^/system_ext/bin/su$', '^/odm/bin/su$',
        '^/system/app/Superuser%.apk$', '^/system/app/Superuser/', '^/system/app/SuperSU/',
        '^/system/bin/magisk', '^/system/bin/resetprop$', '^/system/bin/supolicy$', '^/sbin/magisk', '^/sbin/%.magisk',
        '^/debug_ramdisk/', '^/data/adb/',
        -- The files Xposed of rovo89 installs in /system on Android 8.1 (flash-script.sh of XposedTools): its
        -- properties, its jar, its app_process next to the original, its library of ART, the backups of the files of
        -- ART it replaced (.orig.gz, or .no_orig where there was none) and the odex files it moved aside; the links
        -- app_process32 and app_process64 to its app_process stay (see matched). Riru and EdXposed, modules of Magisk
        -- (customize.sh and post-fs-data.sh of Riru v19 to v23 and of EdXposed 0.2.9 to master), which Magisk mounts
        -- in /system: the libraries of Riru, from v22 libriru.so, libriruhide.so and libriruloader.so, up to v20 the
        -- copy of libmemtrack.so it replaces, libmemtrack_real.so, and up to v21 its folder /data/misc/riru, also of the
        -- modules; the libraries of Riru modules, like libriru_edxp.so, and the files of EdXposed up to 0.4.6.2, its jars
        -- and dex files in /system/framework, like edxp.jar, its libraries .edxp.so and its list of libraries in
        -- /system/etc. Later versions of both name these files randomly, in /data/adb and a folder of /data/misc.
        -- shortcut: zygote puts XposedBridge.jar in the environment CLASSPATH and maps it and libxposed_art.so into every
        -- app, so System.getenv and /proc/self/maps still name them, the folders oat of the moved odex files still list
        -- them, Riru v22 and later set ro.dalvik.vm.native.bridge to libriruloader.so, and the copy of libmemtrack.so of
        -- Riru v21 has a random name; leave them out there too if apps look for Xposed that way.
        '^/system/xposed%.prop$', '^/system/framework/XposedBridge%.jar$', '^/system/bin/app_process%d*_xposed$',
        '^/system/bin/app_process%d*_original$', '^/system/lib%d*/libxposed_art%.so$', '^/system/bin/[^/]+%.orig%.gz$',
        '^/system/bin/[^/]+%.no_orig$', '^/system/lib%d*/[^/]+%.orig%.gz$', '^/system/lib%d*/[^/]+%.no_orig$',
        '^/system/.+%.odex%.gz%.xposed$', '^/vendor/.+%.odex%.gz%.xposed$', '^/system/lib%d*/libriru[^/]*%.so$',
        '^/system/lib%d*/libmemtrack_real%.so$', '^/data/misc/riru$', '^/data/misc/riru/', '^/system/lib%d*/[^/]+%.edxp%.so$',
        '^/system/framework/edxp%.jar$', '^/system/framework/edconfig%.jar$', '^/system/framework/eddalvikdx%.jar$',
        '^/system/framework/eddexmaker%.jar$', '^/system/framework/edxposed%.dex$', '^/system/framework/edconfig%.dex$',
        '^/system/framework/eddalvikdx%.dex$', '^/system/framework/eddexmaker%.dex$',
        '^/system/etc/public%.libraries%-edxp%.txt$' },
    -- The files of the features, libraries and system apps of the device (/system/etc/permissions, also of /vendor,
    -- /product and the others, and sysconfig), like com.samsung.feature.*, which apps can read; the folders of
    -- /system/etc stay, with the files every Android has (see BASE)
    ['Read.Features'] = {},
    ['Get.Applications'] = {},
    ['Get.Sensors'] = {},
    ['Use.Camera'] = {},
    ['Record.Audio'] = {},
}

-- The folders etc of the system, so a copy of a Linux system an app unpacks in its own folder keeps its files:
-- of the root, of each partition, like /vendor/etc, also /system/product/etc, and of APEX modules; and the folder
-- /odm/etc leads to on Android 9 and later without an odm partition, /vendor/odm/etc (/system/vendor/odm/etc where
-- /vendor is a link to /system/vendor), which is its canonical path then
local ETC = { '^/etc/', '^/[%w_]+/etc/', '^/system/[%w_]+/etc/', '^/apex/[^/]+/etc/', '^/vendor/odm/etc/',
              '^/system/vendor/odm/etc/' }

for group, names in pairs({
    -- The codecs of the chip by name (see media_codecs), like /vendor/etc/media_codecs_sm8550_vendor.xml, the
    -- properties of the partitions, of which those of /product and /system_ext can be read and name the device, and
    -- the file systems of the device, named after its hardware, like fstab.qcom; the properties and services of the
    -- vendor in its SELinux contexts (nonplat_ on Android 8.1) and the configuration of the display panel
    ['Use.Tracking'] = { 'media_codecs[^/]*%.xml$', 'build%.prop$', 'fstab%.[^/]*$', 'selinux/vendor_[^/]*_contexts$',
                         'selinux/nonplat_[^/]*_contexts$', 'displayconfig$', 'displayconfig/' },
    ['Read.Features'] = { 'permissions$', 'permissions/', 'sysconfig$', 'sysconfig/' },
    -- The properties of the partitions for Read.Debugging too, which hold the type and tags of the build, like
    -- ro.build.type=userdebug and its fingerprint, and the default properties of init, which hold ro.debuggable
    -- (prop.default of /system/etc where the properties of the vendor are split off, build/make core/Makefile)
    ['Read.Debugging'] = { 'build%.prop$', 'prop%.default$' },
    -- The permissions of the system apps, a file per app, like privapp-permissions-com.samsung.android.dialer.xml, but
    -- of the platform (see matched), and the permissions the system grants apps by default, of which a device without
    -- them has no folder; files of other names are of privappfile
    ['Get.Applications'] = { 'permissions/privapp%-permissions%-[^/]*$', 'default%-permissions$', 'default%-permissions/' },
    -- The files of the sensor features, a file per feature, like android.hardware.sensor.gyroscope.xml, from which
    -- the system has the features hasSystemFeature answers (see features); the other feature files stay, without
    -- the sensor features (see FEATURES). Android 10 and later also read a folder for the SKU of the device in there,
    -- like /odm/etc/permissions/sku_X (in /vendor too from Android 11, in /product from Android 15), no deeper one.
    ['Get.Sensors'] = { 'permissions/android%.hardware%.sensor%.[^/]*$',
                        'permissions/sku_[^/]+/android%.hardware%.sensor%.[^/]*$' },
    -- The same for the camera features, like android.hardware.camera.front.xml, of which AOSP has every one start with
    -- android.hardware.camera and declare only camera features, and for the microphone, which AOSP declares in the
    -- core files of the kind of device, like handheld_core_hardware.xml, a file of its own being one of a maker.
    -- shortcut: a SecureDirectoryStream opens and stats these files by their name (openat, fstatat), which only the
    -- hooks of Read.Identifiers cover, add those hooks to the groups of the feature files if apps read them that way
    ['Use.Camera'] = { 'permissions/android%.hardware%.camera[^/]*$',
                       'permissions/sku_[^/]+/android%.hardware%.camera[^/]*$' },
    ['Record.Audio'] = { 'permissions/android%.hardware%.microphone%.xml$',
                         'permissions/sku_[^/]+/android%.hardware%.microphone%.xml$' },
    -- The same for the biometric features, the files of AOSP of the fingerprint sensor (from Android 8.1) and of face
    -- unlock (from Android 10), and of iris unlock named the same way
    ['Read.Biometrics'] = { 'permissions/android%.hardware%.fingerprint%.xml$',
                            'permissions/android%.hardware%.biometrics%.face%.xml$',
                            'permissions/android%.hardware%.biometrics%.iris%.xml$',
                            'permissions/sku_[^/]+/android%.hardware%.fingerprint%.xml$',
                            'permissions/sku_[^/]+/android%.hardware%.biometrics%.face%.xml$',
                            'permissions/sku_[^/]+/android%.hardware%.biometrics%.iris%.xml$' } }) do
    for _, name in ipairs(names) do
        for _, etc in ipairs(ETC) do
            table.insert(FILES[group], etc .. name)
        end
    end
end
-- The properties at the root of a partition too, like /system/build.prop and /vendor/build.prop, and fstab at the
-- root of devices of Android 8.1 and 9 without system as root, and for Read.Debugging the default properties of
-- init there, /default.prop, which leads to /system/etc/prop.default where that is made, and /prop.default, and those
-- init reads at the root of /vendor and /odm on Android 8.1 to 10, like /vendor/default.prop
for _, group in ipairs({ 'Use.Tracking', 'Read.Debugging' }) do
    table.insert(FILES[group], '^/[%w_]+/build%.prop$')
    table.insert(FILES[group], '^/system/[%w_]+/build%.prop$')
end
table.insert(FILES['Use.Tracking'], '^/fstab%.[^/]*$')
table.insert(FILES['Read.Debugging'], '^/default%.prop$')
table.insert(FILES['Read.Debugging'], '^/prop%.default$')
table.insert(FILES['Read.Debugging'], '^/[%w_]+/default%.prop$')
-- The HALs of the vendor in the manifests of vintf of its partitions, like vendor.qti.hardware.*, also in fragments from
-- Android 10 and at the root of the partitions on Android 8.1, and of /odm also by the path it leads to without an odm
-- partition (see ETC). The manifest of the framework stays, of /system and its link /etc, which every device has with
-- the HALs of AOSP, and so do those of /system_ext and /product.
-- shortcut: the fragments of makers in /system_ext and /product, like vendor.qti.hardware.* of Qualcomm, stay; hide
-- them once it is known which devices have the folder of fragments there. The same for the fragments of the APEXes of
-- the vendor, which Android 14 and later read from /apex/<name>/etc/vintf for the APEXes of /vendor/apex, and for
-- the manifests Android 9 and later read from /odm/etc itself, manifest.xml and manifest_<sku>.xml; hide them once the
-- names of the vendor APEXes are known and the filter lets manifest_ names in /odm/etc through.
for _, partition in ipairs({ '/vendor', '/odm', '/system/vendor', '/vendor/odm', '/system/vendor/odm' }) do
    for _, name in ipairs({ '/etc/vintf/manifest[^/]*%.xml$', '/etc/vintf/manifest$', '/etc/vintf/manifest/',
                            '/manifest%.xml$' }) do
        table.insert(FILES['Use.Tracking'], '^' .. partition .. name)
    end
end

-- The folders of the apps of the system and of their overlays, of each partition, also by the links of /system to the
-- partitions, like SettingsProvider__auto_generated_rro_product.apk of /product/overlay, and of the APEX modules,
-- which Android 11 and later install apps from too (see APEX_APPS): for Get.Applications they list without names and
-- stat gives the link count of an empty folder (see linkcount), but for the folder of the app itself, since it can be
-- an app of the system too, which stays listed and counted (see ownname). /data/app lists and counts only the folder of
-- the app too, since each app the user installed adds one; the folders of hidden apps in it do not exist on Android
-- 8.1 to 10 (see APP_FOLDERS), and from Android 11 they have random names.
-- shortcut: the names in these folders, like /system/priv-app/SettingsProvider, and the folders in /data/app of
-- updated system apps on Android 8.1 to 10 still exist, since the lookups of single apps keep system apps visible
-- (XParam.isVisible) and give their paths, like ApplicationInfo.sourceDir; hide them, but for the class loaders and the
-- loaders of WebView which the framework itself makes, once those lookups hide system apps too.
-- shortcut: the folders of system apps of the partitions of makers, like /my_product/app of OPPO, still list their
-- names, add them when known.
local APPS = {}
for _, partition in ipairs({ '/system', '/product', '/system_ext', '/vendor', '/odm', '/oem', '/system/product',
                             '/system/system_ext', '/system/vendor', '/vendor/odm', '/system/vendor/odm' }) do
    for _, folder in ipairs({ '/app', '/priv-app', '/overlay' }) do
        table.insert(APPS, partition .. folder)
    end
end
local APEX_APPS = { '^/apex/[^/]+/app$', '^/apex/[^/]+/priv%-app$', '^/apex/[^/]+/overlay$' }

-- Whether a folder, without // and /./, is one of the apps of an APEX module, like /apex/com.android.permission/priv-app
function apexapps(folder)
    for _, pattern in ipairs(APEX_APPS) do
        if string.find(folder, pattern) then
            return true
        end
    end
    return false
end

-- The folders named after apps, of which those of hidden apps do not exist for Get.Applications: the private data of
-- apps, which Android 8.1 to 10 let other apps find (app data isolation hides it from Android 11), their code in
-- /data/app, <package>-<random>, of which the name is random from Android 11 (/data/app/~~<random>), and the folders
-- of apps in the shared storage: data and obb, which scoped storage hides from Android 11, and media, which all
-- versions let apps list. Each pattern captures the name of the app, with the last version which needs it.
local APP_FOLDERS = {
    { '^/data/data/([^/]+)', 29 }, { '^/data/user/%d+/([^/]+)', 29 }, { '^/data/user_de/%d+/([^/]+)', 29 },
    { '^/data/misc/profiles/cur/%d+/([^/]+)', 29 }, { '^/data/misc/profiles/ref/([^/]+)', 29 },
    { '^/data/app/([^/]-)%-', 29 },
}
for _, storage in ipairs({ '^/storage/emulated/%d+', '^/storage/self/primary', '^/sdcard', '^/mnt/sdcard',
                           '^/storage/%x%x%x%x%-%x%x%x%x' }) do
    table.insert(APP_FOLDERS, { storage .. '/Android/data/([^/]+)', 29 })
    table.insert(APP_FOLDERS, { storage .. '/Android/obb/([^/]+)', 29 })
    table.insert(APP_FOLDERS, { storage .. '/Android/media/([^/]+)', 999 })
end

-- The paths the script runs for, of the groups with files outside /sys (see filter); the filter also compares
-- absolute paths without /./, // and the links of /proc to the root (XCore.getFilter), relative paths with them run
-- it too, and so do paths with /../ to su and Magisk, like /vendor/../data/adb, which the prefixes do not match.
-- The folders of su and of the codec files are there for listing them, like File.list of /system/xbin, and so are
-- the folders of etc, which list the names the group hides, like fstab.* of /etc, a link to /system/etc, the folders
-- of the drivers of the GPU and the partitions, which list the manifests of Android 8.1 and the property files.
-- shortcut: the roots and folders etc of other partitions of makers than /oem and the my_ ones of OPPO, like /mi_ext,
-- still list build.prop for Use.Tracking and Read.Debugging, and so do those of the APEX modules; add them when known.
local FILTERS = {
    ['Use.Tracking'] = { '*sys/*', '*proc/scsi/*', '*proc/bus/input/*', '*/fonts*', '*etc/media_codecs*',
                         '*/build.prop*', '*etc/fstab.*', '/fstab.*', '/', '*misc/user/*', '*/./*', '*//*',
                         '/system/etc', '/vendor/etc', '/odm/etc', '/vendor/odm/etc', '/product/etc', '/system_ext/etc',
                         '/etc', '*etc/vintf*', '*etc/selinux*', '*etc/displayconfig*', '*lib64/egl*', '*lib/egl*',
                         '*/manifest.xml*', '/system', '/vendor', '/odm', '/system/vendor', '/product', '/system_ext',
                         '/product_services', '/system/product', '/system/system_ext', '/system/product_services',
                         '/system/vendor/etc', '/system/product/etc', '/system/system_ext/etc', '/system/vendor/odm/etc',
                         '/oem', '/oem/etc', '/my_*' },
    ['Read.Network'] = { '*net/*', '*/./*', '*//*' },
    ['Read.VPN'] = { '*net/*', '*/./*', '*//*' },
    ['Read.Debugging'] = { '/system/bin/*', '/system/xbin/*', '/system/sd/*', '/system/usr/*', '/system/app/Super*',
                           '/sbin/*', '/su*', '/data/local/*', '/cache/su', '/dev/su', '/vendor/bin/*',
                           '/vendor/xbin/*', '/product/bin/*', '/system_ext/bin/*', '/odm/bin/*', '/debug_ramdisk*',
                           '/data/adb*', '*bin/su*', '*magisk*', '*debug_ramdisk*', '*/./*', '*//*', '*/../*',
                           '/system/bin', '/system/xbin', '/sbin', '/data/local', '/cache', '/dev', '/vendor/bin',
                           '/vendor/xbin', '/product/bin', '/system_ext/bin', '/odm/bin', '/system/app',
                           '*xposed*', '*XposedBridge*', '*.orig.gz*', '*.no_orig*', '*libriru*', '*libmemtrack_real*',
                           '/data/misc/riru*', '*edxp*', '/system/framework/ed*', '/system/framework', '/system/lib',
                           '/system/lib64',
                           -- The property files and the folders which list them, like for Use.Tracking: the roots of
                           -- the partitions, which hold them on Android 10 and 11, and the folders etc
                           '*/build.prop*', '*/default.prop*', '*/prop.default*', '/', '/system', '/vendor', '/odm',
                           '/system/vendor', '/product', '/system_ext', '/product_services', '/system/product',
                           '/system/system_ext', '/system/product_services', '/system/etc', '/vendor/etc', '/odm/etc',
                           '/vendor/odm/etc', '/product/etc', '/system_ext/etc', '/etc', '/system/vendor/etc',
                           '/system/product/etc', '/system/system_ext/etc', '/system/vendor/odm/etc', '/oem', '/oem/etc',
                           '/my_*' },
    -- The folders etc of every path of ETC, which list the hidden folders: also by the links of /system to the
    -- partitions, like /system/vendor/etc, of /oem and the partitions my_ of OPPO, and of all APEX modules, which the
    -- filter can only pass as all of /apex, so the script also runs for the certificates of the system which Android 14
    -- and later read from /apex/com.android.conscrypt/cacerts, and resolves each once per thread (see HIDDEN).
    -- shortcut: the folders etc of other partitions of makers, like /mi_ext/etc, still list them; add them when known.
    ['Read.Features'] = { '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*', '/system/etc', '/vendor/etc',
                          '/odm/etc', '/vendor/odm/etc', '/product/etc', '/system_ext/etc', '/etc', '/system/vendor/etc',
                          '/system/product/etc', '/system/system_ext/etc', '/system/vendor/odm/etc', '/oem/etc', '/my_*',
                          '/apex/*' },
    -- Also the folders of APPS and the folders etc, which list the folders of default-permissions (see after), and the
    -- configuration files, which name apps (see packagetext)
    ['Get.Applications'] = { '*etc/permissions*', '*etc/default-permissions*', '*etc/sysconfig*', '*/./*', '*//*', '/etc',
                             '/vendor/odm/etc', '/system/vendor/odm/etc' },
    ['Get.Sensors'] = { '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*' },
    ['Use.Camera'] = { '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*' },
    ['Record.Audio'] = { '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*' },
    ['Read.Biometrics'] = { '*sys/*', '*etc/permissions*', '*etc/sysconfig*', '*/./*', '*//*' },
}
-- The folders etc and those of APPS of the partitions, also by the links of /system to them, the latter with a / at the
-- end too, which Os.stat does not take off, and the names in them
for _, partition in ipairs({ '/system', '/product', '/system_ext', '/vendor', '/odm', '/oem', '/system/product',
                             '/system/system_ext', '/system/vendor' }) do
    table.insert(FILTERS['Get.Applications'], partition .. '/etc')
end
for _, folder in ipairs(APPS) do
    table.insert(FILTERS['Get.Applications'], folder)
    table.insert(FILTERS['Get.Applications'], folder .. '/')
end
table.insert(FILTERS['Get.Applications'], '/data/app')
table.insert(FILTERS['Get.Applications'], '/data/app/')
-- The folders of APEX_APPS, which the filter can only pass as all of /apex (see matched)
table.insert(FILTERS['Get.Applications'], '/apex/*')

-- The folders of the shared libraries of the partitions, which apps can list (system_file and vendor_framework_file of
-- the SELinux policy of AOSP), also by the links of /system to the partitions and the folder /odm/framework leads to
-- without an odm partition: of Read.Features the jars of the libraries of makers are left out (see libraries)
local FRAMEWORKS = {}
for _, folder in ipairs({ '/system/framework', '/product/framework', '/system_ext/framework', '/vendor/framework',
                          '/odm/framework', '/system/product/framework', '/system/system_ext/framework',
                          '/system/vendor/framework', '/vendor/odm/framework', '/system/vendor/odm/framework' }) do
    FRAMEWORKS[folder] = true
    table.insert(FILTERS['Read.Features'], folder)
end

-- The features of the system each group hides from the feature files which stay, like handheld_core_hardware.xml, the
-- same as the hooks of the group in features and biometrics hide; a name ending in * covers the names starting with it
local FEATURES = { ['Get.Sensors'] = { 'android.hardware.sensor.*' },
                   ['Use.Camera'] = { 'android.hardware.camera*' }, ['Record.Audio'] = { 'android.hardware.microphone' },
                   ['Read.Biometrics'] = { 'android.hardware.fingerprint', 'android.hardware.biometrics.face',
                                           'android.hardware.biometrics.iris' } }

-- The files of /system/etc/permissions and sysconfig which every build of AOSP has (base.mk and from Android 10
-- base_system.mk of build/make, with runtime_libart.mk from Android 9, oreo-mr1-release to android17-release), which
-- stay for Read.Features with their folders: they declare no features, and their libraries are of the platform, like
-- android.test.base, which getSystemSharedLibraryNames keeps (see features), and so do the files of those libraries
-- (see basefile). A name of a later version on an earlier one is of a maker who added the file, which tells little.
-- They stay for Get.Applications too, read without the apps they name (see packagetext), like allow-in-power-save of
-- platform.xml.
-- shortcut: they are read as they are, with what a maker changed in them, like permissions of its own in platform.xml,
-- give the text of AOSP of the version if apps read them for the maker.
-- shortcut: the folders sysconfig of the other partitions do not exist, and their folders permissions have only the
-- files of the features shown (see shownfile); keep the folders sysconfig if apps look for them.
local BASE = { ['permissions/platform.xml'] = true, ['permissions/privapp-permissions-platform.xml'] = true,
               ['sysconfig/framework-sysconfig.xml'] = true, ['sysconfig/hiddenapi-package-whitelist.xml'] = true,
               ['sysconfig/preinstalled-packages-platform.xml'] = true, ['sysconfig/initial-package-stopped-states.xml'] = true,
               ['sysconfig/preinstalled-packages-asl-files.xml'] = true, ['sysconfig/enhanced-confirmation.xml'] = true,
               ['sysconfig/package-shareduid-allowlist.xml'] = true,
               ['sysconfig/preinstalled-packages-strict-signature.xml'] = true, ['sysconfig/app-lock-exempt.xml'] = true }

-- The features Read.Features shows, the same as in features, which a test compares
local SHOWN = { 'android.hardware.touchscreen*', 'android.hardware.faketouch*', 'android.hardware.wifi',
                'android.hardware.telephony', 'android.hardware.telephony.gsm', 'android.hardware.telephony.cdma',
                'android.hardware.telephony.data', 'android.hardware.telephony.radio.access',
                'android.hardware.telephony.subscription', 'android.hardware.telephony.calling',
                'android.hardware.telephony.messaging', 'android.software.webview' }

-- Whether a canonical path is the folder permissions of etc of a partition, like /vendor/etc/permissions, which most
-- devices have, or a file in it named after a feature Read.Features shows (see SHOWN) or the user keeps (setting
-- features.keep), like android.hardware.wifi.xml, which in AOSP declares that feature and those it implies alone
-- (frameworks/native/data/etc), so the files declare what hasSystemFeature reports.
-- shortcut: such a file is read as it is, with a feature a maker added to it, and the features shown which only other
-- files declare, like the touch screen and WebView of handheld_core_hardware.xml, are declared by no file the app sees;
-- keep those files without the hidden features (see withoutfeatures) if apps compare them
function shownfile(path, param)
    for _, etc in ipairs(ETC) do
        if string.find(path, etc .. 'permissions$') then
            return true
        end
        local name = string.match(path, etc .. 'permissions/([^/]+)%.xml$')
        if name ~= nil then
            if featured(name, SHOWN) then
                return true
            end
            local keep, names = (param ~= nil and param:getSetting('features.keep') or nil), {}
            for feature in string.gmatch(keep or '', '[^,%s]+') do
                names[#names + 1] = feature
            end
            return featured(name, names)
        end
    end
    return false
end

-- Whether a canonical path is the folder permissions or sysconfig of /system/etc, a file of BASE or of a library of the
-- platform which getSystemSharedLibraryNames keeps (see sharedlibraries), or a path under one, which the kernel answers
-- ENOTDIR for, as without Doppel
function basefile(path, param)
    local rest = string.match(path, '^/system/etc/(.+)$')
    if rest == nil then
        return false
    end
    local folder, name = string.match(rest, '^(%a+)/([^/]+)')
    if folder == nil then
        return rest == 'permissions' or rest == 'sysconfig'
    end
    if folder == 'permissions' then
        local _, kept = sharedlibraries(param)
        if kept ~= nil and kept[name] then
            return true
        end
    end
    return BASE[folder .. '/' .. name] == true
end

-- The feature files of the system, which it reads its features from, in the folders permissions and sysconfig of etc
-- and in their folders of the SKU of the device, like sku_X, which Android 10 and later read too (see Get.Sensors)
local FEATURE_FILES = {}
for _, etc in ipairs(ETC) do
    table.insert(FEATURE_FILES, etc .. 'permissions/[^/]+%.xml$')
    table.insert(FEATURE_FILES, etc .. 'sysconfig/[^/]+%.xml$')
    table.insert(FEATURE_FILES, etc .. 'permissions/sku_[^/]+/[^/]+%.xml$')
    table.insert(FEATURE_FILES, etc .. 'sysconfig/sku_[^/]+/[^/]+%.xml$')
end

-- Whether a network interface is of a VPN, like vpn and interface_entries, which a test compares
function isvpninterface(name)
    -- tunl0 is the tunnel of the kernel for IP in IP, which every device has, VPN or not
    if type(name) ~= 'string' or string.sub(name, 1, 4) == 'tunl' then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- The error of a file which does not exist, OsConstants.ENOENT, the error of making a file in a folder the app cannot
-- write, EACCES, and on a file system mounted read-only, EROFS, the flag of open which makes the file, O_CREAT, the bits
-- of open for reading or writing, O_ACCMODE (0 is O_RDONLY), the flag of open which fails for a file which exists,
-- O_EXCL, the flag of fstatat which does not follow a link, AT_SYMLINK_NOFOLLOW, the flag of statvfs of a file system
-- mounted read-only, ST_RDONLY, and the bits of the type of a file in its mode, S_IFMT, of a folder S_IFDIR, the same on
-- every Linux; O_NOFOLLOW is not (see followslink)
local ENOENT = 2
local EACCES = 13
local EROFS = 30
local O_CREAT = 64
local O_EXCL = 128
local O_ACCMODE = 3
local AT_SYMLINK_NOFOLLOW = 256
local ST_RDONLY = 1
local S_IFMT = 61440
local S_IFDIR = 16384

-- The texts of those errors, which java.io puts in its messages, the same as strerror of bionic
local REASONS = { [ENOENT] = 'No such file or directory', [EACCES] = 'Permission denied', [EROFS] = 'Read-only file system' }

-- The script runs for the files of /sys only (with net/ in the path for Read.Network and Read.VPN, see FILTERS for
-- the others), also by other paths, like /proc/self/root/sys/..., /data/../sys/...
-- or sys/... relative to /, the folder apps run in; see XCore.getFilter. It also runs for the paths through the
-- descriptor of a folder the app opened, like /proc/self/fd/57/cid and /dev/fd/57/cid, which name no folder of
-- the group and which matched resolves, and for links of apps themselves to the files, which the filter compares by
-- the path they lead to. The calls by the descriptor of a folder, openat, unlinkat, renameat and fstatat, have no path
-- the filter could compare, and link, symlink, rename and renameat have a second path, which the filter does not see,
-- so the script compares their paths itself like the filter, also by links (see atpath, renaming and filtered). The name
-- and the text of a file store have no argument at all (see storename), and Os.fstat has a descriptor, which the script
-- looks up itself (see linkcount).
local UNFILTERED = { openat = true, unlinkat = true, renameat = true, fstatat = true, link = true, symlink = true,
                     rename = true, name = true, toString = true, fstat = true }

-- Parts of the paths of the tables of the process (see tables), which only the hooks of open of the groups run for: the
-- maps and the mount tables for Use.Tracking, the mount tables for Read.Debugging
local TABLES = { ['Use.Tracking'] = { '/maps', '/smaps', '/mount' }, ['Read.Debugging'] = { '/mount' } }

function filter(hook)
    local method, group = hook:getMethodName(), hook:getGroup()
    if UNFILTERED[method] then
        return nil
    end
    local values = paths(group)
    if method == 'open' then
        for _, part in ipairs(TABLES[group] or {}) do
            values[#values + 1] = '*' .. part .. '*'
        end
    end
    return values
end

-- Whether a path has a part of the paths of the tables of the process of a group
function tabled(group, path)
    for _, part in ipairs(TABLES[group] or {}) do
        if string.find(path, part, 1, true) then
            return true
        end
    end
    return false
end

-- The paths the script runs for in a group, which the filter gives the hooks
function paths(group)
    local values = {}
    for _, value in ipairs(FILTERS[group] or { '*sys/*', '*/./*', '*//*' }) do
        values[#values + 1] = value
    end
    values[#values + 1] = '*/fd/*'
    if group ~= 'Get.Applications' then
        return values
    end
    -- The folders of APP_FOLDERS, only on the versions which need them, since apps use their own ones often
    values[#values + 1] = '*Android/media*'
    if sdkversion() <= 29 then
        for _, value in ipairs({ '/data/data/*', '/data/user/*', '/data/user_de/*', '/data/misc/profiles/*', '/data/app/*',
                                 '*Android/data*', '*Android/obb*' }) do
            values[#values + 1] = value
        end
    end
    return values
end

-- The version of Android, read once
local VERSION = nil

function sdkversion()
    if VERSION == nil then
        VERSION = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    end
    return VERSION
end

-- The app a path is in by APP_FOLDERS, nil when it is in no folder named after an app; like .nomedia, a name which
-- cannot be a package is of no app
function appfolder(path)
    for _, folder in ipairs(APP_FOLDERS) do
        if sdkversion() <= folder[2] then
            local name = string.match(path, folder[1])
            if name ~= nil then
                return (string.find(name, '^%a[%w_]*%.[%w_.]*[%w_]$') and name or nil)
            end
        end
    end
    return nil
end

-- Whether an app is installed and hidden: the folder of an app which is not installed stays, like on Android, also
-- one the app made itself, which an app could else make to tell the hook is there
function hiddenapp(param, name)
    if name == param:getPackageName() or param:isPackageVisible(name) then
        return false
    end
    local installed = pcall(function()
        return param:unhooked(function()
            -- MATCH_UNINSTALLED_PACKAGES, like XParam looks packages up
            return param:getApplicationContext():getPackageManager():getPackageInfo(name, 0x2000)
        end)
    end)
    return installed
end

-- Whether a permission file of the system grants permissions to another app by its package, like the privileged
-- permissions of com.android.launcher3.xml of /system_ext/etc/permissions (privapp-permissions) or those of an app of
-- the vendor (oem-permissions), whatever the name of the file; by its text, since the app cannot look up most system
-- apps from Android 11 (package visibility), read without the hooks of the app, which hide it. The files of the
-- features and libraries of the platform start with android., like android.hardware.wifi.xml and android.test.base.xml,
-- and are not read, nor are the files FILES hides by name.
-- The other files are read without the elements which name apps, like allow-in-power-save of platform.xml and the
-- files of etc/sysconfig, like preinstalled-packages-*.xml (see packagetext).
function privappfile(path, param)
    local name
    -- Also in a folder of the SKU of the device, like /odm/etc/permissions/sku_X, which Android 10 and later read too
    for _, etc in ipairs(ETC) do
        name = name or string.match(path, etc .. 'permissions/([^/]+)%.xml$') or
                string.match(path, etc .. 'permissions/sku_[^/]+/([^/]+)%.xml$')
    end
    if name == nil or string.sub(name, 1, 8) == 'android.' or string.find(name, '^privapp%-permissions%-') then
        return false
    end
    local ok, text = pcall(function()
        return param:unhooked(function()
            -- Without StrictMode, which would tell the app of a read of a file of the system
            local strict = luajava.bindClass('android.os.StrictMode')
            local policy = strict:allowThreadDiskReads()
            local done, value = pcall(function()
                return param:readFile(path)
            end)
            strict:setThreadPolicy(policy)
            return done and value or nil
        end)
    end)
    if not ok or text == nil then
        return false
    end
    for package in string.gmatch(text, '<[%a%-]*permissions%s+package%s*=%s*["\']([^"\']+)') do
        if package ~= param:getPackageName() then
            return true
        end
    end
    return false
end

-- Whether paths are hidden for a group, per thread, since a script runs once per thread (see docs/DEFINE.md): files
-- of /sys are read often, like the frequencies of the processors, and resolving their paths takes a system call.
-- A file which exists stays what it is while the app runs, but one which does not can come, like the cid of an SD card
-- the user inserts, so only the answers for files which exist are kept, and not those of paths which can lead through
-- a link of the app, which can lead elsewhere the next time (see ownlinked); the cache starts over when it is full.
local HIDDEN = {}
local cached = 0

-- Whether a path can lead through a link the app made, which it can point elsewhere at any time: apps can only make
-- links in their own folders, which have the name of the app in their paths (the shared storage takes none); so can
-- the links of the kernel to the files the app mapped and to the folder it runs in, which relative paths start from,
-- and /dev/stdin, /dev/stdout and /dev/stderr, links to the descriptors 0 to 2, which the app can replace by dup2
-- shortcut: the folders of another app of the same shared user id have the name of that app, keep its paths out of
-- the cache too if such apps probe like that
-- shortcut: the paths of the app which the filter lets in, like with // or by the path a link leads to, are resolved
-- on each call; the cost was not measured on a device, keep them by the path they lead to if apps slow down
function ownlinked(path, param)
    if string.sub(path, 1, 1) ~= '/' or string.find(path, '/cwd', 1, true) or
            string.find(path, '/map_files/', 1, true) or string.find(path, '^/dev/std') then
        return true
    end
    return ownfile(path, param)
end

-- Whether a path is in the own folders of the app, which have its name in their paths, the only ones it can make
-- links in
function ownfile(path, param)
    local ok, name = pcall(function()
        return param:getPackageName()
    end)
    return ok and name ~= nil and string.find(path, name, 1, true) ~= nil
end

-- The links of the descriptors of the app, like /proc/self/fd/9 of a file another app shared with it: the file is open
-- already, only a path on through the descriptor of a folder leads to files the app could not open
local DESCRIPTORS = { '^/?proc/[^/]+/fd/%d+/?$', '^/?proc/[^/]+/task/%d+/fd/%d+/?$', '^/?dev/fd/%d+/?$' }

-- The group, class, method and return type of a hook, which do not change, without asking the hook each time
local HOOKS = {}

function about(hook)
    local known = HOOKS[hook]
    if known == nil then
        known = { group = hook:getGroup(), class = hook:getClassName(), method = hook:getMethodName(),
                  returnType = hook:getReturnType() }
        HOOKS[hook] = known
    end
    return known
end

-- The path without a link to the root folder in front, like /proc/self/root/system/xbin/su, also where the
-- links cannot be resolved
function unrooted(path)
    for _, prefix in ipairs({ '^/proc/self/root/', '^/proc/thread%-self/root/', '^/proc/%d+/root/' }) do
        path = string.gsub(path, prefix, '/')
    end
    return path
end

-- The path a path leads to, by the links of /sys, or the path as it is; without the hooks of the app, which give
-- the canonical path of a hidden file as of one which does not exist and would ask for this path again, see before
function resolve(path, param)
    local ok, resolved = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):getCanonicalPath()
        end)
    end)
    if ok and resolved ~= nil then
        return resolved
    end
    return path
end

-- Whether the app could reach a file: else the real call answers, since SELinux denies many paths whatever their
-- name (EACCES), like all of /sys/class/net, and a fake answer for only the hidden names would tell the hook is there,
-- and nothing is hidden for a file which does not exist, like su on a phone without root
-- Read.Network only denies reading its files (see denied): of /proc/net apps can tell they exist, but not read them.
-- The property files of the partitions and of init and fstab are there on every device, so the ones apps cannot read, like
-- /system/build.prop of Android 14 (EACCES), stay as they are: hiding them would turn EACCES into ENOENT; the same for
-- the manifests and SELinux contexts of vintf and the drivers of the GPU (EVERYWHERE, the files of every device). So do
-- the ids of Read.Identifiers which SELinux lets apps find but not read: the id stays unknown, and the driver
-- makes the file with the others of its folder, like cid with csd and date of an SD card.
local EVERYWHERE = { 'build%.prop$', '/default%.prop$', 'prop%.default$', '/fstab%.[^/]*$', '/manifest%.xml$', '/vintf/',
                     '/selinux/', '/egl/' }

function reachable(group, path, param)
    local ok, exists = pcall(function()
        return param:unhooked(function()
            local file = luajava.newInstance('java.io.File', path)
            if group == 'Read.Network' or group == 'Read.Identifiers' then
                return file:canRead()
            end
            for _, pattern in ipairs(EVERYWHERE) do
                if string.find(path, pattern) then
                    return file:canRead()
                end
            end
            return file:exists()
        end)
    end)
    return not ok or exists
end

-- Whether a file exists, without the hooks of the app; as if it did when that cannot be told, like reachable
function present(path, param)
    local ok, exists = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):exists()
        end)
    end)
    return not ok or exists
end

-- Whether a file is one of the group of the hook which the app could reach: its path or the path it leads to matches
-- a pattern. The interfaces of VPNs come and go, and so does the folder of a descriptor, which the app can close and
-- open again for another folder by the same number, and so do files which do not exist yet, see HIDDEN, and the files of
-- /proc/net of an interface, like dev_snmp6/tun0; the rest stays while the app runs. The group is the one of the hook,
-- unless another is given. With nofollow a link at the end of the path counts by itself, by its text, see linked.
function hidden(hook, path, param, group, nofollow)
    group = group or about(hook).group
    if FILES[group] == nil then
        return false
    end
    local plain = unrooted(path)
    for _, pattern in ipairs(DESCRIPTORS) do
        if string.find(plain, pattern) then
            return false
        end
    end
    local key = group .. (nofollow and '|-' or '|') .. path
    local known = HIDDEN[key]
    if known ~= nil then
        return known
    end
    local named = matched(group, path, param, nofollow)
    local found = named and reachable(group, path, param)
    -- Nothing is under a hidden file either, like .../privapp-permissions-com.example.xml/x or its /., for which the
    -- kernel answers ENOTDIR, which would tell the file is there; a path with /../ counts by the file it leads to (see
    -- matched), the files of Read.Network stay there, and of Read.VPN only the paths its patterns name are looked at,
    -- like /proc/net/dev_snmp6/tun0/x under the file of tun0 or a missing tun0/x, which the app would make with EACCES,
    -- under its folder, since the patterns cover what is under its folders.
    -- shortcut: a path on through a hidden file and back, like .../privapp-permissions-com.example.xml/../a.xml, still
    -- answers ENOTDIR, check each name of such paths if apps probe like that.
    if not found and group ~= 'Read.Network' and (named or group ~= 'Read.VPN') and
            not string.find(path, '/../', 1, true) then
        local folder = string.match(path, '^(.+)/[^/]*$')
        found = folder ~= nil and hidden(hook, folder, param, group)
    end
    -- Of Get.Applications the paths in the folders of apps are found by the name of the folder (see matched), whatever
    -- links they lead through
    if group == 'Read.VPN' or string.find(path, '/fd/', 1, true) or (ownlinked(plain, param) and
            not (group == 'Get.Applications' and not string.find(plain, '/../', 1, true) and appfolder(plain))) or
            (not found and not present(path, param)) or (found and group == 'Read.Network') then
        return found
    end
    if cached >= 1000 then
        HIDDEN = {}
        cached = 0
    end
    HIDDEN[key] = found
    cached = cached + 1
    return found
end

-- The fonts every Android has (Roboto, Noto and the families of AOSP); the other fonts of the system, like SamsungOne,
-- MiSans and Google Sans, name the maker and the ROM. They are left out of listings and do not exist for the app;
-- text still shows with them, which the system loads itself. Like system_fonts, which a test compares.
local FONTS = { 'Roboto', 'Noto', 'DroidSans', 'CutiveMono', 'ComingSoon', 'DancingScript', 'CarroisGothicSC',
                'SourceSansPro', 'AndroidClock' }

-- Whether a file is a font of the system which is not of every Android, in a fonts folder of a partition
function brandfont(path)
    local partition, name = string.match(path, '^/([^/]+)/fonts/([^/]+)$')
    if partition == nil then
        partition, name = string.match(path, '^/system/([^/]+)/fonts/([^/]+)$')
    end
    if partition == nil or not (partition == 'system' or partition == 'product' or partition == 'system_ext' or
            partition == 'vendor' or partition == 'odm' or partition == 'oem' or string.find(partition, '^my_')) then
        return false
    end
    for _, prefix in ipairs(FONTS) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return false
        end
    end
    return true
end

-- Whether a file is a driver of the GPU, in the folder egl of the libraries of a partition, like
-- /vendor/lib64/egl/libGLESv2_adreno.so and libGLES_mali.so, which name the GPU (see gles_getstring); the folder stays,
-- the system loads the driver natively, and so do the libraries of ANGLE, which name no GPU, like ro.hardware.egl=angle
function gpudriver(path)
    local name = string.match(path, '^/[%w_]+/lib%w*/egl/([^/]+)$') or string.match(path, '^/system/[%w_]+/lib%w*/egl/([^/]+)$')
    return name ~= nil and not string.find(name, '_angle%.so$')
end

-- The files of the vendor which the maps of the app leave out: the libraries of the partitions of the vendor, which
-- only the GPU driver and the graphics HALs load into apps, named after the GPU or the board, like libgsl.so of Adreno,
-- the libraries of the emulator and mapper.qti.so, gralloc.msm8998.so and vulkan.adreno.so of /hw; the GPU drivers of
-- /system of devices without Treble (see gpudriver), and the devices of the GPUs of Adreno, Mali and PowerVR, whose
-- memory the driver maps. ANGLE names no GPU (see gpudriver).
local VENDOR = { '^/vendor/lib', '^/odm/lib', '^/system/vendor/lib', '^/dev/kgsl', '^/dev/mali', '^/dev/pvrsrvkm' }

function vendorfile(path)
    if string.find(path, '_angle%.so') then
        return false
    elseif gpudriver(path) then
        return true
    end
    for _, prefix in ipairs(VENDOR) do
        if string.find(path, prefix) then
            return true
        end
    end
    return false
end

-- The maps or smaps of a process without the mappings of vendorfile, and how many it left out; a line of a mapping
-- starts with its addresses, and in smaps the lines after it belong to it. The path is after the inode, the first /
-- of the line after a space, unlike in names like [anon:dalvik-/system/framework/boot.art].
function unmapped(text)
    local result, left, skipping = {}, 0, false
    for line in string.gmatch(text, '[^\n]*\n?') do
        if string.find(line, '^%x+%-%x+ ') then
            local path = string.match(line, '^%x+%-%x+ [^/\n]* (/[^\n]*)')
            skipping = path ~= nil and vendorfile(path)
            left = left + (skipping and 1 or 0)
        end
        if not skipping then
            result[#result + 1] = line
        end
    end
    return table.concat(result), left
end

-- The partitions of a mount table or entry by name in /dev/block/by-name, which the paths of the controllers lead to,
-- like /dev/block/pci/pci0000:00/0000:00:06.0/by-name/metadata of the emulator, and how many it renamed. Android 9 and
-- later make the links of by-name; SELinux does not let apps search /dev/block on any version, so that Android 8.1
-- has none cannot be found either.
function byname(text)
    return string.gsub(text, '/dev/block/[^%s]-/by%-name/', '/dev/block/by-name/')
end

-- Whether a mount is of Magisk, which a device without root does not have, by its source, its mount point and, in
-- mountinfo, the folder of the source it mounts: the tmpfs of Magisk, named magisk, on /sbin up to Android 10 (where
-- older versions name it tmpfs, and Android mounts nothing) and on /debug_ramdisk from Android 11, an empty folder of
-- the root of AOSP (see FILES); its programs mounted from there, like /system/bin/magisk, the mirrors of the
-- partitions and the modules in its folder .magisk, also as the source by its block devices there, the folders of the
-- system which the modules change, mounted from its tmpfs named worker, and the files of the modules, which mountinfo
-- names by their path in /data, like /adb/modules/example/system/bin/tool.
function magiskmount(source, point, root)
    if string.find(point, '/.magisk', 1, true) or (root ~= nil and string.find(root, '/.magisk', 1, true)) then
        return true
    end
    return source ~= nil and string.find(source, 'magisk', 1, true) ~= nil or source == 'worker' or point == '/sbin' or
            point == '/debug_ramdisk' or string.find(point, '^/debug_ramdisk/') ~= nil or
            (root ~= nil and string.find(root, '^/adb/') ~= nil)
end

-- A mount table without the mounts of Magisk (see magiskmount) and the mounts in them, like the files of the system
-- which Magisk mounts back into a folder it changes, and how many it left out, by the fields of each table: the source
-- and mount point of mounts, the id, the parent, the folder of the source, the mount point and after the type the
-- source of mountinfo, and the source and mount point of mountstats. A mount in one of Magisk is by its parent in
-- mountinfo, so no parent is missing, and in the others by its mount point under an earlier one of Magisk; an earlier
-- mount there stays, which Magisk only covers.
-- shortcut: a mount table without the folders of the source (mounts and mountstats) names a file of a module mounted
-- over a file of the system only by the partition of /data, like /dev/block/dm-48 /system/bin/tool, which stays; and
-- the file stores of FileSystems.getFileStores, which java.nio makes of /proc/mounts it reads natively (see
-- storename), still have the mounts of Magisk, like /debug_ramdisk; leave them out too if apps look for root that way.
function unmagisked(text, name)
    local mounts, ids = {}, {}
    for line in string.gmatch(text, '[^\n]*\n?') do
        local mount = { line = line }
        if name == 'mountinfo' then
            mount.id, mount.parent, mount.root, mount.point = string.match(line, '^(%d+) (%d+) %d+:%d+ (%S+) (%S+) ')
            mount.source = string.match(line, ' %- %S+ (%S+)')
        elseif name == 'mountstats' then
            mount.source, mount.point = string.match(line, '^device (%S+) mounted on (%S+) ')
        else
            mount.source, mount.point = string.match(line, '^(%S+) (%S+) ')
        end
        mount.left = mount.point ~= nil and magiskmount(mount.source, mount.point, mount.root)
        mounts[#mounts + 1] = mount
    end
    if name == 'mountinfo' then
        -- By the parent, until no more are found, since a parent may come after its mount
        local found = true
        while found do
            found = false
            for _, mount in ipairs(mounts) do
                if mount.left and mount.id ~= nil then
                    ids[mount.id] = true
                elseif not mount.left and mount.parent ~= nil and ids[mount.parent] then
                    mount.left, found = true, true
                end
            end
        end
    else
        -- By the mount point under an earlier one of Magisk
        local points = {}
        for _, mount in ipairs(mounts) do
            if not mount.left and mount.point ~= nil then
                for _, folder in ipairs(points) do
                    if string.sub(mount.point, 1, #folder + 1) == folder .. '/' then
                        mount.left = true
                        break
                    end
                end
            end
            if mount.left and mount.point ~= '/' then
                points[#points + 1] = mount.point
            end
        end
    end
    local result, left = {}, 0
    for _, mount in ipairs(mounts) do
        if mount.left then
            left = left + 1
        else
            result[#result + 1] = mount.line
        end
    end
    return table.concat(result), left
end

-- The tables of the process which the app opened with a hook of open of a group of TABLES, by the path they lead to
-- through /proc or a descriptor, like /proc/self/maps, /proc/thread-self/smaps, /proc/mounts, a link to self/mounts,
-- and /dev/fd/9/maps of a descriptor of /proc/self, and by links of the app itself, which the filter lets in by the
-- path they lead to (see XCore.Filter), whatever else of the filter the text of their path matches, like files//m,
-- files/./m, a folder of the app named sys or the descriptor of a folder of the app; so every path is resolved but the
-- files of /sys, in which no app can make a link and whose links lead only within /sys. For Use.Tracking the maps
-- without the files of the vendor (see unmapped) and the mount tables with the partitions by name (see byname), for
-- Read.Debugging the mount tables without the mounts of Magisk (see unmagisked). Both hooks of an open replace the
-- text, each the one the other left (see XParam.getFileText), and the faked_files hooks of both groups give the stat,
-- size and link of the descriptor of the table, not of the fake.
-- shortcut: on Android 8.1 and 9 FileInputStream, which FileReader opens too, opens by its native open0, not by
-- IoBridge.open and the hooks of open (AOSP libcore oreo-mr1-release and pie-release, FileInputStream(File)), so it reads
-- the tables as they are, the maps with the files of the vendor and the mount tables with the mounts of Magisk, while
-- RandomAccessFile, Os.open and java.nio read them rewritten; the hooks of open0 of Read.Debugging only hide its files
-- (see before), put the fake in place of the descriptor of the stream there (Os.dup2) if apps read the tables that way.
function tables(hook, param)
    local path, group = pathof(param), about(hook).group
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    -- shortcut: each open of the groups outside /sys resolves its path once more, also when the filter resolved it
    -- already; the cost was not measured on a device, take the path the filter resolved if apps slow down
    local plain = collapsed(path)
    local absolute = unrooted(string.sub(plain, 1, 1) == '/' and plain or '/' .. plain)
    if string.sub(absolute, 1, 5) == '/sys/' and not string.find(absolute, '/..', 1, true) then
        return false
    end
    local resolved = resolve(plain, param)
    local name = string.match(resolved, '^/proc/[^/]+/(%a+)$') or string.match(resolved, '^/proc/[^/]+/task/%d+/(%a+)$')
    local text, left
    local mounts = name == 'mounts' or name == 'mountinfo' or name == 'mountstats'
    if group == 'Read.Debugging' then
        if mounts then
            text, left = unmagisked(param:getFileText(), name)
        end
    elseif name == 'maps' or name == 'smaps' then
        text, left = unmapped(param:getFileText())
    elseif mounts then
        text, left = byname(param:getFileText())
    end
    if left == nil or left == 0 then
        return false
    end
    param:setFileText(text)
    return true, tostring(left)
end

-- The name and the text of a file store of java.nio, name() and toString(), like of the stores of
-- FileSystems.getDefault().getFileStores(), which it makes of the mount entries it reads of /proc/mounts natively: the
-- partition by name, like the mount tables (see byname). Files.getFileStore(path) throws SecurityException on Android
-- 8.1 to 17, so that list is the way apps get stores, of the mounts whose folder they can stat, like /system where
-- Android 8.1 and 9 mount it straight from its partition. The methods are hooked rather than the natives which fill the
-- entries, since the name in the entry is a field the hidden API rules block: a listing of the fields may leave it out,
-- and getDeclaredField reports it to StrictMode as a hidden API the app used.
function storename(param)
    local result = param:getResult()
    if param:getException() ~= nil or result == nil then
        return false
    end
    local renamed, count = byname(result)
    if count == 0 then
        return false
    end
    param:setResult(renamed)
    return true, result, renamed
end

-- Whether a file is one of a group, without the cache, so other threads can ask too (see listing); with nofollow the
-- folder of the path is resolved already and a link at its end counts by its own name (see linked)
function matched(group, path, param, nofollow)
    local function follow(text)
        return nofollow and text or resolve(text, param)
    end
    local plain = unrooted(path)
    -- Only the file it leads to counts: the filter passes all paths with /../, like ones through a folder of the app
    if string.find(plain, '/../', 1, true) then
        plain = follow(plain)
    end
    -- A file the app makes itself in the shared storage, like /sdcard/etc/permissions/android.hardware.sensor.light.xml,
    -- is no feature file of the system, of sensors, cameras, microphones or biometrics, as for featurefile.
    -- shortcut: Read.Features, Get.Applications and Use.Tracking still hide the names of their etc patterns there, like
    -- /sdcard/etc/permissions, leave them too if apps make such files
    if FEATURES[group] ~= nil and string.find(plain, '^/sdcard/') then
        return false
    end
    -- app_process32 and app_process64, which every device has, stay, also where Xposed made them links to its own.
    -- shortcut: readlink, getCanonicalPath and stat give the name, size and inode of the app_process of Xposed, answer
    -- with those of the hidden _original if apps compare them.
    if group == 'Read.Debugging' and string.find(plain, '/app_process%d*$') then
        return false
    end
    -- Resolved once, when needed: getCanonicalPath reads the file system
    local resolved
    if group == 'Get.Applications' then
        -- Of /apex only the permission files are hidden, the folders of APEX_APPS only list without names; the other
        -- paths are not resolved, like the certificates of the system of Android 14 and later
        if string.find(plain, '^/apex/') and not string.find(plain, '/etc/', 1, true) then
            return false
        end
        -- A folder named after an app, by its path or the path it leads to, like /sdcard to /storage/emulated/0;
        -- the paths in the own folders of the app, which apps use often, do not read the file system
        -- shortcut: so on Android 8.1 to 10 a link in the private folders of the app to the folder of a hidden app,
        -- like files/l to /data/data/com.hidden, is found as a file of the app and leads there; resolve the paths of
        -- the own folders, at the cost of a call each, if apps probe like that
        local name = appfolder(plain)
        if name == nil then
            resolved = follow(plain)
            name = appfolder(resolved)
        end
        if name ~= nil then
            return hiddenapp(param, name)
        end
        -- The permissions of the platform itself, which every AOSP build has, name no app of a maker (see BASE), and
        -- are read without the apps they name (see packagetext)
        if string.find(plain, '/privapp%-permissions%-platform%.xml$') then
            return false
        end
        -- A permission file of the privileged permissions of a system app by another name (see privappfile)
        if privappfile(plain, param) or (resolved ~= plain and privappfile(resolved, param)) then
            return true
        end
    end
    -- Also through the descriptor of a folder, like /proc/self/fd/57/SamsungOne.ttf of /system/fonts, and through a link
    -- of the app itself to the folder, which the filter lets in by the path it leads to (see below)
    if group == 'Use.Tracking' and (string.find(path, 'fonts', 1, true) or string.find(path, 'egl', 1, true) or
            string.find(path, '/fd/', 1, true)) then
        if brandfont(plain) or gpudriver(plain) then
            return true
        end
        resolved = follow(plain)
        if brandfont(resolved) or gpudriver(resolved) then
            return true
        end
    end
    local patterns = FILES[group]
    -- The patterns of Read.VPN capture the name of the interface
    local function matches(text, pattern)
        if group == 'Read.VPN' then
            return isvpninterface(string.match(text, pattern))
        end
        return string.find(text, pattern) ~= nil
    end
    local found = false
    for _, pattern in ipairs(patterns) do
        if matches(plain, pattern) then
            found = true
            break
        end
    end
    if not found then
        resolved = resolved or follow(plain)
        if group == 'Use.Tracking' and (brandfont(resolved) or gpudriver(resolved)) then
            return true
        end
        for _, pattern in ipairs(patterns) do
            if matches(resolved, pattern) then
                found = true
                break
            end
        end
    end
    -- By the path it leads to, like /etc/permissions/platform.xml and /system/etc/permissions/.
    if found and group == 'Read.Features' then
        local canonical = resolved or follow(plain)
        return not basefile(canonical, param) and not shownfile(canonical, param)
    end
    return found
end

-- The canonical path of a hidden file, like of a file which does not exist: its name in its folder, not the file
-- a link leads to, like /system/bin/magisk of su; the folder by its links, unless it is hidden too
function missing(hook, path, param)
    local folder, name = string.match(path, '^(.*)/([^/]+)$')
    if folder == nil or folder == '' then
        return path
    end
    if hidden(hook, folder, param) then
        folder = missing(hook, folder, param)
    else
        folder = resolve(folder, param)
    end
    return (folder == '/' and '' or folder) .. '/' .. name
end

-- The error of making a hidden file, which does not exist for the app, nil when the kernel would check the permissions:
-- ENOENT when its folder is hidden too, EROFS when the folder is on a file system mounted read-only, like the
-- partitions of the system and the APEX modules by any path, like /etc, a link to /system/etc, which the kernel
-- checks before the permissions; the real statvfs, which SELinux lets every app call
function making(hook, path, param)
    local folder = luajava.newInstance('java.io.File', path):getParent()
    if folder == nil then
        return nil
    end
    if hidden(hook, folder, param) then
        return ENOENT
    end
    local ok, readonly = pcall(function()
        return param:unhooked(function()
            return bit32.band(luajava.bindClass('android.system.Os'):statvfs(folder).f_flag, ST_RDONLY) ~= 0
        end)
    end)
    return (ok and readonly) and EROFS or nil
end

-- The error of a call on a hidden file: of making a file, a folder, a pipe or a node by its name (open and openat with
-- O_CREAT, which FileOutputStream always sets, mkdir, mkfifo, mknod) like a missing name in a folder of /sys, which apps
-- cannot write (EACCES), unless making answers first, since Linux never gives ENOENT for a name it makes in a folder
-- which exists; of removing it (remove) EROFS on a file system mounted read-only, like the fonts of /system, which
-- Linux checks before it looks the name up; else of a name no lookup finds. A call which follows a link at the end of
-- the path makes the file the link leads to (see target), so by the folder of that file.
-- shortcut: making a name of a hidden app in the shared storage, like Android/media/<package>, answers EACCES like a
-- folder apps cannot write; answer what the storage gives for a missing name of another app once that is known.
function failing(hook, path, param)
    local class, method = about(hook).class, about(hook).method
    local flags = (method == 'open' and param:getArgument(1)) or (method == 'openat' and param:getArgument(2)) or 0
    if method == 'mkdir' or method == 'mkfifo' or method == 'mknod' or class == 'java.io.FileOutputStream' or
            bit32.band(flags, O_CREAT) ~= 0 then
        return making(hook, followslink(method, param) and target(path, param) or path, param) or EACCES
    elseif method == 'remove' then
        return making(hook, path, param) or ENOENT
    end
    return ENOENT
end

-- The path of the first argument: a text, a java.io.File or a path of java.nio, which print their path
function pathof(param)
    return tostring(param:getArgument(0))
end

-- The path of openat, unlinkat, renameat and fstatat, by which a SecureDirectoryStream opens, removes and moves the
-- files of its folder, also to read or change their attributes, and reads their attributes: the name of an argument,
-- which is relative to the folder of the descriptor in the argument before it (its link in /proc/self/fd) unless it is
-- absolute; nil when the folder is not known or the filter of the other hooks would not run the script for the path,
-- like for the files of the app, which libraries remove this way. The folder of the descriptor is the one its links
-- lead to already, so a name in it of a call which acts on a link itself (follows false) is compared by its text.
function atpath(hook, param, index, follows)
    local path = luajava.newInstance('java.lang.String', param:getArgument(index))
    local single = (string.sub(path, 1, 1) ~= '/' and not string.find(path, '/', 1, true))
    if string.sub(path, 1, 1) ~= '/' then
        local ok, folder = pcall(function()
            return param:unhooked(function()
                return luajava.bindClass('android.system.Os'):readlink('/proc/self/fd/' .. param:getArgument(index - 1))
            end)
        end)
        if not ok or folder == nil then
            return nil
        end
        path = (folder == '/' and '' or folder) .. '/' .. path
    end
    return filtered(about(hook).group, path, (follows or not single) and param or nil, follows) and path or nil
end

-- Whether a call acts on the file a link at the end of its path leads to, like XCore.Filter tells: open and openat
-- not with O_NOFOLLOW of the platform, which differs between ARM and x86, nor with O_CREAT and O_EXCL, which fails with
-- EEXIST for any link, fstatat not with AT_SYMLINK_NOFOLLOW, and not the calls of NOFOLLOW, which act on the link
-- itself; the others, like stat, FileOutputStream and the listings, follow it
local nofollow = nil
local NOFOLLOW = { lstat = true, readlink = true, lchown = true, remove = true, mkdir = true, mkfifo = true, mknod = true,
                   createFileExclusively = true, unlinkat = true }

function followslink(method, param)
    if method == 'fstatat' then
        return bit32.band(param:getArgument(2), AT_SYMLINK_NOFOLLOW) == 0
    elseif method ~= 'openat' and method ~= 'open' then
        return not NOFOLLOW[method]
    end
    if nofollow == nil then
        local ok, value = pcall(function()
            return luajava.bindClass('android.system.OsConstants').O_NOFOLLOW
        end)
        nofollow = (ok and type(value) == 'number') and value or 0
    end
    -- The flags of open follow its path, of openat its descriptor and path; a call without them follows, like the
    -- filter of XCore
    local flags = param:getArgument(method == 'open' and 1 or 2)
    if type(flags) ~= 'number' then
        return true
    end
    return bit32.band(flags, nofollow) == 0 and bit32.band(flags, O_CREAT + O_EXCL) ~= O_CREAT + O_EXCL
end

-- Whether the filter of a group runs the script for a path, like XCore.Filter compares it: a value ending in * is the
-- start of the path, starting with * too a part of it, other values the path, also of the path without a link to the
-- root in front and of a relative path from the root; paths with /.. always run the script. With a param also by the
-- path its links lead to, of a call which acts on a link itself (follows false) by its name in the folder the links
-- before it lead to; without one by its text only.
-- shortcut: a path no value matches by its text is resolved for each call of link, symlink, rename and the calls by a
-- descriptor, up to two folders for rename; the cost was not measured on a device, pass the paths the filter resolved
-- if apps slow down
function filtered(group, path, param, follows)
    local absolute = string.sub(path, 1, 1) == '/' and path or '/' .. path
    if string.find(absolute, '/../', 1, true) or string.find(absolute, '/%.%.$') then
        return true
    end
    if textmatches(group, { path, unrooted(path), absolute }) then
        return true
    end
    if param == nil then
        return false
    end
    local resolved = follows and resolve(absolute, param) or linkfolder(absolute, param)
    return resolved ~= absolute and textmatches(group, { resolved })
end

-- The path of a name in the folder the links before it lead to, like of a call on a link itself
function linkfolder(path, param)
    local folder, name = string.match(path, '^(.*)/([^/]+)$')
    if folder == nil or folder == '' then
        return path
    end
    folder = resolve(folder, param)
    return (folder == '/' and '' or folder) .. '/' .. name
end

-- Whether a path is hidden for a call (follows, see followslink). A call which acts on a link at the end of its path
-- itself, like lstat, readlink, remove, rename or open with O_NOFOLLOW, judges a link the app made in its own folders
-- by the link, its name in the folder the links before it lead to, like the filter of XCore, also when the filter let
-- the path in by its text, like files//s, so it answers like on Android: with the attributes and the text of the link,
-- ELOOP or EEXIST. Links of the system, which the app cannot change, and the paths of the other calls count by the file
-- they lead to, like a link the group hides by that file only.
function linked(hook, path, param, follows, group)
    if not follows and not string.find(path, '/%.?%.?$') then
        local link = linkfolder(path, param)
        if ownfile(link, param) then
            return hidden(hook, link, param, group, true)
        end
    end
    return hidden(hook, path, param, group)
end

-- The file a call which follows a link at the end of its path makes: of a link the app made in its own folders the one
-- it leads to, like /system/fonts/SamsungOne.ttf of files/f, which Linux makes in /system/fonts (EROFS), not in files;
-- else the path itself, by the folder of its own path (see making), also of a hidden link of the system, which does not
-- exist for the app. The hidden file exists, so the link resolves.
function target(path, param)
    local link = linkfolder(path, param)
    if not ownfile(link, param) then
        return path
    end
    local resolved = resolve(path, param)
    return resolved ~= link and resolved or path
end

-- Whether one of the texts of a path matches a value of the filter of a group by its text, see filtered
function textmatches(group, texts)
    for _, value in ipairs(paths(group)) do
        local part, start = string.match(value, '^%*(.+)%*$'), string.match(value, '^(.*)%*$')
        for _, text in ipairs(texts) do
            if text == value or (part ~= nil and string.find(text, part, 1, true)) or
                    (part == nil and start ~= nil and string.sub(text, 1, #start) == start) then
                return true
            end
        end
    end
    return false
end

-- A path the kernel answers like a hidden one, which does not exist for the app: a name of its folder which does not
-- exist, or that name in such a stand-in of its folder when the folder is hidden too
function standin(hook, path, param)
    local folder, name = string.match(path, '^(.*)/([^/]*)$')
    if folder ~= nil and folder ~= '' and hidden(hook, folder, param) then
        return standin(hook, folder, param) .. '/' .. name
    end
    local other = path .. '~'
    while present(other, param) and #other < #path + 8 do
        other = other .. '~'
    end
    return other
end

-- link, symlink, rename and renameat, which make a name or move a file: a hidden path, of the new name of link and
-- symlink (symlink does not look its first path up) or either path of rename and renameat, is replaced by a stand-in, so
-- the real call answers like for a missing name, in the order of Linux and with the exception and stack trace of
-- Android: of a name which exists link and symlink give EEXIST before the errors of a missing one, EROFS, EXDEV of link
-- to another file system, and EACCES in a folder of /sys; rename looks its first path up after EXDEV and EROFS (EACCES
-- when it exists), so a SecureDirectoryStream moving a hidden id to the folder of the app gets EXDEV, as without it.
-- The real call cannot make the stand-in, since apps cannot write the folders of the hidden names, or their file system
-- has no links, like the shared storage.
local RENAMED = { link = { 0, 1 }, symlink = { 1 }, rename = { 0, 1 }, renameat = { 1, 3 } }

function renaming(hook, param)
    local known = about(hook)
    local renamed
    for _, index in ipairs(RENAMED[known.method]) do
        local path
        -- None of them follows a link at the end of a path: link makes a link to a link, rename moves the link
        if known.method == 'renameat' then
            path = atpath(hook, param, index, false)
        else
            path = tostring(param:getArgument(index))
            path = filtered(known.group, path, param, false) and path or nil
        end
        if path ~= nil and linked(hook, path, param, false) then
            local other = standin(hook, path, param)
            -- The names of renameat are bytes, which XParam makes of a text (and absolute, so not of the descriptor)
            if known.class == 'android.system.Os' or known.method == 'renameat' then
                param:setArgument(index, other)
            else
                param:setArgument(index, luajava.newInstance('java.io.File', other):toPath())
            end
            renamed = renamed or path
        end
    end
    if renamed == nil then
        return false
    end
    return true, renamed
end

-- Files.newDirectoryStream, list and walk: the names of hidden files are left out by a filter in front of
-- the one of the app; a hidden folder is not opened at all (see the hooks of UnixNativeDispatcher.open, which a
-- SecureDirectoryStream opens its folder with, and of opendir). Listing a folder of /sys is not reported, since nothing
-- is hidden in most of them. The filter can run on another thread, which must not change
-- the tables of this one, so it does not use the cache. Without a filter Android throws NullPointerException.
function listing(hook, param, folder)
    local original = param:getArgument(1)
    local group = about(hook).group
    if original == nil or hidden(hook, folder, param) then
        return false
    end
    local none, jars = unlisted(group, folder, param), libraries(group, folder, param) or {}
    local own = none and ownname(folder, param)
    param:setArgument(1, luajava.createProxy('java.nio.file.DirectoryStream$Filter', {
        accept = function(entry)
            -- Left out as File.list does, so a matched file the app cannot read stays listed, as Android lists it;
            -- of Read.VPN only the names of the interfaces of VPNs are looked up (see after)
            local path = tostring(entry)
            if none and string.match(path, '[^/]*$') == own then
                return original:accept(entry)
            end
            if none or jars[string.match(path, '[^/]*$')] or
                    ((group ~= 'Read.VPN' or isvpninterface(string.match(path, '[^/]+$'))) and
                    matched(group, path, param) and reachable(group, path, param)) then
                return false
            end
            return original:accept(entry)
        end
    }))
    return false
end

-- Whether the store of certificates of the app reads the certificates of the user, which runs in the app and must
-- find them, or TLS with them fails; what the app itself asks the store is filtered (see device_details). The store
-- finds a certificate by the hash of its subject, as a file name; only listing the folder gives all of them, which
-- the accepted issuers of the trust manager and the aliases of the store do, so the listing leaves them out (see after).
function truststore()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if string.find(class, 'conscrypt', 1, true) or string.find(class, 'android.security.net.config', 1, true) then
            return true
        end
    end
    return false
end

-- A path without // and /./ and without /. or / at its end, which the file system reads the same
function collapsed(path)
    local previous
    repeat
        previous = path
        path = string.gsub(string.gsub(path, '/%./', '/'), '//+', '/')
    until path == previous
    return (string.gsub(string.gsub(path, '/%.$', ''), '(.)/$', '%1'))
end

-- The path of a folder the file system lists, the same for the forms of a path which it lists the same, like
-- /system/app/., /system//priv-app, /system/app/ and /x/../app, also the descriptor of the folder, like /proc/self/fd/57
-- of /system/priv-app, and a link of the app to it, which only its resolved path names: a path which names no folder
-- of APPS or FRAMEWORKS by its text is resolved.
-- shortcut: so each listing of Get.Applications and Read.Features of another folder resolves its path once more; the
-- cost was not measured on a device, take the path the filter resolved if apps slow down
function listedpath(folder, param)
    local plain = unrooted(folder)
    -- Relative to the root, which apps run in, like system/priv-app and ../system/priv-app, as the filter takes them
    if string.sub(plain, 1, 1) ~= '/' then
        plain = '/' .. plain
    end
    plain = collapsed(plain)
    if FRAMEWORKS[plain] then
        return plain
    end
    -- Only the folder itself, so /system/app/../priv-app is resolved too
    for _, apps in ipairs(APPS) do
        if plain == apps then
            return plain
        end
    end
    return resolve(plain, param)
end

-- Whether the names of a folder are left out of a listing, but the one of the app itself (see ownname); the files
-- themselves stay (see APPS)
function unlisted(group, folder, param)
    if group ~= 'Get.Applications' then
        return false
    end
    local plain = listedpath(folder, param)
    if plain == '/data/app' or apexapps(plain) then
        return true
    end
    for _, apps in ipairs(APPS) do
        if plain == apps then
            return true
        end
    end
    return false
end

-- A folder of APPS or /data/app by the path it has on every device, without the links of /system to the partitions,
-- like /product/app of /system/product/app
function samefolder(folder)
    for _, partition in ipairs({ 'product', 'system_ext', 'vendor' }) do
        folder = string.gsub(folder, '^/system/' .. partition .. '/', '/' .. partition .. '/')
    end
    return folder
end

-- The folder of APPS or /data/app a path is in and its name there, like /system/priv-app and SettingsProvider of
-- /system/priv-app/SettingsProvider/SettingsProvider.apk, nil for other paths, and whether it is a folder
function appentry(path, apps)
    for _, folder in ipairs(apps or APPS) do
        if string.sub(path, 1, #folder + 1) == folder .. '/' then
            local name = string.match(path, '^[^/]+', #folder + 2)
            if name ~= nil then
                return samefolder(folder), name, #path > #folder + 1 + #name
            end
        end
    end
    return nil
end

-- The folder of APPS or /data/app the app itself is installed in, its name there and whether it is a folder, like
-- /data/app and ~~x== of /data/app/~~x==/com.example-y==/base.apk, read once; false when it is in none, nil when the
-- path of its APK cannot be read
local OWN = nil

function ownentry(param)
    if OWN == nil then
        local ok, source = pcall(function()
            return param:getApplicationContext():getApplicationInfo().sourceDir
        end)
        if not ok or source == nil then
            return nil
        end
        local apps = { '/data/app', string.match(source, '^/apex/[^/]+/[^/]+') }
        for _, folder in ipairs(APPS) do
            apps[#apps + 1] = folder
        end
        local folder, name, nested = appentry(source, apps)
        OWN = folder and { folder = folder, name = name, nested = nested } or false
    end
    return OWN or nil
end

-- The name of the app itself in a folder whose names are left out (see unlisted), which stays, nil when it is not in
-- there; and whether it is a folder, which adds a link to the folder
function ownname(folder, param)
    local own = ownentry(param)
    if own == nil or own.folder ~= samefolder(listedpath(folder, param)) then
        return nil
    end
    return own.name, own.nested
end

-- The link count of a folder whose names are left out (see unlisted), by stat and lstat, of Os and java.nio, and by
-- Os.fstat of a descriptor of it: 2, its name and its own ., like an empty folder, since each folder in it adds one by
-- its .. (ext4 and erofs), and 3 with the folder of the app itself in it, like /data/app.
-- shortcut: Libcore.os.fstat by reflection, which Android calls for every file it opens, so a hook of it would run the
-- script for each, and fstat of java.nio, of the descriptor of a SecureDirectoryStream, still count the folders in it;
-- hook them too if apps use them on these folders.
function linkcount(hook, param)
    local known = about(hook)
    if known.returnType == 'int' or param:getException() ~= nil then
        return false
    end
    local path
    if known.method == 'fstat' then
        local result = param:getResult()
        -- Only a folder (S_IFDIR) with folders in it, which needs the path of the descriptor
        if result == nil or bit32.band(result.st_mode, S_IFMT) ~= S_IFDIR or result.st_nlink <= 2 then
            return false
        end
        path = folderof(param)
    else
        path = pathof(param)
    end
    if path == nil or not unlisted(known.group, path, param) then
        return false
    end
    local own, nested = ownname(path, param)
    local links = (own ~= nil and nested) and 3 or 2
    if known.class == 'sun.nio.fs.UnixNativeDispatcher' then
        -- java.nio reads them into the attributes of its argument, like storage_times changes them
        local attributes = param:getArgument(1)
        local old = attributes and attributes.st_nlink
        if old == nil or old <= links then
            return false
        end
        attributes.st_nlink = links
        return true, tostring(old), tostring(links)
    end
    local result = param:getResult()
    if result == nil or result.st_nlink <= links then
        return false
    end
    param:setResult(luajava.newInstance('android.system.StructStat', result.st_dev, result.st_ino, result.st_mode, links,
            result.st_uid, result.st_gid, result.st_rdev, result.st_size, result.st_atim, result.st_mtim, result.st_ctim,
            result.st_blksize, result.st_blocks))
    return true, tostring(result.st_nlink), tostring(links)
end

-- The path of the descriptor of Os.fstat by its link in /proc/self/fd, nil when it fails; the number is read from the
-- descriptor of the app, by the hidden getInt$, which LuaJ finds by listing the methods (see storage_times)
function folderof(param)
    local fd = param:getArgument(0)
    local ok, path = pcall(function()
        return param:unhooked(function()
            return luajava.bindClass('android.system.Os'):readlink('/proc/self/fd/' .. fd['getInt$'](fd))
        end)
    end)
    return ok and path or nil
end

-- The libraries of the Android platform and the ones of Google on every device, which features does not hide from
-- getSystemSharedLibraryNames, the same as there, which a test compares
local PLATFORM = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { ['com.google.android.maps'] = true, ['com.google.android.media.effects'] = true }

-- Whether a shared library of the system is not part of the Android platform, like vendor of features
function vendor(name)
    if name == nil or GOOGLE[name] then
        return false
    end
    for _, prefix in ipairs(PLATFORM) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return false
        end
    end
    return true
end

-- The jars Read.Features leaves out of the listing of a folder of FRAMEWORKS, by name, nil for other groups and
-- folders: of the libraries features hides from getSystemSharedLibraryNames, named after them, like
-- com.google.android.dialer.support.jar of /product/framework; the jars of AOSP, like framework.jar, are of no library.
-- The files stay, since the class loader of an app with such a library checks its jar with File.isFile when it is made
-- (DexPathList.makeDexElements, AOSP libcore oreo-mr1-release to main), which can be after the hooks are in place.
-- The names are read once per thread, without the hooks of the app, which hide them.
-- shortcut: a jar not named after its library stays listed, read the paths of the libraries from their permission files
-- if makers name jars otherwise; and like a library can be loaded by its name (see features), a jar is found by name.
-- shortcut: the files compiled from the jars in the folders oat of FRAMEWORKS, like oat/arm64/<library>.odex, and the
-- folders framework of other partitions, like /oem and /my_product, still list the names, leave them out too if apps
-- list those folders.
local JARS, KEPT = nil, nil

function libraries(group, folder, param)
    if group ~= 'Read.Features' or not FRAMEWORKS[listedpath(folder, param)] then
        return nil
    end
    return (sharedlibraries(param))
end

-- The jars of the libraries features hides and the permission files of the ones it keeps, like javax.obex.xml, which
-- the build of AOSP makes for each library of the platform in /system/etc/permissions (see basefile), nil when the
-- names cannot be read
function sharedlibraries(param)
    if JARS == nil then
        local ok, names = pcall(function()
            return param:unhooked(function()
                return param:getApplicationContext():getPackageManager():getSystemSharedLibraryNames()
            end)
        end)
        if not ok or names == nil then
            return nil, nil
        end
        -- Made apart and kept at once, since the filter of a listing can ask on another thread (see listing)
        local jars, kept = {}, {}
        for index = 1, names['length'] do
            if vendor(names[index]) then
                jars[names[index] .. '.jar'] = true
            else
                kept[names[index] .. '.xml'] = true
            end
        end
        JARS, KEPT = jars, kept
    end
    return JARS, KEPT
end

-- The bit of reading of access, R_OK, the same on every Linux, and also FileSystem.ACCESS_READ of java.io, while
-- ACCESS_OK, which File.exists asks, is 8
local R_OK = 4

-- A file of /proc/net, denied the way SELinux can deny reading a file: it is there and stat answers, but opening it and
-- access for reading fail with EACCES, also File.canRead and Files.isReadable, which Linux checks the same way; the
-- other calls, like access for whether it exists, get the real answer. A missing file would answer ENOENT, which Linux
-- never gives for a file access finds.
-- shortcut: the ErrnoException of Os is made here, with the frames of the script in its stack trace, like the other
-- errors which are not ENOENT (see before), make it by a real call of a path with EACCES if apps compare traces
function denied(hook, param, path)
    local known = about(hook)
    if known.method == 'access' or known.method == 'checkAccess' then
        if bit32.band(param:getArgument(1), R_OK) == 0 then
            return false
        end
    elseif known.method ~= 'open' then
        return false
    end
    -- A folder, like /proc/net/stat, stays readable: File.list lists it natively, and Linux lists no folder which
    -- File.canRead denies. The names in the folders stay too, of which dev_snmp6 and xt_qtaguid/iface_stat have one
    -- per interface, which Read.VPN leaves out for the interfaces of VPNs only.
    local ok, folder = pcall(function()
        return param:unhooked(function()
            return luajava.newInstance('java.io.File', path):isDirectory()
        end)
    end)
    if ok and folder then
        return false
    end
    -- A file of the interface of a VPN, like /proc/net/dev_snmp6/tun0, does not exist where Read.VPN is restricted too,
    -- whichever of the hooks of the call runs first, since this one leaves the call to the other then: File.isFile asks
    -- the hooks of the app (not unhooked), which those of Read.VPN answer false and those of this group do not hook.
    -- It is false too when the interface went away, and the real call answers ENOENT.
    if matched('Read.VPN', path, param) and not luajava.newInstance('java.io.File', path):isFile() then
        return false
    end
    if known.class == 'java.io.UnixFileSystem' then
        param:setResult(false)
    elseif known.class == 'sun.nio.fs.UnixNativeDispatcher' then
        param:setResult(luajava.newInstance('sun.nio.fs.UnixException', EACCES))
    else
        param:setResult(luajava.newInstance('android.system.ErrnoException', known.method, EACCES))
    end
    return true, path
end

function before(hook, param)
    local class, method = about(hook).class, about(hook).method
    local path
    if method == 'name' or method == 'toString' or method == 'fstat' then
        return false
    elseif method == 'openat' or method == 'fstatat' or method == 'unlinkat' then
        -- unlinkat of a hidden id: ENOENT (see failing), which Linux gives for a missing name before it checks the
        -- permissions; the ids are in /sys, which is mounted read-write, so EROFS does not come first
        path = atpath(hook, param, 1, followslink(method, param))
        if path == nil then
            return false
        end
    elseif RENAMED[method] then
        return renaming(hook, param)
    else
        path = pathof(param)
    end
    -- Files which only the parts of the tables of the process let in, like in a folder maps of the app, are no hidden
    -- files; paths of /proc run the script, since filtered does not know all its links to the root, and so does a link
    -- of the app named like a table to a hidden file, like files/mounts to su, which the filter lets in by its target
    -- shortcut: so each open of a path with such a part outside /proc resolves it once more, also when the filter
    -- resolved it already; the cost was not measured on a device, take the path the filter resolved if apps slow down
    local group, follows = about(hook).group, followslink(method, param)
    if method == 'open' and not string.find(path, '^/?proc/') and tabled(group, path) and
            not filtered(group, path, param, follows) then
        return false
    end
    if class == 'sun.nio.fs.UnixFileSystemProvider' and method == 'newDirectoryStream' then
        return listing(hook, param, path)
    end
    -- Read.VPN denies the files of /proc/net like Read.Network, since on Android 9 and before they name the interface of
    -- a VPN, its counters, addresses and routes, like tun0 in /proc/net/dev; the names of its interfaces in there, like
    -- /proc/net/dev_snmp6/tun0, do not exist (ENOENT, not EACCES). The check of Read.Network leaves the links of the
    -- descriptors of the app alone, like for Read.Network itself (see hidden).
    local found = linked(hook, path, param, follows)
    if group == 'Read.Network' and found or
            group == 'Read.VPN' and not found and linked(hook, path, param, follows, 'Read.Network') then
        return denied(hook, param, path)
    end
    if not found or (string.find(path, 'cacerts', 1, true) and truststore()) then
        return false
    end

    if class == 'libcore.io.BlockGuardOs' or class == 'android.system.Os' then
        -- Os.open, stat, lstat, access and readlink, which FileInputStream uses (to open from Android 10, see below),
        -- and the calls on the attributes, the mode, the owner and links; Os.access and the others of apps are hooked
        -- themselves, since the hooks of BlockGuardOs do not run for them (Android 14)
        local errno = failing(hook, path, param)
        if errno == ENOENT then
            -- The real call with an empty path, which no lookup finds, answers like for a missing file: with the
            -- exception Android makes, of which the stack trace is that of any missing file, without the frames of
            -- the script, and in the order of Android, since Linux 6.11 and later check the name, value and flags of
            -- setxattr and removexattr before they look the path up (EINVAL, ERANGE, E2BIG), 6.13 and later the name
            -- of getxattr (ERANGE), older kernels after, and libcore throws NullPointerException for a null name
            -- or value before the kernel runs
            param:setArgument(0, '')
        elseif method == 'remove' then
            -- EROFS, which the real call gives for the hidden file as for a missing one, with the stack trace of Android
            return false
        else
            -- shortcut: the other errors (EACCES, EROFS) are made here, so their stack trace has the frames of the
            -- script, make them by a real call of a path with the same error if apps compare traces
            param:setResult(luajava.newInstance('android.system.ErrnoException', method, errno))
        end
    elseif class == 'java.io.FileInputStream' or class == 'java.io.FileOutputStream' then
        -- FileInputStream and FileOutputStream of Android 8.1 and 9, which open natively (open0), not by BlockGuardOs:
        -- the exception of the native code, the path the app gave with the error
        -- shortcut: the exception is made here, with the frames of the script in its stack trace (see the errors of Os
        -- above), make it by a real call too if apps compare traces
        param:setResult(luajava.newInstance('java.io.FileNotFoundException',
                path .. ' (' .. REASONS[failing(hook, path, param)] .. ')'))
    elseif class == 'java.io.UnixFileSystem' then
        if method == 'canonicalize0' then
            -- File.getCanonicalPath and getCanonicalFile, which follow links: .. names another folder, . the
            -- folder itself, like /sys/class/net/tun0/., which is a link
            local name = string.match(path, '([^/]+)$')
            if name == '.' then
                local folder = string.match(path, '^(.+)/%.$')
                if folder == nil or not hidden(hook, folder, param) then
                    return false
                end
                path = folder
            elseif name == nil or name == '..' then
                return false
            end
            param:setResult(missing(hook, path, param))
        elseif method == 'createFileExclusively' then
            -- File.createNewFile: apps cannot make files in /sys, and not at all in a folder which does not exist
            -- or on a file system mounted read-only (see making)
            -- shortcut: the exception is made here, with the frames of the script in its stack trace (see the errors
            -- of Os above), make it by a real call too if apps compare traces
            param:setResult(luajava.newInstance('java.io.IOException', REASONS[making(hook, path, param) or EACCES]))
        elseif method == 'list0' then
            -- File.list of a hidden folder: like of a folder which does not exist
            param:setResult(nil)
        elseif method == 'checkAccess' then
            -- File.exists, canRead, canWrite and canExecute; they call Libcore.os.access, but the hook of
            -- BlockGuardOs.access does not run for them (Android 14)
            param:setResult(false)
        elseif method == 'getLastModifiedTime' then
            -- File.lastModified, 0 like of a file which does not exist
            param:setResult(0)
        else
            -- File.isFile and isDirectory: no attributes; File.length, getTotalSpace, getFreeSpace and
            -- getUsableSpace (getLength and getSpace, which call stat and statvfs natively): 0, like of a file
            -- which does not exist
            param:setResult(0)
        end
    else
        -- java.nio.file: Files.exists, the mode of Files.isRegularFile, the rest throws like the kernel, with
        -- UnixException, which has no stack trace
        if method == 'exists' then
            param:setResult(false)
        elseif method == 'stat' and about(hook).returnType == 'int' then
            param:setResult(0)
        else
            param:setResult(luajava.newInstance('sun.nio.fs.UnixException', failing(hook, path, param)))
        end
    end
    return true, path
end

-- Whether a path is of a feature file of the system, by the path or the path it leads to, like with /../, relative to
-- the root, through the descriptor of a folder or a link of the app; not the same names in a folder of the app, nor in
-- /sdcard/etc, shared storage apps can write to
function featurefile(path, param)
    local function found(text)
        if string.find(text, '^/sdcard/') then
            return false
        end
        for _, pattern in ipairs(FEATURE_FILES) do
            if string.find(text, pattern) then
                return true
            end
        end
        return false
    end
    -- The filter lets in links of the app by the path they lead to, like files/h to handheld_core_hardware.xml, also as
    -- files//h, so any other path is resolved, but the files of /sys, which lead to no feature file, while the hooks
    -- of Read.Biometrics run for all of them (see tables)
    local plain = unrooted(collapsed(path))
    if found(plain) then
        return true
    end
    local absolute = string.sub(plain, 1, 1) == '/' and plain or '/' .. plain
    if string.sub(absolute, 1, 5) == '/sys/' and not string.find(absolute, '/..', 1, true) then
        return false
    end
    return found(resolve(plain, param))
end

-- Whether a feature is one of the names of FEATURES
function featured(name, names)
    for _, feature in ipairs(names) do
        if name == feature or
                (string.sub(feature, -1) == '*' and string.sub(name, 1, #feature - 1) == string.sub(feature, 1, -2)) then
            return true
        end
    end
    return false
end

-- The text of a feature file without the features of the names (see FEATURES), and the number left out; as
-- long as the file, which stat and File.length give, by spaces after the root element, which XML allows (like
-- font_config). shortcut: only empty elements, <feature name="..." />, which the files of AOSP and of makers have,
-- upgrade when a device declares a feature as <feature name="..."></feature>.
function withoutfeatures(text, names)
    local count = 0
    local result = string.gsub(text, '%s*<feature%s[^>]*/>', function(element)
        local name = string.match(element, '%sname%s*=%s*"([^"]*)"') or string.match(element, "%sname%s*=%s*'([^']*)'")
        if name ~= nil and featured(name, names) then
            count = count + 1
            return ''
        end
        return nil
    end)
    if #result < #text then
        result = result .. string.rep(' ', #text - #result)
    end
    return result, count
end

-- A feature file the app opened, without the features the group hides (see FEATURES); a hidden file did not open.
-- Only when opened for reading: the text of a file opened for writing cannot be read, and replacing its descriptor
-- would lose what the app writes. The flags are the second argument of both BlockGuardOs.open and
-- UnixNativeDispatcher.open.
-- shortcut: FileInputStream and FileReader of Android 8.1 and 9 open natively (open0), which returns no descriptor to
-- replace, so they read these files in full, with the features of the group, while java.nio reads them without; replace
-- the descriptor of the stream after open0 if apps read the feature files like that there
function featuretext(hook, param)
    local names = FEATURES[about(hook).group]
    if names == nil or bit32.band(param:getArgument(1), O_ACCMODE) ~= 0 or param:getException() ~= nil or
            param:getResult() == nil or not featurefile(pathof(param), param) then
        return false
    end
    local text, count = withoutfeatures(param:getFileText(), names)
    if count == 0 then
        return false
    end
    param:setFileText(text)
    return true, tostring(count)
end

-- Whether a path is of a permission or configuration file of the system (see packagetext), by the path or the path it
-- leads to, like featurefile
function configfile(path, param)
    local function found(text)
        if string.find(text, '^/sdcard/') then
            return false
        end
        for _, etc in ipairs(ETC) do
            if string.find(text, etc .. 'permissions/.+%.xml$') or string.find(text, etc .. 'sysconfig/.+%.xml$') then
                return true
            end
        end
        return false
    end
    local plain = unrooted(path)
    local linked = string.find(plain, 'etc/', 1, true) ~= nil or string.find(plain, '/fd/', 1, true) ~= nil
    return found(plain) or (linked and found(resolve(plain, param)))
end

-- The text of a permission or configuration file without the elements which name another app by its package, like
-- <privapp-permissions package="com.android.settings"> with its permissions and allow-in-power-save of platform.xml,
-- or by the service of a transport of backup, package/class, and the number left out; as long as the file (see
-- withoutfeatures). The elements of the app itself stay.
-- shortcut: other attributes which name apps stay, like target and allowed of allow-association; leave those elements
-- out too if apps read them for the apps of the system.
function withoutpackages(text, own)
    local count = 0
    local function strip(attributes)
        local package = string.match(attributes, '%spackage%s*=%s*"([^"]*)"') or
                string.match(attributes, "%spackage%s*=%s*'([^']*)'") or
                string.match(attributes, '%sservice%s*=%s*"([^"/]*)/') or string.match(attributes, "%sservice%s*=%s*'([^'/]*)/")
        if package == nil or package == own then
            return nil
        end
        count = count + 1
        return ''
    end
    -- Empty elements first, then those with content, of which only the ones with a package are looked at, so the
    -- elements in another one, like the root, are not passed over
    local result = string.gsub(text, '%s*<%a[%w%-]*(%s[^<>]-)/>', strip)
    result = string.gsub(result, '%s*<(%a[%w%-]*)(%s[^<>]-package%s*=[^<>]-[^/<>])>.-</%1%s*>', function(_, attributes)
        return strip(attributes)
    end)
    if #result < #text then
        result = result .. string.rep(' ', #text - #result)
    end
    return result, count
end

-- A permission or configuration file the app opened, without the other apps it names (see withoutpackages), for
-- Get.Applications; only when opened for reading, like featuretext.
-- shortcut: FileInputStream and FileReader of Android 8.1 and 9 open natively (open0), which returns no descriptor to
-- replace, so they read these files with the apps, like the feature files (see featuretext).
function packagetext(hook, param)
    if bit32.band(param:getArgument(1), O_ACCMODE) ~= 0 or param:getException() ~= nil or param:getResult() == nil or
            not configfile(pathof(param), param) then
        return false
    end
    local text, count = withoutpackages(param:getFileText(), param:getPackageName())
    if count == 0 then
        return false
    end
    param:setFileText(text)
    return true, tostring(count)
end

-- File.list of a folder with hidden files: without their names; a feature file opened: without hidden features; a
-- permission file opened: without other apps; the tables of the process an app opened (see tables), the names of the
-- file stores of java.nio (see storename) and the link count of a folder without names (see linkcount)
function after(hook, param)
    if about(hook).method == 'open' then
        if TABLES[about(hook).group] then
            return tables(hook, param)
        elseif about(hook).group == 'Get.Applications' then
            return packagetext(hook, param)
        end
        return featuretext(hook, param)
    elseif about(hook).method == 'name' or about(hook).method == 'toString' then
        return storename(param)
    elseif about(hook).method == 'stat' or about(hook).method == 'lstat' or about(hook).method == 'fstat' then
        return linkcount(hook, param)
    end
    if about(hook).method ~= 'list0' or param:getException() ~= nil then
        return false
    end
    local names = param:getResult()
    if names == nil then
        return false
    end

    local folder = pathof(param)
    local kept = {}
    -- Of Read.VPN only the names of the interfaces of VPNs are looked up, like tun0 of /proc/net/dev_snmp6, since the
    -- filter runs the script for folders of apps too, like a cache of Cronet, and each other name would read the file
    -- system (see matched); the folder of an interface itself is not listed (see before).
    -- shortcut: the names in the folder of a VPN interface through a descriptor opened natively, like /proc/self/fd/57
    -- of /proc/sys/net/ipv4/conf/tun0, stay, look them up too if apps list such descriptors
    local vpn = about(hook).group == 'Read.VPN'
    local jars = libraries(about(hook).group, folder, param) or {}
    local none = unlisted(about(hook).group, folder, param)
    local own = none and ownname(folder, param)
    for index = 1, names['length'] do
        -- Of the root the path is /name, not //name, which the patterns of the root do not match
        if (none and names[index] == own) or (not none and not jars[names[index]] and
                ((vpn and not isvpninterface(names[index])) or
                not hidden(hook, (folder == '/' and '' or folder) .. '/' .. names[index], param))) then
            table.insert(kept, names[index])
        end
    end
    if #kept == names['length'] then
        return false
    end

    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local fake = arrayClass:newInstance(param:findClass('java.lang.String'), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(names['length']), tostring(#kept)
end
