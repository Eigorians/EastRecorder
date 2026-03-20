package com.eastcompany.eastsub.eastRecorder.utils;

import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Optional;

public class PacketRewriter {

    public static byte[] rewriteToZeroId(PacketType.Play.Server type, byte[] data) {
        return rewriteToAnyId(type, data, 0);
    }

    public static byte[] rewriteToAnyId(PacketType.Play.Server type, byte[] data, int targetId) {
        PacketWrapper<?> wrapper = getWrapperInstance(type );
        if (wrapper == null) return data;

        try {
            Object buf = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
            ByteBufHelper.writeBytes(buf, data);
            wrapper.setBuffer(buf);

            wrapper.read(); // 保存データ(ID=0)を読み込む
            setEntityId(wrapper, targetId); // 指定されたID(NPC ID)に書き換える

            Object outBuf = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
            try {
                wrapper.setBuffer(outBuf);
                wrapper.write();
                return ByteBufHelper.copyBytes(outBuf);
            } finally {
                ByteBufHelper.release(outBuf);
            }
        } catch (Exception e) {
            return data;
        }
    }

    public static PacketWrapper<?> rewriteToAnyIdWrapper(PacketType.Play.Server type, byte[] data, int targetId) {
        PacketWrapper<?> wrapper = getWrapperInstance(type);
        if (wrapper == null) return null;

        try {
            Object buf = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
            ByteBufHelper.writeBytes(buf, data);
            wrapper.setBuffer(buf);

            wrapper.read(); // 0で保存されたデータを読み込む
            setEntityId(wrapper, targetId); // NPC IDを注入

            Object outBuf = com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper.buffer();
            wrapper.setBuffer(outBuf);
            wrapper.write();

            return wrapper;
        } catch (Exception e) {
            return null;
        }
    }

    public static PacketWrapper<?> getWrapperInstance(PacketType.Play.Server type) {
        return switch (type) {
            case ENTITY_RELATIVE_MOVE -> new WrapperPlayServerEntityRelativeMove(0, 0, 0, 0, false);
            case ENTITY_RELATIVE_MOVE_AND_ROTATION -> new WrapperPlayServerEntityRelativeMoveAndRotation(0, 0, 0, 0, (byte)0, (byte)0, false);
            case ENTITY_ROTATION -> new WrapperPlayServerEntityRotation(0, (byte)0, (byte)0, false);
            case ENTITY_TELEPORT -> new WrapperPlayServerEntityTeleport(0, Vector3d.zero(), 0, 0, false);
            case ENTITY_HEAD_LOOK -> new WrapperPlayServerEntityHeadLook(0, (byte)0);
            case ENTITY_ANIMATION -> new WrapperPlayServerEntityAnimation(0, WrapperPlayServerEntityAnimation.EntityAnimationType.HURT);
            case ENTITY_METADATA -> new WrapperPlayServerEntityMetadata(0, new ArrayList<>());
            case ENTITY_VELOCITY -> new WrapperPlayServerEntityVelocity(0, new Vector3d(0.0, 0.0, 0.0));
            case ENTITY_POSITION_SYNC -> new WrapperPlayServerEntityPositionSync(0, new EntityPositionData(new Vector3d(0.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0), 0.0f, 0.0f), false);
            case SPAWN_ENTITY -> new WrapperPlayServerSpawnEntity(0, Optional.empty(), null, Vector3d.zero(), 0, 0, 0, 0, Optional.empty());
            case PLAYER_INFO_UPDATE -> new WrapperPlayServerPlayerInfoUpdate(EnumSet.noneOf(WrapperPlayServerPlayerInfoUpdate.Action.class), new ArrayList<>());
            case ENTITY_STATUS -> new WrapperPlayServerEntityStatus(0, (byte)2);
            case DESTROY_ENTITIES -> new WrapperPlayServerDestroyEntities(new int[]{0});
            case DAMAGE_EVENT -> new WrapperPlayServerDamageEvent(0, null, -1, -1, null);
            case ENTITY_EQUIPMENT -> new WrapperPlayServerEntityEquipment(0, new ArrayList<>());
            case SET_PASSENGERS -> new WrapperPlayServerSetPassengers(0, new int[]{0});
            case ENTITY_MOVEMENT -> new WrapperPlayServerEntityMovement(0);

            // 該当がない場合は例外を投げる
            default -> throw new IllegalArgumentException("Unsupported packet type: " + type);
        };
    }

    public static void setEntityId(PacketWrapper<?> wrapper, int id) {
        switch (wrapper) {
            case WrapperPlayServerEntityRelativeMove w -> w.setEntityId(id);
            case WrapperPlayServerEntityRelativeMoveAndRotation w -> w.setEntityId(id);
            case WrapperPlayServerEntityRotation w -> w.setEntityId(id);
            case WrapperPlayServerEntityMovement w -> w.setEntityId(id);
            case WrapperPlayServerEntityTeleport w -> w.setEntityId(id);
            case WrapperPlayServerEntityHeadLook w -> w.setEntityId(id);
            case WrapperPlayServerEntityAnimation w -> w.setEntityId(id);
            case WrapperPlayServerEntityMetadata w -> w.setEntityId(id);
            case WrapperPlayServerEntityVelocity w -> w.setEntityId(id);
            case WrapperPlayServerEntityPositionSync w -> w.setId(id);
            case WrapperPlayServerSpawnEntity w -> w.setEntityId(id);
            case WrapperPlayServerEntityStatus w -> w.setEntityId(id);
            case WrapperPlayServerDestroyEntities w -> w.setEntityIds(new int[]{id});
            case WrapperPlayServerDamageEvent w -> w.setEntityId(id);
            case WrapperPlayServerEntityEquipment w -> w.setEntityId(id);
            case WrapperPlayServerSetPassengers w -> w.setEntityId(id);
            default -> {
                return;
            }
        }
    }
}