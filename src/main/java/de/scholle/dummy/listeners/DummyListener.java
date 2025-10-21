package de.scholle.dummy.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.NamespacedKey;
import de.scholle.dummy.Dummy;
import org.bukkit.event.Listener;

public class DummyListener implements Listener {
    private final Dummy plugin;
    private final NamespacedKey HITBOX_KEY;

    public DummyListener(final Dummy plugin) {
        this.plugin = plugin;
        this.HITBOX_KEY = new NamespacedKey((Plugin) plugin, "offline_dummy_hitbox");
    }

    @EventHandler
    public void onDamage(final EntityDamageByEntityEvent e) {
        final Entity entity = e.getEntity();
        if (!(entity instanceof ArmorStand)) {
            return;
        }
        final ArmorStand as = (ArmorStand) entity;
        final Byte mark = as.getPersistentDataContainer().get(this.HITBOX_KEY, PersistentDataType.BYTE);
        if (mark == null || mark != 1) {
            return;
        }
        Player damager = null;
        final Entity damager2 = e.getDamager();
        if (damager2 instanceof final Player player2) {
            final Player p = damager = player2;
        } else {
            final Entity damager3 = e.getDamager();
            if (damager3 instanceof final Projectile proj) {
                final ProjectileSource shooter = proj.getShooter();
                if (shooter instanceof final Player player) {
                    final Player p2 = damager = player;
                }
            }
        }
        if (damager == null) {
            return;
        }
        e.setCancelled(true);
        try {
            this.plugin.dummies().onDummyHit(as, damager);
        } catch (final Throwable t) {}
    }
}