package dev.betterbots;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

final class TabList {
    private final Logger logger;
    private final Bridge bridge;
    private boolean warned;

    TabList(Logger logger) {
        this.logger = logger;
        Bridge resolved = null;
        try {
            resolved = new Bridge();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warn(failure);
        }
        bridge = resolved;
    }

    boolean available() {
        return bridge != null && !warned;
    }

    void show(Collection<? extends Player> players, Collection<Bot> bots, boolean showBotTag) {
        if (bridge == null || players.isEmpty() || bots.isEmpty()) {
            return;
        }
        try {
            var entries = new ArrayList<>();
            for (Bot bot : bots) {
                Object profile = bridge.profile.newInstance(bot.id, bot.name);
                Object label = bridge.literal.invoke(null, bot.displayName(showBotTag));
                entries.add(bridge.entry.newInstance(bot.id, profile, true, 0, bridge.survival,
                        label, true, 0, null));
            }
            Object packet = bridge.add.newInstance(bridge.actions, List.copyOf(entries));
            send(players, packet);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warn(failure);
        }
    }

    void hide(Collection<? extends Player> players, Collection<UUID> ids) {
        if (bridge == null || players.isEmpty() || ids.isEmpty()) {
            return;
        }
        try {
            send(players, bridge.remove.newInstance(List.copyOf(ids)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warn(failure);
        }
    }

    private void send(Collection<? extends Player> players, Object packet) throws ReflectiveOperationException {
        for (Player player : players) {
            Object handle = bridge.handle.invoke(player);
            bridge.send.invoke(bridge.connection.get(handle), packet);
        }
    }

    private void warn(Throwable failure) {
        if (!warned) {
            logger.log(Level.WARNING, "Could not send bot tab-list entries on this server build.", failure);
            warned = true;
        }
    }

    private static final class Bridge {
        final Constructor<?> profile;
        final Constructor<?> entry;
        final Constructor<?> add;
        final Constructor<?> remove;
        final Method literal;
        final Method handle;
        final Field connection;
        final Method send;
        final Object survival;
        final EnumSet<?> actions;

        Bridge() throws ReflectiveOperationException {
            String game = "net.minecraft.network.protocol.game.";
            Class<?> packetType = Class.forName(game + "ClientboundPlayerInfoUpdatePacket");
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            Class<?> gameProfile = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
            Class<?> chatData = Class.forName("net.minecraft.network.chat.RemoteChatSession$Data");
            profile = gameProfile.getConstructor(UUID.class, String.class);
            literal = component.getMethod("literal", String.class);
            survival = gameType.getField("SURVIVAL").get(null);
            entry = Class.forName(packetType.getName() + "$Entry").getConstructor(UUID.class, gameProfile,
                    boolean.class, int.class, gameType, component, boolean.class, int.class, chatData);
            add = packetType.getConstructor(EnumSet.class, List.class);
            remove = Class.forName(game + "ClientboundPlayerInfoRemovePacket").getConstructor(List.class);
            actions = actions(Class.forName(packetType.getName() + "$Action").asSubclass(Enum.class));
            handle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
            connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            send = connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static EnumSet<?> actions(Class<? extends Enum> type) {
            EnumSet result = EnumSet.noneOf(type);
            for (String name : List.of("ADD_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED",
                    "UPDATE_LATENCY", "UPDATE_DISPLAY_NAME", "UPDATE_HAT", "UPDATE_LIST_ORDER")) {
                result.add(Enum.valueOf(type, name));
            }
            return result;
        }
    }
}
