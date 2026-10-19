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

# ------------------------------------------------------------------ PdfBox（文字层提取）
# PdfBox 通过反射加载字体/CMap/滤镜实现，整包保留最省事；
# 只用于读 PDF 文字，不参与渲染。
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**
