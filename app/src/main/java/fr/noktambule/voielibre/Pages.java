package fr.noktambule.voielibre;

import android.content.Context;

import androidx.webkit.WebViewAssetLoader;

/**
 * Sert la page Voie libre (assets/VoieLibre.html) à la vue web, depuis une adresse https locale :
 * la page garde ainsi son stockage (lien Graou, planning, réglages) d'une ouverture à l'autre.
 * VoieLibre.html reste un fichier autonome, qui s'ouvre aussi tel quel dans Chrome.
 */
final class Pages {

    private Pages() { }

    static WebViewAssetLoader chargeur(Context ctx) {
        return new WebViewAssetLoader.Builder()
                .setDomain(MainActivity.HOTE)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(ctx.getApplicationContext()))
                .build();
    }
}
