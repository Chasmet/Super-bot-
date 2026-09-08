# Super Bot 1.3.22 — programmation séquentielle

Parcours : métadonnées vérifiées → Plus d'options → date → heure → minutes → deux lectures stables → Terminé → résumé de programmation vérifié → Publier une seule fois → confirmation explicite TikTok → menu Super Bot → mission suivante.

- Le code Java exécuté est directement versionné. Aucun remplacement de sources dans preBuild.
- Les colonnes numériques sont séparées selon les positions réellement exposées. La ligne centrale ne dépend plus du libellé mobile « aujourd'hui ».
- Les roues sont déplacées une ligne à la fois puis relues. Une colonne illisible ou immobile suspend le scénario avec un diagnostic.
- Les boutons exigent un libellé exact, visible et activé, avec secours tactile au centre du nœud.
- Le clic Publier est persisté avant exécution pour empêcher une répétition après interruption.
- Sans confirmation explicite, la mission reste en attente/suspendue et bloque la suivante.
- Le retour au menu est vérifié par MainActivity avant le résultat final envoyé au MCP.
- L'application peut rouvrir uniquement le réseau cible de sa mission ; les gestes restent limités aux applications sociales.
- Un fichier explicitement nommé mais absent n'est plus remplacé par la dernière vidéo.

Serveur de production : Chasmet/Asset-3d-asset-2.5d, mcp/src/superbot.ts, route /superbot. Le serveur autonome dans ce dépôt utilise la même version compilée.

Le protocole 2 distingue delivered, running, paused, completed. Les anciens résultats publication_dispatched ne terminent plus une publication. Le serveur reste basé sur une mémoire de processus : une mission inconnue après redémarrage nécessite une réconciliation, jamais une nouvelle publication automatique.

Validation : assertions Java sur dates/heures et confirmations négatives ; tests HTTP du cycle MCP ; assembleDebug et lintDebug ; vérification du certificat existant avant publication GitHub Release. Une installation sur téléphone et un essai TikTok restent nécessaires pour confirmer les gestes sur la version de TikTok utilisée.
