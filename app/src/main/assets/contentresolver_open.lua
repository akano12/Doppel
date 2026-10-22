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

-- Files of the providers of contacts, messages and voicemails, like contact photos, vCards, attachments of MMS
-- messages (content://mms/part/1) and voicemail recordings, which apps can open by id, without a query
-- (see contentresolver_query), are not found. With contacts.starred, the files of the contacts which the queries
-- keep, like their photos (contacts/<id>/photo, display_photo/<id>) and vCards, are found.

-- The authority of a uri without the user, like 0@com.android.contacts, like in contentresolver_query
function authorityof(uri)
    local authority = uri:getAuthority()
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^%d+@', ''))
end

-- Whether a provider has files of the variant of the hook: the authority contacts is of the former contacts API
-- (Contacts.People), served by the same provider; the voicemails are of Get.Messages, like their queries
function restricts(name, authority)
    return (name == 'contacts' and authority == 'com.android.contacts') or
            (name == 'contacts' and authority == 'contacts') or
            (name == 'mmssms' and authority == 'mms') or
            (name == 'mmssms' and authority == 'mms-sms') or
            (name == 'mmssms' and authority == 'com.google.android.apps.messaging.shared.datamodel.BugleContentProvider') or
            (name == 'mmssms' and authority == 'com.android.voicemail')
end

-- The path of a uri as a table, like { 'contacts', '1', 'photo' }
function segmentsof(uri)
    local segments = {}
    local list = uri:getPathSegments()
    for index = 0, list:size() - 1 do
        table.insert(segments, list:get(index))
    end
    return segments
end

function isid(segment)
    return (segment ~= nil and string.match(segment, '^%d+$') ~= nil)
end

-- Whether a path is of the display photo of a raw contact, which ContactsProvider2 writes for every mode but r,
-- like the rw of RawContacts.DisplayPhoto, through a pipe which cannot be read (openDisplayPhotoForWrite)
function israwphoto(segments)
    return (#segments == 3 and segments[1] == 'raw_contacts' and isid(segments[2]) and segments[3] == 'display_photo')
end

-- The rows of the contacts of a file, which the queries have to find for the file to be found, like contacts/1
-- of contacts/1/photo, also of lookup uris, raw_contacts/1 of raw_contacts/1/display_photo, the photo with the file
-- of display_photo/1 and the contacts of the lookup keys of vCards; nil for other files, like of the profile
function rowsof(uri)
    local segments = segmentsof(uri)
    local count = #segments
    if segments[1] == 'contacts' and (segments[count] == 'photo' or segments[count] == 'display_photo') and
            ((count == 3 and isid(segments[2])) or
                    (segments[2] == 'lookup' and (count == 4 or (count == 5 and isid(segments[4]))))) then
        local path = {}
        for index = 1, count - 1 do
            table.insert(path, segments[index])
        end
        return { { path = path } }
    elseif count == 3 and segments[1] == 'contacts' and segments[2] == 'as_vcard' then
        return { { path = { 'contacts', 'lookup', segments[3] } } }
    elseif count == 3 and segments[1] == 'contacts' and segments[2] == 'as_multi_vcard' then
        -- The provider decodes the keys once more, like Uri.decode
        local uriClass = luajava.bindClass('android.net.Uri')
        local rows = {}
        for key in string.gmatch(segments[3], '[^:]+') do
            table.insert(rows, { path = { 'contacts', 'lookup', uriClass:decode(key) } })
        end
        return rows
    elseif israwphoto(segments) then
        return { { path = { 'raw_contacts', segments[2] } } }
    elseif count == 2 and segments[1] == 'display_photo' and isid(segments[2]) then
        -- The photo file of a photo row is data14 (Photo.PHOTO_FILE_ID)
        return { { path = { 'data' },
                   selection = "mimetype = 'vnd.android.cursor.item/photo' AND data14 = " .. segments[2] } }
    end
    return nil
end

-- Whether all contacts of a file are of the contacts which the queries keep with contacts.starred;
-- the queries are hooked too and filter the same way, see contentresolver_query
function starredfile(param, uri)
    local starred = param:getSetting('contacts.starred')
    if starred ~= '0' and starred ~= '1' then
        return false
    end

    local rows = rowsof(uri)
    if rows == nil or #rows == 0 then
        return false
    end

    local resolver = param:getApplicationContext():getContentResolver()
    for _, row in ipairs(rows) do
        -- The same user, like 10@com.android.contacts, also of a ContentProviderClient for any scheme
        local builder = luajava.newInstance('android.net.Uri$Builder')
        builder:scheme('content')
        builder:encodedAuthority(uri:getEncodedAuthority())
        for _, segment in ipairs(row.path) do
            builder:appendPath(segment)
        end

        local where = 'starred = ' .. starred
        if row.selection ~= nil then
            where = where .. ' AND ' .. row.selection
        end
        local cursor = resolver:query(builder:build(), nil, where, nil, nil)
        if cursor == nil then
            return false
        end
        local found = (cursor:getCount() > 0)
        cursor:close()
        if not found then
            return false
        end
    end
    return true
end

function before(hook, param)
    local uri = param:getArgument(0)
    if uri == nil then
        return false
    end

    -- Hook names are like Function/variant
    local _, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    local authority = authorityof(uri)
    if not restricts(name, authority) then
        return false
    end

    -- ContentResolver opens android.resource and file uris itself, like the resources of the app com.android.contacts;
    -- a ContentProviderClient passes any uri to its provider, which does not look at the scheme
    if hook:getClassName() == 'android.content.ContentResolver' and uri:getScheme() ~= 'content' then
        return false
    end

    -- Writing only reads nothing; the second argument of the typed methods is a type, they read
    local method = hook:getMethodName()
    if method == 'openFile' or method == 'openAssetFile' or method == 'openAssetFileDescriptor' then
        local mode = param:getArgument(1)
        if mode ~= nil and (not string.find(mode, 'r', 1, true) or
                (mode ~= 'r' and authority == 'com.android.contacts' and israwphoto(segmentsof(uri)))) then
            return false
        end
    end

    -- Without the contact or with an error, like without the permission to read contacts, the file is not found
    if name == 'contacts' and authority == 'com.android.contacts' then
        local ok, starred = pcall(starredfile, param, uri)
        if ok and starred then
            return false
        end
    end

    -- Like ContentProvider.openFileHelper for a row which does not exist; the provider is not called
    param:setResult(luajava.newInstance('java.io.FileNotFoundException', 'No entry for ' .. uri:toString()))
    return true
end
