package com.eastcompany.eastsub.eastRecorder.command;

import com.eastcompany.eastsub.eastRecorder.core.EastRecorder;
import com.eastcompany.eastsub.eastRecorder.frame.ReplayFrame;
import com.eastcompany.eastsub.eastRecorder.play.ReplaySession;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RecordCommand {

    private final EastRecorder plugin;

    public RecordCommand(EastRecorder plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        // 基本となる /record コマンド
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("record")
                .requires(stack -> stack.getSender().hasPermission("eastrecorder.admin"));

        // --- /record start ---
        node.then(Commands.literal("startrecord")
                .then(Commands.argument("filename", StringArgumentType.string())
                        .executes(ctx -> handleStart(ctx.getSource(), StringArgumentType.getString(ctx, "filename")))
                )
                .executes(ctx -> handleStart(ctx.getSource(), "replay_" + System.currentTimeMillis()))
        );

        // --- /record stop ---
        node.then(Commands.literal("stop")
                .executes(ctx -> handleStop(ctx.getSource()))
        );

        // --- /record list ---
        node.then(Commands.literal("list")
                .executes(ctx -> handleList(ctx.getSource()))
        );

        // --- /record play <filename> [speed] ---
        node.then(Commands.literal("play")
                .then(Commands.argument("filename", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            List<String> files = plugin.getRecordManager().getReplayFileList();
                            String remaining = builder.getRemaining().toLowerCase();
                            for (String file : files) {
                                if (file.toLowerCase().startsWith(remaining)) {
                                    builder.suggest(file);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> handlePlay(ctx.getSource(), StringArgumentType.getString(ctx, "filename"), 1.0))
                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0, 50.0)) // 0を許容
                                .executes(ctx -> handlePlay(ctx.getSource(), StringArgumentType.getString(ctx, "filename"), DoubleArgumentType.getDouble(ctx, "speed")))
                        )
                )
        );

        // --- /record speed <value> ---
        node.then(Commands.literal("speed")
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 50.0))
                        .executes(ctx -> {
                            double speed = DoubleArgumentType.getDouble(ctx, "value");
                            ReplaySession session = plugin.getActiveSession();
                            if (session == null) {
                                ctx.getSource().getSender().sendMessage("§c現在実行中のリプレイセッションがありません。");
                                return 0;
                            }
                            session.setPlaybackSpeed(speed);
                            ctx.getSource().getSender().sendMessage(String.format("§a再生速度を §f%.1fx §aに変更しました。", speed));
                            return Command.SINGLE_SUCCESS;
                        })
                )
        );
        node.then(Commands.literal("cancel")
                .executes(ctx -> {
                    CommandSourceStack stack = ctx.getSource();
                    boolean acted = false;

                    // 1. 再生中なら停止
                    ReplaySession session = plugin.getActiveSession();
                    if (session != null) {
                        session.stop();
                        stack.getSender().sendMessage("§e[EastRecorder] 再生セッションを強制終了しました。");
                        acted = true;
                    }

                    // 2. 録画中なら停止（保存せずに破棄したい場合は cleanup を直接呼ぶのもアリですが、通常はstopで安全に閉じるのが無難です）
                    if (!plugin.getRecordManager().isNotRecording()) {
                        plugin.getRecordManager().stopRecording();
                        stack.getSender().sendMessage("§e[EastRecorder] 進行中の録画を停止しました。");
                        acted = true;
                    }

                    if (!acted) {
                        stack.getSender().sendMessage("§7実行中の録画や再生はありません。");
                    }

                    return Command.SINGLE_SUCCESS;
                })
        );
        // --- /record spawn ---
        node.then(Commands.literal("spawn")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        spawnNotchNPC(player, 2000, player.getLocation());
                        return 1;
                    }
                    return 0;
                })
        );

        // 最後に一括登録
        commands.register(node.build(), "EastRecorder main command", List.of());
    }

    private int handleStart(CommandSourceStack stack, String fileName) {
        // 録画中かどうかチェック
        if (!plugin.getRecordManager().isNotRecording()) {
            stack.getSender().sendMessage("§c[EastRecorder] すでに録画は開始されています。");
            return 0; // コマンド失敗として0を返す（または定義に合わせて-1）
        }

        if (!(stack.getSender() instanceof Player player)) return 0;
        try {
            plugin.getRecordManager().startRecording(fileName);
            player.sendMessage("§a録画を開始しました: §f" + fileName);
        } catch (Exception e) {
            e.printStackTrace();
            plugin.getRecordManager().stopRecording();
        }
        return Command.SINGLE_SUCCESS;
    }

    private int handleStop(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player player)) return 0;
        plugin.getRecordManager().stopRecording();
        player.sendMessage("§e録画を停止し、保存処理を開始しました。");
        return Command.SINGLE_SUCCESS;
    }

    private int handleList(CommandSourceStack stack) {
        if (!(stack.getSender() instanceof Player player)) return 0;
        List<String> files = plugin.getRecordManager().getReplayFileList();
        if (files.isEmpty()) {
            player.sendMessage("§c保存された録画データが見つかりません。");
            return 1;
        }
        player.sendMessage("§b--- 保存済みリプレイ一覧 ---");
        for (String fileName : files) {
            player.sendMessage("§f・ §e" + fileName);
        }
        player.sendMessage("§b----------------------------");
        return Command.SINGLE_SUCCESS;
    }

    private int handlePlay(CommandSourceStack stack, String fileName, double speed) {
        if (!(stack.getSender() instanceof Player player)) return 0;
        if (plugin.getActiveSession() != null) {
            plugin.getActiveSession().stop();
        }
        List<ReplayFrame> frames = plugin.getRecordManager().loadFromFile(fileName);
        if (frames.isEmpty()) {
            player.sendMessage("§cエラー: データが見つかりません。");
            return 0;
        }
        player.sendMessage(String.format("§bリプレイ §f%s §bを速度 §e%.1fx §bで開始します...", fileName, speed));
        ReplaySession session = new ReplaySession(speed, frames, player, plugin);
        plugin.setActiveSession(session);
        session.start();
        return 1;
    }

    public void spawnNotchNPC(Player viewer, int entityID, Location loc) {
        // 1. NotchのUUID
        UUID npcUUID = UUID.fromString("ba70a6e4-357e-4f17-a382-3f0881685e89");

        com.destroystokyo.paper.profile.PlayerProfile bukkitProfile = Bukkit.createProfile(npcUUID);

        bukkitProfile.complete();

        // 4. PacketEvents側のUserProfileを作成
        UserProfile packetProfile = new UserProfile(npcUUID, "スルメ");

        // 5. BukkitのProfileからプロパティを抜き出してPacketEvents側に移す
        for (com.destroystokyo.paper.profile.ProfileProperty property : bukkitProfile.getProperties()) {
            if (property.getName().equals("textures")) {
                // ここで skinValue と skinSignature が手に入る！
                packetProfile.getTextureProperties().add(
                        new TextureProperty("textures", property.getValue(), property.getSignature())
                );
            }
        }

        // --- 以下、パケット送信処理 ---
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);

        // PlayerInfoUpdateパケット (リストに追加)
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                packetProfile, true, 10, GameMode.SURVIVAL, null, null
        );
        user.sendPacket(new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                List.of(info)
        ));

        // SpawnEntityパケット (実体を出現させる)
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityID, Optional.of(npcUUID), EntityTypes.PLAYER,
                new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                loc.getPitch(), loc.getYaw(), loc.getYaw(), 0, Optional.empty()
        );
        user.sendPacket(spawn);
    }
}