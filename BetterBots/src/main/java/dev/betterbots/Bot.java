package dev.betterbots;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class Bot {
    enum Mode { WANDER, STAY, FOLLOW }
    enum Style {
        EXPLORER(Material.SPYGLASS, Material.LEATHER_BOOTS),
        BUILDER(Material.OAK_PLANKS, Material.LEATHER_CHESTPLATE),
        MINER(Material.IRON_PICKAXE, Material.IRON_HELMET),
        FARMER(Material.STONE_HOE, Material.LEATHER_LEGGINGS),
        FISHER(Material.FISHING_ROD, Material.LEATHER_BOOTS);

        final Material hand;
        final Material clothing;

        Style(Material hand, Material clothing) {
            this.hand = hand;
            this.clothing = clothing;
        }
    }

    record Anchor(String world, double x, double y, double z, float yaw) {
        Anchor(Location at) {
            this(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw());
        }

        Location location() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, 0);
        }
    }

    static final Map<String, EquipmentSlot> SLOTS = Map.of(
            "hand", EquipmentSlot.HAND, "offhand", EquipmentSlot.OFF_HAND,
            "head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST,
            "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET);

    final UUID id;
    final Inventory inventory;
    final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    String name;
    Anchor anchor;
    Style style;
    Mode mode = Mode.WANDER;
    UUID follow;
    boolean muted;
    boolean collecting;
    boolean online = true;

    Mannequin body;
    Zombie pilot;
    Location lastSafe;
    long nextMove;
    long nextIdle;
    long nextStep;
    long nextTalk;
    long resumePose;

    Bot(UUID id, String name, Anchor anchor, Style style) {
        this.id = id;
        this.name = name;
        this.anchor = anchor;
        this.style = style;
        this.inventory = Bukkit.createInventory(null, 27);
    }

    void equip(EquipmentSlot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            equipment.remove(slot);
        } else {
            equipment.put(slot, stack.clone());
        }
        if (body != null) {
            body.getEquipment().setItem(slot, equipment.get(slot), true);
        }
    }

    void outfit() {
        equip(EquipmentSlot.HAND, ItemStack.of(style.hand));
        equip(EquipmentSlot.OFF_HAND, ItemStack.of(style == Style.EXPLORER ? Material.COMPASS : Material.TORCH));
        EquipmentSlot clothingSlot = switch (style) {
            case EXPLORER, FISHER -> EquipmentSlot.FEET;
            case BUILDER -> EquipmentSlot.CHEST;
            case MINER -> EquipmentSlot.HEAD;
            case FARMER -> EquipmentSlot.LEGS;
        };
        equip(clothingSlot, ItemStack.of(style.clothing));
        inventory.addItem(ItemStack.of(Material.BREAD, 3), ItemStack.of(Material.TORCH, 6));
    }

    String displayName(boolean showBotTag) {
        return showBotTag ? "[Bot] " + name : name;
    }

    String describe() {
        return name + " · " + style.name().toLowerCase(Locale.ROOT) + " · "
                + (online ? mode.name().toLowerCase(Locale.ROOT) : "paused")
                + (muted ? " · muted" : "") + (collecting ? " · collecting" : "");
    }
}
