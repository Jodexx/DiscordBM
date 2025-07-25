package com.wairesd.discordbm.host.common.commandbuilder.interaction.placeholders;

import com.wairesd.discordbm.common.utils.logging.PluginLogger;
import com.wairesd.discordbm.common.utils.logging.Slf4jPluginLogger;
import com.wairesd.discordbm.host.common.discord.DiscordBMHPlatformManager;
import com.wairesd.discordbm.host.common.commandbuilder.core.channel.ChannelFinder;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.context.Context;
import com.wairesd.discordbm.host.common.commandbuilder.utils.PlaceholderUtils;
import com.wairesd.discordbm.host.common.config.configurators.Settings;
import com.wairesd.discordbm.host.common.network.NettyServer;
import io.netty.channel.Channel;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PlaceholdersResolver {
    private static final PluginLogger logger = new Slf4jPluginLogger(LoggerFactory.getLogger("DiscordBM"));
    private final DiscordBMHPlatformManager platformManager;
    private final NettyServer nettyServer;
    private final ChannelFinder channelFinder;
    private final PlaceholderRequestSender requestSender;

    public PlaceholdersResolver(DiscordBMHPlatformManager platformManager) {
        this.platformManager = platformManager;
        this.nettyServer = platformManager.getNettyServer();
        this.channelFinder = new ChannelFinder(nettyServer);
        this.requestSender = new PlaceholderRequestSender(nettyServer);
    }

    public CompletableFuture<Void> resolvePlaceholders(String template, String playerName, Context context) {
        if (context == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (Settings.isDebugResolvedMessages()) {
            logger.info("Resolving placeholders for template: {} with player: {}", template, playerName);
        }

        List<String> placeholders = PlaceholderUtils.extractPlaceholders(template);
        if (placeholders.isEmpty()) {
            context.setResolvedMessage(template);
            return CompletableFuture.completedFuture(null);
        }

        if (nettyServer == null) {
            context.setResolvedMessage("NettyServer is not initialized.");
            return CompletableFuture.completedFuture(null);
        }

        var proxy = platformManager.getVelocityProxy();
        var playerOpt = proxy.getPlayer(playerName);

        if (playerOpt.isPresent()) {
            var player = playerOpt.get();
            var serverOpt = player.getCurrentServer();
            if (serverOpt.isEmpty()) {
                context.setResolvedMessage("The player is online, but not connected to the server.");
                return CompletableFuture.completedFuture(null);
            }
            String serverName = serverOpt.get().getServerInfo().getName();
            Channel channel = channelFinder.findChannelForServer(serverName);
            if (channel == null) {
                context.setResolvedMessage("The server is not connected.");
                return CompletableFuture.completedFuture(null);
            }
            return requestSender.sendGetPlaceholdersRequest(channel, playerName, placeholders)
                    .thenAccept(values -> {
                        String resolved = PlaceholderUtils.substitutePlaceholders(template, values);
                        if (Settings.isDebugResolvedMessages()) {
                            logger.info("Resolved message: {}", resolved);
                        }
                        context.setResolvedMessage(resolved);
                        context.setResolvedPlaceholders(values);
                    }).exceptionally(ex -> {
                        context.setResolvedMessage("Error getting placeholders: " + ex.getMessage());
                        return null;
                    });
        } else {
            List<Channel> channels = new ArrayList<>(nettyServer.getChannelToServerName().keySet());
            Map<String, CompletableFuture<Boolean>> canHandleFutures = new HashMap<>();

            for (Channel channel : channels) {
                String serverName = nettyServer.getServerName(channel);
                canHandleFutures.put(serverName,
                        requestSender.sendCanHandlePlaceholdersRequest(channel, playerName, placeholders));
            }

            CompletableFuture<Void> all = CompletableFuture.allOf(canHandleFutures.values().toArray(new CompletableFuture[0]));

            return all.orTimeout(5, TimeUnit.SECONDS).thenCompose(v -> {
                List<String> capableServers = new ArrayList<>();
                for (var entry : canHandleFutures.entrySet()) {
                    try {
                        if (entry.getValue().get()) {
                            capableServers.add(entry.getKey());
                        }
                    } catch (Exception ignored) {}
                }
                if (capableServers.isEmpty()) {
                    context.setResolvedMessage("No server can handle the required placeholders.");
                    return CompletableFuture.completedFuture(null);
                }
                String serverName = capableServers.get(0);
                Channel channel = channelFinder.findChannelForServer(serverName);
                if (channel == null) {
                    context.setResolvedMessage("The selected server is not connected.");
                    return CompletableFuture.completedFuture(null);
                }
                return requestSender.sendGetPlaceholdersRequest(channel, playerName, placeholders)
                        .thenAccept(values -> {
                            String resolved = PlaceholderUtils.substitutePlaceholders(template, values);
                            if (Settings.isDebugResolvedMessages()) {
                                logger.info("Resolved message: {}", resolved);
                            }
                            context.setResolvedMessage(resolved);
                            context.setResolvedPlaceholders(values);
                        }).exceptionally(ex -> {
                            context.setResolvedMessage("Error getting placeholders: " + ex.getMessage());
                            return null;
                        });
            });
        }
    }
}
