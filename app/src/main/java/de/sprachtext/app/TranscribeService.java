package de.sprachtext.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Vordergrunddienst: lädt bei Bedarf das Modell, dekodiert die Audiodatei und
 * transkribiert nativ mit whisper.cpp – unabhängig davon, ob die App sichtbar ist.
 */
public class TranscribeService extends Service {

    static final String EXTRA_FILE = "file";
    static final String EXTRA_NAME = "name";
    static final String ACTION_CANCEL = "de.sprachtext.app.CANCEL";

    static final String CHANNEL_WORK = "work";
    static final String CHANNEL_DONE = "done";
    static final int ID_WORK = 1;
    static final int ID_DONE = 2;

    /** Aktueller Zustand – wird von der Oberfläche angezeigt. */
    static final class State {
        volatile boolean running;
        volatile String status = "";
        volatile int percent = -1;          // -1 = unbestimmt
        volatile String meta = "";
        volatile String partial = "";       // bereits erkannter Text (live)
        volatile String result;             // fertiges Ergebnis
        volatile boolean error;
        volatile long version;              // zählt bei jeder Änderung hoch
    }

    interface Listener { void onStateChanged(); }

    static final State STATE = new State();
    static volatile Listener listener;
    static volatile boolean uiVisible;

    // Geladenes Modell bleibt zwischen Aufträgen im Speicher, solange der Prozess lebt
    private static long ctx;
    private static String ctxModel;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private NotificationManager nm;
    private long lastNotify;

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_WORK, "Transkription läuft", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Transkript fertig", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void changed() {
        STATE.version++;
        Listener l = listener;
        if (l != null) MAIN.post(l::onStateChanged);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        nm = getSystemService(NotificationManager.class);
        createChannels(this);

        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Whisper.cancel();
            return START_NOT_STICKY;
        }

        startInForeground("Bereite vor …", -1);
        if (intent == null || STATE.running) {
            if (!STATE.running) stopSelf();
            return START_NOT_STICKY;
        }

        final String file = intent.getStringExtra(EXTRA_FILE);
        final String name = intent.getStringExtra(EXTRA_NAME);
        STATE.running = true;
        STATE.error = false;
        STATE.result = null;
        STATE.partial = "";
        STATE.meta = name == null ? "" : name;
        changed();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sprachtext:transcribe");
        wakeLock.acquire(60 * 60 * 1000L);

        Thread t = new Thread(() -> run(file, name == null ? "Sprachnachricht" : name), "whisper");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return START_NOT_STICKY;
    }

    private void run(String file, String name) {
        long t0 = System.currentTimeMillis();
        try {
            final String model = Store.model(this);
            final String lang = Store.lang(this);

            // 1) Modell (einmalig) herunterladen
            if (!Models.isDownloaded(this, model)) {
                setStatus("Modell wird geladen (nur beim ersten Mal) …", 0, "");
                Models.ensure(this, model, (loaded, total) -> {
                    int pc = total > 0 ? (int) (loaded * 100 / total) : -1;
                    setStatus("Modell wird geladen (nur beim ersten Mal) …", pc,
                            mb(loaded) + (total > 0 ? " / " + mb(total) : "") + " MB");
                });
            }

            // 2) Audio dekodieren
            setStatus("Audio wird gelesen …", -1, name);
            float[] audio = AudioDecoder.decodeTo16kMono(file);
            final double audioSecs = audio.length / 16000.0;
            final String metaBase = name + " · " + dur(audioSecs);
            if (audio.length < 1600) throw new Exception("Audiodatei ist leer oder zu kurz");

            // 3) Modell in den Speicher laden (nur bei Wechsel)
            if (ctx == 0 || !model.equals(ctxModel)) {
                setStatus("Modell wird vorbereitet …", -1, metaBase);
                if (ctx != 0) { Whisper.free(ctx); ctx = 0; ctxModel = null; }
                ctx = Whisper.init(Models.file(this, model).getPath());
                if (ctx == 0) {
                    Models.file(this, model).delete(); // vermutlich beschädigt → beim nächsten Mal neu laden
                    throw new Exception("Modell konnte nicht geladen werden, bitte erneut versuchen");
                }
                ctxModel = model;
            }

            // 4) Transkribieren
            final int threads = threads();
            final long tStart = System.currentTimeMillis();
            setStatus("Transkribiere (" + threads + " Kerne) …", 0, metaBase);
            final StringBuilder live = new StringBuilder();
            byte[] out = Whisper.transcribe(ctx, audio, lang, threads, new Whisper.Listener() {
                @Override public void onProgress(int percent) {
                    setStatus("Transkribiere (" + threads + " Kerne) …", percent, metaBase);
                }
                @Override public void onSegment(byte[] utf8Text) {
                    live.append(new String(utf8Text, StandardCharsets.UTF_8));
                    STATE.partial = live.toString().trim();
                    changed();
                }
            });
            if (out == null) throw new CancelledException();

            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) text = "(kein Text erkannt)";
            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            double whisperSecs = (System.currentTimeMillis() - tStart) / 1000.0;
            Store.addHistory(this, text, secs, audioSecs);
            STATE.result = text;
            STATE.partial = "";
            STATE.status = "Fertig";
            STATE.percent = 100;
            STATE.meta = String.format(Locale.GERMANY, "%s · %.1f s (Erkennung %.1f s) · %d Kerne", metaBase, secs, whisperSecs, threads);
            finish(true, text);
        } catch (CancelledException e) {
            STATE.status = "Abgebrochen";
            STATE.percent = 0;
            STATE.error = true;
            finish(false, null);
        } catch (Throwable e) {
            STATE.status = "Fehler: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            STATE.percent = 0;
            STATE.error = true;
            finish(false, STATE.status);
        } finally {
            new File(file).delete();
        }
    }

    private void finish(boolean ok, String text) {
        STATE.running = false;
        changed();
        MAIN.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (!uiVisible && text != null) {
                Notification n = new Notification.Builder(this, CHANNEL_DONE)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle(ok ? "Transkript fertig" : "Transkription fehlgeschlagen")
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setContentIntent(openApp(this))
                        .build();
                try { nm.notify(ID_DONE, n); } catch (Exception ignored) { }
            }
            stopSelf();
        });
    }

    private void setStatus(String status, int percent, String meta) {
        STATE.status = status;
        STATE.percent = percent;
        STATE.meta = meta;
        changed();
        long now = System.currentTimeMillis();
        if (now - lastNotify > 1000) { // Benachrichtigung höchstens 1×/s aktualisieren
            lastNotify = now;
            MAIN.post(() -> startInForeground(status, percent));
        }
    }

    private void startInForeground(String text, int percent) {
        Intent cancel = new Intent(this, TranscribeService.class).setAction(ACTION_CANCEL);
        PendingIntent pc = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_WORK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sprachtext")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .setContentIntent(openApp(this))
                .addAction(new Notification.Action.Builder(null, "Abbrechen", pc).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_WORK, n);
    }

    /** Anzahl Rechen-Threads: höchstens 4 (große Kerne), mindestens 1. */
    static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, n >= 8 ? 4 : n - (n > 4 ? 1 : 0)));
    }

    private static String mb(long b) { return String.valueOf(b / 1048576); }

    static String dur(double s) {
        int m = (int) (s / 60), sec = (int) Math.round(s % 60);
        if (sec == 60) { m++; sec = 0; }
        return String.format(Locale.GERMANY, "%d:%02d min", m, sec);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static final class CancelledException extends Exception { }
}
