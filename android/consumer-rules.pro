# Firebase Messaging is optional: FirebaseTokens.kt reaches it by name, so R8
# must keep these names in the host app (rules are no-ops without Firebase).
-keep class com.google.firebase.messaging.FirebaseMessaging {
    public static com.google.firebase.messaging.FirebaseMessaging getInstance();
    public com.google.android.gms.tasks.Task getToken();
    public com.google.android.gms.tasks.Task deleteToken();
}

# Task members read reflectively by FirebaseTokens.kt; the concrete task class
# may be obfuscated, but the overrides must keep these names.
-keep class com.google.android.gms.tasks.Task {
    public boolean isComplete();
    public boolean isSuccessful();
    public java.lang.Object getResult();
    public java.lang.Exception getException();
}
-keepclassmembers class * extends com.google.android.gms.tasks.Task {
    public boolean isComplete();
    public boolean isSuccessful();
    public java.lang.Object getResult();
    public java.lang.Exception getException();
}
