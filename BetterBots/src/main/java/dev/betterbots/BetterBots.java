package dev.betterbots;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public class BetterBots extends JavaPlugin implements Listener {
    record Settings(int max, int radius, int activation, double speed, boolean tab, boolean showBotTag,
                    boolean includeBotsInCount, boolean footsteps,
                    boolean interact, boolean collect, boolean web, boolean chat, int proximity, int cooldown,
                    int ambientMin, int ambientMax, boolean ambient, boolean replies, boolean nearbyReplies,
                    boolean greet) {
        Settings(FileConfiguration cfg) {
            this(integer(cfg, "bots.max", 24, 1, 64),
                    integer(cfg, "bots.roam-radius", 20, 4, 36),
                    integer(cfg, "bots.activation-range", 56, 16, 128),
                    speed(cfg.getDouble("bots.walking-speed", 1.12)),
                    cfg.getBoolean("bots.tab-list", true), cfg.getBoolean("bots.show-bot-tag", false),
                    cfg.getBoolean("bots.include-in-server-list-count", true), cfg.getBoolean("bots.footsteps", true),
                    cfg.getBoolean("bots.right-click-info", true), cfg.getBoolean("bots.allow-collection"),
                    cfg.getBoolean("names.use-web-apis", true), cfg.getBoolean("chat.enabled", true),
                    integer(cfg, "chat.proximity", 64, 4, 128),
                    integer(cfg, "chat.cooldown", 9, 2, 180),
                    integer(cfg, "chat.ambient-min-seconds", 40, 5, 3600),
                    Math.max(integer(cfg, "chat.ambient-min-seconds", 40, 5, 3600),
                            integer(cfg, "chat.ambient-max-seconds", 95, 5, 7200)),
                    cfg.getBoolean("chat.ambient", true), cfg.getBoolean("chat.replies", true),
                    cfg.getBoolean("chat.nearby-replies", true), cfg.getBoolean("chat.greet-joins", true));
        }

        private static int integer(FileConfiguration cfg, String key, int fallback, int min, int max) {
            return Math.clamp(cfg.getInt(key, fallback), min, max);
        }

        private static double speed(double value) {
            return Double.isFinite(value) ? Math.clamp(value, 0.4, 1.65) : 1.12;
        }
    }

    final Map<UUID, Bot> bots = new LinkedHashMap<>();
    volatile Settings settings;
    NamespacedKey marker;
    Movement movement;
    Conversation conversation;
    TabList tab;
    long tick;
    boolean dirty;
    int pending;
    private volatile int enabledBotCount;
    private BotStore store;
    private ExecutorService requests;
    private Names names;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        marker = new NamespacedKey(this, "npc");
        BotStore profiles = new BotStore(getDataFolder().toPath().resolve("bots.yml"), getLogger());
        try {
            bots.putAll(profiles.load());
            store = profiles;
        } catch (IOException | InvalidConfigurationException failure) {
            getLogger().log(Level.SEVERE, "Unable to read bots.yml. Repair it before starting BetterBots.", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        updateEnabledBotCount();
        movement = new Movement(this);
        conversation = new Conversation(this);
        tab = new TabList(getLogger());
        names = new Names();
        requests = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("BetterBots-names").factory());

        Commands commands = new Commands(this);
        PluginCommand command = Objects.requireNonNull(getCommand("betterbots"));
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            resolveCollision(player);
        }
        conversation.reset();
        showTab(Bukkit.getOnlinePlayers());
        getServer().getScheduler().runTaskTimer(this, this::step, 1, 1);
        getLogger().info("Loaded " + bots.size() + " bot profiles.");
    }

    private void step() {
        tick++;
        List.copyOf(bots.values()).forEach(movement::step);
        conversation.tick();
        if (dirty && tick % 200 == 0) {
            flush();
        }
    }

    @Override
    public void onDisable() {
        if (requests != null) {
            requests.shutdownNow();
            names.close();
        }
        if (tab != null) {
            tab.hide(Bukkit.getOnlinePlayers(), bots.keySet());
        }
        if (movement != null) {
            bots.values().forEach(movement::despawn);
        }
        if (dirty && store != null) {
            flush();
        }
    }

    void rereadSettings() {
        reloadConfig();
        boolean previousTag = settings.showBotTag();
        settings = new Settings(getConfig());
        if (previousTag != settings.showBotTag()) {
            for (Bot bot : bots.values()) {
                if (bot.body != null && bot.body.isValid()) {
                    movement.updateLabel(bot, bot.body);
                }
            }
        }
        conversation.reset();
        tab.hide(Bukkit.getOnlinePlayers(), bots.keySet());
        showTab(Bukkit.getOnlinePlayers());
    }

    void flush() {
        updateEnabledBotCount();
        dirty = !store.save(bots.values());
    }

    private void updateEnabledBotCount() {
        // Paper can dispatch server-list pings off the main thread.
        enabledBotCount = (int) bots.values().stream().filter(bot -> bot.online).count();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void serverListPing(PaperServerListPingEvent event) {
        int count = enabledBotCount;
        if (settings.includeBotsInCount() && count > 0 && !event.shouldHidePlayers()) {
            int original = event.getNumPlayers();
            if (original >= 0) {
                event.setNumPlayers((int) Math.min((long) original + count, Integer.MAX_VALUE));
            }
        }
    }

    Bot find(String name) {
        return bots.values().stream().filter(bot -> bot.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private boolean taken(String name) {
        return find(name) != null || Bukkit.getOnlinePlayers().stream()
                .anyMatch(player -> player.getName().equalsIgnoreCase(name));
    }

    private String unique(String proposal) {
        String name = proposal;
        while (taken(name)) {
            name = Names.local();
        }
        return name;
    }

    void addRandom(CommandSender sender, Location origin, int count) {
        pending += count;
        UUID worldId = origin.getWorld().getUID();
        boolean useWeb = settings.web();
        if (useWeb) {
            tell(sender, "Choosing names for " + count + (count == 1 ? " bot…" : " bots…"));
        }
        requests.submit(() -> {
            List<String> candidates = names.take(count, useWeb);
            try {
                getServer().getScheduler().runTask(this, () -> {
                    pending -= count;
                    World world = Bukkit.getWorld(worldId);
                    if (world == null) {
                        error(sender, "The destination world was unloaded.");
                        return;
                    }
                    Location center = origin.clone();
                    center.setWorld(world);
                    List<Bot> added = new ArrayList<>();
                    for (String candidate : candidates) {
                        if (bots.size() >= settings.max()) {
                            break;
                        }
                        added.add(create(unique(candidate), position(center, added.size()), randomStyle()));
                    }
                    if (added.isEmpty()) {
                        error(sender, "The bot limit has been reached.");
                        return;
                    }
                    flush();
                    if (settings.tab()) {
                        tab.show(Bukkit.getOnlinePlayers(), added, settings.showBotTag());
                    }
                    tell(sender, "Added " + added.stream().map(bot -> bot.name).collect(Collectors.joining(", ")) + ".");
                });
            } catch (IllegalPluginAccessException ignored) {
                // The server disabled the plugin while an HTTP request was finishing.
            }
        });
    }

    void addNamed(CommandSender sender, String name, Location origin, Bot.Style style) {
        if (taken(name)) {
            error(sender, "That name is already in use.");
            return;
        }
        Bot bot = create(name, origin, style);
        flush();
        if (settings.tab()) {
            tab.show(Bukkit.getOnlinePlayers(), List.of(bot), settings.showBotTag());
        }
        tell(sender, "Added " + bot.describe() + ".");
    }

    private Bot create(String name, Location center, Bot.Style style) {
        Location safe = Movement.safeNear(center);
        Bot bot = new Bot(UUID.randomUUID(), name, new Bot.Anchor(safe == null ? center : safe), style);
        bot.outfit();
        bots.put(bot.id, bot);
        if (safe != null && !safe.getWorld().getNearbyPlayers(safe, settings.activation()).isEmpty()) {
            movement.spawn(bot, safe);
        }
        return bot;
    }

    private Location position(Location origin, int index) {
        if (index == 0) {
            return origin;
        }
        double angle = index * 2.3999632;
        double radius = Math.min(12, 2 + Math.sqrt(index) * 1.5);
        return origin.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    static Bot.Style randomStyle() {
        Bot.Style[] styles = Bot.Style.values();
        return styles[Movement.random(0, styles.length - 1)];
    }

    void remove(Bot bot) {
        movement.despawn(bot);
        tab.hide(Bukkit.getOnlinePlayers(), List.of(bot.id));
        bots.remove(bot.id);
        flush();
    }

    void setOnline(Bot bot, boolean online) {
        bot.online = online;
        if (!online) {
            movement.despawn(bot);
            tab.hide(Bukkit.getOnlinePlayers(), List.of(bot.id));
        } else if (settings.tab()) {
            tab.show(Bukkit.getOnlinePlayers(), List.of(bot), settings.showBotTag());
        }
        flush();
    }

    void setAnchor(Bot bot, Location destination) {
        bot.anchor = new Bot.Anchor(destination);
        if (bot.online) {
            movement.spawn(bot, destination);
        }
        flush();
    }

    private void resolveCollision(Player player) {
        Bot bot = find(player.getName());
        if (bot == null) {
            return;
        }
        String previous = bot.name;
        tab.hide(Bukkit.getOnlinePlayers(), List.of(bot.id));
        bot.name = unique(Names.local());
        if (bot.body != null) {
            movement.name(bot, bot.body);
        }
        flush();
        if (settings.tab() && bot.online) {
            tab.show(Bukkit.getOnlinePlayers(), List.of(bot), settings.showBotTag());
        }
        getLogger().info("Renamed bot " + previous + " to " + bot.name + " after a player with that name joined.");
    }

    private void showTab(java.util.Collection<? extends Player> players) {
        if (settings.tab()) {
            tab.show(players, bots.values().stream().filter(bot -> bot.online).toList(), settings.showBotTag());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        resolveCollision(player);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                showTab(List.of(player));
            }
        }, 2);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                conversation.greet(player);
            }
        }, 70);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                showTab(List.of(player));
            }
        }, 2);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (!settings.chat() || !settings.replies()) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isBlank() || message.length() > 256) {
            return;
        }
        UUID sender = event.getPlayer().getUniqueId();
        Set<UUID> audience = event.viewers().stream().filter(Player.class::isInstance).map(Player.class::cast)
                .map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet());
        try {
            getServer().getScheduler().runTask(this, () -> conversation.accept(sender, audience, message));
        } catch (IllegalPluginAccessException ignored) {
            // The plugin was disabled while chat was being handled.
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        String id = entity.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !settings.interact()) {
            return;
        }
        Bot bot = bots.get(UUID.fromString(id));
        if (bot == null || entity != bot.body) {
            return;
        }
        ItemStack held = bot.equipment.get(EquipmentSlot.HAND);
        Component text = Component.text(bot.displayName(settings.showBotTag()), NamedTextColor.AQUA);
        if (held != null) {
            text = text.append(Component.text(" · holding ", NamedTextColor.GRAY))
                    .append(Component.translatable(held.translationKey()));
        }
        event.getPlayer().sendActionBar(text);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (owned(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) {
        if (owned(event.getTarget()) || owned(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactWithBlock(EntityInteractEvent event) {
        if (owned(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void changeBlock(EntityChangeBlockEvent event) {
        if (owned(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private boolean owned(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkUnload(ChunkUnloadEvent event) {
        int x = event.getChunk().getX();
        int z = event.getChunk().getZ();
        World world = event.getWorld();
        for (Bot bot : bots.values()) {
            if (isInChunk(bot.pilot, world, x, z) || isInChunk(bot.body, world, x, z)) {
                movement.despawn(bot);
            }
        }
    }

    private static boolean isInChunk(Entity entity, World world, int x, int z) {
        if (entity == null || entity.getWorld() != world) {
            return false;
        }
        Location at = entity.getLocation();
        return (at.getBlockX() >> 4) == x && (at.getBlockZ() >> 4) == z;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        for (Bot bot : bots.values()) {
            if (bot.body != null && bot.body.getWorld().equals(event.getWorld())) {
                movement.despawn(bot);
            }
        }
    }

    static void tell(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BetterBots] ", NamedTextColor.AQUA)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BetterBots] ", NamedTextColor.AQUA)
                .append(Component.text(message, NamedTextColor.RED)));
    }
}
