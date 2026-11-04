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

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// The faked text of a file before Android 11: in a pipe, or in a removed file when larger than a pipe holds
@RunWith(AndroidJUnit4.class)
public class TempFileTest {
    @Test
    public void largeText() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File folder = new File(context.getCacheDir(), "tempFileTest");
        folder.mkdirs();

        char[] chars = new char[64 * 1024];
        Arrays.fill(chars, 'x');
        String text = new String(chars);
        FileDescriptor fd = XParam.oldFile(folder, text.getBytes(StandardCharsets.UTF_8));
        try {
            // Read-only, like the file it replaces, and already removed
            try {
                Os.write(fd, new byte[1], 0, 1);
                fail("Writable");
            } catch (ErrnoException ex) {
                assertEquals(OsConstants.EBADF, ex.errno);
            }
            assertEquals(0, folder.list().length);
            assertTrue(OsConstants.S_ISREG(Os.fstat(fd).st_mode));
            assertEquals(chars.length, Os.fstat(fd).st_size);
            // Not owned by the stream, closed below
            assertEquals(text, Util.readText(new FileInputStream(fd)));
        } finally {
            Os.close(fd);
            folder.delete();
        }
    }

    // A small text, like of the files of /proc, in a pipe, also without a cache folder
    @Test
    public void smallText() throws Throwable {
        char[] chars = new char[4096];
        Arrays.fill(chars, 'y');
        String text = new String(chars);
        FileDescriptor fd = XParam.oldFile(null, text.getBytes(StandardCharsets.UTF_8));
        try {
            assertTrue(OsConstants.S_ISFIFO(Os.fstat(fd).st_mode));
            assertEquals(text, Util.readText(new FileInputStream(fd)));
        } finally {
            Os.close(fd);
        }
    }
}
