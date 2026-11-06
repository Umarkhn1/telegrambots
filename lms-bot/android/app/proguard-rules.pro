# Общий слой LMS (Java): Jackson и jsoup работают через рефлексию по моделям.
-keep class uz.tuit.lmsbot.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RecordComponents

# Jackson
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.yaml.snakeyaml.**
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn java.beans.**

# OkHttp / jsoup / Lombok
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# kotlinx.serialization — кэш на диске и данные виджетов
-keepattributes RuntimeVisibleAnnotations
-keep,includedescriptorclasses class uz.tuit.lms.**$$serializer { *; }
-keepclassmembers class uz.tuit.lms.** { *** Companion; }
-keepclasseswithmembers class uz.tuit.lms.** { kotlinx.serialization.KSerializer serializer(...); }
