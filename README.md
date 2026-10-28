# mc-unlooted

Bukkit plugin for Minecraft 1.12.1 that collects items left lying on the ground into a shared inventory.

## How it works

- **Per-item timer.** Every dropped item gets its own timer. It only runs while the item's chunk is loaded. It restarts when a player, mob or hopper picks up part of the stack. Merged stacks keep the younger age, like vanilla.
- **Global loot timer.** A sweep runs every `loot-interval-seconds`. It loots every item whose own timer reached `loot-delay-seconds`, all in the same sweep. Items are never looted the instant their own timer ends.
- **Any item type.** The sweep scans every loaded item entity, so modded items are collected even when a mod spawns them without firing a Bukkit event. Items that can never be picked up, such as shop display items, are skipped by default.
- **Self-resizing shared inventory.** The inventory is exactly as big as the number of stored stacks needs, rounded up to a full row. Beyond 54 slots it splits into pages. It shrinks again once nobody has it open.
- **Broadcast.** After a sweep that looted something, the plugin announces how many items were collected. Nothing is sent when a sweep looted nothing.
- Vanilla's 5 minute despawn is cancelled for tracked items, so they wait for the plugin instead of vanishing.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/unlooted [page]` (aliases `/ul`, `/unlootedbin`) | `unlooted.use` (default: everyone) | Open the shared inventory |
| `/unlooted loot` | `unlooted.loot` (default: op) | Run the global loot sweep now. Loots items whose own timer already reached the delay |
| `/unlooted loot all` | `unlooted.loot` (default: op) | Loot every item on the ground right now, ignoring the per-item timers |

The loot subcommands also work from the server console.

## Configuration

See [config.yml](src/main/resources/config.yml).

| Key | Default | Meaning |
| --- | --- | --- |
| `loot-delay-seconds` | `300` | Time an item must lie untouched before it can be looted |
| `loot-interval-seconds` | `60` | How often the global sweep runs |
| `max-pages` | `0` | Page cap for the shared inventory, `0` is unlimited |
| `ignore-unpickupable-items` | `true` | Skip items that can never be picked up |
| `broadcast-message` | see file | Supports `{count}` and `{stacks}` placeholders and `&` color codes |

Stored items and timers are saved to `plugins/Unlooted/data.yml`.

## Building

```
mvn package
```

The jar is written to `target/Unlooted-<version>.jar`.

## Releases

Every push to `master` that changes the plugin publishes a GitHub release with the built jar.

- The patch number goes up by one each time, so `v0.1.0` is followed by `v0.1.1`, `v0.1.2` and so on.
- For a bigger step, raise `revision` in `pom.xml`, for example to `0.2.0`. The next release then uses that version.
- Changes that only touch the README or other files outside `src/` and `pom.xml` do not create a release.
- A release can also be started by hand from the Actions tab.
