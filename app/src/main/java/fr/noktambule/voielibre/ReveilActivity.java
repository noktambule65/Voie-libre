package fr.noktambule.voielibre;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lancée par le bouton de la notification « Réveil oublié » : envoie à l'Horloge le réveil et le départ
 * (mêmes réglages que le bouton ⏰ de la journée), l'un après l'autre, puis se ferme sans rien afficher.
 */
public class ReveilActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(4);
        int n = 0;
        try {
            JSONArray l = new JSONArray(getIntent().getStringExtra("liste"));
            if (new Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, "Aucune appli Horloge pour régler le réveil", Toast.LENGTH_LONG).show();
            } else {
                for (int k = 0; k < l.length(); k++) {
                    JSONObject r = l.getJSONObject(k);
                    final Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, r.getInt("h"))
                            .putExtra(AlarmClock.EXTRA_MINUTES, r.getInt("m"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, r.optString("msg", "Voie libre"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    String son = r.optString("son", "");
                    if (son.equals("silence")) i.putExtra(AlarmClock.EXTRA_RINGTONE, AlarmClock.VALUE_RINGTONE_SILENT);
                    else if (son.startsWith("content:")) i.putExtra(AlarmClock.EXTRA_RINGTONE, son);
                    if (r.has("vib")) i.putExtra(AlarmClock.EXTRA_VIBRATE, r.optBoolean("vib", true));
                    h.postDelayed(() -> {
                        try { startActivity(i); } catch (Exception e) {
                            Toast.makeText(this, "Réveil impossible à régler", Toast.LENGTH_SHORT).show();
                        }
                    }, k * 1500L);
                    n++;
                }
                if (n > 0) Toast.makeText(this, n > 1 ? "Réveil et départ envoyés à l'Horloge" : "Réveil envoyé à l'Horloge", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Réveil impossible à régler", Toast.LENGTH_SHORT).show();
        }
        h.postDelayed(() -> { finish(); overridePendingTransition(0, 0); }, Math.max(1, n) * 1500L + 500);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
