# ci/sonarqube

Ce répertoire n'est qu'un placeholder historique (Phase 0) : la configuration
SonarQube réelle vit ailleurs et ce fichier ne l'a jamais suivie.

- **Provisioning** : rôle Ansible `ansible/roles/sonarqube/` (instance
  conteneurisée, base dédiée, profil Compose `ci`).
- **Déclenchement** : depuis les pipelines Jenkins (`services/*/Jenkinsfile`),
  stage « SonarQube analysis » + « Quality gate », actifs uniquement si la
  variable Jenkins `SONAR_HOST_URL` est configurée — **jamais sur Pull
  Request** : pas d'intégration GitHub App/webhook, l'analyse tourne sur
  `main` au même rythme que les tests (poll SCM, voir `ci/jenkins/README.md`).

Voir `services/README.md` (section CI/CD) pour l'état vérifié et
`ansible/roles/sonarqube/README.md` pour la procédure complète.
