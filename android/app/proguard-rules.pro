# ONNX Runtime uses JNI; keep its classes if minification is ever enabled.
-keep class ai.onnxruntime.** { *; }
