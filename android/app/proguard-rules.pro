# JNI: the native engine calls back into these by name.
-keep class app.dibe.android.engine.NativeEngine { *; }
-keep interface app.dibe.android.engine.NativeCallbacks { *; }
-keepclassmembers class * implements app.dibe.android.engine.NativeCallbacks { *; }
