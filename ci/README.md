# ci/

CI/CD. **Contrainte 8 Go : lancée à la demande**, pas en permanence (ne cohabite pas
avec la stack applicative complète). Cf. `docs/architecture-decisions.md` §8.

| Dossier | Rôle |
|---|---|
| `jenkins/` | Build + tests unitaires par PR, orchestration du pipeline |
| `sonarqube/` | Analyse qualité de code, gate sur les PR |

> Placeholders Phase 0 — aucune config écrite.