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
