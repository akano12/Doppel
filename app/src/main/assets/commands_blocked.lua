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
-- runs no program: the command fails like a program which may not run, before it starts, or like one which does not
-- exist when there is none. Runtime.exec starts processes with ProcessBuilder.start, and Os.execv and execve
-- replace the process with a program. The methods beneath them are hooked too, UNIXProcess.forkAndExec (of
-- ProcessImpl.start and the UNIXProcess constructor) and Linux.execv and execve, as an app can call those by
-- reflection, on Android 8.1 without any trick since it has no restrictions of hidden members. Programs in the native
-- library folder of the app still run: they come with its APK like its native libraries, which Doppel cannot hook
-- either; they are found by their canonical path, so a link to them runs too, but a copy elsewhere does not.
-- One reached by a link also runs by that path, so a link the app changes after the check cannot run another program.
-- shortcut: File.canExecute and Os.access with X_OK still say yes for programs like sh, getprop and id, whose exec
-- fails with EACCES, a pair a device without Doppel does not show for them; hook those too when an app is seen to
-- compare the two

-- The error of a program which may not run, OsConstants.EACCES, and of one which does not exist, ENOENT, the same
-- on every Linux, with the texts of strerror
local EACCES = 13
local ENOENT = 2
local DENIED = 'Permission denied'
local NOFILE = 'No such file or directory'

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

-- The errno and text of the exec of a program which is not let run, in the order the child of forkAndExec meets
-- them: the working directory dir (a file or a path), then the program, which may not run when it exists. Os.execv
-- and execve take it as a path, of the working directory of the process when relative, forkAndExec too when it has
-- a /, else when search is true a name in the folders of PATH, like execvp: EACCES when a folder gave it, else the
-- error of the last one. As the app sees the files, so a file other hooks hide, like su, does not exist either.
-- shortcut: the PATH rules are those of execvp of bionic, which ProcessBuilder uses unless the app set its
-- environment; JDK_execvpe, used then, only differs for a name too long after a folder gave EACCES, so follow it
-- when an app which sets the environment of a builder is seen to read that error
function failure(dir, program, search)
    local class = luajava.bindClass('java.io.File')
    local ok, errno, text = pcall(function()
        if type(dir) == 'string' then
            dir = luajava.new(class, dir)
        end
        if dir ~= nil and not dir:isDirectory() then
            -- chdir fails first; with a / at the end stat fails for a file with ENOTDIR too
            return absent(dir:getPath() .. '/')
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
    if not string.find(program, '/', 1, true) or string.sub(program, -1) == '/' then
        return nil
    end
    local folder = param:getApplicationContext():getApplicationInfo().nativeLibraryDir
    if folder == nil then
        return nil
    end
    local class = luajava.bindClass('java.io.File')
    local resolved, paths = pcall(function()
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

-- The text of a path which ProcessImpl passes on as a C string, its bytes up to the NUL it ends with
function cstring(bytes)
    if bytes == nil then
        return nil
    end
    local text = luajava.newInstance('java.lang.String', bytes)
    local nul = string.find(text, '\0', 1, true)
    return (nul and string.sub(text, 1, nul - 1) or text)
end

-- The FileNotFoundException of a file which does not open, from the text of the error, which is all pcall gives:
-- its message and, from Android 10 on, which opens files with IoBridge.open, the ErrnoException of open as cause
-- unless the path is invalid; nil for another error
function unopened(err)
    local message = string.match(tostring(err), '^vm error: java%.io%.FileNotFoundException: (.*)$')
    if message == nil then
        return nil
    end
    local ex = luajava.new(luajava.bindClass('java.io.FileNotFoundException'), message)
    local name = string.match(message, ': open failed: (E%u+) %([^()]*%)$')
    local known, errno = pcall(function()
        return luajava.bindClass('android.system.OsConstants')[name]
    end)
    if name ~= nil and known and type(errno) == 'number' then
        ex:initCause(luajava.newInstance('android.system.ErrnoException', 'open', errno))
    end
    return ex
end

-- ProcessBuilder.start opens the files the builder redirects to before the exec, like ProcessImpl.start in this
-- order, so files written are created or truncated also when the program does not run; the exception of a file
-- which does not open comes before that of the exec, else nil
function redirected(builder)
    -- By the methods of Redirect: each redirect is of an anonymous class, whose methods reflection may not call
    local Redirect = luajava.bindClass('java.lang.ProcessBuilder$Redirect')
    local streams, thrown = {}, nil
    pcall(function()
        for index, redirect in ipairs({ builder:redirectInput(), builder:redirectOutput(), builder:redirectError() }) do
            local file = Redirect.file(redirect)
            if file ~= nil then
                local ok, stream
                if index == 1 then
                    ok, stream = pcall(luajava.new, luajava.bindClass('java.io.FileInputStream'), file)
                else
                    ok, stream = pcall(luajava.new, luajava.bindClass('java.io.FileOutputStream'), file,
                            Redirect.type(redirect):name() == 'APPEND')
                end
                if not ok then
                    thrown = unopened(stream)
                    return
                end
                streams[#streams + 1] = stream
            end
        end
    end)
    for _, stream in ipairs(streams) do
        pcall(function()
            stream:close()
        end)
    end
    return thrown
end

-- The list of the app while its builder starts with a copy, see before and after
local original

function before(hook, param)
    local method = hook:getMethodName()
    -- Os.execv and execve, static, and Linux.execv and execve, with the exception of the exec; Java throws for a
    -- null program itself
    if method == 'execv' or method == 'execve' then
        local program = param:getArgument(0)
        if program == nil then
            return false
        end
        -- The path is apart from the arguments, so the program runs by the path checked and keeps its name
        local path = nativeprogram(param, nil, program)
        if path ~= nil then
            param:setArgument(0, path)
            return false
        end
        param:setResult(luajava.newInstance('android.system.ErrnoException', method, (failure(nil, program, false))))
        return true, program
    end

    -- UNIXProcess.forkAndExec, with the exception of the exec, which the constructor and ProcessImpl.start pass on as
    -- it is; the program, which is also its name, and the working directory are C strings; a null program is left to
    -- Java. ProcessImpl.start opened the files of the redirects already.
    if method == 'forkAndExec' then
        local program = cstring(param:getArgument(0))
        local dir = cstring(param:getArgument(5))
        if program == nil then
            return false
        end
        local path = nativeprogram(param, dir, program)
        if path ~= nil then
            -- As a C string again, the bytes of the text, which ByteBuffer.wrap gets from Lua as they are, and a NUL;
            -- only for another path, so the bytes of the app stay as they are
            if path ~= program then
                param:setArgument(0, luajava.bindClass('java.nio.ByteBuffer'):wrap(path .. '\0'):array())
            end
            return false
        end
        local errno, text = failure(dir, program, true)
        param:setResult(luajava.new(luajava.bindClass('java.io.IOException'), 'error=' .. errno .. ', ' .. text))
        return true, program
    end

    local builder = param:getThis()
    local command = builder:command()
    if command == nil then
        return false
    end
    -- The list can be one of the app, which shows other words here than start reads from it, so both read a copy
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
    if #words == 0 then
        return false
    end
    -- The first word is both the program and its name, so the program runs by the path checked
    local dir = builder:directory()
    local path = nativeprogram(param, dir, words[1])
    if path ~= nil then
        copy:set(0, path)
        return false
    end

    -- The exception of ProcessBuilder.start when the exec fails, with the cause of the exec or of a redirect
    local exception = luajava.bindClass('java.io.IOException')
    local cause = redirected(builder)
    if cause == nil then
        local errno, text = failure(dir, words[1], true)
        cause = luajava.new(exception, 'error=' .. errno .. ', ' .. text)
    end
    param:setResult(luajava.new(exception, 'Cannot run program "' .. words[1] .. '"' ..
            (dir and ' (in directory "' .. dir:getPath() .. '")' or '') .. ': ' .. cause:getMessage(), cause))
    return true, table.concat(words, ' ')
end

-- The builder has the list of the app again: used once more, it starts with the words the list has then
function after(hook, param)
    if original ~= nil then
        param:getThis():command(original)
        original = nil
    end
    return false
end
