# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class ai.openclaw.imapp.data.model.** { *; }
-keep class * implements ai.openclaw.imapp.data.model.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Exceptions
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Hilt
-dontwarn dagger.hilt.**
