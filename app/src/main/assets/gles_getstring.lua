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
-- apps use for features, also by index (glGetStringi; beyond the kept ones an empty name: the number of
-- GL_NUM_EXTENSIONS stays, since a hook of glGetIntegerv would run for every query of the state of OpenGL) and of EGL.
local VENDOR = 7936 -- GL_VENDOR
local RENDERER = 7937 -- GL_RENDERER
local VERSION = 7938 -- GL_VERSION
local EXTENSIONS = 7939 -- GL_EXTENSIONS
local EGL_EXTENSIONS = 12373 -- EGL_EXTENSIONS
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
    if fake == nil or fake == result then
        -- Beyond the kept ones: an empty name
        if fake == nil then
            param:setResult('')
            return true, result, ''
        end
        return false
    end
    param:setResult(fake)
    return true, result, fake
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    if method == 'glGetStringi' then
        return byindex(param)
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
