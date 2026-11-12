# solar2-android : application Android native (Kotlin + Jetpack Compose)

Client de l'API `solar2` (voir `../solar2`). Mêmes écrans que le site web : puissance actuelle,
énergie du jour, pic, hauteur du soleil, courbe de puissance d'un jour (toucher/glisser pour lire
une valeur), historique 30 jours / 90 jours / 12 mois, tableaux de données, thème clair/sombre.

Au premier lancement, l'application demande l'adresse du serveur (par exemple `solar.exemple.fr`),
la vérifie (`/api/v1/health`) puis la mémorise. Les connexions non chiffrées sont refusées : le
serveur doit être en **HTTPS**. L'application est en lecture seule et ne stocke aucun identifiant.

## Structure

| Module | Contenu | Plateforme |
|---|---|---|
| `core` | modèles JSON, client HTTP (OkHttp), formats, échelles des axes, découpage des courbes, dépôt de données | **Kotlin pur** : se teste avec un simple JDK |
| `app` | écrans Compose, graphiques dessinés en Canvas, ViewModel | Android (SDK requis) |

## Compiler

Avec **Android Studio** : ouvrir ce dossier ; l'IDE télécharge le SDK, crée `local.properties` et
configure `:app`. Ensuite `Run` (téléphone branché en débogage USB ou émulateur).

En ligne de commande (SDK installé, `ANDROID_HOME` défini ou `local.properties` avec `sdk.dir=…`) :

```sh
./gradlew :core:test            # tests du module Kotlin pur (sans SDK : fonctionne partout)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # installe sur le téléphone branché
```

Sans SDK, `settings.gradle.kts` n'inclut pas `:app` : `:core` reste testable seul.
Sans JDK local : `docker run --rm --network host -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gh -v "$HOME/.cache/gradle-docker:/gh" -v "$PWD":/p -w /p gradle:8.9-jdk17 gradle :core:test`.

## Tester contre un serveur local

La version **debug** accepte le HTTP en clair (utile pour un serveur de test) ; la version release
le refuse. Depuis l'émulateur Android, la machine hôte est `10.0.2.2` : saisir
`http://10.0.2.2:18080` à l'écran de configuration. Pour exécuter le code réduit par R8 comme en
release : `./gradlew :app:installDebug -PminifyDebug`.

## Version « release »

`assembleRelease` produit un APK **non signé** (R8 activé). Pour l'installer ailleurs que par
`installDebug`, créer une clé (`keytool -genkeypair …`), la déclarer dans un `signingConfigs`
**hors dépôt** (`*.jks` et `*.keystore` sont ignorés par git) puis signer.
