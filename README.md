# Cobblemon Faster AI

Mod Fabric pour **Cobblemon 1.8.4 (Minecraft 1.21.1)** qui réduit ce que coûte au serveur un grand nombre de Pokémon
sauvages (IA, déplacements, contacts avec les blocs), **sans changer leur comportement** : mêmes décisions, mêmes
déplacements, au même tick.

Une seule fonction fait exception, et elle est **coupée par défaut** : le [ralenti des Pokémon éloignés](#ralenti-des-pokémon-éloignés-optionnel),
qui soulage le serveur quand il sature.

## En bref

Mesuré sur 3000 Pokémon (6 zones, Lithium installé), en alternant mod coupé / actif toutes les 100 ticks dans la même
partie :

| Répartition des 3000 Pokémon | Tick moyen sans → avec | Coût par Pokémon | Pics (1 % des pires ticks) | TPS |
|---|---|---|---|---|
| Mélange : dispersés + 2 chunks à 100 par zone | 44,9 → 36,3 ms (−19 %) | −23 % | 113 → 48 ms (−58 %) | 18,8 → 20,0 |
| Tous serrés : 125 par chunk | 49,1 → 37,6 ms (−23 %) | −28 % | 187 → 51 ms (−73 %) | 17,6 → 20,0 |
| Tous dispersés | 46,2 → 37,7 ms (−18 %) | −21 % | 104 → 50 ms (−52 %) | 18,8 → 20,0 |

- Le plus gros gain porte sur les **pics** : toutes les 20 ticks (chaque seconde), chaque Pokémon d'une espèce à troupeau cherchait un
  chef parmi tout ce qu'il voit, tous au même tick. Ces ticks-là ne dépassent plus ~50 ms au lieu de 100 à 190 ms.
- **Exact** : chaque raccourci se vérifie en jeu (`/fasterai verify on`) contre le code d'origine. 0 différence sur
  plus de 70 millions de comparaisons dans la dernière vérification (détail dans [docs/MESURES.md](docs/MESURES.md)).
- **Ce qu'il ne fait pas** : 3000 Pokémon restent lourds. Ces chiffres viennent d'un processeur rapide (cœurs
  « performance » d'un i7-13700KF) ; sur des cœurs deux fois plus lents, comptez le double, donc au-dessus des 50 ms
  qui font 20 TPS. Le mod retire du travail inutile ; par défaut il ne ralentit ni n'endort aucun Pokémon. Ce qui
  reste est du vrai travail, réparti en dizaines de petits postes dont aucun ne dépasse 5 % : pour aller plus loin
  il faut accepter le ralenti ci-dessous.

## Installation

- **Serveur** : déposer `cobblemon-faster-ai-<version>.jar` dans `mods/`, avec Fabric API et Cobblemon.
- **Client** : facultatif. Le mod n'ajoute ni contenu ni échange réseau ; les clients sans le mod se connectent
  normalement. En solo, l'installer côté client suffit (le serveur intégré l'utilise).

| Requis | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16 ou plus |
| Fabric API | toute version pour 1.21.1 |
| Cobblemon | 1.8.4 (`~1.8.4` : 1.8.4 et suivantes de la branche 1.8) |

Compatible avec Lithium (mesures faites avec, et vérifié aussi sans), et pensé pour cohabiter avec Cobblemon Faster
Snacks. Vérifié en plus sur un serveur chargé avec les mods de l'instance Tropimon (98 mods dont Lithium, ModernFix,
ScalableLux, Noisium, Krypton, FerriteCore et les extensions Cobblemon) : 0 différence, −23 % sur le tick.

## Ce que fait le mod

Les raccourcis marqués « tous les mobs » s'appliquent à tout mob doté d'un cerveau (Pokémon, PNJ de Cobblemon,
villageois, piglins...) ; les autres ne touchent que les Pokémon (`PokemonEntity`).

| Optimisation | Ce que faisait le jeu | Ce que fait le mod | Depuis |
|---|---|---|---|
| Recherche de chef de troupeau | Toutes les 20 ticks, un rayon de vue (raycast) vers chaque entité visible, puis seulement « est-ce un chef possible ? » | Les mêmes tests dans un ordre moins cher : espèce, filtre de Cobblemon, puis rayon de vue seulement si l'entité deviendrait le chef retenu | 0.1.0 |
| Contact eau / lave | Lecture de chaque bloc de la hitbox, pour l'eau puis pour la lave, à chaque tick | Si la palette des sections autour ne contient aucun bloc d'eau (ou de lave), la réponse est « aucun » sans lire les blocs | 0.1.0 |
| Bloc sous les pieds | 64 blocs lus (cube 4 × 4 × 4) à chaque tick pour chaque Pokémon au sol | Les blocs du bord ne comptent que s'ils ont une forme qui dépasse (clôture, muret) : sautés quand la palette n'en contient pas | 0.1.0 |
| Feu / lave après un déplacement | Lecture des blocs de la hitbox après chaque mouvement | Même principe de palette | 0.1.0 |
| Étouffement (`isInWall`) | Un flux (stream) Java pour 1 à 4 blocs, chaque tick | Une boucle simple sur les mêmes blocs | 0.1.0 |
| Tri des entités proches (capteur) | Deux calculs de distance par comparaison | Une distance par entité, même tri stable, même ordre | 0.1.0 |
| Tâches du cerveau (tous les mobs) | À chaque tick, chaque tâche de l'activité en cours (une quinzaine par Pokémon) est interrogée : « peux-tu démarrer ? » La plupart répondent non d'après leurs seules conditions de mémoire (« a une cible », « a été blessé »...) | Une tâche dont les conditions de mémoire ne sont pas remplies n'est plus interrogée tant qu'aucune mémoire du cerveau n'apparaît ou ne disparaît : 60 % des interrogations de ces tâches évitées | 0.2.0 |
| Capteurs du cerveau (tous les mobs) | À chaque tick, le compte à rebours de chaque capteur est décrémenté ; un capteur ne scrute que toutes les 20 ticks | Les ticks où aucun capteur n'est dû sont sautés d'un bloc, et les comptes à rebours remis à jour avant le passage suivant : chaque capteur scrute au même tick qu'avant (86 % des passages évités) | 0.2.0 |
| Expiration des mémoires (tous les mobs) | À chaque tick, toutes les mémoires du cerveau sont parcourues pour décompter les quelques-unes qui expirent | Seules les mémoires qui peuvent expirer sont parcourues | 0.2.0 |
| `onGround()` des Pokémon | Appelé une dizaine de fois par Pokémon et par tick, à travers trois fonctions anonymes imbriquées de Cobblemon | Les mêmes lectures et le même appel au comportement de monture, écrits à plat | 0.2.0 |
| Condition d'évolution « blocs parcourus » | À chaque tick, trois listes temporaires (évolutions triées, conditions aplaties, conditions filtrées) pour savoir si l'espèce a cette condition | Les mêmes ensembles lus sur place | 0.2.0 |
| Contrôle de déplacement en attente | À chaque tick, la vitesse du Pokémon est calculée (expression MoLang, pose, attribut, racine carrée), même quand aucun déplacement n'est demandé et qu'elle ne sert pas (2 ticks sur 3) | En attente, seul ce que fait réellement Cobblemon dans ce cas est exécuté ; tout autre cas (déplacement, combat, virage) passe par son code | 0.2.0 |

Mesurés ensemble, les six ajouts de la 0.2.0 retirent 6,7 % du tick en plus de ceux de la 0.1.0 (37,9 → 35,4 ms).
Mesuré seul, le contact eau / lave en retire 5 % ; l'étouffement, lui, n'apporte pas de gain mesurable.

Les réponses « par palette » sont mises en cache par section de chunk et oubliées à chaque modification de la section :
une réponse en cache décrit toujours la palette actuelle (vérifié : 0 réponse périmée sur 12 millions de contrôles).

Pourquoi c'est exact :

- **Palette** : une section de chunk (16 × 16 × 16) garde la liste des états de bloc qu'elle contient. Si aucun état
  de la liste n'est de l'eau, aucun bloc de la section n'en est. Le mod ne répond donc « aucun » que quand le jeu
  aurait répondu « aucun » ; dans tous les autres cas, c'est le code de Minecraft qui tourne.
- **Troupeau** : les étapes déplacées ne font que lire l'état du jeu, et une entité qui ne deviendrait pas le chef
  retenu ne change rien au résultat, qu'elle soit visible ou non. Seuls les rayons de vue dont la réponse ne servait à
  rien sont évités.
- **Bloc sous les pieds** : mêmes blocs, mêmes tests, même règle de départage ; seul l'ordre de visite change, et la
  réponse n'en dépend pas.
- **Tâches du cerveau** : seules deux formes de tâches sont concernées, celles dont le premier geste est un test de
  mémoires lisible de l'extérieur (les tâches « OneShot » construites par `BehaviorBuilder` à partir de mémoires
  présentes / absentes, et les `Behavior` qui gardent le test d'entrée de Minecraft). Quand ce test échoue, la tâche
  refuse sans rien exécuter d'autre ; et il ne peut pas réussir tant qu'aucune mémoire n'apparaît ou ne disparaît.
  Toute autre tâche est interrogée comme avant.
- **Capteurs, mémoires, `onGround`, évolution, contrôle en attente** : le même calcul sur les mêmes données, sans les
  étapes dont le résultat n'était pas utilisé.

## Ralenti des Pokémon éloignés (optionnel)

**Coupé par défaut. C'est la seule fonction du mod qui change ce que font les Pokémon.**

Un Pokémon sauvage loin de tout joueur ne reçoit plus qu'un tick sur N. Il prend les mêmes décisions et suit les mêmes
règles, au ralenti : rien n'est approximé, le temps passe simplement moins vite pour lui. Son âge pour le despawn
continue de compter en ticks réels.

Ne sont jamais ralentis : les Pokémon possédés (équipe, pâturage), en combat, occupés (capture, évolution), blessés
ou qui se souviennent d'un attaquant, en feu, montés, en laisse, en chute, ceux qui ont une cible, ceux qui viennent
d'apparaître, et tout ce qui se trouve à moins de la distance minimale d'un joueur.

Deux modes :

- **`overload` (recommandé)** : le ralenti ne s'enclenche que si le serveur sature. Une fois par seconde, la durée
  moyenne des ticks est comparée à la cible (45 ms par défaut) : au-dessus, le ralenti monte d'un cran (d'abord les
  Pokémon à plus de 96 blocs à un tick sur deux, puis 80, 64, 48, 40, 32 blocs, puis un tick sur 3, sur 4) ; sous
  85 % de la cible, il redescend d'un cran. Tant que le serveur tient ses 20 TPS, rien ne change. Sans cette
  fonction, un serveur saturé ralentit tout pour tout le monde ; avec, il fait porter le ralentissement sur les
  Pokémon dont personne n'est proche.
- **`always`** : toujours au cran le plus fort (distance minimale et intervalle maximal de la config).

Mesuré sur le mélange de 3000 Pokémon, joueurs au centre de chaque zone :

| Réglage | Tick moyen sans → avec |
|---|---|
| Toujours, au-delà de 48 blocs, 1 tick sur 2 | 35,5 → 29,1 ms (−18 %) |
| Toujours, au-delà de 32 blocs, 1 tick sur 4 | 35,8 → 22,2 ms (−38 %) |
| `overload` avec une cible à 30 ms (charge naturelle : ~38 ms) | tenu à 27,5 ms, autour du cran 5 (au-delà de 40 blocs, 1 tick sur 2) |

Pour l'activer : `/fasterai slowmotion overload` (ou `farSlowMotion: true` dans la config).

## Ce qui a été étudié et écarté

- **Faire réfléchir les Pokémon moins souvent sans ralentir le reste** (IA une fois sur N) : certaines tâches de
  Cobblemon ne se déclenchent qu'à des ticks précis (multiples de 20 pour les troupeaux), d'autres sont des tirages
  aléatoires à chaque tick, d'autres comptent leurs appels. Certains Pokémon ne chercheraient plus jamais de troupeau,
  d'autres se promèneraient moins souvent ou attendraient plusieurs secondes avant de marcher. Le ralenti optionnel
  ci-dessus évite ces pièges : tout ralentit ensemble, donc tout reste cohérent.
- **Sauter le déplacement des Pokémon immobiles** : un tiers seulement des ticks de Pokémon sont « au repos », et
  prouver qu'un déplacement nul ne déclenche rien (plaques, magma, baies...) demande d'examiner chaque bloc autour.
- **Distance au joueur le plus proche du despawn de Cobblemon** (une racine carrée par joueur, par Pokémon et par
  tick) : réécrit avec une seule racine, aucun gain mesurable même avec 100 joueurs. Retiré.
- **Recherche de chemin**, **collisions de Lithium**, **poussée entre Pokémon** (~5 % chacun) : du vrai travail,
  déjà optimisé par Lithium ou interne à Cobblemon.
- **Compte des mobs pour le spawn naturel de Minecraft** (~4 %) : parcourt toutes les entités à chaque tick ; le
  rendre incrémental demanderait de modifier le suivi des entités du serveur.
- **Lumière** : un Pokémon ne déclenche aucun calcul de lumière côté serveur.
- **Synchronisation de Cobblemon** (~3 %) : une série de petites mises à jour par tick, trop liées aux détails
  internes de Cobblemon pour être réécrites proprement.

## Commandes

Toutes demandent le niveau de permission 3.

| Commande | Effet |
|---|---|
| `/fasterai optimize [true\|false]` | Active ou coupe tout le mod (sauvegardé dans la config) |
| `/fasterai stats [reset]` | Combien de fois chaque raccourci a répondu, les différences trouvées en mode vérification, l'état du ralenti |
| `/fasterai verify on\|off` | Mode vérification : chaque raccourci tourne à côté du code d'origine, c'est la réponse d'origine qui est utilisée, et toute différence est comptée et écrite dans le log. Plus lent : pour vérifier, pas pour jouer |
| `/fasterai slowmotion` | État du ralenti : mode, cran actuel, durée des ticks, part des ticks sautés |
| `/fasterai slowmotion off\|overload\|always` | Coupe le ralenti, ou l'active en mode « seulement en surcharge » ou « toujours » |
| `/fasterai slowmotion target <ms>` | Durée de tick au-dessus de laquelle le ralenti monte (5 à 200) |
| `/fasterai slowmotion distance <blocs>` | Distance à un joueur en dessous de laquelle rien n'est ralenti (8 à 256) |
| `/fasterai slowmotion interval <ticks>` | Ralenti le plus fort autorisé : 1 tick sur ce nombre (2 à 20) |

## Configuration

`config/cobblemon_faster_ai.json`, créé au premier lancement :

| Clé | Défaut | Rôle |
|---|---|---|
| `enabled` | `true` | Interrupteur général (`false` = Cobblemon et Minecraft exactement comme sans le mod) |
| `herdLeaderSearch` | `true` | Recherche de chef de troupeau |
| `fluidSectionCheck` | `true` | Contact eau / lave |
| `fireSectionCheck` | `true` | Feu / lave après un déplacement |
| `supportingBlockScan` | `true` | Bloc sous les pieds |
| `inWallScan` | `true` | Étouffement |
| `sensorSortKeys` | `true` | Tri du capteur d'entités proches |
| `brainTaskGates` | `true` | Tâches du cerveau |
| `sensorSchedule` | `true` | Capteurs du cerveau |
| `memoryExpiryList` | `true` | Expiration des mémoires |
| `flatOnGround` | `true` | `onGround()` des Pokémon |
| `travelRequirementScan` | `true` | Condition d'évolution « blocs parcourus » |
| `idleMoveControl` | `true` | Contrôle de déplacement en attente |
| `farSlowMotion` | **`false`** | Ralenti des Pokémon éloignés (change le comportement) |
| `farSlowMotionOnlyWhenOverloaded` | `true` | `true` : seulement en surcharge ; `false` : toujours |
| `farSlowMotionTargetMspt` | `45.0` | Durée de tick (ms) au-dessus de laquelle le ralenti monte |
| `farSlowMotionMinDistance` | `32` | Aucun ralenti à moins de cette distance (blocs) d'un joueur |
| `farSlowMotionMaxInterval` | `4` | Ralenti le plus fort : 1 tick sur ce nombre |

## Compiler

```
cp gradle.properties.example gradle.properties   # puis ajuster les chemins locaux si besoin
./gradlew build                                   # build/libs/cobblemon-faster-ai-<version>.jar
./gradlew build -Pbench                           # version de mesure (/fasterai bench), pour un monde de test
```

La version de mesure ajoute `/fasterai bench crowd ...`, qui fait apparaître des milliers de Pokémon et construit des
enclos en barrière : **à n'utiliser que sur un monde de test**. La méthode et les scripts (`tools/bench/`) sont décrits
dans [docs/MESURES.md](docs/MESURES.md).
