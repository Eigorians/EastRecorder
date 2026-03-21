package com.eastcompany.eastsub.eastRecorder.play;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ReplayMergeBuffer {

    private final Map<Integer, WrapperPlayServerEntityTeleport> latestTeleport = new HashMap<>();
    private final Map<Integer, WrapperPlayServerEntityHeadLook> latestHeadLook = new HashMap<>();
    private final Map<Integer, WrapperPlayServerEntityMetadata> latestMetadata = new HashMap<>();

    /**
     * パケットとタイプを指定してバッファに追加します
     * @param npcId 対象のNPC ID
     * @param type パケットタイプ
     * @param packet パケットラッパー
     */
    public void add(int npcId, PacketTypeCommon type, PacketWrapper<?> packet) {
        if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport tp = (WrapperPlayServerEntityTeleport) packet;
            tp.setEntityId(npcId);
            latestTeleport.put(npcId, tp);

        } else if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
            WrapperPlayServerEntityHeadLook hl = (WrapperPlayServerEntityHeadLook) packet;
            hl.setEntityId(npcId);
            latestHeadLook.put(npcId, hl);

        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata meta = (WrapperPlayServerEntityMetadata) packet;
            meta.setEntityId(npcId);
            latestMetadata.put(npcId, meta);
        }
    }

    /**
     * バッファを放出してクリア
     */
    public void flush(Consumer<PacketWrapper<?>> sender) {
        latestTeleport.values().forEach(sender);
        latestHeadLook.values().forEach(sender);
        latestMetadata.values().forEach(sender);

        latestTeleport.clear();
        latestHeadLook.clear();
        latestMetadata.clear();
    }
}