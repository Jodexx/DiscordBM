package com.wairesd.discordbm.host.common.discord.handler;

import com.wairesd.discordbm.api.DBMAPI;
import com.wairesd.discordbm.common.utils.logging.PluginLogger;
import com.wairesd.discordbm.common.utils.logging.Slf4jPluginLogger;
import com.wairesd.discordbm.host.common.discord.DiscordBMHPlatformManager;
import com.wairesd.discordbm.host.common.commandbuilder.commands.core.CommandExecutorFacade;
import com.wairesd.discordbm.host.common.config.configurators.Settings;
import com.wairesd.discordbm.host.common.discord.response.ResponseHelper;
import com.wairesd.discordbm.host.common.discord.request.RequestSender;
import com.wairesd.discordbm.host.common.models.command.CommandDefinition;
import com.wairesd.discordbm.api.command.CommandRegistration;
import com.wairesd.discordbm.host.common.models.command.HostCommandRegistration;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.LoggerFactory;

public class CommandHandler {
    private static final PluginLogger logger = new Slf4jPluginLogger(LoggerFactory.getLogger("DiscordBM"));
    private final DiscordBMHPlatformManager platformManager;
    private final RequestSender requestSender;
    private final ResponseHelper responseHelper;
    private final CommandExecutorFacade commandExecutorFacade;

    public CommandHandler(DiscordBMHPlatformManager platformManager, RequestSender requestSender, ResponseHelper responseHelper) {
        this.platformManager = platformManager;
        this.requestSender = requestSender;
        this.responseHelper = responseHelper;

        if (platformManager.getDiscordBotManager().getJda() != null) {
            this.commandExecutorFacade = new CommandExecutorFacade();
            logger.info("CommandExecutor initialized");
        } else {
            logger.error("Failed to initialize CommandExecutor - JDA is null!");
            this.commandExecutorFacade = null;
        }
    }

    public boolean isCommandRestrictedToDM(SlashCommandInteractionEvent event, CommandDefinition cmdDef) {
        return cmdDef != null && "dm".equals(cmdDef.context()) && event.getGuild() != null;
    }

    public void handleCustomCommand(SlashCommandInteractionEvent event, String command) {
        var customCommand = platformManager.getCommandManager().getCommand(command);
        if (customCommand != null) {
            if (commandExecutorFacade != null) {
                if (Settings.isDebugCommandExecution()) {
                    logger.info("Executing custom command: {}", command);
                }
                commandExecutorFacade.execute(event, customCommand);
            } else {
                logger.error("CommandExecutor is null, cannot execute command '{}'", command);
                event.reply("Command execution failed due to internal error.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
            return;
        }

        var api = DBMAPI.getInstance();
        if (api != null) {
            CommandRegistration reg = api.getCommandRegistration();
            var registered = reg.getRegisteredCommands();
            for (var cmd : registered) {
                if (cmd.getName().equalsIgnoreCase(command)) {
                    if (reg instanceof HostCommandRegistration hostReg) {
                        var handler = hostReg.getHandler(command);
                        if (handler != null) {
                            String reqId = java.util.UUID.randomUUID().toString();
                            event.deferReply(false).queue(hook -> {
                                requestSender.getPendingRequests().put(java.util.UUID.fromString(reqId), event);
                                handler.handleCommand(command, event.getOptions().stream().collect(
                                    java.util.stream.Collectors.toMap(o -> o.getName(), o -> o.getAsString())
                                ), reqId);
                            });
                            return;
                        }
                    }
                }
            }
        }

        if (Settings.isDebugCommandNotFound()) {
            logger.warn("Custom command '{}' not found", command);
        }
        event.reply("Command unavailable.")
                .setEphemeral(true)
                .queue();
    }
}
