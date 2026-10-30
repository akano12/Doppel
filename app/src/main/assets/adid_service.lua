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

-- Apps can ask the advertising id service of Play services themselves, with their own copy of its interface,
-- instead of AdvertisingIdClient (see advertisingidclient$info_getid). The connection to the service gets a
-- binder in front of it, which answers like the service after the user deleted the advertising id: the id of
-- zeros and limited ad tracking; other calls go to the service. All connections of an app pass here, also with
-- an executor, and unbindService still finds the connection of the app.
local SERVICE = 'com.google.android.gms.ads.identifier.service.AdvertisingIdService'
local GET_ID = 1 -- IAdvertisingIdService.getId
local IS_LIMITED = 2 -- IAdvertisingIdService.isLimitAdTrackingEnabled
local ZEROS = '00000000-0000-0000-0000-000000000000'

function before(hook, param)
    local name = param:getArgument(0)
    local service = param:getArgument(1)
    if name == nil or service == nil or name:getClassName() ~= SERVICE then
        return false
    end

    -- Every method of IBinder and Object, since a proxy returns null for the others, which a boolean cannot be
    param:setArgument(1, luajava.createProxy('android.os.IBinder', {
        transact = function(code, data, reply, flags)
            if (code == GET_ID or code == IS_LIMITED) and reply ~= nil then
                reply:writeNoException()
                if code == GET_ID then
                    reply:writeString(ZEROS)
                else
                    reply:writeInt(1)
                end
                -- Like Binder.transact, so the app reads the reply from its start
                reply:setDataPosition(0)
                return true
            end
            return service:transact(code, data, reply, flags)
        end,
        queryLocalInterface = function(descriptor)
            return nil
        end,
        getInterfaceDescriptor = function()
            return service:getInterfaceDescriptor()
        end,
        pingBinder = function()
            return service:pingBinder()
        end,
        isBinderAlive = function()
            return service:isBinderAlive()
        end,
        linkToDeath = function(recipient, flags)
            service:linkToDeath(recipient, flags)
        end,
        unlinkToDeath = function(recipient, flags)
            return service:unlinkToDeath(recipient, flags)
        end,
        dump = function(fd, args)
            service:dump(fd, args)
        end,
        dumpAsync = function(fd, args)
            service:dumpAsync(fd, args)
        end,
        getExtension = function()
            return service:getExtension()
        end,
        hashCode = function()
            return service:hashCode()
        end,
        equals = function(other)
            return service:equals(other)
        end,
        toString = function()
            return service:toString()
        end
    }))
    return true
end
