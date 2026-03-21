package com.eastcompany.eastsub.eastRecorder.play;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.FakeChannelUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class NPCManager {
    // 録画UUID -> NPC状態 の一括管理
    private final Map<UUID, NPCState> npcStates = new HashMap<>();

    // 録画から届いたプロフィールの一時保管 (Spawn前用)
    private final Map<UUID, com.github.retrooper.packetevents.protocol.player.UserProfile> pendingProfiles = new HashMap<>();

    public void handlePlayerInfoUpdate(UUID originalUuid, WrapperPlayServerPlayerInfoUpdate packet) {
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : packet.getEntries()) {
            pendingProfiles.put(originalUuid, info.getGameProfile());
        }
    }

    public void handleMetaDataUpdate(UUID originalUuid, WrapperPlayServerEntityMetadata packet) {
        NPCState state = npcStates.get(originalUuid);
        if (state != null) {
            state.updateMetadata(packet);
            packet.resetBuffer();
            broadcast(packet);
        }
    }

    public void handleEntityEquipment(UUID originalUuid, WrapperPlayServerEntityEquipment packet) {
        NPCState state = npcStates.get(originalUuid);
        if (state != null) {
            state.updateEquipment(packet);
            packet.resetBuffer();
            broadcast(packet);
        }
    }

    public void spawnEntity(UUID originalUuid, WrapperPlayServerSpawnEntity spawn) {
        int npcId = 100000 + new Random().nextInt(10000);
        NPCState state = new NPCState(npcId, originalUuid);

        // スキンのセットアップ
        if (spawn.getEntityType().equals(EntityTypes.PLAYER)) {
            var profile = pendingProfiles.get(originalUuid);
            if (profile != null) {
                state.setProfile(profile);
                spawn.setUUID(Optional.of(profile.getUUID()));
            } else {
                spawn.setUUID(Optional.of(UUID.randomUUID()));
            }
        }

        state.updateSpawn(spawn);
        npcStates.put(originalUuid, state);

        // 全員に現在の状態を適用
        for (Player p : Bukkit.getOnlinePlayers()) {
            state.apply(p);
        }
    }

    public void respawnAt(int npcId, Vector3d vector3d,float yaw, float pitch) {
        NPCState state = findByNpcId(npcId);
        if (state == null) return;
        state.setLocation(vector3d);
        // 再生成用の位置更新（内部のSpawnパケットを書き換え）
        // ※実際には新しい座標でapplyし直す

        state.setRotation(yaw,pitch);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, new WrapperPlayServerDestroyEntities(npcId));

            // 再生成
            state.apply(p);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p,
                    new WrapperPlayServerEntityHeadLook(npcId, yaw));
        }
    }

    public Integer getNpcId(UUID originalUuid) {
        NPCState state = npcStates.get(originalUuid);
        return (state != null) ? state.getNpcId() : null;
    }

    public void cleanup() {
        if (npcStates.isEmpty()) return;
        int[] ids = npcStates.values().stream().mapToInt(NPCState::getNpcId).toArray();
        for(Player player : Bukkit.getOnlinePlayers()){
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerDestroyEntities(ids));
        }

        npcStates.clear();
        pendingProfiles.clear();
    }

    private NPCState findByNpcId(int npcId) {
        return npcStates.values().stream()
                .filter(s -> s.getNpcId() == npcId)
                .findFirst()
                .orElse(null);
    }

    private void broadcast(com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            packet.setClientVersion(PacketEvents.getAPI().getPlayerManager().getClientVersion(p));
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, packet);
        }
    }
}