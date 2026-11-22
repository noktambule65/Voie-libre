# Questionnaire d'accès à la production (brouillon)

À remplir dans la Play Console quand le bouton « Demander à publier en production » sera actif (≈ 4/12/2026).
Sources des faits : `registre-test-ferme.md`. Les passages [À COMPLÉTER] attendent des chiffres ou des retours
réels : ne rien inventer. Pas de nom ni d'adresse de testeur ici (dépôt public).

Rappels :
- Cliquer sur « Suivant » (parties 1 et 2) ou « Envoyer » (partie 3) pour enregistrer, sinon tout est perdu.
- Être concret : nombres, versions, dates. Citer au moins un problème signalé et sa correction.
- Ne rien gonfler (testeurs, usage, installations).

---

## Partie 1 : à propos du test fermé

### 1. Comment avez-vous recruté les testeurs ?

> Voie libre est faite pour les conducteurs de train (agents de conduite) et je suis moi-même conducteur.
> J'ai invité directement des collègues conducteurs par un groupe WhatsApp entre collègues et par
> un message sur X. Les testeurs ont rejoint un groupe Google, puis accepté le test depuis le lien Google Play.
> 15 personnes ont rejoint le groupe ; 12 testeurs ou plus sont inscrits sans interruption depuis le 20/11/2026.

### 2. Facilité de recrutement (choix)

Proposition : **Facile** (collègues directs, objectif atteint en 2 à 3 jours).
Ne pas choisir « Très facile » : l'inscription a posé problème à plusieurs personnes (voir question 4).

### 3. Décrivez l'engagement des testeurs

> Les testeurs sont des conducteurs qui ont utilisé l'appli avec leur vrai planning, en service, comme le
> feront les futurs utilisateurs : consultation de la journée et de la semaine, météo des gares,
> notifications avant les départs, widget sur l'écran d'accueil, rappel de réveil la veille d'une prise de
> service matinale. [À COMPLÉTER avec les chiffres de la Play Console : appareils actifs, versions d'Android,
> marques de téléphones, plantages éventuels (Android vitals).]
> J'utilise moi-même l'appli chaque jour et j'ai consigné chaque anomalie dans un registre daté.
> Différence avec l'usage attendu : [À COMPLÉTER, par ex. certaines fonctions comme le réveil sont surtout
> utiles les veilles de service tôt le matin, donc moins souvent sollicitées pendant 14 jours].

### 4. Résumé des retours reçus et façon de les recueillir

> Retours recueillis directement (WhatsApp entre collègues, X) et par le bouton
> « Signaler un problème » de l'appli ; le journal de diagnostic de l'appli a servi à trouver les causes.
> Principaux retours :
> - Installation impossible juste après l'inscription (« Élément introuvable ») : explications envoyées
>   (même compte Google que le Play Store, délai, cache), et message d'invitation complété.
> - Le lien « télécharger sur Google Play » de la page de test ne marchait pas pour un testeur : consigne
>   ajoutée « chercher Voie libre dans le Play Store ».
> - Le rappel « réveil oublié » ne se déclenchait pas : le journal de diagnostic a montré qu'une appli
>   d'automatisation était prise pour un réveil. Corrigé (voir partie 3).
> - Une journée apparaissait en double et certains trajets différaient du planning d'origine : corrigés ou
>   expliqués dans l'aide.
> [À COMPLÉTER avec les retours des collègues reçus d'ici le 4/12, positifs comme négatifs.]

---

## Partie 2 : à propos de l'appli

### 5. Public visé

> Les conducteurs de train (agents de conduite), adultes, qui consultent déjà leur planning de travail sur
> Graou. Voie libre affiche ce planning plus lisiblement sur leur téléphone. Ce n'est pas une appli grand
> public, ni une appli officielle : elle est indépendante, ni éditée ni validée par la SNCF.

### 6. Comment l'appli apporte de la valeur

> Elle reprend le planning personnel de l'utilisateur (son lien d'export Graou) et l'affiche en vue jour et
> semaine, avec un graphique de la journée, la météo des gares de prise et de fin de service (Open-Meteo),
> un widget, et des rappels : avant chaque départ, la veille d'un découché, et si aucun réveil n'est réglé
> avant une prise de service matinale. Tout reste sur le téléphone : pas de compte, pas de serveur, pas de
> publicité, aucune donnée envoyée au développeur.

### 7. Installations attendues la première année (choix)

Proposition : **moins de 1 000** (public restreint de collègues conducteurs).

---

## Partie 3 : préparation à la production

### 8. Changements faits à la suite du test fermé

> Mises à jour publiées pendant le test (versions 1.201 à 1.208) :
> - Rappel « réveil oublié » fiabilisé : seuls les réveils d'une appli Horloge sont pris en compte (toutes
>   marques de téléphone), avertissement « Vérifie ton réveil » en cas de doute, journal de diagnostic.
> - Rappels de découché et de changement d'heure à la minute près, rappels manqués rattrapés si le téléphone
>   était éteint, recalcul si l'heure du téléphone change.
> - Journée en double dans le planning affichée une seule fois ; fin de trajet estimée corrigée.
> - Raccourcis demandés (consultation des réservations de taxi et d'hôtel, localisation de la tablette).
> - Mention « application indépendante, non officielle » ajoutée dans l'appli et dans la fiche.
> - Nouvelle question dans l'aide pour expliquer les écarts venant du planning d'origine.

### 9. Comment avez-vous décidé que l'appli était prête ?

> Chaque version est d'abord installée en test interne sur mon téléphone et utilisée en service réel avant
> d'être proposée aux testeurs. Les problèmes signalés ont tous été corrigés ou expliqués, et chaque
> correction a été vérifiée sur tout mon planning (487 jours). [À COMPLÉTER : aucun plantage
> relevé dans Android vitals / nombre de plantages.] Les dernières versions n'ont plus fait remonter de
> problème bloquant.
