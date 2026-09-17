---
description: Crée la prochaine migration Flyway (identity/payment-service) ou une contrainte Neo4j (travel-service)
argument-hint: <service> <nom_de_la_migration>
---

Service et nom : $ARGUMENTS

Si le service est `identity-service` ou `payment-service` :

1. Liste `services/<service>/src/main/resources/db/migration/` pour trouver le
   dernier numéro `Vn__*.sql` utilisé.
2. Crée `V{n+1}__<nom>.sql` avec un commentaire d'en-tête expliquant ce que la
   migration fait et pourquoi (même style que les migrations existantes, ex.
   `V3__add_role.sql`), sans modifier les migrations précédentes.

Si le service est `travel-service` :

- Il n'y a pas de Flyway côté Neo4j : dis-le, et ajoute plutôt la/les
  contraintes nécessaires dans `Neo4jSchemaInitializer` avec
  `CREATE CONSTRAINT ... IF NOT EXISTS` (idempotent), en conservant celles déjà
  présentes.

Dans tous les cas, n'exécute pas la migration toi-même (pas de `docker compose
up`) — la création du fichier suffit, elle sera appliquée au prochain démarrage
du service.
