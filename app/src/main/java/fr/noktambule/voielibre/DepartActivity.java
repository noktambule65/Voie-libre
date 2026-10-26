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

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        int id = getIntent().getIntExtra("id", 0);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(0xFFC62828);
        l.setPadding(48, 48, 48, 48);
        l.addView(texte(getIntent().getStringExtra("grand"), 34, true));
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
