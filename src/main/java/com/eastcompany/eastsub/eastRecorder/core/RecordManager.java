package com.eastcompany.eastsub.eastRecorder.core;

import com.eastcompany.eastsub.eastRecorder.frame.ReplayFrame;
import com.eastcompany.eastsub.eastRecorder.utils.ReplayFileManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RecordManager{

    private final EastRecorder plugin;
    private final ReplayFileManager fileManager;
    private final RecordEvents recordEvents;
    private final PacketListener recordPacketEvents;
    private final AddSpawnPacket addSpawnPacket = new AddSpawnPacket(this);
    private long startTime;
    private String currentFileName;
    public RecordStatus getRecordStatus() {return recordStatus;}

    private RecordStatus recordStatus;
    private PacketListenerCommon packetListenerCommon = null;
    public Map<Integer, UUID> getEntityIdToUuidMap() {
        return entityIdToUuidMap;
    }
    private final Map<Integer, UUID> entityIdToUuidMap = new ConcurrentHashMap<>();
    private final List<UUID> realEntityUuidList = new ArrayList<>();
    public List<UUID> getRealUuid(){return realEntityUuidList;}
    private final Set<Integer> playerID = ConcurrentHashMap.newKeySet();
    public Set<Block> getPlacedbefore() {
        return placedbefore;
    }

    private final Set<Block> placedbefore = new HashSet<>();
    RecordingSession recordingSession;

    public RecordManager(EastRecorder plugin) {
        this.plugin = plugin;
        this.fileManager = new ReplayFileManager(plugin);
        this.recordEvents = new RecordEvents(this);
        this.recordPacketEvents = new RecordPacketEvents(this);
    }

    public AddSpawnPacket getAddSpawnPacket() {
        return addSpawnPacket;
    }

    private boolean recording = false;

    public long getStartTime() {
        return startTime;
    }

    private final Queue<ReplayFrame> recordedFrames = new ConcurrentLinkedQueue<>();

    public void saveFrame(long elapsed, Integer entityID, PacketType.Play.Server type, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper) {
        if (!recording) return;

        Object buffer = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
        try {
            wrapper.setBuffer(buffer);
            wrapper.write();

            byte[] bytes = ByteBufHelper.copyBytes(buffer);
            UUID targetUUID = entityIdToUuidMap.get(entityID);

            if (targetUUID != null) {
                recordedFrames.add(new ReplayFrame(elapsed, targetUUID, type, bytes));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("フレーム保存中にエラーが発生しました。録画を強制停止します。");
            e.printStackTrace();
            stopRecording(); // エラー時に停止
        } finally {
            if (buffer != null) {
                ByteBufHelper.release(buffer);
            }
        }
    }

    /**
     * 録画を開始します。
     */
    public void startRecording(String fileName) {
        if (recording) return;


        try {
            this.recordStatus = new RecordStatus(this , plugin);;
            this.currentFileName = fileName;
            this.startTime = System.currentTimeMillis();
            this.recording = true;
            recordingSession = new RecordingSession(this);
            recordingSession.startTickLoop(plugin);
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (org.bukkit.entity.Entity entity : world.getEntities()) {
                    addSpawnPacket.addSpawnPacket(entity);
                }
            }

            this.packetListenerCommon = PacketEvents.getAPI().getEventManager().registerListener(
                    recordPacketEvents,
                    PacketListenerPriority.NORMAL
            );

            Bukkit.getServer().getPluginManager().registerEvents(recordEvents, plugin);

            Bukkit.broadcastMessage("§a[EastRecorder] 録画を開始しました: §f" + fileName);

        } catch (Exception e) {
            plugin.getLogger().severe("録画の開始に失敗しました。");
            e.printStackTrace();
            cleanup(); // 失敗時の後片付け
        }
    }

    /**
     * 録画を停止し、ファイルに書き出します。
     */
    public void stopRecording() {
        if (!recording) return;
        this.recording = false;

        try {
            String savedName = this.currentFileName;
            List<ReplayFrame> filteredFrames = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();

            // 1. データのフィルタリング（重複削除）
            ReplayFrame frame;
            while ((frame = recordedFrames.poll()) != null) {
                // ブロック変更とチャットはフィルタリングせずに全て残す
                if (frame.packetType() == PacketType.Play.Server.BLOCK_CHANGE ||
                        frame.packetType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
                    filteredFrames.add(frame);
                    continue;
                }

                // それ以外はTick単位で重複を排除
                long tick = frame.timestamp() / 50;
                String key = tick + ":" + frame.targetUuid() + ":" + frame.packetType().getName();
                if (seenKeys.add(key)) {
                    filteredFrames.add(frame);
                }
            }
            if (!filteredFrames.isEmpty()) {
                filteredFrames.sort(Comparator.comparingLong(ReplayFrame::timestamp));

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    fileManager.saveToFile(savedName, new ConcurrentLinkedQueue<>(filteredFrames));

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Bukkit.broadcastMessage(String.format("§b[EastRecorder] §f%s §7(%d フレーム保存完了)",
                                savedName, filteredFrames.size()));
                    });
                });
            }
        } catch (Exception e) {
            plugin.getLogger().severe("録画停止処理中にエラーが発生しました。");
            e.printStackTrace();
        } finally {
            cleanup(); // リソース解放
        }
    }

    public void addIfNotEmpty(List<Equipment> list, com.github.retrooper.packetevents.protocol.player.EquipmentSlot slot, org.bukkit.inventory.ItemStack item) {
        if (item != null && item.getType() != org.bukkit.Material.AIR) {
            list.add(new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(item)));
        }
    }
    /**
     * リプレイファイルを読み込みます。
     */
    public List<ReplayFrame> loadFromFile(String fileName) {
        return fileManager.loadFromFile(fileName);
    }

    public void saveChatFrame(long elapsed, UUID playerUuid, String message) {
        byte[] chatBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        recordedFrames.add(new ReplayFrame(
                elapsed,
                playerUuid,
                PacketType.Play.Server.SYSTEM_CHAT_MESSAGE,
                chatBytes
        ));
    }

    public void savePlace(long elapsed, UUID playerUuid, Location location, BlockData blockData) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeUTF(location.getWorld().getName());

            dos.writeDouble(location.getX());
            dos.writeDouble(location.getY());
            dos.writeDouble(location.getZ());
            dos.writeFloat(location.getYaw());
            dos.writeFloat(location.getPitch());

            // 2. BlockDataの書き込み
            dos.writeUTF(blockData.getAsString());

            byte[] dataBytes = bos.toByteArray();

            recordedFrames.add(new ReplayFrame(
                    elapsed,
                    playerUuid,
                    PacketType.Play.Server.BLOCK_CHANGE,
                    dataBytes
            ));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Set<Integer> getPlayerID() {
        return playerID;
    }

    public boolean isNotRecording() {
        return !recording;
    }

    public List<String> getReplayFileList() {
        return fileManager.getReplayFileList();
    }

    private void cleanup() {
        entityIdToUuidMap.clear();
        playerID.clear();
        recordedFrames.clear();
        realEntityUuidList.clear();
        placedbefore.clear();
        if (recordingSession != null) {
            recordingSession = null;
        }

        if (packetListenerCommon != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListenerCommon);
            packetListenerCommon = null;
        }

        if (this.recordStatus != null) {
            recordStatus = null;
        }

        org.bukkit.event.HandlerList.unregisterAll(recordEvents);

        this.currentFileName = null;
        this.startTime = 0L;
        this.recording = false;
    }
}