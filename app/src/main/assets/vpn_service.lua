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

-- An app which is a VPN starts no tunnel, like one the user has not allowed, since the hooks of vpn hide every VPN
-- from it and a tunnel of its own would show them wrong. VpnService.prepare gives the Intent of the consent dialog
-- without asking Android, like VpnConfig.getIntentForConfirmation, since Android would make an app the user allowed
-- the owner of the VPN and so stop the VPN of another app. Builder.establish gives null without asking Android, like
-- Vpn.establish for a caller which is not prepared, so no tun interface appears, and isAlwaysOn and isLockdownEnabled
-- give false, like for an app which is not the always-on VPN. The platform VPN of Android 11 and later answers the
-- same way: VpnManager.provisionVpnProfile gives the Intent of its own dialog, and starting the profile throws the
-- SecurityException of Vpn.startVpnProfile; startProvisionedVpnProfile throws it itself also from Android 13, where it
-- only calls the session one, in case the boot image inlined that call. getProvisionedVpnProfileState gives null, like
-- for a package which runs no platform VPN. The dialog component is the one of the device, which an OEM may overlay.
-- shortcut: the consent dialog (ConfirmDialog, and PlatformVpnConfirmDialog for provisionVpnProfile) prepares the app
-- like Android, at once for an app the user allowed before and when the user taps OK the first time, which makes it
-- the owner of the VPN and stops the VPN of another app, and answers RESULT_OK, after which prepare still gives the
-- Intent and establish null, so the app may ask again; hook the dialog's prepareVpn in its own process when an app
-- loops or a VPN drops.
-- shortcut: a platform VPN made always-on, which system server starts without a call of the app, or one started before
-- the restriction keeps running, and from Android 13 Android starts the service of the app for VPN_MANAGER_EVENT with
-- its real state, session key and underlying network; also any VPN app, one of VpnService too, which has a service for
-- that action gets the event of always-on being set or unset for it, with the real always-on and lockdown flags; stop
-- the profile and leave out the events in system server when an app needs it.
-- shortcut: only the Java calls are hooked, so an app which calls prepareVpn or establishVpn of the binder service
-- through reflection, which the hidden API checks do not stop on Android 8.1 and may not stop for an app targeting an
-- older version, starts its tunnel; hook the binder proxy when an app does that.

local function confirmation(config)
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    local text = resources:getString(resources:getIdentifier(config, 'string', 'android'))
    local component = luajava.bindClass('android.content.ComponentName'):unflattenFromString(text)
    local intent = luajava.newInstance('android.content.Intent')
    intent:setComponent(component)
    return intent
end

function before(hook, param)
    local method = hook:getMethodName()
    if method == 'prepare' then
        -- A null context throws the NullPointerException of Android
        if param:getArgument(0) == nil then
            return false
        end
        param:setResult(confirmation('config_customVpnConfirmDialogComponent'))
        return true
    elseif method == 'establish' then
        param:setResult(nil)
        return true
    elseif method == 'isAlwaysOn' or method == 'isLockdownEnabled' then
        param:setResult(false)
        return true
    elseif method == 'startProvisionedVpnProfile' or method == 'startProvisionedVpnProfileSession' then
        local name = param:getApplicationContext():getOpPackageName()
        param:setResult(luajava.newInstance('java.lang.SecurityException', 'User consent not granted for package ' .. name))
        return true
    end
    return false
end

function after(hook, param)
    -- The exceptions of Android stay, like the one of provisionVpnProfile for a profile it cannot store, or of
    -- getProvisionedVpnProfileState for a restricted user
    local method = hook:getMethodName()
    if param:getException() ~= nil then
        return false
    elseif method == 'provisionVpnProfile' and param:getResult() == nil then
        param:setResult(confirmation('config_platformVpnConfirmDialogComponent'))
        return true
    elseif method == 'getProvisionedVpnProfileState' and param:getResult() ~= nil then
        param:setResult(nil)
        return true
    end
    return false
end
