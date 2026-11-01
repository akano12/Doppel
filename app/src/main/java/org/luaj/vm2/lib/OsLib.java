/*******************************************************************************
* Copyright (c) 2009 Luaj.org. All rights reserved.
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
package org.luaj.vm2.lib;

import java.util.Calendar;
import java.util.Date;

import org.luaj.vm2.Buffer;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The lua {@code os} library, trimmed to clock, date, difftime and time:
 * scripts of hooks have no use for the process, environment or file functions.
 */
public class OsLib extends TwoArgFunction {
	private static final int CLOCK     = 0;
	private static final int DATE      = 1;
	private static final int DIFFTIME  = 2;
	private static final int TIME      = 3;

	private static final String[] NAMES = {
		"clock",
		"date",
		"difftime",
		"time",
	};
	
	private static final long t0 = System.currentTimeMillis();

	/** 
	 * Create and OsLib instance.   
	 */
	public OsLib() {
	}
	
	/** Perform one-time initialization on the library by creating a table
	 * containing the library functions, adding that table to the supplied environment,
	 * adding the table to package.loaded, and returning table as the return value.
	 * @param modname the module name supplied if this is loaded via 'require'.
	 * @param env the environment to load into, typically a Globals instance.
	 */
	public LuaValue call(LuaValue modname, LuaValue env) {
		LuaTable os = new LuaTable();
		for (int i = 0; i < NAMES.length; ++i)
			os.set(NAMES[i], new OsLibFunc(i, NAMES[i]));
		env.set("os", os);
		return os;
	}

	class OsLibFunc extends VarArgFunction {
		public OsLibFunc(int opcode, String name) {
			this.opcode = opcode;
			this.name = name;
		}
		public Varargs invoke(Varargs args) {
			switch ( opcode ) {
			case CLOCK:
				return valueOf(clock());
			case DATE: {
				String s = args.optjstring(1, "%c");
				double t = args.isnumber(2)? args.todouble(2): time(null);
				if (s.equals("*t")) {
					Calendar d = Calendar.getInstance();
					d.setTime(new Date((long)(t*1000)));
					LuaTable tbl = LuaValue.tableOf();
					tbl.set("year", LuaValue.valueOf(d.get(Calendar.YEAR)));
					tbl.set("month", LuaValue.valueOf(d.get(Calendar.MONTH)+1));
					tbl.set("day", LuaValue.valueOf(d.get(Calendar.DAY_OF_MONTH)));
					tbl.set("hour", LuaValue.valueOf(d.get(Calendar.HOUR_OF_DAY)));
					tbl.set("min", LuaValue.valueOf(d.get(Calendar.MINUTE)));
					tbl.set("sec", LuaValue.valueOf(d.get(Calendar.SECOND)));
					tbl.set("wday", LuaValue.valueOf(d.get(Calendar.DAY_OF_WEEK)));
					tbl.set("yday", LuaValue.valueOf(d.get(0x6))); // Day of year
					tbl.set("isdst", LuaValue.valueOf(isDaylightSavingsTime(d)));
					return tbl;
				}
				return valueOf( date(s, t==-1? time(null): t) );
			}
			case DIFFTIME:
				return valueOf(difftime(args.checkdouble(1),args.checkdouble(2)));
			case TIME:
				return valueOf(time(args.opttable(1, null)));
			}
			return NONE;
		}
	}

	/**
	 * @return an approximation of the amount in seconds of CPU time used by 
	 * the program.  For luaj this simple returns the elapsed time since the 
	 * OsLib class was loaded.
	 */
	protected double clock() {
		return (System.currentTimeMillis()-t0) / 1000.;
	}

	/**
	 * Returns the number of seconds from time t1 to time t2. 
	 * In POSIX, Windows, and some other systems, this value is exactly t2-t1.
	 * @param t2
	 * @param t1
	 * @return diffeence in time values, in seconds
	 */
	protected double difftime(double t2, double t1) {
		return t2 - t1;
	}

	/**
	 * If the time argument is present, this is the time to be formatted 
	 * (see the os.time function for a description of this value). 
	 * Otherwise, date formats the current time.
	 * 
	 * Date returns the date as a string, 
	 * formatted according to the same rules as ANSII strftime, but without
	 * support for %g, %G, or %V.
	 * 
	 * When called without arguments, date returns a reasonable date and 
	 * time representation that depends on the host system and on the 
	 * current locale (that is, os.date() is equivalent to os.date("%c")).
	 *  
	 * @param format 
	 * @param time time since epoch, or -1 if not supplied
	 * @return a LString or a LTable containing date and time, 
	 * formatted according to the given string format.
	 */
	public String date(String format, double time) {
		Calendar d = Calendar.getInstance();
		d.setTime(new Date((long)(time*1000)));
		if (format.startsWith("!")) {
			time -= timeZoneOffset(d);
			d.setTime(new Date((long)(time*1000)));
			format = format.substring(1);
		}
		byte[] fmt = format.getBytes();
		final int n = fmt.length;
		Buffer result = new Buffer(n);
		byte c;
		for ( int i = 0; i < n; ) {
			switch ( c = fmt[i++ ] ) {
			case '\n':
				result.append( "\n" );
				break;
			default:
				result.append( c );
				break;
			case '%':
				if (i >= n) break;
				switch ( c = fmt[i++ ] ) {
				default:
					LuaValue.argerror(1, "invalid conversion specifier '%"+c+"'");
					break;
				case '%':
					result.append( (byte)'%' );
					break;
				case 'a':
					result.append(WeekdayNameAbbrev[d.get(Calendar.DAY_OF_WEEK)-1]);
					break;
				case 'A':
					result.append(WeekdayName[d.get(Calendar.DAY_OF_WEEK)-1]);
					break;
				case 'b':
					result.append(MonthNameAbbrev[d.get(Calendar.MONTH)]);
					break;
				case 'B':
					result.append(MonthName[d.get(Calendar.MONTH)]);
					break;					 
				case 'c': 
					result.append(date("%a %b %d %H:%M:%S %Y", time));
					break;
				case 'd':
					result.append(String.valueOf(100+d.get(Calendar.DAY_OF_MONTH)).substring(1));
					break;
				case 'H':
					result.append(String.valueOf(100+d.get(Calendar.HOUR_OF_DAY)).substring(1));
					break;
				case 'I':
					result.append(String.valueOf(100+(d.get(Calendar.HOUR_OF_DAY)%12)).substring(1));
					break;
				case 'j': { // day of year.
					Calendar y0 = beginningOfYear(d);
					int dayOfYear = (int) ((d.getTime().getTime() - y0.getTime().getTime()) / (24 * 3600L * 1000L));
					result.append(String.valueOf(1001+dayOfYear).substring(1));
					break;
				}
				case 'm':
					result.append(String.valueOf(101+d.get(Calendar.MONTH)).substring(1));
					break;
				case 'M':
					result.append(String.valueOf(100+d.get(Calendar.MINUTE)).substring(1));
					break;
				case 'p':
					result.append(d.get(Calendar.HOUR_OF_DAY) < 12? "AM": "PM");
					break;
				case 'S':
					result.append(String.valueOf(100+d.get(Calendar.SECOND)).substring(1));
					break;
				case 'U':
					result.append(String.valueOf(weekNumber(d, 0)));
					break;
				case 'w':
					result.append(String.valueOf((d.get(Calendar.DAY_OF_WEEK)+6)%7));
					break;
				case 'W': 
					result.append(String.valueOf(weekNumber(d, 1)));
					break;
				case 'x':
					result.append(date("%m/%d/%y", time));
					break;
				case 'X':
					result.append(date("%H:%M:%S", time));
					break;
				case 'y':
					result.append(String.valueOf(d.get(Calendar.YEAR)).substring(2));
					break;
				case 'Y':
					result.append(String.valueOf(d.get(Calendar.YEAR)));
					break;
				case 'z': {
					final int tzo = timeZoneOffset(d) / 60;
					final int a = Math.abs(tzo);
					final String h = String.valueOf(100 + a / 60).substring(1);
					final String m = String.valueOf(100 + a % 60).substring(1);
					result.append((tzo>=0? "+": "-") + h + m);
					break;
				}
				}
			}
		}
		return result.tojstring();
	}
	
	private static final String[] WeekdayNameAbbrev = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
	private static final String[] WeekdayName = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
	private static final String[] MonthNameAbbrev = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
	private static final String[] MonthName = { "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December" };

	private Calendar beginningOfYear(Calendar d) {
		Calendar y0 = Calendar.getInstance();
		y0.setTime(d.getTime());
		y0.set(Calendar.MONTH, 0);
		y0.set(Calendar.DAY_OF_MONTH, 1);
		y0.set(Calendar.HOUR_OF_DAY, 0);
		y0.set(Calendar.MINUTE, 0);
		y0.set(Calendar.SECOND, 0);
		y0.set(Calendar.MILLISECOND, 0);
		return y0;
	}
	
	private int weekNumber(Calendar d, int startDay) {
		Calendar y0 =  beginningOfYear(d);
		y0.set(Calendar.DAY_OF_MONTH, 1 + (startDay + 8 - y0.get(Calendar.DAY_OF_WEEK)) % 7);
		if (y0.after(d)) {
			y0.set(Calendar.YEAR, y0.get(Calendar.YEAR) - 1);
			y0.set(Calendar.DAY_OF_MONTH, 1 + (startDay + 8 - y0.get(Calendar.DAY_OF_WEEK)) % 7);
		}
		long dt = d.getTime().getTime() - y0.getTime().getTime();
		return 1 + (int) (dt / (7L * 24L * 3600L * 1000L));
	}
	
	private int timeZoneOffset(Calendar d) {
		int localStandarTimeMillis = ( 
				d.get(Calendar.HOUR_OF_DAY) * 3600 +
				d.get(Calendar.MINUTE) * 60 +
				d.get(Calendar.SECOND)) * 1000;
		return d.getTimeZone().getOffset(
				1,
				d.get(Calendar.YEAR),
				d.get(Calendar.MONTH),
				d.get(Calendar.DAY_OF_MONTH),
				d.get(Calendar.DAY_OF_WEEK), 
				localStandarTimeMillis) / 1000;
	}
	
	private boolean isDaylightSavingsTime(Calendar d) {
		return timeZoneOffset(d) != d.getTimeZone().getRawOffset() / 1000;		
	}
	
	/**
	 * Returns the current time when called without arguments, 
	 * or a time representing the date and time specified by the given table. 
	 * This table must have fields year, month, and day, 
	 * and may have fields hour, min, sec, and isdst 
	 * (for a description of these fields, see the os.date function).
	 * @param table
	 * @return long value for the time
	 */
	protected double time(LuaTable table) {
		java.util.Date d;
		if (table == null) {
			d = new java.util.Date();
		} else {
			Calendar c = Calendar.getInstance();
			c.set(Calendar.YEAR, table.get("year").checkint());
			c.set(Calendar.MONTH, table.get("month").checkint()-1);
			c.set(Calendar.DAY_OF_MONTH, table.get("day").checkint());
			c.set(Calendar.HOUR_OF_DAY, table.get("hour").optint(12));
			c.set(Calendar.MINUTE, table.get("min").optint(0));
			c.set(Calendar.SECOND, table.get("sec").optint(0));
			c.set(Calendar.MILLISECOND, 0);
			d = c.getTime();
		}
		return d.getTime() / 1000.;
	}
}
