package com.grindrplus.hooks

import android.widget.Toast
import com.grindrplus.GrindrPlus
import com.grindrplus.bridge.BridgeService
import com.grindrplus.core.Config
import com.grindrplus.core.DatabaseHelper
import com.grindrplus.core.Logger
import com.grindrplus.core.logd
import com.grindrplus.core.loge
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.RetrofitUtils
import com.grindrplus.utils.RetrofitUtils.isGET
import com.grindrplus.utils.RetrofitUtils.isPOST
import com.grindrplus.utils.RetrofitUtils.isPUT
import com.grindrplus.utils.RetrofitUtils.isResult
import com.grindrplus.utils.hook
import com.grindrplus.utils.hookConstructor
import com.grindrplus.utils.withSuspendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

// supported version: 25.20.0
class AntiBlock : Hook(
    "Anti Block",
    "Notifies you when someone blocks or unblocks you"
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var myProfileId: Long = 0
    private var chatService = "com.grindrapp.android.chat.data.datasource.api.service.ChatRestService" // search for '"v3/inbox"' and use the outer interface
    private val chatDeleteConversationPlugin = "R9.c" // search for '"com.grindrapp.android.chat.ChatDeleteConversationPlugin",' and use the outer class
    private val inboxFragmentV2DeleteConversations = "re.d" // search for '("chat_read_receipt", conversationId, null);'
    private val individualUnblockActivityViewModel = "bl.k" // search for 'SnackbarEvent.i.ERROR, R.string.unblock_individual_sync_blocks_failure, null, new SnackbarEvent'

    override fun init() {
        logd("Initializing Anti Block with special stuff")
        val chatServiceClass = findClass(chatService)

        RetrofitUtils.hookService(chatServiceClass) { originalHandler, proxy, method, args ->
            val result = try {
                originalHandler.invoke(proxy, method, args)
            } catch (e: Exception) {
                loge("Original invocation failed for ${method.name}: ${e.message}")
                throw e
            }

            try {
                val isInboxV3 =
                    method.name == "C"
                logd("Method ${method.name}: isInboxV3=$isInboxV3")

                if (isInboxV3) {
                    logd("Interception triggered for v3/inbox via method: ${method.name}")
                    // Debug arguments to catch potential ClassCast issues
                    args.forEachIndexed { index, arg ->
                        logd("Arg[$index]: type=${arg?.javaClass?.name}, value=$arg")
                    }
                    return@hookService handleGetChats(args, result)
                }
            } catch (e: Exception) {
                loge("Error in AntiBlock interception logic: ${e.message}")
                Logger.writeRaw("Interception Error:\n${e.stackTraceToString()}")
            }

            result
        }

        // do not invoke antiblock notification when the user is unblocking someone else
        // search for '.setValue(new DialogMessage(116, null, 2, null));'
        findClass(individualUnblockActivityViewModel)
            .hook("R", HookStage.BEFORE) { param ->
                GrindrPlus.shouldTriggerAntiblock = false
            }

        // reenable antiblock notification after *above* is finished
        // search for '.setValue(new DialogMessage(116, null, 2, null));'
        findClass(individualUnblockActivityViewModel)
            .hook("R", HookStage.AFTER) { param ->
                Thread.sleep(700) // Wait for WS to unblock
                GrindrPlus.shouldTriggerAntiblock = true
            }

        if (Config.get("force_old_anti_block_behavior", false) as Boolean) {
            findClass("com.grindrapp.android.chat.model.ConversationDeleteNotification")
                .hookConstructor(HookStage.BEFORE) { param ->
                    @Suppress("UNCHECKED_CAST")
                    val profiles = param.args().firstOrNull() as? List<String> ?: emptyList()
                    param.setArg(0, emptyList<String>())
                }
        } else {
            // search for '("chat_read_receipt", conversationId, null);'
            findClass(inboxFragmentV2DeleteConversations)
                .hook("b", HookStage.BEFORE) { param ->
                    GrindrPlus.shouldTriggerAntiblock = false
                    GrindrPlus.blockCaller = "inboxFragmentV2DeleteConversations"
                }

            // search for '("chat_read_receipt", conversationId, null);'
            findClass(inboxFragmentV2DeleteConversations)
                .hook("b", HookStage.AFTER) { param ->
                    val numberOfChatsToDelete = (param.args().firstOrNull() as? List<*>)?.size ?: 0
                    if (numberOfChatsToDelete == 0)
                        return@hook
                    // is this okay to return here? shouldTriggerAntiblock stays false.
                    // Do we expect another invocation with number > 0 ?

                    logd("Request to delete $numberOfChatsToDelete chats")
                    Thread.sleep((300 * numberOfChatsToDelete).toLong()) // FIXME
                    GrindrPlus.shouldTriggerAntiblock = true
                    GrindrPlus.blockCaller = ""
                }

            // search for 'Deleting conversations'
            findClass(chatDeleteConversationPlugin)
                .hook("b", HookStage.BEFORE) { param ->
                    myProfileId = GrindrPlus.myProfileId.toLong()
                    if (GrindrPlus.shouldTriggerAntiblock)
                        return@hook

                    val whitelist = listOf(
                        "inboxFragmentV2DeleteConversations",
                    )
                    if (GrindrPlus.blockCaller in whitelist)
                        return@hook

                    param.setResult(null)
                }

            findClass("com.grindrapp.android.chat.model.ConversationDeleteNotification")
                .hookConstructor(HookStage.BEFORE) { param ->
                    @Suppress("UNCHECKED_CAST")
                    if (GrindrPlus.shouldTriggerAntiblock) {
                        val profiles = param.args().firstOrNull() as? List<String> ?: emptyList()
                        param.setArg(0, emptyList<String>())
                    }
                }

            scope.launch {
                GrindrPlus.serverNotifications.collect { notification ->
                    if (notification.typeValue != "chat.v1.conversation.delete") return@collect
                    if (!GrindrPlus.shouldTriggerAntiblock) return@collect

                    val conversationIds = notification.payload
                        ?.optJSONArray("conversationIds") ?: return@collect

                    val conversationIdStrings = (0 until conversationIds.length())
                        .map { index -> conversationIds.getString(index) }

                    val myId = GrindrPlus.myProfileId.toLongOrNull() ?: return@collect

                    val otherProfileId = conversationIdStrings
                        .flatMap { conversationId ->
                            conversationId.split(":").mapNotNull { it.toLongOrNull() }
                        }
                        .firstOrNull { id -> id != myId }

                    if (otherProfileId == null || otherProfileId == myId) {
                        logd("Skipping block/unblock handling for my own profile or no valid profile found")
                        return@collect
                    }

                    try {
                        if (DatabaseHelper.query(
                                "SELECT * FROM blocks WHERE profileId = ?",
                                arrayOf(otherProfileId.toString())
                            ).isNotEmpty()
                        ) {
                            return@collect
                        }
                    } catch (e: Exception) {
                        loge("Error checking if user is blocked: ${e.message}")
                        Logger.writeRaw(e.stackTraceToString())
                    }

                    try {
                        val response = fetchProfileData(otherProfileId.toString())
                        if (handleProfileResponse(otherProfileId,
                                conversationIdStrings.joinToString(","), response)) {
                        }
                    } catch (e: Exception) {
                        loge("Error handling block/unblock request: ${e.message ?: "Unknown error"}")
                        Logger.writeRaw(e.stackTraceToString())
                    }
                }
            }
        }
    }


    private fun handleGetChats(args: Array<Any?>, result: Any) =
        withSuspendResult(args, result) { suspendArgs, originalResult ->
            try {
                // originalResult is the Result wrapper (e.g., AbstractC5533a.Success)
                // We need to use reflection to find the 'data' field containing ConversationsResponseV3
                val resultClass = originalResult.javaClass

                // Search for a field that matches our ConversationsResponse type
                val dataField = resultClass.declaredFields.firstOrNull { field ->
                    // This checks if the field type is a subclass of ConversationsResponse
                    findClass("com.grindrapp.android.chat.api.model.ConversationsResponse")
                        .isAssignableFrom(field.type)
                }

                dataField?.let { field ->
                    field.isAccessible = true
                    val response = field.get(originalResult)

                    // Use reflection to call 'getConversations' on the response object
                    val getConversationsMethod = response.javaClass.getMethod("getConversations")
                    val conversations = getConversationsMethod.invoke(response) as List<*>

                    // 1. Extract IDs from the incoming network response
                    val incomingIds = conversations.mapNotNull { conv ->
                        // Each item is an 'InboxConversation'. We need its conversationId.
                        // You might need to check JADX for the exact method name (e.g., "getConversationId")
                        val getConvIdMethod = conv?.javaClass?.getMethod("getConversationId")
                        getConvIdMethod?.invoke(conv) as? String
                    }

                    // 2. Fetch known IDs from your local database
                    val localIds = DatabaseHelper.query(
                        "SELECT conversation_id FROM chat_conversations",
                        null
                    ).mapNotNull { it["conversation_id"] as? String }

                    // 3. Find the differences
                    val missingFromInbox = localIds.filter { it !in incomingIds }

                    if (missingFromInbox.isNotEmpty()) {
                        logd("Potential Blocks Detected! Missing IDs: ${missingFromInbox.joinToString()}")
                        // Trigger your notification logic for each missing ID here
                    }

                    // 4. Find new chats
                    val newInInbox = incomingIds.filter { it !in localIds }

                    if(newInInbox.isNotEmpty()) {
                        logd("New Chats Detected! New IDs: ${newInInbox.joinToString()}")
                    }
                }
            } catch (e: Exception) {
                loge("AntiBlock: Failed to process inbox diff: ${e.message}")
            }

            // Return the originalResult untouched to keep the app happy
            return@withSuspendResult originalResult
        }

    private fun fetchProfileData(profileId: String): String {
        val response = GrindrPlus.httpClient.sendRequest(
            url = "https://grindr.mobi/v4/profiles/$profileId",
            method = "GET"
        )

        if (response.isSuccessful) {
            return response.body?.string() ?: "Empty response"
        } else {
            throw Exception("Failed to fetch profile data: ${response.body?.string()}")
        }
    }

    private fun handleProfileResponse(profileId: Long, conversationIds: String, response: String): Boolean {
        try {
            val jsonResponse = JSONObject(response)
            val profilesArray = jsonResponse.optJSONArray("profiles")

            if (profilesArray == null || profilesArray.length() == 0) {
                var displayName = ""
                try {
                    displayName = (DatabaseHelper.query(
                        "SELECT name FROM chat_conversations WHERE conversation_id = ?",
                        arrayOf(conversationIds)
                    ).firstOrNull()?.get("name") as? String)?.takeIf {
                            name -> name.isNotEmpty() } ?: profileId.toString()
                } catch (e: Exception) {
                    loge("Error fetching display name: ${e.message}")
                    Logger.writeRaw(e.stackTraceToString())
                    displayName = profileId.toString()
                }
                displayName = if (displayName == profileId.toString() || displayName == "null")
                { profileId.toString() } else { "$displayName ($profileId)" }
                GrindrPlus.bridgeClient.logBlockEvent(profileId.toString(), displayName, true,
                    GrindrPlus.packageName)
                if (Config.get("anti_block_use_toasts", false) as Boolean) {
                    GrindrPlus.showToast(Toast.LENGTH_LONG, "Blocked by $displayName")
                } else {
                    GrindrPlus.bridgeClient.sendNotificationWithMultipleActions(
                        "Blocked by User",
                        "You have been blocked by user $displayName",
                        10000000 + (profileId % 10000000).toInt(),
                        listOf("Copy ID"),
                        listOf("COPY"),
                        listOf(profileId.toString(), profileId.toString()),
                        BridgeService.CHANNEL_BLOCKS,
                        "Block Notifications",
                        "Notifications when users block you"
                    )
                }
                return true
            } else {
                val profile = profilesArray.getJSONObject(0)
                var displayName = profile.optString("displayName", profileId.toString())
                    .takeIf { it.isNotEmpty() && it != "null" } ?: profileId.toString()
                displayName = if (displayName != profileId.toString()) "$displayName ($profileId)" else displayName
                GrindrPlus.bridgeClient.logBlockEvent(profileId.toString(), displayName, false,
                    GrindrPlus.packageName)
                if (Config.get("anti_block_use_toasts", false) as Boolean) {
                    GrindrPlus.showToast(Toast.LENGTH_LONG, "Unblocked by $displayName")
                } else {
                    GrindrPlus.bridgeClient.sendNotificationWithMultipleActions(
                        "Unblocked by $displayName",
                        "$displayName has unblocked you.",
                        20000000 + (profileId % 10000000).toInt(),
                        listOf("Copy ID"),
                        listOf("COPY"),
                        listOf(profileId.toString()),
                        BridgeService.CHANNEL_UNBLOCKS,
                        "Unblock Notifications",
                        "Notifications when users unblock you"
                    )
                }
                return false
            }
        } catch (e: Exception) {
            loge("Error handling profile response: ${e.message ?: "Unknown error"}")
            Logger.writeRaw(e.stackTraceToString())
            return false
        }
    }
}
