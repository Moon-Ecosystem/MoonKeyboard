package com.moonkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoRowOrderTest {
  // The grid fills columns top then bottom, so pairs here are (top, bottom) of each column
  @Test fun fullScreensReadAlongTheTopRowFirst() =
    assertEquals(listOf(0, 3, 1, 4, 2, 5, 6, 9, 7, 10, 8, 11), twoRowOrder((0..11).toList(), cols = 3))

  @Test fun shortLastScreenSplitsEvenly() =
    assertEquals(listOf(0, 3, 1, 4, 2, 5, 6, 8, 7), twoRowOrder((0..8).toList(), cols = 3))

  @Test fun fewItemsStayInOrder() = assertEquals(listOf(0), twoRowOrder(listOf(0), cols = 3))
}
