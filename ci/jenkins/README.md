# ci/jenkins

Ce répertoire n'est qu'un placeholder historique (Phase 0) : la configuration
Jenkins réelle vit ailleurs et ce fichier ne l'a jamais suivie.

- **Pipelines** : `services/{identity,payment,travel}-service/Jenkinsfile`
  (`./mvnw test`, puis analyse SonarQube + quality gate conditionnées à
  `SONAR_HOST_URL`).
- **Provisioning des jobs** : rôle Ansible `ansible/roles/jenkins/` (rend les
  `config.xml` depuis `templates/job-pipeline.xml.j2`, un par service).
- **Déclenchement** : poll SCM opt-in (`jenkins_scm_poll_enabled`, voir
  `ansible/roles/jenkins/defaults/main.yml` pour la justification détaillée du
  choix poll-plutôt-que-webhook), jamais un déclenchement par Pull Request —
  ni multibranch, ni webhook GitHub configurés.

Voir `services/README.md` (section CI/CD) pour l'état vérifié et
`ansible/roles/jenkins/README.md` pour la procédure complète.
