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

-- The ICCID of a port of a SIM card, which Android 13 and later give an app without carrier privileges on that port
-- redacted, also for a port which is not active (getUiccPortInfoUnPrivileged of PhoneInterfaceManager). An app with
-- READ_PRIVILEGED_PHONE_STATE never gets it redacted, from getUiccCardsInfo nor from getUiccSlotsInfo, which only such
-- apps may call: it gets none, like a port gives before the records of its card are read, which agrees with the card id
-- of the slot (UiccSlotInfo.getCardId).
local REDACTED = 'FFFFFFFFFFFFFFFFFFFF' -- UiccPortInfo.ICCID_REDACTED

function after(hook, param)
    local result = param:getResult()
    local fake = REDACTED
    if privileged(param) then
        fake = nil
    end
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end

-- Whether the app has READ_PRIVILEGED_PHONE_STATE, asked without the hooks of the app, which could else answer for the
-- script; an error of the check counts as not, which gives the answer of before
function privileged(param)
    local ok, result = pcall(param.unhooked, param, function()
        return param:getApplicationContext():checkSelfPermission('android.permission.READ_PRIVILEGED_PHONE_STATE')
    end)
    return ok and result == 0
end
