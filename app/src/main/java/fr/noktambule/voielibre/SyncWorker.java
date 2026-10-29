package fr.noktambule.voielibre;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewAssetLoader;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Synchro automatique en arrière-plan (toutes les 2 h par défaut, réglable), seulement avec du réseau :
 * même travail que le bouton ⟳ du widget (planning Graou, météo, images du widget), mais sans
 * aucun écran. Android choisit le moment exact (veille, économie de batterie).
 */
public class SyncWorker extends Worker {

    private static final String NOM = "synchro-auto";
    private static final long DELAI_MAX_S = 90;

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    /** Programme la synchro périodique (fréquence réglée dans Paramètres, 2 h par défaut) ; sans effet si elle l'est déjà. */
    static void programmer(Context ctx) { programmer(ctx, false); }

    /** changement = true : la fréquence a changé, la synchro est reprogrammée avec la nouvelle. */
    static void programmer(Context ctx, boolean changement) {
        int min = Math.max(15, Notifs.prefs(ctx).getInt("syncFreq", 120));
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(SyncWorker.class, min, TimeUnit.MINUTES)
                .setConstraints(c)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(NOM, changement ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, r);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context ctx = getApplicationContext();
        // Nouvelle version validée sur GitHub ? (une fois par jour au plus, notification une seule fois par version)
        try { MiseAJour.enArrierePlan(ctx); } catch (Exception ignore) { }
        // L'appli ouverte se met déjà à jour elle-même ; une seconde page dans le même moteur web la ralentirait
        if (MainActivity.visible) return Result.success();
        final Handler principal = new Handler(Looper.getMainLooper());
        final CountDownLatch fin = new CountDownLatch(1);
        final WebView[] web = new WebView[1];

        principal.post(() -> {
            try {
                WebView w = new WebView(ctx);
                web[0] = w;
                // Pas d'écran : on lui donne une taille, comme s'il était affiché
                w.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
                w.layout(0, 0, 400, 800);

                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setAllowFileAccess(false);

                final WebViewAssetLoader chargeur = Pages.chargeur(ctx);
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                        return chargeur.shouldInterceptRequest(r.getUrl());
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        return !MainActivity.HOTE.equals(r.getUrl().getHost());
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                        Pont.noterArret(ctx, d, "synchro en arrière-plan");
                        try { v.destroy(); } catch (Exception ignore) { }
                        web[0] = null;
                        fin.countDown();
                        return true;
                    }
                });

                Pont pont = new Pont(ctx, null, w);
                pont.finSynchro = fin::countDown;
                w.addJavascriptInterface(pont, "BoulotAndroid");
                w.loadUrl(MainActivity.ACCUEIL + "?sync=1");
            } catch (Exception e) {
                fin.countDown();
            }
        });

        try { fin.await(DELAI_MAX_S, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }

        // Laisse le temps à la page d'envoyer les journées au widget, puis libère tout
        final CountDownLatch nettoye = new CountDownLatch(1);
        principal.postDelayed(() -> {
            if (web[0] != null) { try { web[0].destroy(); } catch (Exception ignore) { } }
            VoieLibreWidget.majTous(ctx);
            nettoye.countDown();
        }, 1500);
        try { nettoye.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        return Result.success();
    }
}
