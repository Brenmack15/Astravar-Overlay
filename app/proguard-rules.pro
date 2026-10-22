-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class com.yazsras.astravar.rules.**$$serializer { *; }

# Pushy logs its installation token even with payload logging disabled. Remove SDK logs
# from the owner release so private registration tokens cannot enter Android logcat.
-assumenosideeffects class me.pushy.sdk.util.PushyLogger {
    public static void d(java.lang.String);
    public static void d(java.lang.String, java.lang.Exception);
    public static void e(java.lang.String);
    public static void e(java.lang.String, java.lang.Exception);
}

# Pushy includes an optional upstream FCM adapter. Astravar disables it with
# toggleFCM(false) and does not bundle Firebase or Play services Tasks.
# Suppress only the three absent optional types reported by R8 for SDK 1.0.129.
-dontwarn com.google.android.gms.tasks.OnCompleteListener
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.firebase.messaging.FirebaseMessaging
