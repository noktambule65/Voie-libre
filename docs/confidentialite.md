# Voie libre — Règles de confidentialité

*Dernière mise à jour : 13 novembre 2026*

Voie libre est une application gratuite et non commerciale qui aide les conducteurs de train à consulter leur planning de service, la météo et à recevoir des rappels avant leurs trains. Elle n'est pas une application officielle de la SNCF.

## En résumé

- **Aucun compte**, aucune inscription.
- **Aucune donnée n'est envoyée au développeur** : Voie libre n'a pas de serveur.
- **Pas de publicité, pas de mesure d'audience, pas de pistage**, aucun outil tiers de ce type.
- Ton planning, tes réglages et ton lien d'abonnement Graou **restent sur ton téléphone**.

## Données utilisées et où elles vont

| Donnée | Utilisation | Envoyée à |
|---|---|---|
| Lien d'abonnement Graou (que tu colles toi-même) | Télécharger ton planning | Graou (graou.sncf.fr), uniquement pour lire ce planning |
| Coordonnées des gares de ton planning | Afficher la météo | Open-Meteo (api.open-meteo.com) |
| Position approximative du téléphone (**seulement si tu l'actives**) | Météo du lieu où tu te trouves, nom de la commune | Open-Meteo ; Géoplateforme IGN (data.geopf.fr) ou Base Adresse Nationale (api-adresse.data.gouv.fr) pour trouver le nom de la commune |
| Zone de carte affichée | Afficher les fonds de carte | Géoplateforme IGN (data.geopf.fr), à défaut OpenStreetMap (tile.openstreetmap.org) |
| Aucune donnée personnelle | Afficher les nouveautés de l'application | GitHub (api.github.com) |

Ces services reçoivent seulement ce qui est nécessaire à la réponse (des coordonnées, pas ton planning ni ton nom). Leurs propres règles de confidentialité s'appliquent à ces échanges.

## Données sur le téléphone

Le planning, les réglages, le lien Graou et le journal de diagnostic sont enregistrés uniquement dans l'application, sur ton téléphone. Ils ne sont pas copiés dans la sauvegarde Google. Les supprimer : désinstaller l'application, ou effacer ses données dans les réglages Android.

Le fichier « Mes réglages » (Paramètres › Sauvegarde des réglages) n'est créé que si tu le demandes ; il ne contient ni le lien Graou ni le planning.

## Autorisations

- **Notifications, alarmes exactes, écran plein écran** : rappels et alertes avant tes trains, réveil.
- **Démarrage du téléphone** : reprogrammer ces rappels après un redémarrage.
- **Position approximative** (facultative) : météo du lieu où tu te trouves.

## Contact

voielibreadc@gmail.com
