# ═══════════════════════════════════════════════════════════════════════════════
# Drift - ProGuard / R8 Rules
# ═══════════════════════════════════════════════════════════════════════════════

# ─── Debugging ────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations

# Don't warn about Kotlin internal stuff
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ─── Room Database ────────────────────────────────────────────────────────────
# Keep all Room entities, DAOs, and DB classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep our specific Room classes
-keep class com.kousoyu.drift.data.local.** { *; }

# ─── Data Models ──────────────────────────────────────────────────────────────
# Keep all data classes and sealed classes used in the app
-keep class com.kousoyu.drift.data.Manga { *; }
-keep class com.kousoyu.drift.data.MangaChapter { *; }
-keep class com.kousoyu.drift.data.MangaDetail { *; }
-keep class com.kousoyu.drift.data.MangaSource { *; }
-keep class com.kousoyu.drift.data.AuthManager$* { *; }
-keep class com.kousoyu.drift.data.DriftUser { *; }

# Keep all source implementations
-keep class com.kousoyu.drift.data.sources.** { *; }

# Keep UpdateManager and its inner classes
-keep class com.kousoyu.drift.data.UpdateManager { *; }
-keep class com.kousoyu.drift.data.UpdateManager$* { *; }

# Keep all ViewModel state subclasses
-keep class com.kousoyu.drift.data.MangaListState$* { *; }
-keep class com.kousoyu.drift.data.DetailState$* { *; }
-keep class com.kousoyu.drift.data.ReaderState$* { *; }

# ─── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ─── Jsoup ────────────────────────────────────────────────────────────────────
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ─── Coil ─────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ─── Telephoto (Zoomable) ─────────────────────────────────────────────────────
-keep class me.saket.telephoto.** { *; }
-dontwarn me.saket.telephoto.**

# ─── AndroidX / Jetpack Compose ───────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ─── Coroutines ───────────────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── General Android ──────────────────────────────────────────────────────────
# Keep enum classes (used in sealed classes / when expressions)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable { *; }

# Keep Serializable implementations
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── Supabase + Ktor + kotlinx.serialization ──────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.kousoyu.drift.data.DriftSupabase { *; }