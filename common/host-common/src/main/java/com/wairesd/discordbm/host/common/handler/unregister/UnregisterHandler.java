package com.wairesd.discordbm.host.common.handler.unregister;

import com.wairesd.discordbm.common.models.unregister.UnregisterMessage;
import com.wairesd.discordbm.host.common.config.configurators.Settings;
import com.wairesd.discordbm.host.common.network.NettyServer;
import com.wairesd.discordbm.common.utils.logging.PluginLogger;
import com.wairesd.discordbm.common.utils.logging.Slf4jPluginLogger;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.LoggerFactory;

import java.util.List;

public class UnregisterHandler {
    private static final PluginLogger logger = new Slf4jPluginLogger(LoggerFactory.getLogger("DiscordBM"));
    private final NettyServer nettyServer;

    public UnregisterHandler(NettyServer nettyServer) {
        this.nettyServer = nettyServer;
    }

    public void handleUnregister(ChannelHandlerContext ctx, UnregisterMessage unregMsg) {
        if (unregMsg.secret == null || !unregMsg.secret.equals(Settings.getSecretCode())) {
            ctx.writeAndFlush("Error: Invalid secret code");
            return;
        }

        String serverName = unregMsg.serverName;
        String commandName = unregMsg.commandName;

        List<NettyServer.ServerInfo> servers = nettyServer.getCommandToServers().get(commandName);
        if (servers != null) {
            servers.removeIf(serverInfo -> serverInfo.serverName().equals(serverName));
            if (servers.isEmpty()) {
                nettyServer.getCommandDefinitions().remove(commandName);
            }
        }

        if (Settings.isDebugCommandRegistrations()) {
            logger.warn("Unregistered command {} for server {}", commandName, serverName);
        }
    }
}
