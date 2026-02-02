package com.grindrplus.persistence.model;

public class ConversationEntity {
    public String conversationId;
    public Long participantId;
    public String name;
    public String lastMessage;
    public int unreadCount;
    public long lastActivityTimestamp;
    public boolean isMuted;
    public boolean isPinned;
    public boolean isFavorite;

    public ConversationEntity() {}

    // Add this constructor for cleaner mapping in Kotlin
    public ConversationEntity(String conversationId, Long participantId, String name,
                              String lastMessage, int unreadCount, long lastActivityTimestamp,
                              boolean isMuted, boolean isPinned, boolean isFavorite) {
        this.conversationId = conversationId;
        this.participantId = participantId;
        this.name = name;
        this.lastMessage = lastMessage;
        this.unreadCount = unreadCount;
        this.lastActivityTimestamp = lastActivityTimestamp;
        this.isMuted = isMuted;
        this.isPinned = isPinned;
        this.isFavorite = isFavorite;
    }
}