# Ker Patrimo — suivi de placements (PEA, assurance vie, CTO…)

Version 0.1, point de départ. Kotlin + Jetpack Compose, minSdk 26 (Android 8).

## Compilation automatique (GitHub)

À chaque modification poussée sur la branche `main`, GitHub compile l'APK (onglet Actions)
et le publie dans **Releases** : depuis le téléphone, ouvrir la dernière release, télécharger
`KerPatrimo-N.apk` et l'installer par-dessus la précédente (même clé de signature, données conservées).
Relancer à la main : Actions › Compiler l'APK › Run workflow.

## Démarrer (Android Studio, facultatif)

1. Ouvrir le dossier `Patrimo` dans Android Studio (Ladybug ou plus récent), laisser Gradle synchroniser.
2. Brancher le téléphone (débogage USB) et lancer ▶.
3. Copier sur le téléphone votre classeur de suivi `Mesplacements_AV….xlsm` (onglet « portefeuille » lu directement)
   ou le modèle `Modele_import_placements.xlsx`, puis « Importer ».
4. Placements › Ajouter, ou toucher une ligne : modifier, enregistrer un achat/une vente, supprimer.
   Onglet « Surveiller » : lignes soldées (0 part) et idées, hors patrimoine.
   Assistant : « achat de 5 parts de CW8 à 548 sur PEA », « vends 10 Air Liquide à 172 », « supprime Kering », « surveille LVMH » ;
   chaque modification proposée est appliquée seulement après « Valider ».
5. Roue dentée › Réglages : frais des contrats, frais des fonds, assistant. Les valeurs modifiées sont conservées aux imports suivants.

> Le projet n'a pas pu être compilé dans l'environnement où il a été écrit (pas de SDK Android).
> Attendez-vous à quelques corrections mineures à la première compilation.

## Ce que fait la v0.1

| Besoin | Où c'est dans le code |
|---|---|
| Import depuis Excel (sans bibliothèque lourde) | `importer/XlsxReader.kt`, `importer/PortfolioImporter.kt` |
| Cours trouvés à partir du seul ISIN | `quotes/QuoteProviders.kt` : Yahoo Finance d'abord, Boursorama en secours |
| Conversion en euros des lignes en devises | `quotes/QuoteService.kt` |
| Valeur, gain, perf par ligne | `domain/Portfolio.kt` (`Ligne`) |
| Courbe 1S / 1M / 6M / 1A / 5A | `Portfolio.courbe` + `ui/Components.kt` (`CourbeChart`) |
| Tableau des gains et pertes, tri et filtre par enveloppe | `ui/MainActivity.kt` (`Placements`) |
| Constats et alertes | `domain/InsightEngine.kt` (seuils dans `Seuils`) |
| Notifications en arrière-plan (toutes les heures) | `work/RefreshWorker.kt` |
| Assistant conversationnel | `assistant/Assistant.kt` : toute API « chat/completions » (Mistral par défaut) |

### Sources de cotation
- **Yahoo Finance** (API non officielle) : actions, ETF, beaucoup d'OPCVM, avec historique 5 ans.
- **Boursorama** (lecture de la page, pas d'API) : en secours, utile pour certaines UC d'assurance vie. Fragile si le site change.
- Les supports sans ISIN (fonds euros, livrets) utilisent « Valeur manuelle » et « Taux annuel ».
- Cours différés d'environ 15 min selon la place. Rafraîchissement toutes les 5 min appli ouverte, toutes les heures en fond.
- La source trouvée est mémorisée par ligne (`symbole`, `source`) : la recherche n'est faite qu'une fois.

### Assistant
Réglages (roue dentée) : URL de base, clé, modèle. Par défaut `https://api.mistral.ai/v1` / `mistral-small-latest`.
Sans clé, il répond hors ligne à quelques questions simples. Le portefeuille est envoyé au fournisseur choisi à chaque question.

## Limites connues
- La courbe est « à composition constante » : elle valorise les positions actuelles dans le passé.
  Les achats et ventes passés (onglet « Mouvements ») ne sont pas encore rejoués.
- Le fonds euros n'est pas capitalisé automatiquement : mettre à jour la valeur manuelle une fois par an.
- La clé d'API est stockée en clair dans les préférences de l'appli. Passer à EncryptedSharedPreferences avant toute diffusion.

## Pistes pour la v0.2
1. Rejouer l'onglet « Mouvements » : courbe exacte, rendement annualisé (TRI) et plus-values réalisées.
2. Saisie et édition d'une ligne directement dans l'appli.
3. Seuils d'alerte réglables par ligne et alertes de prix (« préviens-moi sous 50 € »).
4. Benchmark sur la courbe (MSCI World, CAC 40).
5. Sauvegarde et synchronisation (Supabase par exemple).
