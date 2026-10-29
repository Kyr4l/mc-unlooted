# mc-unlooted

Bukkit plugin for Minecraft 1.12.1 that collects items left lying on the ground into a shared storage.

## How it works

- **Per-item timer.** Every dropped item gets its own timer. It only runs while the item's chunk is loaded. It restarts when a player, mob or hopper picks up part of the stack. Merged stacks keep the younger age, like vanilla.
- **Global loot timer.** A sweep runs every `loot-interval-seconds`. It loots every item whose own timer reached `loot-delay-seconds`, all in the same sweep. Items are never looted the instant their own timer ends.
- **Any item type.** The sweep scans every loaded item entity, so modded items are collected even when a mod spawns them without firing a Bukkit event. Items that can never be picked up, such as shop display items, are skipped by default.
- **Grouped, unlimited storage.** Identical items share one entry, however many stacks there are. The item list shows one icon per item with the real amount below it. Clicking an item with several stacks opens a list of those stacks to take one. Shift-click takes a stack straight away. Pages hold 45 entries and are only as tall as they need to be.
- **Navigation bar.** The bottom row is a bar of black glass panes. A red pane goes to the previous page, a green pane to the next one, and a yellow pane returns from a stack list to the item list.
- **Take-only.** The storage is a menu, not a chest. Players can only take items, never put any in.
- **Owner first.** Items a player dropped or lost on death are reserved for them for `owner-priority-seconds` after they are looted. Others see them marked as reserved. Admins can always take them.
- **Exploit-proof menu.** Every icon is a tagged display copy. The open menu is checked every tick, so sorting mods can't move anything, and real items moved in go back to the player. A menu icon that escapes is removed from inventories, chests, drops and the ground.
- **Broadcast.** After a sweep that looted something, the plugin announces how many items were collected. Nothing is sent when a sweep looted nothing.
- Vanilla's 5 minute despawn is cancelled for tracked items, so they wait for the plugin instead of vanishing.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/unlooted [page]` (aliases `/ul`, `/unlootedbin`) | `unlooted.use` (default: everyone) | Open the storage |
| `/unlooted loot` | `unlooted.loot` (default: op) | Run the global loot sweep now. Loots items whose own timer already reached the delay |
| `/unlooted loot all` | `unlooted.loot` (default: op) | Loot every item on the ground right now, ignoring the per-item timers |
| `/unlooted clear` | `unlooted.admin` (default: op) | Permanently delete everything in the storage. Asks for `/unlooted clear confirm` within 30 seconds first |
| `/unlooted reload` | `unlooted.admin` (default: op) | Reload `config.yml` from disk after editing it |
| `/unlooted config` | `unlooted.admin` (default: op) | List all settings and their current values |
| `/unlooted config <key>` | `unlooted.admin` (default: op) | Show one setting |
| `/unlooted config <key> <value>` | `unlooted.admin` (default: op) | Change a setting. It takes effect at once and is saved to `config.yml` with its comments kept |

All subcommands except opening the storage also work from the server console, without the leading slash. For example:

```
unlooted config loot-delay-seconds 600
unlooted config broadcast-message &a{count} items were rescued!
unlooted config excluded-worlds creative, minigames
unlooted reload
```

Lowering `max-pages` never deletes stored items. It only stops new kinds of items from being looted once the storage is that full. Lists take comma-separated values, or `none` to empty them.

`unlooted.admin` also lets a player take items reserved for someone else.

## Configuration

See [config.yml](src/main/resources/config.yml).

| Key | Default | Meaning |
| --- | --- | --- |
| `loot-delay-seconds` | `300` | Time an item must lie untouched before it can be looted |
| `loot-interval-seconds` | `60` | How often the global sweep runs |
| `owner-priority-seconds` | `600` | How long looted items stay reserved for the player who dropped them, `0` turns it off |
| `max-pages` | `0` | Page cap for the storage, 45 kinds of items per page, `0` is unlimited |
| `excluded-worlds` | none | Worlds whose items are never looted |
| `excluded-items` | none | Item types that are never looted, `*` as wildcard |
| `ignore-unpickupable-items` | `true` | Skip items that can never be picked up |
| `broadcast-message` | see file | Supports `{count}` and `{stacks}` placeholders and `&` color codes |

Stored items and timers are saved to `plugins/Unlooted/data.yml`.

## Item data is kept intact

Looted items keep all of their data: enchantments, damage, names and lore, shulker box and backpack contents, and any data mods attach to items.

- Items are moved using the server's own item data, never rebuilt from Bukkit's item types.
- Items are only grouped together when their complete data is identical.
- Stacks are handed out using the server's own stack limits, so modded items that stack lower are respected.
- `data.yml` stores each item's raw data. On Paper this uses Paper's own item format. On CraftBukkit, Spigot and Forge hybrids it stores the raw NBT, including Forge capability data. The server log says which format is in use at startup.
- An item whose mod is missing stays in `data.yml` untouched and loads again once the mod is back.
- Saves happen only after changes, at most every 30 seconds, and the file is written in the background. Each save goes to a temporary file first, so a crash cannot corrupt `data.yml`. A `data.yml` that cannot be read is moved aside instead of being overwritten.
- Storage saved by older versions is converted automatically on startup.

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
