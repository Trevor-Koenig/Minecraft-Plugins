// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.utils;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Command errors with MiniMessage formatting. Throw the result from a command executor and the player sees
 * it in red, like any Brigadier error, but it can contain display names, money and other placeholders.
 */
public class Errors {

    private static final DynamicCommandExceptionType FAILED = new DynamicCommandExceptionType(message -> (Message) message);

    public static CommandSyntaxException fail(String template, TagResolver... placeholders) {
        return FAILED.create(MessageComponentSerializer.message().serialize(
            MiniMessage.miniMessage().deserialize(template, placeholders)));
    }
}
