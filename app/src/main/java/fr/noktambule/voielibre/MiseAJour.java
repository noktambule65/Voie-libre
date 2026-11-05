package fr.noktambule.voielibre;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mises à jour de l'appli depuis les « Releases » GitHub du dépôt.
 *
 * Seule la dernière version officielle est proposée (adresse /releases/latest de l'API GitHub) :
 * les pré-versions, publiées automatiquement à chaque compilation, ne sont jamais vues ici.
 * Une version n'est donc proposée à tout le monde qu'une fois passée de « pré-version » à
 * « dernière version » sur GitHub, après avoir été testée.
 *
 * Vérification au plus une fois par jour (à l'ouverture de l'appli et pendant la synchro en
 * arrière-plan), sans compte ni jeton : GitHub accepte 60 demandes par heure et par adresse.
 * Notification à chaque nouvelle version, puis relances (3 jours, puis tous les 7 jours) tant qu'elle n'est pas installée.
 * Le widget l'indique aussi en bas, hors service (« Mise à jour dispo »).
 */
final class MiseAJour {

    private static final String API = "https://api.github.com/repos/noktambule65/Voie-libre/releases/latest";
    /** Mode testeur (caché : 7 appuis sur le numéro de version) : toutes les versions, pré-versions comprises. */
    private static final String API_TOUTES = "https://api.github.com/repos/noktambule65/Voie-libre/releases?per_page=15";
    private static final String PREFS = "maj";
    private static final long JOUR = 86400000L;
    private static final String CANAL = "mise_a_jour";
    private static final int ID_NOTIF = 6000;
    private static final String TYPE_APK = "application/vnd.android.package-archive";

    static final int OK = 0, A_JOUR = 1, RESEAU = 2, LIMITE = 3, INTROUVABLE = 4;

    private static final Handler PRINCIPAL = new Handler(Looper.getMainLooper());

    private MiseAJour() { }

    /** Version disponible sur GitHub. */
    static final class Info {
        int version;
        String apk, notes, page;
        long taille;
    }

    static final class Resultat {
        int etat;
        Info info;
        Resultat(int etat, Info info) { this.etat = etat; this.info = info; }
    }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, 0); }

    static long versionInstallee(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.getLongVersionCode();
        } catch (Exception e) {
            return Long.MAX_VALUE;   // version inconnue : ne rien proposer
        }
    }

    static String nomVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    // ---------- Mode testeur ----------

    static boolean testeur(Context c) { return prefs(c).getBoolean("testeur", false); }

    /** Active ou désactive le mode testeur ; la prochaine vérification interroge GitHub sans attendre. */
    static void reglerTesteur(Context c, boolean on) {
        prefs(c).edit().putBoolean("testeur", on).remove("quand").remove("version").apply();
        VoieLibreWidget.majTous(c.getApplicationContext());
        new Thread(() -> { try { verifier(c, true); } catch (Exception ignore) { } }, "maj").start();
    }

    // ---------- Vérification ----------

    /** Interroge GitHub. À appeler hors du fil principal. */
    private static Resultat interroger(Context c) {
        HttpURLConnection h = null;
        try {
            boolean toutes = testeur(c);
            h = (HttpURLConnection) new URL(toutes ? API_TOUTES : API).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "VoieLibre-Android");
            int statut = h.getResponseCode();
            // 403 / 429 : trop de demandes depuis cette adresse ; 404 : dépôt privé ou aucune version officielle
            if (statut == 403 || statut == 429) return new Resultat(LIMITE, null);
            if (statut == 404) return new Resultat(INTROUVABLE, null);
            if (statut != 200) return new Resultat(RESEAU, null);
            String corps = lire(h.getInputStream());
            JSONObject o;
            if (toutes) {
                // la version la plus récente, pré-versions comprises (brouillons exclus)
                JSONArray l = new JSONArray(corps);
                o = null;
                for (int k = 0; k < l.length(); k++) {
                    JSONObject r = l.optJSONObject(k);
                    if (r == null || r.optBoolean("draft")) continue;
                    if (o == null || numero(r.optString("tag_name")) > numero(o.optString("tag_name"))) o = r;
                }
                if (o == null) return new Resultat(INTROUVABLE, null);
            } else {
                o = new JSONObject(corps);
                if (o.optBoolean("prerelease") || o.optBoolean("draft")) return new Resultat(INTROUVABLE, null);
            }
            Info i = new Info();
            i.version = numero(o.optString("tag_name"));
            i.notes = o.optString("body", "").trim();
            i.page = o.optString("html_url", "");
            JSONArray fichiers = o.optJSONArray("assets");
            if (fichiers != null) for (int k = 0; k < fichiers.length(); k++) {
                JSONObject f = fichiers.getJSONObject(k);
                if (f.optString("name").toLowerCase().endsWith(".apk")) {
                    i.apk = f.optString("browser_download_url");
                    i.taille = f.optLong("size");
                    break;
                }
            }
            if (i.version <= 0 || i.apk == null || i.apk.isEmpty()) return new Resultat(INTROUVABLE, null);
            return new Resultat(OK, i);
        } catch (Exception e) {
            return new Resultat(RESEAU, null);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static int numero(String tag) {
        String n = tag == null ? "" : tag.replaceAll("[^0-9]", "");
        try { return n.isEmpty() ? 0 : Integer.parseInt(n); } catch (Exception e) { return 0; }
    }

    private static String lire(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[8192];
        int n;
        while ((n = in.read(t)) > 0) {
            // réponse de GitHub : quelques dizaines de Ko ; au-delà de 5 Mo, anomalie → traitée comme une erreur réseau
            if (b.size() + n > 5 * 1024 * 1024) { in.close(); throw new java.io.IOException("réponse trop grosse"); }
            b.write(t, 0, n);
        }
        in.close();
        return b.toString("UTF-8");
    }

    /**
     * Vérifie s'il y a une version plus récente. Sans « forcer », GitHub n'est interrogé qu'une fois
     * par jour au plus ; sinon la réponse de la dernière vérification est réutilisée.
     */
    static Resultat verifier(Context c, boolean forcer) {
        SharedPreferences p = prefs(c);
        long maintenant = System.currentTimeMillis();
        if (forcer || maintenant - p.getLong("quand", 0) > JOUR) {
            Resultat r = interroger(c);
            if (r.etat != RESEAU) {
                SharedPreferences.Editor e = p.edit().putLong("quand", maintenant);
                if (r.etat == OK) {
                    if (p.getInt("version", 0) != r.info.version) PRINCIPAL.post(() -> VoieLibreWidget.majTous(c.getApplicationContext()));
                    e.putInt("version", r.info.version).putString("apk", r.info.apk).putLong("taille", r.info.taille)
                            .putString("notes", r.info.notes).putString("page", r.info.page);
                } else if (r.etat == INTROUVABLE) {
                    e.remove("version");
                }
                e.apply();
            }
            if (r.etat != OK) return r;
        }
        Info i = memorisee(c);
        return i == null ? new Resultat(A_JOUR, null) : new Resultat(OK, i);
    }

    /** Version plus récente déjà repérée, ou null. */
    private static Info memorisee(Context c) {
        SharedPreferences p = prefs(c);
        int v = p.getInt("version", 0);
        if (v <= versionInstallee(c)) return null;
        Info i = new Info();
        i.version = v;
        i.apk = p.getString("apk", "");
        i.taille = p.getLong("taille", 0);
        i.notes = p.getString("notes", "");
        i.page = p.getString("page", "");
        return i.apk.isEmpty() ? null : i;
    }

    /** Une version plus récente que celle installée est connue (pour le widget). */
    static boolean disponible(Context c) {
        try { return memorisee(c) != null; } catch (Exception e) { return false; }
    }

    // ---------- Synchro en arrière-plan : notification ----------

    /** Relances d'une même version, si elle n'est toujours pas installée : 3 jours après la notification, puis tous les 7 jours. */
    private static long delaiRelance(int nb) { return nb <= 1 ? 3 * JOUR : 7 * JOUR; }

    /**
     * Appelé par la synchro en arrière-plan : une notification par nouvelle version, puis des relances
     * (3 jours, puis tous les 7 jours) tant qu'elle n'est pas installée. Une version plus récente repart de zéro.
     */
    static void enArrierePlan(Context c) {
        Resultat r = verifier(c, false);
        if (r.etat != OK || r.info == null) return;   // à jour (ou GitHub injoignable) : rien à signaler
        SharedPreferences p = prefs(c);
        long maintenant = System.currentTimeMillis();
        int notifiee = p.getInt("notifiee", 0), nb = p.getInt("notifNb", 0);
        boolean relance;
        if (notifiee < r.info.version) {
            relance = false;                            // nouvelle version : première notification
        } else if (nb >= 1 && maintenant - p.getLong("notifQuand", 0) >= delaiRelance(nb)) {
            relance = true;                             // même version, toujours pas installée : relance
        } else {
            return;
        }
        if (!Notifs.autorisees(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Mise à jour de Voie libre", NotificationManager.IMPORTANCE_DEFAULT));
        Intent ouvrir = new Intent(c, MainActivity.class).putExtra("maj", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 6000, ouvrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String titre = relance ? "Rappel : mise à jour de Voie libre" : "Mise à jour de Voie libre disponible";
        String texte = relance
                ? "La version 1." + r.info.version + " t'attend toujours (tu as la " + nomVersion(c) + "). Touche pour la télécharger."
                : "La version 1." + r.info.version + " est prête. Touche pour la télécharger.";
        Notification n = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF2E7D32)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(texte))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(ID_NOTIF, n);
        p.edit().putInt("notifiee", r.info.version).putInt("notifNb", relance ? nb + 1 : 1)
                .putLong("notifQuand", maintenant).apply();
    }

    // ---------- Appli ouverte : fenêtre de proposition ----------

    /**
     * À l'ouverture de l'appli : propose la mise à jour s'il y en a une.
     * « demandee » : ouverture depuis la notification, la proposition s'affiche même si elle a été reportée.
     */
    static void aLOuverture(Activity a, boolean demandee) {
        final Context c = a.getApplicationContext();
        new Thread(() -> {
            Resultat r = verifier(c, false);
            if (r.etat != OK || r.info == null) return;
            if (!demandee && System.currentTimeMillis() < prefs(c).getLong("reporteeJusqua", 0)) return;
            PRINCIPAL.post(() -> proposer(a, r.info));
        }, "maj").start();
    }

    /** Bouton « Rechercher une mise à jour » (Paramètres › Aide). */
    static void manuellement(Activity a) {
        final Context c = a.getApplicationContext();
        Toast.makeText(a, "Recherche d'une mise à jour…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Resultat r = verifier(c, true);
            PRINCIPAL.post(() -> {
                if (r.etat == OK && r.info != null) { proposer(a, r.info); return; }
                String m;
                switch (r.etat) {
                    case A_JOUR: m = "Voie libre est à jour (version " + nomVersion(c) + ")"; break;
                    case LIMITE: m = "GitHub refuse la demande pour l'instant : réessaie dans une heure"; break;
                    case INTROUVABLE: m = "Aucune version publiée trouvée sur GitHub"; break;
                    default: m = "Vérification impossible : pas de réseau ?";
                }
                Toast.makeText(a, m, Toast.LENGTH_LONG).show();
            });
        }, "maj").start();
    }

    /** Longueur maximale de la note affichée (la fenêtre défile ; au-delà, coupure en fin de ligne). */
    static final int NOTE_MAX = 3000;

    /**
     * Mise en forme simple de la note de version GitHub (Markdown) pour la fenêtre de mise à jour :
     * **texte** et titres « # » en gras (positions ajoutées à gras), « - » ou « * » en début de ligne → « • »,
     * [texte](lien) → texte. Le texte rendu ne contient plus les signes de mise en page.
     */
    static String miseEnForme(String md, java.util.List<int[]> gras) {
        String src = md.replace("\r\n", "\n").replace('\r', '\n');
        if (src.length() > NOTE_MAX) {
            int fin = src.lastIndexOf('\n', NOTE_MAX);
            src = src.substring(0, fin > NOTE_MAX / 2 ? fin : NOTE_MAX) + "\n…";
        }
        StringBuilder out = new StringBuilder();
        String[] lignes = src.split("\n", -1);
        for (int k = 0; k < lignes.length; k++) {
            String l = lignes[k];
            String t = l.trim();
            boolean titre = false;
            if (t.startsWith("#")) { t = t.replaceFirst("^#+\\s*", ""); titre = true; }
            else if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) t = "• " + t.substring(2).trim();
            else t = l.replaceAll("\\s+$", "");
            t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            int debutLigne = out.length();
            // **gras** (ou __gras__) dans la ligne
            int p = 0;
            while (p < t.length()) {
                int a = t.indexOf("**", p), b = a < 0 ? -1 : t.indexOf("**", a + 2);
                int a2 = t.indexOf("__", p), b2 = a2 < 0 ? -1 : t.indexOf("__", a2 + 2);
                if (b2 >= 0 && (b < 0 || a2 < a)) { a = a2; b = b2; }
                if (a < 0 || b < 0) { out.append(t.substring(p)); break; }
                out.append(t, p, a);
                int s = out.length();
                out.append(t, a + 2, b);
                if (out.length() > s) gras.add(new int[]{s, out.length()});
                p = b + 2;
            }
            if (titre && out.length() > debutLigne) gras.add(new int[]{debutLigne, out.length()});
            if (k < lignes.length - 1) out.append('\n');
        }
        return out.toString().replaceAll("\\s+$", "");   // fin seulement : les positions du gras restent justes
    }

    private static boolean ouverte(Activity a) { return a != null && !a.isFinishing() && !a.isDestroyed(); }

    private static void proposer(Activity a, Info i) {
        if (!ouverte(a)) return;
        // Pas plus d'une proposition automatique toutes les 12 h, quelle que soit la réponse
        prefs(a).edit().putLong("reporteeJusqua", System.currentTimeMillis() + JOUR / 2).apply();
        android.text.SpannableStringBuilder m = new android.text.SpannableStringBuilder();
        m.append("La version 1.").append(String.valueOf(i.version)).append(" de Voie libre est disponible (tu as la ")
                .append(nomVersion(a)).append(").");
        if (i.taille > 0) m.append("\nTéléchargement : ").append(String.valueOf(Math.max(1, Math.round(i.taille / 1048576.0)))).append(" Mo.");
        if (i.notes != null && !i.notes.isEmpty()) {
            m.append("\n\n");
            java.util.List<int[]> gras = new java.util.ArrayList<>();
            String n = miseEnForme(i.notes, gras);
            int debut = m.length();
            m.append(n);
            for (int[] g : gras)
                m.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), debut + g[0], debut + g[1],
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        m.append("\n\nLe navigateur télécharge la nouvelle version : ouvre le fichier pour l'installer. Tes réglages et ton planning sont conservés.");
        new AlertDialog.Builder(a)
                .setTitle("Mise à jour disponible")
                .setMessage(m)
                .setPositiveButton("Télécharger", (d, w) -> ouvrirLien(a, i))
                .setNegativeButton("Plus tard", (d, w) ->
                        prefs(a).edit().putLong("reporteeJusqua", System.currentTimeMillis() + JOUR).apply())
                .show();
    }

    // ---------- Téléchargement : confié au navigateur ----------

    /**
     * Ouvre le lien de l'APK dans le navigateur, qui télécharge et installe comme lors de la première
     * installation. L'appli n'installe rien elle-même : pas d'autorisation « installer des applis »,
     * pas de téléchargement interne (schéma signalé comme dangereux par Play Protect).
     */
    private static void ouvrirLien(Activity a, Info i) {
        if (!ouverte(a)) return;
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(i.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, "Téléchargement dans le navigateur : ouvre ensuite le fichier pour installer", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Aucun navigateur pour ouvrir le lien", Toast.LENGTH_LONG).show();
        }
    }
}
