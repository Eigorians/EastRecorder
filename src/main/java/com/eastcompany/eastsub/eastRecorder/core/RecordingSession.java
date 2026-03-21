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
        float yaw = cur.getYaw();
        float pitch = cur.getPitch();

        // 1. 移動または回転があったか判定
        boolean moved = cur.getX() != last.getX() || cur.getY() != last.getY() || cur.getZ() != last.getZ();
        boolean rotated = (Math.abs(yaw - last.getYaw()) > 0.01) || (Math.abs(pitch - last.getPitch()) > 0.01);
        boolean isOnGround = entity.isOnGround();

        // 2. 移動または回転があれば、すべて Teleport パケットとして処理
        if (moved || rotated) {
            // EntityTeleport は絶対座標を指定するため、RelativeMove のような距離制限がありません
            var tp = new WrapperPlayServerEntityTeleport(
                    entity.getEntityId(),
                    SpigotConversionUtil.fromBukkitLocation(cur),
                    isOnGround
            );
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_TELEPORT, tp);

            // 3. 首の向き (Head Rotation) も同時に保存
            // テレポートパケットだけでは首の向き（Yaw）が同期されない場合があるため必須です
            var headLook = new WrapperPlayServerEntityHeadLook(entity.getEntityId(), yaw);
            recordManager.saveFrame(elapsed, entity.getEntityId(), PacketType.Play.Server.ENTITY_HEAD_LOOK, headLook);
        }
    }
}