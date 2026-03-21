package com.eastcompany.eastsub.eastRecorder.core;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.*;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecordEvents implements Listener {
    private final RecordManager recordManager;

    public RecordEvents(RecordManager recordManager) {
        this.recordManager = recordManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (recordManager.isNotRecording()) return;
        Bukkit.getScheduler().runTask(EastRecorder.getInstance(), () -> recordPlayerEquipment(event.getPlayer()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (recordManager.isNotRecording()) return;
        recordManager.getAddSpawnPacket().addSpawnPacket(event.getPlayer());
    }

    @EventHandler
    public void onSpawnEvent(EntitySpawnEvent event) {
        if (recordManager.isNotRecording()) return;
        recordManager.getAddSpawnPacket().addSpawnPacket(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (recordManager.isNotRecording()) return;
        recordDestroy(event.getPlayer().getEntityId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (recordManager.isNotRecording()) return;
        Entity entity = event.getEntity();
        recordDestroy(entity.getEntityId());
    }

    private void recordDestroy(int entityId) {
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
        recordManager.saveFrame(elapsed, entityId, PacketType.Play.Server.DESTROY_ENTITIES, destroy);
        recordManager.getEntityIdToUuidMap().remove(entityId);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (recordManager.isNotRecording()) return;
        recordTeleport(event.getPlayer(), event.getRespawnLocation());
    }

    public void clearProcessedBlocks() {
        this.placedbefore.clear();
    }

    Set<Block> placedbefore = new HashSet<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (recordManager.isNotRecording()) return;

        BlockState replacedState = event.getBlockReplacedState();
        Location loc = replacedState.getLocation();

        if(!placedbefore.contains(loc.getBlock())) {
            recordManager.savePlace(0, event.getPlayer().getUniqueId(), loc, event.getBlockReplacedState().getBlockData());
        }

        placedbefore.add(loc.getBlock());

        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        recordManager.savePlace(elapsed, event.getPlayer().getUniqueId(), loc, event.getBlockPlaced().getBlockData());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (recordManager.isNotRecording()) return;

        Block block = event.getBlock();
        Location loc = block.getLocation();

        if (!placedbefore.contains(block)) {
            recordManager.savePlace(0, event.getEntity().getUniqueId(), loc, block.getBlockData());
            placedbefore.add(block);
        }

        // 2. エンティティによる変化後（event.getBlockData()）を記録
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        recordManager.savePlace(elapsed, event.getEntity().getUniqueId(), loc, event.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (recordManager.isNotRecording()) return;

        Block block = event.getBlock();
        Location loc = block.getLocation();

        // 1. その場所をまだ記録していなければ、0秒時点に「壊される前の状態」を保存
        if (!placedbefore.contains(block)) {
            recordManager.savePlace(0, event.getPlayer().getUniqueId(), loc, block.getBlockData());
            placedbefore.add(block);
        }

        // 2. 壊した瞬間のアクションを記録（空気にする）
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        recordManager.savePlace(elapsed, event.getPlayer().getUniqueId(), loc, Bukkit.createBlockData(Material.AIR));
    }

    @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerChat(AsyncChatEvent event) {
            if (recordManager.isNotRecording()) return;
            Player player = event.getPlayer();
            long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
            String plainMessage = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(event.message());

            String formatted = "§7" + player.getName() + ": [" + plainMessage + "]";
        recordManager.saveChatFrame(elapsed, player.getUniqueId(), formatted);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (recordManager.isNotRecording()) return; // 録画中かチェック

        try {
            String message = event.getMessage();
            String teamMessage;

            if (message.startsWith("/teammsg ")) {
                teamMessage = message.substring(9).trim();
            } else if (message.startsWith("/tm ")) {
                teamMessage = message.substring(4).trim();
            } else {
                return;
            }

            if (teamMessage.isEmpty()) return;

            Player sender = event.getPlayer();
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = scoreboard.getEntryTeam(sender.getName());
            if (team == null) return;

            Component format = Component.text("[観戦] ", NamedTextColor.GRAY)
                    .append(Component.text("[", NamedTextColor.WHITE))
                    .append(team.displayName().color(team.color())) // チームの色
                    .append(Component.text("] ", NamedTextColor.WHITE))
                    .append(sender.displayName().color(NamedTextColor.GRAY))
                    .append(Component.text(": ", NamedTextColor.WHITE))
                    .append(Component.text(teamMessage, NamedTextColor.WHITE));

            String legacyFormat = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(format);
            long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
            recordManager.saveChatFrame(elapsed, sender.getUniqueId(), legacyFormat);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.SPECTATOR && !team.hasEntry(player.getName())) {
                    player.sendMessage(format);
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[EastRecorder] TeamMessage 記録中にエラーが発生しました: " + e.getMessage());
        }
    }

    private void recordTeleport(Player player, Location to) {
        if (to == null) return;
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        Bukkit.getScheduler().runTask(EastRecorder.getInstance(), () -> {
            WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                    0,
                    new Vector3d(to.getX(), to.getY(), to.getZ()),
                    to.getYaw(),
                    to.getPitch(),
                    !player.isFlying()
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_TELEPORT, tp);
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                    0, SpigotConversionUtil.getEntityMetadata(player)
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_METADATA, meta);
            recordManager.recordingSession.lastLocations.put(player.getUniqueId(), to.clone());
        });
    }

    public void recordPlayerEquipment(Player player) {
        if (recordManager.isNotRecording()) return;
        long elapsedAtTrigger = System.currentTimeMillis() - recordManager.getStartTime();
        if (recordManager.isNotRecording() || !player.isOnline()) return;
        List<Equipment> equipmentList = new ArrayList<>();
        org.bukkit.inventory.EntityEquipment bukkitEquip = player.getEquipment();

        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.MAIN_HAND, bukkitEquip.getItemInMainHand());
        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.OFF_HAND, bukkitEquip.getItemInOffHand());
        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.HELMET, bukkitEquip.getHelmet());
        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.CHEST_PLATE, bukkitEquip.getChestplate());
        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.LEGGINGS, bukkitEquip.getLeggings());
        recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.BOOTS, bukkitEquip.getBoots());

        if (!equipmentList.isEmpty()) {
            WrapperPlayServerEntityEquipment equipPacket = new WrapperPlayServerEntityEquipment(0, equipmentList);
            recordManager.saveFrame(elapsedAtTrigger, player.getEntityId(), PacketType.Play.Server.ENTITY_EQUIPMENT, equipPacket);
        }
    }
    
}