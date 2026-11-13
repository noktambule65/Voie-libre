# Voie libre — Préparation Google Play

Document de travail : réponses à préparer pour la Play Console. À relire avant de remplir les formulaires.

## Fiche de l'application

- **Nom** : Voie libre
- **Identifiant (nom de package)** : fr.voielibre.app — définitif dès la création de l'appli dans la console
- **Description courte** (80 caractères max, ici 80) : Ta journée de service sur l'écran d'accueil : widget planning, météo et rappels.
- **Description longue** :
  Ta journée de service d'un coup d'œil, sans ouvrir l'appli : le widget Voie libre affiche sur ton écran d'accueil ta prise et ta fin de service, tes trains, tes gares et la météo, puis les semaines à venir quand tu l'agrandis.

  Pour les agents SNCF qui consultent leur planning sur Graou : Voie libre lit ton planning à partir de ton lien d'abonnement Graou (à copier une fois depuis Graou). Sans accès à Graou, l'appli n'a rien à afficher.

  Ce que tu y trouves :
  • widget de l'écran d'accueil, journée et semaines
  • journée détaillée et vue des semaines, découchés et repos
  • météo des gares de ta journée
  • rappels avant la prise de service, réveil, alerte de départ avec compte à rebours
  • partage de ta journée, y compris une version « pour un proche » sans jargon

  Tout reste sur ton téléphone : pas de compte, pas de publicité, aucune donnée envoyée au développeur.
  Application non officielle, créée par un conducteur pour ses collègues. Seul Pacific fait foi.
- **Captures d'écran** (2 à 8) : commencer par le **widget sur un écran d'accueil**, puis le widget agrandi (journée + semaines), puis la journée, les semaines, l'alerte de départ. Planning réel ou anonymisé, jamais de logo SNCF.
- **Image de présentation** (1024 × 500) : le widget sur un téléphone, avec le titre « Ta journée sur ton écran d'accueil ».
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

- **Alarmes exactes** : la version Play n'utilise **pas** USE_EXACT_ALARM (réservée aux réveils et agendas, déclaration obligatoire). Elle utilise SCHEDULE_EXACT_ALARM, que l'utilisateur accorde lui-même ; l'appli le lui propose (Paramètres › Rappels de service et tuile « Prochains rappels »). Aucune déclaration à remplir. Sans cette autorisation, les rappels arrivent quand même, avec quelques minutes d'écart possibles.
- **USE_FULL_SCREEN_INTENT** : déclaration à remplir. Cas d'usage : alerte de départ d'un train affichée sur l'écran verrouillé, comme un réveil, à l'heure choisie par l'utilisateur. Depuis Android 14, cette autorisation n'est accordée d'office qu'aux applis de réveil et d'appel ; sinon l'utilisateur l'accorde lui-même et l'appli le lui propose déjà (Paramètres › Rappels de service › « Bandeau rouge non autorisé › Autoriser »). Si Google refuse la déclaration : retirer l'autorisation de la version Play (même méthode que pour USE_EXACT_ALARM, dans src/play/AndroidManifest.xml) ; l'alerte reste en bandeau rouge, sans l'écran plein écran verrouillé.
- **Position approximative** : facultative, météo locale uniquement.

## Technique (déjà prêt)

- Vise Android 16 (API 36), affichage bord à bord, retour prédictif.
- Version Play (variante « play ») : sans recherche de mise à jour sur GitHub, sans USE_EXACT_ALARM.
- Aucune notification activée par défaut ; autorisations demandées seulement quand l'utilisateur active un rappel.
- Pas de bibliothèque native (aucune contrainte de pages de 16 Ko), pas de trafic en clair (HTTPS partout), sauvegarde Android désactivée.
- Signal pour MacroDroid : facultatif, désactivé par défaut, décrit dans les règles de confidentialité.
- Paquet AAB : onglet Actions › « Version Play Store (AAB) » › Run workflow, avec un numéro de version.
