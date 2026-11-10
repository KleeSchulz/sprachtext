package de.sprachtext.app;

/** Dünne Hülle um die native whisper.cpp-Bibliothek. */
final class Whisper {

    static {
        System.loadLibrary("sprachtext");
    }

    interface Listener {
        void onProgress(int percent);
        void onSegment(byte[] utf8Text);
    }

    static native long init(String modelPath);
    static native void free(long ctx);
    static native void cancel();
    static native String systemInfo();
    static native byte[] transcribe(long ctx, float[] audio16kMono, String language, int threads, Listener listener);

    private Whisper() { }
}
