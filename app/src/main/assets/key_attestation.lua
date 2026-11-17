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

-- Key attestation is refused like by a keystore without attestation keys. Any app can ask for it without a permission
-- (KeyGenParameterSpec.setAttestationChallenge), and the certificate it gets is signed by the device with the real
-- security patch of the system and the vendor, which Use.Tracking gives as not set (see systemproperties_get), and
-- the state of the boot. The hooks are below the framework, so Android itself makes what the app gets of the error and
-- removes the key again: on Android 8.1 to 11 keystore gives the code of the keymaster for KeyStore.attestKey, and
-- AndroidKeyStoreKeyPairGeneratorSpi throws ProviderException "Failed to generate attestation certificate chain" with
-- a KeyStoreException of the code; from Android 12 keystore2 answers the call of generateKey which has the challenge
-- with the error, of which KeyStoreSecurityLevel makes a KeyStoreException and the generator a ProviderException
-- "Failed to generate key pair.". The code is the one of a keymaster without attestation keys from the factory:
-- ATTESTATION_KEYS_NOT_PROVISIONED of Keymaster 4.1 (Android 11) and KeyMint (generateKey of IKeyMintDevice.aidl).
-- Keys without a challenge are made.
-- shortcut: an attestation by a key of the app (setAttestKeyAlias, Android 12 and later), which has the challenge too,
-- is refused as well, which a keystore without attestation keys of its own still makes, but with the real patch
-- level; let it through if apps tell such keystores apart by it.
-- shortcut: Keymaster 3 and 4.0 have no code for missing attestation keys, so before Android 11 it is UNIMPLEMENTED,
-- which they give for what they do not do; makers answer as they like, like INVALID_ARGUMENT of Trusty for an empty
-- key slot; ATTESTATION_KEYS_NOT_PROVISIONED of Android 11 and later is a convention too on every device whose
-- keymaster is 4.0 or older, also behind keystore2 from Android 12 (km_compat), which cannot give it; take the code of
-- a device without keys if apps compare it.
-- shortcut: from Android 13 the message of keystore2, the chain of its errors (anyhow) with the files and lines of
-- its source, which differ per build, is in the one of the KeyStoreException; this is the one of android-14.0.0_r1
-- for a device without remotely provisioned keys, take the ones of the other versions from devices if apps compare.
-- shortcut: a key the app made with attestation before it was restricted keeps its certificates, which
-- KeyStore.getCertificateChain gives, leave them out too if apps keep such keys.
-- The characteristics of every key, which an app reads by the hidden KeyStore.getKeyCharacteristics on Android 8.1 to
-- 11 (from Android 9 only where the rules on hidden APIs let it), have the patch levels of the system, the vendor and
-- the boot too, which the keymaster adds to every key: they are 0, the patch level of the system the keymaster
-- configuration of Android gives for a security patch not set (GetOsPatchlevel of system/keymaster), and the same for
-- the vendor and the boot, so they agree with Use.Tracking. Keystore gives KeyCharacteristics through its parcel
-- (readFromParcel) for each call which has them, as an out parameter of IKeystoreService on Android 8.1 and 9 and by
-- the callback of KeyCharacteristics from Android 10; the hook rewrites them through a parcel too, which Parcel
-- writes itself, since their fields and KeymasterArguments are hidden, of which the rules on hidden APIs give apps
-- readFromParcel (light greylist of Android 9, @UnsupportedAppUsage of Android 10 and 11).
-- shortcut: from Android 12 the authorizations of KeyMetadata of keystore2 keep the real patch levels, which only an
-- app that gets around the rules on hidden APIs reads; zero them in IKeystoreSecurityLevel.generateKey and
-- IKeystoreService.getKeyEntry if apps do.

local UNIMPLEMENTED = -100
local NOT_PROVISIONED = -74

-- KM_TAG_ATTESTATION_CHALLENGE, KM_BYTES | 708 (KeymasterDefs)
local CHALLENGE = -1879047484

local MESSAGE = 'system/security/keystore2/src/security_level.rs:622\n\nCaused by:\n' ..
        '    0: system/security/keystore2/src/security_level.rs:620: While generating Key without explicit ' ..
        'attestation key.\n    1: Error::Km(r#ATTESTATION_KEYS_NOT_PROVISIONED)'

-- KM_TAG_OS_PATCHLEVEL, KM_TAG_VENDOR_PATCHLEVEL and KM_TAG_BOOT_PATCHLEVEL, KM_UINT | 706, 718 and 719
local PATCHLEVELS = { [805307074] = true, [805307086] = true, [805307087] = true }

-- How each type of tag (KeymasterDefs.getTagType) writes its value after the tag (the subclasses of KeymasterArgument):
-- enums and numbers of 32 bits as an int, of 64 bits and dates as a long, flags as nothing and bignums and bytes as an
-- array of bytes
local VALUES = { 'readInt', 'readInt', 'readInt', 'readInt', 'readLong', 'readLong', false, 'createByteArray',
    'createByteArray', 'readLong' }

-- Sets the patch levels in a list of KeymasterArguments in a parcel, at its position, to 0 and returns whether one
-- was not 0 then; the list is written by writeTypedList: the count, then 1 and the item for each item which is not
-- null, else 0, and each item is its tag and the value of its type
function unpatched(parcel)
    local found = false
    for _ = 1, parcel:readInt() do
        if parcel:readInt() ~= 0 then
            local tag = parcel:readInt()
            local read = VALUES[bit32.rshift(tag, 28)]
            if read == nil then
                error('Bad tag: ' .. tag)
            end
            if PATCHLEVELS[tag] then
                local at = parcel:dataPosition()
                if parcel:readInt() ~= 0 then
                    parcel:setDataPosition(at)
                    parcel:writeInt(0)
                    found = true
                end
            elseif read then
                parcel[read](parcel)
            end
        end
    end
    return found
end

function after(hook, param)
    if hook:getMethodName() ~= 'readFromParcel' then
        return false
    end
    local characteristics = param:getThis()
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, done = pcall(function()
        -- Parcel writes them itself, after a 1 for not null: the ones of software, then those of the hardware; a
        -- keymaster of software puts them in the first
        parcel:writeTypedObject(characteristics, 0)
        parcel:setDataPosition(0)
        parcel:readInt()
        local start = parcel:dataPosition()
        local software = unpatched(parcel)
        local hardware = unpatched(parcel)
        if not software and not hardware then
            return false
        end
        parcel:setDataPosition(start)
        param:unhooked(function()
            characteristics:readFromParcel(parcel)
        end)
        return true
    end)
    parcel:recycle()
    return ok and done
end

function before(hook, param)
    local method = hook:getMethodName()
    if method == 'attestKey' then
        local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
        param:setResult(sdk >= 30 and NOT_PROVISIONED or UNIMPLEMENTED)
        return true
    elseif method ~= 'generateKey' then
        return false
    end

    local params = param:getArgument(2)
    if params == nil then
        return false
    end
    for index = 1, params['length'] do
        local item = params[index]
        if item ~= nil and item.tag == CHALLENGE then
            param:setResult(luajava.newInstance('android.os.ServiceSpecificException', NOT_PROVISIONED, MESSAGE))
            return true
        end
    end
    return false
end
