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

-- The message is not sent. A call which Android refuses in the app before it sends still runs, so the app gets the
-- answer of its Android version: arguments Android throws for, and a text or data message without the SMS service.
-- An app without SEND_SMS gets the SecurityException of the phone process without the call, since the user could grant
-- the permission while the call is on its way, and the phone process would then send the message. Sending without
-- persisting needs carrier privileges, or MODIFY_PHONE_STATE and SEND_SMS: Android 9 and later ask for them in this
-- order, so a carrier app needs no SEND_SMS, and Android 8.1 asks for SEND_SMS first and then for the others with an
-- exception of its own. From Android 10 a skipped message fails like one of an app of which the user denied sending
-- messages (the app op): the sent intent of each part gets RESULT_ERROR_GENERIC_FAILURE and a delivery intent nothing;
-- Android 8.1 and 9 answer such a message with nothing, and so do all versions for a multimedia message.
-- shortcut: a device without a SIM, or with two and no default, answers a denied message, and from Android 10 one of
-- an app without SEND_SMS, with RESULT_NO_DEFAULT_SMS_APP or a question for the SIM first; and from Android 14 a data
-- or multipart message, or one with options, of an app without SEND_SMS which the work profile or the fixed dialing
-- numbers do not allow gets RESULT_USER_NOT_ALLOWED or RESULT_ERROR_FDN_CHECK_FAILURE instead of the exception;
-- answer the same when an app needs it.
-- shortcut: without the SMS service a multimedia message of the default subscription throws from Android 10 too, but
-- is skipped, since Android sends one of another subscription; from Android 15 the service throws for a device with
-- telephony but without messaging, for apps targeting Android 15 on a vendor partition of Android 15 or later, which
-- the script does not tell apart, so the message is skipped, or refused without SEND_SMS; and the phone process could
-- start again and add the service between the look-up of the script and that of Android, which would then send the
-- message; answer these when an app needs it.
-- shortcut: the IMS app of the carrier may send without persisting without carrier privileges and MODIFY_PHONE_STATE,
-- which the script does not tell from other apps; carrier privileges are those of the default subscription, not of the
-- subscription of the message; and Android 8.1 answers an app of which the app op is denied with nothing before it
-- asks for the privileges, and a device without a SIM card with 'No Carrier Privilege: No UICC'; answer these when an
-- app needs it.
function before(hook, param)
    if refused(hook, param) then
        return false
    end
    local method = hook:getMethodName()
    local denial = denied(method, param)
    if denial ~= nil then
        param:setResult(luajava.newInstance('java.lang.SecurityException', denial))
        return true
    end
    param:setResult(nil)
    if method ~= 'sendMultimediaMessage' and luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 29 then
        for _, intent in ipairs(sentIntents(method, param)) do
            -- SmsManager.RESULT_ERROR_GENERIC_FAILURE; Android ignores an intent which was canceled
            pcall(intent.send, intent, 1)
        end
    end
    return true
end

-- Whether Android refuses the call before it sends: an empty address, text, data or list of parts, like
-- TextUtils.isEmpty tells, a single part with an empty text, which Android sends as a text, and no content; from
-- Android 11 no context of a multimedia message, which looks up the service with it; on Android 9 the priority and
-- validity of the overloads with options out of range, also when not given (-1); and a text or data message without
-- the SMS service
function refused(hook, param)
    local method = hook:getMethodName()
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    if method == 'sendMultimediaMessage' then
        if param:getArgument(1) == nil or (sdk >= 30 and param:getArgument(0) == nil) then
            return true
        end
    else
        if empty(param:getArgument(0)) then
            return true
        end
        if method == 'sendDataMessage' then
            local data = param:getArgument(3)
            if data == nil or data.length == 0 then
                return true
            end
        elseif method == 'sendMultipartTextMessage' then
            local parts = param:getArgument(2)
            if parts == nil or parts:size() == 0 or (parts:size() == 1 and empty(parts:get(0))) then
                return true
            end
        elseif empty(param:getArgument(2)) then
            return true
        end
        if sdk == 28 and hook:getParameterTypes()['length'] == 8 then
            local priority = param:getArgument(5)
            local validity = param:getArgument(7)
            if priority < 0 or priority > 3 or validity < 5 or validity > 0x09b0a0 then
                return true
            end
        end
        if nosms(param) then
            return true
        end
    end
    return false
end

-- The message of the SecurityException of Android for an app which may not send the message, or nil: like
-- Context.enforceCallingPermission in the phone process, or for a multimedia message in system server
function denied(method, param)
    local persisted = (method ~= 'sendTextMessageWithoutPersisting')
    local old = (luajava.bindClass('android.os.Build$VERSION').SDK_INT < 28)
    local missing
    if not persisted and not old then
        if carrier(param) then
            return nil
        end
        if not granted(param, 'MODIFY_PHONE_STATE') then
            missing = 'MODIFY_PHONE_STATE'
        end
    end
    if missing == nil and not granted(param, 'SEND_SMS') then
        missing = 'SEND_SMS'
    end
    if missing ~= nil then
        local what = (method == 'sendMultimediaMessage' and 'Send MMS message' or 'Sending SMS message')
        local uid = luajava.bindClass('android.os.Process'):myUid()
        return what .. ': uid ' .. uid .. ' does not have android.permission.' .. missing .. '.'
    end
    if not persisted and old and not granted(param, 'MODIFY_PHONE_STATE') and not carrier(param) then
        return 'No Carrier Privilege.'
    end
    return nil
end

-- Whether the app has the permission, asked without the hooks of the app, which could else answer for the script;
-- an error of the check counts as granted, since the message is then skipped and not sent
function granted(param, permission)
    local ok, result = pcall(param.unhooked, param, function()
        return param:getApplicationContext():checkSelfPermission('android.permission.' .. permission)
    end)
    return not ok or result == 0
end

-- Whether the app has carrier privileges, asked like granted
function carrier(param)
    local ok, result = pcall(param.unhooked, param, function()
        return param:getApplicationContext():getSystemService('phone'):hasCarrierPrivileges()
    end)
    return not ok or result == true
end

-- Whether the SMS service of the phone process is missing, like on a device without telephony from Android 15, or on
-- a build without the phone app: Android looks it up the same way and then throws an UnsupportedOperationException,
-- "Sms is not supported", for a text or data message, before it could send. An error of the lookup counts as a
-- service, since the message would else be sent.
function nosms(param)
    local ok, service = pcall(param.unhooked, param, function()
        return luajava.bindClass('android.os.ServiceManager'):getService('isms')
    end)
    return ok and service == nil
end

function empty(text)
    return text == nil or text == ''
end

-- The sent intents Android answers: of the message, or of each part of a message of more parts;
-- a message of one part is sent as a text, with the first sent intent
function sentIntents(method, param)
    local intents = {}
    if method == 'sendMultipartTextMessage' then
        local list = param:getArgument(3)
        if list ~= nil then
            local count = list:size()
            if param:getArgument(2):size() == 1 and count > 1 then
                count = 1
            end
            for i = 0, count - 1 do
                intents[#intents + 1] = list:get(i)
            end
        end
    else
        intents[1] = param:getArgument(method == 'sendDataMessage' and 4 or 3)
    end
    return intents
end
