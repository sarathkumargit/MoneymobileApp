# Retrofit / OkHttp
-dontwarn okhttp3.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson models
-keepclassmembers class com.moneymobile.installment.model.** { *; }
-keep class com.moneymobile.installment.model.** { *; }

# Device Admin
-keep class com.moneymobile.installment.admin.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
