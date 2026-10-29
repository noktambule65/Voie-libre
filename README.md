# Voie libre

Ton planning Graou et la météo de ta journée de service, sur Android : graphique de la journée,
météo des gares traversées, widget d'écran d'accueil et rappels (découché, tâches, réveil, neige,
changement d'heure).

## Installer ou mettre à jour

1. Ouvrir l'onglet **Releases** de ce dépôt, sur le téléphone, dans Chrome.
2. Télécharger le fichier `Voie-libre-vXX.apk` de la version la plus récente.
3. L'ouvrir et accepter l'installation (autoriser Chrome à installer des applis la première fois).
4. Ouvrir Voie libre › ⚙ Paramètres, et coller son lien d'abonnement Graou
   (le bouton « Où trouver mon lien d'abonnement Graou ? » explique où le trouver).

Ensuite, Voie libre prévient elle-même quand une nouvelle version est prête (fenêtre à l'ouverture,
notification) et la télécharge. La première fois, Android demande d'autoriser Voie libre à installer
ses mises à jour. Bouton manuel : ⚙ Paramètres › Aide › « Rechercher une mise à jour ».

Le lien Graou et le planning restent sur le téléphone : ils ne sont envoyés nulle part.
La météo vient d'Open-Meteo.

## Comment c'est construit

- `app/src/main/assets/VoieLibre.html` : l'appli elle-même (fichier autonome, qui s'ouvre aussi dans Chrome).
  La remplacer par une nouvelle version suffit.
- À chaque modification envoyée sur `main`, GitHub Actions compile l'APK et le publie dans Releases.
- `app/voie-libre.jks.chiffre` : clé de signature, chiffrée. Son mot de passe est le secret
  `VOIE_LIBRE_MDP` du dépôt. La même clé doit servir à chaque version, pour que les mises à jour
  s'installent par-dessus sans perdre les réglages.

## Publier une nouvelle version (feu vert)

1. Chaque compilation est publiée en **pré-version** : personne d'autre ne la voit.
2. L'installer sur son propre téléphone depuis la page Releases et la tester.
3. Feu vert : modifier la release (crayon), décocher « Set as a pre-release », cocher
   « Set as the latest release », valider. Le texte de la release s'affiche dans la fenêtre
   de mise à jour : on peut y écrire les nouveautés.
4. Les téléphones la proposent dans la journée (vérification au plus une fois par jour).

Le dépôt doit être **public** pour que les téléphones puissent voir les versions sans compte GitHub.
