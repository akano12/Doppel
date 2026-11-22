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

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The helpers of the screens: the shared background thread and the search
public class UiTest {
    // The write of a screen which closes comes before the load of the screen which opens, whichever screen queued it,
    // also when the write is slow
    @Test
    public void tasksOfAllScreensRunInTheOrderTheyWereQueued() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Ui.submit(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            order.add("write of the closing screen");
        });
        Ui.submit(() -> {
            order.add("load of the next screen");
            done.countDown();
        });
        order.add("queued");
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("queued", "write of the closing screen", "load of the next screen"), order);
    }

    // A task which fails goes to the handler of errors and leaves the thread running for the next one
    @Test
    public void failedTaskIsReportedAndLaterTasksRun() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Ui.submit(() -> {
            throw new IllegalStateException("failed");
        }, ex -> {
            errors.add(ex);
            done.countDown();
        });
        Ui.submit(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, errors.size());
    }

    // A screen with an executor of its own would be ordered with itself only
    @Test
    public void screensHaveNoExecutorOfTheirOwn() throws Exception {
        for (String name : new String[]{"ActivityBase", "FragmentBase"})
            for (Field field : Class.forName("eu.faircode.xlua." + name, false, getClass().getClassLoader()).getDeclaredFields())
                assertFalse(name + "." + field.getName(), ExecutorService.class.isAssignableFrom(field.getType()));
    }

    @Test
    public void searchIgnoresCaseAndNull() {
        assertTrue(Ui.contains("Android System", "SYSTEM"));
        assertFalse(Ui.contains("Android System", "systems"));
        assertFalse(Ui.contains(null, "a"));
        assertTrue(new Ui.Query("").in("anything"));
    }

    // The language of the device lower-cases some letters in another way, which labels are searched with
    @Test
    public void searchFindsLabelsInTheLanguageOfTheDevice() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(Ui.contains("ISPARTA", "ısparta"));
            assertTrue(new Ui.Haystack("com.example", "ISPARTA").has(new Ui.Query("ısparta")));
            // Ids are matched with the root locale too
            assertTrue(new Ui.Haystack("com.example.INFO", null).has(new Ui.Query("info")));
        } finally {
            Locale.setDefault(before);
        }
    }

    // Lower-cased once per row gives the same answers as texts matched one by one, and no match over the line break
    @Test
    public void haystackAgreesWithContains() {
        String[] texts = {"Camera", null, "com.android.camera", "Take pictures"};
        Ui.Haystack haystack = new Ui.Haystack(texts);
        for (String query : new String[]{"cam", "PICT", "droid.cam", "nothing", "x"}) {
            boolean one = false;
            for (String text : texts)
                one |= Ui.contains(text, query);
            assertEquals(query, one, haystack.has(new Ui.Query(query)));
        }
        assertFalse(haystack.has(new Ui.Query("cameracom")));
    }
}
