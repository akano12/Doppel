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

-- The error of a program which may not run, OsConstants.EACCES, and of one which does not exist, ENOENT, the same
-- on every Linux
local EACCES = 13
local ENOENT = 2

-- Whether the program does not exist, for which the exec fails with ENOENT: a path, of the working directory dir
-- (a file or a path) when it is relative, else a name in the folders of PATH, which Java searches like execvp; as the
-- app sees the files, so a file other hooks hide, like su, does not exist either
function missing(dir, program)
    local class = luajava.bindClass('java.io.File')
    local ok, exists = pcall(function()
        if string.find(program, '/', 1, true) then
            local file = luajava.new(class, program)
            if dir ~= nil and not file:isAbsolute() then
                file = luajava.new(class, dir, program)
            end
            return file:exists()
        end
        for folder in string.gmatch(luajava.bindClass('java.lang.System'):getenv('PATH') or '', '[^:]+') do
            if luajava.new(class, folder, program):exists() then
                return true
            end
        end
        return false
    end)
    return ok and not exists
end

-- Whether the program, a path with a /, is in the native library folder of the app; relative paths are files of the
-- working directory dir (a file or a path), else of the process
function nativeprogram(param, dir, program)
    if not string.find(program, '/', 1, true) then
        return false
    end
    local folder = param:getApplicationContext():getApplicationInfo().nativeLibraryDir
    if folder == nil then
        return false
    end
    local class = luajava.bindClass('java.io.File')
    local resolved, paths = pcall(function()
        local file = luajava.new(class, program)
        if dir ~= nil and not file:isAbsolute() then
            file = luajava.new(class, dir, program)
        end
        return { file:getCanonicalPath(), luajava.new(class, folder):getCanonicalPath() .. '/' }
    end)
    return (resolved and string.sub(paths[1], 1, #paths[2]) == paths[2])
end

-- The exception of UNIXProcess.forkAndExec when the exec fails, the cause of the one of ProcessBuilder.start
function execfailure(dir, program)
    return luajava.new(luajava.bindClass('java.io.IOException'), missing(dir, program) and
            'error=2, No such file or directory' or 'error=13, Permission denied')
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

-- The list of the app while its builder starts with a copy, see before and after
local original

function before(hook, param)
    local method = hook:getMethodName()
    -- Os.execv and execve, static, and Linux.execv and execve, with the exception of the exec; Java throws for a
    -- null program itself
    if method == 'execv' or method == 'execve' then
        local program = param:getArgument(0)
        if program == nil or nativeprogram(param, nil, program) then
            return false
        end
        param:setResult(luajava.newInstance('android.system.ErrnoException', method,
                missing(nil, program) and ENOENT or EACCES))
        return true, program
    end

    -- UNIXProcess.forkAndExec, with the exception of the exec, which the constructor and ProcessImpl.start pass on as
    -- it is; the program and the working directory are C strings; a null program is left to Java
    if method == 'forkAndExec' then
        local program = cstring(param:getArgument(0))
        local dir = cstring(param:getArgument(5))
        if program == nil or nativeprogram(param, dir, program) then
            return false
        end
        param:setResult(execfailure(dir, program))
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
    -- Java throws for an empty command or a null word itself
    local args = copy:toArray()
    local words = {}
    for index = 1, args['length'] do
        if args[index] == nil then
            return false
        end
        words[index] = args[index]
    end
    local dir = builder:directory()
    if #words == 0 or nativeprogram(param, dir, words[1]) then
        return false
    end

    -- The exception of ProcessBuilder.start when the exec fails, with the cause of the exec
    local exception = luajava.bindClass('java.io.IOException')
    local cause = execfailure(dir, words[1])
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
