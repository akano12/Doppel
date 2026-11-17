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

-- Apps run programs like getprop, uname, pm, cat and su to read what the other hooks fake, so a restricted app
-- runs no program: the command fails like a program which may not run, or like one which does not exist when there
-- is none, with the error the kernel gives for its path, see failure. Runtime.exec starts processes with
-- ProcessBuilder.start, and Os.execv and execve replace the process with a program. The methods beneath them are
-- hooked too, UNIXProcess.forkAndExec (of ProcessImpl.start and the UNIXProcess constructor) and Linux.execv and
-- execve, as an app can call those by reflection, on Android 8.1 without any trick since it has no restrictions of
-- hidden members. Programs in the native library folder of the app still run: they come with its APK like its native
-- libraries, which Doppel cannot hook either; they are found by their canonical path, so a link to them runs too, but
-- a copy elsewhere does not.
-- One reached by a link also runs by that path, so a link the app changes after the check cannot run another program.
-- File.canExecute, Os.access and Files.isExecutable agree with the exec, see unrunnable.

-- Each group which refuses programs has the same hooks of this script, which act the same whatever their group: once
-- one of them refused a program the others find the program run instead (see failing) or the answer given, so only one
-- hook of each method is installed (see XCore.installMethod), and a call, like the access of File.exists, runs the
-- script once and not once for each restricted group
function shared(hook)
    return true
end

-- The error of a program which may not run, OsConstants.EACCES, and of one which does not exist, ENOENT, the same
-- on every Linux, with the texts of strerror
local EACCES = 13
local ENOENT = 2
local DENIED = 'Permission denied'
local NOFILE = 'No such file or directory'

-- The exec runs another program instead, which fails with the same error before anything of it is opened, so Android
-- throws its own exception, with the frames of its own methods, like forkAndExec, and none of the script; for
-- ProcessBuilder.start the redirects are still opened and the child process made, which changes to the working
-- directory first, like for any program which fails to run. /dev/null is a device without the right to run, which
-- the kernel refuses before SELinux sees it (EACCES), unlike a folder, which before Linux 5.9 is opened first and
-- logged as a denial; /proc has no file descriptor -1 (ENOENT); /dev/null/ wants a folder of a device (ENOTDIR); a
-- path of PATH_MAX bytes is refused before it is looked up (ENAMETOOLONG); /proc/self/root links to the root, so 21
-- of them are 42 links, more than the 40 one path may follow (ELOOP). The numbers are those of OsConstants on arm and
-- x86, which Android runs on.
-- shortcut: another errno, which stat only gives for rare errors like EIO or ENOMEM, gets an exception made by the
-- script, with its frames, and for ProcessBuilder.start without the redirects opened; add a program which fails with
-- it when one is seen
local FAILING = {
    [EACCES] = '/dev/null',
    [ENOENT] = '/proc/self/fd/-1',
    [20] = '/dev/null/',
    [36] = '/' .. string.rep('x', 4095),
    [40] = string.rep('/proc/self/root', 21)
}

-- The program run instead for the errno, nil for another errno
function instead(errno)
    return FAILING[errno]
end

-- Whether the program is one run instead, which the hooks of the same method beneath, of other groups, pass on
function failing(program)
    for _, path in pairs(FAILING) do
        if program == path then
            return true
        end
    end
    return false
end

-- The errno and text of a path which File.exists does not find, from Os.stat, which looks the path up like the exec:
-- ENOTDIR through a file, ENAMETOOLONG, EACCES in a folder which may not be searched, ELOOP; else ENOENT, also when
-- stat finds a file which another hook hides
function absent(path)
    local ok, err = pcall(function()
        luajava.bindClass('android.system.Os'):stat(path)
    end)
    local name, text = string.match(tostring(err), ' failed: (E%u+) %((.-)%)')
    local known, errno = pcall(function()
        return luajava.bindClass('android.system.OsConstants')[name]
    end)
    if ok or name == nil or not known or type(errno) ~= 'number' then
        return ENOENT, NOFILE
    end
    return errno, text
end

-- Calls fn like pcall, with disk reads allowed, so the files the script checks, with the hooks of the app, tell
-- StrictMode of no disk read: the call of the app tells it once itself, like access, or not at all, like the exec;
-- the policy of the thread is restored also when fn fails
function quietly(fn)
    local strict = luajava.bindClass('android.os.StrictMode')
    local policy = strict:allowThreadDiskReads()
    local ok, first, second = pcall(fn)
    strict:setThreadPolicy(policy)
    return ok, first, second
end

-- The errno and text of the exec of a program which is not let run, which may not run when it exists. Os.execv and
-- execve take it as a path, of the working directory of the process when relative, forkAndExec too when it has a /,
-- of the working directory dir (a file or a path) when there is one, else when search is true a name in the folders
-- of PATH, like execvp: EACCES when a folder gave it, else the error of the last one. As the app sees the files, so
-- a file other hooks hide, like su, does not exist either. A working directory which does not work fails the real
-- exec first, in the child of forkAndExec, whatever the program run instead.
-- shortcut: the PATH rules are those of execvp of bionic, which ProcessBuilder uses unless the app set its
-- environment; JDK_execvpe, used then, only differs for a name too long after a folder gave EACCES, so follow it
-- when an app which sets the environment of a builder is seen to read that error
function failure(dir, program, search)
    local class = luajava.bindClass('java.io.File')
    local ok, errno, text = quietly(function()
        if type(dir) == 'string' then
            dir = luajava.new(class, dir)
        end
        if program == '' then
            return ENOENT, NOFILE
        end
        if not search or string.find(program, '/', 1, true) then
            local path, file = program, luajava.new(class, program)
            if dir ~= nil and not file:isAbsolute() then
                path, file = dir:getPath() .. '/' .. program, luajava.new(class, dir, program)
            end
            -- File drops a / at the end, after which the exec, like stat, wants a folder: ENOTDIR for a file
            if file:exists() and (string.sub(program, -1) ~= '/' or file:isDirectory()) then
                return EACCES, DENIED
            end
            return absent(path)
        end
        local errno, text, denied = ENOENT, NOFILE, false
        for folder in string.gmatch(luajava.bindClass('java.lang.System'):getenv('PATH') or '', '[^:]+') do
            local file = luajava.new(class, folder, program)
            if file:exists() then
                return EACCES, DENIED
            end
            errno, text = absent(file:getPath())
            denied = denied or errno == EACCES
        end
        if denied then
            return EACCES, DENIED
        end
        return errno, text
    end)
    if not ok then
        return EACCES, DENIED
    end
    return errno, text
end

-- The path to run the program by, a path with a /, when it is in the native library folder of the app, else nil:
-- its canonical path when a link can be on the path, else the path as it is, which is also the name of the program
-- for ProcessBuilder and forkAndExec. Relative paths are files of the working directory dir (a file or a path), else
-- of the process. Only a path which leads to a file counts: a canonical path also drops . and .. after a folder which
-- does not exist or a file, and File a / at the end, where the exec walks the path as given and fails, so such a
-- path fails like one which does not exist.
-- shortcut: a program reached by a link, or by a path with .., which can follow a link, runs with its real path as
-- its name, which breaks multi-call programs that pick their tool by that name, like toybox; keep the name of the
-- link when an app is seen to rely on it
function nativeprogram(param, dir, program)
    -- shortcut: exec of a bare name is never a program of the app, and File resolves it against user.dir, not the
    -- real working directory; check the name in the real working directory when an app is seen to change it into its
    -- native library folder
    if not string.find(program, '/', 1, true) or string.sub(program, -1) == '/' then
        return nil
    end
    local folder = param:getApplicationContext():getApplicationInfo().nativeLibraryDir
    if folder == nil then
        return nil
    end
    local class = luajava.bindClass('java.io.File')
    local resolved, paths = quietly(function()
        local file = luajava.new(class, program)
        if dir ~= nil and not file:isAbsolute() then
            file = luajava.new(class, dir, program)
        end
        if not file:exists() then
            return nil
        end
        -- With no link on it, the absolute path without its . parts is the canonical path; one with .. never is
        local plain = ''
        for name in string.gmatch(file:getAbsolutePath(), '[^/]+') do
            plain = plain .. (name == '.' and '' or '/' .. name)
        end
        return { file:getCanonicalPath(), luajava.new(class, folder):getCanonicalPath() .. '/', plain }
    end)
    if resolved and paths ~= nil and string.sub(paths[1], 1, #paths[2]) == paths[2] then
        return (paths[1] == paths[3] and program or paths[1])
    end
    return nil
end

-- The bits of access which ask whether a file may run, X_OK, and be written, W_OK, the same on every Linux and as
-- FileSystem.ACCESS_EXECUTE and ACCESS_WRITE of java.io
local X_OK = 1
local W_OK = 2

-- The version of Android
function sdk()
    return luajava.bindClass('android.os.Build$VERSION').SDK_INT
end

-- Whether the check of access for running the file at path, with mode, says no like on a device whose SELinux denies
-- running it, with EACCES, as the exec of it is refused: for a file the app sees, not a folder, whose search stays, nor
-- a program of the native library folder, which runs. A file in the data folders of an app which targets Android 10 or
-- later keeps its answer, since Android itself lets access say yes for it while its exec fails (SELinux allows execute
-- of app_data_file, not execute_no_trans). With W_OK asked too a file the app may not write keeps its answer, EROFS or
-- EACCES, which Linux gives before it asks SELinux. A path with a / at the end, which File drops, keeps its answer
-- too, ENOTDIR for a file, which the kernel gives when it looks the path up, before any check of the right to run.
function unrunnable(param, path, mode)
    local ok, denied = quietly(function()
        local class = luajava.bindClass('java.io.File')
        local file = luajava.new(class, path)
        if string.sub(path, -1) == '/' or not file:isFile() or nativeprogram(param, nil, path) ~= nil or
                (bit32.band(mode, W_OK) ~= 0 and not file:canWrite()) then
            return false
        end
        local info = param:getApplicationContext():getApplicationInfo()
        if sdk() >= 29 and info.targetSdkVersion >= 29 then
            local canonical = file:getCanonicalPath()
            for _, folder in pairs({ info.dataDir, info.deviceProtectedDataDir }) do
                local prefix = luajava.new(class, folder):getCanonicalPath() .. '/'
                if string.sub(canonical, 1, #prefix) == prefix then
                    return false
                end
            end
        end
        return true
    end)
    return ok and denied
end

-- The text of a path which ProcessImpl passes on as a C string, its bytes up to the NUL it ends with
function cstring(bytes)
    if bytes == nil then
        return nil
    end
    local text = luajava.newInstance('java.lang.String', bytes)
    local nul = string.find(text, '\0', 1, true)
    return (nul and string.sub(text, 1, nul - 1) or text)
end

-- A path as a C string again, the bytes of the text, which ByteBuffer.wrap gets from Lua as they are, and a NUL
function cbytes(path)
    return luajava.bindClass('java.nio.ByteBuffer'):wrap(path .. '\0'):array()
end

-- The list of the app while its builder starts with a copy, see before and after
local original
-- The start of the message of ProcessBuilder.start for the program run instead, and for the program of the app
local renamed

function before(hook, param)
    local method = hook:getMethodName()
    -- Os.access and Libcore.os.access (of BlockGuardOs) for X_OK, see unrunnable: the real call for the file run
    -- instead for EACCES, which may not run either, so the exception is that of Android, with its own stack trace;
    -- other modes and a null path are left as they are. Likewise UnixNativeDispatcher.access, of the checkAccess of the
    -- provider of java.nio, whose AccessDeniedException then names the path of the app like for any file which may not
    -- run; the boot image can have it compiled into that checkAccess, which the definitions name to deoptimize.
    -- File.canExecute and Files.isExecutable, see after.
    -- shortcut: UnixFileSystem.checkAccess of java.io, which apps reach by reflection only, keeps the real answer where
    -- the hook of BlockGuardOs.access does not run for it, as on Android 14, and so do its native checkAccess0 of
    -- Android 8.1 and 9, UnixNativeDispatcher.access0 and Linux.access by Libcore.rawOs; hook them when an app is seen
    -- to call them for running a file
    if method == 'access' then
        local path, mode = param:getArgument(0), param:getArgument(1)
        local unix = (hook:getClassName() == 'sun.nio.fs.UnixNativeDispatcher')
        path = (unix and path ~= nil and tostring(path) or path)
        if path == nil or bit32.band(mode, X_OK) == 0 or failing(path) or not unrunnable(param, path, mode) then
            return false
        end
        param:setArgument(0, unix and luajava.newInstance('java.io.File', instead(EACCES)):toPath() or instead(EACCES))
        return true, path
    elseif method == 'canExecute' or method == 'isExecutable' then
        return false
    end

    -- Os.execv and execve, static, and Linux.execv and execve, whose exception does not name the program; Java throws
    -- for a null program itself
    if method == 'execv' or method == 'execve' then
        local program = param:getArgument(0)
        if program == nil or failing(program) then
            return false
        end
        -- The path is apart from the arguments, so the program runs by the path checked and keeps its name
        local path = nativeprogram(param, nil, program)
        if path ~= nil then
            param:setArgument(0, path)
            return false
        end
        local errno = failure(nil, program, false)
        if instead(errno) ~= nil then
            param:setArgument(0, instead(errno))
        else
            param:setResult(luajava.newInstance('android.system.ErrnoException', method, errno))
        end
        return true, program
    end

    -- UNIXProcess.forkAndExec, whose exception the constructor and ProcessImpl.start pass on as it is, the error of the
    -- exec alone; the program, which is also its name, and the working directory are C strings; a null program is left
    -- to Java
    if method == 'forkAndExec' then
        local program = cstring(param:getArgument(0))
        local dir = cstring(param:getArgument(5))
        if program == nil or failing(program) then
            return false
        end
        local path = nativeprogram(param, dir, program)
        if path ~= nil then
            -- Only for another path, so the bytes of the app stay as they are
            if path ~= program then
                param:setArgument(0, cbytes(path))
            end
            return false
        end
        local errno, text = failure(dir, program, true)
        if instead(errno) ~= nil then
            param:setArgument(0, cbytes(instead(errno)))
        else
            param:setResult(luajava.new(luajava.bindClass('java.io.IOException'), 'error=' .. errno .. ', ' .. text))
        end
        return true, program
    end

    local builder = param:getThis()
    local command = builder:command()
    if command == nil then
        return false
    end
    -- The list can be one of the app, which shows other words here than start reads from it, so both read a copy
    -- shortcut: another thread of the app sees the copy, and the program run instead, while start runs; upgrade when a
    -- way to pass start other words than the builder has is found
    local copy = luajava.newInstance('java.util.ArrayList', command)
    original = command
    builder:command(copy)
    -- Java throws for an empty command, a null word or a NUL character in a word after the first itself
    local args = copy:toArray()
    local words = {}
    for index = 1, args['length'] do
        if args[index] == nil or (index > 1 and string.find(args[index], '\0', 1, true)) then
            return false
        end
        words[index] = args[index]
    end
    if #words == 0 or failing(words[1]) then
        return false
    end
    -- The first word is both the program and its name, so the program runs by the path checked
    local dir = builder:directory()
    local path = nativeprogram(param, dir, words[1])
    if path ~= nil then
        copy:set(0, path)
        return false
    end

    local errno, text = failure(dir, words[1], true)
    if instead(errno) ~= nil then
        copy:set(0, instead(errno))
        renamed = { 'Cannot run program "' .. instead(errno) .. '"', 'Cannot run program "' .. words[1] .. '"' }
    else
        local exception = luajava.bindClass('java.io.IOException')
        local cause = luajava.new(exception, 'error=' .. errno .. ', ' .. text)
        param:setResult(luajava.new(exception, 'Cannot run program "' .. words[1] .. '"' ..
                (dir and ' (in directory "' .. dir:getPath() .. '")' or '') .. ': ' .. cause:getMessage(), cause))
    end
    return true, table.concat(words, ' ')
end

-- The exception of ProcessBuilder.start names the program of the app, like after its own exec: the same class, stack
-- trace and cause, like package_probe, else the message would have to be set by reflection on a hidden field; the
-- builder has the list of the app again: used once more, it starts with the words the list has then
function after(hook, param)
    -- File.canExecute and Files.isExecutable say no after their real check, which tells StrictMode about reading the
    -- disk like on any device, false like the real check of a file which may not run, see unrunnable, on every version:
    -- the hooks of access beneath them do not always run, like that of BlockGuardOs.access for File on Android 14, nor
    -- where the boot image compiled access into its caller. The path is the one the check of File takes, getPath, and
    -- of java.nio only a path of the default file system, which the exec could run; a getPath which throws leaves the
    -- answer as it is.
    local method = hook:getMethodName()
    if method == 'canExecute' or method == 'isExecutable' then
        local ok, path = pcall(function()
            if method == 'canExecute' then
                return param:getThis():getPath()
            end
            local target = param:getArgument(0)
            return (target:getClass() == luajava.bindClass('sun.nio.fs.UnixPath') and tostring(target) or nil)
        end)
        if param:getResult() ~= true or not ok or type(path) ~= 'string' or not unrunnable(param, path, X_OK) then
            return false
        end
        param:setResult(false)
        return true, path
    end

    if original ~= nil then
        param:getThis():command(original)
        original = nil
    end
    local names = renamed
    renamed = nil
    if names == nil then
        return false
    end
    local ex = param:getException()
    if ex == nil or ex:getMessage() == nil or string.sub(ex:getMessage(), 1, #names[1]) ~= names[1] then
        return false
    end
    local made = luajava.new(ex:getClass(), names[2] .. string.sub(ex:getMessage(), #names[1] + 1))
    made:setStackTrace(ex:getStackTrace())
    if ex:getCause() ~= nil then
        made:initCause(ex:getCause())
    end
    param:setResult(made)
    return false
end
