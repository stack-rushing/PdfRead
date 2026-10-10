# ============ Room / KSP ============
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ============ kotlinx.serialization ============
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.novelreader.pdf.**$$serializer { *; }
-keepclassmembers class com.novelreader.pdf.** {
    *** Companion;
}
-keepclasseswithmembers class com.novelreader.pdf.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============ 排错用 ============
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
