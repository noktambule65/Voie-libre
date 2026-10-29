package fr.noktambule.voielibre;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;

/**
 * Voie libre : affiche VoieLibre.html (dans les assets) en plein écran, sans barre de navigateur.
 * La page est servie depuis une adresse https locale, ce qui garde son stockage (lien Graou,
 * planning, réglages) d'une ouverture à l'autre. Le pont « BoulotAndroid » fournit à la page
 * ce qu'une WebView ne sait pas faire seule : partage, enregistrement de fichiers, écran allumé.
 */
public class MainActivity extends Activity {

    static final String HOTE = "appassets.androidplatform.net";
    static final String ACCUEIL = "https://" + HOTE + "/assets/VoieLibre.html";
    private static final int REQ_FICHIERS = 1;
    static final int REQ_SONNERIE = 2;
    /** Réglage en cours de choix dans le sélecteur de sonneries (« r » réveil, « d » départ). */
    static String sonnerieCible = null;

    private WebView web;
    private FrameLayout racine;
    private View vuePleinEcran;
    private WebChromeClient.CustomViewCallback rappelPleinEcran;
    private ValueCallback<Uri[]> rappelFichiers;
    private WebChromeClient chrome;
    static final int REQ_POSITION = 3;
    /** Demande de position de la page en attente de la réponse d'Android (météo des jours de repos). */
    private String originePosition;
    private android.webkit.GeolocationPermissions.Callback rappelPosition;
    /** Une demande d'autorisation de position n'est faite que sur un choix de l'utilisateur (Paramètres › Ma position). */
    static volatile boolean demandePositionPermise = false;
    /** Ouverture depuis la notification « Mise à jour disponible » : la proposition s'affiche au premier plan. */
    private boolean majDemandee = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        SyncWorker.programmer(this);   // synchro automatique en arrière-plan (sans effet si déjà programmée)
        Notifs.programmer(this);       // prochain rappel de découché, s'il est activé
        majDemandee = getIntent() != null && getIntent().getBooleanExtra("maj", false);
        if (majDemandee) getIntent().removeExtra("maj");

        racine = new FrameLayout(this);
        web = new WebView(this);
        racine.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(racine);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        s.setGeolocationEnabled(true);

        final WebViewAssetLoader chargeur = Pages.chargeur(this);

        web.addJavascriptInterface(new Pont(this, web), "BoulotAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return chargeur.shouldInterceptRequest(r.getUrl());
            }

            /** Moteur web arrêté (plantage ou mémoire) : on le note pour le journal de diagnostic et on relance l'écran. */
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                Pont.noterArret(MainActivity.this, d, "appli");
                if (web != null) { racine.removeView(web); web.destroy(); web = null; }
                recreate();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                // Les pages de l'appli restent dedans ; les liens externes s'ouvrent dans Chrome ou l'appli concernée
                if ((sch.equals("http") || sch.equals("https")) && HOTE.equals(u.getHost())) return false;
                if (sch.equals("http") || sch.equals("https") || sch.equals("mailto")
                        || sch.equals("tel") || sch.equals("sms") || sch.equals("geo") || sch.equals("intent")) {
                    ouvrirExterne(u);
                    return true;
                }
                return false;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rappel, FileChooserParams p) {
                if (rappelFichiers != null) rappelFichiers.onReceiveValue(null);
                rappelFichiers = rappel;
                boolean images = false;
                String[] types = p.getAcceptTypes();
                if (types != null && types.length > 0) {
                    images = true;
                    for (String t : types) if (t == null || !t.trim().startsWith("image/")) { images = false; break; }
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(images ? "image/*" : "*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_FICHIERS);
                } catch (ActivityNotFoundException e) {
                    rappelFichiers = null;
                    rappel.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            /** La page demande la position (Paramètres › Météo › Ma position) : autorisation Android « position approximative ». */
            @Override
            public void onGeolocationPermissionsShowPrompt(String origine, android.webkit.GeolocationPermissions.Callback rappel) {
                if (!MainActivity.HOTE.equals(Uri.parse(origine).getHost())) { rappel.invoke(origine, false, false); return; }
                if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    rappel.invoke(origine, true, false);
                    return;
                }
                // Une seule demande à la fois, et pas de nouvelle demande automatique après une réponse :
                // la demande met l'appli en pause, la page se masque puis se réaffiche et redemande → boucle sans fin.
                if (rappelPosition != null) { rappel.invoke(origine, false, false); return; }
                if (!demandePositionPermise) {
                    Pont.etape(MainActivity.this, "position demandée par la page, refusée sans redemander (autorisation Android absente)", false);
                    rappel.invoke(origine, false, false);
                    return;
                }
                demandePositionPermise = false;
                Pont.etape(MainActivity.this, "demande d'autorisation de position à Android", false);
                originePosition = origine;
                rappelPosition = rappel;
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_POSITION);
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() { }

            @Override
            public void onShowCustomView(View vue, CustomViewCallback rappel) {
                if (vuePleinEcran != null) { rappel.onCustomViewHidden(); return; }
                vuePleinEcran = vue;
                rappelPleinEcran = rappel;
                racine.addView(vue, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (web != null) web.setVisibility(View.GONE);
                barresSysteme(false);
            }

            @Override
            public void onHideCustomView() {
                if (vuePleinEcran == null) return;
                racine.removeView(vuePleinEcran);
                vuePleinEcran = null;
                if (web != null) web.setVisibility(View.VISIBLE);
                barresSysteme(true);
                if (rappelPleinEcran != null) rappelPleinEcran.onCustomViewHidden();
                rappelPleinEcran = null;
            }
        };
        web.setWebChromeClient(chrome);

        web.setDownloadListener((url, ua, cd, mime, taille) -> {
            if (url != null && url.startsWith("http")) ouvrirExterne(Uri.parse(url));
        });

        if (etat != null) web.restoreState(etat);
        else web.loadUrl(ACCUEIL + ancre(getIntent()));
    }

    /**
     * Le widget ou une notification peut demander d'ouvrir directement une rubrique, éventuellement
     * à un jour précis (« Voir ma journée ») ou en vue Semaines.
     * Renvoie {rubrique, précision (date ou ville), vue}, ou null.
     */
    private static String[] destination(Intent i) {
        String r = i == null ? null : i.getStringExtra("rubrique");
        if (r == null || !r.matches("[a-z]+")) return null;
        String date = i.getStringExtra("date"), ville = i.getStringExtra("ville"), vue = i.getStringExtra("vue");
        String arg = date != null && date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : ville != null ? ville : "";
        return new String[]{r, arg, "mois".equals(vue) ? "mois" : ""};
    }

    private static String ancre(Intent i) {
        String[] d = destination(i);
        if (d == null) return "";
        return "#" + d[0] + "/" + Uri.encode(d[1]) + "/" + d[2];
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("maj", false)) majDemandee = true;
        String[] d = destination(intent);
        if (d != null && web != null)
            web.evaluateJavascript("window.boulotAller&&window.boulotAller(" + org.json.JSONObject.quote(d[0]) + ","
                    + org.json.JSONObject.quote(d[1]) + "," + org.json.JSONObject.quote(d[2]) + ")", null);
    }

    @SuppressWarnings("deprecation")
    private void barresSysteme(boolean visibles) {
        View d = getWindow().getDecorView();
        if (visibles) d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        else d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void ouvrirExterne(Uri u) {
        try {
            Intent i = "intent".equalsIgnoreCase(u.getScheme())
                    ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Aucune appli pour ouvrir ce lien", Toast.LENGTH_SHORT).show();
        }
    }

    /** Sonnerie choisie dans le sélecteur d'Android : renvoyée à la page (Paramètres › Réveil). */
    private void retourSonnerie(int resultat, Intent data) {
        String cible = sonnerieCible;
        sonnerieCible = null;
        if (cible == null || resultat != RESULT_OK || data == null || web == null) return;
        Uri u = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        String uri, titre;
        if (u == null) { uri = "silence"; titre = "Silencieux"; }
        else {
            uri = u.toString();
            titre = "Sonnerie";
            try {
                android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this, u);
                if (r != null) titre = r.getTitle(this);
            } catch (Exception ignore) { }
        }
        if (web == null) return;
        web.evaluateJavascript("window.__boulotSonnerie&&window.__boulotSonnerie(" + org.json.JSONObject.quote(cible) + ","
                + org.json.JSONObject.quote(uri) + "," + org.json.JSONObject.quote(titre) + ")", null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code != REQ_POSITION || rappelPosition == null) return;
        boolean ok = resultats.length > 0 && resultats[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        rappelPosition.invoke(originePosition, ok, false);
        rappelPosition = null;
        originePosition = null;
    }

    @Override
    protected void onActivityResult(int code, int resultat, Intent data) {
        super.onActivityResult(code, resultat, data);
        if (code == REQ_SONNERIE) { retourSonnerie(resultat, data); return; }
        if (code != REQ_FICHIERS || rappelFichiers == null) return;
        Uri[] res = null;
        if (resultat == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                res = new Uri[n];
                for (int k = 0; k < n; k++) res[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                res = new Uri[]{data.getData()};
            }
        }
        rappelFichiers.onReceiveValue(res);
        rappelFichiers = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (vuePleinEcran != null) { chrome.onHideCustomView(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    /** Vrai quand l'appli est à l'écran : la synchro en arrière-plan ne tourne pas en même temps (même moteur web). */
    static volatile boolean visible = false;

    @Override
    protected void onPause() { super.onPause(); Pont.etape(this, "⏸ appli en pause", false); visible = false; chien.removeCallbacks(veille); if (web != null) web.onPause(); }

    // ---------- Chien de garde (diagnostic) : repère une page figée ou un écran Android bloqué, et note où ----------
    private final Handler chien = new Handler(Looper.getMainLooper());
    private long pingEnvoye = 0;
    private boolean pingAttente = false, figeNote = false;
    static volatile long battement = 0, tacheDepuis = 0;
    static volatile String tacheEnCours = null;
    private static Thread gardien;
    private final Runnable veille = new Runnable() {
        @Override public void run() {
            long t = SystemClock.uptimeMillis();
            battement = t;
            if (web != null && visible) {
                if (pingAttente) {
                    if (!figeNote && t - pingEnvoye > 5000) { figeNote = true; Pont.etape(MainActivity.this, "⚠ PAGE FIGÉE depuis 5 s (le moteur web ne répond plus)", true); }
                } else {
                    pingAttente = true; pingEnvoye = t;
                    web.evaluateJavascript("1", v -> {
                        long d = SystemClock.uptimeMillis() - pingEnvoye;
                        if (figeNote || d > 2000) Pont.etape(MainActivity.this, (figeNote ? "✓ " : "") + "page de nouveau réactive après " + (d / 100) / 10.0 + " s", true);
                        pingAttente = false; figeNote = false;
                    });
                }
            }
            chien.postDelayed(this, 1000);
        }
    };

    private void lancerGardien() {
        if (gardien != null) return;
        final android.content.Context app = getApplicationContext();
        // Tâche en cours sur le fil principal d'Android (pour savoir laquelle bloque)
        Looper.getMainLooper().setMessageLogging(x -> {
            if (x.startsWith(">")) { tacheEnCours = x; tacheDepuis = SystemClock.uptimeMillis(); } else tacheEnCours = null;
        });
        gardien = new Thread(() -> {
            boolean note = false; long prochain = 0, pire = 0;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long retard = SystemClock.uptimeMillis() - battement;
                if (visible && retard > 4000) {
                    pire = retard;
                    if (!note || retard > prochain) {
                        note = true; prochain = retard + 10000;
                        String tache = tacheEnCours;
                        String quoi = tache == null ? " · aucune tâche en cours (attente)" : " · tâche : " + tache.replaceAll("\\s+", " ") + " (depuis " + (SystemClock.uptimeMillis() - tacheDepuis) / 1000 + " s)";
                        StringBuilder pile = new StringBuilder();
                        for (StackTraceElement el : Looper.getMainLooper().getThread().getStackTrace()) {
                            if (pile.length() > 900) break;
                            pile.append(" < ").append(el.getClassName().replaceAll(".*\\.", "")).append('.').append(el.getMethodName());
                        }
                        Pont.etape(app, "⚠ ÉCRAN ANDROID BLOQUÉ depuis " + retard / 1000 + " s" + quoi + " · pile :" + pile, true);
                    }
                } else if (note) {
                    note = false;
                    Pont.etape(app, "✓ écran Android de nouveau réactif (bloqué au moins " + pire / 1000 + " s)", true);
                }
            }
        }, "gardien");
        gardien.setDaemon(true);
        gardien.start();
    }

    @Override
    protected void onResume() { super.onResume(); Pont.etape(this, "▶ appli au premier plan", false); visible = true; battement = SystemClock.uptimeMillis(); pingAttente = false; figeNote = false; chien.removeCallbacks(veille); chien.post(veille); lancerGardien(); if (web != null) web.onResume();
        // Mise à jour : installation en attente d'autorisation, sinon vérification (une fois par jour au plus)
        MiseAJour.auRetour(this);
        MiseAJour.aLOuverture(this, majDemandee);
        majDemandee = false;
    }

    @Override
    protected void onDestroy() {
        if (web != null) { racine.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
