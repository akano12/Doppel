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

-- The strings of the OpenGL driver name the graphics chip, like Adreno (TM) 740, which narrows down the model of
-- the device, like Build.MODEL: the renderer and the vendor become unknown, also the family, like Adreno or Mali,
-- which games use for workarounds of drivers; the version keeps its number, like OpenGL ES 3.2, which they parse,
-- the build of the driver goes. The vendor of EGL stays: the EGL loader of Android gives Android on every device.
-- Not covered is native code, which calls the driver itself, like most games and Vulkan.
-- The extensions of vendors, like GL_QCOM_tiled_rendering, GL_ARM_shader_framebuffer_fetch and EGL_IMG_context_priority,
-- name the chip and the driver too; the extensions of Khronos and Android (OES, EXT, KHR, ANDROID, OVR) stay, which
-- apps use for features, also by index (glGetStringi) and of EGL. Their number GL_NUM_EXTENSIONS, which the EGL loader of
-- Android counts from the words of glGetString(GL_EXTENSIONS), is the number of the kept ones, so the script runs for each
-- glGetIntegerv, glGetInteger64v and glGetFloatv of the app; glGetBooleanv gives true for it still, since the loader always
-- has GL_EXT_debug_marker.
-- shortcut: the getters of GLES10, GLES11 and GL10 (GLImpl) go to the driver without the loader, so on a context of OpenGL
-- ES 3 they give its real number; hook them too if apps are seen to count the extensions that way.
local VENDOR = 7936 -- GL_VENDOR
local RENDERER = 7937 -- GL_RENDERER
local VERSION = 7938 -- GL_VERSION
local EXTENSIONS = 7939 -- GL_EXTENSIONS
local EGL_EXTENSIONS = 12373 -- EGL_EXTENSIONS
local NUM_EXTENSIONS = 33309 -- GL_NUM_EXTENSIONS
local KEPT = { 'GL_OES_', 'GL_EXT_', 'GL_KHR_', 'GL_ANDROID_', 'GL_OVR_', 'EGL_KHR_', 'EGL_EXT_', 'EGL_ANDROID_' }

-- The extensions of a list separated by spaces which are not of a vendor, as a table
function kept(list)
    local result = {}
    for name in string.gmatch(list, '%S+') do
        for _, prefix in ipairs(KEPT) do
            if string.sub(name, 1, string.len(prefix)) == prefix then
                result[#result + 1] = name
                break
            end
        end
    end
    return result
end

-- The extensions of the current context without those of vendors, from the real list, kept per context, since
-- apps ask for each index in turn (the script runs per thread, like a context is current per thread)
local CONTEXT = nil
local CONTEXTKEPT = nil

function contextkept(param)
    local context = luajava.bindClass('android.opengl.EGL14'):eglGetCurrentContext():getNativeHandle()
    if CONTEXTKEPT ~= nil and context == CONTEXT then
        return CONTEXTKEPT
    end
    local list
    param:unhooked(function()
        list = luajava.bindClass('android.opengl.GLES20'):glGetString(EXTENSIONS)
    end)
    CONTEXT = context
    CONTEXTKEPT = (list ~= nil and kept(list) or nil)
    return CONTEXTKEPT
end

-- glGetStringi(GL_EXTENSIONS, index): the extension of the index of the kept ones
function byindex(param)
    if param:getArgument(0) ~= EXTENSIONS then
        return false
    end
    local list = contextkept(param)
    local index = param:getArgument(1)
    local result = param:getResult()
    if list == nil or result == nil then
        return false
    end
    local fake = list[index + 1]
    if fake == nil then
        -- Beyond the kept ones, which GL_NUM_EXTENSIONS counts: what the driver gives for an index beyond its own ones,
        -- like for -1, which is the largest index unsigned, with its GL_INVALID_VALUE; no name, or an empty one on some,
        -- like the one of the emulator
        local none
        param:unhooked(function()
            none = luajava.bindClass('android.opengl.GLES30'):glGetStringi(EXTENSIONS, -1)
        end)
        if none == result then
            return false
        end
        param:setResult(none)
        return true, result, none
    end
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end

-- glGetIntegerv, glGetInteger64v and glGetFloatv(GL_NUM_EXTENSIONS): the number of the kept ones, into the array at the
-- offset or into the buffer at its position, like the loader does for every current context, also of OpenGL ES 2
function count(hook, param)
    if param:getArgument(0) ~= NUM_EXTENSIONS then
        return false
    end
    local list = contextkept(param)
    if list == nil then
        return false
    end
    local params = param:getArgument(1)
    local fake = #list
    if hook:getParameterTypes()['length'] == 3 then
        -- Arrays of Lua start at 1; the JNI of glGetInteger64v does not check the length, so the offset may be beyond it
        local index = param:getArgument(2) + 1
        local real = (index <= #params and params[index] or nil)
        if real == nil or real == fake then
            return false
        end
        params[index] = fake
        return true, real, fake
    end
    -- shortcut: a read-only direct buffer keeps the real count the driver wrote, since put throws; write at its address
    -- if apps are seen to pass one
    if params:isReadOnly() then
        return false
    end
    -- The driver writes in the native byte order, which a direct buffer of the other one, like the IntBuffer of
    -- ByteBuffer.allocateDirect, reads swapped
    if not params:order():equals(luajava.bindClass('java.nio.ByteOrder'):nativeOrder()) then
        local int = luajava.bindClass('java.lang.Integer')
        local method = hook:getMethodName()
        if method == 'glGetIntegerv' then
            fake = int:reverseBytes(fake)
        elseif method == 'glGetInteger64v' then
            fake = luajava.bindClass('java.lang.Long'):reverseBytes(fake)
        else
            local float = luajava.bindClass('java.lang.Float')
            fake = float:intBitsToFloat(int:reverseBytes(float:floatToRawIntBits(fake)))
        end
    end
    -- Nor the remaining of a buffer
    local real = (params:hasRemaining() and params:get(params:position()) or nil)
    if real == nil or real == fake then
        return false
    end
    params:put(params:position(), fake)
    return true, real, fake
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    if method == 'glGetStringi' then
        return byindex(param)
    elseif method == 'glGetIntegerv' or method == 'glGetInteger64v' or method == 'glGetFloatv' then
        return count(hook, param)
    end
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- eglQueryString(display, name): the name is the second argument
    local name = param:getArgument(method == 'eglQueryString' and 1 or 0)
    local fake
    if name == EXTENSIONS or name == EGL_EXTENSIONS then
        fake = table.concat(kept(result), ' ')
        -- Lists end with a space, like of most drivers
        if string.sub(result, -1) == ' ' and fake ~= '' then
            fake = fake .. ' '
        end
    elseif method == 'eglQueryString' then
        return false
    elseif name == RENDERER or name == VENDOR then
        fake = 'unknown'
    elseif name == VERSION then
        fake = string.match(result, '^OpenGL ES[%-%a]* %d+%.%d+')
    end
    if fake == nil or fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
