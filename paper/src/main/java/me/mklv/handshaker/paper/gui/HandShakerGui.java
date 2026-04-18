package me.mklv.handshaker.paper.gui;

import me.mklv.handshaker.common.commands.CommandRuntimeOperations;
import me.mklv.handshaker.common.commands.ConfigCommandOperations;
import me.mklv.handshaker.common.commands.DiagnosticCommand;
import me.mklv.handshaker.common.commands.IgnoreCommandOperations;
import me.mklv.handshaker.common.commands.ModRuleCommandOperations;
import me.mklv.handshaker.common.configs.ConfigTypes.ConfigState;
import me.mklv.handshaker.common.configs.ConfigTypes.ModEntry;
import me.mklv.handshaker.common.database.PlayerHistoryDatabase;
import me.mklv.handshaker.common.utils.ClientInfo;
import me.mklv.handshaker.common.utils.LoggerAdapter;
import me.mklv.handshaker.paper.ConfigManager;
import me.mklv.handshaker.paper.HandShakerPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HandShakerGui {

    // ===== Inner inventory holder =====

    public static class Holder implements InventoryHolder {
        private final UUID playerUuid;
        private Inventory inventory;

        Holder(UUID uuid) { this.playerUuid = uuid; }
        public UUID getPlayerUuid() { return playerUuid; }
        @Override public Inventory getInventory() { return inventory; }
        void setInventory(Inventory inv) { this.inventory = inv; }
    }

    // ===== Layout constants =====

    private static final int SIZE          = 54;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END   = 44;   // inclusive
    private static final int PAGE_SIZE     = 36;

    // Footer slots (row 5 = slots 45-53)
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_ACTION = 48;
    private static final int SLOT_NEXT   = 52;
    private static final int SLOT_CLOSE  = 53;

    // Mode-selector overlay slots (centred in content area)
    private static final int MODE_SLOT_LABEL      = 13;
    private static final int MODE_SLOT_REQUIRED   = 19;
    private static final int MODE_SLOT_BLACKLISTED = 22;
    private static final int MODE_SLOT_ALLOWED    = 25;
    private static final int MODE_SLOT_REMOVE     = 31;
    private static final int MODE_SLOT_CANCEL     = SLOT_PREV;

    // Title colour
    private static final TextColor GOLD = TextColor.color(0xC09045);

    // PDC key constants
    private static final NamespacedKey KEY_MOD_TOKEN   = new NamespacedKey("handshaker", "modtoken");
    private static final NamespacedKey KEY_PLAYER_UUID = new NamespacedKey("handshaker", "playeruuid");
    private static final NamespacedKey KEY_MODE_VALUE  = new NamespacedKey("handshaker", "modevalue");
    private static final NamespacedKey KEY_SETTING    = new NamespacedKey("handshaker", "settingparam");

    // ===== State =====

    private final HandShakerPlugin plugin;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();

    // ===== Constructor =====

    public HandShakerGui(HandShakerPlugin plugin) {
        this.plugin = plugin;
    }

    // ===== Public API =====

    /** Open GUI for a player, creating a fresh session. */
    public void openGui(Player player) {
        GuiSession session = new GuiSession();
        sessions.put(player.getUniqueId(), session);
        buildAndOpen(player, session);
    }

    /** Reopen with the existing session (used after chat input). */
    public void reopenGui(Player player) {
        GuiSession session = sessions.get(player.getUniqueId());
        if (session == null) { openGui(player); return; }
        buildAndOpen(player, session);
    }

    /** Remove and discard the session. */
    public void closeSession(UUID uuid) {
        sessions.remove(uuid);
    }

    /** Returns the session or null – used by the listener. */
    public GuiSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Called from AsyncChatEvent to consume a pending chat input.
     * Returns true if the message was consumed.
     */
    public boolean handleChatInput(Player player, String rawMessage) {
        GuiSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isWaitingForChat()) return false;

        Consumer<String> callback = session.getPendingChatCallback();
        session.clearChatCapture();

        // Execute callback on the main region scheduler thread
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> callback.accept(rawMessage));
        return true;
    }

    /** Called from InventoryClickEvent. Event must already be cancelled by listener. */
    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Only process clicks inside the GUI inventory (top half)
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;

        GuiSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        int slot = event.getSlot();

        // Sub-state: mode-selector overlay is active
        if (session.getSubState() != GuiSession.SubState.NONE) {
            handleModeSelect(player, session, slot);
            return;
        }

        // Tab navigation row (slots 0-5 = tab buttons, 6-8 = fillers)
        if (slot <= 5) {
            GuiSession.Tab tab = GuiSession.Tab.fromSlot(slot);
            if (tab != null && tab != session.getCurrentTab()) {
                session.setCurrentTab(tab);
                refreshGui(player, session);
            }
            return;
        }

        // Footer row (slots 45-53)
        if (slot >= 45) {
            handleFooterClick(player, session, slot);
            return;
        }

        // Content area (slots 9-44)
        if (slot >= CONTENT_START && slot <= CONTENT_END) {
            handleContentClick(player, session, slot);
        }
    }

    // ===== Private: GUI building =====

    private void buildAndOpen(Player player, GuiSession session) {
        Holder holder = new Holder(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
            Component.text("✦ HandShaker").color(GOLD).decoration(TextDecoration.BOLD, true));
        holder.setInventory(inv);
        populateInventory(inv, player, session);
        player.openInventory(inv);
    }

    private void refreshGui(Player player, GuiSession session) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder(false) instanceof Holder)) return;
        top.clear();
        populateInventory(top, player, session);
    }

    private void populateInventory(Inventory inv, Player player, GuiSession session) {
        renderTabBar(inv, session);

        if (session.getSubState() != GuiSession.SubState.NONE) {
            renderModeSelector(inv, session);
            // Footer: only back button – rendered inside renderModeSelector
            return;
        }

        switch (session.getCurrentTab()) {
            case SETTINGS    -> renderSettings(inv, session);
            case MOD_RULES   -> renderModRules(inv, session);
            case ALL_MODS    -> renderAllMods(inv, player, session);
            case IGNORE_LIST -> renderIgnoreList(inv, session);
            case PLAYER_INFO -> renderPlayerInfo(inv, session);
            case DIAGNOSTIC  -> renderDiagnostic(inv, session);
        }

        renderFooter(inv, session);
    }

    // ===== Private: Tab bar =====

    private void renderTabBar(Inventory inv, GuiSession session) {
        for (GuiSession.Tab tab : GuiSession.Tab.values()) {
            inv.setItem(tab.ordinal(), tabItem(tab, tab == session.getCurrentTab()));
        }
        // Fill remainder of row 0 (slots 6-8) with glass fillers
        for (int i = GuiSession.Tab.values().length; i <= 8; i++) {
            inv.setItem(i, filler());
        }
    }

    private ItemStack tabItem(GuiSession.Tab tab, boolean active) {
        record Def(Material mat, String label, String desc) {}
        Def def = switch (tab) {
            case SETTINGS    -> new Def(Material.COMPARATOR,     "Settings",    "Server configuration");
            case MOD_RULES   -> new Def(Material.CRAFTING_TABLE, "Mod Rules",   "Configured mod rules");
            case ALL_MODS    -> new Def(Material.BOOKSHELF,      "All Mods",    "Mods seen in database");
            case IGNORE_LIST -> new Def(Material.BARRIER,        "Ignore List", "Ignored mods");
            case PLAYER_INFO -> new Def(Material.PLAYER_HEAD,    "Players",     "Player mod history");
            case DIAGNOSTIC  -> new Def(Material.PAPER,          "Diagnostic",  "Server diagnostic info");
        };
        ItemStack item = new ItemStack(def.mat());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(def.label())
            .color(active ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, active));
        meta.lore(List.of(
            Component.text(def.desc()).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    // ===== Private: Footer =====

    private void renderFooter(Inventory inv, GuiSession session) {
        for (int i = 45; i <= 53; i++) inv.setItem(i, filler());

        int totalItems = getTotalItemCount(session);
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / PAGE_SIZE));
        int page = session.getCurrentPage();

        if (page > 0) {
            inv.setItem(SLOT_PREV, namedItem(Material.ARROW,
                Component.text("← Previous").color(NamedTextColor.GREEN)));
        }
        if (totalPages > 1) {
            inv.setItem(49, namedItem(Material.BOOK,
                Component.text("Page " + (page + 1) + " / " + totalPages).color(NamedTextColor.WHITE)));
        }
        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT, namedItem(Material.ARROW,
                Component.text("Next →").color(NamedTextColor.GREEN)));
        }

        ItemStack actionItem = getTabActionItem(session);
        if (actionItem != null) inv.setItem(SLOT_ACTION, actionItem);

        inv.setItem(SLOT_CLOSE, namedItem(Material.BARRIER,
            Component.text("✕ Close").color(NamedTextColor.RED)));
    }

    private ItemStack getTabActionItem(GuiSession session) {
        if (session.getCurrentTab() == GuiSession.Tab.PLAYER_INFO && session.hasPlayerView()) {
            return actionItem(Material.ARROW, "← Back to Players", "Return to player list");
        }
        return switch (session.getCurrentTab()) {
            case MOD_RULES   -> actionItem(Material.WRITABLE_BOOK, "➕ Add Mod",           "Type a mod ID to add a new rule");
            case ALL_MODS    -> actionItem(Material.NETHER_STAR,   "➕ Add All My Mods",   "Add all your current mods to a rule");
            case IGNORE_LIST -> actionItem(Material.WRITABLE_BOOK, "➕ Add to Ignore",     "Type a mod ID to ignore it");
            case DIAGNOSTIC  -> actionItem(Material.PAPER,         "📤 Export Report",     "Save diagnostic to exports/");
            default          -> null;
        };
    }

    // ===== Private: Render tabs =====

    // ---- Settings ----
    private void renderSettings(Inventory inv, GuiSession session) {
        ConfigManager config = plugin.getConfigManager();
        List<ItemStack> items = new ArrayList<>();

        items.add(settingToggle(Material.COMMAND_BLOCK, "Force HandShaker Mod",
            "handshaker_enforcement", config.isForceHandshakerMod()));
        items.add(settingToggle(Material.IRON_BARS, "Whitelist Enforcement",
            "whitelist_enforcement", config.isWhitelist()));
        items.add(settingToggle(Material.SLIME_BALL, "Allow Bedrock Players",
            "bedrock_policy", config.isAllowBedrockPlayers()));
        items.add(settingToggle(Material.BOOKSHELF, "Player Database",
            "database", config.isPlayerdbEnabled()));

        items.add(settingChat(Material.CLOCK, "Timeout Seconds",
            String.valueOf(config.getHandshakeTimeoutSeconds()),
            "timeout_seconds", "Enter new value (seconds):"));
        items.add(settingChat(Material.REDSTONE, "Default Action",
            config.getDefaultAction() != null ? config.getDefaultAction() : "none",
            "default_action", "Enter action (kick/ban/none):"));

        String hashDisplay = config.getRequiredModpackHashes().isEmpty()
            ? "OFF" : config.getRequiredModpackHashes().size() + " hash(es) configured";
        items.add(settingChat(Material.NETHER_STAR, "Modpack Hashes",
            hashDisplay,
            "modpack_hashes", "Enter hash / 'current' (= your mods) / 'off' (= disable):"));

        items.add(settingToggle(Material.CRAFTING_TABLE, "Hash Mods",
            "hash_mods", config.isHashMods()));
        items.add(settingToggle(Material.BOOK, "Mod Versioning",
            "mod_versioning", config.isModVersioning()));
        items.add(settingToggle(Material.COMPARATOR, "Runtime Cache",
            "runtime_cache", config.isRuntimeCache()));

        items.add(settingModeToggle(Material.LIME_STAINED_GLASS_PANE, "Required Mods List",
            "mods_required", config.areModsRequiredEnabled()));
        items.add(settingModeToggle(Material.RED_STAINED_GLASS_PANE, "Blacklisted Mods List",
            "mods_blacklisted", config.areModsBlacklistedEnabled()));
        items.add(settingModeToggle(Material.YELLOW_STAINED_GLASS_PANE, "Whitelisted Mods List",
            "mods_whitelisted", config.areModsWhitelistedEnabled()));

        items.add(settingToggle(Material.ENDER_EYE, "Modern Compat",
            "compat_modern", config.isModernCompatibilityEnabled()));
        items.add(settingToggle(Material.ENDER_PEARL, "Hybrid Compat",
            "compat_hybrid", config.isHybridCompatibilityEnabled()));
        items.add(settingToggle(Material.CLOCK, "Legacy Compat",
            "compat_legacy", config.isLegacyCompatibilityEnabled()));
        items.add(settingToggle(Material.SPIDER_EYE, "Unsigned Compat",
            "compat_unsigned", config.isUnsignedCompatibilityEnabled()));

        placePagedItems(inv, items, session.getCurrentPage());
    }

    /** Toggle item backed by ConfigCommandOperations.applyConfigValue. */
    private ItemStack settingToggle(Material mat, String label, String param, boolean enabled) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label)
            .color(enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Status: " + (enabled ? "ON" : "OFF"))
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Click to toggle").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(KEY_SETTING, PersistentDataType.STRING, param);
        item.setItemMeta(meta);
        return item;
    }

    /** Toggle item backed by ConfigCommandOperations.applyModeToggle. */
    private ItemStack settingModeToggle(Material mat, String label, String listName, boolean enabled) {
        ItemStack item = settingToggle(mat, label, listName, enabled);
        ItemMeta meta = item.getItemMeta();
        // Override PDC to mark as "mode" type by prefixing with "mode:"
        meta.getPersistentDataContainer().set(KEY_SETTING, PersistentDataType.STRING, "mode:" + listName);
        item.setItemMeta(meta);
        return item;
    }

    /** Chat-input item with display value. */
    private ItemStack settingChat(Material mat, String label, String currentValue, String param, String chatPrompt) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label).color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Value: " + currentValue).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Click to change").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        // Store "chat:<param>|<prompt>" to differentiate later
        meta.getPersistentDataContainer().set(KEY_SETTING, PersistentDataType.STRING,
            "chat:" + param + "|" + chatPrompt);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Mod Rules ----
    private void renderModRules(Inventory inv, GuiSession session) {
        ConfigManager config = plugin.getConfigManager();
        List<Map.Entry<String, ConfigState.ModConfig>> mods = config.getModConfigMap().entrySet().stream()
            .filter(e -> !config.isIgnored(e.getKey()))
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .collect(Collectors.toList());

        if (mods.isEmpty()) {
            inv.setItem(CONTENT_START, emptySlot("No Configured Mods", "Use ➕ Add Mod in the footer"));
            return;
        }
        placePagedItems(inv, mods.stream().map(e -> configuredModItem(e.getKey(), e.getValue())).collect(Collectors.toList()),
            session.getCurrentPage());
    }

    private ItemStack configuredModItem(String token, ConfigState.ModConfig modConfig) {
        ModEntry entry = ModEntry.parse(token);
        String display = entry != null && entry.displayName() != null ? entry.displayName()
            : entry != null ? entry.modId() : token;
        String mode   = modConfig != null ? modConfig.getMode() : "unknown";
        String action = modConfig != null && modConfig.getActionName() != null ? modConfig.getActionName() : "";

        Material mat = switch (mode) {
            case "required"    -> Material.LIME_WOOL;
            case "blacklisted" -> Material.RED_WOOL;
            case "allowed"     -> Material.YELLOW_WOOL;
            default            -> Material.GRAY_WOOL;
        };
        NamedTextColor color = switch (mode) {
            case "required"    -> NamedTextColor.GREEN;
            case "blacklisted" -> NamedTextColor.RED;
            case "allowed"     -> NamedTextColor.YELLOW;
            default            -> NamedTextColor.GRAY;
        };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(display).color(color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Mode: " + mode).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        if (!action.isEmpty()) {
            lore.add(Component.text("Action: " + action).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to change / remove").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KEY_MOD_TOKEN, PersistentDataType.STRING, token);
        item.setItemMeta(meta);
        return item;
    }

    // ---- All Mods ----
    private void renderAllMods(Inventory inv, Player player, GuiSession session) {
        PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
        if (db == null || !db.isEnabled()) {
            inv.setItem(CONTENT_START, emptySlot("Database Disabled", "Enable player database in config"));
            return;
        }
        ConfigManager config = plugin.getConfigManager();
        List<Map.Entry<String, Integer>> sorted = db.getModPopularity().entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .collect(Collectors.toList());

        if (sorted.isEmpty()) {
            inv.setItem(CONTENT_START, emptySlot("No Mods in Database", "Mods appear after players join with HandShaker"));
            return;
        }
        placePagedItems(inv, sorted.stream().map(e -> dbModItem(e.getKey(), e.getValue(), config)).collect(Collectors.toList()),
            session.getCurrentPage());
    }

    private ItemStack dbModItem(String token, int count, ConfigManager config) {
        ModEntry entry = ModEntry.parse(token);
        String display = entry != null && entry.displayName() != null ? entry.displayName()
            : entry != null ? entry.modId() : token;
        ConfigState.ModConfig modCfg = config.getModConfig(token);

        Material mat = modCfg == null ? Material.GRAY_WOOL
            : switch (modCfg.getMode()) {
                case "required"    -> Material.LIME_WOOL;
                case "blacklisted" -> Material.RED_WOOL;
                case "allowed"     -> Material.YELLOW_WOOL;
                default            -> Material.GRAY_WOOL;
            };
        NamedTextColor color = modCfg == null ? NamedTextColor.GRAY
            : switch (modCfg.getMode()) {
                case "required"    -> NamedTextColor.GREEN;
                case "blacklisted" -> NamedTextColor.RED;
                case "allowed"     -> NamedTextColor.YELLOW;
                default            -> NamedTextColor.GRAY;
            };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(display).color(color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Seen by " + count + " player(s)").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        if (modCfg != null) {
            lore.add(Component.text("Current rule: " + modCfg.getMode()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to add / change rule").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KEY_MOD_TOKEN, PersistentDataType.STRING, token);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Ignore List ----
    private void renderIgnoreList(Inventory inv, GuiSession session) {
        ConfigManager config = plugin.getConfigManager();
        List<String> ignored = new ArrayList<>(config.getIgnoredMods());
        Collections.sort(ignored);

        if (ignored.isEmpty()) {
            inv.setItem(CONTENT_START, emptySlot("No Ignored Mods", "Use ➕ Add to Ignore in the footer"));
            return;
        }
        placePagedItems(inv, ignored.stream().map(this::ignoredModItem).collect(Collectors.toList()),
            session.getCurrentPage());
    }

    private ItemStack ignoredModItem(String modId) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(modId).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Click to remove from ignore list").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(KEY_MOD_TOKEN, PersistentDataType.STRING, modId);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Player Info ----
    private void renderPlayerInfo(Inventory inv, GuiSession session) {
        if (session.hasPlayerView()) {
            renderPlayerHistoryView(inv, session);
        } else {
            renderPlayerSkulls(inv, session);
        }
    }

    private void renderPlayerSkulls(Inventory inv, GuiSession session) {
        PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
        if (db == null || !db.isEnabled()) {
            inv.setItem(CONTENT_START, emptySlot("Database Disabled", "Enable player database in config"));
            return;
        }
        List<PlayerHistoryDatabase.PlayerSummaryInfo> players = db.getPlayersWithActiveMods();
        if (players.isEmpty()) {
            inv.setItem(CONTENT_START, emptySlot("No Players in Database", "Players appear after joining the server"));
            return;
        }
        placePagedItems(inv, players.stream().map(this::playerSkullItem).collect(Collectors.toList()),
            session.getCurrentPage());
    }

    private void renderPlayerHistoryView(Inventory inv, GuiSession session) {
        PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
        UUID uuid = session.getViewingPlayerUuid();
        if (db == null || !db.isEnabled() || uuid == null) {
            inv.setItem(CONTENT_START, emptySlot("Database Disabled", "Enable player database in config"));
            return;
        }
        List<PlayerHistoryDatabase.ModHistoryEntry> history = db.getPlayerHistory(uuid);
        if (history.isEmpty()) {
            inv.setItem(CONTENT_START, emptySlot("No History Found", "No mod history for: " + session.getViewingPlayerName()));
            return;
        }
        placePagedItems(inv, history.stream().map(this::historyEntryItem).collect(Collectors.toList()),
            session.getCurrentPage());
    }

    private ItemStack historyEntryItem(PlayerHistoryDatabase.ModHistoryEntry entry) {
        ItemStack item = new ItemStack(entry.isActive() ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(entry.modName())
            .color(entry.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Status: " + (entry.isActive() ? "ACTIVE" : "REMOVED"))
                .color(entry.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Added: " + entry.getAddedDateFormatted())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack playerSkullItem(PlayerHistoryDatabase.PlayerSummaryInfo info) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(info.uuid()));
        meta.displayName(Component.text(info.currentName()).color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (info.modCount() > 0) {
            lore.add(Component.text("Active mods tracked: " + info.modCount())
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to view mod history")
            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KEY_PLAYER_UUID, PersistentDataType.STRING, info.uuid().toString());
        skull.setItemMeta(meta);
        return skull;
    }

    // ---- Diagnostic ----
    private void renderDiagnostic(Inventory inv, GuiSession session) {
        ConfigManager config = plugin.getConfigManager();
        PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
        List<String> lines = DiagnosticCommand.buildDisplayLines(
            config, db,
            plugin.getPluginMeta().getVersion(),
            plugin.getServer().getName() + " (" + plugin.getServer().getVersion() + ")",
            plugin.getServer().getMinecraftVersion());

        List<ItemStack> items = new ArrayList<>();
        for (String line : lines) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta meta = paper.getItemMeta();
            if (line.contains(": ")) {
                int idx = line.indexOf(": ");
                meta.displayName(Component.text(line.substring(0, idx) + ":")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text(line.substring(idx + 2))
                    .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
            } else {
                meta.displayName(Component.text(line).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            }
            paper.setItemMeta(meta);
            items.add(paper);
        }
        if (!items.isEmpty()) {
            placePagedItems(inv, items, session.getCurrentPage());
        } else {
            inv.setItem(CONTENT_START, emptySlot("No Diagnostic Data", "Run /handshaker info diagnostic for details"));
        }
    }

    // ---- Mode selector overlay ----
    private void renderModeSelector(Inventory inv, GuiSession session) {
        // Clear content area
        for (int i = CONTENT_START; i <= CONTENT_END; i++) inv.setItem(i, filler());

        String token = session.getPendingModToken();
        ModEntry entry = token != null ? ModEntry.parse(token) : null;
        String displayLabel = entry != null && entry.modId() != null ? entry.modId()
            : (token != null ? token : "?");

        String context = switch (session.getSubState()) {
            case SELECT_MODE_FOR_CHANGE -> "Change mode: " + displayLabel;
            case SELECT_MODE_FOR_ADD    -> "Add mod: " + displayLabel;
            case SELECT_MODE_FOR_ALL    -> "Mode for all your mods";
            default                     -> "Select mode: " + displayLabel;
        };

        // Label item
        ItemStack label = new ItemStack(Material.NAME_TAG);
        ItemMeta lm = label.getItemMeta();
        lm.displayName(Component.text(context).color(NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        label.setItemMeta(lm);
        inv.setItem(MODE_SLOT_LABEL, label);

        inv.setItem(MODE_SLOT_REQUIRED,    modeButton("required",    Material.LIME_WOOL,   NamedTextColor.GREEN,  "Player MUST have this mod"));
        inv.setItem(MODE_SLOT_BLACKLISTED, modeButton("blacklisted", Material.RED_WOOL,    NamedTextColor.RED,    "Player must NOT have this mod"));
        inv.setItem(MODE_SLOT_ALLOWED,     modeButton("allowed",     Material.YELLOW_WOOL, NamedTextColor.YELLOW, "Mod is explicitly allowed"));

        if (session.getSubState() == GuiSession.SubState.SELECT_MODE_FOR_CHANGE) {
            inv.setItem(MODE_SLOT_REMOVE, modeButton("remove", Material.GRAY_WOOL, NamedTextColor.GRAY, "Remove this rule entirely"));
        }

        // Footer: only Cancel
        for (int i = 45; i <= 53; i++) inv.setItem(i, filler());
        inv.setItem(MODE_SLOT_CANCEL, namedItem(Material.ARROW,
            Component.text("← Cancel").color(NamedTextColor.RED)));
    }

    private ItemStack modeButton(String mode, Material mat, NamedTextColor color, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        String label = Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
        meta.displayName(Component.text(label).color(color)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(Component.text(desc).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(KEY_MODE_VALUE, PersistentDataType.STRING, mode);
        item.setItemMeta(meta);
        return item;
    }

    // ===== Private: Click handlers =====

    private void handleFooterClick(Player player, GuiSession session, int slot) {
        if (slot == SLOT_CLOSE) {
            sessions.remove(player.getUniqueId());
            player.closeInventory();
            return;
        }

        int totalItems = getTotalItemCount(session);
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / PAGE_SIZE));
        int page = session.getCurrentPage();

        if (slot == SLOT_PREV && page > 0) {
            session.setCurrentPage(page - 1);
            refreshGui(player, session);
            return;
        }
        if (slot == SLOT_NEXT && page < totalPages - 1) {
            session.setCurrentPage(page + 1);
            refreshGui(player, session);
            return;
        }
        if (slot == SLOT_ACTION) {
            if (session.getCurrentTab() == GuiSession.Tab.PLAYER_INFO && session.hasPlayerView()) {
                session.clearPlayerView();
                refreshGui(player, session);
            } else {
                handleTabAction(player, session);
            }
        }
    }

    private void handleTabAction(Player player, GuiSession session) {
        switch (session.getCurrentTab()) {
            case MOD_RULES ->
                startChatInput(player, session, "Type mod ID to add (or 'cancel'):", input -> {
                    if (!input.equalsIgnoreCase("cancel")) {
                        session.setPendingModToken(stripQuotes(input.trim()));
                        session.setSubState(GuiSession.SubState.SELECT_MODE_FOR_ADD);
                    }
                    reopenGui(player);
                });
            case ALL_MODS -> {
                session.setPendingModToken("*");
                session.setSubState(GuiSession.SubState.SELECT_MODE_FOR_ALL);
                refreshGui(player, session);
            }
            case IGNORE_LIST ->
                startChatInput(player, session, "Type mod ID to ignore (or 'cancel'):", input -> {
                    if (!input.equalsIgnoreCase("cancel")) {
                        performAddIgnore(player, input.trim());
                    }
                    reopenGui(player);
                });
            case DIAGNOSTIC ->
                performExportDiagnostic(player);
            default -> { /* nothing */ }
        }
    }

    private void handleContentClick(Player player, GuiSession session, int slot) {
        switch (session.getCurrentTab()) {
            case SETTINGS    -> handleSettingsClick(player, session, slot);
            case MOD_RULES   -> handleModRulesClick(player, session, slot);
            case ALL_MODS    -> handleAllModsClick(player, session, slot);
            case IGNORE_LIST -> handleIgnoreListClick(player, session, slot);
            case PLAYER_INFO -> handlePlayerInfoClick(player, session, slot);
            case DIAGNOSTIC  -> { /* items are display-only; export is via footer action */ }
        }
    }

    private void handleModeSelect(Player player, GuiSession session, int slot) {
        // Cancel / back button
        if (slot == MODE_SLOT_CANCEL) {
            session.clearSubState();
            refreshGui(player, session);
            return;
        }

        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack clicked = top.getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) return;
        String modeValue = clicked.getItemMeta().getPersistentDataContainer()
            .get(KEY_MODE_VALUE, PersistentDataType.STRING);
        if (modeValue == null) return;

        GuiSession.SubState subState  = session.getSubState();
        String             modToken  = session.getPendingModToken();
        session.clearSubState();

        switch (subState) {
            case SELECT_MODE_FOR_ADD -> {
                if (modToken != null) performAddMod(player, modToken, modeValue);
            }
            case SELECT_MODE_FOR_CHANGE -> {
                if (modToken != null) {
                    if ("remove".equals(modeValue)) performRemoveMod(player, modToken);
                    else                            performChangeMod(player, modToken, modeValue);
                }
            }
            case SELECT_MODE_FOR_ALL          -> performAddAllMods(player, modeValue);
            case SELECT_MODE_FOR_ADD_FROM_ALL -> {
                if (modToken != null) performAddMod(player, modToken, modeValue);
            }
            default -> { /* nothing */ }
        }

        refreshGui(player, session);
    }

    // ---- Settings content clicks ----
    private void handleSettingsClick(Player player, GuiSession session, int slot) {
        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack clicked = top.getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) return;

        String settingKey = clicked.getItemMeta().getPersistentDataContainer()
            .get(KEY_SETTING, PersistentDataType.STRING);
        if (settingKey == null) return;

        ConfigManager config = plugin.getConfigManager();

        if (settingKey.startsWith("mode:")) {
            // Mode list toggle (applyModeToggle)
            String listName = settingKey.substring(5);
            boolean current = switch (listName) {
                case "mods_required"    -> config.areModsRequiredEnabled();
                case "mods_blacklisted" -> config.areModsBlacklistedEnabled();
                case "mods_whitelisted" -> config.areModsWhitelistedEnabled();
                default -> false;
            };
            performModeToggle(player, listName, current ? "disable" : "enable");

        } else if (settingKey.startsWith("chat:")) {
            // Chat input
            String rest = settingKey.substring(5); // "param|prompt"
            int sep = rest.indexOf('|');
            String param  = sep >= 0 ? rest.substring(0, sep) : rest;
            String prompt = sep >= 0 ? rest.substring(sep + 1) : "Enter value:";
            startChatInput(player, session, prompt + " (or 'cancel')", input -> {
                if (!input.equalsIgnoreCase("cancel")) {
                    performConfigSet(player, param, input.trim());
                }
                reopenGui(player);
            });

        } else {
            // Boolean toggle (applyConfigValue)
            boolean current = switch (settingKey) {
                case "handshaker_enforcement" -> config.isForceHandshakerMod();
                case "whitelist_enforcement"  -> config.isWhitelist();
                case "bedrock_policy"         -> config.isAllowBedrockPlayers();
                case "database"               -> config.isPlayerdbEnabled();
                case "hash_mods"              -> config.isHashMods();
                case "mod_versioning"         -> config.isModVersioning();
                case "runtime_cache"          -> config.isRuntimeCache();
                case "compat_modern"          -> config.isModernCompatibilityEnabled();
                case "compat_hybrid"          -> config.isHybridCompatibilityEnabled();
                case "compat_legacy"          -> config.isLegacyCompatibilityEnabled();
                case "compat_unsigned"        -> config.isUnsignedCompatibilityEnabled();
                default -> false;
            };
            performConfigToggle(player, settingKey, String.valueOf(!current));
        }
    }

    // ---- Mod Rules content clicks ----
    private void handleModRulesClick(Player player, GuiSession session, int slot) {
        String token = getModTokenFromSlot(player, slot);
        if (token == null) return;
        session.setPendingModToken(token);
        session.setSubState(GuiSession.SubState.SELECT_MODE_FOR_CHANGE);
        refreshGui(player, session);
    }

    // ---- All Mods content clicks ----
    private void handleAllModsClick(Player player, GuiSession session, int slot) {
        String token = getModTokenFromSlot(player, slot);
        if (token == null) return;
        session.setPendingModToken(token);
        session.setSubState(GuiSession.SubState.SELECT_MODE_FOR_ADD_FROM_ALL);
        refreshGui(player, session);
    }

    // ---- Ignore List content clicks ----
    private void handleIgnoreListClick(Player player, GuiSession session, int slot) {
        String modId = getModTokenFromSlot(player, slot);
        if (modId == null) return;
        performRemoveIgnore(player, modId);
        refreshGui(player, session);
    }

    // ---- Player Info content clicks ----
    private void handlePlayerInfoClick(Player player, GuiSession session, int slot) {
        if (session.hasPlayerView()) return; // history items are display-only

        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack clicked = top.getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) return;

        String uuidStr = clicked.getItemMeta().getPersistentDataContainer()
            .get(KEY_PLAYER_UUID, PersistentDataType.STRING);
        if (uuidStr == null) return;

        String playerName = clicked.getItemMeta().displayName() != null
            ? PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(clicked.getItemMeta().displayName()))
            : uuidStr;

        try {
            UUID targetUuid = UUID.fromString(uuidStr);
            session.setPlayerView(targetUuid, playerName);
            refreshGui(player, session);
        } catch (IllegalArgumentException e) {
            // invalid UUID stored in PDC
        }
    }

    // ===== Private: Operations =====

    private void performConfigToggle(Player player, String param, String newValue) {
        ConfigCommandOperations.MutationResult result = ConfigCommandOperations.applyConfigValue(
            plugin.getConfigManager(), param, newValue, getPlayerMods(player));
        applyMutation(player, result);
        GuiSession session = sessions.get(player.getUniqueId());
        if (session != null) refreshGui(player, session);
    }

    private void performConfigSet(Player player, String param, String value) {
        ConfigCommandOperations.MutationResult result = ConfigCommandOperations.applyConfigValue(
            plugin.getConfigManager(), param, value, getPlayerMods(player));
        applyMutation(player, result);
    }

    private void performModeToggle(Player player, String listName, String action) {
        ConfigCommandOperations.MutationResult result = ConfigCommandOperations.applyModeToggle(
            plugin.getConfigManager(),
            plugin.getDataFolder().toPath(),
            LoggerAdapter.fromLoaderLogger(plugin.getLogger()),
            listName, action);
        applyMutation(player, result);
        GuiSession session = sessions.get(player.getUniqueId());
        if (session != null) refreshGui(player, session);
    }

    private void applyMutation(Player player, ConfigCommandOperations.MutationResult result) {
        ConfigManager config = plugin.getConfigManager();
        if (!result.success()) {
            player.sendMessage(Component.text(result.message()).color(NamedTextColor.RED));
            return;
        }
        if (result.shouldSave())          config.save();
        if (result.shouldReloadConfig())  config.load();
        if (result.shouldRecheckPlayers()) plugin.checkAllPlayers();
        player.sendMessage(Component.text("✓ " + result.message()).color(NamedTextColor.GREEN));
    }

    private void performAddMod(Player player, String modToken, String mode) {
        ConfigManager config = plugin.getConfigManager();
        String defaultAction = config.getDefaultActionForMode(mode);
        ModRuleCommandOperations.upsertModRule(modToken, mode, defaultAction,
            config::getDefaultActionForMode, config::setModConfig,
            this::performRegisterFingerprint);
        config.save();
        plugin.checkAllPlayers();
        player.sendMessage(Component.text("✓ Added '" + modToken + "' as " + mode).color(NamedTextColor.GREEN));
    }

    private void performChangeMod(Player player, String modToken, String mode) {
        ConfigManager config = plugin.getConfigManager();
        String defaultAction = config.getDefaultActionForMode(mode);
        ModRuleCommandOperations.upsertModRule(modToken, mode, defaultAction,
            config::getDefaultActionForMode, config::setModConfig, null);
        config.save();
        plugin.checkAllPlayers();
        player.sendMessage(Component.text("✓ Changed '" + modToken + "' to " + mode).color(NamedTextColor.GREEN));
    }

    private void performRemoveMod(Player player, String modToken) {
        ConfigManager config = plugin.getConfigManager();
        boolean removed = ModRuleCommandOperations.removeModRule(modToken, config::removeModConfig);
        if (removed) {
            config.save();
            plugin.checkAllPlayers();
            player.sendMessage(Component.text("✓ Removed '" + modToken + "'").color(NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Mod not found: " + modToken).color(NamedTextColor.RED));
        }
    }

    private void performAddAllMods(Player player, String mode) {
        ConfigManager config = plugin.getConfigManager();
        ClientInfo info = plugin.getClients().get(player.getUniqueId());
        Set<String> mods = info != null ? info.mods() : null;
        if (mods == null || mods.isEmpty()) {
            player.sendMessage(Component.text("No mod list found for you").color(NamedTextColor.YELLOW));
            return;
        }
        String defaultAction = config.getDefaultActionForMode(mode);
        int added = ModRuleCommandOperations.upsertBulkModRules(mods, config::isIgnored,
            mode, defaultAction, config::getDefaultActionForMode, config::setModConfig,
            this::performRegisterFingerprint);
        config.save();
        plugin.checkAllPlayers();
        player.sendMessage(Component.text("✓ Added " + added + " mods as " + mode).color(NamedTextColor.GREEN));
    }

    private void performAddIgnore(Player player, String modId) {
        ConfigManager config = plugin.getConfigManager();
        boolean added = IgnoreCommandOperations.addIgnoredMod(modId, config::isIgnored, config::addIgnoredMod);
        config.save();
        player.sendMessage(added
            ? Component.text("✓ Added '" + modId + "' to ignore list").color(NamedTextColor.GREEN)
            : Component.text("'" + modId + "' already in ignore list").color(NamedTextColor.YELLOW));
    }

    private void performRemoveIgnore(Player player, String modId) {
        ConfigManager config = plugin.getConfigManager();
        boolean removed = IgnoreCommandOperations.removeIgnoredMod(modId, config::removeIgnoredMod);
        config.save();
        player.sendMessage(removed
            ? Component.text("✓ Removed '" + modId + "' from ignore list").color(NamedTextColor.GREEN)
            : Component.text("'" + modId + "' not in ignore list").color(NamedTextColor.YELLOW));
    }

    private void performExportDiagnostic(Player player) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isDiagnosticCommandEnabled()) {
            player.sendMessage(Component.text("Diagnostic command is disabled in config").color(NamedTextColor.RED));
            return;
        }
        PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
        Path exportDir = plugin.getDataFolder().toPath().resolve("exports");
        DiagnosticCommand.FileExportResult result = DiagnosticCommand.writeDiagnosticReport(
            config, db, exportDir, System.currentTimeMillis(),
            plugin.getPluginMeta().getVersion(),
            plugin.getServer().getName() + " (" + plugin.getServer().getVersion() + ")",
            plugin.getServer().getMinecraftVersion());
        player.sendMessage(result.success()
            ? Component.text("✓ Exported: " + result.output().getFileName()).color(NamedTextColor.GREEN)
            : Component.text("Export failed: " + result.errorMessage()).color(NamedTextColor.RED));
    }

    private void performRegisterFingerprint(String token) {
        ConfigManager config = plugin.getConfigManager();
        CommandRuntimeOperations.registerModFingerprint(
            token, plugin.getPlayerHistoryDb(),
            config.isHashMods(), config.isModVersioning(),
            plugin.getClients().values(),
            config.isAsyncDatabaseOperations(),
            cmd -> plugin.getServer().getAsyncScheduler().runNow(plugin, t -> cmd.run()));
    }

    // ===== Private: Chat input =====

    private void startChatInput(Player player, GuiSession session, String prompt, Consumer<String> callback) {
        // Set callback BEFORE closing inventory so InventoryCloseEvent sees it
        session.setChatCapture(callback, prompt);
        player.closeInventory();
        player.sendMessage(Component.text(prompt).color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("(type your answer in chat — type 'cancel' to abort)")
            .color(NamedTextColor.DARK_GRAY));
    }

    // ===== Private: Utilities =====

    private void placePagedItems(Inventory inv, List<ItemStack> items, int page) {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, items.size());
        int slot  = CONTENT_START;
        for (int i = start; i < end; i++) {
            inv.setItem(slot++, items.get(i));
        }
    }

    private int getTotalItemCount(GuiSession session) {
        ConfigManager config = plugin.getConfigManager();
        return switch (session.getCurrentTab()) {
            case SETTINGS    -> 17; // fixed number of setting entries
            case MOD_RULES   -> (int) config.getModConfigMap().entrySet().stream()
                                    .filter(e -> !config.isIgnored(e.getKey())).count();
            case ALL_MODS    -> {
                PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
                yield db != null && db.isEnabled() ? db.getModPopularity().size() : 0;
            }
            case IGNORE_LIST -> config.getIgnoredMods().size();
            case PLAYER_INFO -> {
                PlayerHistoryDatabase db = plugin.getPlayerHistoryDb();
                if (db == null || !db.isEnabled()) yield 0;
                if (session.hasPlayerView()) {
                    UUID uuid = session.getViewingPlayerUuid();
                    yield uuid != null ? db.getPlayerHistory(uuid).size() : 0;
                }
                yield db.getPlayersWithActiveMods().size();
            }
            case DIAGNOSTIC  ->
                DiagnosticCommand.buildDisplayLines(config, plugin.getPlayerHistoryDb(),
                    plugin.getPluginMeta().getVersion(),
                    plugin.getServer().getName() + " (" + plugin.getServer().getVersion() + ")",
                    plugin.getServer().getMinecraftVersion()).size();
        };
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
            && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
              || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String getModTokenFromSlot(Player player, int slot) {
        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack item = top.getItem(slot);
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
            .get(KEY_MOD_TOKEN, PersistentDataType.STRING);
    }

    private Set<String> getPlayerMods(Player player) {
        ClientInfo info = plugin.getClients().get(player.getUniqueId());
        return info != null ? info.mods() : null;
    }

    // ===== Item builder helpers =====

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack namedItem(Material mat, Component name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack actionItem(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(desc).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptySlot(String title, String hint) {
        ItemStack item = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(hint).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }
}
