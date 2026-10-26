package fr.noktambule.voielibre;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

/**
 * Synchro express lancée par le bouton ⟳ du widget : charge Voie libre de façon invisible,
 * retélécharge le planning Graou, recalcule les journées du widget, puis se referme.
 */
public class SyncActivity extends Activity {

    private WebView web;
    private final Handler minuteur = new Handler(Looper.getMainLooper());
    private boolean fini = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getSharedPreferences(VoieLibreWidget.PREFS, 0).edit()
                .putLong("syncDepuis", System.currentTimeMillis()).apply();
        VoieLibreWidget.majTous(this);

        FrameLayout racine = new FrameLayout(this);
        web = new WebView(this);
        racine.addView(web, new FrameLayout.LayoutParams(400, 800));
        web.setAlpha(0f);
        setContentView(racine);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader chargeur = Pages.chargeur(this);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeur.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !MainActivity.HOTE.equals(r.getUrl().getHost());
            }
        });

        Pont pont = new Pont(this, web);
        pont.finSynchro = () -> terminer(true);
        web.addJavascriptInterface(pont, "BoulotAndroid");
        web.loadUrl(MainActivity.ACCUEIL + "?sync=1");

        // Sécurité : on abandonne au bout de 60 s
        minuteur.postDelayed(() -> terminer(false), 60000);
    }

    private void terminer(boolean ok) {
        if (fini) return;
        fini = true;
        minuteur.removeCallbacksAndMessages(null);
        getSharedPreferences(VoieLibreWidget.PREFS, 0).edit().remove("syncDepuis").apply();
        if (!ok) Toast.makeText(this, "Voie libre : mise à jour impossible", Toast.LENGTH_SHORT).show();
        // Laisse le temps à la page d'envoyer les journées au widget avant de fermer
        minuteur.postDelayed(() -> {
            VoieLibreWidget.majTous(this);
            finish();
            overridePendingTransition(0, 0);
        }, 800);
    }

    @Override
    protected void onDestroy() {
        minuteur.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
