# Add project specific ProGuard rules here.

# JNI: the native bridge is found by class name, and the app never minifies today - keep it that way.
-keep class com.romirmile.hermes.data.ChatterboxNative { *; }
