package de.scholle.dummy.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import de.scholle.dummy.Dummy;
import org.bukkit.event.Listener;

public class DummyJoinListener implements Listener {
    private final Dummy plugin;

    public DummyJoinListener(final Dummy plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent e) {
        this.plugin.dummies().handleJoin(e.getPlayer());
    }
}