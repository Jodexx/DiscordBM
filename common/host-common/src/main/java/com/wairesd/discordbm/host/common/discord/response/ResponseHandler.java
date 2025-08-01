package com.wairesd.discordbm.host.common.discord.response;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wairesd.discordbm.common.models.buttons.ButtonDefinition;
import com.wairesd.discordbm.common.models.buttons.ButtonStyle;
import com.wairesd.discordbm.common.models.embed.EmbedDefinition;
import com.wairesd.discordbm.common.models.modal.ModalDefinition;
import com.wairesd.discordbm.common.models.response.ResponseMessage;
import com.wairesd.discordbm.common.utils.logging.PluginLogger;
import com.wairesd.discordbm.common.utils.logging.Slf4jPluginLogger;
import com.wairesd.discordbm.host.common.commandbuilder.components.buttons.component.ButtonEditor;
import com.wairesd.discordbm.host.common.commandbuilder.utils.message.MessageComponentFetcher;
import com.wairesd.discordbm.host.common.commandbuilder.utils.message.MessageUpdater;
import com.wairesd.discordbm.host.common.discord.DiscordBMHPlatformManager;
import com.wairesd.discordbm.host.common.config.configurators.Settings;
import com.wairesd.discordbm.host.common.discord.DiscordBotListener;
import com.wairesd.discordbm.host.common.discord.request.RequestSender;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.context.Context;
import com.wairesd.discordbm.host.common.commandbuilder.utils.MessageFormatterUtils;
import com.wairesd.discordbm.host.common.commandbuilder.core.parser.CommandParserCondition;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.conditions.CommandCondition;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.error.CommandErrorMessages;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.error.CommandErrorType;
import com.wairesd.discordbm.host.common.commandbuilder.components.buttons.registry.ButtonActionRegistry;
import com.wairesd.discordbm.api.message.ResponseType;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.wairesd.discordbm.host.common.commandbuilder.interaction.messages.DeleteMessageAction;

public class ResponseHandler {
    private static DiscordBotListener listener;
    private static DiscordBMHPlatformManager platformManager;
    private static final PluginLogger logger = new Slf4jPluginLogger(LoggerFactory.getLogger("DiscordBM"));
    private static final ConcurrentHashMap<String, Boolean> sentFormRequests = new ConcurrentHashMap<>();

    public static void init(DiscordBotListener discordBotListener, DiscordBMHPlatformManager platform) {
        listener = discordBotListener;
        platformManager = platform;
    }

    public static void handleResponse(ResponseMessage respMsg) {
        if (Settings.isDebugRequestProcessing()) {
            logger.info("Response received for request " + respMsg.requestId() + ": " + respMsg.toString());
        }

        ResponseType responseType = ResponseTypeDetector.determineResponseType(respMsg);
        if (Settings.isDebugRequestProcessing()) {
            logger.info("Auto-detected response type: {} for requestId: {}", responseType, respMsg.requestId());
        }
        
        if (respMsg.conditions() != null && !respMsg.conditions().isEmpty()) {
            Context context = null;
            var event = listener != null ? listener.getRequestSender().getPendingRequests().get(respMsg.requestId()) : null;
            if (event != null) {
                context = new Context(event);
            } else {
                context = new Context((SlashCommandInteractionEvent) null);
            }
            for (var condMap : respMsg.conditions()) {
                try {
                    CommandCondition cond = CommandParserCondition.parseCondition(condMap);
                    if (!cond.check(context)) {
                        logger.info("Message condition not met, skipping send: " + condMap);
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse/check message condition: " + condMap, e);
                    return;
                }
            }
        }
        
        try {
            UUID requestId = null;
            switch (responseType) {
                case MODAL:
                case REPLY_MODAL:
                case REPLY:
                case EDIT_MESSAGE:
                    requestId = UUID.fromString(respMsg.requestId());
                    break;
                default:
                    break;
            }

            switch (responseType) {
                case MODAL:
                    if (sentFormRequests.putIfAbsent(respMsg.requestId(), true) != null) {
                        return;
                    }
                    handleFormResponse(requestId, respMsg);
                    return;
                case DIRECT:
                    sendDirectMessage(respMsg);
                    return;
                case CHANNEL:
                    sendChannelMessage(respMsg);
                    return;
                case EDIT_MESSAGE:
                    editMessage(respMsg);
                    return;
                case REPLY_MODAL:
                    handleReplyModal(requestId, respMsg);
                    return;
                case REPLY:
                default:
                    break;
            }

            if (respMsg.flags() != null && respMsg.flags().shouldPreventMessageSend()) {
                return;
            }

            if (respMsg.flags() != null && respMsg.flags().isFormResponse()) {
                return;
            }

            InteractionHook buttonHook = (InteractionHook)platformManager.getPendingButtonRequests().remove(requestId);
            if (buttonHook != null) {
                var embedBuilder = new EmbedBuilder();
                if (respMsg.embed() != null) {
                    embedBuilder.setTitle(respMsg.embed().title())
                            .setDescription(respMsg.embed().description())
                            .setColor(new Color(respMsg.embed().color()));
                }
                var embed = embedBuilder.build();

                List<Button> jdaButtons = respMsg.buttons().stream()
                        .map(btn -> Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label()))
                        .collect(Collectors.toList());

                boolean ephemeral = false;
                if (ephemeral) {
                    buttonHook.sendMessageEmbeds(embed).addActionRow(jdaButtons).setEphemeral(true).queue();
                } else {
                    buttonHook.editOriginalEmbeds(embed)
                            .setActionRow(jdaButtons)
                            .queue();
                }
                return;
            }

            InteractionHook storedHook = listener.getRequestSender().removeInteractionHook(requestId);
            if (storedHook != null) {
                sendResponseWithHook(storedHook, respMsg);
                return;
            }

            final UUID finalRequestId = requestId;
            var event = listener.getRequestSender().getPendingRequests().remove(requestId);
            if (event == null) {
                if (respMsg.embed() != null && respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
                    return;
                }
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        InteractionHook retryHook = listener.getRequestSender().removeInteractionHook(finalRequestId);
                        if (retryHook != null) {
                            sendResponseWithHook(retryHook, respMsg);
                            return;
                        }
                        var retryEvent = listener.getRequestSender().getPendingRequests().remove(finalRequestId);
                        if (retryEvent != null) {
                            sendResponse(retryEvent, respMsg);
                        } else {
                            logger.error("Still no event or hook found for requestId: {}", finalRequestId);
                        }
                    }
                }, 100);
                return;
            } else {
                sendResponse(event, respMsg);
            }
        } catch (IllegalArgumentException e) {
            logInvalidUUID(respMsg.requestId(), e);
        }
    }

    private static void handleFormResponse(UUID requestId, ResponseMessage respMsg) {
        ModalDefinition formDef = respMsg.modal();
        if (formDef == null) {
            logger.error("Form definition is null for requestId: {}", requestId);
            return;
        }

        Modal.Builder modalBuilder = Modal.create(formDef.getCustomId(), formDef.getTitle());
        
        for (var fieldDef : formDef.getFields()) {
            TextInputStyle style = TextInputStyle.valueOf(fieldDef.getType().toUpperCase());
            TextInput input = TextInput.create(
                    fieldDef.getVariable(),
                    fieldDef.getLabel(),
                    style)
                    .setPlaceholder(fieldDef.getPlaceholder())
                    .setRequired(fieldDef.isRequired())
                    .build();
            modalBuilder.addActionRow(input);
        }
        
        Modal modal = modalBuilder.build();

        String messageTemplate = respMsg.response() != null ? respMsg.response() : "";
        boolean isNettyForm = false;
        if (listener != null) {
            Map<String, String> requestIdToCommand = listener.getRequestIdToCommand();
            if (requestIdToCommand != null && requestIdToCommand.containsKey(requestId.toString())) {
                isNettyForm = true;
            }
        }
        if (!isNettyForm) {
            platformManager.getFormHandlers().put(formDef.getCustomId(), messageTemplate);
        }

        if (respMsg.flags() != null && respMsg.flags().requiresModal()) {
            if (listener != null) {
                listener.formEphemeralMap.put(requestId.toString(), false);
            }
        }

        var event = listener.getRequestSender().getPendingRequests().get(requestId);
        if (event != null) {
            event.replyModal(modal).queue(
                    success -> {
                        if (Settings.isDebugRequestProcessing()) {
                            logger.info("Form sent successfully for requestId: {}", requestId);
                        }
                    },
                    failure -> {
                        logger.error("Failed to send form: {}", failure.getMessage());
                        event.getHook().sendMessage("Failed to open form. Please try again.").setEphemeral(true).queue();
                    }
            );
        } else {
            InteractionHook hook = listener.getRequestSender().removeInteractionHook(requestId);
            if (hook != null) {
                if (respMsg.response() != null && !respMsg.response().isEmpty()) {
                    hook.sendMessage(respMsg.response()).setEphemeral(true).queue();
                }
                hook.sendMessage("Form functionality is not available for deferred responses.").setEphemeral(true).queue();
            } else {
                logger.error("No event or hook found for form requestId: {}", requestId);
            }
        }
    }

    private static void sendResponse(SlashCommandInteractionEvent event, ResponseMessage respMsg) {
        boolean ephemeral = respMsg.flags() != null && respMsg.flags().isEphemeral();
        String responseType = respMsg.flags() != null ? respMsg.flags().getResponseType() : null;
        String label = respMsg.requestId();
        if ("RANDOM_REPLY".equalsIgnoreCase(responseType) && respMsg.responses() != null && !respMsg.responses().isEmpty()) {
            List<String> responses = respMsg.responses();
            String randomMessage = responses.get(new java.util.Random().nextInt(responses.size()));
            try {
                event.getHook().sendMessage(randomMessage).setEphemeral(ephemeral).queue(
                    success -> {
                        if (label != null && !label.isEmpty()) {
                            String channelId = event.getChannel().getId();
                            String messageId = success.getId();
                            platformManager.setGlobalMessageLabel(label, channelId, messageId);
                        }
                        if (Settings.isDebugRequestProcessing()) {
                            logger.info("Random reply message sent successfully");
                        }
                    },
                    failure -> logger.error("Failed to send random reply message: {}", failure.getMessage())
                );
            } catch (Exception e) {
                logger.error("Exception while sending message for requestId: {} | {}", respMsg.requestId(), e.getMessage(), e);
            }
            return;
        }
        if ("EDIT_MESSAGE".equalsIgnoreCase(responseType)) {
            event.getHook().editOriginal(respMsg.response() != null ? respMsg.response() : "").queue();
            return;
        }
        if ("REPLY_TO_MESSAGE".equalsIgnoreCase(responseType) && respMsg.replyMessageId() != null && !respMsg.replyMessageId().isEmpty()) {
            try {
                if (event != null) {
                    if (!event.isAcknowledged()) {
                        event.deferReply(true).queue(hook -> hook.deleteOriginal().queue());
                    } else if (event.getHook() != null) {
                        event.getHook().deleteOriginal().queue();
                    }
                }
                var msgAction = event.getChannel().sendMessage(respMsg.response())
                        .setMessageReference(respMsg.replyMessageId())
                        .mentionRepliedUser(Boolean.TRUE.equals(respMsg.replyMentionAuthor()));
                msgAction.queue(success -> {
                    if (label != null && !label.isEmpty()) {
                        String channelId = event.getChannel().getId();
                        String messageId = success.getId();
                        platformManager.setGlobalMessageLabel(label, channelId, messageId);
                    }
                }, failure -> logger.error("Failed to send REPLY_TO_MESSAGE: {}", failure.getMessage()));
            } catch (Exception e) {
                logger.error("Exception while sending REPLY_TO_MESSAGE for requestId: {} | {}", respMsg.requestId(), e.getMessage(), e);
            }
            return;
        }
        if (respMsg.embed() != null) {
            sendCustomEmbed(event, respMsg.embed(), respMsg.buttons(), UUID.fromString(respMsg.requestId()), ephemeral);
        } else if (respMsg.response() != null) {
            if (respMsg.response().startsWith("ERROR:")) {
                handleConditionError(event, respMsg.response(), ephemeral);
                return;
            }
            
            if (respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
                List<Button> jdaButtons = respMsg.buttons().stream()
                        .map(btn -> {
                            if (btn.style() == ButtonStyle.LINK) {
                                return Button.link(btn.url(), btn.label());
                            } else {
                                return Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label())
                                        .withDisabled(btn.disabled());
                            }
                        })
                        .collect(Collectors.toList());
                event.getHook().sendMessage(respMsg.response())
                        .addActionRow(jdaButtons)
                        .setEphemeral(ephemeral)
                        .queue(
                                success -> {
                                    if (label != null && !label.isEmpty()) {
                                        String channelId = event.getChannel().getId();
                                        String messageId = success.getId();
                                        platformManager.setGlobalMessageLabel(label, channelId, messageId);
                                    }
                                    if (Settings.isDebugRequestProcessing()) {
                                        logger.info("Message with buttons sent successfully");
                                    }
                                },
                                failure -> logger.error("Failed to send message with buttons: {}", failure.getMessage())
                        );
            } else {
                event.getHook().sendMessage(respMsg.response()).setEphemeral(ephemeral).queue(
                        success -> {
                            if (label != null && !label.isEmpty()) {
                                String channelId = event.getChannel().getId();
                                String messageId = success.getId();
                                platformManager.setGlobalMessageLabel(label, channelId, messageId);
                            }
                            if (Settings.isDebugRequestProcessing()) {
                                logger.info("Message sent successfully");
                            }
                        },
                        failure -> logger.error("Failed to send message: {}", failure.getMessage())
                );
            }
            if (Settings.isDebugRequestProcessing()) {
                logger.info("Response sent for requestId: {}", respMsg.requestId());
            }
            UUID requestId = UUID.fromString(respMsg.requestId());
            var removed = listener.getRequestSender().getPendingRequests().remove(requestId);
        } else {
            event.getHook().sendMessage("No response provided.").setEphemeral(ephemeral).queue();
        }
        if (respMsg.buttons() != null && !respMsg.buttons().isEmpty() && respMsg.modal() != null) {
            for (var btn : respMsg.buttons()) {
                if (btn.formName() != null && !btn.formName().isEmpty()) {
                    ButtonActionRegistry registry = new ButtonActionRegistry();
                    registry.registerFormButton(
                        btn.customId(),
                        btn.formName(),
                        respMsg.response(),
                        null,
                        10 * 60 * 1000L
                    );
                }
            }
        }
    }

    private static void sendCustomEmbed(SlashCommandInteractionEvent event, EmbedDefinition embedDef, List<ButtonDefinition> buttons, UUID requestId, boolean ephemeral) {
        var embedBuilder = new EmbedBuilder();
        if (embedDef.title() != null) {
            embedBuilder.setTitle(embedDef.title());
        }
        if (embedDef.description() != null) {
            Context context = new Context(event);
            String serverName = listener.getRequestSender().getServerNameForRequest(requestId);
            if (serverName != null) {
                Map<String, String> variables = new HashMap<>();
                variables.put(RequestSender.SERVER_NAME_VAR, serverName);
                context.setVariables(variables);
            }
            String description = embedDef.description();
            try {
                description = MessageFormatterUtils.format(description, event, context, false).get();
            } catch (Exception e) {
                if (Settings.isDebugErrors()) {
                    logger.error("Error formatting embed description: {}", e.getMessage());
                }
            }
            embedBuilder.setDescription(description);
        }
        if (embedDef.color() != null) {
            embedBuilder.setColor(new Color(embedDef.color()));
        }
        if (embedDef.fields() != null) {
            for (var field : embedDef.fields()) {
                Context context = new Context(event);
                String serverName = listener.getRequestSender().getServerNameForRequest(requestId);
                if (serverName != null) {
                    Map<String, String> variables = new HashMap<>();
                    variables.put(RequestSender.SERVER_NAME_VAR, serverName);
                    context.setVariables(variables);
                }
                String fieldName = field.name();
                String fieldValue = field.value();
                try {
                    fieldName = MessageFormatterUtils.format(fieldName, event, context, false).get();
                    fieldValue = MessageFormatterUtils.format(fieldValue, event, context, false).get();
                } catch (Exception e) {
                    if (Settings.isDebugErrors()) {
                        logger.error("Error formatting embed field: {}", e.getMessage());
                    }
                }
                embedBuilder.addField(fieldName, fieldValue, field.inline());
            }
        }
        var embed = embedBuilder.build();
        if (buttons != null && !buttons.isEmpty()) {
            List<Button> jdaButtons = buttons.stream()
                    .map(btn -> {
                        if (btn.style() == ButtonStyle.LINK) {
                            return Button.link(btn.url(), btn.label());
                        } else {
                            return Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label())
                                    .withDisabled(btn.disabled());
                        }
                    })
                    .collect(Collectors.toList());
            if (ephemeral) {
                event.getHook().sendMessageEmbeds(embed).addActionRow(jdaButtons).setEphemeral(true).queue();
            } else {
                event.getHook().editOriginalEmbeds(embed)
                        .setActionRow(jdaButtons.toArray(new Button[0]))
                        .queue();
            }
        } else {
            if (Settings.isDebugRequestProcessing()) {
                logger.info("About to send embed for requestId: {}", requestId);
            }
            if (ephemeral) {
                event.getHook().sendMessageEmbeds(embed).setEphemeral(true).queue(
                        success -> {
                            if (Settings.isDebugRequestProcessing()) {
                                logger.info("Successfully sent embed for requestId: {}", requestId);
                            }
                        },
                        failure -> logger.error("Failed to send embed for requestId: {} - {}", requestId, failure.getMessage())
                );
            } else {
                event.getHook().editOriginalEmbeds(embed).queue(
                        success -> {
                            if (Settings.isDebugRequestProcessing()) {
                                logger.info("Successfully sent embed for requestId: {}", requestId);
                            }
                        },
                        failure -> logger.error("Failed to send embed for requestId: {} - {}", requestId, failure.getMessage())
                );
            }
        }
    }

    private static net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle getJdaButtonStyle(ButtonStyle style) {
        return switch (style) {
            case PRIMARY -> net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.PRIMARY;
            case SECONDARY -> net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SECONDARY;
            case SUCCESS -> net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.SUCCESS;
            case DANGER -> net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.DANGER;
            case LINK -> net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle.LINK;
        };
    }

    private static void sendResponseWithHook(InteractionHook hook, ResponseMessage respMsg) {
        boolean ephemeral = false;
        if (respMsg.embed() != null) {
            var embedBuilder = new EmbedBuilder();
            if (respMsg.embed().title() != null) {
                embedBuilder.setTitle(respMsg.embed().title());
            }
            if (respMsg.embed().description() != null) {
                embedBuilder.setDescription(respMsg.embed().description());
            }
            if (respMsg.embed().color() != null) {
                embedBuilder.setColor(new Color(respMsg.embed().color()));
            }
            if (respMsg.embed().fields() != null) {
                for (var field : respMsg.embed().fields()) {
                    embedBuilder.addField(field.name(), field.value(), field.inline());
                }
            }
            var embed = embedBuilder.build();

            if (respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
                List<Button> jdaButtons = respMsg.buttons().stream()
                        .map(btn -> {
                            if (btn.style() == ButtonStyle.LINK) {
                                return Button.link(btn.url(), btn.label());
                            } else {
                                return Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label())
                                        .withDisabled(btn.disabled());
                            }
                        })
                        .collect(Collectors.toList());
                if (ephemeral) {
                    hook.sendMessageEmbeds(embed).addActionRow(jdaButtons).setEphemeral(true).queue();
                } else {
                    hook.editOriginalEmbeds(embed)
                            .setActionRow(jdaButtons.toArray(new Button[0]))
                            .queue();
                }
            } else {
                if (ephemeral) {
                    hook.sendMessageEmbeds(embed).setEphemeral(true).queue();
                } else {
                    hook.editOriginalEmbeds(embed).queue();
                }
            }
        } else if (respMsg.response() != null) {
            if (ephemeral) {
                hook.sendMessage(respMsg.response()).setEphemeral(true).queue();
            } else {
                hook.editOriginal(respMsg.response()).queue();
            }
        }
    }

    private static void logInvalidUUID(String requestIdStr, IllegalArgumentException e) {
        logger.error("Invalid UUID format for requestId: {}", requestIdStr, e);
    }

    public static void handleFormOnly(ResponseMessage respMsg) {
        UUID requestId = UUID.fromString(respMsg.requestId());
        handleFormResponse(requestId, respMsg);
    }

    private static void handleReplyModal(UUID requestId, ResponseMessage respMsg) {
        var event = listener.getRequestSender().getPendingRequests().remove(requestId);
        if (event != null) {
            boolean ephemeral = respMsg.flags() != null && respMsg.flags().isEphemeral();
            if (respMsg.response() != null && !respMsg.response().isEmpty()) {
                event.getHook().sendMessage(respMsg.response()).setEphemeral(ephemeral).queue(
                    success -> {
                        if (Settings.isDebugRequestProcessing()) {
                            logger.info("Reply sent for REPLY_MODAL, now sending form for requestId: {}", requestId);
                        }
                        handleFormResponse(requestId, respMsg);
                    },
                    failure -> logger.error("Failed to send reply for REPLY_MODAL: {}", failure.getMessage())
                );
            } else {
                handleFormResponse(requestId, respMsg);
            }
        } else {
            logger.error("No event found for REPLY_MODAL requestId: {}", requestId);
        }
    }

    public static void sendDirectMessage(ResponseMessage respMsg) {
        String userId = respMsg.userId();
        if (userId == null) {
            logger.error("No userId provided for direct_message");
            return;
        }
        var jda = platformManager.getDiscordBotManager().getJda();
        try {
            var user = jda.getUserById(userId);
            if (user == null) {
                logger.error("User with ID {} not found for direct_message", userId);
                return;
            }
            user.openPrivateChannel().queue(pc -> {
                var msgAction = respMsg.response() != null ? pc.sendMessage(respMsg.response()) : pc.sendMessage("");
                if (respMsg.embed() != null) {
                    var embed = toJdaEmbed(respMsg.embed()).build();
                    msgAction.setEmbeds(embed);
                }
                if (respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
                    List<Button> jdaButtons = respMsg.buttons().stream()
                            .map(btn -> btn.style() == ButtonStyle.LINK ? Button.link(btn.url(), btn.label()) : Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label()).withDisabled(btn.disabled()))
                            .collect(Collectors.toList());
                    msgAction.setActionRow(jdaButtons);
                }
                msgAction.queue(null, error -> {
                    if (error != null && error.getClass().getSimpleName().equals("ErrorResponseException") && error.getMessage().contains("50007")) {
                        logger.warn("Failed to send DM to user {}: 50007 Cannot send messages to this user", userId);
                    }
                });
            });
        } catch (NumberFormatException e) {
            logger.error("Invalid userId for direct_message: {}", userId);
            var embed = CommandErrorMessages.createErrorEmbed(CommandErrorType.INVALID_SNOWFLAKE);
        }
    }

    public static void sendChannelMessage(ResponseMessage respMsg) {
        String channelId = respMsg.channelId();
        if (channelId == null) {
            logger.error("No channelId provided for channel_message");
            return;
        }
        var jda = platformManager.getDiscordBotManager().getJda();
        var channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            logger.error("Channel with ID {} not found for channel_message", channelId);
            return;
        }
        var msgAction = respMsg.response() != null ? channel.sendMessage(respMsg.response()) : channel.sendMessage("");
        if (respMsg.embed() != null) {
            var embed = toJdaEmbed(respMsg.embed()).build();
            msgAction.setEmbeds(embed);
        }
        if (respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
            List<Button> jdaButtons = respMsg.buttons().stream()
                    .map(btn -> btn.style() == ButtonStyle.LINK ? Button.link(btn.url(), btn.label()) : Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label()).withDisabled(btn.disabled()))
                    .collect(Collectors.toList());
            msgAction.setActionRow(jdaButtons);
        }
        msgAction.queue(success -> {
            if (respMsg.requestId() != null && !respMsg.requestId().isEmpty()) {
                String messageId = success.getId();
                platformManager.setGlobalMessageLabel(respMsg.requestId(), channelId, messageId);
            }
        });
    }

    private static net.dv8tion.jda.api.EmbedBuilder toJdaEmbed(EmbedDefinition embedDef) {
        var embedBuilder = new EmbedBuilder();
        if (embedDef.title() != null) embedBuilder.setTitle(embedDef.title());
        if (embedDef.description() != null) embedBuilder.setDescription(embedDef.description());
        if (embedDef.color() != null) embedBuilder.setColor(new Color(embedDef.color()));
        if (embedDef.fields() != null) {
            for (var field : embedDef.fields()) {
                embedBuilder.addField(field.name(), field.value(), field.inline());
            }
        }
        return embedBuilder;
    }

    public static void editMessage(ResponseMessage respMsg) {
        String label = respMsg.requestId();
        if (respMsg.type() != null && respMsg.type().equals("edit_message")) {
            label = respMsg.response();
        }
        if (label == null) {
            return;
        }
        String[] ref = platformManager.getMessageReference(label);
        if (ref == null || ref.length != 2) {
            return;
        }
        String channelId = ref[0];
        String messageId = ref[1];
        var jda = platformManager.getDiscordBotManager().getJda();
        var channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        if (respMsg.embed() != null || (respMsg.buttons() != null && !respMsg.buttons().isEmpty())) {
            var action = channel.editMessageById(messageId, respMsg.response() != null ? respMsg.response() : "");
            if (respMsg.embed() != null) {
                var embed = toJdaEmbed(respMsg.embed()).build();
                action = action.setEmbeds(embed);
            }
            if (respMsg.buttons() != null && !respMsg.buttons().isEmpty()) {
                var jdaButtons = respMsg.buttons().stream()
                        .map(btn -> Button.of(getJdaButtonStyle(btn.style()), btn.customId(), btn.label()))
                        .collect(Collectors.toList());
                action = action.setActionRow(jdaButtons);
            }
            action.queue();
        } else {
            channel.editMessageById(messageId, respMsg.response() != null ? respMsg.response() : "").queue();
        }
    }

    public static void editComponent(ResponseMessage respMsg) {
        String label = respMsg.requestId();
        if (respMsg.type() != null && respMsg.type().equals("edit_component")) {
            label = respMsg.response();
        }
        if (label == null) {
            return;
        }
        String[] ref = platformManager.getMessageReference(label);
        if (ref == null || ref.length != 2) {
            return;
        }
        String channelId = ref[0];
        String messageId = ref[1];
        var jda = platformManager.getDiscordBotManager().getJda();
        var channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        JsonObject obj = new JsonParser().parse(respMsg.response()).getAsJsonObject();
        String componentId = obj.get("componentId").getAsString();
        String newLabel = obj.has("newLabel") ? obj.get("newLabel").getAsString() : null;
        String newStyle = obj.has("newStyle") ? obj.get("newStyle").getAsString() : null;
        Boolean disabled = obj.has("disabled") ? obj.get("disabled").getAsBoolean() : null;
        new MessageComponentFetcher(channel, messageId)
                .fetchAndApply(rows -> {
                    new ButtonEditor(componentId, newLabel, newStyle, disabled)
                            .edit(rows);
                    new MessageUpdater(channel, messageId, rows).update();
                });
    }

    public static void deleteMessage(ResponseMessage respMsg) {
        String label = respMsg.requestId();
        if (label == null) {
            logger.error("No label provided for delete_message");
            return;
        }
        try {
            Map<String, Object> properties = new HashMap<>();
            properties.put("label", label);
            properties.put("delete_all", respMsg.deleteAll());
            DeleteMessageAction action = new DeleteMessageAction(properties);
            action.execute(null).join();
        } catch (Exception e) {
            logger.error("Failed to delete message for label: {}", label, e);
        }
    }
    
    private static void handleConditionError(SlashCommandInteractionEvent event, String errorMessage, boolean ephemeral) {
        try {
            String[] parts = errorMessage.split(":", 3);
            if (parts.length < 2) {
                event.getHook().sendMessage("Invalid error format").setEphemeral(ephemeral).queue();
                return;
            }
            
            String errorType = parts[1];
            Map<String, String> placeholders = new HashMap<>();
            
            if (parts.length > 2) {
                String[] placeholderPairs = parts[2].split(",");
                for (String pair : placeholderPairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        placeholders.put(keyValue[0], keyValue[1]);
                    }
                }
            }
            
            CommandErrorType commandErrorType = parseErrorType(errorType);
            MessageEmbed embed = CommandErrorMessages.createErrorEmbed(commandErrorType, placeholders);
            
            event.getHook().sendMessageEmbeds(embed).setEphemeral(ephemeral).queue();
            
        } catch (Exception e) {
            logger.error("Error handling condition error: {}", e.getMessage(), e);
            event.getHook().sendMessage("Error processing condition").setEphemeral(ephemeral).queue();
        }
    }
    
    private static CommandErrorType parseErrorType(String errorType) {
        try {
            return CommandErrorType.valueOf(errorType);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown error type: {}, using SERVER_ERROR", errorType);
            return CommandErrorType.SERVER_ERROR;
        }
    }
}