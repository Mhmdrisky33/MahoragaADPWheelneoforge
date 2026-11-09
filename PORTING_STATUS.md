# Mahoraga NeoForge 1.21.1 / 21.1.251 port status

## Target
- Minecraft 1.21.1
- NeoForge 21.1.251
- Java 21
- GeckoLib NeoForge 4.7.3

## Changes applied in this worktree
- NeoForge ModDev Gradle build configuration and Java 21 toolchain.
- NeoForge `neoforge.mods.toml` metadata and 1.21.1 pack metadata.
- Migrated the seven packet types used by the supplied source tree from Forge `SimpleChannel` to NeoForge custom payloads (`CustomPacketPayload`, `StreamCodec`, `RegisterPayloadHandlersEvent`, `PacketDistributor`).
- Replaced old `@Mod.EventBusSubscriber` annotations with NeoForge `@EventBusSubscriber` and migrated `Bus.FORGE` to `Bus.GAME`.
- Corrected registry constants to 1.21.1 names (`ITEM`, `MOB_EFFECT`, `ENTITY_TYPE`, `SOUND_EVENT`).
- Restored 75 missing asset files from the supplied 1.20.1 JAR without overwriting files already in the source project.

## Important blockers — not an installable mod yet
1. **No successful Gradle build has been possible.** The environment has Java 21, but cannot resolve `services.gradle.org` (DNS/network unavailable), so Gradle 8.14.3 cannot be downloaded and dependencies cannot be resolved.
2. **The supplied source ZIP is incomplete compared with the supplied JAR.** The JAR contains 169 top-level compiled classes; the ZIP contains source for only 77 top-level classes. 92 top-level classes present in the JAR have no matching Java source in the ZIP. These include blocks, particles, many client render/effect systems, entities, mixins, and additional skill/network classes. Their Forge 1.20.1 bytecode cannot safely be copied into a NeoForge 1.21.1 mod.
3. All remaining 1.21.1 API and GeckoLib compatibility issues still need compiler feedback and runtime testing.
4. Client and dedicated-server launch tests, resource checks, packet tests, and gameplay tests have not been run.

## Do not install this ZIP
This is a source worktree for continuing the port, not a compiled `.jar`. Do not put it in the Minecraft `mods` folder.

## Needed to finish the full port
- The complete source repository matching the supplied `mahoraga-1.0.0.jar` (including the missing Java source files), or confirmation that the reduced source project is the intended feature set.
- A build environment with internet access to download Gradle and Maven dependencies.


## Follow-up repository upload (2026-11-05)
The newly uploaded GitHub repository ZIP was inspected. It contains 77 Java source files and is the same source set as the earlier `mahoraga-mod-main.zip`; it does include the Gradle wrapper and complete repository-level build files, but it does not add the missing Java sources found in the original JAR comparison.

A build was attempted with `./gradlew build --no-daemon`. It stops before Gradle configuration because the wrapper cannot resolve `services.gradle.org` (`UnknownHostException`). Therefore there is still no compiler output and no installable JAR.
