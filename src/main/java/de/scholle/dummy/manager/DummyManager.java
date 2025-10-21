package de.scholle.dummy.manager;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.attribute.AttributeInstance;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.NpcData;
import org.bukkit.attribute.Attribute;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.SoundCategory;
import org.bukkit.Sound;
import de.oliver.fancynpcs.api.Npc;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.HandlerList;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.event.entity.PlayerDeathEvent;
import java.util.HashSet;
import java.util.HashMap;
import org.bukkit.scheduler.BukkitTask;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import de.scholle.dummy.Dummy;
import org.bukkit.event.Listener;

public class DummyManager implements Listener {
    private final Dummy plugin;
    private final MiniMessage MM;
    private final Map<UUID, DummyData> active;
    private final Map<String, UUID> byNpcName;
    private final Set<UUID> pendingDeath;
    private final Set<UUID> suppressSpawnOnce;
    private BukkitTask ticker;
    private Listener deathFilterListener;

    public DummyManager(final Dummy plugin) {
        this.MM = MiniMessage.miniMessage();
        this.active = new HashMap<UUID, DummyData>();
        this.byNpcName = new HashMap<String, UUID>();
        this.pendingDeath = new HashSet<UUID>();
        this.suppressSpawnOnce = new HashSet<UUID>();
        this.plugin = plugin;
    }

    public void start() {
        this.stop();
        this.deathFilterListener = (Listener)new Listener() {
            @EventHandler
            public void onDeath(final PlayerDeathEvent e) {
                final UUID uid = e.getEntity().getUniqueId();
                if (!DummyManager.this.pendingDeath.contains(uid)) {
                    return;
                }
                try {
                    e.deathMessage((Component)null);
                }
                catch (final Throwable t) {}
                e.getDrops().clear();
                e.setShouldDropExperience(false);
                e.setDroppedExp(0);
            }
        };
        this.plugin.getServer().getPluginManager().registerEvents(this.deathFilterListener, (Plugin)this.plugin);
        this.ticker = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            final long now = System.currentTimeMillis();
            final Iterator<Map.Entry<UUID, DummyData>> it = this.active.entrySet().iterator();
            while (it.hasNext()) {
                final DummyData d = it.next().getValue();
                final long secs = Math.max(0L, (d.expiresAt - now) / 1000L);
                try {
                    d.npc.getData().setDisplayName(this.mmTitle(d.ownerName, d.health, d.maxHealth, secs));
                    d.npc.updateForAll();
                    this.calmNpc(d.npc, d.location);
                } catch (final Throwable t) {}
                if (now >= d.expiresAt) {
                    this.removeNpc(d);
                    it.remove();
                }
            }
        }, 5L, 5L);
    }

    public void stop() {
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
        for (final UUID u : new ArrayList<>(this.active.keySet())) {
            final DummyData d = this.active.remove(u);
            if (d != null) {
                this.removeNpc(d);
            }
        }
        this.byNpcName.clear();
        this.pendingDeath.clear();
        this.suppressSpawnOnce.clear();
        if (this.deathFilterListener != null) {
            HandlerList.unregisterAll(this.deathFilterListener);
            this.deathFilterListener = null;
        }
    }

    public void onDummyHit(final ArmorStand ignored, final Player damager) {
        // Basic implementation for dummy hit handling
    }

    public void suppressSpawn(final UUID id) {
        this.suppressSpawnOnce.add(id);
    }

    public void markPendingDeath(final UUID id) {
        this.pendingDeath.add(id);
    }

    public void onNpcLeftClick(final Npc npc, final Player damager) {
        final DummyData d = this.findByNpc(npc);
        if (d == null || damager == null) {
            return;
        }
        d.expiresAt = System.currentTimeMillis() + (plugin.getDummyDuration() * 1000L);
        final World w = d.location.getWorld();
        if (w != null) {
            w.playSound(d.location, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
        final double damage = approximateDamage(damager);
        double afterArmor = applyArmorReduction(damage, d.armorPoints, d.toughness);
        if (afterArmor <= 0.0) {
            afterArmor = 0.5;
        }
        final DummyData dummy = d;
        dummy.health -= afterArmor;
        if (d.health <= 1.0E-4) {
            this.killDummy(d, damager);
        }
    }

    public void spawnOnLogout(final Player p) {
        try {
            final UUID id = p.getUniqueId();
            if (this.suppressSpawnOnce.remove(id)) {
                return;
            }
            this.createFor(p);
        }
        catch (final Exception ex) {
            this.plugin.getLogger().warning("[Dummy] Spawn fehlgeschlagen: " + ex.getMessage());
        }
    }

    public void handleJoin(final Player p) {
        final UUID id = p.getUniqueId();
        if (!this.pendingDeath.remove(id)) {
            return;
        }
        try {
            p.getInventory().clear();
            p.getInventory().setArmorContents(new ItemStack[4]);
            p.getInventory().setItemInOffHand((ItemStack)null);
            p.updateInventory();
        }
        catch (final Throwable t) {}
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> p.setHealth(0.0));
    }

    public boolean hasPendingDeath(final UUID id) {
        return this.pendingDeath.contains(id);
    }

    private Location snapToGround(final Location src) {
        if (src == null || src.getWorld() == null) {
            return src;
        }
        final World w = src.getWorld();
        int y;
        for (int minY = w.getMinHeight(), startY = y = Math.min(w.getMaxHeight() - 1, Math.max(minY + 1, src.getBlockY())); y > minY + 1; --y) {
            final Material below = w.getBlockAt(src.getBlockX(), y - 1, src.getBlockZ()).getType();
            if (!below.isAir()) {
                return new Location(w, src.getX(), y + 0.01, src.getZ(), src.getYaw(), 0.0f);
            }
        }
        final int hy = w.getHighestBlockYAt(src);
        return new Location(w, src.getX(), hy + 0.01, src.getZ(), src.getYaw(), 0.0f);
    }

    private void createFor(final Player p) {
        Location loc = p.getLocation().clone();
        loc.setPitch(0.0f);
        loc = this.snapToGround(loc);
        double max = 20.0;
        try {
            final AttributeInstance attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                max = Math.max(1.0, attr.getValue());
            }
        }
        catch (final Throwable t) {}
        final double cur = Math.max(0.5, Math.min(max, p.getHealth()));
        final PlayerInventory inv = p.getInventory();
        final ItemStack[] storage = cloneArray(inv.getStorageContents());
        final ItemStack[] armor = cloneArray(inv.getArmorContents());
        final ItemStack offhand = safeClone(inv.getItemInOffHand());
        final ItemStack mainHand = safeClone(inv.getItemInMainHand());
        final ArmorStats stats = calcArmorStats(armor);
        final String npcName = "dummy_" + p.getUniqueId().toString().substring(0, 8);
        final NpcData data = new NpcData(npcName, p.getUniqueId(), loc);

        if (plugin.usePlayerSkin()) {
            data.setSkin(p.getUniqueId().toString());
        }

        data.setDisplayName(this.mmTitle(p.getName(), cur, max, plugin.getDummyDuration()));
        final Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
        npc.setSaveToFile(false);
        FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);
        npc.create();
        npc.spawnForAll();
        this.freezeNpc(npc, loc);

        if (plugin.showEquipment()) {
            this.applyVisualEquipmentSafe(npc, armor, mainHand, offhand);
        }

        final DummyData d = new DummyData(p.getUniqueId(), p.getName(), npc, loc, cur, max, storage, armor, offhand, mainHand, stats.armorPoints, stats.toughness);
        d.expiresAt = System.currentTimeMillis() + (plugin.getDummyDuration() * 1000L);
        this.active.put(p.getUniqueId(), d);
        this.byNpcName.put(npcName, p.getUniqueId());

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Dummy] Dummy erstellt für " + p.getName() + " für " + plugin.getDummyDuration() + " Sekunden");
        }
    }

    private void killDummy(final DummyData d, final Player killer) {
        if (plugin.shouldDropItems()) {
            this.dropOnce(d);
        }
        this.removeNpc(d);
        this.active.remove(d.owner);

        final String k = (killer != null) ? killer.getName() : "unbekannt";
        String broadcastMsg = plugin.getKillBroadcast()
                .replace("{player}", d.ownerName)
                .replace("{killer}", k);
        Bukkit.getServer().broadcast(Component.text(broadcastMsg));

        this.pendingDeath.add(d.owner);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Dummy] Dummy von " + d.ownerName + " wurde von " + k + " besiegt");
        }
    }

    private void removeNpc(final DummyData d) {
        try {
            d.npc.removeForAll();
            FancyNpcsPlugin.get().getNpcManager().removeNpc(d.npc);
        }
        catch (final Throwable t) {}
        this.byNpcName.values().removeIf(u -> u.equals(d.owner));

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[Dummy] Dummy von " + d.ownerName + " entfernt");
        }
    }

    private void dropOnce(final DummyData d) {
        if (d.dropped) {
            return;
        }
        d.dropped = true;
        final World w = d.location.getWorld();
        if (w == null) {
            return;
        }
        for (final ItemStack is : d.storage) {
            this.drop(w, d.location, is);
        }
        for (final ItemStack is : d.armor) {
            this.drop(w, d.location, is);
        }
        this.drop(w, d.location, d.offhand);
    }

    private void drop(final World w, final Location at, final ItemStack is) {
        if (is == null || is.getType() == Material.AIR) {
            return;
        }
        w.dropItemNaturally(at, is.clone());
    }

    private DummyData findByNpc(final Npc npc) {
        try {
            final String name = npc.getData().getName();
            final UUID owner = this.byNpcName.get(name);
            if (owner != null) {
                return this.active.get(owner);
            }
        }
        catch (final Throwable t) {}
        for (final DummyData d : this.active.values()) {
            if (d.npc == npc) {
                return d;
            }
        }
        return null;
    }

    private String mmTitle(final String playerName, final double health, final double max, final long secs) {
        return "<red>Offline</red> <gray>|</gray> <gold><bold>" + playerName + "</bold></gold> <gray>|</gray> " + this.heartBarMini(health, max) + " <gray>(" + secs + "s)</gray>";
    }

    private String heartBarMini(final double hp, final double max) {
        final int total = Math.max(1, (int)Math.round(max / 2.0));
        final int full = (int)Math.floor(hp / 2.0);
        final boolean half = hp % 2.0 >= 1.0;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < full; ++i) {
            sb.append("<red>\u2764</red>");
        }
        if (half) {
            sb.append("<gold>\u2764</gold>");
        }
        while (this.countHearts(sb) < total) {
            sb.append("<gray>\u2764</gray>");
        }
        return sb.toString();
    }

    private int countHearts(final CharSequence s) {
        int c = 0;
        for (int i = 0; i < s.length(); ++i) {
            if (s.charAt(i) == '\u2764') {
                ++c;
            }
        }
        return c;
    }

    private void freezeNpc(final Npc npc, final Location loc) {
        if (npc == null) {
            return;
        }
        try {
            final Object data = npc.getData();
            this.callFlag(data, "setCanMove", plugin.getConfig().getBoolean("npc-behavior.can-move", false));
            this.callFlag(data, "setWalking", false);
            this.callFlag(data, "setWalk", false);
            this.callFlag(data, "setSprint", false);
            this.callFlag(data, "setRunning", false);
            this.callFlag(data, "setJumping", false);
            this.callFlag(data, "setGliding", false);
            this.callFlag(data, "setSwimming", false);
            this.callFlag(data, "setAttackAnimation", plugin.getConfig().getBoolean("npc-behavior.can-attack", false));
            this.callFlag(data, "setAttacking", plugin.getConfig().getBoolean("npc-behavior.can-attack", false));
            this.callFlag(data, "setAttack", plugin.getConfig().getBoolean("npc-behavior.can-attack", false));
            this.callFlag(data, "setSwinging", false);
            this.callFlag(data, "setLeftClicking", false);
            this.callFlag(data, "setHandAnimation", false);
            this.callFlag(data, "setUseItem", false);
            this.callFlag(data, "setAttackable", false);
            this.callFlag(data, "setImitatePlayer", plugin.getConfig().getBoolean("npc-behavior.imitate-player", false));
            this.callFlag(data, "setTurnToPlayer", plugin.getConfig().getBoolean("npc-behavior.look-at-players", true));
            this.callFlag(data, "setLookAtPlayer", plugin.getConfig().getBoolean("npc-behavior.look-at-players", true));
            try {
                Method m = null;
                final Class<?> poseEnum = Arrays.stream(data.getClass().getMethods())
                        .filter(method -> method.getName().equals("setPose") && method.getParameterCount() == 1)
                        .map(method -> method.getParameterTypes()[0])
                        .findFirst()
                        .orElse(null);
                if (poseEnum != null && poseEnum.isEnum()) {
                    final Object standing = Arrays.stream(poseEnum.getEnumConstants()).filter(ec -> ec.toString().equalsIgnoreCase("STANDING") || ec.toString().equalsIgnoreCase("STAND")).findFirst().orElse(null);
                    if (standing != null) {
                        m = data.getClass().getMethod("setPose", poseEnum);
                        m.invoke(data, standing);
                    }
                }
            }
            catch (final Throwable t) {}
            this.callRotation(data, "setRotation", new Class[] { Float.TYPE, Float.TYPE }, new Object[] { loc.getYaw(), 0.0f });
            this.callRotation(data, "setYaw", new Class[] { Float.TYPE }, new Object[] { loc.getYaw() });
            this.callRotation(data, "setPitch", new Class[] { Float.TYPE }, new Object[] { 0.0f });
            npc.updateForAll();
        }
        catch (final Throwable t2) {}
    }

    private void calmNpc(final Npc npc, final Location faceTowards) {
        if (npc == null) {
            return;
        }
        try {
            final Object data = npc.getData();
            this.callFlag(data, "setAttackAnimation", false);
            this.callFlag(data, "setAttacking", false);
            this.callFlag(data, "setAttack", false);
            this.callFlag(data, "setSwinging", false);
            this.callFlag(data, "setLeftClicking", false);
            this.callFlag(data, "setUseItem", false);
            this.callFlag(data, "setHandAnimation", false);
            this.callFlag(data, "setImitatePlayer", plugin.getConfig().getBoolean("npc-behavior.imitate-player", false));
            this.callFlag(data, "setCanMove", plugin.getConfig().getBoolean("npc-behavior.can-move", false));
            this.callFlag(data, "setWalking", false);
            this.callFlag(data, "setWalk", false);
            this.callFlag(data, "setRunning", false);
            this.callFlag(data, "setSprint", false);
            this.callFlag(data, "setJumping", false);
            this.callFlag(data, "setGliding", false);
            this.callFlag(data, "setSwimming", false);
            this.callFlag(data, "setAttackable", false);
            this.callFlag(data, "setLookAtPlayer", plugin.getConfig().getBoolean("npc-behavior.look-at-players", true));
            this.callFlag(data, "setTurnToPlayer", plugin.getConfig().getBoolean("npc-behavior.look-at-players", true));
            if (faceTowards != null) {
                this.callRotation(data, "setRotation", new Class[] { Float.TYPE, Float.TYPE }, new Object[] { faceTowards.getYaw(), 0.0f });
                this.callRotation(data, "setYaw", new Class[] { Float.TYPE }, new Object[] { faceTowards.getYaw() });
                this.callRotation(data, "setPitch", new Class[] { Float.TYPE }, new Object[] { 0.0f });
            }
            npc.updateForAll();
        }
        catch (final Throwable t) {}
    }

    private void callFlag(final Object target, final String setter, final boolean value) {
        try {
            target.getClass().getMethod(setter, Boolean.TYPE).invoke(target, value);
        }
        catch (final Throwable t) {}
    }

    private boolean callRotation(final Object target, final String name, final Class<?>[] paramTypes, final Object[] args) {
        try {
            final Method m = target.getClass().getMethod(name, paramTypes);
            m.invoke(target, args);
            return true;
        }
        catch (final Throwable ignored) {
            return false;
        }
    }

    private static double approximateDamage(final Player attacker) {
        final ItemStack mh = attacker.getInventory().getItemInMainHand();
        final Material t = (mh != null) ? mh.getType() : Material.AIR;
        double base = 0.0;
        switch (t) {
            case NETHERITE_SWORD: {
                base = 8.0;
                break;
            }
            case DIAMOND_SWORD: {
                base = 7.0;
                break;
            }
            case IRON_SWORD: {
                base = 6.0;
                break;
            }
            case STONE_SWORD: {
                base = 5.0;
                break;
            }
            case GOLDEN_SWORD:
            case WOODEN_SWORD: {
                base = 4.0;
                break;
            }
            case NETHERITE_AXE: {
                base = 10.0;
                break;
            }
            case DIAMOND_AXE: {
                base = 9.0;
                break;
            }
            case IRON_AXE:
            case STONE_AXE: {
                base = 9.0;
                break;
            }
            case GOLDEN_AXE:
            case WOODEN_AXE: {
                base = 7.0;
                break;
            }
            default: {
                base = 3.0;
                break;
            }
        }
        final boolean crit = attacker.getFallDistance() > 0.0 && !attacker.isSprinting() && !attacker.isClimbing() && !attacker.isInWater() && !attacker.isGliding();
        if (crit) {
            base *= 1.5;
        }
        final PotionEffect eff = attacker.getPotionEffect(PotionEffectType.INCREASE_DAMAGE);
        if (eff != null) {
            base += 3.0 * (eff.getAmplifier() + 1);
        }
        return Math.max(0.5, base);
    }

    private static double applyArmorReduction(final double damage, final int armorPoints, final double toughness) {
        final double base = Math.min(20.0, Math.max(armorPoints / 5.0, armorPoints - damage / (2.0 + toughness / 4.0)));
        final double multiplier = 1.0 - base / 25.0;
        return Math.max(0.0, damage * multiplier);
    }

    private static ArmorStats calcArmorStats(final ItemStack[] armor) {
        final ArmorStats s = new ArmorStats();
        if (armor == null) {
            return s;
        }
        for (final ItemStack piece : armor) {
            if (piece != null) {
                switch (piece.getType()) {
                    case LEATHER_HELMET: {
                        final ArmorStats armorStats = s;
                        ++armorStats.armorPoints;
                        break;
                    }
                    case LEATHER_CHESTPLATE: {
                        final ArmorStats armorStats2 = s;
                        armorStats2.armorPoints += 3;
                        break;
                    }
                    case LEATHER_LEGGINGS: {
                        final ArmorStats armorStats3 = s;
                        armorStats3.armorPoints += 2;
                        break;
                    }
                    case LEATHER_BOOTS: {
                        final ArmorStats armorStats4 = s;
                        ++armorStats4.armorPoints;
                        break;
                    }
                    case GOLDEN_HELMET:
                    case CHAINMAIL_HELMET: {
                        final ArmorStats armorStats5 = s;
                        armorStats5.armorPoints += 2;
                        break;
                    }
                    case GOLDEN_CHESTPLATE:
                    case CHAINMAIL_CHESTPLATE: {
                        final ArmorStats armorStats6 = s;
                        armorStats6.armorPoints += 5;
                        break;
                    }
                    case GOLDEN_LEGGINGS:
                    case CHAINMAIL_LEGGINGS: {
                        final ArmorStats armorStats7 = s;
                        armorStats7.armorPoints += 4;
                        break;
                    }
                    case GOLDEN_BOOTS:
                    case CHAINMAIL_BOOTS: {
                        final ArmorStats armorStats8 = s;
                        ++armorStats8.armorPoints;
                        break;
                    }
                    case IRON_HELMET: {
                        final ArmorStats armorStats9 = s;
                        armorStats9.armorPoints += 2;
                        break;
                    }
                    case IRON_CHESTPLATE: {
                        final ArmorStats armorStats10 = s;
                        armorStats10.armorPoints += 6;
                        break;
                    }
                    case IRON_LEGGINGS: {
                        final ArmorStats armorStats11 = s;
                        armorStats11.armorPoints += 5;
                        break;
                    }
                    case IRON_BOOTS: {
                        final ArmorStats armorStats12 = s;
                        armorStats12.armorPoints += 2;
                        break;
                    }
                    case DIAMOND_HELMET: {
                        final ArmorStats armorStats13 = s;
                        armorStats13.armorPoints += 3;
                        final ArmorStats armorStats14 = s;
                        armorStats14.toughness += 2.0;
                        break;
                    }
                    case DIAMOND_CHESTPLATE: {
                        final ArmorStats armorStats15 = s;
                        armorStats15.armorPoints += 8;
                        final ArmorStats armorStats16 = s;
                        armorStats16.toughness += 2.0;
                        break;
                    }
                    case DIAMOND_LEGGINGS: {
                        final ArmorStats armorStats17 = s;
                        armorStats17.armorPoints += 6;
                        final ArmorStats armorStats18 = s;
                        armorStats18.toughness += 2.0;
                        break;
                    }
                    case DIAMOND_BOOTS: {
                        final ArmorStats armorStats19 = s;
                        armorStats19.armorPoints += 3;
                        final ArmorStats armorStats20 = s;
                        armorStats20.toughness += 2.0;
                        break;
                    }
                    case NETHERITE_HELMET: {
                        final ArmorStats armorStats21 = s;
                        armorStats21.armorPoints += 3;
                        final ArmorStats armorStats22 = s;
                        armorStats22.toughness += 3.0;
                        break;
                    }
                    case NETHERITE_CHESTPLATE: {
                        final ArmorStats armorStats23 = s;
                        armorStats23.armorPoints += 8;
                        final ArmorStats armorStats24 = s;
                        armorStats24.toughness += 3.0;
                        break;
                    }
                    case NETHERITE_LEGGINGS: {
                        final ArmorStats armorStats25 = s;
                        armorStats25.armorPoints += 6;
                        final ArmorStats armorStats26 = s;
                        armorStats26.toughness += 3.0;
                        break;
                    }
                    case NETHERITE_BOOTS: {
                        final ArmorStats armorStats27 = s;
                        armorStats27.armorPoints += 3;
                        final ArmorStats armorStats28 = s;
                        armorStats28.toughness += 3.0;
                        break;
                    }
                }
            }
        }
        return s;
    }

    private void applyVisualEquipmentSafe(final Npc npc, final ItemStack[] armor, final ItemStack mainHand, final ItemStack offhand) {
        try {
            final Object data = npc.getData();
            try {
                final Method mGetEq = data.getClass().getMethod("getEquipment", (Class<?>[])new Class[0]);
                final Object eq = mGetEq.invoke(data, new Object[0]);
                boolean changed = false;
                changed |= this.setEqB(eq, "setHelmet", this.getArmor(armor, EquipmentSlot.HEAD));
                changed |= this.setEqB(eq, "setChestplate", this.getArmor(armor, EquipmentSlot.CHEST));
                changed |= this.setEqB(eq, "setLeggings", this.getArmor(armor, EquipmentSlot.LEGS));
                changed |= this.setEqB(eq, "setBoots", this.getArmor(armor, EquipmentSlot.FEET));
                changed |= (this.setEqB(eq, "setHand", mainHand) | this.setEqB(eq, "setMainHand", mainHand));
                changed |= (this.setEqB(eq, "setOffhand", offhand) | this.setEqB(eq, "setOffHand", offhand));
                try {
                    final Method mSet = data.getClass().getMethod("setEquipment", eq.getClass());
                    mSet.invoke(data, eq);
                }
                catch (final Throwable t) {}
                if (changed) {
                    npc.updateForAll();
                }
            }
            catch (final NoSuchMethodException ex) {
                boolean changed2 = false;
                changed2 |= this.callIfPresentB(data, "setHelmet", ItemStack.class, this.getArmor(armor, EquipmentSlot.HEAD));
                changed2 |= this.callIfPresentB(data, "setChestplate", ItemStack.class, this.getArmor(armor, EquipmentSlot.CHEST));
                changed2 |= this.callIfPresentB(data, "setLeggings", ItemStack.class, this.getArmor(armor, EquipmentSlot.LEGS));
                changed2 |= this.callIfPresentB(data, "setBoots", ItemStack.class, this.getArmor(armor, EquipmentSlot.FEET));
                changed2 |= (this.callIfPresentB(data, "setMainHand", ItemStack.class, mainHand) | this.callIfPresentB(data, "setHand", ItemStack.class, mainHand));
                changed2 |= (this.callIfPresentB(data, "setOffHand", ItemStack.class, offhand) | this.callIfPresentB(data, "setOffhand", ItemStack.class, offhand));
                if (changed2) {
                    npc.updateForAll();
                }
            }
        }
        catch (final Throwable t2) {}
    }

    private boolean setEqB(final Object eq, final String method, final ItemStack item) {
        try {
            final Method m = eq.getClass().getMethod(method, ItemStack.class);
            m.invoke(eq, (item == null || item.getType() == Material.AIR) ? null : item.clone());
            return true;
        }
        catch (final Throwable ignored) {
            return false;
        }
    }

    private boolean callIfPresentB(final Object target, final String name, final Class<?> param, Object arg) {
        try {
            final Method m = target.getClass().getMethod(name, param);
            if (arg instanceof final ItemStack is) {
                if (is.getType() == Material.AIR) {
                    arg = null;
                }
            }
            m.invoke(target, arg);
            return true;
        }
        catch (final Throwable ignored) {
            return false;
        }
    }

    private ItemStack getArmor(final ItemStack[] armor, final EquipmentSlot slot) {
        if (armor == null) {
            return null;
        }
        return switch (slot) {
            case FEET -> (armor.length > 0) ? armor[0] : null;
            case LEGS -> (armor.length > 1) ? armor[1] : null;
            case CHEST -> (armor.length > 2) ? armor[2] : null;
            case HEAD -> (armor.length > 3) ? armor[3] : null;
            default -> null;
        };
    }

    private static ItemStack[] cloneArray(final ItemStack[] src) {
        if (src == null) {
            return new ItemStack[0];
        }
        final ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; ++i) {
            out[i] = safeClone(src[i]);
        }
        return out;
    }

    private static ItemStack safeClone(final ItemStack is) {
        return (is == null || is.getType() == Material.AIR) ? null : is.clone();
    }

    private static class ArmorStats {
        int armorPoints;
        double toughness;
    }

    private static class DummyData {
        final UUID owner;
        final String ownerName;
        final Npc npc;
        final Location location;
        double health;
        double maxHealth;
        final ItemStack[] storage;
        final ItemStack[] armor;
        final ItemStack offhand;
        final ItemStack mainHand;
        final int armorPoints;
        final double toughness;
        long expiresAt;
        boolean dropped;

        DummyData(final UUID owner, final String ownerName, final Npc npc, final Location location, final double health, final double maxHealth, final ItemStack[] storage, final ItemStack[] armor, final ItemStack offhand, final ItemStack mainHand, final int armorPoints, final double toughness) {
            this.dropped = false;
            this.owner = owner;
            this.ownerName = ownerName;
            this.npc = npc;
            this.location = location;
            this.health = health;
            this.maxHealth = maxHealth;
            this.storage = storage;
            this.armor = armor;
            this.offhand = offhand;
            this.mainHand = mainHand;
            this.armorPoints = armorPoints;
            this.toughness = toughness;
        }
    }
}