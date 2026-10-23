/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

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
    // The network codes, in Read.Telephony
    private static final String PLMN = "SubscriptionInfo.toString/plmn";

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("operator_tostring", null);
    }

    private Varargs after(String className, String name, String text) {
        LuaValue hook = globals.load("local className, name = ...\n" +
                "return { getClassName = function(self) return className end,\n" +
                "    getName = function(self) return name end }").invoke(
                LuaValue.valueOf(className), LuaValue.valueOf(name)).arg1();
        LuaValue param = globals.load("local text = ...\n" +
                "local param = { result = text }\n" +
                "function param.getResult(self) return self.result end\n" +
                "function param.setResult(self, value) self.result = value end\n" +
                "return param").call(LuaValue.valueOf(text));
        Varargs result = globals.get("after").invoke(hook, param);
        return LuaValue.varargsOf(result.arg1(), param.get("result"));
    }

    private Varargs after(String className, String text) {
        String name = className.substring(className.lastIndexOf('.') + 1) + ".toString/operator";
        return after(className, name, text);
    }

    // Both definitions of the subscription, like when both groups are restricted
    private String both(String text) {
        return after(SUBSCRIPTION, PLMN, after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring()).arg(2).tojstring();
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

    // Android 8.0 and before print the values without labels, which stay
    @Test
    public void serviceStateWithoutLabels() {
        String text = "0 0 voice home data home Carrier, Inc. CAR 310260 Carrier, Inc. CAR 310260  LTE LTE " +
                "CSS not supported -1 -1 RoamInd=-1 DefRoamInd=-1 EmergOnly=false IsDataRoamingFromRegistration=false";
        Varargs result = after(SERVICE_STATE, text);
        assertFalse(result.arg1().toboolean());
        assertEquals(text, result.arg(2).tojstring());
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
        assertEquals("[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=-1 " +
                        "displayName=unknown carrierName=unknown isOpportunistic=false number=[****] " +
                        "mcc=310 mnc=260 ehplmns=[310260] hplmns=[310260] cardString=[****] countryIso=xx " +
                        "profileClass=-1 mType=LOCAL_SIM]",
                after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring());
        assertEquals("[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1 " +
                        "displayName=My Carrier carrierName=My Carrier isOpportunistic=false number=[****] " +
                        "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString=[****] countryIso=us " +
                        "profileClass=-1 mType=LOCAL_SIM]",
                after(SUBSCRIPTION, PLMN, text).arg(2).tojstring());
        assertEquals("[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=-1 " +
                "displayName=unknown carrierName=unknown isOpportunistic=false number=[****] " +
                "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString=[****] countryIso=xx " +
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
        assertEquals("{id=1 iccId=[****] simSlotIndex=0 carrierId=-1 displayName=unknown carrierName=unknown " +
                        "nameSource=0 iconTint=0 number=[****] dataRoaming=0 iconBitmap=null mcc=310 mnc=260 " +
                        "countryIso=xx isEmbedded=false nativeAccessRules=null cardString=[****] cardId=0 portIndex=0 " +
                        "isOpportunistic=false groupUUID=null isGroupDisabled=false profileClass=-1 " +
                        "ehplmns=[310260] hplmns=[310260] subscriptionType=0 groupOwner=null}",
                after(SUBSCRIPTION, OPERATOR, text).arg(2).tojstring());
        assertEquals("{id=1 iccId=[****] simSlotIndex=0 carrierId=-1 displayName=unknown carrierName=unknown " +
                "nameSource=0 iconTint=0 number=[****] dataRoaming=0 iconBitmap=null mcc=001 mnc=01 countryIso=xx " +
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
        assertEquals("{id=1, iccId=[****] simSlotIndex=0 carrierId=-1 displayName=unknown carrierName=unknown " +
                "nameSource=0 iconTint=0 mNumber=[****] dataRoaming=0 iconBitmap=null mcc 001 mnc 01" +
                "mCountryIso=xx isEmbedded false accessRules null cardString=[****] cardId=0 " +
                "isOpportunistic false mGroupUUID=null mIsGroupDisabled=false profileClass=-1 " +
                "ehplmns = [] hplmns = [] subscriptionType=0 mGroupOwner=null}", both(text));
    }

    // Android 9 and before print the codes as numbers and no country
    @Test
    public void subscriptionNumbers() {
        assertEquals("{id=1, iccId=[****] simSlotIndex=0 displayName=unknown carrierName=unknown nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 1 mnc 1 isEmbedded false accessRules null " +
                        "cardId=[****]}",
                both("{id=1, iccId=[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260 isEmbedded false accessRules null " +
                        "cardId=[****]}"));
        // Android 7.0
        assertEquals("{id=1, iccId=[****] simSlotIndex=0 displayName=unknown carrierName=unknown nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 1 mnc 1 SimProvisioningStatus 0}",
                both("{id=1, iccId=[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260 SimProvisioningStatus 0}"));
        // Android 6, 7.1 and 8.0
        Varargs result = after(SUBSCRIPTION, PLMN,
                "{id=1, iccId=[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier nameSource=0 " +
                        "iconTint=0 dataRoaming=0 iconBitmap=null mcc 310 mnc 260}");
        assertTrue(result.arg1().toboolean());
        assertEquals("{id=1, iccId=[****] simSlotIndex=0 displayName=My Carrier carrierName=My Carrier nameSource=0 " +
                "iconTint=0 dataRoaming=0 iconBitmap=null mcc 1 mnc 1}", result.arg(2).tojstring());
    }
}
