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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vordergrunddienst mit Warteschlange: lädt bei Bedarf die Modelle, dekodiert jede Audiodatei,
 * schneidet Stille heraus und transkribiert nativ mit whisper.cpp – auch wenn die App im Hintergrund ist.
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
        volatile String progress = "";      // "45 % · 0:12 vergangen · noch ca. 0:08"
        volatile String meta = "";          // "Nachricht 2 von 3 · PTT-….opus · 0:45 min"
        volatile String partial = "";       // bereits erkannter Text (live)
        volatile String result;             // fertiges Ergebnis
        volatile boolean error;
        volatile long version;              // zählt bei jeder Änderung hoch
    }

    interface Listener { void onStateChanged(); }

    static final State STATE = new State();
    static volatile Listener listener;
    static volatile boolean uiVisible;

    private static final class Job {
        final String file, name;
        Job(String file, String name) { this.file = file; this.name = name; }
    }

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static volatile boolean cancelAll;

    // Geladene Modelle bleiben zwischen Aufträgen im Speicher, solange der Prozess lebt
    private static long ctx;
    private static String ctxModel;
    private static long vad;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private NotificationManager nm;
    private long lastNotify;
    private Thread worker;

    // Fortschritt der laufenden Phase
    private int batchIndex, batchTotal;
    private long jobStart;

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
            cancelAll = true;
            synchronized (QUEUE) {
                for (Job j : QUEUE) new File(j.file).delete();
                QUEUE.clear();
            }
            Whisper.cancel();
            return START_NOT_STICKY;
        }

        startInForeground(STATE.running ? STATE.status : "Bereite vor …", STATE.running ? STATE.percent : -1);
        String file = intent == null ? null : intent.getStringExtra(EXTRA_FILE);
        if (file == null) {
            if (!STATE.running) stopSelf();
            return START_NOT_STICKY;
        }
        String name = intent.getStringExtra(EXTRA_NAME);
        synchronized (QUEUE) {
            QUEUE.add(new Job(file, name == null ? "Sprachnachricht" : name));
            if (STATE.running) {
                batchTotal++;
                updateMeta(STATE.meta.replaceFirst("^Nachricht \\d+ von \\d+ · ", ""));
                changed();
                return START_NOT_STICKY;
            }
            batchTotal = QUEUE.size();
        }

        batchIndex = 0;
        cancelAll = false;
        STATE.running = true;
        STATE.error = false;
        STATE.result = null;
        STATE.partial = "";
        STATE.progress = "";
        changed();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sprachtext:transcribe");
        wakeLock.acquire(2 * 60 * 60 * 1000L);

        worker = new Thread(this::runQueue, "whisper");
        worker.setPriority(Thread.MAX_PRIORITY);
        worker.start();
        return START_NOT_STICKY;
    }

    // ---------- Warteschlange ----------

    private void runQueue() {
        List<String[]> done = new ArrayList<>(); // {name, text}
        String lastError = null;
        boolean cancelled = false;
        while (true) {
            Job job;
            synchronized (QUEUE) { job = QUEUE.poll(); }
            if (job == null) break;
            if (cancelAll) { new File(job.file).delete(); continue; }
            batchIndex++;
            try {
                String text = runJob(job);
                done.add(new String[]{job.name, text});
            } catch (CancelledException e) {
                cancelled = true;
            } catch (Throwable e) {
                lastError = "Fehler: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                done.add(new String[]{job.name, "(" + lastError + ")"});
            } finally {
                new File(job.file).delete();
            }
        }

        // Ergebnis zusammenstellen
        String text;
        if (done.isEmpty()) {
            text = null;
        } else if (done.size() == 1) {
            text = done.get(0)[1];
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < done.size(); i++) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("▶ ").append(i + 1).append(". ").append(done.get(i)[0]).append("\n").append(done.get(i)[1]);
            }
            text = sb.toString();
        }

        boolean ok = !cancelled && lastError == null && text != null;
        STATE.result = text;
        STATE.partial = "";
        STATE.error = !ok;
        if (cancelled) {
            STATE.status = "Abgebrochen";
        } else if (lastError != null && done.size() <= 1) {
            STATE.status = lastError;
        } else {
            STATE.status = done.size() > 1 ? done.size() + " Nachrichten fertig" : "Fertig";
            STATE.error = false;
        }
        STATE.percent = ok ? 100 : STATE.percent;
        finish(text != null && !cancelled,
                done.size() > 1 ? done.size() + " Transkripte fertig" : null,
                text != null ? text : (cancelled ? null : STATE.status));
    }

    /** Ein Auftrag: Modell sicherstellen → dekodieren → Stille entfernen → transkribieren. */
    private String runJob(Job job) throws Exception {
        jobStart = System.currentTimeMillis();
        final String model = Store.model(this);
        final String lang = Store.lang(this);
        final int threads = threads();
        STATE.partial = "";

        // 1) Modelle (einmalig) herunterladen
        if (!Models.isDownloaded(this, model)) {
            updateMeta(job.name);
            setStatus("Modell wird geladen (nur beim ersten Mal)", 0, "");
            final long dlStart = System.currentTimeMillis();
            Models.ensure(this, model, (loaded, total) -> {
                int pc = total > 0 ? (int) (loaded * 100 / total) : -1;
                double el = (System.currentTimeMillis() - dlStart) / 1000.0;
                String rest = "";
                if (total > 0 && loaded > 0 && el > 1) rest = " · noch ca. " + clock((total - loaded) * el / loaded);
                setStatus("Modell wird geladen (nur beim ersten Mal)", pc,
                        (pc >= 0 ? pc + " % · " : "") + mb(loaded) + (total > 0 ? " / " + mb(total) : "") + " MB" + rest);
            });
        }
        if (vad == 0) {
            try {
                File vf = Models.ensureVad(this);
                vad = Whisper.vadInit(vf.getPath(), threads);
            } catch (Exception ignored) {
                vad = 0; // ohne Stille-Erkennung weitermachen
            }
        }
        checkCancel();

        // 2) Audio dekodieren
        updateMeta(job.name);
        setStatus("Audio wird gelesen", -1, "");
        float[] audio = AudioDecoder.decodeTo16kMono(job.file);
        final double audioSecs = audio.length / 16000.0;
        updateMeta(job.name + " · " + dur(audioSecs));
        if (audio.length < 1600) throw new Exception("Audiodatei ist leer oder zu kurz");
        checkCancel();

        // 3) Stille herausschneiden
        float[] speech = audio;
        if (vad != 0) {
            setStatus("Stille wird entfernt", -1, "");
            float[] s = Whisper.vadCompact(vad, audio);
            if (s != null) {
                if (s.length < 1600) {
                    Store.addHistory(this, "(keine Sprache erkannt)", elapsed(), audioSecs);
                    return "(keine Sprache erkannt)";
                }
                speech = s;
            }
        }
        final double speechSecs = speech.length / 16000.0;
        if (speech != audio) updateMeta(job.name + " · " + dur(audioSecs) + " (Sprache " + dur(speechSecs) + ")");
        audio = null;
        checkCancel();

        // 4) Modell in den Speicher laden (nur bei Wechsel)
        if (ctx == 0 || !model.equals(ctxModel)) {
            setStatus("Modell wird vorbereitet", -1, "");
            if (ctx != 0) { Whisper.free(ctx); ctx = 0; ctxModel = null; }
            ctx = Whisper.init(Models.file(this, model).getPath());
            if (ctx == 0) {
                Models.file(this, model).delete(); // vermutlich beschädigt → beim nächsten Mal neu laden
                throw new Exception("Modell konnte nicht geladen werden, bitte erneut versuchen");
            }
            ctxModel = model;
        }
        checkCancel();

        // 5) Transkribieren – bei kurzer Sprache nur so viel rechnen wie nötig
        int audioCtx = 0;
        if (speechSecs < 28) {
            audioCtx = (int) Math.ceil((speechSecs * 1.15 + 1.0) * 50);
            audioCtx = Math.max(256, Math.min(1500, ((audioCtx + 31) / 32) * 32));
        }
        final double expected = Math.max(1.0, Store.speed(this, model) * speechSecs);
        final long tStart = System.currentTimeMillis();
        final int[] cbPercent = {0};
        final String label = "Transkribiere (" + threads + " Kerne)";

        // Ticker: Balken und Restzeit flüssig aktualisieren (Whisper meldet nur grob)
        final boolean[] ticking = {true};
        Thread ticker = new Thread(() -> {
            while (ticking[0]) {
                double e = (System.currentTimeMillis() - tStart) / 1000.0;
                double tp = e < expected ? e / expected * 95 : 95 + 4 * (1 - Math.exp(-(e - expected) / expected));
                int pc = (int) Math.max(cbPercent[0], Math.min(99, tp));
                double rest = pc >= 5 && cbPercent[0] >= pc ? e * (100 - pc) / pc : Math.max(0, expected - e);
                setStatus(label, pc, pc + " % · " + clock(elapsed()) + " vergangen"
                        + (rest >= 1 ? " · noch ca. " + clock(rest) : " · gleich fertig"));
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
        });
        ticker.start();

        final StringBuilder live = new StringBuilder();
        byte[] out;
        try {
            out = Whisper.transcribe(ctx, speech, lang, threads, audioCtx, new Whisper.Listener() {
                @Override public void onProgress(int percent) { cbPercent[0] = percent; }
                @Override public void onSegment(byte[] utf8Text) {
                    live.append(new String(utf8Text, StandardCharsets.UTF_8));
                    STATE.partial = live.toString().trim();
                    changed();
                }
            });
        } finally {
            ticking[0] = false;
            ticker.interrupt();
            try { ticker.join(1000); } catch (InterruptedException ignored) { }
        }
        if (out == null) {
            if (cancelAll) throw new CancelledException();
            throw new Exception("Transkription fehlgeschlagen");
        }

        double whisperSecs = (System.currentTimeMillis() - tStart) / 1000.0;
        Store.learnSpeed(this, model, whisperSecs / Math.max(0.5, speechSecs));

        String text = new String(out, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) text = "(kein Text erkannt)";
        double secs = elapsed();
        Store.addHistory(this, text, secs, audioSecs);
        STATE.percent = 100;
        STATE.progress = String.format(Locale.GERMANY, "100 %% · %s gesamt (Erkennung %.1f s)", clock(secs), whisperSecs);
        changed();
        return text;
    }

    private void checkCancel() throws CancelledException {
        if (cancelAll) throw new CancelledException();
    }

    private double elapsed() { return (System.currentTimeMillis() - jobStart) / 1000.0; }

    private void updateMeta(String rest) {
        STATE.meta = (batchTotal > 1 ? "Nachricht " + Math.max(1, batchIndex) + " von " + batchTotal + " · " : "") + rest;
    }

    // ---------- Abschluss / Benachrichtigungen ----------

    private void finish(boolean ok, String title, String text) {
        STATE.running = false;
        changed();
        MAIN.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
            if (!uiVisible && text != null) {
                Notification n = new Notification.Builder(this, CHANNEL_DONE)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle(title != null ? title : ok ? "Transkript fertig" : "Transkription fehlgeschlagen")
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

    private void setStatus(String status, int percent, String progress) {
        STATE.status = status;
        STATE.percent = percent;
        STATE.progress = progress;
        changed();
        long now = System.currentTimeMillis();
        if (now - lastNotify > 1000) { // Benachrichtigung höchstens 1×/s aktualisieren
            lastNotify = now;
            String txt = (batchTotal > 1 ? batchIndex + "/" + batchTotal + " · " : "") + status
                    + (progress.isEmpty() ? "" : " · " + progress);
            MAIN.post(() -> { if (STATE.running) startInForeground(txt, percent); });
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

    // ---------- Hilfen ----------

    /** Anzahl Rechen-Threads: höchstens 4 (große Kerne), mindestens 1. */
    static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, n >= 8 ? 4 : n - (n > 4 ? 1 : 0)));
    }

    private static String mb(long b) { return String.valueOf(b / 1048576); }

    /** 0:07, 1:23 … */
    static String clock(double s) {
        int t = (int) Math.round(Math.max(0, s));
        return String.format(Locale.GERMANY, "%d:%02d", t / 60, t % 60);
    }

    static String dur(double s) { return clock(s) + " min"; }

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
