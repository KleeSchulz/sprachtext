package de.sprachtext.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Dekodiert eine beliebige Audiodatei (Opus/OGG, M4A, MP3, WAV …) zu 16 kHz Mono-Float. */
final class AudioDecoder {

    static float[] decodeTo16kMono(String path) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("Keine Audiospur gefunden"); }
        ex.selectTrack(track);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();

        int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        FloatList mono = new FloatList(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inEos = false, outEos = false;
        try {
            while (!outEos) {
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int ch = Math.max(1, channels);
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = le.asFloatBuffer();
                            int frames = fb.remaining() / ch;
                            for (int f = 0; f < frames; f++) {
                                float s = 0;
                                for (int c = 0; c < ch; c++) s += fb.get();
                                mono.add(s / ch);
                            }
                        } else {
                            ShortBuffer sb = le.asShortBuffer();
                            int frames = sb.remaining() / ch;
                            for (int f = 0; f < frames; f++) {
                                float s = 0;
                                for (int c = 0; c < ch; c++) s += sb.get() / 32768f;
                                mono.add(s / ch);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
            }
        } finally {
            codec.stop();
            codec.release();
            ex.release();
        }
        return resample(mono.toArray(), sampleRate, 16000);
    }

    /** Herunterrechnen per Mittelwert über das Fenster (einfacher Tiefpass), Hochrechnen linear. */
    static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        double ratio = (double) from / to;
        int outLen = (int) (in.length / ratio);
        float[] out = new float[outLen];
        if (ratio > 1) {
            for (int i = 0; i < outLen; i++) {
                int a = (int) (i * ratio), b = Math.min(in.length, (int) ((i + 1) * ratio));
                if (b <= a) b = Math.min(in.length, a + 1);
                float s = 0;
                for (int k = a; k < b; k++) s += in[k];
                out[i] = s / (b - a);
            }
        } else {
            for (int i = 0; i < outLen; i++) {
                double pos = i * ratio;
                int a = (int) pos;
                int b = Math.min(in.length - 1, a + 1);
                float t = (float) (pos - a);
                out[i] = in[a] * (1 - t) + in[b] * t;
            }
        }
        return out;
    }

    private static final class FloatList {
        float[] a; int n;
        FloatList(int cap) { a = new float[cap]; }
        void add(float v) {
            if (n == a.length) { float[] b = new float[a.length * 2]; System.arraycopy(a, 0, b, 0, n); a = b; }
            a[n++] = v;
        }
        float[] toArray() { float[] r = new float[n]; System.arraycopy(a, 0, r, 0, n); return r; }
    }

    private AudioDecoder() { }
}
