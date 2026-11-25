package com.alruwaili.garminalbarr

import android.content.Context
import java.io.*

object LocationsCacheManager {
    private const val CACHE_FILE_NAME = "locations_cache.bin"

    /**
     * Checks if the cache file exists and is not empty.
     */
    fun isCacheAvailable(context: Context): Boolean {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return file.exists() && file.length() > 0
    }

    /**
     * Saves the list of LocationLightweight to a fast-read binary file.
     */
    fun saveCache(context: Context, locations: List<LocationLightweight>): Boolean {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        val tempFile = File(context.filesDir, "${CACHE_FILE_NAME}.tmp")
        return try {
            FileOutputStream(tempFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    DataOutputStream(bos).use { dos ->
                        dos.writeInt(locations.size)
                        for (loc in locations) {
                            dos.writeInt(loc.id)
                            dos.writeBoolean(loc.isFav ?: false)
                            
                            val hasLat = loc.latitude != null
                            dos.writeBoolean(hasLat)
                            if (hasLat) {
                                dos.writeDouble(loc.latitude)
                            }
                            
                            val hasLon = loc.longitude != null
                            dos.writeBoolean(hasLon)
                            if (hasLon) {
                                dos.writeDouble(loc.longitude)
                            }
                            
                            val hasTitle = loc.title != null
                            dos.writeBoolean(hasTitle)
                            if (hasTitle) {
                                dos.writeUTF(loc.title)
                            }
                        }
                    }
                }
            }
            if (tempFile.renameTo(file)) {
                true
            } else {
                if (file.exists() && file.delete()) {
                    tempFile.renameTo(file)
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
            false
        }
    }

    /**
     * Reads the list of LocationLightweight from the binary cache file.
     */
    fun readCache(context: Context): List<LocationLightweight>? {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    DataInputStream(bis).use { dis ->
                        val size = dis.readInt()
                        val list = ArrayList<LocationLightweight>(size)
                        for (i in 0 until size) {
                            val id = dis.readInt()
                            val isFav = dis.readBoolean()
                            
                            val hasLat = dis.readBoolean()
                            val latitude = if (hasLat) dis.readDouble() else null
                            
                            val hasLon = dis.readBoolean()
                            val longitude = if (hasLon) dis.readDouble() else null
                            
                            val hasTitle = dis.readBoolean()
                            val title = if (hasTitle) dis.readUTF() else null
                            
                            list.add(LocationLightweight(id, isFav, latitude, longitude, title))
                        }
                        list
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Clears the cache by deleting the file.
     */
    fun clearCache(context: Context) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }
}
