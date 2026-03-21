package com.eastcompany.eastsub.eastRecorder.core;

import com.eastcompany.eastsub.eastRecorder.core.RecordManager;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class RecordingSession {

    private final RecordManager recordManager;
    // エンティティごとの「前回の位置」を記憶するMap
    public final java.util.Map<UUID, Location> lastLocations = new java.util.HashMap<>();

    public RecordingSession(RecordManager recordManager){
        this.recordManager = recordManager;
    }

    public void startTickLoop(JavaPlugin plugin) {

        for (UUID uuid : recordManager.getEntityIdToUuidMap().values()) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null) continue;
            Location current = entity.getLocation();
            lastLocations.put(uuid, current);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (recordManager.isNotRecording()) {
                    this.cancel();
                    return;
                }

                long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
                for (UUID uuid : recordManager.getRealUuid()) {
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity == null) continue;


                    Location current = entity.getLocation();
                    Location last = lastLocations.get(uuid);

                    if (last != null) {
                        processMovement(elapsed, entity, current, last);
                    }

                    // 今回の位置を保存（次回比較用）
                    lastLocations.put(uuid, current.clone());
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void processMovement(long elapsed, Entity entity, Location cur, Location last) {
        // 1. 座標の差分計算
        double dx = cur.getX() - last.getX();
        double dy = cur.getY() - last.getY();
        double dz = cur.getZ() - last.getZ();
        float yaw = cur.getYaw();
        float pitch = cur.getPitch();

        double distanceSq = dx * dx + dy * dy + dz * dz;
        boolean moved = distanceSq > 0.0001; // 微小な移動を検知
        boolean rotated = (Math.abs(yaw - last.getYaw()) > 0.01) || (Math.abs(pitch - last.getPitch()) > 0.01);
        boolean isOnGround = entity.isOnGround();

        // 2. 適切なパケットの選択
        if (distanceSq > 64.0 || !cur.getWorld().equals(last.getWorld())) {
            // 8ブロック以上の移動、またはワールド移動は「テレポート」
            var tp = new WrapperPlayServerEntityTeleport(0, SpigotConversionUtil.fromBukkitLocation(cur), isOnGround);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_TELEPORT, tp);
        }
        else if (moved && rotated) {
            var moveRot = new WrapperPlayServerEntityRelativeMoveAndRotation(0, dx, dy, dz, yaw, pitch, isOnGround);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION, moveRot);
        }
        else if (moved) {
            var move = new WrapperPlayServerEntityRelativeMove(0, dx, dy, dz, isOnGround);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_RELATIVE_MOVE, move);
        }
        else if (rotated) {
            var rot = new WrapperPlayServerEntityRotation(0, yaw, pitch, isOnGround);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_ROTATION, rot);
        }

        // 3. 首の向きは独立して保存（これがないと首が回らない）
        if (rotated) {
            var headLook = new WrapperPlayServerEntityHeadLook(0, yaw);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_HEAD_LOOK, headLook);
        }
    }
}