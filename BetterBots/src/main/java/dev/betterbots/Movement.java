package dev.betterbots;

import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

final class Movement {
    private static final Set<Material> HAZARDS = EnumSet.of(Material.LAVA, Material.WATER,
            Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE,
            Material.COBWEB, Material.BUBBLE_COLUMN, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY);
    private final BetterBots plugin;
    private Method getHandle;
    private Method setPosition;

    Movement(BetterBots plugin) {
        this.plugin = plugin;
        try {
            getHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftMannequin").getMethod("getHandle");
            setPosition = getHandle.getReturnType().getMethod("setPos", double.class, double.class, double.class);
        } catch (ReflectiveOperationException | LinkageError failure) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Using Bukkit entity movement.", failure);
        }
    }

    private void moveBody(Mannequin body, Location at) {
        if (setPosition != null) {
            try {
                setPosition.invoke(getHandle.invoke(body), at.getX(), at.getY(), at.getZ());
                body.setRotation(at.getYaw(), at.getPitch());
                body.setBodyYaw(at.getYaw());
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                setPosition = null;
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Using Bukkit entity movement.", failure);
            }
        }
        body.teleport(at);
    }

    boolean spawn(Bot bot, Location where) {
        despawn(bot);
        if (!loaded(where)) {
            return false;
        }
        World world = where.getWorld();
        try {
            bot.pilot = world.spawn(where, Zombie.class, false, pilot -> {
                pilot.getPersistentDataContainer().set(plugin.marker, PersistentDataType.STRING, bot.id.toString());
                pilot.setPersistent(false);
                pilot.setPersistenceRequired(true);
                pilot.setVisibleByDefault(false);
                pilot.setAdult();
                pilot.setInvisible(true);
                pilot.setSilent(true);
                pilot.setInvulnerable(true);
                pilot.setCollidable(false);
                pilot.setCanPickupItems(false);
                pilot.setShouldBurnInDay(false);
                pilot.setCanBreakDoors(false);
                pilot.setAware(true);
                pilot.getEquipment().clear();
                Bukkit.getMobGoals().removeAllGoals(pilot);
                pilot.getPathfinder().setCanOpenDoors(false);
                pilot.getPathfinder().setCanPassDoors(true);
                pilot.getPathfinder().setCanFloat(false);
            });
            bot.body = world.spawn(where, Mannequin.class, false, body -> {
                body.getPersistentDataContainer().set(plugin.marker, PersistentDataType.STRING, bot.id.toString());
                body.setPersistent(false);
                body.setGravity(false);
                body.setSilent(true);
                body.setInvulnerable(true);
                body.setCollidable(false);
                body.setCanPickupItems(false);
                body.setImmovable(true);
                name(bot, body);
                bot.equipment.forEach((slot, stack) -> body.getEquipment().setItem(slot, stack, true));
            });
            if (!bot.pilot.isValid() || !bot.body.isValid()) {
                despawn(bot);
                return false;
            }
            bot.lastSafe = where.clone();
            bot.nextMove = plugin.tick + random(15, 70);
            bot.nextIdle = plugin.tick + random(70, 180);
            bot.resumePose = 0;
            return true;
        } catch (RuntimeException failure) {
            despawn(bot);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not spawn " + bot.name, failure);
            return false;
        }
    }

    void name(Bot bot, Mannequin body) {
        body.setProfile(ResolvableProfile.resolvableProfile().uuid(bot.id).name(bot.name).build());
        updateLabel(bot, body);
        body.setCustomNameVisible(true);
        body.setDescription(null);
    }

    void updateLabel(Bot bot, Mannequin body) {
        body.customName(net.kyori.adventure.text.Component.text(bot.displayName(plugin.settings.showBotTag())));
    }

    void despawn(Bot bot) {
        if (bot.body != null) {
            bot.body.remove();
            bot.body = null;
        }
        if (bot.pilot != null) {
            bot.pilot.remove();
            bot.pilot = null;
        }
        bot.lastSafe = null;
    }

    void step(Bot bot) {
        Location anchor = bot.anchor.location();
        Player target = bot.mode == Bot.Mode.FOLLOW && bot.follow != null ? Bukkit.getPlayer(bot.follow) : null;
        if (!bot.online || anchor == null) {
            if (bot.body != null || bot.pilot != null) {
                despawn(bot);
            }
            return;
        }
        if (bot.body == null || bot.pilot == null || !bot.body.isValid() || !bot.pilot.isValid()) {
            if ((plugin.tick + bot.id.hashCode()) % 40 == 0) {
                despawn(bot);
                Location spawn = nearViewer(anchor) ? safeNear(anchor) : null;
                if (spawn != null) {
                    spawn(bot, spawn);
                }
            }
            return;
        }
        Location at = bot.pilot.getLocation();
        if ((plugin.tick + bot.id.hashCode()) % 40 == 0 && !nearViewer(at)) {
            despawn(bot);
            return;
        }
        if (at.getY() < at.getWorld().getMinHeight() + 1 || bot.pilot.isInWater()
                || HAZARDS.contains(at.getBlock().getType())) {
            Location recovery = bot.lastSafe == null ? safeNear(anchor) : bot.lastSafe;
            despawn(bot);
            if (recovery != null) {
                spawn(bot, recovery);
            }
            return;
        }
        if (bot.pilot.isConverting()) {
            bot.pilot.setConversionTime(-1);
        }
        Location old = bot.body.getLocation();
        if (!old.getWorld().equals(at.getWorld())) {
            despawn(bot);
            return;
        }
        double moved = old.distanceSquared(at);
        if (moved > 0.0001) {
            float yaw = old.getYaw();
            double dx = at.getX() - old.getX();
            double dz = at.getZ() - old.getZ();
            if (dx * dx + dz * dz > 0.0001) {
                float desired = (float) Math.toDegrees(Math.atan2(-dx, dz));
                yaw += Math.clamp(Location.normalizeYaw(desired - yaw), -32.0f, 32.0f);
            }
            at.setYaw(yaw);
            at.setPitch(0);
            moveBody(bot.body, at);
            if (bot.pilot.isOnGround() && plugin.tick >= bot.nextStep) {
                bot.nextStep = plugin.tick + random(6, 10);
                Block floor = at.clone().subtract(0, 0.15, 0).getBlock();
                if (standable(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                    bot.lastSafe = at.clone();
                }
                if (plugin.settings.footsteps() && !floor.isPassable()) {
                    at.getWorld().playSound(at, floor.getBlockSoundGroup().getStepSound(),
                            SoundCategory.PLAYERS, 0.17f, 0.95f);
                }
            }
        }
        if (bot.resumePose != 0 && plugin.tick >= bot.resumePose) {
            bot.body.setPose(Pose.STANDING, true);
            bot.resumePose = 0;
        }
        if (plugin.tick >= bot.nextMove) {
            navigate(bot, at, anchor, target);
        }
        if (plugin.tick >= bot.nextIdle && !bot.pilot.getPathfinder().hasPath()) {
            idle(bot, at);
        }
        if (bot.collecting && plugin.settings.collect() && (plugin.tick + bot.id.hashCode()) % 20 == 0) {
            collect(bot, at);
        }
    }

    private void navigate(Bot bot, Location at, Location anchor, Player target) {
        Pathfinder pathfinder = bot.pilot.getPathfinder();
        if (bot.mode == Bot.Mode.STAY) {
            pathfinder.stopPathfinding();
            bot.nextMove = plugin.tick + 30;
        } else if (bot.mode == Bot.Mode.FOLLOW) {
            bot.nextMove = plugin.tick + 16;
            if (target == null || !target.getWorld().equals(at.getWorld())) {
                pathfinder.stopPathfinding();
                return;
            }
            Location destination = target.getLocation();
            double distance = destination.distanceSquared(at);
            if (distance > 3.5 * 3.5 && distance < 38 * 38 && loaded(destination)) {
                Pathfinder.PathResult path = pathfinder.findPath(destination, 2);
                if (safePath(path)) {
                    pathfinder.moveTo(path, plugin.settings.speed() * (distance > 144 ? 1.22 : 1));
                }
            } else if (distance <= 3.5 * 3.5) {
                pathfinder.stopPathfinding();
                bot.body.lookAt(target.getEyeLocation().getX(), target.getEyeLocation().getY(),
                        target.getEyeLocation().getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            } else {
                pathfinder.stopPathfinding();
            }
        } else {
            bot.nextMove = plugin.tick + random(45, 120);
            if (pathfinder.hasPath()) {
                return;
            }
            int radius = plugin.settings.radius();
            for (int attempt = 0; attempt < 4; attempt++) {
                Location base = at.clone().add(random(-9, 9), 0, random(-9, 9));
                double dx = base.getX() - anchor.getX();
                double dz = base.getZ() - anchor.getZ();
                if (dx * dx + dz * dz > radius * radius || at.distanceSquared(base) < 10) {
                    continue;
                }
                Location goal = findFloor(base, 4);
                if (goal == null) {
                    continue;
                }
                Pathfinder.PathResult path = pathfinder.findPath(goal, 0);
                if (safePath(path) && path.canReachFinalPoint()
                        && pathfinder.moveTo(path, plugin.settings.speed())) {
                    return;
                }
            }
            if (at.distanceSquared(anchor) > (radius + 2) * (radius + 2) && loaded(anchor)) {
                Pathfinder.PathResult route = pathfinder.findPath(anchor, 1);
                if (safePath(route)) {
                    pathfinder.moveTo(route, plugin.settings.speed());
                }
            }
        }
    }

    private boolean safePath(Pathfinder.PathResult path) {
        if (path == null) {
            return false;
        }
        return path.getPoints().stream().allMatch(point -> loaded(point)
                && point.getWorld().getWorldBorder().isInside(point)
                && !HAZARDS.contains(point.getBlock().getType())
                && !HAZARDS.contains(point.clone().add(0, 1, 0).getBlock().getType())
                && !HAZARDS.contains(point.clone().subtract(0, 1, 0).getBlock().getType()));
    }

    private void idle(Bot bot, Location at) {
        bot.nextIdle = plugin.tick + random(75, 220);
        List<Player> players = at.getWorld().getNearbyPlayers(at, 7).stream()
                .filter(player -> player.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                .filter(player -> player.hasLineOfSight(bot.body)).toList();
        if (!players.isEmpty()) {
            Location eyes = players.getFirst().getEyeLocation();
            bot.body.lookAt(eyes.getX(), eyes.getY(), eyes.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        } else {
            bot.body.setRotation(Location.normalizeYaw(at.getYaw() + random(-65, 65)), random(-8, 15));
        }
        switch (random(0, 4)) {
            case 0, 1 -> bot.body.swingMainHand();
            case 2 -> {
                bot.body.setPose(Pose.SNEAKING, true);
                bot.resumePose = plugin.tick + random(13, 32);
            }
            case 3 -> {
                ItemStack held = bot.equipment.get(EquipmentSlot.HAND);
                if (held != null && held.getType() == Material.SPYGLASS) {
                    Mannequin body = bot.body;
                    body.startUsingItem(EquipmentSlot.HAND);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (body.isValid()) {
                            body.clearActiveItem();
                        }
                    }, 26);
                }
            }
            default -> { }
        }
    }

    private void collect(Bot bot, Location at) {
        for (Item item : at.getWorld().getNearbyEntitiesByType(Item.class, at, 1.3, 0.8, 1.3)) {
            if (!item.isValid() || item.getOwner() != null || item.getPickupDelay() > 0
                    || !bot.body.hasLineOfSight(item)) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            int before = stack.getAmount();
            ItemStack leftover = bot.inventory.addItem(stack.clone()).get(0);
            if (leftover == null || leftover.getAmount() < before) {
                if (leftover == null) {
                    item.remove();
                } else {
                    item.setItemStack(leftover);
                }
                bot.body.swingMainHand();
                at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.15f, 1.1f);
                plugin.dirty = true;
                return;
            }
        }
    }

    private boolean nearViewer(Location at) {
        double range = plugin.settings.activation();
        return at.getWorld().getNearbyPlayers(at, range).stream()
                .anyMatch(player -> player.getLocation().distanceSquared(at) <= range * range);
    }

    static Location safeNear(Location center) {
        Location chosen = findFloor(center, 4);
        if (chosen != null) {
            return chosen;
        }
        for (int radius = 1; radius <= 4; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == radius) {
                        chosen = findFloor(center.clone().add(x, 0, z), 4);
                        if (chosen != null) {
                            return chosen;
                        }
                    }
                }
            }
        }
        return null;
    }

    static Location findFloor(Location point, int limit) {
        if (!loaded(point) || !point.getWorld().getWorldBorder().isInside(point)) {
            return null;
        }
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        for (int shift = 0; shift <= limit; shift++) {
            int up = point.getBlockY() + shift;
            int down = point.getBlockY() - shift;
            if (standable(world, x, up, z)) {
                return new Location(world, x + 0.5, up, z + 0.5, point.getYaw(), 0);
            }
            if (shift != 0 && standable(world, x, down, z)) {
                return new Location(world, x + 0.5, down, z + 0.5, point.getYaw(), 0);
            }
        }
        return null;
    }

    private static boolean standable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block floor = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        if (floor.isPassable() || !feet.isPassable() || !head.isPassable()
                || HAZARDS.contains(floor.getType()) || HAZARDS.contains(feet.getType())
                || HAZARDS.contains(head.getType())) {
            return false;
        }
        BoundingBox collision = floor.getBoundingBox();
        return collision.getMaxY() <= y + 0.02 && collision.getMaxY() >= y - 0.55;
    }

    static boolean loaded(Location at) {
        return at != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
    }

    static int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
