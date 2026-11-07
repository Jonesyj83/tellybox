# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Retrofit service interfaces
-keep,allowobfuscation interface com.opentelly.tv.data.parser.xtream.XtreamService
-keepattributes Signature, Exceptions
