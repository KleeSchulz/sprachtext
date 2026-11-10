package de.sprachtext.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TranscribeService.Listener {

    private static final int REQ_FILE = 42;
    private static final String[] LANG_KEYS = {"de", "auto", "en"};
    private static final String[] LANG_LABELS = {"Deutsch", "Automatisch erkennen", "Englisch"};

    private View work, result, historyBox;
    private TextView status, meta, partial, progressText;
    private ProgressBar bar;
    private EditText text;
    private LinearLayout history;
    private long shownVersion = -1;
    private String shownResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TranscribeService.createChannels(this);

        work = findViewById(R.id.work);
        result = findViewById(R.id.result);
        historyBox = findViewById(R.id.historyBox);
        status = findViewById(R.id.status);
        meta = findViewById(R.id.meta);
        progressText = findViewById(R.id.progressText);
        partial = findViewById(R.id.partial);
        bar = findViewById(R.id.bar);
        text = findViewById(R.id.text);
        history = findViewById(R.id.history);

        findViewById(R.id.pick).setOnClickListener(v -> pickFile());
        findViewById(R.id.cancel).setOnClickListener(v ->
                startService(new Intent(this, TranscribeService.class).setAction(TranscribeService.ACTION_CANCEL)));
        findViewById(R.id.copy).setOnClickListener(v -> copy());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.clearHist).setOnClickListener(v -> { Store.clearHistory(this); renderHistory(); });

        setupSpinner(R.id.model, Models.LABELS, Models.index(Store.model(this)),
                i -> { Store.model(this, Models.KEYS[i]); renderInfo(); });
        int li = 0;
        for (int i = 0; i < LANG_KEYS.length; i++) if (LANG_KEYS[i].equals(Store.lang(this))) li = i;
        setupSpinner(R.id.lang, LANG_LABELS, li, i -> Store.lang(this, LANG_KEYS[i]));

        if (!cpuSupported()) {
            TextView w = findViewById(R.id.cpuWarn);
            w.setText("Hinweis: Dieser Prozessor unterstützt die benötigten Befehle (ARMv8.2 Dot-Product) evtl. nicht – die Erkennung könnte abstürzen.");
            w.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.battery).setOnClickListener(v -> askBatteryExemption());

        renderHistory();
        renderInfo();
        resumeInterrupted();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TranscribeService.listener = this;
        TranscribeService.uiVisible = true;
        getSystemService(NotificationManager.class).cancel(TranscribeService.ID_DONE);
        onStateChanged();
        renderHistory(); // falls im Hintergrund etwas fertig geworden ist
        renderBattery();
    }

    @Override
    protected void onPause() {
        TranscribeService.uiVisible = false;
        TranscribeService.listener = null;
        super.onPause();
    }

    // ---------- Eingänge ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "Audiodatei wählen"), REQ_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "Keine Dateiauswahl verfügbar", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && resultCode == RESULT_OK && data != null
                && (data.getData() != null || data.getClipData() != null)) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else {
                uris.add(data.getData());
            }
            startJobs(uris);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) uris.add(u);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) uris.add(intent.getData());
        }
        intent.setAction(null); // nicht erneut verarbeiten (z. B. nach Drehen)
        if (!uris.isEmpty()) startJobs(uris);
    }

    /** Kopiert die Dateien in den App-Speicher und reiht sie im Dienst ein (Reihenfolge bleibt erhalten). */
    private void startJobs(ArrayList<Uri> uris) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        if (!TranscribeService.STATE.running) {
            result.setVisibility(View.GONE);
            work.setVisibility(View.VISIBLE);
            status.setText(uris.size() > 1 ? uris.size() + " Dateien werden übernommen …" : "Datei wird übernommen …");
            progressText.setText("");
            bar.setIndeterminate(true);
        } else {
            Toast.makeText(this, uris.size() + " zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            File dir = new File(getFilesDir(), "jobs");
            dir.mkdirs();
            int failed = 0;
            for (int k = 0; k < uris.size(); k++) {
                Uri uri = uris.get(k);
                try {
                    String name = displayName(uri);
                    File out = new File(dir, "audio-" + System.currentTimeMillis() + "-" + k);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream os = new FileOutputStream(out)) {
                        if (in == null) throw new Exception("Datei nicht lesbar");
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    try (FileWriter w = new FileWriter(out.getPath() + ".name")) { w.write(name); }
                    runOnUiThread(() -> startForegroundService(new Intent(this, TranscribeService.class)
                            .putExtra(TranscribeService.EXTRA_FILE, out.getPath())
                            .putExtra(TranscribeService.EXTRA_NAME, name)));
                } catch (Exception e) {
                    failed++;
                }
            }
            final int f = failed;
            if (f > 0) runOnUiThread(() -> {
                Toast.makeText(this, f + " Datei(en) konnten nicht übernommen werden", Toast.LENGTH_LONG).show();
                if (f == uris.size() && !TranscribeService.STATE.running) {
                    status.setText("Die Datei konnte nicht übernommen werden.");
                    bar.setIndeterminate(false);
                    bar.setProgress(0);
                }
            });
        }).start();
    }

    /**
     * Falls die App (bzw. der Dienst) während einer Transkription beendet wurde,
     * liegen die Dateien noch im Ordner "jobs" → automatisch erneut einreihen.
     */
    private void resumeInterrupted() {
        if (TranscribeService.STATE.running) return;
        File[] files = new File(getFilesDir(), "jobs").listFiles((d, n) -> n.startsWith("audio-") && !n.endsWith(".name"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            String name = "Sprachnachricht";
            try {
                File nf = new File(f.getPath() + ".name");
                if (nf.exists()) name = new String(Files.readAllBytes(nf.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            startForegroundService(new Intent(this, TranscribeService.class)
                    .putExtra(TranscribeService.EXTRA_FILE, f.getPath())
                    .putExtra(TranscribeService.EXTRA_NAME, name));
        }
        Toast.makeText(this, "Unterbrochene Transkription wird fortgesetzt", Toast.LENGTH_LONG).show();
    }

    // ---------- Akku-Optimierung ----------

    private boolean batteryExempt() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void renderBattery() {
        findViewById(R.id.batteryBox).setVisibility(batteryExempt() ? View.GONE : View.VISIBLE);
    }

    private void askBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        return "Sprachnachricht";
    }

    // ---------- Anzeige ----------

    @Override
    public void onStateChanged() {
        TranscribeService.State s = TranscribeService.STATE;
        if (s.version == shownVersion) return;
        shownVersion = s.version;
        if (s.version == 0) return; // noch nie etwas gelaufen

        if (s.running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        work.setVisibility(View.VISIBLE);
        status.setText(s.status);
        status.setTextColor(getColor(s.error ? R.color.err : android.R.color.white));
        meta.setText(s.meta);
        progressText.setText(s.progress);
        progressText.setVisibility(s.progress == null || s.progress.isEmpty() ? View.GONE : View.VISIBLE);
        bar.setIndeterminate(s.running && s.percent < 0);
        bar.setProgress(Math.max(0, s.percent));
        findViewById(R.id.cancel).setVisibility(s.running ? View.VISIBLE : View.GONE);

        String p = s.partial;
        partial.setVisibility(s.running && p != null && !p.isEmpty() ? View.VISIBLE : View.GONE);
        partial.setText(p);

        if (!s.running && s.result != null && !s.result.equals(shownResult)) {
            shownResult = s.result;
            showResult(s.result);
            renderHistory();
        }
    }

    private void showResult(String t) {
        result.setVisibility(View.VISIBLE);
        text.setText(t);
    }

    private void renderHistory() {
        JSONArray h = Store.history(this);
        historyBox.setVisibility(h.length() == 0 ? View.GONE : View.VISIBLE);
        history.removeAllViews();
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) continue;
            final String t = o.optString("text");
            TextView tv = new TextView(this);
            String head = df.format(new Date(o.optLong("t")));
            if (o.has("audio")) head += " · " + TranscribeService.dur(o.optDouble("audio"))
                    + String.format(Locale.GERMANY, " · %.0f s", o.optDouble("secs"));
            tv.setText(head + "\n" + (t.length() > 140 ? t.substring(0, 140) + " …" : t));
            tv.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
            tv.setOnClickListener(v -> { showResult(t); text.requestFocus(); });
            history.addView(tv);
        }
    }

    private void renderInfo() {
        String m = Store.model(this);
        String s = "Modell " + (Models.isDownloaded(this, m) ? "ist geladen und offline nutzbar." : "wird beim ersten Transkribieren heruntergeladen (WLAN empfohlen).")
                + "\nErkennung läuft komplett auf dem Handy mit " + TranscribeService.threads() + " Kernen. Audiodateien verlassen das Handy nicht.";
        ((TextView) findViewById(R.id.info)).setText(s);
    }

    // ---------- Aktionen ----------

    private void copy() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Transkript", text.getText().toString()));
        Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text.getText().toString());
        startActivity(Intent.createChooser(i, "Text teilen"));
    }

    // ---------- Hilfen ----------

    private interface OnPick { void on(int index); }

    private void setupSpinner(int id, String[] labels, int selected, OnPick onPick) {
        Spinner sp = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(selected, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onPick.on(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    /** Prüft, ob der Prozessor Dot-Product-Befehle (asimddp) kann – dafür ist die Bibliothek gebaut. */
    private static boolean cpuSupported() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features") && line.contains("asimddp")) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // im Zweifel nicht warnen
        }
    }
}
