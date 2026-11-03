package com.example.firetvchannels

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun parseChannels_isCorrect() {
    val sample = """
      SPORT 24 HD 🇮🇹 | [www.htsport.org/sport24hd.htm](https://www.htsport.org/sport24hd.htm)
      SPORT 24 🇮🇹 | [www.htsport.org/sport24.htm](https://www.htsport.org/sport24.htm)
      SPORT 1 HD 🇮🇹 | [www.htsport.org/sportunohd.htm](https://www.htsport.org/sportunohd.htm)
    """.trimIndent()
    val channels = MainActivity.parseChannels(sample)
    assertEquals(3, channels.size)
    assertEquals("SPORT 24 HD 🇮🇹", channels[0].name)
    assertEquals("https://www.htsport.org/sport24hd.htm", channels[0].url)
  }
}
