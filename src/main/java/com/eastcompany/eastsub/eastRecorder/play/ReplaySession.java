package com.eastcompany.eastsub.eastRecorder.play;

import com.eastcompany.eastsub.eastRecorder.core.EastRecorder;
import com.eastcompany.eastsub.eastRecorder.frame.ReplayFrame;
import com.eastcompany.eastsub.eastRecorder.utils.PacketRewriter;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReplaySession {
    private final List<ReplayFrame> frames;
    private final Player viewer;
    private final EastRecorder plugin;
    private final NPCManager npcManager;
    private BukkitTask currentTask;
    private double playbackSpeed = 1;
    private long playbackStartTime;
    private final ReplayBlockHandler blockHandler;
    private final java.util.Map<UUID, Location> lastReplayLocations = new java.util.HashMap<>();
    private final ReplayMergeBuffer replayMergeBuffer;


    public ReplaySession(double speed, List<ReplayFrame> frames, Player viewer, EastRecorder plugin) {
        this.frames = frames;
        this.viewer = viewer;
        this.plugin = plugin;
        this.npcManager = new NPCManager();
        blockHandler = new ReplayBlockHandler();
        this.playbackSpeed = speed;
        this.replayMergeBuffer = new ReplayMergeBuffer();
        Bukkit.getLogger().info("size " + frames.size() + " ---");
    }

    public void start() {
        this.currentTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!viewer.isOnline()) return;
                    runReplayLoop();
                } catch (Exception e) {
                    handleCriticalError("初期化中にエラーが発生しました", e);
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    public void stop() {
        if (currentTask != null) {
            currentTask.cancel();
            currentTask = null;
        }
        cleanup();

        if (plugin.getActiveSession() == this) {
            plugin.setActiveSession(null);
        }
    }

    public void setPlaybackSpeed(double speed) {
        if (speed <= 0) return;
        this.playbackSpeed = speed;
    }

    private void runReplayLoop() {
        this.playbackStartTime = System.currentTimeMillis();
        cleanup();

        this.currentTask = new BukkitRunnable() {
            int frameIndex = 0;

            @Override
            public void run() {
                try {

                    if (!viewer.isOnline() || frameIndex >= frames.size()) {
                        cleanup();
                        this.cancel();
                        return;
                    }

                    long realElapsed = System.currentTimeMillis() - playbackStartTime;
                    long virtualElapsed = (long) (realElapsed * playbackSpeed);

                    // --- 1. このTickで処理すべきフレームを抽出 ---
                    // LinkedHashMap で時系列を維持しつつ重複排除
                    java.util.Map<String, ReplayFrame> frameMap = new java.util.LinkedHashMap<>();

                    while (frameIndex < frames.size() && frames.get(frameIndex).timestamp() <= virtualElapsed) {
                        ReplayFrame frame = frames.get(frameIndex);
                        PacketType.Play.Server type = frame.packetType();

                        String key;
                        // 除外リスト：これらは重複排除せず、すべて処理する
                        if (type == PacketType.Play.Server.BLOCK_CHANGE ||
                                type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {

                            // 常にユニークなキーにすることで、Mapの上書きを防ぐ
                            key = java.util.UUID.randomUUID().toString();
                        } else {
                            // 同一エンティティ かつ 同一パケットタイプなら上書き
                            key = frame.targetUuid().toString() + "-" + type.getName();
                        }

                        frameMap.put(key, frame);
                        frameIndex++;
                    }

                    // --- 2. 抽出されたフレームを一括処理 ---
                    if (!frameMap.isEmpty()) {
                        processFrameBatch(new java.util.ArrayList<>(frameMap.values()));
                    }

                } catch (Exception e) {
                    handleCriticalError("リプレイ・メインループ内でエラーが発生しました", e);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void processFrameBatch(List<ReplayFrame> batch) {
        for (ReplayFrame frame : batch) {
            processAndSendPacket(frame);
        }
    }

    private void processAndSendPacket(ReplayFrame frame) {
        try {
            UUID originalUuid = frame.targetUuid();
            PacketType.Play.Server type = frame.packetType();

            if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                blockHandler.process(frame);
                return;
            }

            if (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
                String message = new String(frame.data(), java.nio.charset.StandardCharsets.UTF_8);

                for(Player player : Bukkit.getOnlinePlayers()){
                    player.sendMessage(message);
                }
                return; // パケットデコード処理に行かせない
            }

            // --- 特別なパケットの個別処理 ---
            if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
                PacketWrapper<?> wrapper = PacketRewriter.getWrapperInstance(type);
                if (wrapper instanceof WrapperPlayServerPlayerInfoUpdate infoUpdate) {
                    decode(wrapper, frame.data());
                    npcManager.handlePlayerInfoUpdate(originalUuid, infoUpdate);
                    return;
                }
            }

            if (type == PacketType.Play.Server.ENTITY_METADATA) {
                PacketWrapper<?> wrapper = PacketRewriter.getWrapperInstance(type);
                if (wrapper instanceof WrapperPlayServerEntityMetadata infoUpdate) {
                    decode(infoUpdate, frame.data());
                    npcManager.handleMetaDataUpdate(originalUuid, infoUpdate);
                    return;
                }
            }

            if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                PacketWrapper<?> wrapper = PacketRewriter.getWrapperInstance(type);
                if (wrapper instanceof WrapperPlayServerEntityEquipment equipment) {
                    decode(equipment, frame.data());
                    npcManager.handleEntityEquipment(originalUuid, equipment);
                    return;
                }
            }

            if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                PacketWrapper<?> rewrittenWrapper = PacketRewriter.getWrapperInstance(type);
                if (rewrittenWrapper instanceof WrapperPlayServerSpawnEntity spawn) {
                    decode(spawn, frame.data());
                    npcManager.spawnEntity(originalUuid, spawn);
                    return;
                }
            }

            // --- 汎用的なID書き換えと送信 ---
            Integer currentNpcId = npcManager.getNpcId(originalUuid);
            if (currentNpcId == null) return;

            PacketWrapper<?> rewrittenWrapper = PacketRewriter.rewriteToAnyIdWrapper(type, frame.data(), currentNpcId);

            if (rewrittenWrapper != null) {
                if (rewrittenWrapper instanceof WrapperPlayServerEntityTeleport tp) {

                    PacketWrapper<?> out = getOptimizedMovePacket(originalUuid,npcManager.getNpcId(originalUuid),tp);

                    broadcastPacket(out);
                    return;
                }

                //logPacketDebug(type, rewrittenWrapper, currentNpcId);
                broadcastPacket(rewrittenWrapper);
            }
        } catch (Exception e) {
            handleCriticalError("パケットの処理プロセスで例外が発生しました: " + frame.packetType().getName(), e);
        }
    }

    private void broadcastPacket(com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        try {
            if (packet.getBuffer() != null) {
                packet.resetBuffer();
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                packet.setClientVersion(PacketEvents.getAPI().getPlayerManager().getClientVersion(player));
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            }
        } catch (Exception e) {
            handleCriticalError("パケットの送信(broadcast)に失敗しました", e);
        }
    }

    private void logPacketDebug(PacketType.Play.Server type, PacketWrapper<?> wrapper, int npcId) {
        try {
            String detail = "N/A";
            if (wrapper instanceof WrapperPlayServerEntityTeleport w) {
                detail = String.format("Pos: %.2f, %.2f, %.2f", w.getPosition().x, w.getPosition().y, w.getPosition().z);
            } else if (wrapper instanceof WrapperPlayServerEntityRelativeMove w) {
                detail = String.format("Delta: %.2f, %.2f, %.2f", w.getDeltaX(), w.getDeltaY(), w.getDeltaZ());
            } else if (wrapper instanceof WrapperPlayServerEntityRotation w) {
                detail = String.format("Rot: Yaw:%.2f, Pitch:%.2f", w.getYaw(), w.getPitch());
            } else if (wrapper instanceof WrapperPlayServerEntityRelativeMoveAndRotation w) {
                detail = String.format("MoveRot: Delta(%.2f, %.2f, %.2f) Rot(Yaw:%.2f, Pitch:%.2f)",
                        w.getDeltaX(), w.getDeltaY(), w.getDeltaZ(), w.getYaw(), w.getPitch());
            } else if (wrapper instanceof WrapperPlayServerEntityHeadLook w) {
                detail = String.format("HeadLook: Yaw:%.2f", w.getHeadYaw());
            } else if (wrapper instanceof WrapperPlayServerSpawnEntity w) {
                detail = String.format("SPAWN: Type:%s", w.getEntityType());
            } else if (wrapper instanceof WrapperPlayServerEntityStatus w) {
                detail = "StatusID: " + w.getEntityId();
            }

            plugin.getLogger().info(String.format("[REPLAY DEBUG] Type: %s | NPC_ID: %d | %s",
                    type.getName(), npcId, detail));
        } catch (Exception e) {
            // デバッグログ出力自体の失敗でシステムを止めたくないので、例外は握りつぶさず標準出力のみ
            plugin.getLogger().warning("デバッグログの生成中に軽微なエラーが発生しました");
        }
    }

    private void decode(com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper, byte[] data) {
        Object buf = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
        try {
            ByteBufHelper.writeBytes(buf, data);
            wrapper.setBuffer(buf);
            wrapper.read();
        } catch (Exception e) {
            handleCriticalError("パケットのデコードに失敗しました: " + wrapper.getClass().getSimpleName(), e);
        } finally {
            ByteBufHelper.release(buf);
        }
    }

    private void cleanup() {
        try {
            blockHandler.restoreAll();
            npcManager.cleanup();
            lastReplayLocations.clear();
        } catch (Exception e) {
            plugin.getLogger().severe("クリーンアップ中にエラーが発生しました: " + e.getMessage());
        }
    }

    private void handleCriticalError(String message, Exception e) {
        plugin.getLogger().severe("[ReplayError] " + message);
        e.printStackTrace();
        if (viewer.isOnline()) {
            viewer.sendMessage("§c[EastRecorder] 致命的なエラーが発生したため、リプレイを停止しました。");
            viewer.sendMessage("§7エラー内容: " + message);
        }
        stop();
    }

    private PacketWrapper<?> getOptimizedMovePacket(UUID uuid, int npcId, WrapperPlayServerEntityTeleport tp) {
        Location currentLoc = new Location(viewer.getWorld(), tp.getPosition().x, tp.getPosition().y, tp.getPosition().z, tp.getYaw(), tp.getPitch());
        Location lastLoc = lastReplayLocations.get(uuid);

        if(lastLoc == null){
            lastReplayLocations.put(uuid,currentLoc);
            return tp;
        }

        PacketWrapper<?> resultPacket;

        if (lastLoc.getWorld().equals(currentLoc.getWorld())) {
            double dx = currentLoc.getX() - lastLoc.getX();
            double dy = currentLoc.getY() - lastLoc.getY();
            double dz = currentLoc.getZ() - lastLoc.getZ();

            double distanceSq = dx * dx + dy * dy + dz * dz;

            // 8ブロック以内、かつ移動がある場合は RelativeMoveAndRotation
            // 8ブロック以上、または全く動いていない場合は Teleport (同期ズレ防止)
            if (distanceSq > 0.0001 && distanceSq < 64.0) {
                resultPacket = new WrapperPlayServerEntityRelativeMoveAndRotation(
                        npcId, dx, dy, dz, currentLoc.getYaw(), currentLoc.getPitch(), tp.isOnGround()
                );
            } else {
                npcManager.respawnAt(npcId,tp.getPosition(),tp.getYaw(),tp.getPitch());
                tp.setEntityId(npcManager.getNpcId(uuid));
                resultPacket = tp;
            }
        } else {
            // 初回、またはワールドが違う場合は強制的に Teleport
            tp.setEntityId(npcId);
            resultPacket = tp;
        }

        // 次回比較用に今回の位置を保存
        lastReplayLocations.put(uuid, currentLoc.clone());
        return resultPacket;
    }
}