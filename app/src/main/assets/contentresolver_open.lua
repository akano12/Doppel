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

-- Files of the providers of contacts, the call log, messages and voicemails, like contact photos, vCards, call
-- composer pictures, attachments of MMS messages (content://mms/part/1) and voicemail recordings, which apps can open
-- by id, without a query (see contentresolver_query), are not found, with the answer of the provider for a file which
-- is not there. With contacts.starred, the files of the contacts which the queries keep, like their photos
-- (contacts/<id>/photo, display_photo/<id>) and vCards, are found. An app which may not open them gets the answer
-- of Android, like a SecurityException, and uris and modes which the providers answer without looking at their files
-- go to them.

local FLAG_GRANT_READ_URI_PERMISSION = 1
local FLAG_GRANT_WRITE_URI_PERMISSION = 2
local PERMISSION_GRANTED = 0
local MODE_ALLOWED = 0

-- The authority of a uri without the user, like 0@com.android.contacts, like in contentresolver_query
function authorityof(uri)
    local authority = uri:getAuthority()
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^.*@', ''))
end

-- Whether a provider has files of the variant of the hook; the voicemails are of Get.Messages, like their queries.
-- The former contacts API (authority contacts) and mms-sms have none: ContactsProvider2 opens no uri of the former
-- API and MmsSmsProvider opens nothing, so they answer the same whatever is there.
function restricts(name, authority)
    return (name == 'contacts' and authority == 'com.android.contacts') or
            (name == 'call_log' and authority == 'call_log') or
            (name == 'mmssms' and authority == 'mms') or
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
-- like the rw of RawContacts.DisplayPhoto, through a pipe which cannot be read (openDisplayPhotoForWrite);
-- as of Android 12 only for modes with w, and it reads for the others, like rt
function israwphoto(segments)
    return (#segments == 3 and segments[1] == 'raw_contacts' and isid(segments[2]) and segments[3] == 'display_photo')
end

function isvcard(segments)
    local count = #segments
    return (count == 3 and segments[1] == 'contacts' and (segments[2] == 'as_vcard' or segments[2] == 'as_multi_vcard')) or
            (count == 2 and segments[1] == 'profile' and segments[2] == 'as_vcard')
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

-- Whether Android lets the app open a file, else it throws before the provider looks at it: a SecurityException
-- without the permission of the provider, of writing for modes with w, or a grant of the uri, like an attachment the
-- app was sent (ContentProvider.enforceReadPermission and enforceWritePermission); MmsProvider has no permission for
-- writing but the app op of the default SMS app, and else says "App op not allowed".
-- shortcut: the permissions of Google Messages are not known, so its files are not found for any app, upgrade when
-- an app without them tells this from Android
function allowed(param, uri, authority, mode)
    local context = param:getApplicationContext()
    local writing = (string.find(mode, 'w', 1, true) ~= nil)
    local process = luajava.bindClass('android.os.Process')
    local permission
    if authority == 'com.android.contacts' then
        permission = 'android.permission.READ_CONTACTS'
    elseif authority == 'call_log' then
        permission = (writing and 'android.permission.WRITE_CALL_LOG' or 'android.permission.READ_CALL_LOG')
    elseif authority == 'mms' and writing then
        return param:unhooked(function()
            return context:getSystemService('appops'):checkOpNoThrow('android:write_sms', process:myUid(),
                    context:getPackageName()) == MODE_ALLOWED
        end)
    elseif authority == 'mms' then
        permission = 'android.permission.READ_SMS'
    elseif authority == 'com.android.voicemail' then
        return voicemails(param, mode)
    else
        return true
    end

    if context:checkSelfPermission(permission) == PERMISSION_GRANTED then
        return true
    end
    local flag = (writing and FLAG_GRANT_WRITE_URI_PERMISSION or FLAG_GRANT_READ_URI_PERMISSION)
    return context:checkUriPermission(uri, process:myPid(), process:myUid(), flag) == PERMISSION_GRANTED
end

-- Whether VoicemailContentProvider, which checks itself, opens a voicemail of any app: for the default and the system
-- dialer, and with READ_VOICEMAIL for r, also with WRITE_VOICEMAIL for other modes. Other apps, also with a grant of
-- the uri, ADD_VOICEMAIL or carrier privileges, get a SecurityException or open only their own voicemails, since
-- openFileHelper looks for the file with a query of the provider, which keeps only those (getPackageRestrictionClause).
function voicemails(param, mode)
    local context = param:getApplicationContext()
    if param:unhooked(function()
        local telecom = context:getSystemService('telecom')
        -- Public as of Android 10, hidden before
        local ok, system = pcall(telecom.getSystemDialerPackage, telecom)
        local package = context:getPackageName()
        return telecom:getDefaultDialerPackage() == package or (ok and system == package)
    end) then
        return true
    end
    local function holds(name)
        return context:checkSelfPermission('com.android.voicemail.permission.' .. name) == PERMISSION_GRANTED
    end
    return holds('READ_VOICEMAIL') and (mode == 'r' or holds('WRITE_VOICEMAIL'))
end

-- The type which a provider gives a file which is not there (getType), which ContentProvider.openTypedAssetFile
-- compares with the filter of a typed open before it opens anything; nil when it has none, like a photo row (data/1).
-- shortcut: the other uris of contacts are taken as without a type, all uris of voicemails as of one voicemail and
-- the files of Google Messages as parts of MMS, upgrade with the answers for these uris (see missing)
function typeof(uri, authority, segments)
    if authority == 'call_log' then
        return 'application/octet-stream'
    elseif authority == 'com.android.voicemail' then
        return 'vnd.android.cursor.item/voicemail'
    elseif authority == 'com.android.contacts' then
        if isvcard(segments) then
            return 'text/x-vcard'
        end
        -- Photos by contact, lookup key, raw contact and photo file
        return (rowsof(uri) ~= nil and 'image/jpeg' or nil)
    end
    -- MmsProvider looks for the type of a part in its table
    return '*/*'
end

-- The answer of the provider for a file which is not there, like of a contact or a message which the queries hide,
-- for a typed open with the filter, which is nil for the other methods.
-- The providers get the uri without the user (ContentProvider.Transport), but for MMS, whose uris have none here.
function missing(uri, authority, segments, filter)
    local text = (string.gsub(uri:toString(), '^([^:/?#]+://)[^/?#]*@', '%1', 1))
    if filter ~= nil and filter ~= '*/*' then
        local base = typeof(uri, authority, segments)
        if base == nil or not luajava.bindClass('android.content.ClipDescription'):compareMimeTypes(base, filter) then
            return luajava.newInstance('java.io.FileNotFoundException', "Can't open " .. text .. ' as type ' .. filter)
        end
    end

    -- Like ContentProvider.openFileHelper, which MmsProvider and VoicemailContentProvider use
    local message = 'No entry for ' .. text
    if authority == 'call_log' then
        message = text .. ' does not correspond to a valid file.'
    elseif authority == 'com.android.contacts' then
        local count = #segments
        if isvcard(segments) then
            -- An empty vCard, which ContactsProvider2 writes through a pipe; null when it cannot make one
            -- shortcut: the descriptor is the pipe itself, where ContentResolver gives its own wrapper of it
            -- (ContentResolver$ParcelFileDescriptorInner, private), whose class an app can read, upgrade when one does
            local ok, fds = pcall(function()
                return luajava.bindClass('android.os.ParcelFileDescriptor'):createPipe()
            end)
            if not ok then
                return nil
            end
            fds[2]:close()
            return luajava.newInstance('android.content.res.AssetFileDescriptor', fds[1], 0, -1)
        elseif count == 2 and segments[1] == 'display_photo' and isid(segments[2]) then
            -- The id as a number, like Long.parseLong
            message = 'No photo file found for ID ' .. (string.gsub(segments[2], '^0+(%d)', '%1'))
        elseif israwphoto(segments) then
            -- A raw contact which is not there has no photo file, which is 0
            message = 'No photo file found for ID 0'
        else
            -- Like for a contact or a photo row which is not there, like contacts/1/photo and data/1
            -- shortcut: also for photos by lookup key, which ContactsProvider2 reads from an empty cursor for a key it
            -- does not know, and for uris without files ("Stream I/O not supported"), upgrade when checked on a device
            message = text
        end
    end
    return luajava.newInstance('java.io.FileNotFoundException', message)
end

function before(hook, param)
    -- Android throws a NullPointerException for a uri, mode or type which is null before it calls the provider
    local uri = param:getArgument(0)
    if uri == nil or param:getArgument(1) == nil then
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

    -- The second argument of the typed methods is a type, they read
    local method = hook:getMethodName()
    local filter
    local mode = param:getArgument(1)
    if method == 'openTypedAssetFile' or method == 'openTypedAssetFileDescriptor' then
        filter, mode = mode, 'r'
    end
    local segments = segmentsof(uri)

    -- The providers answer these uris and modes without looking at their files: ContactsProvider2 has no openFile,
    -- so ContentProvider says "No files supported by provider at", and refuses every mode but r before it reads, but
    -- for the display photo of a raw contact (see israwphoto); MmsProvider opens only parts, and none with the user
    -- in the uri, which it keeps since it serves all users (singleUser), so its uris do not match (null);
    -- CallLogProvider opens only call composer pictures, and only for r and w (UnsupportedOperationException).
    -- MmsProvider and VoicemailContentProvider look for the file for every mode, also for writing only.
    if authority == 'com.android.contacts' then
        if method == 'openFile' or (mode ~= 'r' and (string.find(mode, 'w', 1, true) or not israwphoto(segments) or
                luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31)) then
            return false
        end
    elseif authority == 'mms' then
        if uri:getAuthority() ~= 'mms' or #segments ~= 2 or segments[1] ~= 'part' or not isid(segments[2]) then
            return false
        end
    elseif authority == 'call_log' then
        -- The provider resolves the name under its folder of pictures, so ., .. and all_users are folders it always or
        -- may have, which it opens or refuses (SecurityException) instead of not finding them.
        -- shortcut: a name with an encoded / (%2F), like x%2F.., is taken as a missing picture, also when it resolves to
        -- such a folder, upgrade when apps open those.
        if (mode ~= 'r' and mode ~= 'w') or #segments ~= 2 or segments[1] ~= 'call_composer' or
                segments[2] == '.' or segments[2] == '..' or segments[2] == 'all_users' then
            return false
        end
    elseif authority ~= 'com.android.voicemail' and not string.find(mode, 'r', 1, true) then
        -- Google Messages, whose provider is not known, is taken to read nothing for modes without r
        return false
    end

    -- Without the permission Android throws before the provider is called; when this fails the file is not found
    local ok, permitted = pcall(allowed, param, uri, authority, mode)
    if ok and not permitted then
        return false
    end

    -- Without the contact or with an error the file is not found
    if authority == 'com.android.contacts' then
        local found, starred = pcall(starredfile, param, uri)
        if found and starred then
            return false
        end
    end

    -- The provider is not called
    param:setResult(missing(uri, authority, segments, filter))
    return true
end
