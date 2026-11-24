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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ValueLineTest {
    @Test
    public void bothFitKeepTheirSize() {
        assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}, ValueLine.share(300, 100, 150));
    }

    @Test
    public void shortValueKeepsItsSizeAndTheOtherTakesTheRest() {
        assertArrayEquals(new int[]{60, 140}, ValueLine.share(200, 60, 400));
        assertArrayEquals(new int[]{140, 60}, ValueLine.share(200, 400, 60));
    }

    @Test
    public void longValuesShareTheRoom() {
        assertArrayEquals(new int[]{100, 100}, ValueLine.share(200, 400, 400));
    }

    @Test
    public void noRoomLeavesNothingNegative() {
        int[] caps = ValueLine.share(-20, 100, 100);
        assertEquals(0, caps[0] + caps[1]);
    }
}
