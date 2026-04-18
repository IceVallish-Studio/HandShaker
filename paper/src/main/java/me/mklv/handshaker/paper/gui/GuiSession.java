package me.mklv.handshaker.paper.gui;

import java.util.UUID;
import java.util.function.Consumer;

public class GuiSession {

    public enum Tab {
        SETTINGS, MOD_RULES, ALL_MODS, IGNORE_LIST, PLAYER_INFO, DIAGNOSTIC;

        public static Tab fromSlot(int slot) {
            Tab[] tabs = values();
            return slot >= 0 && slot < tabs.length ? tabs[slot] : null;
        }
    }

    public enum SubState {
        NONE,
        SELECT_MODE_FOR_ADD,          // new mod typed in chat → choose mode
        SELECT_MODE_FOR_CHANGE,       // existing mod clicked in MOD_RULES → choose mode or remove
        SELECT_MODE_FOR_ALL,          // "Add All My Mods" action clicked → choose mode
        SELECT_MODE_FOR_ADD_FROM_ALL  // mod in ALL_MODS tab clicked → choose mode
    }

    private Tab currentTab = Tab.SETTINGS;
    private int currentPage = 0;
    private Consumer<String> pendingChatCallback;
    private String pendingChatPrompt;
    private String pendingModToken;
    private SubState subState = SubState.NONE;
    private UUID viewingPlayerUuid;
    private String viewingPlayerName;

    // ====== Tab ======
    public Tab getCurrentTab() { return currentTab; }
    public void setCurrentTab(Tab tab) { this.currentTab = tab; this.currentPage = 0; }

    // ====== Page ======
    public int getCurrentPage() { return currentPage; }
    public void setCurrentPage(int page) { this.currentPage = page; }

    // ====== Chat Capture ======
    public Consumer<String> getPendingChatCallback() { return pendingChatCallback; }
    public String getPendingChatPrompt() { return pendingChatPrompt; }
    public boolean isWaitingForChat() { return pendingChatCallback != null; }

    public void setChatCapture(Consumer<String> callback, String prompt) {
        this.pendingChatCallback = callback;
        this.pendingChatPrompt = prompt;
    }

    public void clearChatCapture() {
        this.pendingChatCallback = null;
        this.pendingChatPrompt = null;
    }

    // ====== SubState ======
    public SubState getSubState() { return subState; }
    public void setSubState(SubState state) { this.subState = state; }
    public void clearSubState() { this.subState = SubState.NONE; this.pendingModToken = null; }

    // ====== Player History View ======
    public boolean hasPlayerView() { return viewingPlayerUuid != null; }
    public UUID getViewingPlayerUuid() { return viewingPlayerUuid; }
    public String getViewingPlayerName() { return viewingPlayerName; }
    public void setPlayerView(UUID uuid, String name) {
        this.viewingPlayerUuid = uuid;
        this.viewingPlayerName = name;
        this.currentPage = 0;
    }
    public void clearPlayerView() {
        this.viewingPlayerUuid = null;
        this.viewingPlayerName = null;
        this.currentPage = 0;
    }

    // ====== Pending Mod Token ======
    public String getPendingModToken() { return pendingModToken; }
    public void setPendingModToken(String token) { this.pendingModToken = token; }
}
