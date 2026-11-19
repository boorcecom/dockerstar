# carbu-android : application Android native (Kotlin + Jetpack Compose)

Client de l'API `carbu` (voir `..`). Même structure que `solar2-android` : `core` (Kotlin pur, testable
avec un simple JDK) + `app` (écrans Compose).

## Fonctions

- **Affichage** : prix par carburant (Gazole, SP95, SP95-E10, E85), indice de fiabilité, tri par prix,
  par coût total ou par distance.
- **Position et coût du déplacement** : la position du téléphone sert à calculer la distance vers
  chaque station. Pour chaque station : *plein de 45 L*, *coût du trajet* (A/R par défaut, 7 L/100 km)
  et *plein + trajet*. Le carburant du trajet est valorisé au prix de la station visée. La distance est
  à vol d'oiseau × coefficient de route (1,3 par défaut) : **c'est une estimation**. Consommation,
  volume du plein, coefficient et aller-retour/aller simple sont réglables. Sans position, les distances
  partent du Doulieu. **La position ne quitte jamais le téléphone.**
- **Corrections manuelles** : toucher une station, saisir le prix vu à la pompe. Remplacé
  automatiquement dès que la source publie plus récent. Une correction peut être retirée.
- **Sécurité** : écritures protégées par une clé d'API (voir ci-dessous).

## Paramètres et synchro de clé

Deux URL, saisies dans les paramètres :

| URL | Exemple | Accès |
|---|---|---|
| Accès aux données | `https://carburants.example.com` | internet + LAN |
| Gestion des clés | `https://carburants.example.com:8443` | **LAN uniquement** |

Bouton **« Synchro clé »** : l'application envoie un identifiant d'appareil (UUID généré une fois) et
son nom à l'API de gestion ; le serveur renvoie une clé, affichée **une seule fois**. L'application la
stocke **chiffrée** (AES-GCM, clé dans l'Android Keystore, hors sauvegarde). Rejouer « Synchro clé »
remplace la clé de ce téléphone (rotation, pas de clé orpheline). « Oublier la clé » l'efface du
téléphone ; pour la révoquer côté serveur, voir `../README.md`.

À faire **depuis le Wi-Fi de la maison** : hors du LAN, le serveur répond 403 (message explicite). Les
clés sont inutiles pour la simple consultation : sans clé, seul l'affichage est disponible.

## Compiler

```sh
./gradlew :core:test            # 19 tests, sans SDK
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # installe sur le téléphone branché
```

Le HTTP en clair n'est toléré qu'en **debug** (tester contre un serveur local ; depuis l'émulateur,
l'hôte est `10.0.2.2`). La version release n'accepte que HTTPS.

`assembleRelease` produit un APK **non signé** : le signer comme pour solar2 (`apksigner`, clé hors dépôt).
