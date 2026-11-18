# yt-dlp / FFmpeg para Android (usan reflexión y Jackson para leer la salida de yt-dlp)
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}

# Dependencias opcionales que R8 no encuentra y no se usan en Android
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
