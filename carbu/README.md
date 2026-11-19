# carbu : prix des carburants autour du Doulieu (API Go + PWA)

Même architecture que `solar2` : un seul conteneur (binaire Go statique, aucune dépendance, image
`scratch` ≈ 3 Mo) qui sert l'**API JSON** et le **site web** (PWA installable, thème clair/sombre,
sans CDN). Cible `aarch64` (voir `Dockerfile`). **Aucun historique** : un seul fichier d'état
(`/data/state.json`, ~2 Ko) réécrit à chaque relevé, uniquement pour éviter un nouveau passage
sur carbu.com à chaque redémarrage.

Carburants : Gazole, SP95 (E5), SP95-E10, E85.

## Sources

| Zone | Source | Fréquence |
|---|---|---|
| France | open data [prix-carburants.gouv.fr](https://www.prix-carburants.gouv.fr) (Licence Ouverte) | 1 requête groupée / h |
| Belgique | fiches de stations de **carbu.com** | **1 passage tous les 2 jours** (plancher 12 h imposé), 8 s entre deux fiches |

> **carbu.com** réserve expressément ses droits de fouille de données (`tdm-reservation: 1`).
> Son moissonnage est un choix assumé de l'utilisateur, pour un usage personnel à très faible
> volume. `CARBU_BE_ENABLED=false` coupe tout accès à ce site. Un blocage de leur part est possible ;
> l'application reste alors fonctionnelle (France) et le signale.

## Indice de fiabilité

Calculé à l'affichage d'après l'**âge du prix déclaré par la source** (pas la date de notre relevé) :
100 jusqu'à 24 h, décroissance linéaire jusqu'à 0 à 10 jours. Niveaux : `good` ≥ 75 (≈ 3 j),
`medium` 40–74 (≈ 3 à 7 j), `low` < 40. carbu.com ne donne que le jour : le prix est daté de midi.
Le « moins cher » mis en avant ignore les prix `low`.

## Application Android

Dans `android/` (voir `android/README.md`) : affichage, corrections manuelles, synchro de clé,
géolocalisation et coût du déplacement (plein de 45 L, 7 L/100 km).

## Déploiement

```sh
./build.sh      # docker compose up -d --build --force-recreate
```

Aucun port publié : Apache fait le reverse proxy vers `http://carbu:8080/` (`apache-carbu.conf.example`).
Réseau `docker_default`, volume nommé `carbu-data`. `go vet` et `go test` s'exécutent pendant le build.

## API de données (port 8080, via Apache 443)

Lecture libre :

- `GET /api/v1/prices` : stations triées par distance au Doulieu, prix par carburant
  (`price`, `updated`, `age_hours`, `reliability`, `level`, `origin` = `source` | `manual`),
  `best` (station la moins chère par carburant), état des sources (`ok`, `last_success`, `next_run`).
- `GET /api/v1/health`

Écriture, **uniquement avec une clé d'API** (`Authorization: Bearer ck_…`) :

- `PUT /api/v1/stations/{id}/prices` — `{"prices":{"gazole":2.05,"e10":1.89},"observed_at":"…"}`
  (`observed_at` optionnel : défaut maintenant, max 30 jours, pas dans le futur). Prix accepté de
  0,30 à 5,00 €/L. Renvoie la station telle qu'elle apparaît ensuite.
- `DELETE /api/v1/stations/{id}/prices/{carburant}` — retire la correction manuelle.

**Règle de priorité :** une correction manuelle est une donnée comme une autre, datée de son
observation. La plus récente des deux (source ou correction) est affichée ; dès que la source publie
un prix plus récent, la correction est purgée. Une station sans prix de source (ex. Le Seau) peut
ainsi être renseignée à la main. Pas d'historique : une seule correction par station et carburant.

## API de gestion des clés (port 8081, LAN uniquement)

| Route | Rôle |
|---|---|
| `POST /api/v1/keys` `{"device_id":"…","name":"Pixel 7"}` | génère une clé ; **renvoyée une seule fois** (201) |
| `GET /api/v1/keys` | liste (nom, dates ; jamais la clé ni son empreinte) |
| `DELETE /api/v1/keys/{id}` | révoque |

Une nouvelle synchronisation du même `device_id` **remplace** la clé précédente de l'appareil
(rotation sans clé orpheline). Maximum 20 clés. Sur le disque (`/data/keys.json`, 0600) seules les
empreintes SHA-256 sont conservées.

Trois barrières indépendantes pour que cette API ne soit jamais joignable d'internet :
1. **pare-feu** : port 8443 refusé côté WAN, jamais redirigé par la box ;
2. **Apache** : `Require ip` sur le vhost 8443 (`apache-carbu.conf.example`) ;
3. **le code** : toute requête dont l'adresse source n'est pas privée (RFC 1918, ULA, lien local,
   boucle) est refusée en 403, de même qu'une requête sans `X-Forwarded-For` derrière le proxy.

Anti force brute sur l'écriture : 10 clés invalides en 10 min depuis une IP => 429 (même avec
la bonne clé) ; clés de 256 bits aléatoires. `CARBU_ADMIN_LISTEN=off` désactive la gestion des clés.

Révoquer un appareil perdu : `curl https://carburants.example.com:8443/api/v1/keys` (depuis le LAN)
pour trouver son `id`, puis `curl -X DELETE …/api/v1/keys/<id>`.

## Stations

Liste dans `stations.json` (embarquée ; remplaçable via `CARBU_STATIONS_FILE`).

- France : `"source": "fr"`, `ref` = identifiant de la station sur prix-carburants.gouv.fr.
- Saisie seule : `"source": "manual"` (aucune source automatique ; prix uniquement via l'API d'écriture).
- Belgique : `"source": "carbu"`, `ref` = identifiant numérique de la fiche carbu.com
  (le dernier nombre de l'URL `https://carbu.com/belgie/index.php/station/…/<id>`).

## Configuration (variables `CARBU_*`)

`CARBU_TRUST_PROXY=true` (derrière Apache) · `CARBU_BE_ENABLED` (`true`) · `CARBU_BE_INTERVAL` (`48h`,
min 12h) · `CARBU_FR_INTERVAL` (`1h`, min 5m) · `CARBU_BE_PAUSE` (`8s`) · `CARBU_DATA_DIR` (`/data`) ·
`CARBU_ADMIN_LISTEN` (`:8081`, `off` pour couper) · `CARBU_ADMIN_TRUST_PROXY` · `CARBU_CORS_ORIGIN` · `CARBU_RATE_RPS` / `CARBU_RATE_BURST` · `CARBU_USER_AGENT`.

## Limites connues

- Certaines stations belges (Le Seau, HeiteS Westouter) n'ont **aucun prix** renseigné sur carbu.com
  (« Prijs niet beschikbaar ») : elles s'affichent sans prix.
- Si carbu.com change sa mise en page, l'analyse échoue proprement (erreur journalisée, source
  marquée en échec dans l'interface) au lieu d'afficher de fausses valeurs.
- E85 : non vendu en Belgique.
