package com.alruwaili.dalilalbar.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * قاعدة بيانات المواقع الخاصة بالمستخدم (locations.db، مُرسَلة منه صريحاً وغير مشفّرة، ٣١٤,٨٥٦
 * موقعاً حقيقياً بأسماء عربية). تُنسخ من assets إلى تخزين التطبيق الداخلي عند أول استخدام (SQLite لا يمكن
 * فتحها مباشرة من داخل ملف APK)، ثم تُستعلم محليّاً بحسب حدود الشاشة المرئية فقط — لا تُحمَّل كل الصفوف
 * (٣١٤ ألف) إلى الذاكرة دفعة واحدة، بل فقط ما يقع داخل مستطيل العرض الحالي، وهذا يحدث فقط بعد أن تتجاوز
 * درجة التكبير حدّ الظهور (انظر LocationLabelStyle) أي أن المنطقة المرئية تكون صغيرة جغرافياً عندها.
 *
 * هيكل الجدول (مؤكَّد بالفحص المباشر للملف الحقيقي المرسَل، لا تخمين):
 *   locations(_id INTEGER, country TEXT, is_fav INTEGER, latitude REAL, longitude REAL, title TEXT, is_manual INTEGER)
 */
class LocationsRepository(private val context: Context) {

    data class LocationRow(val id: Long, val title: String, val latitude: Double, val longitude: Double)

    private var db: SQLiteDatabase? = null

    private fun ensureOpen(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val dest = File(context.filesDir, "locations.db")
        try {
            if (!dest.exists() || dest.length() == 0L) {
                context.assets.open("locations.db").use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val opened = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db = opened
            return opened
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * يرجع المواقع التي تقع ضمن مستطيل الحدود المعطى فقط، مع استبعاد العناوين الفارغة (بعض الصفوف
     * عنوانها فارغ فعلاً كما تبيّن من فحص الملف الحقيقي) وبحدّ أقصى [limit] صفاً لتفادي أي استعلام
     * ضخم غير متوقّع (نادراً ما يُستدعى هذا إلا عند تكبير كافٍ يجعل المنطقة المرئية صغيرة جغرافياً).
     */
    fun queryInBounds(south: Double, west: Double, north: Double, east: Double, limit: Int = 400): List<LocationRow> {
        val database = ensureOpen() ?: return emptyList()
        val result = ArrayList<LocationRow>()
        val cursor = database.rawQuery(
            "SELECT _id, title, latitude, longitude FROM locations " +
                "WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? " +
                "AND title IS NOT NULL AND trim(title) != '' " +
                "LIMIT ?",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    LocationRow(
                        id = it.getLong(0),
                        title = it.getString(1),
                        latitude = it.getDouble(2),
                        longitude = it.getDouble(3)
                    )
                )
            }
        }
        return result
    }
}
