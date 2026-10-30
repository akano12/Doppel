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

-- The description of the clip, like from getPrimaryClipDescription: the one of the clip apps get instead,
-- see clipdata_createfromparcel, so the label and the types of the real clip stay hidden
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake = luajava.bindClass('android.content.ClipData'):newPlainText('text', 'Private'):getDescription()
    param:setResult(fake)
    -- Not the label, which can name what was copied, like a password; the log keeps what it gets
    return true
end
