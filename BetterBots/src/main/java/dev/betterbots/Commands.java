package dev.betterbots;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class Commands implements TabExecutor {
    private static final List<String> ACTIONS = List.of("help", "add", "create", "list", "info", "remove",
            "pause", "resume", "wander", "stay", "follow", "summon", "mute", "unmute", "say", "equip",
            "inventory", "give", "drop", "collect", "reload");
    private static final List<String> BOT_ACTIONS = ACTIONS.subList(4, ACTIONS.size() - 1);
    private final BetterBots plugin;

    Commands(BetterBots plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("betterbots.admin")) {
            return true;
        }
        String action = args.length == 0 ? "help" : lower(args[0]);
        try {
            switch (action) {
                case "help" -> help(sender, label);
                case "list" -> list(sender);
                case "reload" -> {
                    plugin.rereadSettings();
                    BetterBots.tell(sender, "Settings reloaded.");
                }
                case "add" -> add(sender, args);
                case "create" -> create(sender, args);
                default -> {
                    if (!BOT_ACTIONS.contains(action)) {
                        throw new IllegalArgumentException("Unknown command. Use /" + label + " help.");
                    }
                    Bot bot = needBot(args);
                    switch (action) {
                        case "info" -> info(sender, bot);
                        case "remove" -> {
                            plugin.remove(bot);
                            BetterBots.tell(sender, "Removed " + bot.name + ".");
                        }
                        case "pause", "resume" -> {
                            plugin.setOnline(bot, action.equals("resume"));
                            BetterBots.tell(sender, (bot.online ? "Resumed " : "Paused ") + bot.name + ".");
                        }
                        case "wander" -> mode(sender, bot, Bot.Mode.WANDER, null);
                        case "stay" -> stay(sender, bot);
                        case "follow" -> follow(sender, bot, args);
                        case "summon" -> summon(sender, bot);
                        case "mute", "unmute" -> {
                            bot.muted = action.equals("mute");
                            updated(sender, bot);
                        }
                        case "say" -> say(sender, bot, args);
                        case "equip" -> equip(sender, bot, args);
                        case "inventory" -> inventory(sender, bot);
                        case "give" -> give(sender, bot, args);
                        case "drop" -> drop(sender, bot, args);
                        case "collect" -> collect(sender, bot, args);
                        default -> throw new IllegalStateException("Unhandled command " + action);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            BetterBots.error(sender, e.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        String prefix = "/" + label + " ";
        BetterBots.tell(sender, "add [1–16], create <name> [style], list, info <bot>, remove <bot>");
        sender.sendMessage(prefix + "wander|stay|pause|resume|summon <bot>");
        sender.sendMessage(prefix + "follow <bot> [player]");
        sender.sendMessage(prefix + "mute|unmute <bot>  •  " + prefix + "say <bot> <message>");
        sender.sendMessage(prefix + "equip <bot> <hand|offhand|head|chest|legs|feet> <item|none>");
        sender.sendMessage(prefix + "inventory <bot>  •  " + prefix + "give <bot> <item> [count]");
        sender.sendMessage(prefix + "drop <bot> <slot 1–27> [count]  •  " + prefix + "collect <bot> <on|off>");
        sender.sendMessage(prefix + "reload");
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: /bb add [count]");
        }
        int count = args.length > 1 ? number(args[1], 1, 16) : 1;
        checkCapacity(count);
        plugin.addRandom(sender, origin(sender), count);
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3 || !Names.valid(args[1])) {
            throw new IllegalArgumentException("Use /bb create <name> [style]. Names need 3–16 letters, numbers or underscores.");
        }
        checkCapacity(1);
        Bot.Style style = BetterBots.randomStyle();
        if (args.length == 3) {
            try {
                style = Bot.Style.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Choose explorer, builder, miner, farmer or fisher.");
            }
        }
        plugin.addNamed(sender, args[1], origin(sender), style);
    }

    private void checkCapacity(int count) {
        if (plugin.bots.size() + plugin.pending + count > plugin.settings.max()) {
            throw new IllegalArgumentException("That would exceed the server's limit of " + plugin.settings.max() + " bots.");
        }
    }

    private Location origin(CommandSender sender) {
        if (sender instanceof Player player) {
            Location at = player.getLocation();
            return at.clone().add(at.getDirection().setY(0).multiply(2.2));
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException("There is no loaded world.");
        }
        return worlds.getFirst().getSpawnLocation();
    }

    private Bot needBot(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Supply a bot name. Use /bb list to see your bots.");
        }
        Bot bot = plugin.find(args[1]);
        if (bot == null) {
            throw new IllegalArgumentException("No bot named " + args[1] + ".");
        }
        return bot;
    }

    private void list(CommandSender sender) {
        if (plugin.bots.isEmpty()) {
            BetterBots.tell(sender, "No bots yet. Use /bb add 3 to create some.");
            return;
        }
        BetterBots.tell(sender, plugin.bots.size() + " profiles. "
                + plugin.bots.values().stream().filter(bot -> bot.body != null).count() + " in the world.");
        for (Bot bot : plugin.bots.values()) {
            sender.sendMessage("  " + bot.describe() + " · " + bot.anchor.world()
                    + (bot.online && bot.body == null ? " · waiting for loaded terrain" : ""));
        }
    }

    private void info(CommandSender sender, Bot bot) {
        BetterBots.tell(sender, bot.describe());
        Bot.Anchor anchor = bot.anchor;
        sender.sendMessage("Home " + anchor.world() + " " + Location.locToBlock(anchor.x()) + ", "
                + Location.locToBlock(anchor.y()) + ", " + Location.locToBlock(anchor.z()));
        if (bot.body != null && bot.body.isValid()) {
            Location at = bot.body.getLocation();
            sender.sendMessage("Current " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ());
        }
        if (bot.mode == Bot.Mode.FOLLOW && bot.follow != null) {
            Player target = Bukkit.getPlayer(bot.follow);
            sender.sendMessage(target == null ? "Follow target is offline." : "Following " + target.getName() + ".");
        }
        if (plugin.settings.tab() && !plugin.tab.available()) {
            sender.sendMessage("Tab-list packets are unavailable on this server build.");
        }
        for (Map.Entry<String, EquipmentSlot> entry : Bot.SLOTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            ItemStack item = bot.equipment.get(entry.getValue());
            if (item != null) {
                sender.sendMessage(entry.getKey() + "  " + lower(item.getType().name()) + " ×" + item.getAmount());
            }
        }
    }

    private void mode(CommandSender sender, Bot bot, Bot.Mode mode, UUID follow) {
        bot.mode = mode;
        bot.follow = follow;
        bot.nextMove = plugin.tick + 1;
        if (bot.pilot != null) {
            bot.pilot.getPathfinder().stopPathfinding();
        }
        updated(sender, bot);
    }

    private void stay(CommandSender sender, Bot bot) {
        if (bot.body != null && bot.body.isValid()) {
            bot.anchor = new Bot.Anchor(bot.body.getLocation());
        }
        mode(sender, bot, Bot.Mode.STAY, null);
    }

    private void follow(CommandSender sender, Bot bot, String[] args) {
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2])
                : sender instanceof Player player ? player : null;
        if (target == null) {
            throw new IllegalArgumentException("Choose an online player to follow.");
        }
        mode(sender, bot, Bot.Mode.FOLLOW, target.getUniqueId());
        if (bot.body == null || !bot.body.getWorld().equals(target.getWorld())
                || bot.body.getLocation().distanceSquared(target.getLocation()) >= 38 * 38) {
            BetterBots.tell(sender, "Use /bb summon " + bot.name + " nearby to start following.");
        }
    }

    private void summon(CommandSender sender, Bot bot) {
        if (!(sender instanceof Player)) {
            throw new IllegalArgumentException("Run this command in game where the bot should appear.");
        }
        Location destination = Movement.safeNear(origin(sender));
        if (destination == null) {
            throw new IllegalArgumentException("Stand near a clear, solid floor and try again.");
        }
        plugin.setAnchor(bot, destination);
        BetterBots.tell(sender, bot.online ? bot.name + " has a new home here." : bot.name + " will appear here when resumed.");
    }

    private void say(CommandSender sender, Bot bot, String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: /bb say <bot> <message>");
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (message.isBlank() || message.length() > 180 || message.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Use 1–180 characters of regular chat text.");
        }
        if (!plugin.conversation.force(bot, message)) {
            throw new IllegalArgumentException("The bot needs to be active and have a nearby listener.");
        }
        BetterBots.tell(sender, "Sent as " + bot.name + ".");
    }

    private void equip(CommandSender sender, Bot bot, String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: /bb equip <bot> <slot> <item|none>");
        }
        EquipmentSlot slot = Bot.SLOTS.get(lower(args[2]));
        if (slot == null) {
            throw new IllegalArgumentException("Choose hand, offhand, head, chest, legs or feet.");
        }
        ItemStack stack = args[3].equalsIgnoreCase("none") ? null : ItemStack.of(item(args[3]));
        bot.equip(slot, stack);
        plugin.flush();
        BetterBots.tell(sender, args[2] + " updated for " + bot.name + ".");
    }

    private void inventory(CommandSender sender, Bot bot) {
        BetterBots.tell(sender, bot.name + "'s inventory");
        boolean empty = true;
        for (int i = 0; i < bot.inventory.getSize(); i++) {
            ItemStack stack = bot.inventory.getItem(i);
            if (stack != null && !stack.isEmpty()) {
                sender.sendMessage("  " + (i + 1) + "  " + lower(stack.getType().name()) + " ×" + stack.getAmount());
                empty = false;
            }
        }
        if (empty) {
            sender.sendMessage("  Empty");
        }
    }

    private void give(CommandSender sender, Bot bot, String[] args) {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: /bb give <bot> <item> [count]");
        }
        Material material = item(args[2]);
        int count = args.length == 4 ? number(args[3], 1, 64) : 1;
        int accepted = 0;
        while (accepted < count) {
            int batch = Math.min(count - accepted, material.getMaxStackSize());
            ItemStack leftover = bot.inventory.addItem(ItemStack.of(material, batch)).get(0);
            accepted += batch - (leftover == null ? 0 : leftover.getAmount());
            if (leftover != null) {
                break;
            }
        }
        if (accepted == 0) {
            throw new IllegalArgumentException("The inventory is full.");
        }
        plugin.flush();
        BetterBots.tell(sender, "Stored " + accepted + " × " + lower(material.name()) + " in " + bot.name + "'s inventory.");
    }

    private void drop(CommandSender sender, Bot bot, String[] args) {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: /bb drop <bot> <slot 1–27> [count]");
        }
        if (!bot.online || bot.body == null || !bot.body.isValid()) {
            throw new IllegalArgumentException("The bot must be present in the world to drop an item.");
        }
        int slot = number(args[2], 1, bot.inventory.getSize()) - 1;
        ItemStack stack = bot.inventory.getItem(slot);
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("That inventory slot is empty.");
        }
        int count = args.length == 4 ? number(args[3], 1, stack.getAmount()) : stack.getAmount();
        ItemStack part = stack.clone();
        part.setAmount(count);
        Location at = bot.body.getLocation();
        Item dropped = at.getWorld().dropItem(at.clone().add(0, 0.7, 0), part, entity -> {
            entity.setPickupDelay(35);
            entity.setVelocity(at.getDirection().multiply(0.28).setY(0.15));
            if (sender instanceof Player player) {
                entity.setThrower(player.getUniqueId());
            }
        });
        if (!dropped.isValid()) {
            throw new IllegalArgumentException("The server cancelled the item drop.");
        }
        int remaining = stack.getAmount() - count;
        if (remaining == 0) {
            bot.inventory.clear(slot);
        } else {
            stack.setAmount(remaining);
            bot.inventory.setItem(slot, stack);
        }
        bot.body.swingMainHand();
        plugin.flush();
        BetterBots.tell(sender, bot.name + " dropped " + count + " × " + lower(part.getType().name()) + ".");
    }

    private void collect(CommandSender sender, Bot bot, String[] args) {
        if (args.length != 3 || !List.of("on", "off").contains(lower(args[2]))) {
            throw new IllegalArgumentException("Usage: /bb collect <bot> <on|off>");
        }
        boolean on = args[2].equalsIgnoreCase("on");
        if (on && !plugin.settings.collect()) {
            throw new IllegalArgumentException("Enable bots.allow-collection in config.yml and reload first.");
        }
        bot.collecting = on;
        updated(sender, bot);
    }

    private void updated(CommandSender sender, Bot bot) {
        plugin.flush();
        BetterBots.tell(sender, bot.describe());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static Material item(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Choose a valid Minecraft item ID.");
        }
        return material;
    }

    private static int number(String input, int min, int max) {
        try {
            int value = Integer.parseInt(input);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Give the same range message for malformed numbers.
        }
        throw new IllegalArgumentException("Use a number between " + min + " and " + max + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("betterbots.admin") || args.length == 0) {
            return List.of();
        }
        String action = lower(args[0]);
        Stream<String> candidates = Stream.empty();
        if (args.length == 1) {
            candidates = ACTIONS.stream();
        } else if (args.length == 2 && BOT_ACTIONS.contains(action)) {
            candidates = plugin.bots.values().stream().map(bot -> bot.name);
        } else if (args.length == 2 && action.equals("add")) {
            candidates = Stream.of("1", "3", "5", "10", "16");
        } else if (args.length == 3) {
            candidates = switch (action) {
                case "create" -> Arrays.stream(Bot.Style.values()).map(style -> lower(style.name()));
                case "follow" -> Bukkit.getOnlinePlayers().stream().map(Player::getName);
                case "equip" -> Bot.SLOTS.keySet().stream().sorted();
                case "give" -> items(false);
                case "collect" -> Stream.of("on", "off");
                case "drop" -> slots(args[1]);
                default -> Stream.empty();
            };
        } else if (args.length == 4 && action.equals("equip")) {
            candidates = items(true);
        }
        String prefix = lower(args[args.length - 1]);
        return candidates.filter(candidate -> lower(candidate).startsWith(prefix)).limit(60).toList();
    }

    private Stream<String> slots(String name) {
        Bot bot = plugin.find(name);
        if (bot == null) {
            return Stream.empty();
        }
        return java.util.stream.IntStream.range(0, bot.inventory.getSize()).filter(index -> {
            ItemStack stack = bot.inventory.getItem(index);
            return stack != null && !stack.isEmpty();
        }).mapToObj(index -> Integer.toString(index + 1));
    }

    private static Stream<String> items(boolean none) {
        Stream<String> materials = Arrays.stream(Material.values()).filter(material -> !material.isLegacy())
                .filter(Material::isItem).map(material -> lower(material.name())).sorted();
        return none ? Stream.concat(Stream.of("none"), materials) : materials;
    }
}
