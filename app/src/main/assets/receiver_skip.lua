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

-- Broadcasts which Android does not send on a device with the fake values do not reach the receivers of the app, per
-- variant: a change of the time zone (timezone), since the fake zone stays the same; Android sends it only when the
-- zone changed (AlarmManagerService). The receiver is not called, and Android is told it finished, like after an
-- onReceive which returned at once.
-- shortcut: Android still starts the process of an app which is not running for a receiver of its manifest, and an
-- ordered broadcast to a registered receiver, or from Android 14 one Android waits for, is still delivered (with the
-- fake zone, see intent_createfromparcel); upgrade when Android itself can leave out the app.
local ACTIONS = { timezone = 'android.intent.action.TIMEZONE_CHANGED' }

function before(hook, param)
    -- Hook names are like Function/variant
    local action = ACTIONS[string.match(hook:getName(), '/(.*)$')]
    local method = hook:getMethodName()
    local data = param:getArgument(0)
    if data == nil then
        return false
    end
    local intent = (method == 'handleReceiver' and data.intent or data)
    if intent == nil or intent:getAction() ~= action then
        return false
    end
    if method == 'handleReceiver' then
        -- A receiver of the manifest, which Android waits for: what handleReceiver does after onReceive (ReceiverData is
        -- the PendingResult of the broadcast); first, so a failure delivers the broadcast like before
        data:finish()
    elseif param:getArgument(4) or (hook:getParameterTypes()['length'] == 10 and not param:getArgument(6)) then
        -- A registered receiver tells Android it finished (PendingResult.finish) only of an ordered broadcast and, from
        -- Android 14, when Android does not assume it delivered (assumeDelivered); else nothing goes back to Android, so
        -- leaving out ReceiverDispatcher.performReceive is the same as an onReceive which does nothing
        return false
    end
    param:setResult(nil)
    return true
end
