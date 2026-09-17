---
description: Rédige une nouvelle entrée de décision d'architecture pour "lets-travel", au format de docs/architecture-decisions.md
argument-hint: <sujet de la décision>
---

Tu vas rédiger une entrée de décision d'architecture dans
`docs/lets-travel-architecture-decisions.md` pour la décision suivante :

**Sujet** : $ARGUMENTS

Avant d'écrire quoi que ce soit :

1. Relis `docs/lets-travel-architecture-decisions.md` (s'il existe déjà) et
   `docs/architecture-decisions.md` pour respecter le même format et ne pas
   contredire une décision déjà tranchée en Phase 0.
2. Relis les sections concernées de `sujet/lets-travel-sujet.md` et
   `sujet/lets-travel-audit.md` (lecture seule — ne jamais les modifier).
3. Regarde le code existant pertinent (services, entités, migrations) pour
   ancrer la décision dans ce qui existe réellement, pas dans l'abstrait.

Rédige l'entrée avec, dans cet ordre :

- **Décision** — ce qui est tranché, sans ambiguïté.
- **Justification** — pourquoi ce choix plutôt qu'un autre, ancré dans le code
  existant (cite les fichiers/patterns concernés).
- **Ce que je sacrifie** — le tradeoff nommé explicitement, jamais caché.
- **Alternative rejetée** — au moins une, avec la raison du rejet.

Ajoute l'entrée à la fin du document (ou dans la bonne section si elle existe
déjà). Termine par : "STOP — en attente de validation avant d'écrire du code
pour cette phase." N'implémente aucun code dans cette commande : uniquement la
décision écrite.
