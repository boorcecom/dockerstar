# solar2 : tableau de bord solaire (API Go + PWA)

Remplace l'ancien site PHP/CanvasJS. Un seul conteneur (binaire Go statique, ~3 Mo, image `scratch`) qui sert :

- l'**API JSON** en lecture seule sur la base `solar` ;
- le **site web** (PWA : PC, tablette, smartphone, installable sur l'écran d'accueil, thème clair/sombre, aucune dépendance externe ni CDN).

Cible : `aarch64` (le `Dockerfile` compile sur la machine cible, ou en cross-compilation :
`docker buildx build --platform linux/arm64 .`).

## Déploiement

```sh
cp .env.example .env     # puis renseigner SOLAR_DB_PASSWORD (compte MariaDB en lecture seule)
./build.sh
```

Aucun port n'est publié : Apache (`httpd` / `publichttpd`, réseau `docker_default`) fait le reverse
proxy vers `http://solar2:8080/`. Exemple de vhost : `apache-solar2.conf.example`.
Compte MariaDB : `sql/create_readonly_user.sql` (`SELECT` seul).

## API (`/api/v1`, GET uniquement)

| Route | Contenu |
|---|---|
| `health` | `{"status":"ok"}` si la base répond |
| `current` | dernière mesure : puissance (W), énergie du jour/totale (kWh), âge, hauteur du soleil |
| `day?date=AAAA-MM-JJ` | points `[timestamp unix, watts]` d'un jour + résumé (énergie, pic, hauteur max du soleil) |
| `days?from=…&to=…` | énergie et pic par jour (max 400 jours, défaut 30 jours) |
| `summary` | énergie : jour, 7 j, 30 j, 365 j, total |

Les timestamps sont en secondes unix (UTC) et `tz` donne le fuseau d'affichage : l'application n'a
pas à deviner le fuseau de l'appareil. Réponses mises en cache (60 s pour aujourd'hui, 1 h pour un
jour passé, 5 min pour les cumuls), limitation de débit par IP, erreurs génériques côté client
(détails dans les logs).

Paramètres d'URL du site : `/?date=2025-06-21`, `/?view=history&range=90`.

## Configuration

Voir `.env.example`. Points importants :

- `SOLAR_TZ` : fuseau dans lequel le collecteur `omnik` écrit les `datetime` (Europe/Paris aujourd'hui).
  **Limite connue** : en heure locale, l'heure 02:00–03:00 du changement d'heure d'octobre est ambiguë
  (points mélangés cette nuit-là, sans conséquence la nuit). La vraie correction est que le
  collecteur enregistre en UTC.
- `SOLAR_TRUST_PROXY=true` derrière Apache (sinon la limitation de débit verrait l'IP d'Apache).

## Tests

`go vet` et `go test` s'exécutent pendant `docker build` (un code cassé ne produit pas d'image).
Vérifié sur une copie des vraies données : jour ≈ 2 ms, 12 mois ≈ 120 ms (avant cache).

## Application Android (étape suivante)

L'API est déjà prête pour un client mobile (JSON simple, timestamps UTC, CORS via `SOLAR_CORS_ORIGIN`).
Deux voies : empaqueter cette PWA (TWA/Bubblewrap, nécessite le site en HTTPS public ; ou Capacitor,
avec `window.SOLAR_API_BASE` pointant vers l'URL publique), ou une application Kotlin native.
