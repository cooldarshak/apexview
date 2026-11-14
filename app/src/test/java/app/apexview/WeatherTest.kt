package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test
    fun parsesTimestampedWeatherFromF1Stream() {
        val start = 1_000_000L
        val stream = "00:00:05.000{\"WindSpeed\":\"1.5\",\"WindDirection\":\"135\",\"TrackTemp\":\"39.5\",\"AirTemp\":\"29.9\",\"Humidity\":\"74.8\",\"Pressure\":\"1009.8\",\"Rainfall\":\"0\"}"
        val reading = WeatherParser.parse(stream, start).at(start + 5_000)!!

        assertEquals(1.5, reading.windSpeedMps!!, 0.001)
        assertEquals(135.0, reading.windDirectionDegrees!!, 0.001)
        assertEquals(39.5, reading.trackTempC!!, 0.001)
        assertEquals(29.9, reading.airTempC!!, 0.001)
        assertEquals(74.8, reading.humidityPct!!, 0.001)
        assertEquals(1009.8, reading.pressureHpa!!, 0.001)
        assertFalse(reading.raining!!)
    }

    @Test
    fun doesNotShowFutureWeatherBeforeTheFirstSample() {
        val start = 1_000_000L
        val stream = "00:00:05.000{\"WindSpeed\":\"1.5\"}"
        assertEquals(null, WeatherParser.parse(stream, start).at(start + 4_000))
    }

    @Test
    fun reportsRainWhenF1RainfallFlagIsSet() {
        val reading = WeatherParser.parse(
            "00:00:05.000{\"Rainfall\":\"1\"}",
            1_000_000L,
        ).at(1_005_000L)!!

        assertTrue(reading.raining!!)
    }
}
