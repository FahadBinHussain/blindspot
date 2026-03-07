package com.github.oangrybird.channelblocker

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.before
import com.discord.widgets.channels.list.`WidgetChannelListModel$Companion$guildListBuilder$$inlined$forEach$lambda$1$2`
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList

/** Channel IDs to block from appearing in the channels list */
private val BLOCKED_CHANNEL_IDS = setOf(997040018604433479L)

@AliucordPlugin
class ChannelBlocker : Plugin() {
    init {
        settingsTab = SettingsTab(Settings::class.java)
    }

    override fun start(context: Context) {
        // Hook the guild channel list builder lambda. When this lambda's invoke() returns true,
        // the channel is added to hiddenChannelsIds and hidden from the list. This is the same
        // mechanism used by Discord's own "Hide Muted Channels" feature.
        patcher.before<`WidgetChannelListModel$Companion$guildListBuilder$$inlined$forEach$lambda$1$2`>("invoke") { param ->
            if (`$textChannelId` in BLOCKED_CHANNEL_IDS) {
                param.result = true
            }
        }

        // Also hook configureUI to filter out any blocked items that may have slipped through
        // (e.g. DM channels or channels loaded outside the guild builder path).
        patcher.before<WidgetChannelsList>(
            "configureUI",
            WidgetChannelListModel::class.java
        ) { param ->
            val model = param.args[0] as WidgetChannelListModel
            val filtered = model.items.filter { item ->
                try {
                    val channelIdField = item::class.java.declaredFields.firstOrNull { field ->
                        (field.type == Long::class.javaPrimitiveType || field.type == java.lang.Long::class.java) &&
                        field.name.contains("channel", ignoreCase = true)
                    }
                    if (channelIdField != null) {
                        channelIdField.isAccessible = true
                        val id = channelIdField.getLong(item)
                        id !in BLOCKED_CHANNEL_IDS
                    } else {
                        true // keep items whose channel ID we can't determine
                    }
                } catch (_: Throwable) {
                    true
                }
            }
            if (filtered.size != model.items.size) {
                // Replace the items list on the model via reflection
                try {
                    val itemsField = WidgetChannelListModel::class.java.declaredFields
                        .firstOrNull { it.name == "items" }
                        ?: WidgetChannelListModel::class.java.declaredFields
                            .firstOrNull { it.type == List::class.java }
                    itemsField?.let {
                        it.isAccessible = true
                        it.set(model, filtered)
                    }
                } catch (e: Throwable) {
                    logger.error("Failed to filter channel list items", e)
                }
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
