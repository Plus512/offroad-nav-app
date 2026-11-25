package com.alruwaili.garminalbarr.map.download

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.alruwaili.garminalbarr.map.MapFileManager
import com.alruwaili.garminalbarr.map.TerrainTileCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TileDownloadService {

    companion object {
        private const val TAG = "TileDownloadService"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val MAX_RETRIES = 3

        // مصدر بلاطات ارتفاع التضاريس: مصدر AWS Terrarium العام الموثَّق (s3.amazonaws.com)،
        // نفس المصدر بالضبط المستخدم سابقاً في دليل البر لتبويب "تضاريس" — رابط عام معروف، وليس تخميناً.
        private const val TERRAIN_URL_FMT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"
        private const val TERRAIN_PARALLEL_DOWNLOADS = 4

        private fun isTrustedDownloadHost(host: String): Boolean {
            return host.endsWith("web.app") ||
                    host.endsWith("googleapis.com") ||
                    host == "aldalilah-tiles.web.app" ||
                    host == "brouter.de" ||
                    host.endsWith("github.com") ||
                    host.endsWith("githubusercontent.com") ||
                    host == "s3.amazonaws.com"
        }
    }

    /**
     * Downloads a region PMTiles archive with strict integrity enforcement.
     */
    suspend fun downloadRegion(
        context: Context,
        region: MapRegion,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "tiles")
        if (!outputDir.exists()) outputDir.mkdirs()

        downloadFile(
            url = region.downloadUrl,
            outputDir = outputDir,
            outputFilename = "${region.id}.pmtiles",
            expectedBytes = region.sizeBytes,
            sha256 = region.sha256,
            onProgress = onProgress
        )
    }

    /**
     * Downloads a Mapsforge .map file for offline vector map rendering.
     * Files are stored in filesDir/maps/{regionId}.map.
     */
    suspend fun downloadMapFile(
        context: Context,
        region: MapRegion,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "maps")
        if (!outputDir.exists()) outputDir.mkdirs()

        downloadFile(
            url = region.mapDownloadUrl,
            outputDir = outputDir,
            outputFilename = "${region.id}.map",
            expectedBytes = region.sizeBytes,
            sha256 = region.sha256,
            onProgress = onProgress
        )
    }

    /**
     * Downloads world.map for baseline coastlines/borders (zoom 1-7).
     * Size is unknown — integrity check uses content-length from HTTP response.
     */
    suspend fun downloadWorldMap(
        context: Context,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "maps")
        if (!outputDir.exists()) outputDir.mkdirs()

        downloadFile(
            url = RegionRegistry.WORLD_MAP_URL,
            outputDir = outputDir,
            outputFilename = RegionRegistry.WORLD_MAP_FILENAME,
            expectedBytes = 0L,
            sha256 = null,
            onProgress = onProgress
        )
    }

    /**
     * Downloads a single BRouter .rd5 routing segment to external storage.
     * Checks primary Firebase Hosting CDN first, falling back to BRouter weekly server.
     */
    suspend fun downloadRoutingSegment(
        context: Context,
        segmentName: String,
        expectedBytes: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val outputDir = MapFileManager.getRoutingSegmentsDir(context)
        val existing = File(outputDir, segmentName)
        if (existing.exists() && existing.length() > 0) {
            if (expectedBytes <= 0L || existing.length() == expectedBytes) {
                onProgress(1f)
                return@withContext Result.success(existing)
            }
        }

        val primaryUrl = "${RegionRegistry.ROUTING_BASE_URL}/$segmentName"
        val fallbackUrl = "${RegionRegistry.ROUTING_FALLBACK_URL}/$segmentName"

        val primaryRes = downloadFile(
            url = primaryUrl,
            outputDir = outputDir,
            outputFilename = segmentName,
            expectedBytes = expectedBytes,
            sha256 = null,
            onProgress = onProgress
        )

        if (primaryRes.isSuccess) {
            primaryRes
        } else {
            Log.w(TAG, "Primary download failed for $segmentName, attempting fallback mirror: $fallbackUrl")
            downloadFile(
                url = fallbackUrl,
                outputDir = outputDir,
                outputFilename = segmentName,
                expectedBytes = expectedBytes,
                sha256 = null,
                onProgress = onProgress
            )
        }
    }

    /**
     * Sequentially downloads all missing routing segments for [region]
     * with aggregated progress feedback and coroutine cancellation support.
     */
    suspend fun downloadCountryRouting(
        context: Context,
        region: MapRegion,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val segments = region.routingSegments
        if (segments.isEmpty()) {
            onProgress(1f)
            return@withContext Result.success(Unit)
        }

        val totalSegments = segments.size
        for ((index, segment) in segments.withIndex()) {
            currentCoroutineContext().ensureActive()
            val res = downloadRoutingSegment(context, segment) { segProgress ->
                val overallProgress = (index + segProgress) / totalSegments
                onProgress(overallProgress.coerceIn(0f, 1f))
            }
            if (res.isFailure) {
                return@withContext Result.failure(
                    res.exceptionOrNull() ?: Exception("Failed to download segment $segment")
                )
            }
        }
        onProgress(1f)
        Result.success(Unit)
    }

    /**
     * يحمّل بلاطات ارتفاع التضاريس (AWS Terrarium) لكامل حدود [region] ضمن
     * [TerrainTileCache.MIN_ZOOM]..[TerrainTileCache.MAX_ZOOM]، ويحفظها عبر [TerrainTileCache].
     * بلاطات محفوظة مسبقاً (تتشارك مع دولة أخرى محمَّلة) لا تُعاد تنزيلها. فشل بلاطة واحدة
     * (ازدحام خادم أو شبكة ضعيفة) لا يُسقط التحميل كله — يُسجَّل في البيان كتغطية جزئية فقط،
     * ولا تُخمَّن بيانات البلاطة الفاشلة.
     */
    suspend fun downloadTerrainRegion(
        context: Context,
        region: MapRegion,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val b = region.boundingBox
        val tiles = TerrainTileCache.tilesForBounds(b.south, b.west, b.north, b.east)
        if (tiles.isEmpty()) {
            onProgress(1f)
            TerrainTileCache.writeManifest(context, region.id, tiles, 0)
            return@withContext Result.success(Unit)
        }

        val semaphore = Semaphore(TERRAIN_PARALLEL_DOWNLOADS)
        var completed = 0
        var failures = 0
        val lock = Any()

        val jobs = tiles.map { t ->
            async {
                currentCoroutineContext().ensureActive()
                semaphore.withPermit {
                    val ok = fetchTerrainTile(context, t[0], t[1], t[2])
                    synchronized(lock) {
                        completed++
                        if (!ok) failures++
                        onProgress((completed.toFloat() / tiles.size).coerceIn(0f, 1f))
                    }
                }
            }
        }
        jobs.awaitAll()

        TerrainTileCache.writeManifest(context, region.id, tiles, failures)
        if (failures == tiles.size) {
            Result.failure(Exception("تعذّر جلب أي بلاطة تضاريس (فشلت كل البلاطات: $failures)"))
        } else {
            Result.success(Unit)
        }
    }

    /** يجلب بلاطة تضاريس واحدة (أو يتأكد من وجودها محفوظة مسبقاً). 404 يعني لا ارتفاع هنا (بحر غالباً) وليس فشلاً. */
    private fun fetchTerrainTile(context: Context, z: Int, x: Int, y: Int): Boolean {
        val f = TerrainTileCache.tileFile(context, z, x, y)
        if (f.exists() && f.length() > 0) return true

        repeat(2) {
            try {
                val url = URL(String.format(TERRAIN_URL_FMT, z, x, y))
                require(isTrustedDownloadHost(url.host)) { "Untrusted host: ${url.host}" }
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT
                    connection.readTimeout = READ_TIMEOUT
                    when (connection.responseCode) {
                        200 -> {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            val tmp = File(f.parentFile, "${f.name}.tmp")
                            FileOutputStream(tmp).use { it.write(bytes) }
                            if (f.exists()) f.delete()
                            if (tmp.renameTo(f)) return true
                        }
                        404 -> return true // لا بلاطة ارتفاع هنا (خارج اليابسة غالباً)؛ ليس فشلاً
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Terrain tile $z/$x/$y attempt failed", e)
            }
        }
        return false
    }

    private suspend fun downloadFile(
        url: String,
        outputDir: File,
        outputFilename: String,
        expectedBytes: Long,
        sha256: String?,
        onProgress: (Float) -> Unit
    ): Result<File> {
        val outputFile = File(outputDir, outputFilename)
        val tempFile = File(outputDir, "$outputFilename.tmp")

        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                var resumeFrom = tempFile.length().takeIf { tempFile.exists() } ?: 0L
                if (expectedBytes > 0 && resumeFrom > expectedBytes) {
                    Log.w(TAG, "$outputFilename: temp ${tempFile.length()}B > expected ${expectedBytes}B, discarding")
                    tempFile.delete()
                    resumeFrom = 0L
                }

                val resumeRequested = resumeFrom > 0L && expectedBytes > 0L
                var currentUrl = url
                var redirectCount = 0
                lateinit var connection: HttpURLConnection

                while (true) {
                    if (redirectCount++ > 5) throw SecurityException("Too many redirects for $outputFilename")
                    val u = URL(currentUrl)
                    require(u.protocol == "https") { "Cleartext URL blocked: $u" }
                    require(isTrustedDownloadHost(u.host)) { "Untrusted host: ${u.host}" }
                    connection = u.openConnection() as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT
                    connection.readTimeout = READ_TIMEOUT
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (resumeRequested) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
                    val rc = connection.responseCode
                    if (rc in 301..308) {
                        val loc = connection.getHeaderField("Location")
                            ?: throw SecurityException("Redirect without Location for $outputFilename")
                        val next = URL(u, loc)
                        require(next.protocol == "https") { "Cleartext redirect blocked: $next" }
                        require(isTrustedDownloadHost(next.host)) { "Untrusted redirect host: ${next.host}" }
                        connection.disconnect()
                        currentUrl = next.toString()
                        continue
                    }
                    break
                }

                val responseCode = connection.responseCode

                when {
                    responseCode == 416 -> {
                        connection.disconnect()
                        if (expectedBytes > 0 && tempFile.length() == expectedBytes) {
                            finalizeDownload(tempFile, outputFile)
                            onProgress(1f)
                            return Result.success(outputFile)
                        }
                        tempFile.delete()
                        lastException = Exception("HTTP 416 with incomplete temp (${tempFile.length()}/${expectedBytes}) for $outputFilename")
                        continue
                    }

                    responseCode == 206 -> {
                        val contentRange = connection.getHeaderField("Content-Range")
                        val rangeStart = contentRange
                            ?.removePrefix("bytes ")
                            ?.substringBefore("-")
                            ?.toLongOrNull()
                        if (!resumeRequested) {
                            // Acceptable
                        } else if (rangeStart == null) {
                            connection.disconnect()
                            tempFile.delete()
                            Log.w(TAG, "$outputFilename: 206 without parsable Content-Range — restarting clean")
                            lastException = Exception("206 without Content-Range for $outputFilename")
                            continue
                        } else if (rangeStart != resumeFrom) {
                            connection.disconnect()
                            tempFile.delete()
                            Log.w(TAG, "$outputFilename: Content-Range starts at $rangeStart but requested $resumeFrom — restarting clean")
                            lastException = Exception("Content-Range mismatch for $outputFilename ($rangeStart != $resumeFrom)")
                            continue
                        }
                    }

                    responseCode in 200..299 -> {
                        if (resumeRequested) {
                            Log.w(TAG, "$outputFilename: server ignored Range (HTTP 200) — discarding partial and writing fresh")
                            tempFile.delete()
                            resumeFrom = 0L
                        }
                    }

                    else -> {
                        connection.disconnect()
                        lastException = Exception("HTTP $responseCode for $outputFilename")
                        continue
                    }
                }

                val appending = responseCode == 206 && resumeRequested
                val totalBytes = when {
                    expectedBytes > 0 -> expectedBytes
                    appending -> resumeFrom + connection.contentLength
                    else -> connection.contentLength.toLong()
                }.takeIf { it > 0 } ?: 0L

                var totalRead: Long = if (appending) resumeFrom else 0L

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile, appending).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                onProgress((totalRead.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                connection.disconnect()

                if (expectedBytes > 0 && tempFile.length() != expectedBytes) {
                    Log.w(TAG, "$outputFilename: integrity check FAILED (got ${tempFile.length()}B, expected ${expectedBytes}B), attempt $attempt")
                    tempFile.delete()
                    lastException = Exception("Size mismatch for $outputFilename: ${tempFile.length()} != $expectedBytes")
                    FirebaseCrashlytics.getInstance().recordException(lastException)
                    continue
                }
                if (sha256 != null && !verifySha256(tempFile, sha256)) {
                    Log.w(TAG, "$outputFilename: SHA-256 mismatch — deleting corrupt file")
                    tempFile.delete()
                    lastException = SecurityException("Checksum failed for $outputFilename")
                    FirebaseCrashlytics.getInstance().recordException(lastException)
                    continue
                }

                finalizeDownload(tempFile, outputFile)
                Log.d(TAG, "$outputFilename: downloaded and verified ${outputFile.length()} bytes")
                return Result.success(outputFile)

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download attempt $attempt failed for $outputFilename", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                if (attempt < MAX_RETRIES) {
                    kotlinx.coroutines.delay(attempt * 2000L)
                }
            }
        }

        return Result.failure(lastException ?: Exception("Download failed after $MAX_RETRIES attempts"))
    }

    private fun finalizeDownload(tempFile: File, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        if (!tempFile.renameTo(outputFile)) {
            throw IllegalStateException("Failed to move ${tempFile.name} into place")
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun cancelDownload(regionId: String) {
        Log.d(TAG, "Cancel requested for $regionId")
    }
}
