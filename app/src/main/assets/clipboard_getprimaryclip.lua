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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- Real clips have the time they were copied, made clips none; the setter is hidden, which can be refused
function stamp(fake, real)
    pcall(function() fake:setTimestamp(real:getTimestamp()) end)
end

function after(hook, param)
    local result = param:getResult()
    -- The description of the clip, like from getPrimaryClipDescription: the one of the clip apps get instead,
    -- so the label and the types of the real clip stay hidden
    if hook:getMethodName() == 'getPrimaryClipDescription' then
        if result == nil then
            return false
        end
        local fake = luajava.bindClass('android.content.ClipData'):newPlainText('text', 'Private'):getDescription()
        stamp(fake, result)
        param:setResult(fake)
        -- Not the label, which can name what was copied, like a password; the log keeps what it gets
        return true
    end

    if result == nil or result:getItemCount() == 0 then
        return false
    end

    -- A plain label, which does not tell apps that a privacy module is installed
    local fake = result:newPlainText('text', 'Private')
    pcall(function() stamp(fake:getDescription(), result:getDescription()) end)
    param:setResult(fake)
    return true
end
