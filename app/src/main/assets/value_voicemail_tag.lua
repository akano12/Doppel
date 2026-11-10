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

-- The label of the voicemail number, which a carrier or the user can set: the default label of Android, like
-- "Voicemail" in the language of the device, which Android gives without a label of the SIM; never null, which
-- Android does not give
function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    local id = resources:getIdentifier('defaultVoiceMailAlphaTag', 'string', 'android')
    local fake = (id ~= 0 and resources:getString(id) or 'Voicemail')
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
