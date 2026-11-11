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

-- The result data of the broadcast of an outgoing call is its number, like the extra which intent_createfromparcel
-- leaves out; none, like Telecom sends without a number. Only what the app reads: the data Android gets back stays,
-- so the call goes on.
-- shortcut: only the number Android sent, a number which an app with a higher priority changed stays, hide it too
-- once a hook knows the intent of the receiver.
-- shortcut: an app which passes the broadcast on with setResultData(getResultData()) sets none, which cancels the call
-- in Telecom (NewOutgoingCallBroadcastIntentReceiver), while on Android it changes nothing; setResultData(null) is also
-- how an app cancels a call on purpose, so a hook cannot tell the two apart; keep the hooks in their own group once
-- the user wants calls of such apps to go on.
function after(hook, param)
    local result = param:getResult()
    if result == nil or result ~= param:getValue('call.outgoing', param:getApplicationContext()) then
        return false
    end
    param:setResult(nil)
    return true
end
