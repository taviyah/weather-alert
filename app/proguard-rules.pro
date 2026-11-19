# kotlinx.serialization keeps its generated serializers on the companion of each @Serializable
# class; R8 cannot see the reflective link from the KSerializer lookup.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.weatheralert.** {
    *** Companion;
}
-keepclasseswithmembers class com.weatheralert.** {
    kotlinx.serialization.KSerializer serializer(...);
}
