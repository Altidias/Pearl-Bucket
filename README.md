# PearlBucket

PearlBucket lets players capture a suspended ender pearl in a bucket, move it safely, and release it somewhere else later.

![Pearl](https://i.imgur.com/SG0a1GC.gif)

## Features

- Capture floating ender pearls with an empty bucket.
- Capture pearls from water or bubble columns that are holding them.
- Empty or dispense the bucket to release the stored pearl again.
- Works with any player-owned pearl.
- Includes an optional client resource pack for the custom pearl bucket model.
- Handles offline owners by storing the pearl until they come back online.

## Installation

1. Download the latest PearlBucket jar.
2. Place it in your server's `plugins` folder.
3. Start or restart the server.
4. If you want the custom item model, also install the included resource pack for players.

PearlBucket is designed for Paper `26.1.x`.

## How To Use

- Hold an empty bucket and right-click a floating ender pearl to capture it.
- You can also bucket the water or bubble column that is supporting the pearl.
- Right-click a block to place the bucketed pearl back into the world.
- Dispensing the pearl bucket also releases the pearl.

When released, the pearl behaves naturally in water, bubble columns, and gravity-based setups.

## Resource Pack

The resource pack is optional, but it is what gives PearlBucket its custom in-game appearance.

- Without the pack, PearlBucket still works normally, but the item will look like a regular water bucket.
- With the pack enabled, online pearl buckets use a custom item model so players can recognize them at a glance.
- The pack is included in the release bundle and is built separately from the plugin jar.
- Players need to download and enable the pack on their client to see the custom model.

For servers that want a polished experience, it is recommended to distribute the pack alongside the plugin.

## For Server Admins

- The item is stored as a `water bucket` with hidden data, so it stays compatible with normal inventory handling.
- Pearl buckets do not stack.
- If the owner is offline, the bucket appears as a normal water bucket until they return.
- If a dropped pearl bucket is destroyed by fire, lava, the void, contact damage, or despawning, the stored pearl is resolved with impact effects.
- The plugin periodically reconciles loaded inventories, containers, dropped items, and dispensers to keep pearl buckets consistent.

### Configuration

PearlBucket uses a standard `config.yml` in the plugin folder.

- `capture-aim-tolerance`: Adjusts how forgiving bucket capture aiming is.

If you do not need to change anything, the default settings should work fine.

## Build From Source

```powershell
.\gradlew.bat clean build releaseBundle
```

This produces:

- The plugin jar in `build/libs/`
- The optional resource pack in `build/resource-pack/`
- A release bundle in `build/distributions/`

## Notes

- The plugin targets Java `25`.
- The included resource pack is optional, but it improves the in-game appearance of the pearl bucket item.
