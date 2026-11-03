# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class de.bahnlive.app.data.** { *** Companion; }
-keepclasseswithmembers class de.bahnlive.app.data.** { kotlinx.serialization.KSerializer serializer(...); }
