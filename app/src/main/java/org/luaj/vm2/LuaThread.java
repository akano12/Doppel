/*******************************************************************************
* Copyright (c) 2007-2012 LuaJ. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package org.luaj.vm2;

/** 
 * The state of the main lua thread of a {@link Globals}, for the debug library
 * and the error handler of xpcall; coroutines are not supported.
 */
public class LuaThread extends LuaValue {

	/** Shared metatable for lua threads. */
	public static LuaValue s_metatable;

	public final State state = new State();

	public static final int        MAX_CALLSTACK = 256;

	/** Thread-local used by DebugLib to store debugging state. 
	 * This is an opaque value that should not be modified by applications. */
	public Object callstack;

	public final Globals globals;

	/** Error message handler for this thread, if any.  */
	public LuaValue errorfunc;
	
	public LuaThread(Globals globals) {
		this.globals = globals;
	}
	
	public int type() {
		return LuaValue.TTHREAD;
	}
	
	public String typename() {
		return "thread";
	}
	
	public boolean isthread() {
		return true;
	}
	
	public LuaThread optthread(LuaThread defval) {
		return this;
	}
	
	public LuaThread checkthread() {
		return this;
	}
	
	public LuaValue getmetatable() { 
		return s_metatable; 
	}

	public static class State {
		/** Hook function control state used by debug lib. */
		public LuaValue hookfunc;

		public boolean hookline;
		public boolean hookcall;
		public boolean hookrtrn;
		public int hookcount;
		public boolean inhook;
		public int lastline;
		public int bytecodes;
	}
}
