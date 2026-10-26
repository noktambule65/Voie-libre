package fr.noktambule.voielibre;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fonctions appelées depuis la page (window.BoulotAndroid), partagées par l'appli et la synchro du widget. */
public class Pont {

    /** Écran de l'appli, ou null pour la synchro en arrière-plan (sans écran). */
    private final Activity act;
    private final Context ctx;
    private final WebView web;
    private final Handler principal = new Handler(Looper.getMainLooper());
    /** Appelé à la fin d'une synchro lancée depuis le widget ou en arrière-plan. */
    Runnable finSynchro;

    Pont(Activity act, WebView web) { this(act, act, web); }

    Pont(Context ctx, Activity act, WebView web) {
        this.ctx = ctx.getApplicationContext();
        this.act = act;
        this.web = web;
    }

    private void surEcran(Runnable r) { principal.post(r); }

    static String nomPropre(String nom) {
        String n = nom == null || nom.trim().isEmpty() ? "fichier" : nom.trim();
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }


        @JavascriptInterface
        public void partager(String titre, String texte, String url) {
            if (act == null) return;
            act.runOnUiThread(() -> {
                String t = texte == null ? "" : texte;
                if (url != null && !url.isEmpty()) t = t.isEmpty() ? url : t + "\n" + url;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t);
                if (titre != null && !titre.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, titre);
                try { act.startActivity(Intent.createChooser(i, "Partager")); } catch (Exception ignore) { }
            });
        }

        @JavascriptInterface
        public void partagerFichier(String nom, String mime, String base64, String titre) {
            if (act == null) return;
            try {
                File dossier = new File(act.getCacheDir(), "partage");
                if (!dossier.exists()) dossier.mkdirs();
                File f = new File(dossier, nomPropre(nom));
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(base64, Base64.DEFAULT)); }
                Uri u = FileProvider.getUriForFile(act, "fr.noktambule.voielibre.fichiers", f);
                act.runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    if (titre != null && !titre.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, titre);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { act.startActivity(Intent.createChooser(i, "Partager")); } catch (Exception ignore) { }
                });
            } catch (Exception e) {
                message("Partage impossible");
            }
        }

        @JavascriptInterface
        public void enregistrer(String nom, String mime, String base64) {
            if (act == null) return;
            try {
                byte[] octets = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, nomPropre(nom));
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Voie libre");
                ContentResolver cr = act.getContentResolver();
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new Exception("insert");
                try (OutputStream o = cr.openOutputStream(u)) {
                    if (o == null) throw new Exception("flux");
                    o.write(octets);
                }
                message("Enregistré dans Téléchargements/Voie libre");
            } catch (Exception e) {
                message("Enregistrement impossible");
            }
        }

        /**
         * Télécharge une adresse sans les restrictions du navigateur (abonnement Graou…).
         * La réponse revient à la page par window.__boulotRetour(id, statut, contenu en base64).
         */
        @JavascriptInterface
        public void telecharger(String id, String adresse) {
            new Thread(() -> {
                int statut = 0;
                String contenu = "";
                HttpURLConnection c = null;
                try {
                    String a = adresse == null ? "" : adresse.trim();
                    if (a.toLowerCase().startsWith("webcal://")) a = "https://" + a.substring(9);
                    for (int saut = 0; saut < 5; saut++) {
                        c = (HttpURLConnection) new URL(a).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setInstanceFollowRedirects(true);
                        c.setRequestProperty("User-Agent", "VoieLibre-Android");
                        statut = c.getResponseCode();
                        if (statut >= 300 && statut < 400 && c.getHeaderField("Location") != null) {
                            a = new URL(new URL(a), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        break;
                    }
                    InputStream in = statut >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        ByteArrayOutputStream tampon = new ByteArrayOutputStream();
                        byte[] bloc = new byte[16384];
                        int n;
                        while ((n = in.read(bloc)) > 0) tampon.write(bloc, 0, n);
                        in.close();
                        contenu = Base64.encodeToString(tampon.toByteArray(), Base64.NO_WRAP);
                    }
                } catch (Exception e) {
                    statut = 0;
                    contenu = "";
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__boulotRetour&&window.__boulotRetour('" + id.replaceAll("[^A-Za-z0-9]", "")
                        + "'," + statut + ",'" + contenu + "')";
                surEcran(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void ecranAllume(boolean allume) {
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (allume) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        private void message(String m) {
            if (act == null) return;
            act.runOnUiThread(() -> Toast.makeText(act, m, Toast.LENGTH_SHORT).show());
        }

    /** Reçoit de la page les journées de service à afficher dans le widget (graphiques en PNG). */
    @JavascriptInterface
    public void majWidget(String json) {
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray jours = o.getJSONArray("jours");
                JSONArray avant;
                synchronized (VoieLibreWidget.VERROU) {
                    avant = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
                    File dossier = new File(ctx.getFilesDir(), "widget");
                    File neuf = new File(ctx.getFilesDir(), "widget.neuf");
                    viderDossier(neuf);
                    neuf.mkdirs();
                    // Images de l'export précédent, par journée : reprises si le nouvel export n'en a pas pour une journée
                    java.util.Map<String, File> anciennes = new java.util.HashMap<>();
                    for (int k = 0; k < avant.length(); k++) {
                        JSONObject a = avant.optJSONObject(k);
                        File f = new File(dossier, k + ".png");
                        if (a != null && f.exists()) anciennes.put(a.optString("date") + "#" + a.optInt("seg"), f);
                    }
                    for (int k = 0; k < jours.length(); k++) {
                        JSONObject j = jours.getJSONObject(k);
                        File cible = new File(neuf, k + ".png");
                        if (!ecrirePng(j.optString("png", ""), cible)) {
                            File vieux = anciennes.get(j.optString("date") + "#" + j.optInt("seg"));
                            if (vieux != null) copier(vieux, cible);
                        }
                        j.remove("png");
                    }
                    JSONArray semaines = o.optJSONArray("semaines");
                    if (semaines != null) for (int k = 0; k < semaines.length(); k++) {
                        JSONObject m = semaines.getJSONObject(k);
                        ecrirePng(m.optString("png", ""), new File(neuf, "s" + k + ".png"));
                        m.remove("png");
                    }
                    JSONArray mois = o.optJSONArray("mois");
                    if (mois != null) for (int k = 0; k < mois.length(); k++) {
                        JSONObject m = mois.getJSONObject(k);
                        ecrirePng(m.optString("png", ""), new File(neuf, "m" + k + ".png"));
                        m.remove("png");
                    }
                    // Bascule d'un coup : anciennes images remplacées par les nouvelles, puis données
                    viderDossier(dossier);
                    if (!neuf.renameTo(dossier)) { dossier.mkdirs(); File[] l = neuf.listFiles(); if (l != null) for (File f : l) copier(f, new File(dossier, f.getName())); viderDossier(neuf); }
                    ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0).edit()
                            .putString("donnees", o.toString()).commit();
                }
                VoieLibreWidget.majTous(ctx);
                VoieLibreWidget.majApres(ctx, 5000);   // second passage une fois la synchro retombée
                Notifs.comparer(ctx, avant, jours);
                Notifs.programmer(ctx);
            } catch (Exception ignore) { }
        }).start();
    }

    /** Réglages des notifications (Paramètres) ; demande l'autorisation d'Android si besoin. */
    @JavascriptInterface
    public void reglerNotifs(String json) {
        Notifs.regler(ctx, json);
        boolean une = json != null && json.contains("true");
        if (une && act != null && Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77));
    }

    /** Mêmes réglages, repris à l'ouverture de l'appli, sans redemander l'autorisation. */
    @JavascriptInterface
    public void reprendreNotifs(String json) { Notifs.regler(ctx, json); }

    @JavascriptInterface
    public boolean notifsAutorisees() { return Notifs.autorisees(ctx); }

    /** Bandeau persistant du départ des trains : autorisation « notifications en plein écran » accordée ? */
    @JavascriptInterface
    public boolean pleinEcranAutorise() { return Notifs.pleinEcranAutorise(ctx); }

    /** Ouvre le réglage Android de cette autorisation (Android 14+). */
    @JavascriptInterface
    public void reglerPleinEcran() {
        if (Build.VERSION.SDK_INT < 34 || act == null) return;
        try {
            act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception e) {
            try { act.startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())); } catch (Exception ignore) { }
        }
    }

    /** Présentation (dernière page) : demande d'autorisation d'Android, ou l'écran des notifications si elle a déjà été refusée. */
    @JavascriptInterface
    public void demanderNotifs() {
        if (act == null) return;
        surEcran(() -> {
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !Notifs.prefs(ctx).getBoolean("permDemandee", false)) {
                Notifs.prefs(ctx).edit().putBoolean("permDemandee", true).apply();
                act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
            } else if (!Notifs.autorisees(ctx)) {
                ouvrirReglagesNotifs();
            }
        });
    }

    /** Bouton « Autoriser les notifications » (Paramètres) : ouvre directement l'écran des notifications de l'appli. */
    @JavascriptInterface
    public void ouvrirReglagesNotifs() {
        if (act == null) return;
        surEcran(() -> {
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName()));
            } catch (Exception e) {
                // repli : fiche de l'appli dans les réglages d'Android
                try { act.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()))); } catch (Exception ignore) { }
            }
        });
    }

    /** Délais et sonneries du bouton ⏰, repris par le bouton de la notification « Réveil oublié ». */
    @JavascriptInterface
    public void reglerReveils(String json) {
        Notifs.prefs(ctx).edit().putString("reveils", json == null ? "{}" : json).apply();
    }

    /** Dernier affichage raté du widget, s'il date de moins de 7 jours (sinon chaîne vide). */
    @JavascriptInterface
    public String diagWidget() {
        android.content.SharedPreferences p = ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0);
        if (System.currentTimeMillis() - p.getLong("echecQuand", 0) > 7 * 86400000L) return "";
        return p.getString("echec", "");
    }

    /**
     * Crée un ou plusieurs réveils dans l'appli Horloge du téléphone : [{"h":5,"m":52,"msg":"…"}, …].
     * Ils sont envoyés l'un après l'autre, espacés, pour laisser l'Horloge traiter chacun.
     * Renvoie false si aucune appli Horloge ne sait le faire.
     */
    @JavascriptInterface
    public boolean reveils(String json) {
        if (act == null) return false;
        try {
            JSONArray l = new JSONArray(json);
            if (l.length() == 0) return false;
            if (new Intent(android.provider.AlarmClock.ACTION_SET_ALARM).resolveActivity(act.getPackageManager()) == null)
                return false;
            for (int k = 0; k < l.length(); k++) {
                JSONObject r = l.getJSONObject(k);
                final Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                        .putExtra(android.provider.AlarmClock.EXTRA_HOUR, r.getInt("h"))
                        .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, r.getInt("m"))
                        .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, r.optString("msg", "Voie libre"))
                        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true);
                // Sonnerie : « silence », une adresse content://, ou rien (sonnerie par défaut de l'Horloge)
                String son = r.optString("son", "");
                if (son.equals("silence")) i.putExtra(android.provider.AlarmClock.EXTRA_RINGTONE, android.provider.AlarmClock.VALUE_RINGTONE_SILENT);
                else if (son.startsWith("content:")) i.putExtra(android.provider.AlarmClock.EXTRA_RINGTONE, son);
                if (r.has("vib")) i.putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, r.optBoolean("vib", true));
                principal.postDelayed(() -> {
                    try { act.startActivity(i); } catch (Exception e) { message("Réveil impossible à régler"); }
                }, k * 1500L);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ouvre le sélecteur de sonneries d'alarme d'Android ; le choix revient par window.__boulotSonnerie(cible, uri, titre). */
    @JavascriptInterface
    public void choisirSonnerie(String cible, String actuelle) {
        if (act == null) return;
        act.runOnUiThread(() -> {
            Intent i = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonnerie du réveil")
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
            if (actuelle != null && actuelle.startsWith("content:"))
                i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(actuelle));
            MainActivity.sonnerieCible = cible;
            try { act.startActivityForResult(i, MainActivity.REQ_SONNERIE); }
            catch (Exception e) { MainActivity.sonnerieCible = null; message("Sélecteur de sonneries indisponible"); }
        });
    }

    private static boolean ecrirePng(String png, File cible) {
        if (png == null || png.isEmpty()) return false;
        try (FileOutputStream out = new FileOutputStream(cible)) {
            out.write(Base64.decode(png, Base64.DEFAULT));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copier(File de, File vers) {
        try (InputStream in = new java.io.FileInputStream(de); FileOutputStream out = new FileOutputStream(vers)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Exception ignore) { }
    }

    private static void viderDossier(File d) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) f.delete();
        d.delete();
    }

    /** La page signale la fin d'une synchro demandée par le widget. */
    @JavascriptInterface
    public void finSync(boolean ok) {
        surEcran(() -> { if (finSynchro != null) finSynchro.run(); });
    }
}
