package com.alruwaili.garminalbarr.map

import org.junit.Assert.assertEquals
import org.junit.Test

class BRouterSegmentMathTest {

    @Test
    fun getSegmentNameForCoordinate_jordanAmmanAndPetra() {
        assertEquals("E35_N30.rd5", MapFileManager.getSegmentNameForCoordinate(31.95, 35.93))
        assertEquals("E35_N30.rd5", MapFileManager.getSegmentNameForCoordinate(30.32, 35.44))
    }

    @Test
    fun getSegmentNameForCoordinate_jordanAqaba() {
        assertEquals("E35_N25.rd5", MapFileManager.getSegmentNameForCoordinate(29.53, 35.00))
    }

    @Test
    fun getSegmentNameForCoordinate_saudiRiyadh() {
        assertEquals("E45_N20.rd5", MapFileManager.getSegmentNameForCoordinate(24.71, 46.67))
    }

    @Test
    fun getSegmentNameForCoordinate_egyptCairo() {
        assertEquals("E30_N30.rd5", MapFileManager.getSegmentNameForCoordinate(30.04, 31.23))
    }

    @Test
    fun getSegmentNameForCoordinate_westernAndSouthernHemispheres() {
        assertEquals("W50_S15.rd5", MapFileManager.getSegmentNameForCoordinate(-12.34, -45.67))
    }
}
