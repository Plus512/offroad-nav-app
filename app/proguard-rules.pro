# ════════════════════════════════════════════
# الدليله (CompassApp) — ProGuard/R8 Rules
# ════════════════════════════════════════════

# ── Stack traces ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Google Play Billing ──
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# ── PiracyChecker ──
-keep class com.github.javiersantos.piracychecker.** { *; }
-dontwarn com.github.javiersantos.piracychecker.**

# ── Gson (used for MyLocationsStorage serialization) ──
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Room entities (prevent obfuscation of column names) ──
-keep class com.navigationgps.desert.LocationEntity { *; }
-keep class com.navigationgps.desert.LocationLightweight { *; }
-keep class com.navigationgps.desert.track.TrackEntity { *; }
-keep class com.navigationgps.desert.track.TrackPointEntity { *; }
-keep class com.navigationgps.desert.track.TrackDao { *; }

# ── DownloadStateManager (Gson serialization) ──
-keep class com.navigationgps.desert.map.download.DownloadStateManager$RegionState { *; }
-keep class com.navigationgps.desert.map.download.DownloadState { *; }

# ── osmdroid ──
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── Mapsforge (offline vector map rendering) ──
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-keep class org.osmdroid.mapsforge.** { *; }

# ── BRouter offline routing engine ──
-keep class btools.** { *; }
-dontwarn btools.**

# ── AndroidX Security Crypto ──
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Play Core (In-App Update) ──
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# ── Strip debug and verbose logs in release builds ──
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ── Firebase Firestore ──
-keep class com.google.firebase.firestore.** { *; }
-dontwarn com.google.firebase.firestore.**

# ── App Set ID ──
-keep class com.google.android.gms.appset.** { *; }
-dontwarn com.google.android.gms.appset.**
