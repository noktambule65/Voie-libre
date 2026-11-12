package fr.noktambule.voielibre;

import android.app.Activity;
import android.app.NotificationManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Écran rouge « Départ dans X min », ouvert seulement si Android choisit d'afficher l'alerte en plein écran
 * (normalement, téléphone en cours d'utilisation, c'est le bandeau persistant qui s'affiche). OK ferme tout.
 */
public class DepartActivity extends Activity {

    // compte à rebours en direct quand l'alerte porte une heure de départ
    private final android.os.Handler minuterie = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable tic;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        // l'écran reste allumé tant que l'alerte est affichée ; elle se ferme toute seule 3 min après (comme la notification)
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // avec une heure de départ : fermé en même temps que la notification (2 min après le départ)
        long dep = getIntent().getLongExtra("depart", 0);
        long duree = dep > 0 ? Math.max(60000L, dep + 120000L - System.currentTimeMillis()) : 3 * 60 * 1000L;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, duree);
        afficher();
    }

    /** Écran déjà ouvert (instance unique) : une nouvelle alerte (départ ou phrase) remplace l'affichage. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        afficher();
    }

    private void afficher() {
        int id = getIntent().getIntExtra("id", 0);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(0xFFC62828);
        l.setPadding(48, 48, 48, 48);
        long depart = getIntent().getLongExtra("depart", 0);
        TextView grand = texte(getIntent().getStringExtra("grand"), 34, true);
        l.addView(grand);
        if (tic != null) minuterie.removeCallbacks(tic);
        tic = null;
        if (depart > 0) {
            TextView chiffres = texte("", 56, true);
            chiffres.setFontFeatureSettings("tnum");
            l.addView(chiffres);
            tic = new Runnable() { @Override public void run() {
                long now = System.currentTimeMillis();
                grand.setText(Notifs.grandDepart(depart, now));
                chiffres.setText(Notifs.decompte(depart, now));
                minuterie.postDelayed(this, 1000L - now % 1000L);
            } };
            tic.run();
        }
        l.addView(texte(getIntent().getStringExtra("train"), 26, true));
        l.addView(texte(getIntent().getStringExtra("trajet"), 20, false));
        String note = getIntent().getStringExtra("note");
        if (note != null && !note.isEmpty()) { TextView n = texte(note, 26, true); n.setTextColor(0xFFFFEB3B); l.addView(n); }
        Button ok = new Button(this);
        ok.setText("OK");
        ok.setTextSize(20);
        ok.setOnClickListener(v -> {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && id != 0) nm.cancel(id);
            finish();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = 60;
        l.addView(ok, p);
        setContentView(l);
    }

    @Override
    protected void onDestroy() {
        if (tic != null) minuterie.removeCallbacks(tic);
        super.onDestroy();
    }

    private TextView texte(String s, int taille, boolean gras) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(taille);
        t.setGravity(Gravity.CENTER);
        if (gras) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 10, 0, 10);
        return t;
    }
}
