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

-- No sync of another app runs or waits: getCurrentSyncs lists none, isSyncActive and isSyncPending (also the hidden
-- one for a user, which isSyncPending calls) say false, getPeriodicSyncs gives none, the hidden status of an account
-- (getSyncStatus) does not wait, and a status listener hears of changes of the sync settings only, not of syncs which
-- wait, start or end (SYNC_OBSERVER_TYPE_PENDING and ACTIVE, and STATUS, hidden).
-- The syncs of the accounts of the app itself stay real everywhere, like the ones its own sync adapter runs (DAVx5
-- syncs its accounts into com.android.contacts), and so does the listener of an app with accounts of its own, which
-- waits for its own syncs by it. Exceptions stay, like the SecurityException without READ_SYNC_STATS.
-- shortcut: the backoff of a failed sync is a change of the settings too, which the listener still hears, leave out
-- those changes when apps are seen to time syncs by them.
-- shortcut: own is by the account type only, so a sync adapter of the app for the accounts of another app (a provider
-- of its own synced with a Google account) is hidden; the listener of an app with accounts of its own hears all syncs,
-- a change does not tell whose it is. Upgrade when such an app is seen to lose its own syncs, or apps time the syncs
-- of others.

-- The types of accounts of the app itself, which keep their names; read once, since a list of accounts is read
-- an account at a time, and the authenticators of the app do not change while it runs
function owntypes(param)
    local context = param:getApplicationContext()
    local own = param:getValue('account.types', context)
    if own == nil then
        own = {}
        -- Without the hook of the types (see account_authenticators), which would report a call of the app
        local auths = param:unhooked(function()
            return luajava.bindClass('android.accounts.AccountManager'):get(context):getAuthenticatorTypes()
        end)
        local packageName = param:getPackageName()
        for index = 1, auths['length'] do
            if auths[index].packageName == packageName then
                own[auths[index].type] = true
            end
        end
        param:putValue('account.types', own, context)
    end
    return own
end

-- Whether a sync of an account is one of the app itself; no account, which isSyncPending takes for any, is not
function own(param, account)
    return account ~= nil and owntypes(param)[account.type] == true
end

-- The syncs of a list which are of the app itself, nil when that are all. Without GET_ACCOUNTS Android gives each sync
-- with the account ***** (SyncInfo.REDACTED_ACCOUNT), so of an authority as many of those stay as accounts of the app
-- itself sync it now.
function ownsyncs(param, syncs)
    local kept = luajava.newInstance('java.util.ArrayList')
    local active = {}
    for index = 0, syncs:size() - 1 do
        local sync = syncs:get(index)
        local account = sync.account
        if own(param, account) then
            kept:add(sync)
        elseif account ~= nil and account.type == '*****' then
            local authority = sync.authority
            active[authority] = active[authority] or ownactive(param, authority)
            if active[authority] > 0 then
                active[authority] = active[authority] - 1
                kept:add(sync)
            end
        end
    end
    if kept:size() == syncs:size() then
        return nil
    end
    return kept
end

-- How many accounts of the app itself sync an authority now, asked without the hooks of the app, which this script
-- answers for isSyncActive too. An app always sees its own accounts, also for the account check of isSyncActive from
-- Android 13, and isSyncActive needs READ_SYNC_STATS like the list of syncs which was given.
function ownactive(param, authority)
    local types = owntypes(param)
    local count = 0
    if next(types) ~= nil then
        local context = param:getApplicationContext()
        param:unhooked(function()
            local manager = luajava.bindClass('android.accounts.AccountManager'):get(context)
            local resolver = luajava.bindClass('android.content.ContentResolver')
            for type in pairs(types) do
                local accounts = manager:getAccountsByType(type)
                for index = 1, accounts['length'] do
                    if resolver:isSyncActive(accounts[index], authority) then
                        count = count + 1
                    end
                end
            end
        end)
    end
    return count
end

-- addStatusChangeListener(mask, callback): the mask keeps SYNC_OBSERVER_TYPE_SETTINGS (1) only. The listener is
-- registered with Android as before, so its handle is real and removeStatusChangeListener takes it.
function before(hook, param)
    if hook:getMethodName() ~= 'addStatusChangeListener' then
        return false
    end
    local mask = param:getArgument(0)
    local kept = mask % 2
    if kept == mask or next(owntypes(param)) ~= nil then
        return false
    end
    param:setArgument(0, kept)
    return true, tostring(mask), tostring(kept)
end

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if method == 'addStatusChangeListener' or param:getException() ~= nil or not result then
        return false
    end
    if method == 'getCurrentSyncs' or method == 'getCurrentSyncsAsUser' then
        local kept = ownsyncs(param, result)
        if kept == nil then
            return false
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    elseif method == 'getCurrentSync' then
        -- The first of getCurrentSyncs, like Android gives, so the first of the own ones
        if own(param, result.account) then
            return false
        end
        local syncs = param:unhooked(function()
            return luajava.bindClass('android.content.ContentResolver'):getCurrentSyncs()
        end)
        local kept = ownsyncs(param, syncs) or syncs
        param:setResult(kept:size() > 0 and kept:get(0) or nil)
        return true
    end
    if own(param, param:getArgument(0)) then
        return false
    end
    if method == 'getPeriodicSyncs' then
        -- A list of its own from the parcel, like Android's
        if result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.ArrayList'))
        return true
    elseif method == 'getSyncStatus' or method == 'getSyncStatusAsUser' then
        -- The SyncStatusInfo of getSyncStatus, of which the field pending is what isSyncPending reads; the app gets a
        -- copy of its own from the parcel (system server, which has the one of the sync storage, is excluded)
        if not result.pending then
            return false
        end
        result.pending = false
        return true
    end
    param:setResult(false)
    return true, 'true', 'false'
end
