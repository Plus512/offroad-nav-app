package com.navigationgps.desert.track

import android.location.Location
import com.navigationgps.desert.map.NavigationMath
import kotlin.math.abs

data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timestampMillis: Long,
    val distanceDeltaMeters: Double
)

data class RawGpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyMeters: Float? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

sealed class FilterResult {
    data class Accept(val point: RecordedPoint) : FilterResult()
    object Stationary : FilterResult()
    object SkipIntermediate : FilterResult()
    data class Reject(val reason: String) : FilterResult()
}

/**
 * Desert-proven GPS jitter dampener & curvature sampler.
 *
 * Prevents false distance drift when stationary in camp/dunes (multipath noise),
 * drops inaccurate fixes (accuracy > 25m), samples sharp curves at high resolution,
 * and smooths barometric/GPS altitude via EMA.
 */
class TrackJitterFilter(
    private val maxAccuracyMeters: Float = 25.0f,
    private val stationarySpeedThresholdMps: Float = 0.8f, // ~2.88 km/h
    private val minDisplacementStationaryMeters: Double = 4.0,
    private val minRecordDisplacementMeters: Double = 5.0,
    private val curveBearingDeltaDeg: Float = 12.0f,
    private val maxTimeGapSeconds: Long = 10L,
    private val maxVerticalSpeedMps: Double = 15.0
) {
    private var lastAcceptedPoint: RecordedPoint? = null
    private var smoothedAltitude: Double = Double.NaN

    fun reset() {
        lastAcceptedPoint = null
        smoothedAltitude = Double.NaN
    }

    fun getLastAcceptedPoint(): RecordedPoint? = lastAcceptedPoint

    fun filter(rawLocation: Location): FilterResult {
        val fix = RawGpsFix(
            latitude = rawLocation.latitude,
            longitude = rawLocation.longitude,
            altitude = if (rawLocation.hasAltitude()) rawLocation.altitude else null,
            speedMps = if (rawLocation.hasSpeed()) rawLocation.speed else null,
            bearingDeg = if (rawLocation.hasBearing()) rawLocation.bearing else null,
            accuracyMeters = if (rawLocation.hasAccuracy()) rawLocation.accuracy else null,
            timestampMillis = rawLocation.time
        )
        return filter(fix)
    }

    fun filter(rawFix: RawGpsFix): FilterResult {
        // 1. Horizontal Accuracy Gate
        if (rawFix.accuracyMeters != null && rawFix.accuracyMeters > maxAccuracyMeters) {
            return FilterResult.Reject("Accuracy poor (${rawFix.accuracyMeters}m > ${maxAccuracyMeters}m)")
        }

        val rawAlt = if (rawFix.altitude != null && rawFix.altitude.isFinite()) rawFix.altitude else null

        val last = lastAcceptedPoint
        if (last == null) {
            val initialAlt = rawAlt ?: 0.0
            smoothedAltitude = initialAlt
            val firstPoint = RecordedPoint(
                latitude = rawFix.latitude,
                longitude = rawFix.longitude,
                altitude = rawAlt,
                speedMps = rawFix.speedMps ?: 0f,
                bearingDeg = rawFix.bearingDeg,
                timestampMillis = rawFix.timestampMillis,
                distanceDeltaMeters = 0.0
            )
            lastAcceptedPoint = firstPoint
            return FilterResult.Accept(firstPoint)
        }

        val distance = NavigationMath.distanceMeters(
            last.latitude, last.longitude,
            rawFix.latitude, rawFix.longitude
        )
        val timeDeltaSec = ((rawFix.timestampMillis - last.timestampMillis) / 1000.0).coerceAtLeast(0.1)
        val speed = rawFix.speedMps ?: (distance / timeDeltaSec).toFloat()

        // 2. Stationary Jitter Gate
        if (speed < stationarySpeedThresholdMps && distance < minDisplacementStationaryMeters) {
            return FilterResult.Stationary
        }

        // 3. Adaptive Curvature & Time Gating
        val bearingDelta = if (rawFix.bearingDeg != null && last.bearingDeg != null) {
            abs(NavigationMath.bearingDiff(last.bearingDeg, rawFix.bearingDeg))
        } else 0f

        val isSharpTurn = distance >= 3.0 && bearingDelta >= curveBearingDeltaDeg
        val isDistanceMoved = distance >= minRecordDisplacementMeters
        val isTimeElapsedMoving = timeDeltaSec >= maxTimeGapSeconds && distance >= 2.0

        if (!isSharpTurn && !isDistanceMoved && !isTimeElapsedMoving) {
            return FilterResult.SkipIntermediate
        }

        // 4. Altitude EMA Smoothing and Vertical Jump Clamping
        val filteredAlt = if (rawAlt != null) {
            if (smoothedAltitude.isNaN()) {
                smoothedAltitude = rawAlt
                rawAlt
            } else {
                val verticalSpeed = abs(rawAlt - smoothedAltitude) / timeDeltaSec
                if (verticalSpeed > maxVerticalSpeedMps) {
                    // Clamp unrealistic vertical jump
                    smoothedAltitude
                } else {
                    val nextAlt = 0.2 * rawAlt + 0.8 * smoothedAltitude
                    smoothedAltitude = nextAlt
                    nextAlt
                }
            }
        } else {
            if (smoothedAltitude.isNaN()) null else smoothedAltitude
        }

        val accepted = RecordedPoint(
            latitude = rawFix.latitude,
            longitude = rawFix.longitude,
            altitude = filteredAlt,
            speedMps = speed,
            bearingDeg = rawFix.bearingDeg ?: last.bearingDeg,
            timestampMillis = rawFix.timestampMillis,
            distanceDeltaMeters = distance
        )
        lastAcceptedPoint = accepted
        return FilterResult.Accept(accepted)
    }
}
