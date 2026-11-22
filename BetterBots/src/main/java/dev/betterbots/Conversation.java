package dev.betterbots;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

final class Conversation {
    private record Reply(Bot bot, UUID player, Set<UUID> audience, String message, long due) {}
    private static final Pattern GREETING = Pattern.compile("^(hey|hi|hello|yo|hiya|heya|sup|morning|evening)[!. ]{0,4}$");
    private static final Pattern THANKS = Pattern.compile("^(ty|thx|thanks|thank you|cheers)[!. ]{0,4}$");
    private static final Map<Bot.Style, List<String>> AMBIENT = Map.of(
            Bot.Style.EXPLORER, List.of("taking the long way round for a bit", "i always forget which way i came from",
                    "a compass is so much better than guessing", "might go see where this path ends"),
            Bot.Style.BUILDER, List.of("spruce and stone is such a good combo", "i always underestimate how much wood a roof takes",
                    "think i prefer the way lanterns look over torches", "i spend more time picking blocks than actually building"),
            Bot.Style.MINER, List.of("i keep forgetting that fortune doesn't work on iron blocks",
                    "always bring more torches than you think", "strip mining with a fresh pick feels so much better",
                    "i should make a habit of marking cave exits"),
            Bot.Style.FARMER, List.of("i always plant way more wheat than i need", "bees make a little farm feel so much more alive",
                    "potatoes are probably my favourite early food", "i should remember to leave room for the water this time"),
            Bot.Style.FISHER, List.of("could honestly just fish for a while", "i keep forgetting to bring an extra rod",
                    "an unbreaking rod is so worth it", "the fishing bobber sound never gets old"));
    private final BetterBots plugin;
    private final Map<UUID, Long> recentPlayers = new HashMap<>();
    private final Set<String> recentLines = new HashSet<>();
    private Reply reply;
    private long nextGlobal;
    private long nextAmbient;

    Conversation(BetterBots plugin) {
        this.plugin = plugin;
    }

    void accept(UUID speakerId, Set<UUID> audience, String original) {
        if (!plugin.settings.chat() || !plugin.settings.replies() || reply != null
                || plugin.tick < recentPlayers.getOrDefault(speakerId, 0L)) {
            return;
        }
        Player speaker = Bukkit.getPlayer(speakerId);
        if (speaker == null) {
            return;
        }
        String message = original.strip().toLowerCase(Locale.ROOT);
        Set<String> words = new HashSet<>(Arrays.asList(message.split("[^a-z0-9_]+")));
        List<Bot> nearby = plugin.bots.values().stream().filter(this::canTalk)
                .filter(bot -> close(bot, speaker.getLocation())).toList();
        for (Bot bot : nearby) {
            if (words.contains(bot.name.toLowerCase(Locale.ROOT))) {
                schedule(bot, speakerId, audience, answer(bot, message));
                return;
            }
        }
        if (!plugin.settings.nearbyReplies() || Movement.random(1, 100) > 38) {
            return;
        }
        Bot nearest = nearest(nearby, speaker.getLocation());
        if (nearest != null && (GREETING.matcher(message).matches() || THANKS.matcher(message).matches()
                || message.equals("good night") || message.equals("gn") || message.equals("gg"))) {
            schedule(nearest, speakerId, audience, answer(nearest, message));
        }
    }

    void greet(Player player) {
        if (!plugin.settings.chat() || !plugin.settings.greet() || reply != null
                || plugin.tick < nextGlobal || Movement.random(1, 100) > 45) {
            return;
        }
        Bot bot = nearest(plugin.bots.values().stream().filter(this::canTalk)
                .filter(candidate -> close(candidate, player.getLocation())).toList(), player.getLocation());
        if (bot != null) {
            schedule(bot, player.getUniqueId(), null, choose("hey, welcome in", "oh hey!", "hi there",
                    "welcome! hope you find a good spot"));
        }
    }

    void tick() {
        if (!plugin.settings.chat()) {
            reply = null;
            return;
        }
        if (plugin.tick % 1200 == 0) {
            recentPlayers.values().removeIf(until -> until <= plugin.tick);
        }
        if (reply != null && plugin.tick >= reply.due()) {
            Reply pending = reply;
            reply = null;
            Player speaker = Bukkit.getPlayer(pending.player());
            if (speaker != null && canTalk(pending.bot()) && close(pending.bot(), speaker.getLocation())) {
                Predicate<Player> eligible = player -> player.getUniqueId().equals(pending.player())
                        || pending.audience() == null || pending.audience().contains(player.getUniqueId());
                send(pending.bot(), pending.message(), eligible);
            }
        }
        if (reply == null && plugin.settings.ambient() && plugin.tick >= nextAmbient) {
            nextAmbient = plugin.tick + 20L * Movement.random(plugin.settings.ambientMin(),
                    plugin.settings.ambientMax());
            List<Bot> choices = plugin.bots.values().stream().filter(this::canTalk)
                    .filter(bot -> !listeners(bot, player -> true).isEmpty()).toList();
            if (!choices.isEmpty()) {
                Bot bot = choices.get(Movement.random(0, choices.size() - 1));
                send(bot, ambient(bot), player -> true);
            }
        }
    }

    boolean force(Bot bot, String message) {
        if (!bot.online || bot.body == null || !bot.body.isValid()) {
            return false;
        }
        return send(bot, message, player -> true);
    }

    void reset() {
        reply = null;
        nextAmbient = plugin.tick + 20L * plugin.settings.ambientMin();
    }

    private void schedule(Bot bot, UUID id, Set<UUID> audience, String message) {
        long due = Math.max(plugin.tick + Movement.random(24, 68), nextGlobal);
        if (due - plugin.tick > 240) {
            return;
        }
        reply = new Reply(bot, id, audience, message, due);
        recentPlayers.put(id, due + 200);
    }

    private String answer(Bot bot, String message) {
        if (message.matches(".*\\b(where|coords|coordinates)\\b.*")) {
            Location at = bot.body.getLocation();
            return "i'm around " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
        }
        if (message.contains("doing") || message.contains("up to")) {
            return switch (bot.mode) {
                case STAY -> choose("just hanging out here for a bit", "taking a breather");
                case FOLLOW -> choose("sticking with someone for now", "trying to keep up");
                case WANDER -> switch (bot.style) {
                    case EXPLORER -> choose("just looking around", "seeing where this path goes");
                    case BUILDER -> choose("looking for a good spot to build", "trying to pick a palette");
                    case MINER -> choose("scouting for a cave entrance", "looking for somewhere to mine later");
                    case FARMER -> choose("looking for flat ground for a farm", "thinking about where to put some crops");
                    case FISHER -> choose("looking for somewhere quiet to fish", "checking out the area for water");
                };
            };
        }
        if (message.contains("trade")) {
            return choose("just looking around today", "i'm keeping what's in my pack");
        }
        if (message.contains("help")) {
            return choose("try asking in server chat, someone might know", "if it's about the server i'd ask a mod");
        }
        if (message.contains("thanks") || THANKS.matcher(message).matches()) {
            return choose("np", "no worries", "all good");
        }
        if (message.equals("gg")) {
            return choose("gg", "nice one", "gg!");
        }
        if (message.equals("gn") || message.contains("good night")) {
            return choose("night!", "gn", "see you around");
        }
        if (message.contains("?")) {
            return choose("not sure tbh", "i don't know that one", "honestly no clue");
        }
        return choose("hey!", "yo", "hey what's up", "oh hi", "hiya");
    }

    private String ambient(Bot bot) {
        Location at = bot.body.getLocation();
        if (Movement.random(1, 100) <= 22) {
            if (at.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
                return choose("i always carry extra blocks in the nether", "nether paths all look the same to me");
            }
            if (at.getWorld().hasStorm()) {
                return choose("kinda like the sound of the rain", "looks like rain again");
            }
            long time = at.getWorld().getTime();
            if (time >= 13000 && time < 23000) {
                return choose("already dark out huh", "i should keep a few torches handy");
            }
            if (bot.mode == Bot.Mode.STAY) {
                return choose("just taking a break here", "pretty quiet around here");
            }
        }
        List<String> lines = AMBIENT.get(bot.style);
        return choose(lines.toArray(String[]::new));
    }

    private String choose(String... lines) {
        int start = Movement.random(0, lines.length - 1);
        for (int offset = 0; offset < lines.length; offset++) {
            String candidate = lines[(start + offset) % lines.length];
            if (!recentLines.contains(candidate)) {
                return candidate;
            }
        }
        return lines[start];
    }

    private boolean canTalk(Bot bot) {
        return bot.online && !bot.muted && bot.body != null && bot.body.isValid()
                && plugin.tick >= bot.nextTalk;
    }

    private boolean close(Bot bot, Location listener) {
        Location body = bot.body.getLocation();
        double range = plugin.settings.proximity();
        return body.getWorld().equals(listener.getWorld()) && body.distanceSquared(listener) <= range * range;
    }

    private Bot nearest(List<Bot> bots, Location at) {
        Bot nearest = null;
        double distance = Double.MAX_VALUE;
        for (Bot bot : bots) {
            double candidate = bot.body.getLocation().distanceSquared(at);
            if (candidate < distance) {
                nearest = bot;
                distance = candidate;
            }
        }
        return nearest;
    }

    private Collection<? extends Player> listeners(Bot bot, Predicate<Player> eligible) {
        Location at = bot.body.getLocation();
        return at.getWorld().getNearbyPlayers(at, plugin.settings.proximity()).stream()
                .filter(player -> close(bot, player.getLocation())).filter(eligible).toList();
    }

    private boolean send(Bot bot, String message, Predicate<Player> eligible) {
        Collection<? extends Player> players = listeners(bot, eligible);
        if (players.isEmpty()) {
            return false;
        }
        if (recentLines.size() > 24) {
            recentLines.clear();
        }
        recentLines.add(message);
        Component chat = Component.text(bot.displayName(plugin.settings.showBotTag()), NamedTextColor.AQUA)
                .append(Component.text(" » ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.WHITE));
        players.forEach(player -> player.sendMessage(chat));
        Bukkit.getConsoleSender().sendMessage(chat);
        nextGlobal = plugin.tick + plugin.settings.cooldown() * 20L;
        bot.nextTalk = plugin.tick + 20L * Movement.random(14, 28);
        return true;
    }
}
