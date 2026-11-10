package de.sprachtext.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Whisper-Modelle (ggml, quantisiert) – einmal herunterladen, dann fest im App-Speicher. */
final class Models {

    static final String[] KEYS   = {"tiny", "base", "small", "turbo"};
    static final String[] LABELS = {
            "Tiny – sehr schnell, ungenau (~31 MB)",
            "Base – Standard (~57 MB)",
            "Small – gut für Deutsch (~181 MB)",
            "Large-v3-Turbo – beste Qualität, langsam (~547 MB)"
    };
    private static final String[] FILES = {
            "ggml-tiny-q5_1.bin", "ggml-base-q5_1.bin", "ggml-small-q5_1.bin", "ggml-large-v3-turbo-q5_0.bin"
    };
    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    private static final String VAD_FILE = "ggml-silero-v6.2.0.bin";
    private static final String VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/" + VAD_FILE;

    /** Grobe Startwerte: Rechensekunden pro Sekunde Sprache (wird nach jedem Lauf angepasst). */
    static final double[] DEFAULT_SPEED = {0.08, 0.15, 0.45, 1.5};

    interface Progress { void on(long loaded, long total); }

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 1;
    }

    private static File dir(Context c) {
        File dir = new File(c.getFilesDir(), "models");
        dir.mkdirs();
        return dir;
    }

    static File file(Context c, String key) {
        return new File(dir(c), FILES[index(key)]);
    }

    static File vadFile(Context c) {
        return new File(dir(c), VAD_FILE);
    }

    /** Kleines VAD-Modell (~1 MB) für das Erkennen von Stille. */
    static File ensureVad(Context c) throws Exception {
        return download(VAD_URL, vadFile(c), 100_000, (l, t) -> { });
    }

    static boolean isDownloaded(Context c, String key) {
        return file(c, key).length() > 1_000_000;
    }

    /** Lädt das Modell, falls noch nicht vorhanden. Schreibt erst in .part, dann umbenennen. */
    static File ensure(Context c, String key, Progress progress) throws Exception {
        return download(BASE_URL + FILES[index(key)], file(c, key), 1_000_000, progress);
    }

    private static File download(String url, File target, long minSize, Progress progress) throws Exception {
        if (target.length() > minSize) return target;
        File part = new File(target.getPath() + ".part");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.connect();
        if (con.getResponseCode() != 200) throw new Exception("Download fehlgeschlagen (HTTP " + con.getResponseCode() + ")");
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            long loaded = 0, lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                loaded += n;
                if (loaded - lastReport > 512 * 1024) { progress.on(loaded, total); lastReport = loaded; }
            }
        } finally {
            con.disconnect();
        }
        if (total > 0 && part.length() != total) { part.delete(); throw new Exception("Download unvollständig"); }
        if (!part.renameTo(target)) throw new Exception("Modell konnte nicht gespeichert werden");
        return target;
    }

    private Models() { }
}
