package com.moonkeyboard.engine.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneTimeCodeTest {
  @Test
  fun justTheCode() = assertEquals("482913", oneTimeCode("482913"))

  @Test
  fun inAMessage() = assertEquals("482913", oneTimeCode("Your verification code is 482913. Don't share it."))

  @Test
  fun prefixedWithALetter() = assertEquals("482913", oneTimeCode("G-482913 is your Google verification code."))

  @Test
  fun splitInTwo() = assertEquals("482913", oneTimeCode("Code: 482 913"))

  @Test
  fun nearestTheKeywordWins() =
    assertEquals("482913", oneTimeCode("482913 is the OTP for your txn of Rs 5000 at Amazon. Valid for 10 mins."))

  @Test
  fun amountsDatesAndTimesAreNotCodes() =
    assertEquals("7731", oneTimeCode("OTP 7731 for Rs. 1,250.00 on 30/10/2026 at 10:45"))

  @Test
  fun phoneNumbersAreTooLong() = assertNull(oneTimeCode("Call me on 9876543210"))

  @Test
  fun severalNumbersAndNoKeyword() = assertNull(oneTimeCode("Rooms 4021 and 4022"))

  @Test
  fun noDigits() = assertNull(oneTimeCode("see you soon"))
}
