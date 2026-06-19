# Development

AgentHub is a JetBrains IDE plugin built with Gradle and the IntelliJ Platform Gradle Plugin. This page covers building the plugin from source.

## Requirements

- **JDK 17+** (a recent GraalVM or Temurin build works)
- **Gradle** — use the bundled wrapper (`gradlew` / `gradlew.bat`); no separate install needed
- A JetBrains IDE **2025.1+** (platform build `251+`) to run/test the plugin

## Build the plugin

```bash
# Produce the installable plugin ZIP
./gradlew buildPlugin
```

The output lands in `build/distributions/agenthub-<version>.zip`. Install it via
**Settings/Preferences > Plugins > ⚙ > Install Plugin from Disk…**.

## Run in a sandbox IDE

```bash
# Launch a sandbox IDE with the plugin pre-installed
./gradlew runIde
```

## Tests

```bash
./gradlew test
```

## Code formatting

```bash
./gradlew ktlintFormat
```

## Documentation

The docs site is built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install mkdocs-material mkdocs-awesome-pages-plugin mkdocs-rss-plugin
mkdocs serve   # preview at http://localhost:8000
```

Pushing to `main` triggers the GitHub Actions workflow, which runs `mkdocs build` and
deploys the site to GitHub Pages automatically.