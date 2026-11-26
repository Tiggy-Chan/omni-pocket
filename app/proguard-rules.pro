# Omni Pocket Release R8 / ProGuard Rules

# Preserve readable stack traces.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep all native JNI methods and their declaring classes.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI classes instantiated from C++ via FindClass / GetMethodID("<init>", ...).
-keep class com.tiggy.qwenimage.engine.NativeDecisionResult { *; }
-keep class com.tiggy.qwenimage.engine.NativeLlmResult { *; }
-keep class com.tiggy.qwenimage.engine.GgufBackendCapabilities { *; }

# JNI engine holders & callback interfaces invoked from native C++ via GetMethodID.
-keep class com.tiggy.qwenimage.engine.LocalDecisionEngine { *; }
-keep class com.tiggy.qwenimage.engine.LocalLlamaChatEngine { *; }
-keep class com.tiggy.qwenimage.engine.LocalLlamaEmbeddingEngine { *; }
-keep class com.tiggy.qwenimage.engine.LocalLlmEngine { *; }
-keep class com.tiggy.qwenimage.engine.NativeEngineBridge { *; }

-keep interface com.tiggy.qwenimage.engine.LlmStreamCallback { *; }
-keep class * implements com.tiggy.qwenimage.engine.LlmStreamCallback {
    boolean onToken(java.lang.String);
}

-keep interface com.tiggy.qwenimage.engine.DecisionProgressCallback { *; }
-keep class * implements com.tiggy.qwenimage.engine.DecisionProgressCallback {
    boolean onProgress(int, int);
}

-keep interface com.tiggy.qwenimage.engine.LogByteCallback { *; }
-keep class * implements com.tiggy.qwenimage.engine.LogByteCallback {
    void onBytes(byte[]);
}

# Qwen-Image 2.1 native library & JNI progress callback.
-keep class com.scsonic.qwenimage21.** { *; }
-keep class * implements com.scsonic.qwenimage21.QwenImage21$ProgressListener {
    void onProgress(int);
}

# Preserve enum valueOf() / values() used for SharedPreferences & JSON persistence.
-keepclassmembers enum com.tiggy.qwenimage.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
