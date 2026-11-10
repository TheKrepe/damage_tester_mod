# Notes for Claude

- Target Minecraft version: 26.3 (`minecraft_version` in gradle.properties).
- Every time the author asks for a change/build of the mod, first run the
  "Check loader versions" workflow (`.github/workflows/versions.yml`), bump
  `neoforge_version`, `fabric_loader_version` and `fabric_version` in
  gradle.properties to the newest versions for the current Minecraft version
  (stable only; keep `neo_form_version` unless a newer non-snapshot `26.3-N`
  appears; keep Loom / ModDevGradle unless a stable release appears), build,
  and tell the author what was updated (old -> new).
- Give the author the jar(s) from the `ci-jars` branch and the GitHub artifact link.
- The author is Star_1mp; replies in Russian.
