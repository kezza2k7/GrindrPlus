package com.grindrplus.persistence.mappers

import com.grindrplus.core.Logger
import com.grindrplus.persistence.model.ConversationEntity
import de.robv.android.xposed.XposedHelpers.*

/**
 * Maps the obfuscated Inbox response to our local Entity.
 */
fun Any.asInboxToConversationEntities(): List<ConversationEntity> {
    val entities = mutableListOf<ConversationEntity>()

    return try {
        // Based on scouting: the wrapper 'Yf.a$b' has a field 'a'
        // which contains the 'ConversationsResponseV3' object.
        val responseData = getObjectField(this, "a") ?: return emptyList()

        // Find the 'conversations' list inside that object
        val rawList = getObjectField(responseData, "conversations") as? List<*> ?: return emptyList()

        rawList.forEach { inboxConv ->
            if (inboxConv != null) {
                entities.add(inboxConv.toConversationEntity())
            }
        }
        entities
    } catch (e: Throwable) {
        Logger.e("Error mapping InboxResponse to Entities: ${e.message}")
        emptyList()
    }
}

/**
 * Maps a single InboxConversation object to our Entity.
 */
fun Any.toConversationEntity(): ConversationEntity {
    return try {
        // Navigate: InboxConversation -> data (ConversationResponse)
        val data = getObjectField(this, "data")

        // Navigate: ConversationResponse -> preview (ConversationPreviewResponse)
        val preview = getObjectField(data, "preview")

        // Navigate: ConversationResponse -> participants (List)
        val participants = getObjectField(data, "participants") as? List<*>
        val firstParticipant = participants?.firstOrNull()

        ConversationEntity().apply {
            conversationId = getObjectField(data, "conversationId") as String

            unreadCount = getIntField(data, "unreadCount")
            lastActivityTimestamp = getLongField(data, "lastActivityTimestamp")

            isMuted = (getObjectField(data, "isMuted") as? Boolean) ?: false
            isPinned = (getObjectField(data, "isPinned") as? Boolean) ?: false
            isFavorite = (getObjectField(data, "isFavorite") as? Boolean) ?: false

            name = getObjectField(data, "name") as? String ?: "Unknown"
            lastMessage = if (preview != null) getObjectField(preview, "text") as? String else null
            participantId = if (firstParticipant != null) getLongField(firstParticipant, "profileId") else 0L
        }
    } catch (e: Throwable) {
        Logger.e("Error converting InboxConversation: ${e.message}")
        ConversationEntity() // Return empty shell on failure
    }
}