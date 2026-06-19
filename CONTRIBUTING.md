# Contributing

Thanks for your interest in the Proxmox Cloud plugin. Bug reports, feature requests, and pull requests are all welcome.

## Reporting issues

File issues at https://github.com/jenkinsci/proxmox-cloud-plugin/issues. For bugs, include the Jenkins version, the plugin version, the Proxmox VE version, and the relevant controller and agent logs. Agent provisioning and lifecycle decisions are logged under `org.jenkinsci.plugins.proxmox`; a System Log recorder for that logger at `FINE` captures the useful detail.

## Building

The build needs JDK 21 and Maven.

```bash
mvn clean verify                # full build and tests
mvn clean package -DskipTests   # build the HPI only
```

The artifact is `target/proxmox-cloud.hpi`. To try it on a running Jenkins, install it via Manage Jenkins > Plugins > Advanced > Deploy Plugin, or drop the HPI into `$JENKINS_HOME/plugins/` and restart.

## Tests

Tests use JUnit 5 (Jupiter) with the Jenkins test harness and WireMock for the Proxmox REST API. Run them with `mvn test`. Please cover new behaviour with tests; the existing suites under `src/test/java` show the patterns for the API client, agent lifecycle, and config sync.

## Pull requests

Pull requests are only considered after an issue has been filed and agreement has been reached with the maintainers that it describes a confirmed bug or accepted feature. PRs that skip this step will not be reviewed or merged.

- Branch from `main` and open the PR against `main`.
- Keep each PR to one logical change.
- Match the surrounding code style. `mvn verify` runs Spotless, SpotBugs, and the enforcer rules, so run it before pushing.
- Label the PR to reflect the change (`enhancement`, `bug`, `developer`, and so on). Release notes are drafted from PR titles and labels.

## Releases

Releases are cut from merged, labelled PRs via the Jenkins CD workflow (JEP-229). See the [plugin release documentation](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).

## Code of Conduct

This project follows the [Jenkins Code of Conduct](https://www.jenkins.io/project/conduct/).
