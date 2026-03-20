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
        if(type == PacketType.Play.Client.CLIENT_TICK_END) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();

        if (type == PacketType.Play.Client.ENTITY_ACTION){
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                    0, SpigotConversionUtil.getEntityMetadata(player)
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_METADATA,
                    meta);
            return;
        }
        if(type == PacketType.Play.Client.PLAYER_INPUT){
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                    0, SpigotConversionUtil.getEntityMetadata(player)
            );
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_METADATA,
                    meta);
            return;
        }
        if (type == PacketType.Play.Client.ANIMATION){
            WrapperPlayClientAnimation animation = new WrapperPlayClientAnimation(event);
            WrapperPlayServerEntityAnimation animation1 = new WrapperPlayServerEntityAnimation(0 ,WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_ANIMATION,
                    animation1);
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_POSITION ||
                type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION ||
                type == PacketType.Play.Client.PLAYER_ROTATION) {

                if (recordManager.isNotRecording()) return;


                com.github.retrooper.packetevents.protocol.world.Location cur = SpigotConversionUtil.fromBukkitLocation(player.getLocation());
                Location last = recordManager.lastLocations.get(uuid);

                if (last != null) {
                    double dx = cur.getX() - last.getX();
                    double dy = cur.getY() - last.getY();
                    double dz = cur.getZ() - last.getZ();
                    double distanceSq = dx * dx + dy * dy + dz * dz;

                    if (distanceSq > 64.0 || !player.getWorld().equals(last.getWorld())) {
                        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                                0, // 再生時に置換するための一時ID
                                cur,
                                false
                        );

                        recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_TELEPORT, teleport);
                    }
                    else {
                        boolean moved = distanceSq > 0.0001;
                        boolean rotated = (Math.abs(cur.getYaw() - last.getYaw()) > 0.1) ||
                                (Math.abs(cur.getPitch() - last.getPitch()) > 0.1);

                        if (moved || rotated) {
                            WrapperPlayServerEntityRelativeMoveAndRotation moveRot = new WrapperPlayServerEntityRelativeMoveAndRotation(
                                    0, dx, dy, dz, cur.getYaw(), cur.getPitch(), false
                            );

                            recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION, moveRot);

                            if (rotated) {
                                recordManager.saveFrame(elapsed, player.getEntityId(), PacketType.Play.Server.ENTITY_HEAD_LOOK,
                                        new WrapperPlayServerEntityHeadLook(0, cur.getYaw()));
                            }
                        }
                    }
                }
                recordManager.lastLocations.put(uuid, player.getLocation().clone());
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

        if (context == null || context.entityId() == -1) return;

        if (recordManager.getEntityIdToUuidMap().get(context.entityId()) == null) return;

        if (isMoveOrRotationPacket(event.getPacketType()) && recordManager.getPlayerID().contains(context.entityId())) {
            return;
        }
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

        try {
            // 全てのケースで: 1. ラッパー生成 -> 2. read() で解析 -> 3. ID取得
            return switch (type) {
                case PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> {
                    var wr = new WrapperPlayServerEntityRelativeMove(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
                    var wr = new WrapperPlayServerEntityRelativeMoveAndRotation(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_TELEPORT -> {
                    var wr = new WrapperPlayServerEntityTeleport(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
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
                case PacketType.Play.Server.ENTITY_HEAD_LOOK -> {
                    var wr = new WrapperPlayServerEntityHeadLook(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                    var wr = new WrapperPlayServerEntityEquipment(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_VELOCITY -> {
                    var wr = new WrapperPlayServerEntityVelocity(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                case PacketType.Play.Server.ENTITY_POSITION_SYNC -> {
                    var wr = new WrapperPlayServerEntityPositionSync(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getId()); // ここだけ getId()
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
                case PacketType.Play.Server.ENTITY_ROTATION -> {
                    var wr = new WrapperPlayServerEntityRotation(clonedEvent);
                    wr.read();
                    yield new PacketContext(wr, wr.getEntityId());
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isMoveOrRotationPacket(PacketTypeCommon type) {

        if (!((type instanceof PacketType.Play.Server serverPacket))) {
        return false;
        }
            return switch (serverPacket) {
                case ENTITY_RELATIVE_MOVE,
                     ENTITY_RELATIVE_MOVE_AND_ROTATION,
                     ENTITY_ROTATION,
                     ENTITY_HEAD_LOOK,
                     ENTITY_TELEPORT -> true;
                default -> false;
            };
        }
}
