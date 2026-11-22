package dev.betterbots;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class BotStore {
    private final Path file;
    private final Logger logger;

    BotStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    Map<UUID, Bot> load() throws IOException, InvalidConfigurationException {
        Map<UUID, Bot> bots = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return bots;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        ConfigurationSection profiles = yaml.getConfigurationSection("bots");
        if (profiles == null) {
            if (yaml.contains("bots")) {
                throw new InvalidConfigurationException("Expected a bots section.");
            }
            return bots;
        }
        for (String key : profiles.getKeys(false)) {
            ConfigurationSection data = profiles.getConfigurationSection(key);
            if (data == null) {
                throw new InvalidConfigurationException("Expected a bot profile at " + key);
            }
            try {
                UUID id = UUID.fromString(key);
                String name = data.getString("name", "");
                String world = data.getString("anchor.world", "");
                double x = data.getDouble("anchor.x", Double.NaN);
                double y = data.getDouble("anchor.y", Double.NaN);
                double z = data.getDouble("anchor.z", Double.NaN);
                float yaw = (float) data.getDouble("anchor.yaw");
                if (!Names.valid(name) || world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                        || !Double.isFinite(z) || !Float.isFinite(yaw)
                        || bots.values().stream().anyMatch(bot -> bot.name.equalsIgnoreCase(name))) {
                    throw new IllegalArgumentException("Invalid or duplicate identity or anchor");
                }
                Bot.Style style = Bot.Style.valueOf(data.getString("style", "EXPLORER"));
                Bot bot = new Bot(id, name, new Bot.Anchor(world, x, y, z, yaw), style);
                bot.mode = Bot.Mode.valueOf(data.getString("mode", "WANDER"));
                String follow = data.getString("follow");
                if (follow != null) {
                    bot.follow = UUID.fromString(follow);
                }
                bot.online = data.getBoolean("online", true);
                bot.muted = data.getBoolean("muted");
                bot.collecting = data.getBoolean("collecting");
                for (EquipmentSlot slot : Bot.SLOTS.values()) {
                    bot.equip(slot, data.getItemStack("equipment." + slot.name().toLowerCase(Locale.ROOT)));
                }
                for (int index = 0; index < bot.inventory.getSize(); index++) {
                    ItemStack stack = data.getItemStack("inventory." + index);
                    if (stack != null && !stack.isEmpty()) {
                        bot.inventory.setItem(index, stack);
                    }
                }
                bots.put(id, bot);
            } catch (RuntimeException failure) {
                throw new InvalidConfigurationException("Invalid bot profile " + key, failure);
            }
        }
        return bots;
    }

    boolean save(Collection<Bot> bots) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection profiles = yaml.createSection("bots");
        for (Bot bot : bots) {
            ConfigurationSection data = profiles.createSection(bot.id.toString());
            data.set("name", bot.name);
            data.set("style", bot.style.name());
            data.set("mode", bot.mode.name());
            data.set("follow", bot.follow == null ? null : bot.follow.toString());
            data.set("online", bot.online);
            data.set("muted", bot.muted);
            data.set("collecting", bot.collecting);
            data.set("anchor.world", bot.anchor.world());
            data.set("anchor.x", bot.anchor.x());
            data.set("anchor.y", bot.anchor.y());
            data.set("anchor.z", bot.anchor.z());
            data.set("anchor.yaw", bot.anchor.yaw());
            bot.equipment.forEach((slot, stack) ->
                    data.set("equipment." + slot.name().toLowerCase(Locale.ROOT), stack));
            for (int index = 0; index < bot.inventory.getSize(); index++) {
                data.set("inventory." + index, bot.inventory.getItem(index));
            }
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "bots-", ".tmp");
            Files.writeString(temporary, yaml.saveToString());
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not save bot profiles. BetterBots will retry.", failure);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    logger.log(Level.FINE, "Could not delete temporary bot profile data.", failure);
                }
            }
        }
    }
}
