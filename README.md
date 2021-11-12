# SonarQube Permissions Updater

Will make all projects private and apply a given permissions template to them. 

Usage:
```bash
./gradlew run --args="-b <SQ server> -t <some permission template ID>"
```

E.g.:
```bash
SONARQUBE_TOKEN=abc123 ./gradlew run --args="-b https://peach.sonarsource.com -t default_template_for_projects"
```
Use `-h` or `--help` for help.
