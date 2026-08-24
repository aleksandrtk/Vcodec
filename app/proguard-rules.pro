# Proguard rules for Smart Encoder
# Keep JNI-registered native methods so R8 does not break the native binding.
-keepclassmembers class com.vcodec.smartencoder.metadata.MetadataRestorer {
    private boolean copyCustomMetadataBoxesFd(int, int, long, long);
    private boolean setFileDescriptorDatesFd(int, long, long);
}
