# AGENTS.md — Fog of War

Multiplayer Android top-down tank game (1–6 players via Google Play Services Nearby Connections, `Strategy.P2P_STAR`). Host-authoritative (30 TPS sim, 15 Hz snapshots, ~95 ms client interpolation).

## Tech Stack & Build
- **Language / SDK:** Kotlin 1.9.24, AGP 8.5.2, compileSdk 34, minSdk 26, JDK 17.
- **UI & Render:** Android Views + XML, custom `SurfaceView` + `Canvas` (no external game engines).
- **Network:** Nearby Connections (`...nearby.v4`, kept for discovery compatibility), binary `ByteBuffer` protocol v5.
- **Environment:** No local Android SDK. CI (GitHub Actions) runs unit tests and builds signed release APK (`versionCode = 1000 + GITHUB_RUN_NUMBER`) on every push.
- **Verification commands:**
  - JVM unit tests: `./gradlew testReleaseUnitTest` (requires Android SDK + JDK 17; in this environment run them via CI on push)
  - Check CI build after push: `gh run watch`

## Architecture & Modules
- `core/` — **Pure Kotlin game simulation (NO Android dependencies).**
  - Deterministic 64×64 map generation by `(mapId, GameConfig.MAP_SEED)` (6 maps, no consecutive repeats).
  - Tiles: `EMPTY`, `BRICK` (2 HP), `CRACKED`, `STEEL`, `WATER`.
  - AABB physics, tank push-apart, bullets, shrinking zone, sonar (~4 s pulse), AI bots (4 in solo), grenades (2 charges, 9 s recharge, thrown ~245 px, 1.15 s fuse, R=92 AoE), pickups (`REPAIR`, `POWER`, `LIGHT`), `EffectEvent` queue.
- `net/` — **Networking & persistent session state.**
  - `NearbyNet`: `Application`-scoped singleton surviving `LOBBY` → `IN_GAME` → `ROUND_OVER` → `LOBBY`.
  - `RoomsActivity`: foreground-only discovery; host advertises `RoomDescriptor` (`nick|mapId|players|L/G|code`, **strictly ≤ 64 bytes UTF-8**) with ~1.5 s debounce.
  - Reliable `TileSync` in 200-tile chunks, `Short` snapshot counters, `Hello`/`Welcome` version check, 8 s client snapshot watchdog (covers the 3 s countdown).
- `render/` — **Camera, Fog of War & procedural pixel-art.**
  - Camera ~14 tiles high; FoW via `clipOutPath` for the headlight cone and self circle + grid DDA raycast (`TileMap.raycastLight`), with cached cone geometry.
  - Headlight cone follows **turret** (`turretDir`), not body; stationary tank with headlight off is invisible to sonar; shooting emits orange sound rings visible to all.
  - `SpriteAtlas.kt`: 16×16 ASCII grids → nearest-neighbour `Bitmap` with per-slot tank coloring.
  - `Effects.kt`: tracks, muzzle flash, sparks, smoke, explosions, sound rings, camera shake, barrel recoil.
  - `MapPreviewView`: lobby map preview using the same deterministic generator.
- `render/GameView.kt` (Input) — Twin-stick controls: left stick = body movement (`moveX/Y`), right stick = Brawl Stars aim (tap = auto-aim nearest LoS enemy, drag & release = manual fire), plus `ГРАНАТА` and `LIGHT` toggle buttons.

## Hard Rules for Agents
1. **Keep `core/` pure:** Never import `android.*` inside `core/`. All simulation logic must remain runnable via JVM unit tests.
2. **Protocol compatibility:** Any change to binary network packets must update both Host and Client in the same commit. Bump protocol `VERSION` on breaking changes; keep `SERVICE_ID` stable so old rooms remain discoverable and can be rejected with a clear message.
3. **Do not touch `versionCode`:** Never increment `versionCode` manually in Gradle files; CI manages it.
4. **No binary image assets:** Add new sprites strictly as 16×16 ASCII grids in `SpriteAtlas.kt`.
5. **Decoupled VFX:** Trigger gameplay effects from `core/` via `EffectEvent` and render them strictly inside `Effects.kt`.
6. **Descriptor size limit:** Keep `RoomDescriptor` payload ≤ 64 bytes UTF-8.
