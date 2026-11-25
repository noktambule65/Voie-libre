package fr.noktambule.voielibre;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Voie de départ de mes trains, appli fermée (option « Voie de départ » de Paramètres).
 *
 * La page envoie avec le widget la liste de mes trains des 48 h à venir ({t départ, u code UIC de la gare,
 * n numéro, j date, v voie déjà connue, k vérification faite}). Ici, sans ouvrir la page :
 * - voie pas encore connue : une demande par minute à partir de 20 min avant le départ, jusqu'à la trouver ;
 * - voie connue : plus rien, sauf une vérification 5 min avant le départ (changement de voie).
 * Les demandes portent l'ETag de la réponse précédente : si rien n'a changé, Train Nomade répond 304 sans contenu.
 * La page n'est relancée (synchro « voie », pour redessiner le widget) que si une voie est nouvelle ou a changé.
 *
 * Source : API publique Train Nomade, https://api.trainnomade.fr/gare/{UIC}.json (données SNCF, ODbL, sans clé).
 */
final class VoieDepart {

    private VoieDepart() { }

    static final String PREFS = "voie-depart";
    private static final String URL_GARE = "https://api.trainnomade.fr/gare/";
    private static final long MIN = 60_000L;
    static final long AVANT = 20 * MIN, VERIF = 5 * MIN, APRES = 2 * MIN;

    private static SharedPreferences p(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Mes trains envoyés par la page (vide si l'option est coupée). */
    static JSONArray trains(Context ctx) {
        try {
            JSONObject d = new JSONObject(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0).getString("donnees", "{}"));
            JSONArray a = d.optJSONArray("voieTrains");
            return a != null ? a : new JSONArray();
        } catch (Exception e) { return new JSONArray(); }
    }

    /** Voies trouvées ici : {"date|numéro": {v, av, t, k}}. */
    static JSONObject memoire(Context ctx) {
        try { return new JSONObject(p(ctx).getString("voies", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Pour la page : voies trouvées appli fermée, à fusionner avec les siennes. */
    static String memoireTexte(Context ctx) { return p(ctx).getString("voies", "{}"); }

    /** Pour la page : données reçues appli fermée, {"AAAA-MM-JJ": [demandes, octets]}. */
    static String consoTexte(Context ctx) { return p(ctx).getString("conso", "{}"); }

    private static String cle(JSONObject tr) { return tr.optString("j") + "|" + tr.optString("n"); }

    private static boolean connue(JSONObject tr, JSONObject mem) {
        JSONObject m = mem.optJSONObject(cle(tr));
        return !tr.optString("v").isEmpty() || (m != null && !m.optString("v").isEmpty());
    }

    private static boolean verifiee(JSONObject tr, JSONObject mem) {
        JSONObject m = mem.optJSONObject(cle(tr));
        return tr.optBoolean("k") || (m != null && m.optBoolean("k"));
    }

    /** Heure de la prochaine demande utile, ou 0 s'il n'y en a pas. */
    static long prochaine(Context ctx, long now) {
        JSONArray L = trains(ctx);
        JSONObject mem = memoire(ctx);
        long q = 0;
        for (int i = 0; i < L.length(); i++) {
            JSONObject tr = L.optJSONObject(i);
            if (tr == null) continue;
            long t = tr.optLong("t");
            if (t <= 0 || now > t + APRES) continue;
            long c;
            if (!connue(tr, mem)) c = Math.max(t - AVANT, now + MIN);
            else if (!verifiee(tr, mem)) c = Math.max(t - VERIF, now + 5000);
            else continue;
            if (c > t + APRES) continue;
            if (q == 0 || c < q) q = c;
        }
        return q;
    }

    /**
     * Demande la voie aux gares de départ des trains qui en ont besoin maintenant.
     * Réseau : à appeler hors du fil principal. Renvoie true si une voie est nouvelle ou a changé.
     */
    static boolean verifier(Context ctx) {
        long now = System.currentTimeMillis();
        JSONArray L = trains(ctx);
        JSONObject mem = memoire(ctx);
        Map<String, Boolean> gares = new HashMap<>();
        for (int i = 0; i < L.length(); i++) {
            JSONObject tr = L.optJSONObject(i);
            if (tr == null || tr.optString("u").isEmpty()) continue;
            long t = tr.optLong("t");
            if (now > t + APRES) continue;
            boolean c = connue(tr, mem);
            if ((!c && now >= t - AVANT - 30_000) || (c && !verifiee(tr, mem) && now >= t - VERIF - 30_000))
                gares.put(tr.optString("u"), true);
        }
        if (gares.isEmpty()) return false;
        boolean change = false;
        for (String u : gares.keySet()) {
            JSONObject rep = lire(ctx, u);
            if (rep == null) continue;                               // pas de réseau, erreur : on réessaiera
            JSONArray passages = rep.optJSONArray("passages");
            if (passages == null) continue;
            // toutes mes voies à cette gare (pas seulement celles qu'on attendait : évite d'autres demandes)
            for (int i = 0; i < L.length(); i++) {
                JSONObject tr = L.optJSONObject(i);
                if (tr == null || !u.equals(tr.optString("u"))) continue;
                long t = tr.optLong("t");
                String k = cle(tr);
                JSONObject av = mem.optJSONObject(k);
                String vAvant = av != null ? av.optString("v") : tr.optString("v");
                String avAvant = av != null ? av.optString("av") : tr.optString("av");
                String v = voie(passages, tr.optString("n"), t);
                boolean verifDue = !vAvant.isEmpty() && now >= t - VERIF - 30_000;
                if (v.isEmpty() && !verifDue) continue;
                try {
                    JSONObject nv = new JSONObject();
                    String vv = v.isEmpty() ? vAvant : v;
                    nv.put("v", vv);
                    String a = !vAvant.isEmpty() && !vAvant.equals(vv) ? vAvant : (!avAvant.isEmpty() && !avAvant.equals(vv) ? avAvant : "");
                    if (!a.isEmpty()) nv.put("av", a);
                    nv.put("t", now);
                    if (verifDue) nv.put("k", true);
                    else if (av != null && av.optBoolean("k")) nv.put("k", true);
                    if (!vv.equals(vAvant) || !a.equals(avAvant)) change = true;
                    if (!vv.equals(vAvant)) Notifs.notifierVoie(ctx, tr, vv, a);   // filtrée selon les réglages
                    mem.put(k, nv);
                } catch (Exception ignore) { }
            }
        }
        // ménage : 3 jours gardés ici, la page garde tout l'historique
        try {
            String lim = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(now - 3 * 86_400_000L));
            for (Iterator<String> it = mem.keys(); it.hasNext(); ) if (it.next().compareTo(lim) < 0) it.remove();
        } catch (Exception ignore) { }
        p(ctx).edit().putString("voies", mem.toString()).apply();
        return change;
    }

    /** Voie du train n au départ de cette gare, à l'heure prévue la plus proche (45 min d'écart au plus). */
    private static String voie(JSONArray passages, String n, long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int s = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60;
        String v = "";
        int meilleur = Integer.MAX_VALUE;
        for (int i = 0; i < passages.length(); i++) {
            JSONObject p = passages.optJSONObject(i);
            if (p == null || !p.has("d") || p.isNull("d")) continue;
            if (!p.optString("n").replaceFirst("^0+", "").equals(n)) continue;
            int z = Math.abs((p.optInt("d") % 86400) - s);
            z = Math.min(z, 86400 - z);
            if (z > 45 * 60 || z >= meilleur) continue;
            meilleur = z;
            v = p.optString("v", "").trim();
        }
        return v;
    }

    /** Réponse de la gare : nouvelle (200) ou la précédente gardée ici si rien n'a changé (304). */
    private static JSONObject lire(Context ctx, String uic) {
        SharedPreferences sp = p(ctx);
        String etag = sp.getString("etag-" + uic, null), garde = sp.getString("corps-" + uic, null);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_GARE + uic + ".json").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            if (etag != null && garde != null) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code == 304 && garde != null) { conso(ctx, 0); return new JSONObject(garde); }
            if (code != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, r);
            }
            String corps = b.toString("UTF-8");
            int lg = c.getContentLength();
            conso(ctx, lg > 0 ? lg : b.size());
            JSONObject o = new JSONObject(corps);
            String e = c.getHeaderField("ETag");
            SharedPreferences.Editor ed = sp.edit().putString("corps-" + uic, corps);
            if (e != null) ed.putString("etag-" + uic, e); else ed.remove("etag-" + uic);
            ed.apply();
            return o;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Données reçues appli fermée, par jour (35 jours gardés). */
    private static synchronized void conso(Context ctx, long octets) {
        try {
            SharedPreferences sp = p(ctx);
            JSONObject o = new JSONObject(sp.getString("conso", "{}"));
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
            long now = System.currentTimeMillis();
            String k = f.format(new Date(now)), lim = f.format(new Date(now - 35 * 86_400_000L));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) if (it.next().compareTo(lim) < 0) it.remove();
            JSONArray v = o.optJSONArray(k);
            if (v == null) v = new JSONArray().put(0).put(0);
            v.put(0, v.optLong(0) + 1).put(1, v.optLong(1) + Math.max(0, octets));
            o.put(k, v);
            sp.edit().putString("conso", o.toString()).apply();
        } catch (Exception ignore) { }
    }
}
