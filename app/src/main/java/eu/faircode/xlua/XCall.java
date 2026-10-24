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

import java.lang.reflect.Member;

// Framework independent state of a hooked method call,
// with the semantics of the legacy Xposed MethodHookParam
public class XCall {
    public final Member method;
    public final Object thisObject;
    public final Object[] args;

    private Object result = null;
    private Throwable throwable = null;
    private boolean returnEarly = false;

    XCall(Member method, Object thisObject, Object[] args) {
        this.method = method;
        this.thisObject = thisObject;
        this.args = args;
    }

    public Object getResult() {
        return this.result;
    }

    // Setting a result before the call skips the original method
    public void setResult(Object result) {
        this.result = result;
        this.throwable = null;
        this.returnEarly = true;
    }

    public Throwable getThrowable() {
        return this.throwable;
    }

    public boolean hasThrowable() {
        return (this.throwable != null);
    }

    // Setting a throwable before the call skips the original method
    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
        this.result = null;
        this.returnEarly = true;
    }

    boolean isReturnEarly() {
        return this.returnEarly;
    }

    // Outcome of the original method, without skipping it
    void setOutcome(Object result, Throwable throwable) {
        this.result = result;
        this.throwable = throwable;
        this.returnEarly = false;
    }
}
