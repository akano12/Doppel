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
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The operator in the text of the service state, network registrations and subscriptions
// (assets/operator_tostring.lua), with the formats of Android 16
public class OperatorTextScriptTest {
    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = JsePlatform.standardGlobals();
        String script = new String(Files.readAllBytes(new File("src/main/assets/operator_tostring.lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(script, "operator_tostring").call();
    }

    private Varargs after(String className, String text) {
        LuaValue hook = globals.load("return { getClassName = function(self) return '" + className + "' end }").call();
        LuaValue param = globals.load("local text = ...\n" +
                "local param = { result = text }\n" +
                "function param.getResult(self) return self.result end\n" +
                "function param.setResult(self, value) self.result = value end\n" +
                "return param").call(LuaValue.valueOf(text));
        Varargs result = globals.get("after").invoke(hook, param);
        return LuaValue.varargsOf(result.arg1(), param.get("result"));
    }

    @Test
    public void serviceState() {
        Varargs result = after("android.telephony.ServiceState",
                "{mVoiceRegState=0(IN_SERVICE), mOperatorAlphaLong=Carrier, Inc., mOperatorAlphaShort=CAR, " +
                        "isManualNetworkSelection=false(automatic), mOperatorAlphaLongRaw=Carrier, Inc., " +
                        "mOperatorAlphaShortRaw=CAR, mIsDataRoamingFromRegistration=false}");
        assertTrue(result.arg1().toboolean());
        assertEquals("{mVoiceRegState=0(IN_SERVICE), mOperatorAlphaLong=unknown, mOperatorAlphaShort=unknown, " +
                "isManualNetworkSelection=false(automatic), mOperatorAlphaLongRaw=unknown, " +
                "mOperatorAlphaShortRaw=unknown, mIsDataRoamingFromRegistration=false}", result.arg(2).tojstring());
    }

    // Without service the names are empty, a fake value would tell they are faked
    @Test
    public void emptyValuesStay() {
        String text = "{mVoiceRegState=1(OUT_OF_SERVICE), mOperatorAlphaLong=, mOperatorAlphaShort=, " +
                "isManualNetworkSelection=false(automatic)}";
        Varargs result = after("android.telephony.ServiceState", text);
        assertFalse(result.arg1().toboolean());
        assertEquals(text, result.arg(2).tojstring());
    }

    @Test
    public void registration() {
        Varargs result = after("android.telephony.NetworkRegistrationInfo",
                "NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=310260 isUsingCarrierAggregation=false}");
        assertEquals("NetworkRegistrationInfo{ domain=PS nrState=NONE rRplmn=00101 isUsingCarrierAggregation=false}",
                result.arg(2).tojstring());
    }

    @Test
    public void subscription() {
        Varargs result = after("android.telephony.SubscriptionInfo",
                "[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=1 " +
                        "displayName=My Carrier carrierName=My Carrier isOpportunistic=false number=[****] " +
                        "mcc=310 mnc=260 ehplmns=[310260] hplmns=[310260] cardString=[****] countryIso=us " +
                        "profileClass=-1]");
        assertEquals("[SubscriptionInfo: id=1 iccId=[****] simSlotIndex=0 portIndex=0 isEmbedded=false carrierId=-1 " +
                "displayName=unknown carrierName=unknown isOpportunistic=false number=[****] " +
                "mcc=001 mnc=01 ehplmns=[] hplmns=[] cardString=[****] countryIso=xx profileClass=-1]",
                result.arg(2).tojstring());
    }
}
