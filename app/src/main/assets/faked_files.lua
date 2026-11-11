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

-- The files scripts fake, like /proc/cpuinfo and boot_id, are a file in memory in place of the one the app opened, before
-- Android 11 a removed file of the app, see XParam.setFileText, which tells them apart by its stat, its size and its
-- link in /proc/self/fd. These answer like for the file it replaced, also through RandomAccessFile.length,
-- FileInputStream.available, FileChannel.size, /dev/fd and java.nio, and by the path of the link (Os.stat, File.length,
-- File.lastModified), see XParam.unfakeFile; not for a pipe, which XParam.oldFile makes when it can make no file and
-- which must keep the stat of a pipe. Not covered are Libcore.os.fstat by reflection, /proc/self/fdinfo, the canonical
-- path of the link, a link the app makes to the link (Os.stat, File.length and File.lastModified of it give the fake,
-- since only paths with /fd/ run this script), mapping a file of /sys, native code, the stat of java.nio by the path of
-- the link (Files.size, readAttributes) and the access mode from Android 11: a file in memory is open for writing too
-- (fcntl F_GETFL), so writes of the app do not fail with EBADF and it cannot be opened again read-only, since apps may
-- not open their files in memory (AOSP sepolicy, app_domain in te_macros). shortcut: only for apps restricted by
-- Use.Tracking, though Read.Resources, Read.Identifiers, Read.Timezone and Read.Locale fake files too, so for those the
-- fake tells its own stat, size and link, also the length of its text instead of the size 0 of a file of /proc before
-- Android 11, where a pipe had it; upgrade by copying these hooks into those groups, of which the answers do not change
-- twice.
function filter(hook)
    local name = hook:getMethodName()
    if name == 'readlink' or name == 'stat' or name == 'getLength' or name == 'getLastModifiedTime' then
        return { '*/fd/*' }
    end
    return nil
end

function after(hook, param)
    return param:unfakeFile()
end
