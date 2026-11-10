# Mesures

## Méthode

- Serveur dédié Fabric 1.21.1 : Cobblemon 1.8.4, Fabric API 0.116.12, Fabric Language Kotlin 1.13.12,
  **Lithium 0.15.3** (réglages par défaut), Java 21, `-Xmx6G`, Windows 11, i7-13700KF.
- **Depuis le 2026-11-10, le serveur est épinglé sur les 8 cœurs « performance »** (processeurs logiques 0 à 15),
  en priorité haute. Sur ce processeur hybride, la même partie donnait 40 ou 75 ms par tick selon le cœur où tombait
  le fil du serveur : les valeurs absolues d'avant cette date (75 à 100 ms) ont été prises sans épinglage. Les
  comparaisons « sans → avec » restent valables dans les deux cas, puisqu'elles alternent dans la même partie.
- Monde de test : jour fixe, météo fixe, pas de spawn naturel, `save-off`. Despawn de Cobblemon repoussé
  (`despawnerMinAgeTicks`) pour garder le même nombre de Pokémon du début à la fin.
- `/fasterai bench crowd <zones> <parZone> <enclos> <parEnclos> <ticks> <bloc> <graine> <nom>` (version `-Pbench`) :
  - 6 zones à 1024 blocs l'une de l'autre, chunks chargés de force, un faux joueur par zone pour que les chunks
    tournent comme près d'un joueur ;
  - `parZone` Pokémon dispersés dans un rayon de 56 blocs, plus `enclos` chunks fermés par des barrières contenant
    chacun `parEnclos` Pokémon ;
  - espèces tirées au hasard (graine fixe) parmi les espèces implémentées, hors légendaires, fabuleux, ultra-chimères
    et paradoxes ; niveaux 5 à 50 ;
  - 400 ticks de chauffe, puis mesure. Le mod est **coupé puis activé toutes les `bloc` ticks dans la même partie**, ce
    qui compare les deux modes sur le même monde, les mêmes Pokémon, à la même heure.
  - `/fasterai bench toggle <interrupteurs>` restreint l'alternance à quelques interrupteurs de la config (le reste
    du mod reste actif) : c'est ce qui mesure ce qu'apporte un raccourci précis.
- Temps d'un tick : du début à la fin du tick serveur. Coût par Pokémon : temps passé dans `PokemonEntity.tick`
  divisé par le nombre de ticks de Pokémon.
- Profilage : Java Flight Recorder (`settings=profile`), JVM lancée avec `-XX:+DebugNonSafepoints` (sans quoi le
  temps propre des méthodes est mal attribué), piles démappées avec les mappings Mojang.
- Scripts : `tools/bench/redeploy.sh` (compile, redémarre le serveur de mesure, l'épingle), `bench.py` (lance une
  partie et relève les résultats, `--toggle=`, `--jfr`), `jfrtool.py` (arbre d'appels d'un profil), `rcon.py`.

Limites :

- Les faux joueurs ne sont pas suivis par le serveur (pas d'envoi réseau des Pokémon) : le coût du suivi des entités
  (`ChunkMap`) n'est mesuré qu'en partie, et le spawn de Cobblemon pas du tout. Le mod n'y touche pas.
- La part d'une méthode dans un profil surestime ce qu'on gagne à la supprimer : avec 3000 Pokémon, le tick est
  limité par les accès mémoire, pas par le nombre d'instructions. Seule l'alternance dans une même partie fait foi.

## Résultats (2026-11-10, version 0.2.0)

3000 Pokémon apparus à chaque fois. Mode coupé / actif alterné toutes les 100 ticks. Sans Cobblemon Faster Snacks.

### Tout le mod

| Partie | Ticks mesurés | Tick moyen OFF → ON | Médiane | p95 | p99 | Pire tick | µs par tick de Pokémon | TPS |
|---|---|---|---|---|---|---|---|---|
| Mélange : 6 × (300 dispersés + 2 enclos de 100), graine 11 | 2400 | 44,9 → 36,3 ms (−19,2 %) | 40,8 → 35,6 | 81,1 → 42,4 | 113,1 → 47,6 | 188,5 → 71,1 | 13,22 → 10,18 (−23,0 %) | 18,76 → 19,98 |
| Serré : 6 × 4 enclos de 125, graine 13 | 1200 | 49,1 → 37,6 ms (−23,4 %) | 41,6 → 36,7 | 74,5 → 44,7 | 187,2 → 50,8 | 221,2 → 73,3 | 14,92 → 10,71 (−28,2 %) | 17,61 → 19,96 |
| Dispersé : 6 × 500, graine 17 | 1200 | 46,2 → 37,7 ms (−18,3 %) | 42,3 → 37,1 | 89,0 → 44,1 | 104,4 → 50,1 | 274,4 → 67,0 | 13,25 → 10,48 (−20,9 %) | 18,77 → 19,98 |

### Par groupe de raccourcis (mélange, le reste du mod actif)

| Interrupteurs alternés | Tick moyen OFF → ON | µs par tick de Pokémon |
|---|---|---|
| Les six de la 0.2.0 (`brainTaskGates`, `sensorSchedule`, `memoryExpiryList`, `flatOnGround`, `travelRequirementScan`, `idleMoveControl`) | 37,9 → 35,4 ms (−6,7 %) | 10,69 → 9,90 (−7,4 %) |
| `fluidSectionCheck` seul | 40,6 → 38,9 ms (−4,2 %) | 11,55 → 10,94 (−5,3 %) |
| `inWallScan` seul | 44,9 → 45,4 ms (pas de gain) | 12,68 → 12,82 |

### Ralenti des Pokémon éloignés (optionnel, mélange)

Le faux joueur est au centre de chaque zone, les Pokémon apparaissent dans un rayon de 56 blocs autour de lui et les
enclos sont à environ 56 blocs : la part de Pokémon « loin » est donc forte dans ce montage. Sur un vrai serveur, elle
dépend de la distance à laquelle Cobblemon fait apparaître les Pokémon (16 à 64 blocs par défaut).

| Réglage | Tick moyen OFF → ON | Médiane |
|---|---|---|
| `always`, au-delà de 48 blocs, 1 tick sur 2 | 35,5 → 29,1 ms (−18,0 %) | 34,7 → 27,6 |
| `always`, au-delà de 32 blocs, 1 tick sur 4 | 35,8 → 22,2 ms (−38,0 %) | 35,0 → 18,9 |

Le régulateur ne change de cran qu'une fois par seconde : la première seconde de chaque bloc « ON » tourne encore
sans ralenti, ce qui sous-estime un peu le gain.

Mode `overload`, cible à 30 ms pour une charge naturelle d'environ 38 ms, 1200 ticks sans alternance : tick moyen
27,5 ms, moyennes par seconde entre 24,6 et 29,9 ms, 6 ticks sur 1200 au-dessus de 50 ms, cran final 5 (au-delà de
40 blocs, 1 tick sur 2), 40 % des ticks de Pokémon sautés.

### Autres environnements

| Environnement | Résultat |
|---|---|
| Sans Lithium (mélange, 3000 Pokémon) | 62,9 → 55,1 ms (−12,3 %), p99 155 → 95 ms ; vérification : 0 différence |
| Mods de l'instance Tropimon sur un serveur dédié (98 mods, sans TropimodClient ni Mega Showdown qui ne démarrent pas sur ce serveur de test), 2 zones, 1600 Pokémon apparus, despawn de Cobblemon actif | 27,4 → 21,2 ms (−22,7 %), p99 92 → 33 ms, 16,00 → 12,10 µs par tick de Pokémon ; vérification : 0 différence |

Fichiers bruts : [mesures/](mesures/) (`crowd_v2_*`, `crowd_verify_*`, `crowd_tropi*`).

### Où part le temps avec le mod actif (mélange, profil JFR, avant les quatre derniers raccourcis)

Le tick des Pokémon fait 79 % du temps du tick serveur. En parts du tick serveur :

| Poste | Part | Dont |
|---|---|---|
| IA (Brain) | 27 % | interrogation des tâches 17 %, expiration des mémoires 4,5 % (avant `memoryExpiryList`), capteurs 3 %, tâches en cours 2,5 % |
| Déplacement et collisions (`travel`) | 18 % | `Entity.move` 14 %, condition « blocs parcourus » 1,7 % (avant `travelRequirementScan`) |
| `baseTick` | 7 % | contact eau / lave 3,6 %, nage 1,3 % |
| Contrôle du mouvement de Cobblemon | 6 % | |
| Poussée entre entités | 6 % | |
| Synchronisation de Cobblemon (delegate) | 3,5 % | |
| Test de despawn | 3 % | |
| Hors Pokémon : suivi des entités (`ChunkMap.tick`) | 8 % | envoi des changements 4 % |
| Hors Pokémon : compte des mobs pour le spawn naturel | 4,7 % | |

Aucune méthode ne dépasse 5 % à elle seule.

## Vérification

`/fasterai verify on` fait tourner chaque raccourci à côté du code d'origine sur les mêmes données et compte les
différences (le code d'origine donne la réponse utilisée). Dernière partie de vérification (mélange, 600 ticks,
Lithium installé, sans Faster Snacks) :

| Raccourci | Comparaisons | Différences |
|---|---|---|
| Chef de troupeau | 83 465 | 0 |
| Cache de palette (réponse en cache contre palette relue) | 12 136 165 | 0 |
| Contact eau / lave | 4 668 377 | 0 |
| Feu / lave | 1 642 910 | 0 |
| Tri du capteur | 86 724 | 0 |
| Bloc sous les pieds | 1 279 068 | 0 |
| Étouffement | 1 749 092 | 0 |
| Tâches du cerveau (mémo « fermé » contre test relu, et tâche restée arrêtée) | 30 867 512 | 0 |
| Capteurs (aucun capteur ne scrute dans un passage sauté) | 1 502 078 | 0 |
| Expiration des mémoires (toute mémoire qui expire est dans la liste) | 773 787 | 0 |
| `onGround()` | 15 154 447 | 0 |
| Condition « blocs parcourus » | 1 766 538 | 0 |
| Contrôle de déplacement en attente | 1 063 889 | 0 |

Avec Faster Snacks installé, la condition « blocs parcourus » est d'abord interceptée par Faster Snacks : le
raccourci de Faster AI ne s'exécute pas (les deux donnent la réponse d'origine).

Le ralenti des Pokémon éloignés n'a pas de vérification : il change volontairement le comportement.

## Anciennes mesures (2026-11-09, version 0.1.0, serveur non épinglé)

| Partie | Ticks mesurés | Tick moyen OFF → ON | p99 | µs par tick de Pokémon |
|---|---|---|---|---|
| Mélange, graine 11 | 2400 | 88,5 → 75,2 ms (−15,1 %) | 214,0 → 100,4 | 27,45 → 22,89 (−16,6 %) |
| Serré, graine 13 | 1200 | 100,4 → 80,4 ms (−19,9 %) | 384,7 → 110,8 | 31,87 → 24,91 (−21,8 %) |
| Dispersé, graine 17 | 1200 | 90,4 → 79,4 ms (−12,2 %) | 196,3 → 98,7 | 27,92 → 24,19 (−13,4 %) |

## Avec Cobblemon Faster Snacks (2026-11-09, serveur non épinglé)

Les deux mods installés ensemble (Faster AI 0.1.0, Faster Snacks 1.0.0), coupés puis activés **ensemble** toutes les
100 ticks dans la même partie. 3000 Pokémon (mélange, graine 21) et 96 snacks (16 par zone) assaisonnés Wacan +
Kelpsy + Oran, posés sur le sol naturel, random ticks ×20 (accélération de Tropimon), `pokeSnackPokemonPerChunk = 2`.
TPS : 1000 ms divisés par la durée réelle des ticks, un tick de moins de 50 ms comptant pour 50 ms (le serveur attend).

| Variante | TPS sans → avec | Tick moyen | p99 | Pire tick | Snacks par tick | Pokémon attirés (sans / avec) |
|---|---|---|---|---|---|---|
| Snacks à côté de la foule, Pokémon attirés gardés | 16,37 → 18,66 | 59,9 → 50,5 ms | 161,8 → 73,9 ms | 193,4 → 82,3 ms | 0,21 → 0,04 ms | 7 / 2 |
| Snacks à côté de la foule, Pokémon attirés retirés aussitôt | 16,05 → 18,23 | 61,4 → 52,7 ms | 160,9 → 75,4 ms | 394,4 → 88,4 ms | 0,58 → 0,22 ms | 37 / 35 |
| Fermes isolées (300 blocs, un joueur sur place), Pokémon retirés aussitôt | 11,31 → 16,09 | 88,1 → 61,2 ms | 220,7 → 93,1 ms | 286,6 → 101,9 ms | 23,9 → 5,6 ms | 753 / 773 |

- Un snack n'attire rien sans joueur à proximité, et abandonne tôt quand sa zone a déjà son plafond de Pokémon : à
  côté d'une foule, ses tentatives coûtent presque rien (médiane 0,06 ms), avec ou sans mod. Le gain vient alors de
  Faster AI.
- Dans une vraie ferme active, chaque tentative coûte ~16 ms (médiane) sans mod : c'est là que Faster Snacks pèse le
  plus. Sa protection de 10 ms par tick a reporté 271 bouchées au tick suivant (aucune perdue : 773 Pokémon attirés
  contre 753).
- Les valeurs absolues varient d'une partie à l'autre (espèces tirées) ; seule la comparaison dans une même partie
  compte.
