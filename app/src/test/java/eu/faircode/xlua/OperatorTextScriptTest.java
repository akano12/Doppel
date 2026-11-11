/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The operator in the text of the service state, network registrations and subscriptions
// (assets/operator_tostring.lua), with the formats of Android 16 and of older versions, from their sources
public class OperatorTextScriptTest {
    private static final String SERVICE_STATE = "android.telephony.ServiceState";
    private static final String REGISTRATION = "android.telephony.NetworkRegistrationInfo";
    private static final String SUBSCRIPTION = "android.telephony.SubscriptionInfo";
    // The names, the carrier and the country, in Use.Tracking
    private static final String OPERATOR = "SubscriptionInfo.toString/operator";
    // The network codes, in Use.Tracking
    private static final String PLMN = "SubscriptionInfo.toString/plmn";
    // The ICCID, the card string, the number and the group, in Read.Telephony
    private static final String TELEPHONY = "SubscriptionInfo.toString/telephony";
    private static final String ICCID = "89014103211118510720";
    private static final String GROUP = "6a2c5d1e-0c4b-4b8e-9f1a-2b3c4d5e6f70";

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("operator_tostring", null);
    }

    // The phone number set is the setting of the param, null for none
    private Varargs after(String className, String name, String text, String number) {
        LuaValue hook = globals.load("local className, name = ...\n" +
                "return { getClassName = function(self) return className end,\n" +
                "    getName = function(self) return name end }").invoke(
                LuaValue.valueOf(className), LuaValue.valueOf(name)).arg1();
        LuaValue param = globals.load("local text, number = ...\n" +
                "local param = { result = text }\n" +
                "function param.getResult(self) return self.result end\n" +
                "function param.setResult(self, value) self.result = value end\n" +
                "function param.getSetting(self, name) if name == 'value.phone_number' then return number end end\n" +
                "return param").call(LuaValue.valueOf(text), number == null ? LuaValue.NIL : LuaValue.valueOf(number));
        Varargs result = globals.get("after").invoke(hook, param);
        return LuaValue.varargsOf(result.arg1(), param.get("result"));
    }

    private Varargs after(String className, String name, String text) {
        return after(className, name, text, null);
    }

    private String telephony(String text, String number) {
        return after(SUBSCRIPTION, TELEPHONY, text, number).arg(2).tojstring();
    }

    private Varargs after(String className, String text) {
        String name = className.substring(className.lastIndexOf('.') + 1) + ".toString/operator";
        return after(className, name, text);
    }

    // Both definitions of the subscription, like when both groups are restricted
    private String both(String text) {
        return after(SUBSCRIPTION, PLMN, after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring()).arg(2).tojstring();
    }

    // The rules of the certificates of the carrier and the app owning the group name the carrier: none, like a SIM of
    // the test network, in the forms of Android 14, 11 to 13 and 8.1 to 10
    @Test
    public void subscriptionAccessRules() {
        String rules = "[cert: 7A2B pkg: com.carrier.app access: 0]";
        assertEquals(" groupOwner= isGroupDisabled=false cardId=0 nativeAccessRules=null carrierConfigAccessRules=null countryIso=",
                after(SUBSCRIPTION, OPERATOR, " groupOwner=com.carrier.app isGroupDisabled=false cardId=0 nativeAccessRules=" +
                        rules + " carrierConfigAccessRules=" + rules + " countryIso=").arg(2).tojstring());
        assertEquals(" nativeAccessRules=null cardString=[****] groupOwner= carrierConfigAccessRules=null areUiccApplicationsEnabled=true",
                after(SUBSCRIPTION, OPERATOR, " nativeAccessRules=" + rules + " cardString=[****] groupOwner=com.carrier.app" +
                        " carrierConfigAccessRules=" + rules + " areUiccApplicationsEnabled=true").arg(2).tojstring());
        assertEquals(" accessRules null cardString=[****] cardId=0 mGroupOwner=}",
                after(SUBSCRIPTION, OPERATOR, " accessRules " + rules + " cardString=[****] cardId=0 mGroupOwner=com.carrier.app}")
                        .arg(2).tojstring());
    }

    @Test
    public void serviceState() {
        Varargs result = after(SERVICE_STATE,
                "{mVoiceRegState=0(IN_SERVICE), mOperatorAlphaLong=Carrier, Inc., mOperatorAlphaShort=CAR, " +
                        "isManualNetworkSelection=false(automatic), mOperatorAlphaLongRaw=Carrier, Inc., " +
                        "mOperatorAlphaShortRaw=CAR, mIsDataRoamingFromRegistration=false}");
        assertTrue(result.arg1().toboolean());
        assertEquals("{mVoiceRegState=0(IN_SERVICE), mOperatorAlphaLong=unknown, mOperatorAlphaShort=unknown, " +
                "isManualNetworkSelection=false(automatic), mOperatorAlphaLongRaw=unknown, " +
                "mOperatorAlphaShortRaw=unknown, mIsDataRoamingFromRegistration=false}", result.arg(2).tojstring());
    }

    // Android 10 names the voice and the data network, Android 8.1 and 9 too, without the raw names
    @Test
    public void serviceStateAndroid10() {
        Varargs result = after(SERVICE_STATE,
                "{mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=-1, duplexMode()=0, " +
                        "mCellBandwidths=[], mVoiceOperatorAlphaLong=Carrier, Inc., mVoiceOperatorAlphaShort=CAR, " +
                        "mDataOperatorAlphaLong=Carrier, Inc., mDataOperatorAlphaShort=CAR, " +
                        "isManualNetworkSelection=false(automatic), getRilVoiceRadioTechnology=14(LTE), " +
                        "mNetworkRegistrationInfos=[], mNrFrequencyRange=0, mOperatorAlphaLongRaw=Carrier, Inc., " +
                        "mOperatorAlphaShortRaw=CAR, mIsIwlanPreferred=false}");
        assertTrue(result.arg1().toboolean());
        assertEquals("{mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=-1, duplexMode()=0, " +
                "mCellBandwidths=[], mVoiceOperatorAlphaLong=unknown, mVoiceOperatorAlphaShort=unknown, " +
                "mDataOperatorAlphaLong=unknown, mDataOperatorAlphaShort=unknown, " +
                "isManualNetworkSelection=false(automatic), getRilVoiceRadioTechnology=14(LTE), " +
                "mNetworkRegistrationInfos=[], mNrFrequencyRange=0, mOperatorAlphaLongRaw=unknown, " +
                "mOperatorAlphaShortRaw=unknown, mIsIwlanPreferred=false}", result.arg(2).tojstring());
    }

    // Without service the names are empty, a fake value would tell they are faked
    @Test
    public void emptyValuesStay() {
        String text = "{mVoiceRegState=1(OUT_OF_SERVICE), mOperatorAlphaLong=, mOperatorAlphaShort=, " +
                "isManualNetworkSelection=false(automatic)}";
        Varargs result = after(SERVICE_STATE, text);
        assertFalse(result.arg1().toboolean());
        assertEquals(text, result.arg(2).tojstring());
    }

    @Test
    public void registration() {
        Varargs result = after(REGISTRATION,
                "NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=310260 isUsingCarrierAggregation=false}");
        assertEquals("NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=00101 isUsingCarrierAggregation=false}",
                result.arg(2).tojstring());
        // Android 11 ends with the registered network
        result = after(REGISTRATION, "NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=310260}");
        assertEquals("NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=00101}", result.arg(2).tojstring());
    }

    // Android 14 and later
    @Test
    public void subscription() {
        String text = "[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1 " +
                "displayName=My Carrier carrierName=My Carrier isOpportunistic=false number=[****] " +
                "mcc=310 mnc=260 ehplmns=[310260] hplmns=[310260] cardString=[****] countryIso=us " +
                "profileClass=-1 mType=LOCAL_SIM]";
        assertEquals("[SubscriptionInfo: id=1 iccId= simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1911 " +
                        "displayName=unknown carrierName=unknown isOpportunistic=false number=[****] " +
                        "mcc=310 mnc=260 ehplmns=[310260] hplmns=[310260] cardString=[****] countryIso= " +
                        "profileClass=-1 mType=LOCAL_SIM]",
                after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring());
        assertEquals("[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1 " +
                        "displayName=My Carrier carrierName=My Carrier isOpportunistic=false number=[****] " +
                        "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString=[****] countryIso=us " +
                        "profileClass=-1 mType=LOCAL_SIM]",
                after(SUBSCRIPTION, PLMN, text).arg(2).tojstring());
        assertEquals("[SubscriptionInfo: id=1 iccId= simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1911 " +
                "displayName=unknown carrierName=unknown isOpportunistic=false number=[****] " +
                "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString=[****] countryIso= " +
                "profileClass=-1 mType=LOCAL_SIM]", both(text));
    }

    // Android 11 to 13 print the country right after the codes and the home networks after the profile class
    @Test
    public void subscriptionAndroid13() {
        String text = "{id=1 iccId=[****] simSlotIndex=0 carrierId=1 displayName=My Carrier carrierName=My Carrier " +
                "nameSource=0 iconTint=0 number=[****] dataRoaming=0 iconBitmap=null mcc=310 mnc=260 countryIso=us " +
                "isEmbedded=false nativeAccessRules=null cardString=[****] cardId=0 portIndex=0 " +
                "isOpportunistic=false groupUUID=null isGroupDisabled=false profileClass=-1 " +
                "ehplmns=[310260] hplmns=[310260] subscriptionType=0 groupOwner=null}";
        assertEquals("{id=1 iccId= simSlotIndex=0 carrierId=1911 displayName=unknown carrierName=unknown " +
                        "nameSource=0 iconTint=0 number=[****] dataRoaming=0 iconBitmap=null mcc=310 mnc=260 " +
                        "countryIso= isEmbedded=false nativeAccessRules=null cardString=[****] cardId=0 portIndex=0 " +
                        "isOpportunistic=false groupUUID=null isGroupDisabled=false profileClass=-1 " +
                        "ehplmns=[310260] hplmns=[310260] subscriptionType=0 groupOwner=null}",
                after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring());
        assertEquals("{id=1 iccId= simSlotIndex=0 carrierId=1911 displayName=unknown carrierName=unknown " +
                "nameSource=0 iconTint=0 number=[****] dataRoaming=0 iconBitmap=null mcc=001 mnc=01 countryIso= " +
                "isEmbedded=false nativeAccessRules=null cardString=[****] cardId=0 portIndex=0 " +
                "isOpportunistic=false groupUUID=null isGroupDisabled=false profileClass=-1 " +
                "ehplmns=[] hplmns=[] subscriptionType=0 groupOwner=null}", both(text));
    }

    // Android 10 prints the codes as text without equals signs, followed by the country
    @Test
    public void subscriptionAndroid10() {
        String text = "{id=1, iccId=[****] simSlotIndex=0 carrierId=1 displayName=My Carrier carrierName=My Carrier " +
                "nameSource=0 iconTint=0 mNumber=[****] dataRoaming=0 iconBitmap=null mcc 310 mnc 260mCountryIso=us " +
                "isEmbedded false accessRules null cardString=[****] cardId=0 isOpportunistic false " +
                "mGroupUUID=null mIsGroupDisabled=false profileClass=-1 ehplmns = [310260] hplmns = [310260] " +
                "subscriptionType=0 mGroupOwner=null}";
        assertEquals("{id=1, iccId=[****] simSlotIndex=0 carrierId=1 displayName=My Carrier carrierName=My Carrier " +
                        "nameSource=0 iconTint=0 mNumber=[****] dataRoaming=0 iconBitmap=null mcc 001 mnc 01" +
                        "mCountryIso=us isEmbedded false accessRules null cardString=[****] cardId=0 " +
                        "isOpportunistic false mGroupUUID=null mIsGroupDisabled=false profileClass=-1 " +
                        "ehplmns = [] hplmns = [] subscriptionType=0 mGroupOwner=null}",
                after(SUBSCRIPTION, PLMN, text).arg(2).tojstring());
        assertEquals("{id=1, iccId= simSlotIndex=0 carrierId=1911 displayName=unknown carrierName=unknown " +
                "nameSource=0 iconTint=0 mNumber=[****] dataRoaming=0 iconBitmap=null mcc 001 mnc 01" +
                "mCountryIso= isEmbedded false accessRules null cardString=[****] cardId=0 " +
                "isOpportunistic false mGroupUUID=null mIsGroupDisabled=false profileClass=-1 " +
                "ehplmns = [] hplmns = [] subscriptionType=0 mGroupOwner=null}", both(text));
    }

    // Android 8.1 and 9 print the first 9 digits of the ICCID, which name the country and the operator
    @Test
    public void subscriptionIccId() {
        String text = "{id=1, iccId=894901234[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier " +
                "nameSource=0 iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260 isEmbedded false}";
        assertEquals("{id=1, iccId= simSlotIndex=0 displayName=unknown carrierName=unknown " +
                        "nameSource=0 iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260 isEmbedded false}",
                after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring());
        // Empty already, as apps without privileges get it, nothing changes
        String empty = "{id=1, iccId= simSlotIndex=0 displayName=unknown carrierName=unknown nameSource=0}";
        assertFalse(after(SUBSCRIPTION, OPERATOR, empty).arg1().toboolean());
    }

    // The ICCID, the card string, the number and the group, like the getters of Read.Telephony: Android 14 and later,
    // of a debuggable build, which prints the ICCID and the number in full, like the emulator
    @Test
    public void subscriptionTelephony() {
        String text = "[SubscriptionInfo: id=1 iccId=" + ICCID + " simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1 " +
                "displayName=T-Mobile carrierName=T-Mobile isOpportunistic=false groupUuid=" + GROUP + " " +
                "groupOwner=com.carrier.app isGroupDisabled=false displayNameSource=CARRIER iconTint=-16746133 " +
                "number=+15555215554 dataRoaming=0 mcc=310 mnc=260 ehplmns=[310260] hplmns=[310260] cardString=" + ICCID +
                " cardId=0 nativeAccessRules=null carrierConfigAccessRules=null countryIso=us profileClass=-1 " +
                "mType=LOCAL_SIM areUiccApplicationsEnabled=true usageSetting=DEFAULT]";
        // The index of the card stays, it is no identifier
        String fake = text.replace(ICCID, "").replace(GROUP, "null");
        assertEquals(fake.replace("+15555215554", ""), telephony(text, null));
        assertEquals(fake.replace("+15555215554", "+15550100"), telephony(text, "+15550100"));
        // With the other groups, like when all are restricted
        assertEquals("[SubscriptionInfo: id=1 iccId= simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1911 " +
                "displayName=unknown carrierName=unknown isOpportunistic=false groupUuid=null groupOwner= " +
                "isGroupDisabled=false displayNameSource=CARRIER iconTint=-16746133 number=+15550100 dataRoaming=0 " +
                "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString= cardId=0 nativeAccessRules=null " +
                "carrierConfigAccessRules=null countryIso= profileClass=-1 mType=LOCAL_SIM " +
                "areUiccApplicationsEnabled=true usageSetting=DEFAULT]", telephony(both(text), "+15550100"));
        // Nothing to hide, like for apps without the permissions on Android 11 and later
        String empty = "[SubscriptionInfo: id=1 iccId= simSlotIndex=0 groupUuid=null groupOwner= iconTint=0 number= " +
                "dataRoaming=0 cardString= cardId=0 nativeAccessRules=null]";
        assertFalse(after(SUBSCRIPTION, TELEPHONY, empty, "+15550100").arg1().toboolean());
    }

    // User builds print the first 9 digits of the ICCID and of the card string, and a number of the SIM as [****],
    // also the fake one; builds which are neither user nor debuggable print a hash of the number
    @Test
    public void subscriptionTelephonyHidden() throws Exception {
        String text = "[SubscriptionInfo: id=1 iccId=890141032[****] simSlotIndex=0 groupUuid=null groupOwner= " +
                "iconTint=0 number=[****] dataRoaming=0 cardString=890141032[****] cardId=0 nativeAccessRules=null]";
        String fake = "[SubscriptionInfo: id=1 iccId= simSlotIndex=0 groupUuid=null groupOwner= " +
                "iconTint=0 number=[****] dataRoaming=0 cardString= cardId=0 nativeAccessRules=null]";
        assertEquals(fake, telephony(text, "+15550100"));
        assertEquals(fake.replace("[****]", ""), telephony(text, null));
        assertEquals(" number=[" + hash("+15550100") + "] dataRoaming=0",
                telephony(" number=[" + hash("+15555215554") + "] dataRoaming=0", "+15550100"));
    }

    // Like Rlog.pii prints a number on builds which are neither user nor debuggable
    private static String hash(String number) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(number.getBytes(StandardCharsets.UTF_8)));
    }

    // Android 11 to 13 print the group in capitals, 10 prints the number and the group as fields
    @Test
    public void subscriptionTelephonyAndroid10To13() {
        String text = "{id=1 iccId=" + ICCID + " simSlotIndex=0 carrierId=1 displayName=T-Mobile carrierName=T-Mobile " +
                "nameSource=0 iconTint=0 number=+15555215554 dataRoaming=0 iconBitmap=null mcc=310 mnc=260 countryIso=us " +
                "isEmbedded=false nativeAccessRules=null cardString=" + ICCID + " cardId=0 portIndex=0 " +
                "isOpportunistic=false groupUUID=" + GROUP + " isGroupDisabled=false profileClass=-1 " +
                "ehplmns=[310260] hplmns=[310260] subscriptionType=0 groupOwner=null}";
        String fake = text.replace(ICCID, "").replace(GROUP, "null");
        assertEquals(fake.replace("+15555215554", "+15550100"), telephony(text, "+15550100"));
        text = "{id=1, iccId=" + ICCID + " simSlotIndex=0 carrierId=1 displayName=T-Mobile carrierName=T-Mobile " +
                "nameSource=0 iconTint=0 mNumber=+15555215554 dataRoaming=0 iconBitmap=null mcc 310 mnc 260mCountryIso=us " +
                "isEmbedded false accessRules null cardString=" + ICCID + " cardId=0 isOpportunistic false " +
                "mGroupUUID=" + GROUP + " mIsGroupDisabled=false profileClass=-1 ehplmns = [310260] hplmns = [310260] " +
                "subscriptionType=0 mGroupOwner=null}";
        fake = text.replace(ICCID, "").replace(GROUP, "null");
        assertEquals(fake.replace("+15555215554", ""), telephony(text, null));
    }

    // Android 9 ends with the ICCID as the id of the card, Android 8.1 prints the ICCID only; neither prints the number
    @Test
    public void subscriptionTelephonyAndroid9() {
        String text = "{id=1, iccId=" + ICCID + " simSlotIndex=0 displayName=T-Mobile carrierName=T-Mobile nameSource=0 " +
                "iconTint=0 dataRoaming=0 iconBitmap=android.graphics.Bitmap@5e2de80 mcc 310 mnc 260 isEmbedded false " +
                "accessRules null cardId=" + ICCID + "}";
        assertEquals(text.replace(ICCID, ""), telephony(text, "+15550100"));
        assertEquals(text.replace(ICCID, ""), telephony(text.replace(ICCID, "890141032[****]"), "+15550100"));
        String old = text.substring(0, text.indexOf(" cardId=")) + "}";
        assertEquals(old.replace(ICCID, ""), telephony(old, "+15550100"));
    }

    // Android 9 and before print the codes as numbers and no country
    @Test
    public void subscriptionNumbers() {
        assertEquals("{id=1, iccId= simSlotIndex=0 displayName=unknown carrierName=unknown nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 1 mnc 1 isEmbedded false accessRules null " +
                        "cardId=[****]}",
                both("{id=1, iccId=[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260 isEmbedded false accessRules null " +
                        "cardId=[****]}"));
    }
}
