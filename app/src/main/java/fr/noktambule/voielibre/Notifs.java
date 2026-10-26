package fr.noktambule.voielibre;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Notifications facultatives, activées dans les Paramètres de Voie libre :
 *  - « Planning modifié » : une journée à venir a changé dans Graou (détecté à chaque mise à jour des journées) ;
 *  - « Découché » : la veille d'un aller en découché, à l'heure choisie, avec la ville du découché.
 */
public class Notifs extends BroadcastReceiver {

    static final String PREFS = "notifs";
    private static final String CANAL_MODIF = "modif_bandeau";
    private static final String CANAL_DEC = "decouche_bandeau";
    private static final String ACTION_DEC = "fr.noktambule.voielibre.DECOUCHE";
    private static final String CANAL_HEURE = "heure_bandeau";
    private static final String ACTION_HEURE = "fr.noktambule.voielibre.CHANGEMENT_HEURE";
    private static final String CANAL_TACHE = "taches_bandeau";
    private static final String CANAL_DEPART = "depart_train";
    private static final String ACTION_OK_DEPART = "fr.noktambule.voielibre.OK_DEPART";
    private static final String ACTION_TACHE = "fr.noktambule.voielibre.TACHE";
    private static final String CANAL_REVEIL = "reveil_oublie";
    private static final String ACTION_REVEIL = "fr.noktambule.voielibre.REVEIL_OUBLIE";
    private static final String CANAL_NEIGE = "neige";
    private static final String CANAL_SYNC = "synchro_hs";
    private static final String ACTION_SYNC = "fr.noktambule.voielibre.SYNCHRO_HS";
    private static final int ID_SYNC = 6;
    private static final String ACTION_NEIGE = "fr.noktambule.voielibre.NEIGE";

    // ---------- Réglages ----------

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, 0); }

    /** Réglages envoyés par la page : {"modif":bool, "dec":bool, "heure":18, "ch3":bool, "chVeille":bool, "chHeure":18}. */
    static void regler(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            prefs(ctx).edit()
                    .putBoolean("modif", o.optBoolean("modif"))
                    .putBoolean("dec", o.optBoolean("dec"))
                    .putInt("heure", Math.max(0, Math.min(23, o.optInt("heure", 18))))
                    .putBoolean("ch3", o.optBoolean("ch3"))
                    .putBoolean("chVeille", o.optBoolean("chVeille"))
                    .putInt("chHeure", Math.max(0, Math.min(23, o.optInt("chHeure", 18))))
                    .putBoolean("sync", o.optBoolean("sync", true))
                    .putInt("syncH", Math.max(1, Math.min(168, o.optInt("syncH", 12))))
                    .putBoolean("neige", o.optBoolean("neige", true))
                    .putBoolean("neigeJour", o.optBoolean("neigeJour", true))
                    .putInt("neigeHeure", Math.max(0, Math.min(23, o.optInt("neigeHeure", 18))))
                    .putBoolean("rv", o.optBoolean("reveilOn", true))
                    .putInt("rvHeure", Math.max(0, Math.min(23, o.optInt("reveilHeure", 21))))
                    .putInt("rvDe", Math.max(0, Math.min(23, o.optInt("reveilDe", 3))))
                    .putInt("rvA", Math.max(1, Math.min(24, o.optInt("reveilA", 11))))
                    .apply();
            // fréquence de synchro, thème du widget, portée de « Planning modifié »
            int freq = Math.max(15, Math.min(24 * 60, o.optInt("syncFreq", 120)));
            boolean freqChange = freq != prefs(ctx).getInt("syncFreq", 120);
            prefs(ctx).edit().putInt("syncFreq", freq)
                    .putString("modifPortee", "horaires".equals(o.optString("modifPortee")) ? "horaires" : "tout").apply();
            if (freqChange) SyncWorker.programmer(ctx, true);
            String theme = o.optString("theme", "auto");
            if (!theme.equals("clair") && !theme.equals("sombre")) theme = "auto";
            SharedPreferences w = ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0);
            if (!theme.equals(w.getString("theme", "auto"))) { w.edit().putString("theme", theme).commit(); VoieLibreWidget.majTous(ctx); }
            JSONObject t = o.optJSONObject("taches");
            if (t != null) prefs(ctx).edit().putString("taches", t.toString()).apply();
        } catch (Exception ignore) { }
        programmer(ctx);
    }

    static boolean autorisees(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    private static void canaux(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // Android fige l'importance d'un canal à sa création : pour passer en bandeau (importance haute),
        // les anciens canaux « par défaut » sont supprimés et remplacés par de nouveaux identifiants.
        for (String ancien : new String[]{"modif", "decouche", "heure"}) nm.deleteNotificationChannel(ancien);
        nm.createNotificationChannel(new NotificationChannel(CANAL_MODIF, "Planning modifié", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_DEC, "Découché (la veille)", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_HEURE, "Changement d'heure", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_TACHE, "Avant tâches", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_DEPART, "Départ des trains", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_REVEIL, "Réveil oublié", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_NEIGE, "Neige sur la journée", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CANAL_SYNC, "Synchro Graou en panne", NotificationManager.IMPORTANCE_HIGH));
    }

    private static PendingIntent ouvrir(Context ctx, int code, String rubrique, String cle, String valeur) {
        Intent i = new Intent(ctx, MainActivity.class).putExtra("rubrique", rubrique)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (cle != null && valeur != null) i.putExtra(cle, valeur);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Tête de signal au feu rouge, en couleur, affichée à droite de la notification (null si impossible). */
    private static android.graphics.Bitmap grandeIcone(Context ctx) {
        try {
            android.graphics.drawable.Drawable d = ctx.getDrawable(R.drawable.ic_notif_grand);
            if (d == null) return null;
            int t = Math.round(64 * ctx.getResources().getDisplayMetrics().density);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(t, t, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, t, t);
            d.draw(c);
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private static void montrer(Context ctx, int id, String canal, String titre, String texte, String detail, PendingIntent pi,
                                Notification.Action... actions) {
        if (!autorisees(ctx)) return;
        canaux(ctx);
        Notification.Builder b = new Notification.Builder(ctx, canal)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFE53935)          // rouge signal, bien visible en clair comme en sombre
                .setLargeIcon(grandeIcone(ctx))
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(detail != null ? detail : texte))
                .setContentIntent(pi)
                .setAutoCancel(true);
        for (Notification.Action a : actions) b.addAction(a);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, b.build());
    }

    // ---------- Planning modifié ----------

    private static String jourLong(String date) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(date);
            String t = new SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(d);
            return Character.toUpperCase(t.charAt(0)) + t.substring(1);
        } catch (Exception e) {
            return date;
        }
    }

    private static String resume(JSONObject j) {
        return j.optString("code") + " " + VoieLibreWidget.hm(j.optLong("debut")) + "–" + VoieLibreWidget.hm(j.optLong("fin"));
    }

    private static Map<String, JSONObject> parCle(JSONArray a) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject j = a.optJSONObject(k);
            if (j != null) m.put(j.optString("date") + "#" + j.optInt("seg"), j);
        }
        return m;
    }

    private static String derniere(JSONArray a) {
        String max = "";
        for (int k = 0; k < a.length(); k++) {
            JSONObject j = a.optJSONObject(k);
            if (j != null && j.optString("date").compareTo(max) > 0) max = j.optString("date");
        }
        return max;
    }

    /** « 872704 15:20–17:30 », « EV 14143 13:08–13:51 », « Coupure 14:21–16:08 »… à partir d'une ligne du déroulé. */
    private static String libelle(String ligne) {
        String[] p = ligne.split("\\|", -1);   // cond | h1 | h2 | lieu | act
        if (p.length < 5) return ligne;
        String act = p[4].replaceAll("^(?i)(marche)$", "Marche à pied");
        return act + " " + p[1] + (p[2].isEmpty() ? "" : "–" + p[2]);
    }

    /**
     * Différences de déroulé (trains, EV, coupure, PC, EJ…) entre deux versions d'une même journée,
     * ou null s'il n'y en a pas. Ne compare que si les deux versions contiennent le déroulé.
     */
    private static String diffDeroule(JSONObject x, JSONObject y) {
        JSONArray a = x.optJSONArray("sig"), b = y.optJSONArray("sig");
        if (a == null || b == null) return null;
        List<String> la = new ArrayList<>(), lb = new ArrayList<>();
        for (int k = 0; k < a.length(); k++) la.add(a.optString(k));
        for (int k = 0; k < b.length(); k++) lb.add(b.optString(k));
        if (la.equals(lb)) return null;
        List<String> moins = new ArrayList<>(la), plus = new ArrayList<>(lb);
        for (String s : lb) moins.remove(s);
        for (String s : la) plus.remove(s);
        List<String> parts = new ArrayList<>();
        for (String s : plus) parts.add("+ " + libelle(s));
        for (String s : moins) parts.add("− " + libelle(s));
        if (parts.isEmpty()) return "ordre du déroulé modifié";
        if (parts.size() > 4) { int n = parts.size() - 3; parts = new ArrayList<>(parts.subList(0, 3)); parts.add("… (" + n + " autres)"); }
        return String.join(" ; ", parts);
    }

    /**
     * Compare les journées à venir avant et après une mise à jour. Seule la période couverte par les deux
     * listes compte (les journées qui entrent simplement dans la fenêtre ne sont pas des modifications).
     */
    static void comparer(Context ctx, JSONArray avant, JSONArray apres) {
        if (!prefs(ctx).getBoolean("modif", false)) return;
        if (avant == null || avant.length() == 0 || apres == null) return;
        String auj = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        String fin = derniere(avant);
        String finN = derniere(apres);
        if (finN.compareTo(fin) < 0) fin = finN;
        Map<String, JSONObject> a = parCle(avant), b = parCle(apres);
        TreeSet<String> cles = new TreeSet<>(a.keySet());
        cles.addAll(b.keySet());
        List<String> lignes = new ArrayList<>();
        String premiere = null;
        for (String c : cles) {
            String date = c.substring(0, c.indexOf('#'));
            if (date.compareTo(auj) < 0 || date.compareTo(fin) > 0) continue;
            JSONObject x = a.get(c), y = b.get(c);
            String l = null;
            if (x != null && y == null) l = jourLong(date) + " : " + resume(x) + " supprimée";
            else if (x == null && y != null) l = jourLong(date) + " : nouvelle journée " + resume(y);
            else if (x != null && (!x.optString("code").equals(y.optString("code"))
                    || x.optLong("debut") != y.optLong("debut") || x.optLong("fin") != y.optLong("fin")))
                l = jourLong(date) + " : " + resume(x) + " → " + resume(y);
            else if (x != null && !"horaires".equals(prefs(ctx).getString("modifPortee", "tout"))) {
                String d = diffDeroule(x, y);
                if (d != null) l = jourLong(date) + " : " + y.optString("code") + " modifiée · " + d;
            }
            if (l != null) { lignes.add(l); if (premiere == null) premiere = date; }
        }
        if (lignes.isEmpty()) return;
        String texte = lignes.size() == 1 ? lignes.get(0) : lignes.size() + " journées modifiées · " + lignes.get(0);
        montrer(ctx, 1, CANAL_MODIF, "Planning modifié", texte, String.join("\n", lignes),
                ouvrir(ctx, 2001, "journee", "date", premiere));
    }

    // ---------- Découché la veille ----------

    /** Programme les prochains rappels activés : découché et changement d'heure. */
    static void programmer(Context ctx) {
        try { programmerDecouche(ctx); } catch (Exception ignore) { }
        try { programmerHeure(ctx); } catch (Exception ignore) { }
        try { programmerTaches(ctx); } catch (Exception ignore) { }
        try { programmerReveil(ctx); } catch (Exception ignore) { }
        try { neige(ctx); } catch (Exception ignore) { }
        try { synchro(ctx); } catch (Exception ignore) { }
    }

    // ---------- Synchro Graou en panne ----------

    private static String duree(long ms) {
        long h = ms / 3600000L;
        return h >= 48 ? (h / 24) + " jours" : h + " h";
    }

    /**
     * Si le planning n'a plus été mis à jour par le lien Graou depuis le délai réglé : une alerte (une seule par panne).
     * Dès qu'une mise à jour réussit, l'alerte disparaît et la surveillance repart de cette heure-là.
     */
    private static void synchro(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_SYNC);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3006, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences p = prefs(ctx);
        JSONObject d = donnees(ctx);
        long graou = d.optLong("graou", 0), now = System.currentTimeMillis();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        // mise à jour réussie depuis la dernière alerte : on retire l'alerte
        if (graou > 0 && p.getLong("syncAlerte", 0) != 0 && p.getLong("syncAlerte", 0) != graou) {
            if (nm != null) nm.cancel(ID_SYNC);
            p.edit().remove("syncAlerte").apply();
        }
        if (!p.getBoolean("sync", true) || graou <= 0) { if (am != null) am.cancel(pi); return; }
        long seuil = p.getInt("syncH", 12) * 3600000L, quand = graou + seuil;
        if (now < quand) {
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
            return;
        }
        if (am != null) am.cancel(pi);
        if (p.getLong("syncAlerte", 0) == graou) return;   // déjà signalé pour cette panne
        String erreur = d.optString("graouErreur", "");
        String quandTxt = jourLong(new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(graou))) + " à " + VoieLibreWidget.hm(graou);
        String detail = "Dernière mise à jour réussie : " + quandTxt + "."
                + (erreur.isEmpty() ? "" : "\n" + erreur)
                + "\nLes rappels (tâches, réveil, neige) et le widget se basent sur ce planning, peut-être périmé."
                + "\nVérifie le réseau, ou le lien Graou dans Paramètres (jeton régénéré ?).";
        Intent sync = new Intent(ctx, SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pSync = PendingIntent.getActivity(ctx, 2009, sync, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action reessayer = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notif), "Réessayer maintenant", pSync).build();
        montrer(ctx, ID_SYNC, CANAL_SYNC, "Planning Graou non mis à jour depuis " + duree(now - graou),
                "Dernière mise à jour : " + quandTxt, detail, ouvrir(ctx, 2008, "param", null, null), reessayer);
        p.edit().putLong("syncAlerte", graou).apply();
    }

    // ---------- Neige sur la journée de service ----------

    private static String signatureNeige(JSONObject n) {
        StringBuilder b = new StringBuilder(n.optString("date") + "#" + n.optInt("seg"));
        JSONArray l = n.optJSONArray("lieux");
        List<String> noms = new ArrayList<>();
        if (l != null) for (int k = 0; k < l.length(); k++) { JSONObject x = l.optJSONObject(k); if (x != null) noms.add(x.optString("n")); }
        java.util.Collections.sort(noms);
        for (String s : noms) b.append("|").append(s);
        return b.toString();
    }

    private static String heures(JSONObject x) {
        int a = x.optInt("h1"), b = x.optInt("h2");
        return a == b ? "vers " + a + " h" : "de " + a + " h à " + (b + 1) + " h";
    }

    /** Délai de réveil (minutes avant la PS) réglé pour une gare de PS, comme pour le bouton ⏰. */
    private static int delaiReveil(Context ctx, String gare) {
        JSONObject o = reglagesReveil(ctx), lieux = o.optJSONObject("lieux"), dl = o.optJSONObject("defaut");
        if (lieux != null && gare != null) {
            java.util.Iterator<String> it = lieux.keys();
            while (it.hasNext()) { String g = it.next(); if (g.equalsIgnoreCase(gare)) { dl = lieux.optJSONObject(g); break; } }
        }
        return dl == null ? 60 : dl.optInt("r", 60);
    }

    /**
     * Alerte neige, une fois par journée et par ensemble de villes (une nouvelle ville enneigée relance l'alerte) :
     * la veille à l'heure réglée, et/ou le jour même à l'heure de la PS, avec la prévision du moment.
     * Programme aussi la prochaine échéance.
     */
    private static void neige(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_NEIGE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3005, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences p = prefs(ctx);
        boolean veilleOn = p.getBoolean("neige", true), jourOn = p.getBoolean("neigeJour", true);
        JSONArray liste = donnees(ctx).optJSONArray("neige");
        if ((!veilleOn && !jourOn) || liste == null) { if (am != null) am.cancel(pi); return; }
        JSONObject vus;
        try { vus = new JSONObject(p.getString("neigeVu", "{}")); } catch (Exception e) { vus = new JSONObject(); }
        long now = System.currentTimeMillis();
        long[] prochain = {0};
        java.util.function.LongConsumer plusTard = t -> { if (t > now && (prochain[0] == 0 || t < prochain[0])) prochain[0] = t; };
        int heure = p.getInt("neigeHeure", 18);
        for (int k = 0; k < liste.length(); k++) {
            JSONObject n = liste.optJSONObject(k);
            if (n == null) continue;
            long ps = n.optLong("debut");
            if (ps + 30 * 60000L <= now) continue;   // l'alerte de la PS reste valable une demi-heure
            String sig = signatureNeige(n);
            long tJ = ps;   // jour même : à l'heure de la PS (mesures neige à prendre pendant la PC ou la RS)
            if (jourOn) {
                if (now < tJ) plusTard.accept(tJ);
                else if (!vus.has("J:" + sig)) { alerteNeige(ctx, n); try { vus.put("J:" + sig, ps); } catch (Exception ignore) { } }
            }
            if (veilleOn) {
                long des = veille(n.optString("date"), heure), fin = ps;
                if (now < des) plusTard.accept(des);
                else if (now < fin && !vus.has("V:" + sig)) { alerteNeige(ctx, n); try { vus.put("V:" + sig, ps); } catch (Exception ignore) { } }
            }
        }
        // oubli des journées passées
        JSONObject garde = new JSONObject();
        java.util.Iterator<String> it = vus.keys();
        while (it.hasNext()) { String c = it.next(); if (vus.optLong(c) > now - 86400000L) try { garde.put(c, vus.optLong(c)); } catch (Exception ignore) { } }
        p.edit().putString("neigeVu", garde.toString()).apply();
        if (am == null) return;
        if (prochain[0] == 0) { am.cancel(pi); return; }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochain[0], pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochain[0], pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochain[0], pi);
        }
    }

    private static void alerteNeige(Context ctx, JSONObject n) {
        long ps = n.optLong("debut"), now = System.currentTimeMillis();
        JSONArray l = n.optJSONArray("lieux");
        List<String> parts = new ArrayList<>();
        for (int x = 0; l != null && x < l.length(); x++) {
            JSONObject y = l.optJSONObject(x);
            if (y != null) parts.add(y.optString("n") + " " + heures(y));
        }
        String auj = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        String demain = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(now + 86400000L));
        String date = n.optString("date");
        String quand = auj.equals(date) ? "aujourd'hui" : demain.equals(date) ? "demain" : jourLong(date).toLowerCase(Locale.FRANCE);
        montrer(ctx, 5000 + (int) ((ps / 60000L) % 10000L), CANAL_NEIGE, "❄ Neige prévue " + quand + " sur ta journée",
                n.optString("code") + " · " + String.join(", ", parts),
                n.optString("code") + " · PS " + VoieLibreWidget.hm(ps) + "\n" + String.join("\n", parts)
                        + "\nPrévision Open-Meteo, à confirmer au moment de partir.",
                ouvrir(ctx, 2007, "journee", "date", date));
    }

    // ---------- Réveil oublié (la veille au soir, pour les PS matinales) ----------

    /** Journées du lendemain de date (première PS) dont la PS tombe dans la plage réglée, ou null. */
    private static JSONObject psMatinale(Context ctx, String date) {
        SharedPreferences p = prefs(ctx);
        int de = p.getInt("rvDe", 3), a = p.getInt("rvA", 11);
        JSONArray jours = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
        JSONObject meilleure = null;
        for (int k = 0; k < jours.length(); k++) {
            JSONObject j = jours.optJSONObject(k);
            if (j == null || !date.equals(j.optString("date"))) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(j.optLong("debut"));
            int h = c.get(Calendar.HOUR_OF_DAY);
            if (h >= de && h < a && (meilleure == null || j.optLong("debut") < meilleure.optLong("debut"))) meilleure = j;
        }
        return meilleure;
    }

    /** Prochaine vérification (la veille à l'heure réglée) d'une PS matinale à venir. */
    private static void programmerReveil(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis(), quand = 0;
        String date = null;
        if (p.getBoolean("rv", true)) {
            int heure = p.getInt("rvHeure", 21);
            JSONArray jours = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
            for (int k = 0; k < jours.length(); k++) {
                JSONObject j = jours.optJSONObject(k);
                if (j == null) continue;
                String d = j.optString("date");
                JSONObject m = psMatinale(ctx, d);
                if (m == null) continue;
                long t = veille(d, heure), ps = m.optLong("debut");
                // en service ce soir-là : la vérification attend la fin de service (+ 5 min)
                for (int x = 0; x < jours.length(); x++) {
                    JSONObject y = jours.optJSONObject(x);
                    if (y == null) continue;
                    long f = y.optLong("fin"), deb = y.optLong("debut");
                    if (f >= t && f < ps && deb < f) t = Math.max(t, f + 5 * 60000L);
                }
                if (t > now && (quand == 0 || t < quand)) { quand = t; date = d; }
            }
        }
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_REVEIL).putExtra("date", date);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3004, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (quand == 0) { am.cancel(pi); return; }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
        }
    }

    /**
     * La veille au soir : y a-t-il un réveil dans l'Horloge avant la PS ? Android ne donne que le prochain réveil
     * programmé ; il compte s'il sonne dans les 4 h qui précèdent la PS.
     */
    private static void verifierReveil(Context ctx, String date) {
        if (date == null || !prefs(ctx).getBoolean("rv", true)) return;
        JSONObject j = psMatinale(ctx, date);   // la journée a pu changer depuis la programmation
        if (j == null) return;
        long ps = j.optLong("debut"), now = System.currentTimeMillis();
        if (ps <= now) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo r = am == null ? null : am.getNextAlarmClock();
        long t = r == null ? 0 : r.getTriggerTime();
        if (t > 0 && t <= ps && t >= ps - 4 * 3600000L) return;   // réveil réglé : rien à dire
        String titre = "Aucun réveil avant ta PS de demain";
        String texte = j.optString("code") + " · PS " + VoieLibreWidget.hm(ps) + " à " + j.optString("lieuPS");
        String detail = texte + "\n" + (t > 0
                ? "Prochain réveil de l'Horloge : " + jourLong(new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(t)))
                  + " à " + VoieLibreWidget.hm(t) + (t > ps ? ", après ta PS." : ", trop tôt.")
                : "Aucun réveil programmé dans l'Horloge.")
                + "\nTouche pour ouvrir ta journée et régler le réveil (⏰).";
        Notification.Action action = actionReveil(ctx, j);
        if (action != null) montrer(ctx, 4, CANAL_REVEIL, titre, texte, detail, ouvrir(ctx, 2005, "journee", "date", date), action);
        else montrer(ctx, 4, CANAL_REVEIL, titre, texte, detail, ouvrir(ctx, 2005, "journee", "date", date));
    }

    /** Délais et sonneries du bouton ⏰ (Paramètres › Réveil), reçus de la page ; sinon les valeurs par défaut. */
    private static JSONObject reglagesReveil(Context ctx) {
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString("reveils", "{}"));
            if (o.optJSONObject("lieux") != null) return o;
        } catch (Exception ignore) { }
        try {
            // aucun délai propre à une gare par défaut : chacun ajoute les siens dans Paramètres › Réveil
            return new JSONObject().put("defaut", new JSONObject().put("r", 60).put("d", 30)).put("lieux", new JSONObject());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject avecSon(JSONObject r, JSONObject sons, String c) throws Exception {
        JSONObject v = sons == null ? null : sons.optJSONObject(c);
        if (v != null) {
            String uri = v.optString("uri", "");
            if (!uri.isEmpty()) r.put("son", uri);
            r.put("vib", v.optBoolean("vib", true));
        }
        return r;
    }

    /** Bouton « Régler le réveil » : mêmes heures, messages et sonneries que le bouton ⏰ de la journée. */
    private static Notification.Action actionReveil(Context ctx, JSONObject j) {
        try {
            JSONObject o = reglagesReveil(ctx), lieux = o.optJSONObject("lieux"), dl = o.optJSONObject("defaut");
            String gare = j.optString("garePS", j.optString("lieuPS"));
            if (lieux != null) {
                java.util.Iterator<String> it = lieux.keys();
                while (it.hasNext()) { String g = it.next(); if (g.equalsIgnoreCase(gare)) { dl = lieux.optJSONObject(g); break; } }
            }
            if (dl == null) dl = new JSONObject().put("r", 60).put("d", 30);
            long ps = j.optLong("debut"), tR = ps - dl.optInt("r", 60) * 60000L, tD = ps - dl.optInt("d", 30) * 60000L;
            // alarmes choisies dans Paramètres › Réveil : réveil et départ, réveil seul ou départ seul
            JSONObject al = o.optJSONObject("alarmes");
            boolean avecR = al == null || al.optBoolean("r", true), avecD0 = al == null || al.optBoolean("d", true);
            if (!avecR && !avecD0) avecR = true;
            boolean avecD = avecD0 && (!avecR || tD > tR);
            long t1 = avecR ? tR : tD;
            if (t1 <= System.currentTimeMillis()) return null;
            String lib = j.optString("code") + " PS " + VoieLibreWidget.hm(ps) + " " + gare;
            Calendar c = Calendar.getInstance();
            JSONArray liste = new JSONArray();
            if (avecR) {
                c.setTimeInMillis(tR);
                liste.put(avecSon(new JSONObject().put("h", c.get(Calendar.HOUR_OF_DAY)).put("m", c.get(Calendar.MINUTE))
                        .put("msg", "Voie libre · Réveil · " + lib), o.optJSONObject("sons"), "r"));
            }
            if (avecD) {
                c.setTimeInMillis(tD);
                liste.put(avecSon(new JSONObject().put("h", c.get(Calendar.HOUR_OF_DAY)).put("m", c.get(Calendar.MINUTE))
                        .put("msg", "Voie libre · Départ · " + lib), o.optJSONObject("sons"), "d"));
            }
            Intent i = new Intent(ctx, ReveilActivity.class).putExtra("liste", liste.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 2006, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String titre = avecR ? "Régler " + VoieLibreWidget.hm(tR) + (avecD ? " + départ " + VoieLibreWidget.hm(tD) : "")
                    : "Régler le départ " + VoieLibreWidget.hm(tD);
            return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notif), titre, pi).build();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Avant tâches (EV, EQ PREND, RS/PC, fin de coupure) ----------

    /** Réglages des rappels avant tâches ; par défaut, ceux demandés (tous activés). */
    private static JSONObject reglagesTaches(Context ctx) {
        JSONObject t;
        try { t = new JSONObject(prefs(ctx).getString("taches", "{}")); } catch (Exception e) { t = new JSONObject(); }
        try {
            if (t.optJSONObject("train") == null) t.put("train", new JSONObject().put("on", true).put("min", 4).put("horsAvion", true)
                    .put("notes", new JSONArray()));
            if (t.optJSONObject("ev") == null) t.put("ev", new JSONObject().put("on", true).put("min", 15));
            if (t.optJSONObject("eq") == null) t.put("eq", new JSONObject().put("on", true).put("min", 15));
            if (t.optJSONObject("coup") == null) t.put("coup", new JSONObject().put("on", true).put("min", 10));
            if (t.optJSONObject("rs") == null) {
                // même délai pour toutes les gares par défaut : chacun ajoute ses gares dans Paramètres
                t.put("rs", new JSONObject().put("on", true).put("horsTrain", true).put("defaut", 15).put("lieux", new JSONObject()));
            }
        } catch (Exception ignore) { }
        return t;
    }

    /** Minutes d'avance pour une tâche, ou -1 si ce type de rappel est désactivé. */
    private static int avance(JSONObject r, JSONObject tache) {
        String type = tache.optString("type");
        JSONObject c = r.optJSONObject(type);
        if (c == null || !c.optBoolean("on", true)) return -1;
        if (!"rs".equals(type)) return c.optInt("min", 15);
        JSONObject l = c.optJSONObject("lieux");
        String lieu = tache.optString("lieu");
        if (l != null) {
            java.util.Iterator<String> it = l.keys();
            while (it.hasNext()) {
                String g = it.next();
                if (g.equalsIgnoreCase(lieu)) return l.optInt(g, c.optInt("defaut", 15));
            }
        }
        return c.optInt("defaut", 15);
    }

    /** Instant du rappel de chaque tâche (0 si aucun : désactivé, ou RS/PC pendant la conduite). */
    private static long[] instantsTaches(Context ctx, JSONArray taches, JSONArray cond) {
        JSONObject r = reglagesTaches(ctx);
        JSONObject rs = r.optJSONObject("rs");
        boolean horsTrain = rs == null || rs.optBoolean("horsTrain", true);
        long[] q = new long[taches.length()];
        for (int k = 0; k < taches.length(); k++) {
            JSONObject t = taches.optJSONObject(k);
            if (t == null || t.optLong("t") <= 0) continue;
            int a = avance(r, t);
            if (a < 0) continue;
            long quand = t.optLong("t") - a * 60000L;
            if (horsTrain && "rs".equals(t.optString("type")) && cond != null) {
                boolean enTrain = false;
                for (int c = 0; c < cond.length(); c++) {
                    JSONArray iv = cond.optJSONArray(c);
                    if (iv != null && quand >= iv.optLong(0) && quand < iv.optLong(1)) { enTrain = true; break; }
                }
                if (enTrain) continue;
            }
            q[k] = quand;
        }
        return q;
    }

    private static JSONObject donnees(Context ctx) {
        try { return new JSONObject(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0).getString("donnees", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Prochain rappel avant tâche (un seul à la fois ; le suivant est programmé quand il sonne). */
    private static void programmerTaches(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        JSONObject d = donnees(ctx);
        JSONArray taches = d.optJSONArray("taches");
        long now = System.currentTimeMillis(), vu = prefs(ctx).getLong("tacheVu", 0), quand = 0;
        if (taches != null) {
            long[] q = instantsTaches(ctx, taches, d.optJSONArray("cond"));
            for (long t : q) if (t > now && t > vu && (quand == 0 || t < quand)) quand = t;
        }
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_TACHE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3003, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (quand == 0) { am.cancel(pi); return; }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
        }
    }

    /** Montre les rappels arrivés à échéance (une notification par tâche), sans jamais répéter le même. */
    private static void rappelTaches(Context ctx) {
        JSONObject d = donnees(ctx);
        JSONArray taches = d.optJSONArray("taches");
        if (taches == null) return;
        long now = System.currentTimeMillis(), vu = prefs(ctx).getLong("tacheVu", 0), max = vu;
        long[] q = instantsTaches(ctx, taches, d.optJSONArray("cond"));
        for (int k = 0; k < taches.length(); k++) {
            long quand = q[k];
            // échu depuis moins de 10 min (un rappel trop ancien n'a plus de sens), et pas encore montré
            if (quand == 0 || quand <= vu || quand > now + 30000 || quand < now - 600000) continue;
            JSONObject t = taches.optJSONObject(k);
            if (quand > max) max = quand;
            // départ de train : pas de notification en mode avion si ce choix est fait (le rappel est abandonné)
            if ("train".equals(t.optString("type"))) {
                JSONObject rt = reglagesTaches(ctx).optJSONObject("train");
                if ((rt == null || rt.optBoolean("horsAvion", true)) && android.provider.Settings.Global.getInt(
                        ctx.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0) continue;
            }
            long dans = Math.max(0, Math.round((t.optLong("t") - now) / 60000.0));
            String type = t.optString("type"), h = t.optString("h"), ou = t.optString("ou");
            String titre = "train".equals(type)
                    ? (dans > 0 ? "Départ dans " + dans + " min : " : "Départ maintenant : ") + t.optString("quoi")
                    : (dans > 0 ? "Dans " + dans + " min : " : "Maintenant : ") + t.optString("quoi");
            String texte = "coup".equals(type) ? "Coupure jusqu'à " + h + (ou.isEmpty() ? "" : " · " + ou)
                    : "ev".equals(type) || "train".equals(type) ? "Départ " + h + (ou.isEmpty() ? "" : " · " + ou)
                    : h + (ou.isEmpty() ? "" : " à " + ou);
            int id = 100 + (int) ((t.optLong("t") / 60000L) % 100000L);
            String note = "train".equals(type) ? noteDepart(ctx, t.optString("lieu")) : "";
            if ("train".equals(type) && telephoneEnUtilisation(ctx)) {
                alerteDepart(ctx, id, t, dans, note);
                continue;
            }
            if (!note.isEmpty()) texte = texte + " · " + note;
            montrer(ctx, id, CANAL_TACHE, titre, texte, note.isEmpty() ? null : texte.replace(" · " + note, "") + "\n" + note,
                    ouvrir(ctx, 2004, "journee", "date", t.optString("date")));
        }
        if (max > vu) prefs(ctx).edit().putLong("tacheVu", max).apply();
    }

    /** Phrase ajoutée au rappel de départ pour un train partant de cette gare (Paramètres), ou chaîne vide. */
    private static String noteDepart(Context ctx, String gare) {
        JSONObject rt = reglagesTaches(ctx).optJSONObject("train");
        JSONArray l = rt == null ? null : rt.optJSONArray("notes");
        List<String> r = new ArrayList<>();
        for (int k = 0; l != null && k < l.length(); k++) {
            JSONObject n = l.optJSONObject(k);
            if (n != null && n.optString("gare").equalsIgnoreCase(gare) && !n.optString("texte").trim().isEmpty()) r.add(n.optString("texte").trim());
        }
        return String.join(" · ", r);
    }

    /** Écran allumé et déverrouillé : le téléphone est en cours d'utilisation. */
    private static boolean telephoneEnUtilisation(Context ctx) {
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
        return pm != null && pm.isInteractive() && (km == null || !km.isKeyguardLocked());
    }

    /** Peut-on afficher le bandeau persistant (autorisation « notifications en plein écran », Android 14+) ? */
    static boolean pleinEcranAutorise(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    /**
     * Départ de train, téléphone en cours d'utilisation : bandeau rouge en gros, qui reste affiché jusqu'à OK
     * (alerte « urgente » d'Android, comme un appel entrant). Retiré tout seul 2 min après le départ.
     */
    private static void alerteDepart(Context ctx, int id, JSONObject t, long dans, String note) {
        if (!autorisees(ctx)) return;
        canaux(ctx);
        String grand = dans > 0 ? "DÉPART DANS " + dans + " MIN" : "DÉPART MAINTENANT";
        String train = t.optString("quoi") + " · " + t.optString("h"), trajet = t.optString("ou");
        android.widget.RemoteViews petit = new android.widget.RemoteViews(ctx.getPackageName(), R.layout.notif_depart);
        petit.setTextViewText(R.id.dGrand, grand);
        petit.setTextViewText(R.id.dLigne, train + (trajet.isEmpty() ? "" : " · " + trajet));
        // particularité de la gare de départ : en évidence, en jaune, sous le trajet
        petit.setTextViewText(R.id.dNote, note);
        petit.setViewVisibility(R.id.dNote, note.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        // version compacte limitée en hauteur : la phrase de la gare remplace la ligne train · trajet (visible une fois déplié)
        if (!note.isEmpty()) petit.setViewVisibility(R.id.dLigne, android.view.View.GONE);
        android.widget.RemoteViews grandV = new android.widget.RemoteViews(ctx.getPackageName(), R.layout.notif_depart_grand);
        grandV.setTextViewText(R.id.dGrand, grand);
        grandV.setTextViewText(R.id.dTrain, train);
        grandV.setTextViewText(R.id.dTrajet, trajet);
        grandV.setTextViewText(R.id.dNote, note);
        grandV.setViewVisibility(R.id.dNote, note.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        Intent ecran = new Intent(ctx, DepartActivity.class).putExtra("id", id).putExtra("grand", grand)
                .putExtra("train", train).putExtra("trajet", trajet).putExtra("note", note)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pEcran = PendingIntent.getActivity(ctx, 2010, ecran, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pOk = PendingIntent.getBroadcast(ctx, 2011, new Intent(ctx, Notifs.class).setAction(ACTION_OK_DEPART).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action ok = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notif), "OK", pOk).build();
        Notification.Builder b = new Notification.Builder(ctx, CANAL_DEPART)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFFE53935)
                .setContentTitle(grand.charAt(0) + grand.substring(1).toLowerCase(Locale.FRANCE) + " : " + t.optString("quoi"))
                .setContentText("Départ " + t.optString("h") + (trajet.isEmpty() ? "" : " · " + trajet) + (note.isEmpty() ? "" : " · " + note))
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(petit)
                .setCustomHeadsUpContentView(petit)
                .setCustomBigContentView(grandV)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(ouvrir(ctx, 2004, "journee", "date", t.optString("date")))
                .addAction(ok)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(60000L, t.optLong("t") + 120000L - System.currentTimeMillis()));
        // l'alerte « urgente » fait persister le bandeau ; sans l'autorisation, bandeau rouge normal (quelques secondes)
        if (pleinEcranAutorise(ctx)) b.setFullScreenIntent(pEcran, true);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, b.build());
    }

    /** Prochain rappel de découché (un seul à la fois ; le suivant est programmé quand il sonne). */
    private static void programmerDecouche(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_DEC);
        SharedPreferences p = prefs(ctx);
        JSONArray jours = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
        long now = System.currentTimeMillis(), quand = 0;
        String date = null;
        if (p.getBoolean("dec", false)) {
            int heure = p.getInt("heure", 18);
            for (int k = 0; k < jours.length(); k++) {
                JSONObject j = jours.optJSONObject(k);
                if (j == null || !j.optBoolean("dec") || j.optInt("seg") != 0) continue;
                long t = veille(j.optString("date"), heure);
                if (t > now && (quand == 0 || t < quand)) { quand = t; date = j.optString("date"); }
            }
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3001, i.putExtra("date", date),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (quand == 0) { am.cancel(pi); return; }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi);
    }

    private static long veille(String date, int heure) {
        try {
            String[] t = date.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(t[0]), Integer.parseInt(t[1]) - 1, Integer.parseInt(t[2]), heure, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.DAY_OF_MONTH, -1);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Changement d'heure (3 jours avant et/ou la veille) ----------

    /** Prochain changement d'heure du fuseau du téléphone, d'après les règles d'Android (rien d'écrit en dur). */
    private static java.time.zone.ZoneOffsetTransition prochainChangement(java.time.Instant apres) {
        return java.time.ZoneId.systemDefault().getRules().nextTransition(apres);
    }

    /** {quand, rappel (3 = trois jours avant, 1 = la veille), instant du changement}, ou null. */
    private static long[] prochainRappelHeure(Context ctx) {
        SharedPreferences p = prefs(ctx);
        boolean trois = p.getBoolean("ch3", false), veille = p.getBoolean("chVeille", false);
        if (!trois && !veille) return null;
        int heure = p.getInt("chHeure", 18);
        long now = System.currentTimeMillis();
        java.time.Instant depuis = java.time.Instant.now();
        for (int n = 0; n < 3; n++) {
            java.time.zone.ZoneOffsetTransition t = prochainChangement(depuis);
            if (t == null) return null;
            java.time.LocalDate jour = t.getDateTimeBefore().toLocalDate();
            long best = 0, type = 0;
            for (int avant : new int[]{3, 1}) {
                if ((avant == 3 && !trois) || (avant == 1 && !veille)) continue;
                long q = jour.minusDays(avant).atTime(heure, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (q > now && (best == 0 || q < best)) { best = q; type = avant; }
            }
            if (best > 0) return new long[]{best, type, t.getInstant().toEpochMilli()};
            depuis = t.getInstant().plusSeconds(1);
        }
        return null;
    }

    private static void programmerHeure(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long[] r = prochainRappelHeure(ctx);
        Intent i = new Intent(ctx, Notifs.class).setAction(ACTION_HEURE);
        if (r != null) i.putExtra("type", (int) r[1]).putExtra("instant", r[2]);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3002, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (r == null) { am.cancel(pi); return; }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r[0], pi);
    }

    private static void rappelHeure(Context ctx, int type, long instant) {
        java.time.zone.ZoneOffsetTransition t = prochainChangement(java.time.Instant.ofEpochMilli(instant - 1000));
        if (t == null) return;
        boolean hiver = t.isOverlap();   // l'heure recule : passage à l'heure d'hiver
        java.time.LocalDateTime avant = t.getDateTimeBefore(), apres = t.getDateTimeAfter();
        String jour = jourLong(avant.toLocalDate().toString());
        String titre = type == 3 ? "Changement d'heure dans 3 jours" : "Changement d'heure cette nuit";
        String texte = jour + " : à " + avant.getHour() + " h il sera " + apres.getHour() + " h ("
                + (hiver ? "passage à l'heure d'hiver" : "passage à l'heure d'été") + ").";
        // Une journée de service passe-t-elle par ce moment ?
        String service = null;
        JSONArray jours = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
        for (int k = 0; k < jours.length(); k++) {
            JSONObject j = jours.optJSONObject(k);
            if (j == null) continue;
            long d = j.optLong("debut"), f = j.optLong("fin");
            if (d <= t.getInstant().toEpochMilli() && t.getInstant().toEpochMilli() < f) {
                service = "Tu es en service cette nuit-là : " + j.optString("code") + ", PS " + VoieLibreWidget.hm(d) + ".";
                break;
            }
        }
        String detail = service == null ? texte : texte + "\n" + service;
        montrer(ctx, 3, CANAL_HEURE, titre, service == null ? texte : service, detail,
                ouvrir(ctx, 2003, "journee", "date", avant.toLocalDate().toString()));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) { programmer(ctx); return; }
        if (ACTION_HEURE.equals(intent.getAction())) {
            if (prefs(ctx).getBoolean("ch3", false) || prefs(ctx).getBoolean("chVeille", false))
                try { rappelHeure(ctx, intent.getIntExtra("type", 1), intent.getLongExtra("instant", 0)); } catch (Exception ignore) { }
            programmer(ctx);
            return;
        }
        if (ACTION_NEIGE.equals(intent.getAction()) || ACTION_SYNC.equals(intent.getAction())) { programmer(ctx); return; }
        if (ACTION_OK_DEPART.equals(intent.getAction())) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(intent.getIntExtra("id", 0));
            return;
        }
        if (ACTION_REVEIL.equals(intent.getAction())) {
            try { verifierReveil(ctx, intent.getStringExtra("date")); } catch (Exception ignore) { }
            programmer(ctx);
            return;
        }
        if (ACTION_TACHE.equals(intent.getAction())) {
            try { rappelTaches(ctx); } catch (Exception ignore) { }
            programmer(ctx);
            return;
        }
        if (!ACTION_DEC.equals(intent.getAction())) return;
        String date = intent.getStringExtra("date");
        if (prefs(ctx).getBoolean("dec", false) && date != null) {
            JSONArray jours = VoieLibreWidget.jours(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0));
            for (int k = 0; k < jours.length(); k++) {
                JSONObject j = jours.optJSONObject(k);
                if (j == null || !date.equals(j.optString("date")) || !j.optBoolean("dec") || j.optInt("seg") != 0) continue;
                String ville = j.optString("rhr", "");
                long d = j.optLong("debut");
                String titre = "Demain : découché" + (ville.isEmpty() ? "" : " à " + ville);
                String texte = j.optString("code") + " · PS " + VoieLibreWidget.hm(d) + " à " + j.optString("lieuPS");
                PendingIntent pi = ouvrir(ctx, 2002, "journee", "date", date);
                montrer(ctx, 2, CANAL_DEC, titre, texte, null, pi);
                break;
            }
        }
        programmer(ctx);   // le suivant
    }
}
