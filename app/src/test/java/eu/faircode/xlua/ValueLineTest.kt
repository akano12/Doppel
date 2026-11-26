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

package eu.faircode.xlua

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ValueLineTest {
    @Test
    fun bothFitKeepTheirSize() {
        assertArrayEquals(intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE), share(300, 100, 150))
    }

    @Test
    fun shortValueKeepsItsSizeAndTheOtherTakesTheRest() {
        assertArrayEquals(intArrayOf(60, 140), share(200, 60, 400))
        assertArrayEquals(intArrayOf(140, 60), share(200, 400, 60))
    }

    @Test
    fun longValuesShareTheRoom() {
        assertArrayEquals(intArrayOf(100, 100), share(200, 400, 400))
    }

    @Test
    fun noRoomLeavesNothingNegative() {
        val caps = share(-20, 100, 100)
        assertEquals(0, caps.sum())
    }
}
