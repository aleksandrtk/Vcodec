# Proguard rules for Smart Encoder
# Add custom keep rules here if needed for native NDK / JNI obfuscation support.
-keep class com.vcodec.smartencoder.metadata.MetadataRestorer {
    private external boolean copyCustomMetadataBoxesFd(int, int);
}
