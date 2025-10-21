package de.scholle.dummy;

import de.scholle.dummy.listeners.DummyListener;
import de.scholle.dummy.listeners.DummyJoinListener;
import de.scholle.dummy.manager.DummyManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Dummy extends JavaPlugin {
    private DummyManager dummyManager;

    public void onEnable() {
        // Standard-Config erstellen
        saveDefaultConfig();

        // Config-Werte setzen falls nicht vorhanden
        getConfig().addDefault("general.dummy-duration", 30);
        getConfig().addDefault("general.drop-items", true);
        getConfig().addDefault("general.allow-projectiles", true);
        getConfig().addDefault("appearance.use-player-skin", true);
        getConfig().addDefault("appearance.show-equipment", true);
        getConfig().addDefault("npc-behavior.look-at-players", true);
        getConfig().addDefault("npc-behavior.can-move", false);
        getConfig().addDefault("npc-behavior.can-attack", false);
        getConfig().addDefault("npc-behavior.imitate-player", false);
        getConfig().addDefault("debug.enabled", false);
        getConfig().options().copyDefaults(true);
        saveConfig();

        this.dummyManager = new DummyManager(this);

        getServer().getPluginManager().registerEvents(new DummyListener(this), this);
        getServer().getPluginManager().registerEvents(new DummyJoinListener(this), this);

        dummyManager.start();

        getLogger().info("[LogoutDummy] Plugin aktiviert.");
        if (getConfig().getBoolean("debug.enabled")) {
            getLogger().info("[LogoutDummy] Debug-Modus aktiviert.");
        }
    }

    public void onDisable() {
        if (dummyManager != null) {
            dummyManager.stop();
        }
        getLogger().info("[LogoutDummy] Plugin deaktiviert.");
    }

    public DummyManager dummies() {
        return dummyManager;
    }

    // Hilfsmethoden für Config-Zugriff
    public int getDummyDuration() {
        return getConfig().getInt("general.dummy-duration", 15);
    }

    public boolean shouldDropItems() {
        return getConfig().getBoolean("general.drop-items", true);
    }

    public boolean allowProjectiles() {
        return getConfig().getBoolean("general.allow-projectiles", true);
    }

    public boolean usePlayerSkin() {
        return getConfig().getBoolean("appearance.use-player-skin", true);
    }

    public boolean showEquipment() {
        return getConfig().getBoolean("appearance.show-equipment", true);
    }

    public String getKillBroadcast() {
        return getConfig().getString("messages.kill-broadcast",
                "§f[§6§lDummy§f] §c{player} §fwurde von §a{killer} §fals Offline-Dummy besiegt.");
    }

    public boolean isDebugEnabled() {
        return getConfig().getBoolean("debug.enabled", false);
    }
}