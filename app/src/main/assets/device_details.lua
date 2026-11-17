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

-- Small details of the device which together set it apart, with the values of a plain device:
-- - the heat (PowerManager): no thermal headroom known (NaN, like devices without the HAL), no throttling (0), also
--   for listeners;
-- - the audio output: 256 frames per buffer, which tells the audio chip, also in the least buffer of a track
--   (AudioTrack.getMinBufferSize), which Android works out of it; the sample rate stays, which is 48000 or 44100 Hz on
--   nearly all devices and tells little, while the app plays at it. shortcut: native code (AAudio, OpenSL ES), the
--   buffer of a track the app made and AudioRecord.getMinBufferSize still show the real ones; hook the getters of
--   AudioTrack when apps are seen comparing them;
-- - the vibrator: no amplitude control, effects and primitives unknown or not supported, no resonance (NaN),
--   which apps answer with a plain vibration;
-- - the certificates the user installed (AndroidCAStore, user: aliases), like of a VPN, proxy or ad blocker, are not
--   listed, also not among the accepted issuers of the trust manager, the alias of such a certificate is the one of the
--   system, else none, and the user did not add it (X509TrustManagerExtensions.isUserAddedCertificate); the system ones
--   the user disabled are listed, in both places, like on a device where they are enabled. Which certificates
--   connections trust stays, but an app which builds a trust store of its own from these lists trusts a disabled one
--   too, and not one of the user.
--   shortcut: the hidden UserCertificateSource, which apps reach only by reflection, still finds the ones of the user,
--   since the trust of connections asks it too; hook it with a check of the caller when an app is seen using it;
-- - the details of the vibrator of Android 12 and later (Vibrator.getInfo), which the methods above use: those of a
--   vibrator without capabilities;
-- - the screen lock: none, like on a device without one, where Android answers so in each place: not secure
--   (KeyguardManager.isDeviceSecure, also of a user, and isKeyguardSecure), never locked (isDeviceLocked, which
--   Android answers only with a secure lock), no intent to confirm it, no way to authenticate by it, no complexity
--   (DevicePolicyManager.getPasswordComplexity) and no secure user id (GateKeeper.getSecureUserId); since biometrics
--   need a lock, none are enrolled either (BiometricManager.canAuthenticate of any authenticators,
--   FingerprintManager.hasEnrolledFingerprints and the hidden getEnrolledFingerprints, the ids keystore asks) and
--   there was no authentication (getLastAuthenticationTime); the texts of a request which allows the lock and
--   biometrics (BiometricManager.getStrings) name the sensor, like a device without a lock, and are none, with no
--   hardware for it, when all sensors are too weak for the request (see sensorname). So keys which need the user to
--   authenticate cannot be made, and an app may show content over a locked screen it would hide behind a lock, like on
--   a device without one.
--   shortcut: the checks of keystore for the use of a key keep the real ids (see KEYUSE), since there a lock which is
--   not there invalidates a key made before for good, which apps answer by deleting it and the data it protects, so
--   such a key still works and asks to authenticate (one of a fingerprint or face not when Read.Biometrics hides the
--   ids too, see biometrics), and a key which needs an unlocked device (setUnlockedDeviceRequired) still fails while
--   the real lock is locked, which keystore checks itself; the listener of KeyguardManager for the locked state and the
--   hidden FaceManager.hasEnrolledTemplates stay, which need permissions apps do not get; a prompt which allows the
--   screen lock (BiometricPrompt) still shows it; fake these when the user wants it or apps are seen comparing them.
local PROPERTIES = { ['android.media.property.OUTPUT_FRAMES_PER_BUFFER'] = '256' }
local BIOMETRIC_SUCCESS = 0
local BIOMETRIC_ERROR_NONE_ENROLLED = 11
local BIOMETRIC_ERROR_NO_HARDWARE = 12
local BIOMETRIC_NO_AUTHENTICATION = -1
local DEVICE_CREDENTIAL = 32768
-- The modalities whose name Android's texts of a sensor have (fingerprint_app_setting_name and so on), else biometric
local MODALITIES = { 'fingerprint', 'face' }
-- The features of the biometric sensors
local SENSORS = { 'android.hardware.fingerprint', 'android.hardware.biometrics.face', 'android.hardware.biometrics.iris' }
-- The checks of keystore, by class and method, which ask for the secure user id and the ids of the enrolled
-- biometrics when the app uses a key: of Android 8.1 to 11 and of Android 12 and later (keystore2)
local KEYUSE = {
    ['android.security.KeyStore'] = { getInvalidKeyException = true, getFingerprintOnlySid = true, getFaceOnlySid = true },
    ['android.security.keystore2.KeyStoreCryptoOperationUtils'] = { canUserAuthorizationSucceed = true },
}
-- The bytes of a sample of the encodings of AudioFormat whose frames have a fixed size (PCM and IEC61937), for which
-- getMinBufferSize counts bytes; of other encodings it counts frames
local BYTES = { [2] = 2, [3] = 1, [4] = 4, [13] = 2, [21] = 3, [22] = 4 }
local STREAM_MUSIC = 3

-- The audio properties and the certificates, so the script runs for these only (see XCore.getFilter)
function filter(hook)
    local method = hook:getMethodName()
    if method == 'getProperty' then
        local keys = {}
        for key, _ in pairs(PROPERTIES) do
            keys[#keys + 1] = key
        end
        return keys
    elseif method == 'containsAlias' or method == 'getCertificate' then
        -- The system aliases for before only, of the overload with the flag (/deleted), which the others call
        if string.find(hook:getName(), '/deleted', 1, true) then
            return { 'user:*', 'system:*' }
        end
        return { 'user:*' }
    end
    return nil
end

-- The listeners of the heat: Android answers a new listener at once with the real values, so it is not added and gets
-- the values of the getters once, on the executor of the call, else on the main thread. Android throws for removing a
-- listener which was not added, which would crash the app, so removing one of these does nothing as well. One object
-- can listen to both the status and the headroom, so it is remembered for each.
function before(hook, param)
    local method = hook:getMethodName()
    -- A system certificate the user disabled is there, like on a device where it is enabled: the store asks for
    -- those itself, also below containsAlias, aliases, getCreationDate and the overloads without the flag, which the
    -- trust of connections does not use. Not reported, since nearly all system certificates are enabled.
    if method == 'getCertificate' or method == 'getCertificateAlias' then
        if hook:getParameterTypes()['length'] == 2 and param:getArgument(1) == false then
            param:setArgument(1, true)
        end
        return false
    end
    local blocked = 'thermal.blocked.' .. (string.find(method, 'Headroom', 1, true) and 'headroom' or 'status')
    if method == 'removeThermalStatusListener' or method == 'removeThermalHeadroomListener' then
        local listener = param:getArgument(0)
        if listener == nil or blockedof(param, blocked):remove(listener) == nil then
            return false
        end
        param:setResult(nil)
        return false
    elseif method ~= 'addThermalStatusListener' and method ~= 'addThermalHeadroomListener' then
        return false
    end

    -- The listener is the last argument of both variants; Android throws for a null one or a null executor
    local count = hook:getParameterTypes()['length']
    local listener = param:getArgument(count - 1)
    local executor = (count == 2 and param:getArgument(0) or nil)
    if listener == nil or (count == 2 and executor == nil) then
        return false
    end
    -- A listener added twice, which Android refuses with the message of its version
    local listeners = blockedof(param, blocked)
    if listeners:containsKey(listener) then
        local message = 'Listener already registered: '
        if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 36 then
            message = (method == 'addThermalStatusListener' and 'Thermal status' or 'Thermal headroom') ..
                    ' listener already registered: '
        end
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', message .. listener:toString()))
        return true
    end
    param:setResult(nil)
    listeners:put(listener, true)
    -- Headroom like a device whose sensors have no samples yet, which Android does not call back for (NaN)
    if method == 'addThermalHeadroomListener' then
        return true
    end

    local runnable = luajava.createProxy('java.lang.Runnable', { run = function()
        listener:onThermalStatusChanged(0) -- THERMAL_STATUS_NONE
    end })
    if executor ~= nil then
        executor:execute(runnable)
    else
        local looper = luajava.bindClass('android.os.Looper'):getMainLooper()
        luajava.newInstance('android.os.Handler', looper):post(runnable)
    end
    return true
end

-- The blocked listeners of the status or the headroom of the PowerManager of the call, which each context has of its
-- own (a cached service), by hashCode and equals like the ArrayMap of each PowerManager, so another object equal to one
-- is the same listener, unlike for putValue, which knows the PowerManager by identity.
-- shortcut: hashCode and equals of the app run below frames of LuaJ and Doppel, where Android runs them below
-- PowerManager, upgrade when apps look at the stack in them.
function blockedof(param, blocked)
    local manager = param:getThis()
    local listeners = param:getValue(blocked, manager)
    if listeners == nil then
        listeners = luajava.newInstance('java.util.concurrent.ConcurrentHashMap')
        param:putValue(blocked, listeners, manager)
    end
    return listeners
end

-- Whether an array of arguments is empty, like areAllEffectsSupported() without effects
function none(array)
    return array == nil or array['length'] == 0
end

-- A Java array of the length of another one, of a type, with one value; nil when the other one has only that value,
-- like the array the hooked getInfo made the method of Vibrator return
function filled(type, other, value)
    local length = other['length']
    local same = true
    for index = 1, length do
        same = same and other[index] == value
    end
    if same then
        return nil
    end
    local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(type, length)
    for index = 1, length do
        array[index] = value
    end
    return array
end

-- Whether a system alias leaves the folder of the system certificates for more than its parent, like
-- system:../../../data/misc/user/0/...: the folders next to it are read-only, but the user ones are not
function outside(alias)
    if string.sub(alias, 1, 7) ~= 'system:' then
        return false
    end
    local depth = 0
    for part in string.gmatch(string.sub(alias, 8), '[^/]+') do
        if part == '..' then
            depth = depth - 1
        elseif part ~= '.' then
            depth = depth + 1
        end
        if depth < -1 then
            return true
        end
    end
    return false
end

-- The alias a store without the certificates of the user gives for one of them (TrustedCertificateStore
-- .getCertificateAlias): the system one of the same certificate, which has the hash of the user alias in its name with
-- an index of its own, also when the user disabled it (see before); else none. The search ends at the first index
-- without a certificate, where Android goes on after empty or broken files, which the read-only folder of the system
-- does not have
function systemalias(store, certificate, alias)
    local hash = string.match(alias, '^user:(.+)%.%d+$')
    if hash == nil then
        return nil
    end
    local index = 0
    while true do
        local system = 'system:' .. hash .. '.' .. index
        local found = store:getCertificate(system, true)
        if found == nil then
            return nil
        elseif found:equals(certificate) then
            return system
        end
        index = index + 1
    end
end

-- A certificate of a file, else nil, also when the file cannot be opened, so one file does not end the whole read
function certificateof(factory, file)
    local stream = nil
    local ok, certificate = pcall(function()
        stream = luajava.newInstance('java.io.FileInputStream', file)
        return factory:generateCertificate(stream)
    end)
    if stream ~= nil then
        pcall(function()
            stream:close()
        end)
    end
    return ok and certificate or nil
end

-- The certificates of the system (a set) and those of them the user disabled (a list), read by public APIs, since the
-- rules of hidden APIs hide the methods of TrustedCertificateStore from apps, and without hooks, which hide the folder
-- of the disabled ones; nil when that folder, of the user of the app, is empty or not there. Kept for the process
-- until that folder changes, since reading all system ones takes long and would tell the hook by its time.
function disabledcertificates(param)
    local folder = luajava.newInstance('java.io.File', userfolder('cacerts-removed'))
    local modified = folder:lastModified()
    local kept = param:getValue('certificates.disabled', nil)
    if kept == nil or kept.modified ~= modified then
        kept = { modified = modified }
        kept.system, kept.disabled = readcertificates(folder:listFiles())
        param:putValue('certificates.disabled', kept, nil)
    end
    return kept.system, kept.disabled
end

-- A folder of the certificates of the user of the app
function userfolder(name)
    local user = math.floor(luajava.bindClass('android.os.Process'):myUid() / 100000) -- UserHandle.PER_USER_RANGE
    return '/data/misc/user/' .. user .. '/' .. name
end

-- The folder of the system certificates conscrypt takes (TrustedCertificateStore.PreloadHolder) and its files: of its
-- module from Android 14, when it has them and the property system.certs.enabled is not true, else of the system
function systemfolder()
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 34 and
            luajava.bindClass('java.lang.System'):getProperty('system.certs.enabled') ~= 'true' then
        local apex = '/apex/com.android.conscrypt/cacerts'
        local files = luajava.newInstance('java.io.File', apex):listFiles()
        if files ~= nil and files['length'] > 0 then
            return apex, files
        end
    end
    local root = (luajava.bindClass('java.lang.System'):getenv('ANDROID_ROOT') or '/system') .. '/etc/security/cacerts'
    return root, luajava.newInstance('java.io.File', root):listFiles()
end

-- The certificates of disabledcertificates, of the files of the folder of the disabled ones and of the system
function readcertificates(removed)
    if removed == nil or removed['length'] == 0 then
        return nil
    end
    local _, files = systemfolder()
    local factory = luajava.bindClass('java.security.cert.CertificateFactory'):getInstance('X.509')
    local system = luajava.newInstance('java.util.HashSet')
    for index = 1, (files ~= nil and files['length'] or 0) do
        local certificate = certificateof(factory, files[index])
        if certificate ~= nil then
            system:add(certificate)
        end
    end
    local disabled = luajava.newInstance('java.util.ArrayList')
    for index = 1, removed['length'] do
        local certificate = certificateof(factory, removed[index])
        if certificate ~= nil and system:contains(certificate) then
            disabled:add(certificate)
        end
    end
    return system, disabled
end

-- The certificates the user installed (a set), of the folder TrustedCertificateStore takes them from, without those
-- the system has too, whose system alias stays (see systemalias); nil when there are none. Read without hooks, which
-- hide the folder, and kept until it changes, like disabledcertificates.
function addedcertificates(param)
    local folder = luajava.newInstance('java.io.File', userfolder('cacerts-added'))
    local modified = folder:lastModified()
    local kept = param:getValue('certificates.added', nil)
    if kept == nil or kept.modified ~= modified then
        kept = { modified = modified, added = readadded(folder:listFiles()) }
        param:putValue('certificates.added', kept, nil)
    end
    return kept.added
end

-- The certificates of addedcertificates, of the files of its folder; a system one has a file of the same hash in the
-- folder of the system, with an index of its own
function readadded(files)
    if files == nil or files['length'] == 0 then
        return nil
    end
    local factory = luajava.bindClass('java.security.cert.CertificateFactory'):getInstance('X.509')
    local folder = systemfolder()
    local added = luajava.newInstance('java.util.HashSet')
    for index = 1, files['length'] do
        local certificate = certificateof(factory, files[index])
        local hash = string.match(files[index]:getName(), '^(.+)%.%d+$')
        local system = false
        local number = 0
        while certificate ~= nil and hash ~= nil and not system do
            local file = luajava.newInstance('java.io.File', folder .. '/' .. hash .. '.' .. number)
            if not file:exists() then
                break
            end
            local other = certificateof(factory, file)
            system = other ~= nil and other:equals(certificate)
            number = number + 1
        end
        if certificate ~= nil and not system then
            added:add(certificate)
        end
    end
    if added:isEmpty() then
        return nil
    end
    return added
end

-- The accepted issuers of a trust manager without the certificates the user installed and with the system ones the
-- user disabled, when the configuration of the app trusts system certificates, which its issuers then have; nil when
-- that changes nothing
function withdisabled(param, issuers)
    local added = addedcertificates(param)
    local system, disabled = disabledcertificates(param)
    local list = luajava.newInstance('java.util.ArrayList')
    local trusted, changed = false, false
    for index = 1, issuers['length'] do
        if added ~= nil and added:contains(issuers[index]) then
            changed = true
        else
            list:add(issuers[index])
        end
        trusted = trusted or (system ~= nil and system:contains(issuers[index]))
    end
    if trusted then
        local iterator = disabled:iterator()
        while iterator:hasNext() do
            local certificate = iterator:next()
            if not list:contains(certificate) then
                list:add(certificate)
                changed = true
            end
        end
    end
    if not changed then
        return nil
    end
    local array = luajava.bindClass('java.lang.reflect.Array')
    return list:toArray(array:newInstance(luajava.bindClass('java.security.cert.X509Certificate'), 0))
end

-- The frames the mixer of an output of 256 frames per buffer (see PROPERTIES) mixes at once at a sample rate:
-- 20 to 24 ms in whole buffers of the output, rounded to 16 frames (PlaybackThread::readOutputParameters_l)
function mixerframes(rate)
    local least = bit32.band(math.floor(20 * rate / 1000) + 15, bit32.bnot(15))
    local most = math.max(bit32.band(math.floor(24 * rate / 1000), bit32.bnot(15)), least)
    local multiplier = least / 256
    if multiplier <= 1 then
        multiplier = 1
    elseif multiplier <= 2 then
        multiplier = (2 * 256 <= most) and 2 or most / 256
    else
        multiplier = math.floor(multiplier)
    end
    return bit32.band(math.floor(multiplier * 256) + 15, bit32.bnot(15))
end

-- The frames on the stack (see keyuse)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Whether keystore asks for an id while the app uses a key (see KEYUSE): the caller is the frame below the deepest one
-- with the name of the hooked method, since the methods without a user call the ones of a user and the hook engine
-- can put a frame of a class of its own in place of the hooked one (see features.lua)
function keyuse(method)
    local frames = callers()
    local caller = nil
    for index = 1, frames['length'] - 1 do
        if frames[index]:getMethodName() == method then
            caller = frames[index + 1]
        end
    end
    local methods = (caller ~= nil and KEYUSE[caller:getClassName()] or nil)
    return methods ~= nil and methods[caller:getMethodName()] == true
end

-- The name of the sensor which a device without a lock names for the biometrics of a request which allows the screen
-- lock and biometrics, of Android 12 and later: the first sensor not enrolled, else the first one
-- (PreAuthInfo.calculateErrorByPriority), which is also the one it names for the biometrics alone when none is usable;
-- else the one strong enough for the request, which Android checks before it checks enrollment, so a sensor the user
-- turned off for apps or a policy disabled is named too (Strings.getSettingName checks the strength alone); false when
-- the device has sensors but none strong enough, where Android has no texts and no hardware for the request
-- (BIOMETRIC_INSUFFICIENT_STRENGTH); nil without a sensor, where the screen lock stays like on a device without one.
-- It asks through the hooks, like the app, since Read.Biometrics hides the sensors there, also when it made the
-- request of the app one of the lock alone, which a hook outside it does not see in its own copy of the arguments.
-- shortcut: a sensor whose feature the device declares but whose HAL did not start counts as there, where Android
-- counts none; take the sensors of the service when a device is seen with one.
function sensorname(manager, authenticators, context)
    local biometric = manager:getStrings(bit32.band(authenticators, bit32.bnot(DEVICE_CREDENTIAL)))
    local label = biometric:getButtonLabel() or biometric:getSettingName()
    if label ~= nil then
        return label
    end
    local packages = context:getPackageManager()
    for _, feature in ipairs(SENSORS) do
        if packages:hasSystemFeature(feature) then
            return false
        end
    end
    return nil
end

-- The text of a getter of BiometricManager.Strings (getButtonLabel, getPromptMessage) of the sensor which a device
-- without a lock names (see sensorname), else nil, also for both getters when the name of that sensor is none of
-- Android's texts, so the label and the message never disagree. Of a lock with usable biometrics the texts already
-- name them.
-- shortcut: of several sensors of which some are enrolled, the enrolled ones are named, where a device without a lock
-- names one; and the texts stay those of the lock when the framework texts of the app differ from those of the service,
-- for a language of the app (Android 13 and later) or overlaid texts; take the order of the sensors and the texts of
-- the service when apps are seen comparing them.
function withoutlock(method, label)
    if label == nil then
        return nil
    end
    -- The texts of the framework, which the service takes in the language of the system
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    local function text(name)
        local id = resources:getIdentifier(name, 'string', 'android')
        return (id ~= 0) and resources:getString(id) or nil
    end
    local modality = (label == text('biometric_app_setting_name')) and 'biometric' or nil
    for _, name in ipairs(MODALITIES) do
        if label == text(name .. '_app_setting_name') then
            modality = name
        end
    end
    local message = modality and text(modality .. '_or_screen_lock_dialog_default_subtitle')
    if not message then
        return nil
    end
    return (method == 'getButtonLabel') and label or message
end

-- The number of channels of a channel configuration of AudioFormat, like AudioFormat.channelCountFromOutChannelMask,
-- which also counts the two old ones of mono and stereo right
function channels(mask)
    local count = 0
    while mask ~= 0 do
        count = count + bit32.band(mask, 1)
        mask = bit32.rshift(mask, 1)
    end
    return count
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local result = param:getResult()
    local fake
    if method == 'getThermalHeadroom' or method == 'getResonantFrequency' or method == 'getQFactor' then
        if result ~= result then
            return false
        end
        fake = 0 / 0
    elseif method == 'getCurrentThermalStatus' or method == 'areAllEffectsSupported' then
        -- THERMAL_STATUS_NONE, VIBRATION_EFFECT_SUPPORT_UNKNOWN; for no effects (yes) and of a vibrator which supports
        -- none (no, like a device without one) the answer of Android stays
        if method == 'areAllEffectsSupported' and (none(param:getArgument(0)) or result == 2) then
            return false
        end
        fake = 0
    elseif method == 'getThermalHeadroomThresholds' then
        if result == nil or result:isEmpty() then
            return false
        end
        fake = luajava.newInstance('java.util.HashMap')
        param:setResult(fake)
        return true, tostring(result:size()), '0'
    elseif method == 'getProperty' then
        fake = PROPERTIES[param:getArgument(0)]
        if fake == nil or result == nil then
            return false
        end
    elseif method == 'getMinBufferSize' then
        -- Two buffers of the mixer, the least Android gives (calculateMinFrameCount), of source frames for the rate
        -- of the output (sourceFramesNeededWithTimestretch), in bytes of the format; its errors stay
        if result <= 0 then
            return false
        end
        local output = luajava.bindClass('android.media.AudioTrack'):getNativeOutputSampleRate(STREAM_MUSIC)
        if output <= 0 then
            return false
        end
        local rate, mixer = param:getArgument(0), mixerframes(output)
        local needed = (rate == output) and mixer or (math.floor(mixer * rate / output) + 2)
        local frames = 2 * (needed + 2)
        local bytes = BYTES[param:getArgument(2)]
        fake = bytes and frames * channels(param:getArgument(1)) * bytes or frames
    elseif method == 'hasAmplitudeControl' or method == 'areAllPrimitivesSupported' then
        -- Of no primitives Android answers true
        if method == 'areAllPrimitivesSupported' and none(param:getArgument(0)) then
            return false
        end
        fake = false
    elseif method == 'areEffectsSupported' or method == 'getPrimitiveDurations' then
        -- Of the effects asked for: unknown (0), of primitives: no duration (0)
        fake = (result ~= nil and filled(luajava.bindClass('java.lang.Integer').TYPE, result, 0) or nil)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true
    elseif method == 'arePrimitivesSupported' then
        fake = (result ~= nil and filled(luajava.bindClass('java.lang.Boolean').TYPE, result, false) or nil)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true
    elseif method == 'aliases' then
        -- The aliases of the certificates, without those of the user; the system ones the user disabled are there (see
        -- before)
        if result == nil then
            return false
        end
        local kept = luajava.newInstance('java.util.LinkedHashSet')
        local iterator = result:iterator()
        while iterator:hasNext() do
            local alias = iterator:next()
            if string.sub(alias, 1, 5) ~= 'user:' then
                kept:add(alias)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    elseif method == 'userAliases' then
        if result == nil or result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.LinkedHashSet'))
        return true, tostring(result:size()), '0'
    elseif method == 'getInfo' then
        -- An empty VibratorInfo (hidden): of Android 13 and later its constant, else built; the one of a device
        -- without a vibrator (NoVibratorInfo) stays, which answers no for all effects
        if result == nil then
            return false
        end
        local known, novibrator = pcall(function()
            return param:isInstance(result, 'android.os.SystemVibrator$NoVibratorInfo')
        end)
        if known and novibrator then
            return false
        end
        -- With the id of the vibrator: EMPTY_VIBRATOR_INFO has id -1, which getVibrator does not give
        local ok, empty = pcall(function()
            return luajava.newInstance('android.os.VibratorInfo$Builder', result:getId()):build()
        end)
        if not ok or empty == nil then
            ok, empty = pcall(function()
                return luajava.bindClass('android.os.VibratorInfo').EMPTY_VIBRATOR_INFO
            end)
        end
        if not ok or empty == nil then
            return false
        end
        param:setResult(empty)
        return true
    elseif method == 'isDeviceSecure' or method == 'isKeyguardSecure' or method == 'isDeviceLocked' or
            method == 'hasEnrolledFingerprints' then
        fake = false
    elseif method == 'getEnrolledFingerprints' then
        -- The list of a device with none enrolled, which comes over binder as an ArrayList; null of no service stays
        if result == nil or result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.ArrayList'))
        return true, tostring(result:size()), '0'
    elseif method == 'getStrings' then
        -- Kept for the texts of a request of the screen lock and biometrics; one which Read.Biometrics made a request of
        -- the lock alone (biometrics_credential) answers like a device without either, also where this hook sees the
        -- argument of the app (see sensorname)
        local authenticators = param:getArgument(0)
        if result ~= nil and bit32.band(authenticators, DEVICE_CREDENTIAL) ~= 0 and authenticators ~= DEVICE_CREDENTIAL then
            param:putValue('lock.strings', { manager = param:getThis(), authenticators = authenticators }, result)
        end
        return false
    elseif method == 'getButtonLabel' or method == 'getPromptMessage' then
        local strings = param:getValue('lock.strings', param:getThis())
        if strings == nil or result == nil then
            return false
        end
        -- The sensor through the hooks, its texts without them
        local ok, text = pcall(function()
            local label = sensorname(strings.manager, strings.authenticators, param:getApplicationContext())
            if label == false then
                return false
            end
            return param:unhooked(function()
                return withoutlock(method, label)
            end)
        end)
        if not ok or text == nil then
            return false
        end
        if text == false then
            param:setResult(nil)
            return true, tostring(result), nil
        end
        fake = tostring(text)
        result = tostring(result)
    elseif method == 'createConfirmDeviceCredentialIntent' then
        -- Of a device without a lock none, also where the framework calls isDeviceSecure inlined, past its hook
        if result == nil then
            return false
        end
        param:setResult(nil)
        return true
    elseif method == 'canAuthenticate' then
        -- Without a lock no biometric can be enrolled either: of any authenticators NONE_ENROLLED, which Android
        -- answers for no lock (BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) and for sensors with none enrolled; no sensor
        -- (Read.Biometrics) and the other errors stay. Of a request of the lock and biometrics whose sensors are all too
        -- weak, Android 12 and later answer no hardware, like the texts (see sensorname); Android 11, which has no
        -- texts, answers none enrolled there (BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL)
        if result ~= BIOMETRIC_SUCCESS then
            return false
        end
        fake = BIOMETRIC_ERROR_NONE_ENROLLED
        -- The one without arguments asks for BIOMETRIC_WEAK alone
        local _, authenticators = pcall(function()
            return param:getArgument(0)
        end)
        if type(authenticators) == 'number' and bit32.band(authenticators, DEVICE_CREDENTIAL) ~= 0 and
                authenticators ~= DEVICE_CREDENTIAL then
            local manager = param:getThis()
            local ok, label = pcall(function()
                return sensorname(manager, authenticators, param:getApplicationContext())
            end)
            if ok and label == false then
                fake = BIOMETRIC_ERROR_NO_HARDWARE
            end
        end
    elseif method == 'getLastAuthenticationTime' then
        -- Android answers so without a secure user id, for any authenticators
        fake = BIOMETRIC_NO_AUTHENTICATION
    elseif method == 'getPasswordComplexity' then
        -- PASSWORD_COMPLEXITY_NONE; without the permission the exception stays
        fake = 0
    elseif method == 'getSecureUserId' or method == 'getAuthenticatorId' then
        -- No secure user id (GateKeeper.INVALID_SECURE_USER_ID) and no enrolled fingerprint or face of Android 8.1 to
        -- 10, for which keystore throws its own exception for keys that need them, unless a key is used (see KEYUSE)
        if result == 0 or keyuse(method) then
            return false
        end
        fake = 0
    elseif method == 'getAuthenticatorIds' then
        -- BiometricManager, hidden: no enrolled biometrics, which keystore of Android 11 and later asks, see above
        if result == nil or result['length'] == 0 or keyuse(method) then
            return false
        end
        param:setResult(luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.Long').TYPE, 0))
        return true, tostring(result['length']), '0'
    elseif method == 'containsAlias' or method == 'getCertificate' then
        -- The filter also passes all aliases with /../, which can lead to the user ones
        local alias = param:getArgument(0)
        if string.sub(alias, 1, 5) ~= 'user:' and not outside(alias) then
            return false
        end
        if method == 'containsAlias' then
            fake = false
        end
    elseif method == 'getCertificateAlias' then
        -- An alias of the user, which the other methods do not have; the system ones are those of the store, also of a
        -- certificate the user disabled (see before)
        if result == nil or string.sub(result, 1, 5) ~= 'user:' then
            return false
        end
        local store = param:getThis()
        local ok, alias = pcall(function()
            return param:unhooked(function()
                return systemalias(store, param:getArgument(0), result)
            end)
        end)
        -- The hidden API rules may hide the methods of the store from apps, then none, like for nearly all of them
        fake = (ok and alias or nil)
    elseif method == 'getAcceptedIssuers' then
        -- Without the certificates of the user and with the system ones the user disabled, like the store (see aliases)
        if result == nil or result['length'] == 0 then
            return false
        end
        local ok, issuers = pcall(function()
            return param:unhooked(function()
                return withdisabled(param, result)
            end)
        end)
        if not ok or issuers == nil then
            return false
        end
        param:setResult(issuers)
        return true, tostring(result['length']), tostring(issuers['length'])
    elseif method == 'isUserAddedCertificate' then
        fake = false
    else
        return false
    end
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
