# sherpa-onnx's native code looks up these Kotlin classes, fields and
# constructors by name over JNI. Shrinking or renaming them crashes at runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }
