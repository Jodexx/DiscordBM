package com.wairesd.discordbm.host.common.network;

import com.wairesd.discordbm.common.network.codec.ByteBufDecoder;
import com.wairesd.discordbm.common.network.codec.ByteBufEncoder;
import com.wairesd.discordbm.common.models.placeholders.response.PlaceholdersResponse;
import com.wairesd.discordbm.common.utils.logging.PluginLogger;
import com.wairesd.discordbm.common.utils.logging.Slf4jPluginLogger;
import com.wairesd.discordbm.host.common.models.command.CommandRegistrationService;
import com.wairesd.discordbm.host.common.config.configurators.Settings;
import com.wairesd.discordbm.host.common.database.Database;
import com.wairesd.discordbm.host.common.models.command.CommandDefinition;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import net.dv8tion.jda.api.JDA;
import org.slf4j.LoggerFactory;
import com.wairesd.discordbm.host.common.utils.ClientInfo;

import java.net.BindException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NettyServer {
    private static final PluginLogger logger = new Slf4jPluginLogger(LoggerFactory.getLogger("DiscordBM"));
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final Map<String, CommandDefinition> commandDefinitions = new HashMap<>();
    private final Map<String, List<ServerInfo>> commandToServers = new HashMap<>();
    private final Map<Channel, String> channelToServerName = new ConcurrentHashMap<>();
    private JDA jda;
    private final int port = Settings.getNettyPort();
    private final Database dbManager;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> canHandleFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<PlaceholdersResponse>> placeholderFutures = new ConcurrentHashMap<>();
    private final String ip = Settings.getNettyIp();
    private final CommandRegistrationService commandRegistrationService;
    private final Map<String, String> commandToPlugin = new ConcurrentHashMap<>();
    private final Map<Channel, Long> channelConnectTime = new ConcurrentHashMap<>();

    public NettyServer(Database dbManager) {
        this.dbManager = dbManager;
        this.commandRegistrationService = new CommandRegistrationService(null, this);
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2 * Runtime.getRuntime().availableProcessors());
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2));
                            ch.pipeline().addLast("byteBufDecoder", new ByteBufDecoder());
                            ch.pipeline().addLast("frameEncoder", new LengthFieldPrepender(2));
                            ch.pipeline().addLast("byteBufEncoder", new ByteBufEncoder());
                            ch.pipeline().addLast("handler", new NettyServerHandler(NettyServer.this, jda, dbManager));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_RCVBUF, 128 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 128 * 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            ChannelFuture future = (ip == null || ip.isEmpty())
                    ? bootstrap.bind(port).sync()
                    : bootstrap.bind(ip, port).sync();

            serverChannel = future.channel();

            if (Settings.isDebugNettyStart()) {
                logger.info("Netty server started on {}:{}", ip == null || ip.isEmpty() ? "0.0.0.0" : ip, port);
            }

            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            if (Settings.isDebugErrors()) {
                logger.error("Netty server interrupted", e);
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Throwable cause = e instanceof BindException ? e : e.getCause();
            if (cause instanceof BindException) {
                logger.error("Port {} is already in use! Please change netty.port in settings.yml or stop the other application.", port);
            } else if (Settings.isDebugErrors()) {
                logger.error("Error starting Netty server: {}", e.getMessage(), e);
            } else {
                logger.error("Error starting Netty server: {}", e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    public Channel getChannelByServerName(String serverName) {
        for (Map.Entry<Channel, String> entry : channelToServerName.entrySet()) {
            if (entry.getValue().equals(serverName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        NettyServerHandler.shutdown();
        if (Settings.isDebugConnections()) {
            logger.info("Netty server shutdown complete");
        }
    }

    public void sendMessage(Channel channel, String message) {
        if (channel != null) {
            if (channel.isActive()) {
                channel.writeAndFlush(message);
            }
        }
    }

    public void removeServer(Channel channel) {
        List<String> commandsToRemove = new ArrayList<>();
        for (var entry : commandToServers.entrySet()) {
            entry.getValue().removeIf(serverInfo -> serverInfo.channel() == channel);
            if (entry.getValue().isEmpty()) {
                commandsToRemove.add(entry.getKey());
            }
        }
        for (String cmd : commandsToRemove) {
            commandToServers.remove(cmd);
            commandDefinitions.remove(cmd);
            commandToPlugin.remove(cmd);
            if (Settings.isDebugCommandRegistrations()) {
                logger.info("Removed command {} as no servers remain", cmd);
            }
        }
        channelToServerName.remove(channel);
        channelConnectTime.remove(channel);
    }

    public Map<Channel, String> getChannelToServerName() {
        return channelToServerName;
    }

    public ConcurrentHashMap<String, CompletableFuture<Boolean>> getCanHandleFutures() {
        return this.canHandleFutures;
    }

    public ConcurrentHashMap<String, CompletableFuture<PlaceholdersResponse>> getPlaceholderFutures() {
        return this.placeholderFutures;
    }

    public void setServerName(Channel channel, String serverName) {
        channelToServerName.put(channel, serverName);
    }

    public String getServerName(Channel channel) {
        return channelToServerName.get(channel);
    }

    public Map<String, List<ServerInfo>> getCommandToServers() {
        return commandToServers;
    }

    public List<ServerInfo> getServersForCommand(String command) {
        return commandToServers.getOrDefault(command, new ArrayList<>());
    }

    public Map<String, CommandDefinition> getCommandDefinitions() {
        return commandDefinitions;
    }

    public record ServerInfo(String serverName, Channel channel) {
    }

    public void setJda(JDA jda) {
        this.jda = jda;
        this.commandRegistrationService.setJda(jda);
    }

    public CommandRegistrationService getCommandRegistrationService() {
        return commandRegistrationService;
    }

    public JDA getJda() {
        return this.jda;
    }

    public void registerAddonCommand(String commandName, String pluginName) {
        commandToPlugin.put(commandName, pluginName);
    }

    public String getPluginForCommand(String commandName) {
        return commandToPlugin.getOrDefault(commandName, "Unknown");
    }

    public Map<String, String> getCommandToPlugin() {
        return commandToPlugin;
    }

    public void setConnectTime(Channel channel, long time) {
        channelConnectTime.put(channel, time);
    }

    public List<ClientInfo> getActiveClientsInfo() {
        return ClientInfo.getActiveClientsInfo(channelToServerName, channelConnectTime);
    }
}