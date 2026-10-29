# mc-unlooted

Bukkit plugin for Minecraft 1.12.1 that collects items left lying on the ground into a shared inventory.

## How it works

- **Per-item timer.** Every dropped item gets its own timer. It only runs while the item's chunk is loaded. It restarts when a player, mob or hopper picks up part of the stack. Merged stacks keep the younger age, like vanilla.
- **Global loot timer.** A sweep runs every `loot-interval-seconds`. It loots every item whose own timer reached `loot-delay-seconds`, all in the same sweep. Items are never looted the instant their own timer ends.
- **Any item type.** The sweep scans every loaded item entity, so modded items are collected even when a mod spawns them without firing a Bukkit event. Items that can never be picked up, such as shop display items, are skipped by default.
- **Unlimited, self-resizing shared inventory.** Each page holds up to 45 stacks and is only as tall as its items need. More items simply add more pages. The inventory shrinks again once nobody has it open.
- **Navigation bar.** The bottom row of every page is a bar of black glass panes that can't be taken. A red pane goes to the previous page and a green pane to the next one. They only appear when there is a page to go to.
- **Broadcast.** After a sweep that looted something, the plugin announces how many items were collected. Nothing is sent when a sweep looted nothing.
- Vanilla's 5 minute despawn is cancelled for tracked items, so they wait for the plugin instead of vanishing.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/unlooted [page]` (aliases `/ul`, `/unlootedbin`) | `unlooted.use` (default: everyone) | Open the shared inventory |
| `/unlooted loot` | `unlooted.loot` (default: op) | Run the global loot sweep now. Loots items whose own timer already reached the delay |
| `/unlooted loot all` | `unlooted.loot` (default: op) | Loot every item on the ground right now, ignoring the per-item timers |

| `/unlooted clear` | `unlooted.admin` (default: op) | Permanently delete everything in the storage. Asks for `/unlooted clear confirm` within 30 seconds first |
| `/unlooted reload` | `unlooted.admin` (default: op) | Reload `config.yml` from disk after editing it |
| `/unlooted config` | `unlooted.admin` (default: op) | List all settings and their current values |
| `/unlooted config <key>` | `unlooted.admin` (default: op) | Show one setting |
| `/unlooted config <key> <value>` | `unlooted.admin` (default: op) | Change a setting. It takes effect at once and is saved to `config.yml` with its comments kept |

All subcommands except opening the inventory also work from the server console, without the leading slash. For example:

```
unlooted config loot-delay-seconds 600
unlooted config broadcast-message &a{count} items were rescued!
unlooted reload
```

Lowering `max-pages` never deletes stored items. It only stops new items from being looted once the inventory is that full.

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

## Item data is kept intact

Looted items keep all of their data: enchantments, damage, names and lore, shulker box and backpack contents, and any data mods attach to items.

- Items are moved using the server's own item data, never rebuilt from Bukkit's item types.
- Two stacks are only merged when their complete data is identical.
- `data.yml` stores each item's raw data. On Paper this uses Paper's own item format. On CraftBukkit, Spigot and Forge hybrids it stores the raw NBT, including Forge capability data. The server log says which format is in use at startup.
- An item whose mod is missing stays in `data.yml` untouched and loads again once the mod is back.
- Saves go to a temporary file first, so a crash during a save cannot corrupt `data.yml`. A `data.yml` that cannot be read is moved aside instead of being overwritten.

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
