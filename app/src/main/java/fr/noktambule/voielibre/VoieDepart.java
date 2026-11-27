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
 * Retards et suppressions (option de Paramètres) : la même réponse donne an (train supprimé), x (arrêt supprimé à
 * cette gare), rd / ra (retard au départ / à l'arrivée, en s). Dans les 20 min avant le départ, la gare est relue
 * toutes les 2 min jusqu'au départ (plus tard si le train est en retard, 1 h au plus). Si Train Nomade annonce une
 * suppression ou un retard que le flux SNCF ne montrait pas à la page (rr, rs), la page est relancée en synchro
 * « retards » : elle relit ce flux (détail du trajet) et redessine le widget. Un train supprimé n'est plus suivi,
 * et sa voie n'est ni montrée ni notifiée.
 * EQ PREND : le train à relever roule déjà ; la gare de relève est lue dès 45 min avant, toutes les 10 min jusqu'à
 * −20 min, pour savoir tôt s'il arrive en retard.
 *
 * Source : API publique Train Nomade, https://api.trainnomade.fr/gare/{UIC}.json (données SNCF, ODbL, sans clé).
 */
final class VoieDepart {

    private VoieDepart() { }

    static final String PREFS = "voie-depart";
    private static final String URL_GARE = "https://api.trainnomade.fr/gare/";
    private static final long MIN = 60_000L;
    static final long AVANT = 20 * MIN, VERIF = 5 * MIN, APRES = 2 * MIN, SUIVI = 2 * MIN, RT_ECART = 3 * MIN,
            AVANT_EQ = 45 * MIN, TOT = 10 * MIN;
    /** Résultat de verifier() : voie, retard ou suppression à redessiner ; flux SNCF des retards à relire. */
    static final int VOIE = 1, RETARDS = 2;

    private static SharedPreferences p(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONObject donnees(Context ctx) {
        try { return new JSONObject(ctx.getSharedPreferences(VoieLibreWidget.PREFS, 0).getString("donnees", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Mes trains envoyés par la page (vide si les deux options sont coupées). */
    static JSONArray trains(Context ctx) { return trains(donnees(ctx)); }

    private static JSONArray trains(JSONObject d) {
        JSONArray a = d.optJSONArray("voieTrains");
        return a != null ? a : new JSONArray();
    }

    /** Options de la page : {voies, retards et suppressions}. Page d'avant la 1.226 : voies seulement. */
    private static boolean[] options(JSONObject d) {
        JSONObject o = d.optJSONObject("tn");
        return o == null ? new boolean[]{true, false} : new boolean[]{o.optBoolean("vo"), o.optBoolean("rt")};
    }

    private static boolean vrai(JSONObject o, String champ) { return o != null && (o.optInt(champ) == 1 || o.optBoolean(champ)); }

    /** Retard et suppression les plus récents (lus ici, appli fermée, ou par la page) : {sup, rd, ts, tg}. */
    private static JSONObject etat(JSONObject tr, JSONObject mem) {
        JSONObject m = mem.optJSONObject(cle(tr)), e = new JSONObject();
        JSONObject src = m != null && m.optLong("ts") >= tr.optLong("ts") ? m : tr;
        try {
            e.put("sup", vrai(src, "sup")).put("rd", src.optLong("rd")).put("ts", src.optLong("ts"))
                    .put("tg", m != null && !m.optString("tg").isEmpty() ? m.optString("tg") : tr.optString("tg"))
                    .put("tq", Math.max(Math.max(m != null ? m.optLong("tq") : 0, tr.optLong("tq")), src.optLong("ts")));
        } catch (Exception ignore) { }
        return e;
    }

    /** Fin du suivi : 2 min après le départ, plus le retard connu (1 h au plus). */
    private static long fin(JSONObject tr, JSONObject e) {
        return tr.optLong("t") + APRES + Math.min(60, Math.max(0, Math.round(e.optLong("rd") / 60.0))) * MIN;
    }

    /** Train supprimé d'après Train Nomade (voie ni montrée ni notifiée). */
    static boolean supprime(Context ctx, JSONObject tr) {
        try { return etat(tr, memoire(ctx)).optBoolean("sup"); } catch (Exception e) { return false; }
    }

    /** Voies trouvées ici : {"date|numéro": {v, av, t, k}}. */
    static JSONObject memoire(Context ctx) {
        try { return new JSONObject(p(ctx).getString("voies", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Pour la page : voies trouvées appli fermée, à fusionner avec les siennes. */
    static String memoireTexte(Context ctx) { return p(ctx).getString("voies", "{}"); }

    /** Pour la page : données reçues appli fermée, {"AAAA-MM-JJ": [demandes, octets]}. */
    static String consoTexte(Context ctx) { return p(ctx).getString("conso", "{}"); }

    /** Départ « date|numéro » ; arrivée du train d'un EQ PREND « date|numéro|a ». */
    static String cle(JSONObject tr) { return tr.optString("j") + "|" + tr.optString("n") + (tr.optBoolean("a") ? "|a" : ""); }

    private static boolean connue(JSONObject tr, JSONObject mem) {
        JSONObject m = mem.optJSONObject(cle(tr));
        return !tr.optString("v").isEmpty() || (m != null && !m.optString("v").isEmpty());
    }

    private static boolean verifiee(JSONObject tr, JSONObject mem) {
        JSONObject m = mem.optJSONObject(cle(tr));
        return tr.optBoolean("k") || (m != null && m.optBoolean("k"));
    }

    /**
     * Voie de départ connue du train « numéro » de la journée « date » (rappel avant un EV ou un EV loc), ou "".
     * Seulement si l'option Voie de départ est active (le train figure alors dans la liste envoyée par la page).
     */
    static String voieConnue(Context ctx, String date, String numero) {
        if (date == null || numero == null || numero.isEmpty()) return "";
        String n = numero.replaceFirst("^0+", "");
        JSONArray L = trains(ctx);
        JSONObject mem = memoire(ctx);
        for (int i = 0; i < L.length(); i++) {
            JSONObject tr = L.optJSONObject(i);
            if (tr == null || tr.optBoolean("a") || !date.equals(tr.optString("j")) || !n.equals(tr.optString("n"))) continue;
            if (etat(tr, mem).optBoolean("sup")) return "";             // train supprimé : pas de voie
            // la plus récente des deux : trouvée ici (appli fermée) ou par la page (vt : heure de l'info)
            JSONObject m = mem.optJSONObject(cle(tr));
            String vm = m != null ? m.optString("v") : "", vp = tr.optString("v");
            if (vm.isEmpty()) return vp;
            if (vp.isEmpty()) return vm;
            return m.optLong("t") >= tr.optLong("vt") ? vm : vp;
        }
        return "";
    }

    /** Heure de la prochaine demande utile, ou 0 s'il n'y en a pas. */
    static long prochaine(Context ctx, long now) {
        JSONObject d = donnees(ctx);
        JSONArray L = trains(d);
        boolean[] op = options(d);
        JSONObject mem = memoire(ctx);
        long q = 0;
        for (int i = 0; i < L.length(); i++) {
            JSONObject tr = L.optJSONObject(i);
            if (tr == null) continue;
            long t = tr.optLong("t");
            JSONObject e = etat(tr, mem);
            long f = fin(tr, e);
            if (t <= 0 || now > f || e.optBoolean("sup")) continue;
            long c = 0, tq = e.optLong("tq");
            if (op[0] && !connue(tr, mem)) c = Math.max(t - AVANT, now + MIN);
            else if (op[0] && !verifiee(tr, mem)) c = Math.max(t - VERIF, now + 5000);
            if (op[1]) {                                                  // retard et suppression : toutes les 2 min
                long s = Math.max(Math.max(t - AVANT, tq + SUIVI), now + 5000);
                c = c == 0 ? s : Math.min(c, s);
            }
            if (tr.optBoolean("a")) {                                     // EQ PREND, de −45 à −20 min : toutes les 10 min
                long s = Math.max(Math.max(t - AVANT_EQ, tq + TOT), now + 5000);
                if (s < t - AVANT) c = c == 0 ? s : Math.min(c, s);
            }
            if (c == 0 || c > f) continue;
            if (q == 0 || c < q) q = c;
        }
        return q;
    }

    /**
     * Demande Train Nomade aux gares qui en ont besoin maintenant (voie, retard, suppression).
     * Réseau : à appeler hors du fil principal. Renvoie VOIE si une voie, un retard ou une suppression a changé
     * (widget à redessiner) et RETARDS s'il faut relire le flux SNCF des retards.
     */
    static int verifier(Context ctx) {
        long now = System.currentTimeMillis();
        JSONObject d = donnees(ctx);
        JSONArray L = trains(d);
        boolean[] op = options(d);
        JSONObject mem = memoire(ctx);
        Map<String, Boolean> gares = new HashMap<>();
        for (int i = 0; i < L.length(); i++) {
            JSONObject tr = L.optJSONObject(i);
            if (tr == null || tr.optString("u").isEmpty()) continue;
            long t = tr.optLong("t");
            JSONObject e = etat(tr, mem);
            boolean eq = tr.optBoolean("a");
            if (e.optBoolean("sup") || now > fin(tr, e) || now < t - (eq ? AVANT_EQ : AVANT) - 30_000) continue;
            long tq = e.optLong("tq");
            boolean du;
            if (now < t - AVANT - 30_000) du = now - tq >= TOT - 15_000;   // EQ PREND en avance : toutes les 10 min
            else {
                boolean c = connue(tr, mem);
                du = (op[0] && !c) || (op[0] && !verifiee(tr, mem) && now >= t - VERIF - 30_000)
                        || (op[1] && now - tq >= SUIVI - 15_000);
            }
            if (du) gares.put(tr.optString("u"), true);
        }
        if (gares.isEmpty()) return 0;
        int res = 0;
        // flux SNCF relu au plus toutes les 3 min sur annonce de Train Nomade
        boolean rtPermis = now - p(ctx).getLong("rt-relu", 0) >= RT_ECART;
        for (String u : gares.keySet()) {
            JSONObject rep = lire(ctx, u);
            if (rep == null) continue;                               // pas de réseau, erreur : on réessaiera
            JSONArray passages = rep.optJSONArray("passages");
            if (passages == null) continue;
            // tous mes trains à cette gare (pas seulement ceux qu'on attendait : évite d'autres demandes)
            for (int i = 0; i < L.length(); i++) {
                JSONObject tr = L.optJSONObject(i);
                if (tr == null || !u.equals(tr.optString("u"))) continue;
                long t = tr.optLong("t");
                boolean arrivee = tr.optBoolean("a");
                String k = cle(tr);
                JSONObject av = mem.optJSONObject(k);
                JSONObject p = passage(passages, tr.optString("n"), t, arrivee);
                try {
                    JSONObject nv = av != null ? new JSONObject(av.toString()) : new JSONObject();
                    JSONObject e0 = etat(tr, mem);
                    boolean touche = true, sup = e0.optBoolean("sup");
                    nv.put("tq", now);                                        // dernière demande pour ce train
                    // retard et suppression à cette gare
                    if (p != null) {
                        sup = vrai(p, "an") || vrai(p, "x");
                        String champ = arrivee && p.has("ra") && !p.isNull("ra") ? "ra" : "rd";
                        long rd = Math.max(0, p.optLong(champ));
                        long rdMin = Math.round(rd / 60.0);
                        if (e0.optBoolean("sup") != sup || Math.round(e0.optLong("rd") / 60.0) != rdMin) res |= VOIE;
                        nv.put("sup", sup ? 1 : 0).put("rd", rd).put("ts", now);
                        touche = true;
                        if (op[1] && rtPermis) {
                            // le flux SNCF (vu par la page) ne le montre pas encore : relu, une fois par état nouveau
                            boolean rs = vrai(tr, "rs");
                            String sig = (sup ? "s" : "") + rdMin;
                            if (((sup && !rs) || Math.abs(rdMin - tr.optInt("rr", 0)) >= 2) && !sig.equals(e0.optString("tg"))) {
                                nv.put("tg", sig);
                                res |= RETARDS;
                            }
                        }
                    }
                    // voie
                    if (op[0]) {
                        String vAvant = av != null && !av.optString("v").isEmpty() ? av.optString("v") : tr.optString("v");
                        String avAvant = av != null && !av.optString("v").isEmpty() ? av.optString("av") : tr.optString("av");
                        String v = p != null ? p.optString("v", "").trim() : "";
                        boolean verifDue = !vAvant.isEmpty() && now >= t - VERIF - 30_000 && now <= fin(tr, e0);
                        if (!v.isEmpty() || verifDue) {
                            String vv = v.isEmpty() ? vAvant : v;
                            nv.put("v", vv);
                            String a = !vAvant.isEmpty() && !vAvant.equals(vv) ? vAvant : (!avAvant.isEmpty() && !avAvant.equals(vv) ? avAvant : "");
                            if (!a.isEmpty()) nv.put("av", a); else nv.remove("av");
                            nv.put("t", now);
                            if (verifDue || (av != null && av.optBoolean("k")) || tr.optBoolean("k")) nv.put("k", true);
                            if (!vv.equals(vAvant) || !a.equals(avAvant)) res |= VOIE;
                            if (!vv.equals(vAvant) && !sup) Notifs.notifierVoie(ctx, tr, vv, a);   // filtrée selon les réglages
                            touche = true;
                        }
                    }
                    if (touche) mem.put(k, nv);
                } catch (Exception ignore) { }
            }
        }
        // ménage : 3 jours gardés ici, la page garde tout l'historique
        try {
            String lim = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(now - 3 * 86_400_000L));
            for (Iterator<String> it = mem.keys(); it.hasNext(); ) if (it.next().compareTo(lim) < 0) it.remove();
        } catch (Exception ignore) { }
        SharedPreferences.Editor ed = p(ctx).edit().putString("voies", mem.toString());
        if ((res & RETARDS) != 0) ed.putLong("rt-relu", now);
        ed.apply();
        return res;
    }

    /**
     * Passage du train n à cette gare, à l'heure prévue la plus proche (45 min d'écart au plus) :
     * heure de départ, ou d'arrivée pour un EQ PREND (de départ s'il ne fait que passer). null si absent.
     */
    private static JSONObject passage(JSONArray passages, String n, long t, boolean arrivee) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int s = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60;
        JSONObject r = null;
        int meilleur = Integer.MAX_VALUE;
        for (int i = 0; i < passages.length(); i++) {
            JSONObject p = passages.optJSONObject(i);
            if (p == null) continue;
            String champ = arrivee && p.has("a") && !p.isNull("a") ? "a" : "d";
            if (!p.has(champ) || p.isNull(champ)) continue;
            if (!p.optString("n").replaceFirst("^0+", "").equals(n)) continue;
            int z = Math.abs((p.optInt(champ) % 86400) - s);
            z = Math.min(z, 86400 - z);
            if (z > 45 * 60 || z >= meilleur) continue;
            meilleur = z;
            r = p;
        }
        return r;
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
