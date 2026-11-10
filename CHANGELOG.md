# Changelog

## 0.2.0

Six raccourcis exacts de plus (−6,7 % sur le tick en plus de la 0.1.0) et un ralenti optionnel pour les serveurs saturés.

- Tâches du cerveau : une tâche dont les conditions de mémoire ne sont pas remplies n'est plus interrogée tant
  qu'aucune mémoire n'apparaît ou ne disparaît (tous les mobs à cerveau).
- Capteurs du cerveau : les ticks où aucun capteur n'est dû sont sautés ; chaque capteur scrute au même tick qu'avant.
- Expiration des mémoires : seules les mémoires qui peuvent expirer sont parcourues à chaque tick.
- `onGround()` des Pokémon sans les fonctions anonymes imbriquées de Cobblemon.
- Condition d'évolution « blocs parcourus » lue sur place, sans listes temporaires.
- Contrôle de déplacement : en attente, la vitesse inutilisée n'est plus calculée.
- **Nouveau, coupé par défaut** : ralenti des Pokémon sauvages éloignés (`/fasterai slowmotion overload|always|off`),
  qui ne s'enclenche par défaut que si le serveur dépasse la durée de tick cible. Jusqu'à −38 % sur le tick.
- `/fasterai stats` et `/fasterai verify` couvrent les nouveaux raccourcis.
- Vérifié avec et sans Lithium, et avec les mods de l'instance Tropimon.
- Scripts de mesure dans `tools/bench/`.

## 0.1.0

Première version, pour Cobblemon 1.8.4 / Minecraft 1.21.1.

- Recherche de chef de troupeau dans un ordre moins cher, même résultat (supprime les pics qui revenaient chaque seconde).
- Contact eau / lave, feu / lave et bloc sous les pieds : réponses par palette de section, en cache jusqu'à la
  prochaine modification de la section.
- Étouffement (`isInWall`) sans stream ; tri du capteur d'entités proches avec une distance par entité.
- `/fasterai optimize`, `/fasterai stats`, `/fasterai verify`.
