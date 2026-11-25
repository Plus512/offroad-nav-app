package com.alruwaili.garminalbarr.legacymap.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.util.UUID

/**
 * تخزين بسيط وموثوق للمواقع التي يحفظها المستخدم يدوياً من الخريطة (ليس من قاعدة locations.db
 * الأصلية)، في SharedPreferences كمصفوفة JSON — لا تحتاج قاعدة بيانات منفصلة لعدد قليل من العناصر
 * التي يحفظها المستخدم بنفسه، وتُقرأ/تُكتب بالكامل في الذاكرة في كل مرة (حجمها لن يكبر لآلاف الصفوف
 * كموقع المستخدم يحفظها يدوياً واحداً واحداً).
 */
class SavedPlacesStore(context: Context) {

    data class SavedPlace(
        val id: String,
        val name: String,
        val latLng: LatLng,
        var visible: Boolean,
        var favorite: Boolean = false
    )

    private val prefs = context.getSharedPreferences("dalilalbar_saved_places", Context.MODE_PRIVATE)
    private val key = "places_json"

    fun list(): List<SavedPlace> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<SavedPlace>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                SavedPlace(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    latLng = LatLng(o.getDouble("lat"), o.getDouble("lon")),
                    visible = o.optBoolean("visible", true),
                    favorite = o.optBoolean("favorite", false)
                )
            )
        }
        return out
    }

    fun add(name: String, latLng: LatLng): SavedPlace {
        val place = SavedPlace(UUID.randomUUID().toString(), name.ifBlank { "موقع محفوظ" }, latLng, true)
        val all = list().toMutableList()
        all.add(place)
        persist(all)
        return place
    }

    fun setVisible(id: String, visible: Boolean) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(visible = visible)
            persist(all)
        }
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
    }

    /** يبدّل حالة "المفضّلة" (أيقونة القلب) لموقع محفوظ، كما في شاشة المحفوظات المرجعية. */
    fun toggleFavorite(id: String) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(favorite = !all[idx].favorite)
            persist(all)
        }
    }

    private fun persist(all: List<SavedPlace>) {
        val arr = JSONArray()
        for (p in all) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("lat", p.latLng.latitude)
                    .put("lon", p.latLng.longitude)
                    .put("visible", p.visible)
                    .put("favorite", p.favorite)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
