package me.mklv.handshaker.paper.utils;

import me.mklv.handshaker.paper.HandShakerPlugin;
import me.mklv.handshaker.paper.gui.GuiSession;
import me.mklv.handshaker.paper.gui.HandShakerGui;
import me.mklv.handshaker.paper.utils.PluginProtocolHandler;
import me.mklv.handshaker.common.utils.ClientInfo;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class HandShakerListener implements Listener {
    private final HandShakerPlugin plugin;
    private final Map<UUID, ClientInfo> clients;
    private final HandShakerGui gui;

    public HandShakerListener(HandShakerPlugin plugin, Map<UUID, ClientInfo> clients, HandShakerGui gui) {
        this.plugin = plugin;
        this.clients = clients;
        this.gui = gui;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        clients.put(playerId, new ClientInfo(Collections.emptySet(), false, false, null, null, null));
        plugin.clearNonceHistory(playerId);
        
        // Record join time for debug timing
        plugin.recordPlayerJoin(playerId);

        int timeoutSeconds = plugin.getConfigManager().getHandshakeTimeoutSeconds();
        long delayTicks = Math.max(1, timeoutSeconds) * 20L;
        // Schedule check with configurable delay to allow plugin channel messages to arrive
        plugin.schedulePlayerCheck(player, delayTicks);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clients.remove(uuid);
        plugin.clearNonceHistory(uuid);
        plugin.removeJoinTimestamp(uuid);
        gui.closeSession(uuid);
    }

    // ===== GUI events =====

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder(false) instanceof HandShakerGui.Holder)) return;
        event.setCancelled(true);
        gui.handleClick(event);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder(false) instanceof HandShakerGui.Holder)) return;
        UUID uuid = player.getUniqueId();
        GuiSession session = gui.getSession(uuid);
        // Keep session alive if a chat-capture callback is pending (close was programmatic)
        if (session != null && !session.isWaitingForChat()) {
            gui.closeSession(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        GuiSession session = gui.getSession(player.getUniqueId());
        if (session == null || !session.isWaitingForChat()) return;

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.setCancelled(true);
        gui.handleChatInput(player, text);
    }
}
