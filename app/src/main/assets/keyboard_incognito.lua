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

-- Text fields send their settings to the keyboard as an EditorInfo; the flag asks the keyboard not to learn what is
-- typed, like the incognito mode of browsers. Keyboards may ignore it, Gboard and others follow it.
local NO_PERSONALIZED_LEARNING = 0x1000000 -- EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

function before(hook, param)
    local info = param:getThis()
    local options = info.imeOptions
    if bit32.band(options, NO_PERSONALIZED_LEARNING) ~= 0 then
        return false
    end
    -- Added, not or-ed: the options can have the sign bit set, which bit32 would return as a number too big for an int
    info.imeOptions = options + NO_PERSONALIZED_LEARNING
    return true, tostring(options), tostring(info.imeOptions)
end
