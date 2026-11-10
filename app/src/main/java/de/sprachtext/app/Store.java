package de.sprachtext.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Einstellungen und Verlauf (max. 20 Einträge) in SharedPreferences. */
final class Store {

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("sprachtext", Context.MODE_PRIVATE);
    }

    static String model(Context c) { return p(c).getString("model", "base"); }
    static void model(Context c, String v) { p(c).edit().putString("model", v).apply(); }

    static String lang(Context c) { return p(c).getString("lang", "de"); }
    static void lang(Context c, String v) { p(c).edit().putString("lang", v).apply(); }

    static JSONArray history(Context c) {
        try { return new JSONArray(p(c).getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void addHistory(Context c, String text, double secs, double audioSecs) {
        try {
            JSONArray old = history(c);
            JSONArray h = new JSONArray();
            h.put(new JSONObject().put("t", System.currentTimeMillis()).put("text", text)
                    .put("secs", secs).put("audio", audioSecs));
            for (int i = 0; i < old.length() && h.length() < 20; i++) h.put(old.get(i));
            p(c).edit().putString("history", h.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void clearHistory(Context c) { p(c).edit().remove("history").apply(); }

    private Store() { }
}
