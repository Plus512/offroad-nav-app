package com.alruwaili.garminalbarr.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmdroidPoiOverlayScaleTest {

    @Test
    fun zoomScaleFor_isOneAtIntegerZooms() {
        assertEquals(1.0, OsmdroidPoiOverlay.zoomScaleFor(12.0), 1e-9)
        assertEquals(1.0, OsmdroidPoiOverlay.zoomScaleFor(13.0), 1e-9)
        assertEquals(1.0, OsmdroidPoiOverlay.zoomScaleFor(15.0), 1e-9)
    }

    @Test
    fun zoomScaleFor_matchesTileBitmapOverZoom() {
        // Halfway through the zoom interval the bitmap (and DB labels) scale by sqrt(2).
        assertEquals(1.4142, OsmdroidPoiOverlay.zoomScaleFor(12.5), 1e-4)
        // Just below the next integer zoom the factor approaches 2 (snap point).
        assertEquals(1.8661, OsmdroidPoiOverlay.zoomScaleFor(14.9), 1e-4)
    }

    @Test
    fun zoomScaleFor_staysWithinOneInclusiveToTwoExclusive() {
        for (z in 110..160) {
            val s = OsmdroidPoiOverlay.zoomScaleFor(z / 10.0)
            assertTrue("scale $s out of range at zoom ${z / 10.0}", s >= 1.0 && s < 2.0)
        }
    }
}
