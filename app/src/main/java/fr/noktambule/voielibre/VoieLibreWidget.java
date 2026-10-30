package fr.noktambule.voielibre;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Widget « Voie libre » : prochaine reprise (ou fin de service si on est en service) avec compte à rebours,
 * graphique de la journée (ou des deux prochaines si la place le permet) ou vue Semaines,
 * avec ‹ › pour naviguer, 📅/📊 pour changer de vue et ⟳ pour resynchroniser avec Graou.
 * Les journées et leurs images sont préparées par la page (rubrique « Voir ma journée »).
 * Suit le thème clair ou sombre du téléphone.
 */
public class VoieLibreWidget extends AppWidgetProvider {

    static final String PREFS = "widget";
    /** Verrou commun : jamais d'affichage pendant que les images du widget sont réécrites. */
    static final Object VERROU = new Object();
    private static final String PREC = "fr.noktambule.voielibre.PREC";
    private static final String SUIV = "fr.noktambule.voielibre.SUIV";
    private static final String AUTO = "fr.noktambule.voielibre.AUTO";
    private static final String TIC = "fr.noktambule.voielibre.TIC";
    private static final String VUE = "fr.noktambule.voielibre.VUE";
    private static final String MINUTE = "fr.noktambule.voielibre.MINUTE";
    private static final String RETOUR = "fr.noktambule.voielibre.RETOUR";
    private static final String SYNC = "fr.noktambule.voielibre.SYNC";
    /** Après avoir navigué avec ‹ ›, retour tout seul à la journée en cours (ou à la semaine en cours) au bout de 5 min. */
    private static final long DELAI_RETOUR = 5 * 60 * 1000L;
    /** Rafraîchissement de l'affichage (trait « maintenant », ligne d'état) : toutes les 5 min, sans réveiller le téléphone. */
    private static final long PERIODE = 5 * 60 * 1000L;

    // Couleurs {clair, sombre}
    private static final int[] BLEU = {0xFF2F5FA8, 0xFF8AB4F8};
    private static final int[] BLEU_PALE = {0x402F5FA8, 0x408AB4F8};
    private static final int[] ENCRE = {0xFF14243A, 0xFFE6EAF0};
    private static final int[] GRIS = {0xFF8A8A8A, 0xFF9AA0A6};
    private static final int[] COMPTE = {0xFF2E7D4F, 0xFF7FE0A0};
    private static final int[] ROUGE = {0xFFD7262E, 0xFFFF8A80};
    private static final int[] ORANGE = {0xFFB85C00, 0xFFFFB74D};
    /** Rappel affiché en bas à gauche pendant le service (la copie Graou n'est pas la référence). */
    private static final String PACIFIC = "⚠\uFE0F Seul Pacific fait foi";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        majTous(ctx);
        programmer(ctx);   // aussi appelé après un redémarrage du téléphone
        SyncWorker.programmer(ctx);
    }

    @Override
    public void onEnabled(Context ctx) { programmer(ctx); }

    /** Le widget a été redimensionné : le contenu suit sa nouvelle taille. */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        majTous(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) { am.cancel(diffusion(ctx, TIC, 6)); am.cancel(diffusion(ctx, TIC, 8)); am.cancel(diffusion(ctx, MINUTE, 9)); am.cancel(diffusion(ctx, RETOUR, 10)); am.cancel(diffusion(ctx, TIC, 11)); }
    }

    /** Alarme répétée inexacte et sans réveil : elle ne tourne que lorsque le téléphone est déjà actif. */
    private static void programmer(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + PERIODE,
                PERIODE, diffusion(ctx, TIC, 6));
    }

    /** Redessine juste après la prochaine bascule (prise ou fin de service), sans réveiller le téléphone. */
    private static void programmerBascule(Context ctx, long t) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (t <= 0) { am.cancel(diffusion(ctx, TIC, 8)); return; }
        am.set(AlarmManager.RTC, t + 2000, diffusion(ctx, TIC, 8));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String a = intent.getAction();
        if (a == null) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        boolean modeMois = "mois".equals(p.getString("vue", "jour"));
        if (a.equals(VUE)) {
            p.edit().putString("vue", modeMois ? "jour" : "mois").remove("moisSel").apply();
            majTous(ctx);
        } else if (modeMois && (a.equals(PREC) || a.equals(SUIV))) {
            int n = semaines(p).length();
            if (n == 0) return;
            int i = Math.max(0, Math.min(premiereMax(ctx, n), p.getInt("moisSel", 0) + (a.equals(SUIV) ? 1 : -1)));
            p.edit().putInt("moisSel", i).apply();
            navigation(ctx, p);
            majTous(ctx);
        } else if (a.equals(PREC) || a.equals(SUIV)) {
            JSONArray jours = jours(p);
            if (jours.length() == 0) return;
            int i = indexAffiche(p, jours) + (a.equals(SUIV) ? 1 : -1);
            i = Math.max(0, Math.min(jours.length() - 1, i));
            p.edit().putString("sel", cleJour(jours.optJSONObject(i))).apply();
            navigation(ctx, p);
            majTous(ctx);
        } else if (a.equals(AUTO) || a.equals(RETOUR)) {
            p.edit().remove("sel").remove("moisSel").remove("navDepuis").apply();
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null && a.equals(AUTO)) am.cancel(diffusion(ctx, RETOUR, 10));
            majTous(ctx);
        } else if (a.equals(TIC)) {
            majTous(ctx);
        } else if (a.equals(MINUTE)) {
            minute(ctx);
        } else if (a.equals(SYNC)) {
            SyncWorker.synchroManuelle(ctx);
        }
    }

    // ---------- Données ----------

    private static JSONArray tableau(SharedPreferences p, String cle) {
        try {
            JSONArray m = new JSONObject(p.getString("donnees", "{}")).optJSONArray(cle);
            return m != null ? m : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONArray semaines(SharedPreferences p) { return tableau(p, "semaines"); }

    static JSONArray jours(SharedPreferences p) { return tableau(p, "jours"); }

    private static String cleJour(JSONObject j) {
        return j == null ? "" : j.optString("date") + "#" + j.optInt("seg");
    }

    /** Journée par défaut : celle en cours, sinon la prochaine. */
    private static int indexAuto(JSONArray jours) {
        long now = System.currentTimeMillis();
        for (int k = 0; k < jours.length(); k++) {
            JSONObject j = jours.optJSONObject(k);
            if (j != null && j.optLong("fin") > now) return k;
        }
        return Math.max(0, jours.length() - 1);
    }

    private static int indexAffiche(SharedPreferences p, JSONArray jours) {
        String sel = p.getString("sel", null);
        if (sel != null) {
            for (int k = 0; k < jours.length(); k++) if (sel.equals(cleJour(jours.optJSONObject(k)))) return k;
        }
        return indexAuto(jours);
    }

    // ---------- Textes ----------

    static String hm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(t));
    }

    static String jourTexte(long t) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long diff = Math.round((debutJour(t) - c.getTimeInMillis()) / 86400000.0);
        if (diff == 0) return "aujourd’hui";
        if (diff == 1) return "demain";
        if (diff == -1) return "hier";
        return new SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(new Date(t));
    }

    private static long debutJour(long t) {
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(t);
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    private static String depuis(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 2) return "à l’instant";
        if (m < 60) return "il y a " + m + " min";
        if (m < 48 * 60) return "il y a " + (m / 60) + " h";
        return "il y a " + (m / 1440) + " j";
    }

    private static String court(long t) {
        return new SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(t));
    }

    private static String dateIso(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(t));
    }

    // ---------- Retour automatique ----------

    /** Note l'heure de la dernière navigation et (re)programme le retour 5 min plus tard, sans réveiller le téléphone. */
    private static void navigation(Context ctx, SharedPreferences p) {
        p.edit().putLong("navDepuis", System.currentTimeMillis()).apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = diffusion(ctx, RETOUR, 10);
        long quand = SystemClock.elapsedRealtime() + DELAI_RETOUR;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExact(AlarmManager.ELAPSED_REALTIME, quand, pi);
            else am.set(AlarmManager.ELAPSED_REALTIME, quand, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.ELAPSED_REALTIME, quand, pi);
        }
    }

    /** Filet de sécurité si l'alarme n'est pas passée : le délai est vérifié à chaque affichage. */
    private static void retourSiExpire(SharedPreferences p) {
        long t = p.getLong("navDepuis", 0);
        if (t > 0 && System.currentTimeMillis() - t >= DELAI_RETOUR)
            p.edit().remove("sel").remove("moisSel").remove("navDepuis").apply();
    }

    // ---------- Compte à rebours (à la minute) ----------

    /** « dans 2 h 16 », « dans 16 min » : arrondi à la minute supérieure, comme un départ. */
    private static String compteTexte(long reste) {
        long m = (reste + 59999) / 60000;
        if (m < 60) return "dans " + m + " min";
        return "dans " + (m / 60) + " h " + String.format(Locale.FRANCE, "%02d", m % 60);
    }

    /**
     * Prochain changement de minute du compte à rebours. Alarme sans réveil : quand l'écran est éteint,
     * rien ne tourne ; elle est rattrapée dès que le téléphone se rallume.
     */
    private static void programmerMinute(Context ctx, long cible) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = diffusion(ctx, MINUTE, 9);
        long reste = cible - System.currentTimeMillis();
        if (cible <= 0 || reste <= 0 || reste >= 24 * 3600 * 1000L) { am.cancel(pi); return; }
        long delai = reste % 60000;
        if (delai < 500) delai += 60000;
        long quand = SystemClock.elapsedRealtime() + delai + 300;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExact(AlarmManager.ELAPSED_REALTIME, quand, pi);
            else am.set(AlarmManager.ELAPSED_REALTIME, quand, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.ELAPSED_REALTIME, quand, pi);
        }
    }

    /**
     * Chaque minute du compte à rebours : tout le widget est redessiné, graphique compris. Une mise à jour partielle
     * (texte seul) laissait parfois disparaître le graphique quand l'écran d'accueil se redessinait.
     * L'alarme ne réveille pas le téléphone : rien ne tourne écran éteint.
     */
    private static void minute(Context ctx) {
        majTous(ctx);   // reprogramme aussi la minute suivante (programmerMinute, dans vue())
    }

    // ---------- Changement d'heure ----------

    /** « ⚠ Changement d'heure la nuit prochaine (3 h → 2 h) » la veille, « … la nuit dernière … » le jour même, sinon null. */
    static String alerteHeure() {
        try {
            java.time.ZoneId z = java.time.ZoneId.systemDefault();
            java.time.LocalDate auj = java.time.LocalDate.now(z);
            java.time.Instant depuis = auj.minusDays(1).atStartOfDay(z).toInstant();
            java.time.zone.ZoneOffsetTransition t = z.getRules().nextTransition(depuis);
            if (t == null) return null;
            java.time.LocalDate jour = t.getDateTimeBefore().toLocalDate();
            String quand = jour.equals(auj.plusDays(1)) ? "la nuit prochaine" : jour.equals(auj) ? "la nuit dernière" : null;
            if (quand == null) return null;
            return "⚠\uFE0F Changement d'heure " + quand;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Même avertissement, mais pour la journée affichée dans le widget (date et horaires de la journée) :
     * « pendant cette journée » si elle passe par le changement, « la nuit suivante » la veille,
     * « la nuit précédente » le jour même ; sinon null.
     */
    static String alerteHeure(String date, long debut, long fin) {
        try {
            java.time.ZoneId z = java.time.ZoneId.systemDefault();
            java.time.LocalDate jour = java.time.LocalDate.parse(date);
            java.time.zone.ZoneOffsetTransition t = z.getRules().nextTransition(jour.minusDays(1).atStartOfDay(z).toInstant());
            if (t == null) return null;
            java.time.LocalDate jt = t.getDateTimeBefore().toLocalDate();
            long inst = t.getInstant().toEpochMilli();
            String quand;
            if (debut > 0 && debut <= inst && inst < fin) quand = "pendant cette journée";
            else if (jt.equals(jour.plusDays(1))) quand = "la nuit suivante";
            else if (jt.equals(jour)) quand = "la nuit précédente";
            else return null;
            return "⚠\uFE0F Changement d'heure " + quand;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ligne du bas, à gauche : l'alerte de changement d'heure en priorité ;
     * sinon, pendant le service, le rappel « Seul Pacific fait foi ».
     */
    private static void poserAlerte(RemoteViews v, String alerte, boolean enService, boolean nuit) {
        String texte = alerte != null ? alerte : enService ? PACIFIC : null;
        v.setTextViewText(R.id.alerte, texte == null ? "" : texte);
        v.setViewVisibility(R.id.alerte, texte == null ? View.GONE : View.VISIBLE);
        couleur(v, R.id.alerte, alerte != null ? ROUGE : ORANGE, nuit);
    }

    // ---------- Thème ----------

    /** Thème réglé dans Paramètres : « auto » (celui de l'appareil), « clair » ou « sombre ». */
    private static String theme(Context ctx) { return ctx.getSharedPreferences(PREFS, 0).getString("theme", "auto"); }

    static boolean nuit(Context ctx) {
        String t = theme(ctx);
        if (t.equals("clair")) return false;
        if (t.equals("sombre")) return true;
        return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Couleur de texte qui suit le thème ; sur Android 12+, le lanceur bascule tout seul. */
    /** Thème imposé (clair ou sombre) : couleur fixe ; sinon, sur Android 12+, le lanceur suit le thème de l'appareil. */
    private static boolean themeImpose = false;

    private static void couleur(RemoteViews v, int id, int[] c, boolean nuit) {
        if (Build.VERSION.SDK_INT >= 31 && !themeImpose) v.setColorInt(id, "setTextColor", c[0], c[1]);
        else v.setTextColor(id, nuit ? c[1] : c[0]);
    }

    /** Version sombre d'une image claire : inversion douce puis rotation des teintes (les couleurs restent reconnaissables). */
    /**
     * Images envoyées au widget en couleurs 16 bits : deux fois plus légères pour le passage vers l'écran d'accueil
     * (canal d'Android limité à environ 1 Mo), même nombre de pixels, donc même netteté.
     */
    private static final Bitmap.Config CONFIG = Bitmap.Config.RGB_565;
    private static final Paint LISSE = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** Réduction de secours (1 = taille normale), utilisée seulement si un premier envoi a échoué. */
    private static double reduction = 1.0;

    private static Bitmap aEchelle(Bitmap b) {
        if (b == null || reduction >= 1.0) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * reduction)),
                Math.max(1, (int) (b.getHeight() * reduction)), true);
    }

    /** Garde la dernière raison d'un affichage raté (consultable dans Paramètres). */
    static void noterEchec(Context ctx, String raison) {
        String t = new java.text.SimpleDateFormat("d/MM HH:mm", java.util.Locale.FRANCE).format(new java.util.Date());
        ctx.getSharedPreferences(PREFS, 0).edit().putString("echec", t + " · " + raison).putLong("echecQuand", System.currentTimeMillis()).apply();
    }

    /** Redessine le widget un peu plus tard (sans réveiller le téléphone), une fois la synchro retombée. */
    static void majApres(Context ctx, long delai) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delai, diffusion(ctx, TIC, 11));
    }

    private static Bitmap assombrir(Bitmap b) {
        float k = -0.8f, o = 229.5f;
        ColorMatrix m = new ColorMatrix(new float[]{
                k * -0.574f, k * 1.430f, k * 0.144f, 0, o,
                k * 0.426f, k * 0.430f, k * 0.144f, 0, o,
                k * 0.426f, k * 1.430f, k * -0.856f, 0, o,
                0, 0, 0, 1, 0});
        Bitmap r = Bitmap.createBitmap(b.getWidth(), b.getHeight(), CONFIG);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(r).drawBitmap(b, 0, 0, p);
        return r;
    }

    // ---------- Taille du widget ----------

    /** Taille actuelle du widget en dp {largeur, hauteur}, selon l'orientation de l'écran. */
    /**
     * Taille du widget sur l'écran d'accueil en portrait (largeur MIN_WIDTH, hauteur MAX_HEIGHT), quelle que soit
     * l'orientation du moment : un redessin fait téléphone tourné en paysage (sortie de poche, vidéo…) ne doit pas
     * conclure à un widget trop petit et masquer le graphique.
     */
    private static int[] tailleDp(Context ctx, AppWidgetManager mgr, int id) {
        Bundle o = mgr.getAppWidgetOptions(id);
        int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int h = o == null ? 0 : Math.max(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT));
        return new int[]{w > 0 ? w : 300, h > 0 ? h : 180};
    }

    /** Place en dp {largeur, hauteur} laissée à l'image une fois les boutons, la ligne d'état et la date de màj posés. */
    private static double[] placeImage(Context ctx, AppWidgetManager mgr, int id, String statut, boolean compte) {
        int[] t = tailleDp(ctx, mgr, id);
        double larg = t[0] - 12;
        // Ligne d'état comptée sur deux lignes dans tous les cas : l'affichage ne doit pas changer
        // selon la longueur du texte (« Mise à jour… », compte à rebours affiché ou non…)
        return new double[]{larg, t[1] - 12 - 40 - 19 * 2 - 15};
    }

    /** Combien de blocs de rapport hauteur/largeur donné tiennent (on tolère une légère réduction). */
    private static int combien(double[] place, double rapport, double entete, double tolerance) {
        double dispo = place[1] - place[0] * entete;
        return (int) Math.floor(dispo / (place[0] * rapport) + tolerance);
    }

    // ---------- Vue semaines ----------

    /** Hauteur (en px) de la ligne « L M M J V S D » dessinée au-dessus des bandes de semaines. */
    private static final int ENTETE = 36;

    private static Bitmap image(Context ctx, String nom) {
        return BitmapFactory.decodeFile(new File(new File(ctx.getFilesDir(), "widget"), nom).getAbsolutePath());
    }

    private static int nbSemaines(Context ctx, AppWidgetManager mgr, int id, int total, String statut, boolean compte) {
        if (total <= 0) return 0;
        Bitmap b = image(ctx, "s0.png");
        double rapport = b != null ? (double) b.getHeight() / b.getWidth() : 116.0 / 720;
        int n = combien(placeImage(ctx, mgr, id, statut, compte), rapport, ENTETE / 720.0, 0.2);
        return Math.max(1, Math.min(total, n));
    }

    /** Première semaine affichable au plus tard, pour le plus petit des widgets posés. */
    private static int premiereMax(Context ctx, int total) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, VoieLibreWidget.class));
        int nMin = total;
        if (ids != null) for (int id : ids) nMin = Math.min(nMin, nbSemaines(ctx, mgr, id, total, null, false));
        return Math.max(0, total - Math.max(1, nMin));
    }

    /** Assemble la ligne des jours et les bandes de semaines [debut, debut + n[ en une seule image. */
    private static Bitmap grilleSemaines(Context ctx, int debut, int n) {
        Bitmap r = grilleBrute(ctx, debut, n);
        return r == null ? null : aEchelle(r);
    }

    /** Semaines debut … debut+n-1 assemblées sous la ligne L M M J V S D, à taille réelle. */
    private static Bitmap grilleBrute(Context ctx, int debut, int n) {
        Bitmap[] b = new Bitmap[n];
        int larg = 0, haut = ENTETE;
        for (int k = 0; k < n; k++) {
            b[k] = image(ctx, "s" + (debut + k) + ".png");
            if (b[k] == null) return null;
            larg = Math.max(larg, b[k].getWidth());
            haut += b[k].getHeight();
        }
        Bitmap r = Bitmap.createBitmap(larg, haut, CONFIG);
        Canvas c = new Canvas(r);
        c.drawColor(0xFFFFFFFF);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFF8A8A8A);
        t.setTextSize(20);
        t.setTextAlign(Paint.Align.CENTER);
        String jours = "LMMJVSD";
        for (int i = 0; i < 7; i++) c.drawText(String.valueOf(jours.charAt(i)), larg / 7f * (i + 0.5f), 24, t);
        int y = ENTETE;
        for (Bitmap x : b) { c.drawBitmap(x, 0, y, LISSE); y += x.getHeight(); }
        return r;
    }

    // ---------- Vue journée ----------

    /** Hauteur (en px) de l'intitulé dessiné au-dessus de la deuxième journée. */
    private static final int LIBELLE = 40;

    private static long minuitDe(JSONObject j) {
        try {
            String[] t = j.optString("date").split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(t[0]), Integer.parseInt(t[1]) - 1, Integer.parseInt(t[2]), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return debutJour(j.optLong("debut"));
        }
    }

    /** Trait « maintenant », comme dans Graou, si l'heure actuelle tombe dans le graphique. */
    private static void traitMaintenant(Canvas cv, JSONObject j, float y0, float hauteur, boolean nuit) {
        double echelle = j.optDouble("echelle", 2);
        // minutes « à l'horloge » depuis minuit du jour de la journée, comme l'axe du graphique (heures HH:MM) :
        // un jour de changement d'heure compte 23 ou 25 h, le temps écoulé depuis minuit ne convient donc pas
        double m;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long jours = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(j.optString("date")), now.toLocalDate());
            m = jours * 1440 + now.getHour() * 60 + now.getMinute() + now.getSecond() / 60.0;
        } catch (Exception e) {
            m = (System.currentTimeMillis() - minuitDe(j)) / 60000.0;
        }
        double t0 = j.optDouble("t0"), t1 = j.optDouble("t1");
        if (!(m >= t0 && m <= t1)) return;
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(nuit ? 0xFF9FA8F0 : 0xFF5C6BC0);
        pt.setStrokeWidth((float) (2.2 * echelle));
        float x = (float) ((j.optDouble("gx") + (m - t0) / 60.0 * j.optDouble("pxh")) * echelle);
        cv.drawLine(x, y0 + (float) (13 * echelle), x, y0 + hauteur - (float) (3 * echelle), pt);
    }

    private static String titreJour(JSONObject j) {
        return j.optString("code") + (j.optBoolean("dec") ? (j.optInt("seg") == 0 ? " · aller découché" : " · retour découché") : "");
    }

    /** Une ou deux journées empilées ; la deuxième porte son intitulé. */
    /**
     * La journée affichée, et dessous (widget assez haut) nbSem semaines à partir de la semaine semDebut,
     * séparées par un petit intervalle.
     */
    private static Bitmap journees(Context ctx, JSONArray jours, int i, int semDebut, int nbSem, boolean nuit) {
        Bitmap a = image(ctx, i + ".png");
        if (a == null) return null;
        Bitmap b = nbSem > 0 ? grilleBrute(ctx, semDebut, nbSem) : null;
        int larg = Math.max(a.getWidth(), b != null ? b.getWidth() : 0);
        int haut = a.getHeight() + (b != null ? ECART_SEMAINES + b.getHeight() : 0);
        Bitmap r = Bitmap.createBitmap(larg, haut, CONFIG);
        Canvas c = new Canvas(r);
        c.drawColor(0xFFFFFFFF);
        c.drawBitmap(a, 0, 0, LISSE);
        if (b != null) c.drawBitmap(b, 0, a.getHeight() + ECART_SEMAINES, LISSE);
        if (nuit) { r = assombrir(r); c = new Canvas(r); }
        traitMaintenant(c, jours.optJSONObject(i), 0, a.getHeight(), nuit);
        return aEchelle(r);
    }

    /** Espace entre la journée et les semaines dessous. */
    private static final int ECART_SEMAINES = 16;

    /** Semaine (index dans les semaines exportées) qui contient la date, bornée aux semaines disponibles. */
    private static int semaineDe(JSONArray ss, long quand) {
        int idx = 0;
        for (int k = 0; k < ss.length(); k++) {
            JSONObject s = ss.optJSONObject(k);
            if (s != null && s.optLong("lundi") <= quand) idx = k;
        }
        return idx;
    }

    // ---------- Affichage ----------

    static void majTous(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, VoieLibreWidget.class));
        if (ids == null || ids.length == 0) return;
        synchronized (VERROU) {
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, vue(ctx, mgr, id));
                } catch (Exception e) {
                    // Envoi refusé (image trop lourde pour le canal d'Android, le plus souvent) :
                    // on le note, on réessaie tout de suite avec une image réduite, puis à taille normale un peu plus tard
                    noterEchec(ctx, e.getClass().getSimpleName() + (e.getMessage() != null ? " : " + e.getMessage() : ""));
                    reduction = 0.6;
                    try { mgr.updateAppWidget(id, vue(ctx, mgr, id)); } catch (Exception e2) {
                        noterEchec(ctx, "2e essai : " + e2.getClass().getSimpleName());
                    } finally { reduction = 1.0; }
                    majApres(ctx, 20000);
                }
            }
        }
    }

    private static PendingIntent diffusion(Context ctx, String action, int code) {
        Intent i = new Intent(ctx, VoieLibreWidget.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Ouvre Voie libre sur « Voir ma journée », directement au jour (ou à la semaine) affiché. */
    private static PendingIntent ouvrirJournee(Context ctx, int code, String date, boolean semaines) {
        Intent i = new Intent(ctx, MainActivity.class).putExtra("rubrique", "journee")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (date != null) i.putExtra("date", date);
        if (semaines) i.putExtra("vue", "mois");
        return PendingIntent.getActivity(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static RemoteViews vue(Context ctx, AppWidgetManager mgr, int id) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        retourSiExpire(p);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        boolean nuit = nuit(ctx);
        themeImpose = !theme(ctx).equals("auto");
        // fond : thème imposé (clair ou sombre), sinon celui de l'appareil (fond_widget, version jour/nuit)
        v.setImageViewResource(R.id.fond, themeImpose ? (nuit ? R.drawable.fond_sombre : R.drawable.fond_clair) : R.drawable.fond_widget);
        couleur(v, R.id.titre, BLEU, nuit);
        couleur(v, R.id.sousTitre, GRIS, nuit);
        couleur(v, R.id.statut, ENCRE, nuit);
        couleur(v, R.id.compte, COMPTE, nuit);
        couleur(v, R.id.rafraichir, ENCRE, nuit);
        couleur(v, R.id.maj, GRIS, nuit);
        couleur(v, R.id.alerte, ROUGE, nuit);
        poserAlerte(v, alerteHeure(), false, nuit);   // d'après la date du jour ; en vue Journée, remplacé par la journée affichée

        v.setOnClickPendingIntent(R.id.prec, diffusion(ctx, PREC, 1));
        v.setOnClickPendingIntent(R.id.suiv, diffusion(ctx, SUIV, 2));
        v.setOnClickPendingIntent(R.id.titreZone, diffusion(ctx, AUTO, 3));
        v.setOnClickPendingIntent(R.id.vue, diffusion(ctx, VUE, 7));
        // ⟳ : synchro en coulisse (aucune fenêtre ouverte, l'écran reste utilisable)
        v.setOnClickPendingIntent(R.id.rafraichir, diffusion(ctx, SYNC, 4));
        PendingIntent pAuj = ouvrirJournee(ctx, 5, null, false);
        v.setOnClickPendingIntent(R.id.statut, pAuj);
        v.setOnClickPendingIntent(R.id.compte, pAuj);

        JSONObject donnees;
        try { donnees = new JSONObject(p.getString("donnees", "{}")); } catch (Exception e) { donnees = new JSONObject(); }
        JSONArray jours = jours(p);
        long now = System.currentTimeMillis();
        boolean enSync = now - p.getLong("syncDepuis", 0) < 70000;
        long graou = donnees.optLong("graou", 0);
        v.setTextViewText(R.id.maj, graou > 0 ? "Graou : màj " + depuis(graou) : "Planning importé");

        if (jours.length() == 0) {
            v.setTextViewText(R.id.titre, "Voie libre");
            v.setTextViewText(R.id.sousTitre, "");
            v.setTextViewText(R.id.statut, enSync ? "Mise à jour…" : "Ouvre Voie libre (ou touche ⟳) pour charger ton planning");
            v.setViewVisibility(R.id.compte, View.GONE);
            v.setViewVisibility(R.id.graphe, View.GONE);
            v.setTextViewText(R.id.maj, "");
            programmerBascule(ctx, 0);
            programmerMinute(ctx, 0);
            return v;
        }

        // Ligne d'état : en service, sinon prochaine reprise (indépendante de la journée affichée),
        // avec compte à rebours tenu à la seconde par le lanceur lui-même (aucun réveil du téléphone)
        String statut = "";
        long cible = 0;
        boolean enService = false;
        for (int k = 0; k < jours.length(); k++) {
            JSONObject j = jours.optJSONObject(k);
            if (j == null) continue;
            long d = j.optLong("debut"), f = j.optLong("fin");
            if (d <= now && now < f) { statut = "En service · fin " + hm(f) + " à " + j.optString("lieuFS"); cible = f; enService = true; break; }
            if (d > now) { statut = "Prochaine reprise " + jourTexte(d) + " · PS " + hm(d) + " à " + j.optString("lieuPS"); cible = d; break; }
        }
        if (statut.isEmpty()) statut = "Aucune reprise connue · touche ⟳";
        if (enSync) statut = "Mise à jour…";
        v.setTextViewText(R.id.statut, statut);
        boolean compte = cible > now && cible - now < 24 * 3600 * 1000L && !enSync;
        if (compte) {
            v.setTextViewText(R.id.compte, compteTexte(cible - now));
            v.setViewVisibility(R.id.compte, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.compte, View.GONE);
        }
        poserAlerte(v, alerteHeure(), enService, nuit);
        p.edit().putLong("cible", compte ? cible : 0).apply();
        programmerBascule(ctx, cible);
        programmerMinute(ctx, compte ? cible : 0);

        // Vue semaines : autant de semaines que la taille du widget le permet
        boolean modeMois = "mois".equals(p.getString("vue", "jour"));
        v.setTextViewText(R.id.vue, modeMois ? "📊" : "📅");
        if (modeMois) {
            JSONArray ss = semaines(p);
            int total = ss.length();
            int n = nbSemaines(ctx, mgr, id, total, statut, compte);
            int im = Math.max(0, Math.min(total - n, p.getInt("moisSel", 0)));
            if (total == 0) {
                v.setTextViewText(R.id.titre, "Semaines");
                v.setTextViewText(R.id.sousTitre, "touche ⟳ pour les préparer");
                v.setViewVisibility(R.id.graphe, View.GONE);
            } else {
                long l0 = ss.optJSONObject(im).optLong("lundi"), l1 = ss.optJSONObject(im + n - 1).optLong("lundi");
                v.setTextViewText(R.id.titre, court(l0) + " → " + court(l1 + 6 * 86400000L));
                v.setTextViewText(R.id.sousTitre, n + (n > 1 ? " semaines" : " semaine"));
                Bitmap bm = grilleSemaines(ctx, im, n);
                if (bm != null) {
                    v.setImageViewBitmap(R.id.graphe, nuit ? assombrir(bm) : bm);
                    v.setViewVisibility(R.id.graphe, View.VISIBLE);
                    v.setOnClickPendingIntent(R.id.graphe, ouvrirJournee(ctx, 1000 + id, dateIso(l0), true));
                } else v.setViewVisibility(R.id.graphe, View.GONE);
            }
            couleur(v, R.id.prec, im > 0 ? BLEU : BLEU_PALE, nuit);
            couleur(v, R.id.suiv, im < total - n ? BLEU : BLEU_PALE, nuit);
            return v;
        }

        // Journée affichée (et la suivante si le widget est assez haut ; rien si trop petit)
        int i = indexAffiche(p, jours);
        JSONObject j = jours.optJSONObject(i);
        long d = j.optLong("debut"), f = j.optLong("fin");
        v.setTextViewText(R.id.titre, titreJour(j));
        v.setTextViewText(R.id.sousTitre, jourTexte(d) + " · " + hm(d) + " → " + hm(f));
        poserAlerte(v, alerteHeure(j.optString("date"), d, f), enService, nuit);
        couleur(v, R.id.prec, i > 0 ? BLEU : BLEU_PALE, nuit);
        couleur(v, R.id.suiv, i < jours.length() - 1 ? BLEU : BLEU_PALE, nuit);

        Bitmap a = image(ctx, i + ".png");
        if (a == null) noterEchec(ctx, "image de la journée " + j.optString("date") + " absente");
        // La journée affichée est toujours dessinée (réduite si la place manque), jamais masquée.
        // Widget agrandi : dessous, les semaines (à partir de celle de la journée affichée), autant qu'il en tient.
        int semDebut = 0, nbSem = 0;
        if (a != null) {
            JSONArray ss = semaines(p);
            Bitmap s0 = ss.length() > 0 ? image(ctx, "s0.png") : null;
            if (s0 != null) {
                double[] place = placeImage(ctx, mgr, id, statut, compte);
                double entete = (a.getHeight() + ECART_SEMAINES + ENTETE) / (double) a.getWidth();
                int k = combien(place, (double) s0.getHeight() / s0.getWidth(), entete, 0.2);
                semDebut = semaineDe(ss, debutJour(d));
                nbSem = Math.max(0, Math.min(k, ss.length() - semDebut));
            }
        }
        Bitmap b = a != null ? journees(ctx, jours, i, semDebut, nbSem, nuit) : null;
        if (a != null && b == null) noterEchec(ctx, "graphique non assemblé (" + j.optString("date") + ")");
        if (b != null) {
            v.setImageViewBitmap(R.id.graphe, b);
            v.setViewVisibility(R.id.graphe, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.graphe, ouvrirJournee(ctx, 1000 + id, j.optString("date"), false));
        } else {
            v.setViewVisibility(R.id.graphe, View.GONE);
        }
        return v;
    }
}
