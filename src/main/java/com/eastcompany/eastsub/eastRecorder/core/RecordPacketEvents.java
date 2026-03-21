package com.eastcompany.eastsub.eastRecorder.core;

import com.eastcompany.eastsub.eastRecorder.utils.PacketRewriter;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;

public class RecordPacketEvents implements PacketListener {

    private final RecordManager recordManager;
    public RecordPacketEvents(RecordManager recordManager) {
        this.recordManager = recordManager;
    }

    Object buffer = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (recordManager.isNotRecording()) return;
        Player player = event.getPlayer();
        if (player == null) return;
        var type = event.getPacketType();
        if(type == PacketType.Play.Client.CLIENT_TICK_END || type == PacketType.Play.Client.PLAYER_POSITION || type == PacketType.Play.Client.KEEP_ALIVE) {
            return;
        }
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        if (type == PacketType.Play.Client.ENTITY_ACTION){
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                    0, SpigotConversionUtil.getEntityMetadata(player)
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_METADATA,
                    meta);
            return;
        }

        if (type.getName().contains("ITEM") || type.getName().contains("INVENTORY") || type.getName().contains("CLICK")){
            recordManager.getRecordStatus().recordEntityEquipment(player);
            return;
        }
        if(type == PacketType.Play.Client.PLAYER_INPUT){
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                    0, SpigotConversionUtil.getEntityMetadata(player)
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_METADATA,
                    meta);
            recordManager.getRecordStatus().recordEntityEquipment(player);
            return;
        }
        if (type == PacketType.Play.Client.ANIMATION){
            WrapperPlayClientAnimation animation = new WrapperPlayClientAnimation(event);
            WrapperPlayServerEntityAnimation animation1 = new WrapperPlayServerEntityAnimation(0 ,WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_ANIMATION,
                    animation1);
            return;
        }
    }

    public record PacketContext(
            com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper,
            int entityId
    ) {}



    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (recordManager.isNotRecording() || event.hasPostTasks()) return;

        PacketContext context = resolvePacketContext(event.clone());

        if (context == null)return;;


        if (context.entityId() == -1) return;

        if (recordManager.getEntityIdToUuidMap().get(context.entityId()) == null) return;

        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        recordManager.saveFrame(
                elapsed,
                context.entityId(),
                (PacketType.Play.Server) event.getPacketType(),
                context.wrapper()
        );
    }

    private PacketContext resolvePacketContext(PacketSendEvent clonedEvent) {
        var type = clonedEvent.getPacketType();

        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();

        try {
            // 全てのケースで: 1. ラッパー生成 -> 2. read() で解析 -> 3. ID取得
            return switch (type) {
                case PacketType.Play.Server.ENTITY_METADATA -> {
                    var wr = new WrapperPlayServerEntityMetadata(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_ANIMATION -> {
                    var wr = new WrapperPlayServerEntityAnimation(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                    var wr = new WrapperPlayServerEntityEquipment(clonedEvent);
                    wr.read();
                    recordManager.getRecordStatus().updateCacheFromPacket(wr.getEntityId(), wr.getEquipment());
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.DAMAGE_EVENT -> {
                    var wr = new WrapperPlayServerDamageEvent(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_STATUS -> {
                    var wr = new WrapperPlayServerEntityStatus(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }

                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
