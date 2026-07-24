package net.afterbloom.gameCoordinator;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class StaffEvents implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        VanishManager.handleJoin(event.getPlayer());
    }
}
