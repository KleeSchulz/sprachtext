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
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Vordergrunddienst, solange transkribiert wird: hält den Prozess am Leben
 * (auch wenn die App im Hintergrund ist) und das Handy per Wakelock wach.
 */
public class TranscribeService extends Service {

    static final String CHANNEL_WORK = "work";
    static final String CHANNEL_DONE = "done";
    static final int ID_WORK = 1;
    static final int ID_DONE = 2;

    private PowerManager.WakeLock wakeLock;

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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels(this);
        Notification n = new Notification.Builder(this, CHANNEL_WORK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sprachtext")
                .setContentText("Transkribiere …")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .setContentIntent(openApp(this))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_WORK, n);
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sprachtext:transcribe");
            wakeLock.acquire(30 * 60 * 1000L); // Sicherheitsgrenze: 30 Minuten
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
