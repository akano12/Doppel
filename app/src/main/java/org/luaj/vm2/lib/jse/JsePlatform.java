/*******************************************************************************
 * Copyright (c) 2009-2011 Luaj.org. All rights reserved.
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
package org.luaj.vm2.lib.jse;

import org.luaj.vm2.Globals;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.OsLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;

/** Initializes the globals of the scripts of hooks:
 * base (without dofile and loadfile), bit32, table, string, math, os (clock, date, difftime and time only)
 * and luajava, with the {@link LuaC} compiler for source text.
 * There are no io, coroutine, package and debug libraries.
 */
public class JsePlatform {

	public static Globals standardGlobals() {
		Globals globals = new Globals();
		globals.load(new BaseLib());
		globals.load(new Bit32Lib());
		globals.load(new TableLib());
		globals.load(new StringLib());
		globals.load(new JseMathLib());
		globals.load(new OsLib());
		globals.load(new LuajavaLib());
		LuaC.install(globals);
		return globals;		
	}

}
