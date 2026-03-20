package com.eastcompany.eastsub.eastRecorder.play;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 1つのNPCの「現在の見た目」をすべて保持するクラス
 */
public class NPCState {
    private final int npcId;
    private final UUID originalUuid;
    private UserProfile profile;

    private WrapperPlayServerSpawnEntity spawnPacket;
    private WrapperPlayServerEntityMetadata metadataPacket;
    private WrapperPlayServerEntityEquipment equipmentPacket;

    public NPCState(int npcId, UUID originalUuid) {
        this.npcId = npcId;
        this.originalUuid = originalUuid;
    }

    public void setLocation(Vector3d vector3d){
        spawnPacket.setPosition(vector3d);
    }

    public void updateSpawn(WrapperPlayServerSpawnEntity packet) {
        packet.setEntityId(this.npcId);
        this.spawnPacket = packet;
    }

    public void updateMetadata(WrapperPlayServerEntityMetadata packet) {
        packet.setEntityId(this.npcId);
        this.metadataPacket = packet;
    }

    public void updateEquipment(WrapperPlayServerEntityEquipment packet) {
        packet.setEntityId(this.npcId);
        this.equipmentPacket = packet;
    }

    public void setProfile(UserProfile profile) { this.profile = profile; }

    public int getNpcId() { return npcId; }
    public UUID getOriginalUuid() { return originalUuid; }

    /**
     * 指定したプレイヤーに対して、このNPCの最新の状態（スキン・召喚・装備・メタ）を送りつける
     */
    public void apply(Player viewer) {
        // 1. スキン情報の同期 (PlayerInfo)
        if (profile != null) {
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    profile, false, 10, GameMode.SURVIVAL, Component.text(profile.getName()), null
            );
            send(viewer, new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER), List.of(info)));
        }

        // 2. エンティティの召喚
        if (spawnPacket != null) send(viewer, spawnPacket);

        // 3. メタデータ（しゃがみ、発光、名前など）
        if (metadataPacket != null) send(viewer, metadataPacket);

        // 4. 装備品
        if (equipmentPacket != null) send(viewer, equipmentPacket);
    }

    private void send(Player p, PacketWrapper<?> w) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(p, w);
    }
}