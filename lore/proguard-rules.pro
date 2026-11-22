# LORE. The Field code itself needs no rules; the two native libraries do.

# LiteRT-LM: its native code calls back into these classes by name, and it
# builds its messages with Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**

# zstd-jni: the native library reads fields of these classes by name.
-keep class com.github.luben.zstd.** { *; }
