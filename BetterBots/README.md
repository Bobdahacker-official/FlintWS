# BetterBots

BetterBots adds player-shaped NPCs to Minecraft Java Edition **26.3** on **Paper**. It targets Paper build **257** and Java **25**. Install the single JAR in your server’s `plugins/` directory and restart the server.

As an operator in game, use `/bb add 3`. The command places bots near you and chooses a personality and starter equipment for each. Bots patrol the terrain or follow an online player, use the vanilla mob pathfinder, swing their arms, look around, crouch, and make footsteps. Each bot has a saved home, equipment loadout, and 27-slot inventory. Bots sleep when no players are nearby and resume when the terrain is loaded and a viewer returns.

Chat uses local personality rules, nearby greetings, direct mentions, and time or weather cues. For example, try `TorchFox, where are you?` next to a bot named TorchFox. There is no AI-service configuration.

## Commands

All commands require `betterbots.admin`, which server operators receive by default. `/betterbots` and `/bb` are equivalent.

| Command | Effect |
| --- | --- |
| `/bb add [count]` | Create 1–16 independently named bots, subject to the configured cap. |
| `/bb create <name> [style]` | Choose a name and optionally `explorer`, `builder`, `miner`, `farmer` or `fisher`. Styles affect gear and dialogue. |
| `/bb list` and `/bb info <bot>` | Inspect profiles, activity, location, and equipment. |
| `/bb wander <bot>` | Patrol within the configured radius of home. |
| `/bb stay <bot>` | Stop and set home to the bot’s current position when its body is active. |
| `/bb follow <bot> [player]` | Follow a nearby online player in the same world. If the bot is out of range, summon it nearby first. |
| `/bb summon <bot>` | Move the bot’s home to a safe nearby spot. Run this command in game. |
| `/bb pause <bot>` and `/bb resume <bot>` | Remove or restore the physical NPC and tab row while keeping its profile. |
| `/bb mute <bot>` and `/bb unmute <bot>` | Control the bot’s automatic dialogue. |
| `/bb say <bot> <message>` | Send a message from the bot to nearby players. |
| `/bb equip <bot> <slot> <item\|none>` | Set or clear `hand`, `offhand`, `head`, `chest`, `legs`, or `feet`. |
| `/bb inventory <bot>` | List occupied inventory slots and quantities. |
| `/bb give <bot> <item> [count]` | Create items in the bot’s inventory as an admin. |
| `/bb drop <bot> <slot> [count]` | Drop items from a numbered inventory slot into the world. |
| `/bb collect <bot> <on\|off>` | Enable or disable pickup of nearby unowned drops. The server setting must enable collection first. |
| `/bb remove <bot>` | Delete the selected bot profile, inventory, entity and tab row. |
| `/bb reload` | Apply changes to `config.yml`. |

## Configuration and saved data

`plugins/BetterBots/config.yml` controls the bot limit, activation radius, roam distance, speed, footsteps, tab entries and chat timing or proximity. Automatic item pickup is disabled by default. Enable `bots.allow-collection` before using `/bb collect <bot> on`.

Bots appear in Tab and contribute to the multiplayer server-list count by default. To change these settings, edit the existing `bots:` section of `config.yml`, then run `/bb reload`. Upgrades preserve any explicitly saved values.

| Setting under `bots:` | Default | Effect when enabled |
| --- | --- | --- |
| `tab-list` | `true` | Show enabled bots in the in-game player list. |
| `show-bot-tag` | `false` | Prefix bot names with `[Bot]` in chat, Tab, and in-game nametags. |
| `include-in-server-list-count` | `true` | Add enabled bots to the multiplayer server list’s online count. Paused bots are excluded. Login slots and the actual connected player count are unaffected. |

Names come from anonymous requests to [Random User Generator](https://randomuser.me/documentation/) and [Faker API](https://www.fakerapi.it/). BetterBots alternates providers when refreshing its username pool, tries the other provider when a request fails, and generates names locally when the pool runs out. The requests run in a background worker and contain no Minecraft player data. Set `names.use-web-apis: false` for local names only. If a real player joins using the same name, BetterBots renames the NPC and retains its profile and equipment.

Profiles save to `plugins/BetterBots/bots.yml`. Admin changes write immediately. Automatic pickups save at the next periodic update or clean shutdown. The file contains inventories, settings specific to each bot, names and home coordinates.

## Build from source

Install a JDK 25 and Maven, then run `mvn -B clean package`. The build uses the [Paper Maven repository](https://docs.papermc.io/paper/dev/project-setup/) and produces `target/BetterBots-1.1.1.jar`. The plugin has no separate runtime plugins or API keys.

## In-game verification

1. Install the JAR on Paper 26.3 build 257 and join as an operator in an open area.
2. Use `/bb create TorchFox explorer` and `/bb add 3`. Check that the mannequins appear and that Tab includes their names.
3. Watch a bot navigate a small obstacle. Run `/bb follow TorchFox`, walk away a few blocks, then use `/bb stay TorchFox`.
4. Ask `TorchFox, where are you?` in chat. Allow a few seconds for the typing delay.
5. Run `/bb equip TorchFox hand iron_pickaxe`, `/bb give TorchFox apple 5`, and `/bb inventory TorchFox`. Drop one apple using the slot shown by the inventory command.
6. Restart the server and use `/bb info TorchFox` to verify the profile, equipment and inventory. Use `/bb pause TorchFox` and then `/bb resume TorchFox` to check tab and entity cleanup.
7. Refresh the multiplayer server list and check that the count includes enabled bots. Pause a bot and refresh again to check that the total decreases. Set `show-bot-tag: true` and run `/bb reload`, then check that the tag appears in chat, Tab, and above each bot.
