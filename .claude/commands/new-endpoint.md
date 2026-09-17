---
description: Scaffold un endpoint (controller + service + DTO + exception + test) dans un des 3 services Spring Boot, au pattern existant
argument-hint: <identity-service|payment-service|travel-service> <ressource>
---

Service et ressource ciblés : $ARGUMENTS

Avant d'écrire du code, lis un contrôleur existant **du service ciblé** (ex.
`UserController` pour identity-service, `PaymentController` pour
payment-service, `DestinationController` pour travel-service) pour reproduire
exactement :

- Le check de rôle/token **ad hoc en première ligne de méthode**
  (`authService.requireXxx(header)` / `tokenValidationService.requireValidToken`
  / `requireUserOrServiceToken`) — ne jamais introduire `@PreAuthorize` ni de
  filter chain Spring Security, ce serait incohérent avec le reste du repo.
- Le filtrage soft-delete sur tous les reads (`deletedAt IS NULL` en JPA,
  `WHERE x.deletedAt IS NULL` en Cypher), sur **chaque hop** s'il y a une
  traversée de relation.
- L'entrée correspondante dans le `GlobalExceptionHandler` du service pour
  toute nouvelle exception métier.
- Le pattern de test d'intégration Testcontainers déjà utilisé dans ce service
  (`src/test/java/.../*IntegrationTest.java`) — pas de mock de la base.

Scaffold ensuite : controller, service, DTO(s) de requête/réponse, exception
métier si besoin, entrée `GlobalExceptionHandler`, et un test d'intégration
Testcontainers couvrant au moins le cas nominal et un cas de refus
d'autorisation. Ne touche à aucun autre service que celui demandé.
