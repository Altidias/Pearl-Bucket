# PearlBucket (WIP)

Paper plugin for Minecraft/Paper `26.1.x` that lets players scoop suspended ender pearls into water buckets and place them somewhere else.

## Build

```powershell
.\gradlew.bat clean build releaseBundle
```

The standard `build` now produces both the plugin jar and the optional client resource pack.

The plugin jar is written to:

```text
build/libs/PearlBucket-0.1.9.jar
```

The optional client resource pack is written to:

```text
build/resource-pack/PearlBucket-resource-pack-0.1.9.zip
```

A GitHub-ready release bundle containing the jar, resource pack, and README is written to:

```text
build/distributions/PearlBucket-0.1.9-release.zip
```

## Behavior

- Right-click a floating ender pearl with an empty bucket, or bucket the water/bubble column containing it, to capture both the water and the pearl.
- The captured pearl bucket is unique and cannot stack.
- With the included resource pack enabled, online pearl buckets use a custom pearl-in-water-bucket item model.
- Any player's pearl can be captured.
- Emptying or dispensing the bucket releases a stationary pearl into water so water, gravity, and bubble columns move it naturally. Normal floor/wall placements create source water; free drops into air or existing water create non-source water/no extra source.
- If the owner is offline, the item appears as a normal water bucket while retaining hidden pearl data.
- When the owner returns, loaded stored buckets become pearl buckets again.
- If a dropped pearl bucket is destroyed by lava, fire, contact damage, the void, or despawn, the stored pearl impacts at the item's location with pearl-style teleport sound, portal particles, ender-pearl teleport cause, and pearl damage.
- Offline impacts are saved in `plugins/PearlBucket/config.yml` and resolved with the same impact effects when that player next joins.

## Notes

The plugin periodically reconciles loaded player inventories, open inventories, dropped items, and loaded container blocks. Buckets stored in unloaded chunks keep their hidden item data and are recovered when loaded again.
