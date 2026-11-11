# Mahoraga NeoForge 1.21.1 / 21.1.251 port status

## Target
- Minecraft 1.21.1
- NeoForge 21.1.251
- Java 21
- GeckoLib NeoForge 4.7.3

## Build configuration and Mixin
- Uses NeoForge ModDevGradle 2.0.107 with Java 21; the Forge-oriented `org.spongepowered.mixin` / MixinGradle plugin is removed.
- Mixin is preserved: all four Mixin Java classes remain in the source tree and are listed in `mixins.mahoraga.json`.
- The config is registered through NeoForge 1.21.1's `[[mixins]]` entry in `META-INF/neoforge.mods.toml`.
- NeoForge 1.21.1 uses official Mojang names at runtime, so this NeoForge config does not need a Forge-style refmap or Mixin annotation processor. The refmap entry and AP were removed from the migration branch after CI showed the AP could not find SRG mappings.
- Removed the legacy `MixinConfigs` JAR manifest entry from the migration branch so the config has one registration path (the NeoForge TOML).
- The `META-INF` directory case is corrected for `neoforge.mods.toml` and the access transformer.

## Verified build — 2026-11-10
- GitHub Actions workflow run #291 for branch `fix/neoforge-1.21.1-migration`, commit `9ceb1739e03ee83127a80b5814bbd28da2563ec1`.
- Java 21 setup succeeded; `./gradlew build --no-daemon` completed with **BUILD SUCCESSFUL** in 19 seconds.
- The JAR artifact was uploaded as `mahoraga-neoforge-1.21.1` (artifact ID `13736267150`).
- Compilation completed with 34 warnings, mainly deprecated `EventBusSubscriber.bus()` usage and existing deprecation/unchecked-operation notices.
- Gradle reported `test NO-SOURCE`; this was a compile/package build, not a client or dedicated-server launch test.
- The manifest cleanup above is a follow-up change after that successful run; CI must run again on the updated branch to verify this last cleanup.

## Validation still outstanding
- No Minecraft client launch, dedicated-server launch, in-game Mixin application test, or gameplay/packet test has been run.
- The original supplied source ZIP is incomplete compared with the supplied 1.20.1 JAR: 77 top-level Java source files versus 169 top-level compiled classes, leaving 92 classes without matching source. These include block, particle, client render/effect, entity, mixin, skill, and network code. Do not copy Forge 1.20.1 bytecode into the NeoForge 1.21.1 project.
- The CI build verifies the reduced source set in this branch; it does not prove full feature parity with the original JAR.

## Local build environment
- This container has Java 21, but `./gradlew build --no-daemon` cannot start because DNS cannot resolve `services.gradle.org` (`UnknownHostException`). This is a wrapper download/network blocker, not a Gradle configuration or Java compilation result.
- GitHub Actions has internet access and successfully resolved the wrapper and Maven dependencies in the verified run.

## Branch and artifact
- The changes are on the review branch `fix/neoforge-1.21.1-migration` / draft PR #2; `main` has not been modified.
- The uploaded build artifact is available from [GitHub Actions run #291](https://github.com/Mhmdrisky33/MahoragaADPWheelneoforge/actions/runs/42757693493).
- The source ZIP is not an installable mod. Use only a verified JAR artifact for testing, and treat it as a test build until client/server runtime checks pass.

## Next steps
1. Re-run CI after the manifest cleanup and confirm the JAR still packages the Mixin config and classes.
2. Run the mod on both client and dedicated server and verify all four Mixins apply.
3. Obtain the complete original Java source set (or explicitly accept the reduced feature set) before claiming a full port.
