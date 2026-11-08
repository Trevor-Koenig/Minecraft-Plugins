// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public class MineCommand {

    public static LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("mine")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage("Hello from TheBox!");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    ctx.getSource().getSender().sendMessage("Creating mine: " + name);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }
}
