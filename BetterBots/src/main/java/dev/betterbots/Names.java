package dev.betterbots;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

final class Names implements AutoCloseable {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final String RANDOM_USER =
            "https://randomuser.me/api/1.4/?results=32&inc=login&noinfo=1";
    private static final String FAKER =
            "https://fakerapi.it/api/v2/custom?_quantity=32&username=userName";
    private static final List<String> FIRST = List.of("Moss", "Copper", "Juniper", "Ash", "Quill",
            "Flint", "Fern", "Lantern", "Ember", "Birch", "Cinder", "Mango", "Wren", "Silver",
            "Cobalt", "Quartz", "Hollow", "Rook", "Moth", "Rowan", "Pebble", "Cedar", "Frost");
    private static final List<String> SECOND = List.of("Trail", "Hops", "Grove", "Vale", "Forge",
            "Ridge", "Bramble", "Otter", "Scout", "Sparrow", "Brew", "Pines", "Cove", "Rift",
            "Badger", "Brook", "Dusk", "Cairn", "Gale", "Hare", "Raven", "Fox");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final LinkedHashSet<String> pool = new LinkedHashSet<>();
    private long retryAfter = System.nanoTime();
    private int provider;

    static boolean valid(String name) {
        return VALID.matcher(name).matches();
    }

    synchronized List<String> take(int count, boolean web) {
        if (web && pool.size() < count && System.nanoTime() - retryAfter >= 0) {
            Collection<String> fetched = fetch(provider++ % 2 == 0);
            if (fetched.isEmpty() && !Thread.currentThread().isInterrupted()) {
                fetched = fetch(provider++ % 2 == 0);
            }
            pool.addAll(fetched);
            if (fetched.isEmpty()) {
                retryAfter = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            }
        }
        List<String> result = new ArrayList<>(count);
        while (web && result.size() < count && !pool.isEmpty()) {
            result.add(pool.removeFirst());
        }
        while (result.size() < count) {
            result.add(local());
        }
        return result;
    }

    private Collection<String> fetch(boolean randomUser) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(randomUser ? RANDOM_USER : FAKER))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BetterBots/1.0")
                    .build();
            HttpResponse<byte[]> response = http.send(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65_536));
            if (response.statusCode() == 200) {
                return parse(new String(response.body(), StandardCharsets.UTF_8), randomUser);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException ignored) {
            // Missing or malformed provider data is handled with another source or local generation.
        }
        return List.of();
    }

    static Collection<String> parse(String json, boolean randomUser) {
        JsonElement document = JsonParser.parseString(json);
        if (!(document instanceof JsonObject root)
                || !(root.get(randomUser ? "results" : "data") instanceof JsonArray rows)) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement row : rows) {
            if (!(row instanceof JsonObject user)) {
                continue;
            }
            JsonObject entry = user;
            if (randomUser) {
                if (!(user.get("login") instanceof JsonObject login)) {
                    continue;
                }
                entry = login;
            }
            JsonElement value = entry.get("username");
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String candidate = value.getAsString().replaceAll("[^A-Za-z0-9_]", "");
                candidate = candidate.substring(0, Math.min(16, candidate.length()));
                if (valid(candidate)) {
                    names.add(candidate);
                }
            }
        }
        return names;
    }

    static String local() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String name = FIRST.get(random.nextInt(FIRST.size())) + SECOND.get(random.nextInt(SECOND.size()));
        return random.nextBoolean() ? name : name + random.nextInt(10, 100);
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
