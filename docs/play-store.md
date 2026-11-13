# Voie libre — Préparation Google Play

Document de travail : réponses à préparer pour la Play Console. À relire avant de remplir les formulaires.

## Fiche de l'application

- **Nom** : Voie libre
- **Description courte** (80 caractères max) : Ton planning de conduite, la météo des gares et des rappels avant chaque train.
- **Description longue** :
  Voie libre affiche ton planning de service à partir de ton lien d'abonnement Graou : journée, semaines, trains, découchés et repos. Elle donne la météo des gares de ta journée, te rappelle tes prises de service et tes départs (avec un compte à rebours), et propose un widget pour l'écran d'accueil.
  Tout reste sur ton téléphone : pas de compte, pas de publicité, aucune donnée envoyée au développeur.
  Application non officielle, créée par un conducteur pour ses collègues. Seul Pacific fait foi.
- **Catégorie** : Productivité (ou Outils)
- **Adresse de contact** : voielibreadc@gmail.com
- **Règles de confidentialité** : https://github.com/noktambule65/Voie-libre/blob/main/docs/confidentialite.md
- **Ne pas utiliser** le logo ni le nom « SNCF » dans l'icône, les captures ou le titre (règle sur l'usurpation d'identité).

## Contenu de l'appli (formulaires)

- **Publicités** : non.
- **Accès à l'appli** : aucune connexion requise. Préciser que l'appli fonctionne sans planning (écrans vides) ; un lien Graou n'est disponible que pour les agents SNCF.
- **Public cible** : 18 ans et plus.
- **Classification du contenu** : utilitaire, aucun contenu sensible.
- **Appli d'actualité** : non. **Appli de santé** : non. **Appli financière** : non.

## Sécurité des données (à vérifier au moment de remplir)

- Données **collectées** au sens de Google (transmises hors du téléphone) :
  - **Position approximative**, facultative, pour la météo ; non conservée par le développeur, traitée de façon éphémère par Open-Meteo et le service d'adresses.
- Données **partagées** : aucune (les services de météo et d'adresses répondent à une demande de l'utilisateur).
- **Chiffrement en transit** : oui (HTTPS partout).
- **Suppression des données** : tout est sur le téléphone ; effacement en désinstallant l'appli.
- Point à confirmer dans l'aide Play Console : la façon exacte de déclarer le lien Graou envoyé à Graou par l'utilisateur lui-même.

## Autorisations sensibles (déclarations)

- **USE_EXACT_ALARM** : l'appli comporte un réveil et des alertes à heure précise avant chaque train (fonction principale). Si refusé : passer à SCHEDULE_EXACT_ALARM, autorisation accordée par l'utilisateur dans les réglages.
- **USE_FULL_SCREEN_INTENT** : alerte de départ affichée sur l'écran verrouillé, comme un réveil. Si refusé : retirer le plein écran de la version Play, le bandeau rouge reste.
- **Position approximative** : facultative, météo locale uniquement.

## Technique (déjà prêt)

- Vise Android 16 (API 36), affichage bord à bord, retour prédictif.
- Version Play sans recherche de mise à jour sur GitHub (VL_PLAY=1).
- Paquet AAB : onglet Actions › « Version Play Store (AAB) » › Run workflow, avec un numéro de version.
