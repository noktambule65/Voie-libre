# Registre du test fermé Google Play (pour le questionnaire d'accès à la production)

Notes datées des problèmes rencontrés, des retours des testeurs et des corrections, à reprendre dans le
questionnaire (« retours reçus », « changements apportés », « pourquoi l'appli est prête »). Pas de nom ni
d'adresse de testeur ici : le dépôt est public.

## Calendrier

| Date | Étape |
|---|---|
| 12/11/2026 | Play Protect bloque les nouvelles versions de l'APK diffusée par GitHub : décision de passer par Google Play |
| 13/11/2026 | Création du compte développeur personnel « Voie Libre ADC » |
| 16/11/2026 | Compte développeur validé |
| 17/11/2026 | Version 200 envoyée en test fermé (canal Alpha, France), testeurs via un groupe Google |
| 18/11/2026 | 1er examen refusé : description de la fiche jugée trompeuse (« informations gouvernementales » sans source). Description réécrite (sources Open-Meteo et IGN, non-affiliation à la SNCF), renvoyée et acceptée le jour même |
| 18/11/2026 | Test fermé en ligne, invitations envoyées (WhatsApp entre collègues, X) |
| 19-20/11/2026 | 12 testeurs inscrits atteints (case cochée dans la Play Console) ; groupe : 15 testeurs + le compte développeur le 20/11 |
| ≈ 3-4/12/2026 | Fin des 14 jours avec 12 testeurs ou plus (à confirmer dans la Play Console) |

## Retours et problèmes constatés pendant le test

| Date | Origine | Constat | Suite donnée |
|---|---|---|---|
| 17/11 | Développeur, usage quotidien | Version Play : le rappel « réveil oublié » et un rappel de départ de train ne semblaient pas arriver pendant des essais (heure du téléphone modifiée pour tester) | Analyse du journal de diagnostic ; les rappels de train fonctionnent ensuite ; cause du réveil trouvée le 19/11 (voir plus bas) |
| 18/11 | Testeur (collègue) | Installation impossible : « Élément introuvable » dans le Play Store juste après l'inscription | Explications : même compte Google que le Play Store, délai de prise en compte, vider le cache du Play Store, Android 10 minimum |
| 18/11 | Développeur | Besoin d'un accès rapide aux réservations de taxi et d'hôtel et à la localisation de la tablette | Version 1.201 : tuile « Raccourcis pratiques » (Galac'SI, Retrouver ma tablette sur iCloud.com) |
| 18/11 | Examen Google | Risque de confusion avec un service officiel | Version 1.201 : mention « application indépendante, ni éditée ni validée par la SNCF » dans Paramètres › Version et mises à jour |
| 18 et 19/11 | Développeur, usage quotidien | Pas de notification « réveil oublié » les veilles de PS du matin, alors qu'aucun réveil n'était réglé | Version 1.202 : suivi de la vérification dans le journal de diagnostic. Le 19/11 à 21:00 le journal montre que la vérification s'est bien déclenchée mais a trouvé un « réveil » à 05:00 : une macro d'automatisation (MacroDroid, déclencheur quotidien à 05:00, sans sonnerie) que Android annonce comme prochain réveil |
| 19/11 | Développeur | Libellé erroné : Galac'SI ne sert pas à réserver mais à consulter les réservations faites par l'entreprise | Texte corrigé : « Voir mes réservations de taxi et d'hôtel » |
| 19/11 | Développeur | Correction du faux réveil | Version 1.203 : seuls les réveils d'une appli Horloge comptent (horloges détectées sur chaque téléphone, toutes marques) ; si une autre appli masque le réveil, avertissement « Vérifie ton réveil » ; mention « fonction expérimentale » |
| 19/11 | Développeur | Le « changement de rame » encombrait le graphique de la journée | Version 1.204 : retiré du graphique (reste dans le déroulé) |
| 20/11 | Testeur (venu de X) | Le lien « télécharger sur Google Play » de la page de test ne fonctionnait pas ; installation réussie en passant directement par le Play Store | À ajouter au message d'invitation : « si le lien ne marche pas, cherche Voie libre dans le Play Store » |
| 20/11 | Développeur | Le découché du 22/12 apparaît deux fois : l'export Graou contient deux exemplaires identiques (seuls l'identifiant et l'étiquette de lieu diffèrent), alors que Graou n'affiche qu'une journée | Version 1.205 : un seul exemplaire gardé, uniquement si les deux sont identiques au caractère près |
| 20/11 | Groupe Google | Un testeur a un problème de distribution des e-mails du groupe (triangle rouge) | Sans effet sur l'accès au test |

## Versions publiées pendant le test

| Version | Canal | Contenu |
|---|---|---|
| 1.200 | Test fermé | Première version Google Play |
| 1.201 → 1.205 | Test interne (développeur) puis test fermé | Raccourcis pratiques, mention d'indépendance, réveil oublié fiabilisé (horloges seulement, avertissement en cas de doute, journal), Galac'SI, changement de rame retiré du graphique ; 1.205 : découché et changement d'heure à la minute près, rappels manqués rattrapés, recalcul si l'heure du téléphone change, journée en double de l'export Graou affichée une fois |

## Pour le questionnaire (rappels)

- Rester factuel : préciser ce qui vient des testeurs et ce qui vient de l'usage quotidien du développeur (lui-même conducteur et utilisateur).
- Réponses en français, phrases courtes, sigles expliqués (PS = prise de service, FS = fin de service, RHR = repos hors résidence).
