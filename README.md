# JMChunkBridge — Paper 26.2 / Fabric 26.3

Source project for a server→client **surface-map** bridge using JourneyMap 6.0.10.

## Important build/test status

**This is implementation source, not a verified working release.** The code contains the end-to-end Paper→Fabric→JourneyMap rendering path, unlike the earlier experimental packet logger. The GitHub Action is configured to compile the project, but **the action has not been run** and neither JAR has been tested against a running game. This environment cannot download the Paper/Fabric dependencies, so compile or runtime errors are possible. Do not install on a production server until the action builds cleanly and you've tested on a copy of the world.

## Installation (after successful GitHub build)

1. Upload repository contents to GitHub. Go to **Actions → Build Paper and Fabric JARs → Run workflow** (or push a commit). Download the `JMChunkBridge-jars` artifact.
2. Put the resulting `jmchunkbridge-paper-26.2-*.jar` in Paper `plugins/` and restart.
3. Put `jmchunkbridge-fabric-26.3-*.jar` in the Fabric client's `mods/`, with JourneyMap 6.0.10 and Fabric API; restart the client.
4. Use Chunky to generate your area **first**. This bridge never generates ungenerated chunks.
5. Join using the modded client and run `/jmbridge start 3000 0 0` for the 6000×6000 centered around origin. Without coordinates, center is the player's current position.
6. `/jmbridge status` displays counters; `/jmbridge stop` stops. Remain logged in until completion.

## What it does

- Paper loads already-generated chunks using `getChunkAtAsync(x,z,false)`.
- Streams five exposed block-state layers + column height + biome, in compressed and palette-encoded payloads.
- One in-flight chunk per player; next chunk starts after the client ACKs rendering. No teleportation or flying.
- Fabric reconstructs an in-memory chunk snapshot, invokes JourneyMap's own `ChunkRenderController`, and periodically flushes its region image cache.
- No persistent Chunky integration/API is required, since Chunky only pregenerates the world.

## Limitations

- **Surface images only** (no caves/full subterranean cartography).
- The world must be joinable by a 26.3 client. A server running 26.2 typically needs an appropriate protocol compatibility layer; this project does not provide one.
- If a scan stops, starting again restarts from the beginning; no resume cursor is persisted.
- Some world features, light-dependent shading and very tall transparent structures may differ because only five top layers are sent; exact vanilla chunk reconstruction is not attempted.
- Blocks added or renamed between 26.2 and 26.3, or belonging to server-side custom registries, may fall back to stone on the client.
- For very large maps, one ACK per chunk is intentionally conservative and can be slow.
- Map results are local to the client running the Fabric mod; other clients will not receive the saved tiles automatically.

## Building locally

Requires JDK 25 and Gradle 9.6.0 or newer. `gradle :paper:build :fabric:build`.

The supplied JourneyMap mod is a **compile-only** dependency from Modrinth Maven; its classes are not redistributed in the bridge. Uploaded third-party JARs are not included.
