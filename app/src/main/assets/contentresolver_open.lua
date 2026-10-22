-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- Files of the providers of contacts and messages, like contact photos, vCards and attachments of MMS messages
-- (content://mms/part/1), which apps can open by id, without a query (see contentresolver_query), are not found.
-- Photos of starred contacts, when they stay visible (contacts.starred), are still found by the query of
-- contacts/<id>/photo, which ContactsContract.Contacts.openContactPhotoInputStream does after a failed open.

-- The authority of a uri without the user, like 0@com.android.contacts, like in contentresolver_query
function authorityof(uri)
    local authority = uri:getAuthority()
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^%d+@', ''))
end

function before(hook, param)
    local uri = param:getArgument(0)
    if uri == nil then
        return false
    end

    -- Writing only reads nothing; the second argument of the typed methods is a type, they read
    local method = hook:getMethodName()
    if method == 'openFile' or method == 'openAssetFile' or method == 'openAssetFileDescriptor' then
        local mode = param:getArgument(1)
        if mode ~= nil and not string.find(mode, 'r', 1, true) then
            return false
        end
    end

    -- Hook names are like Function/variant
    local _, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    local authority = authorityof(uri)
    if (name == 'contacts' and authority == 'com.android.contacts') or
            (name == 'contacts' and authority == 'contacts') or
            (name == 'mmssms' and authority == 'mms') or
            (name == 'mmssms' and authority == 'mms-sms') or
            (name == 'mmssms' and authority == 'com.google.android.apps.messaging.shared.datamodel.BugleContentProvider') then
        -- Like ContentProvider.openFileHelper for a row which does not exist; the provider is not called
        param:setResult(luajava.newInstance('java.io.FileNotFoundException', 'No entry for ' .. uri:toString()))
        return true
    end
    return false
end
