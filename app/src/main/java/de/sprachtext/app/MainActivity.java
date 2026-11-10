package de.sprachtext.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/web/index.html";
    private static final int REQ_FILE = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean visible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File sharedDir = new File(getCacheDir(), "shared");
        sharedDir.mkdirs();

        // Lokale Dateien über eine HTTPS-Adresse ausliefern (sicherer Kontext für Worker, Cache usw.)
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/shared/", new WebViewAssetLoader.InternalStoragePathHandler(this, sharedDir))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        // Rechenprozess der WebView auch im Hintergrund nicht herunterstufen
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        TranscribeService.createChannels(this);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                if (r != null) {
                    // Korrekte MIME-Typen, sonst lädt die WebView .mjs/.wasm nicht als Modul/WebAssembly
                    String path = request.getUrl().getPath();
                    if (path != null) {
                        if (path.endsWith(".mjs") || path.endsWith(".js")) r.setMimeType("text/javascript");
                        else if (path.endsWith(".wasm")) r.setMimeType("application/wasm");
                    }
                }
                return r;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Audiodatei wählen"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (!handleShareIntent(getIntent())) {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** Liest die geteilte Audiodatei in den App-Cache und lädt die Seite mit ?shared=native. */
    private boolean handleShareIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) uri = list.get(0);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return false;

        final Uri src = uri;
        new Thread(() -> {
            String target;
            try {
                File out = new File(new File(getCacheDir(), "shared"), "audio.bin");
                try (InputStream in = getContentResolver().openInputStream(src);
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new Exception("Datei nicht lesbar");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                target = START_URL + "?shared=native&name=" + URLEncoder.encode(displayName(src), "UTF-8")
                        + "&t=" + System.currentTimeMillis();
            } catch (Exception e) {
                target = START_URL + "?shared=error";
            }
            final String url = target;
            runOnUiThread(() -> web.loadUrl(url));
        }).start();
        // Intent nicht erneut verarbeiten (z. B. nach Drehen)
        intent.setAction(null);
        return true;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        getSystemService(NotificationManager.class).cancel(TranscribeService.ID_DONE);
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Wird im JavaScript als window.AndroidBridge angeboten. */
    private class Bridge {
        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Transkript", text));
                Toast.makeText(MainActivity.this, "Kopiert", Toast.LENGTH_SHORT).show();
            });
        }

        /** Transkription startet: Vordergrunddienst + Bildschirm wach halten. */
        @JavascriptInterface
        public void busyStart() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
                }
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {
                    startForegroundService(new Intent(MainActivity.this, TranscribeService.class));
                } catch (Exception ignored) { }
            });
        }

        /** Transkription beendet: Dienst stoppen, ggf. Fertig-Benachrichtigung. */
        @JavascriptInterface
        public void busyEnd(final String text, final boolean ok) {
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                stopService(new Intent(MainActivity.this, TranscribeService.class));
                if (!visible) {
                    String body = text == null ? "" : text;
                    Notification n = new Notification.Builder(MainActivity.this, TranscribeService.CHANNEL_DONE)
                            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                            .setContentTitle(ok ? "Transkript fertig" : "Transkription fehlgeschlagen")
                            .setContentText(body)
                            .setStyle(new Notification.BigTextStyle().bigText(body))
                            .setAutoCancel(true)
                            .setContentIntent(TranscribeService.openApp(MainActivity.this))
                            .build();
                    try {
                        getSystemService(NotificationManager.class).notify(TranscribeService.ID_DONE, n);
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Text teilen"));
            });
        }
    }
}
