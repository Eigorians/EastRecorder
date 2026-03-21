package com.eastcompany.eastsub.eastRecorder.core;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;

import java.util.*;

public class AddSpawnPacket {
    private final RecordManager recordManager;

    public AddSpawnPacket(RecordManager recordManager) {
        this.recordManager = recordManager;
    }

    void addSpawnPacket(Entity entity) {
        EntityType type = SpigotConversionUtil.fromBukkitEntityType(entity.getType());
        Location loc = entity.getLocation();
        UUID newuuid = UUID.randomUUID();

        if (entity.getType() == org.bukkit.entity.EntityType.EXPERIENCE_ORB) {
            return;
        }

        boolean shouldRecord = false;

        if (entity instanceof Player) {
            shouldRecord = true;
            recordManager.getPlayerID().add(entity.getEntityId());
        } else {
            boolean hasTag = entity.getScoreboardTags().contains("record");
            boolean isNearPlayer = entity.getNearbyEntities(2.0, 2.0, 2.0).stream().anyMatch(e -> e instanceof Player);

            if (hasTag) {
                shouldRecord = true;
            } else if (isNearPlayer) {
                shouldRecord = true;
            }
        }

        if (!shouldRecord) {
            return;
        }

        long elapsed = System.currentTimeMillis() - recordManager.getStartTime();
        Integer entityID = entity.getEntityId();
        recordManager.getEntityIdToUuidMap().put(entityID, newuuid);
        recordManager.getRealUuid().add(entity.getUniqueId());
        if (entity instanceof Player player) {
            PlayerProfile bukkitProfile = player.getPlayerProfile(); // complete済みの情報を取得
            String name = bukkitProfile.getName() != null ? bukkitProfile.getName() : "ReplayNPC";
            UserProfile packetProfile = new UserProfile(newuuid, name);

            List<TextureProperty> textures = new ArrayList<TextureProperty>();

            bukkitProfile.getProperties().forEach(prop -> {
                textures.add(new TextureProperty(prop.getName(), prop.getValue(), prop.getSignature()));
            });

            packetProfile.setTextureProperties(textures);

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    packetProfile, true, 10, SpigotConversionUtil.fromBukkitGameMode(player.getGameMode()), player.name(), null
            );
            recordManager.saveFrame(elapsed, entityID, PacketType.Play.Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                    List.of(info)));
        }
        // ★ ここも newuuid（名簿と同じID）を使う
        WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                0,
                Optional.of(newuuid),
                type,
                new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                loc.getPitch(),
                loc.getYaw(),
                loc.getYaw(),
                0,
                Optional.empty()
        );

        recordManager.saveFrame(elapsed, entityID, PacketType.Play.Server.SPAWN_ENTITY, spawnPacket);

        WrapperPlayServerEntityMetadata metaPacket = new WrapperPlayServerEntityMetadata(0, SpigotConversionUtil.getEntityMetadata(entity));
        recordManager.saveFrame(elapsed, entityID, PacketType.Play.Server.ENTITY_METADATA, metaPacket);
        if (entity instanceof LivingEntity living) {
            EntityEquipment bukkitEquip = living.getEquipment();
            if (bukkitEquip != null) {
                List<Equipment> equipmentList = new ArrayList<Equipment>();
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.MAIN_HAND, bukkitEquip.getItemInMainHand());
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.OFF_HAND, bukkitEquip.getItemInOffHand());
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.HELMET, bukkitEquip.getHelmet());
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.CHEST_PLATE, bukkitEquip.getChestplate());
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.LEGGINGS, bukkitEquip.getLeggings());
                recordManager.addIfNotEmpty(equipmentList, EquipmentSlot.BOOTS, bukkitEquip.getBoots());
                if (!equipmentList.isEmpty()) {
                    WrapperPlayServerEntityEquipment equipmentPacket = new WrapperPlayServerEntityEquipment(0, equipmentList);
                    recordManager.saveFrame(elapsed, entityID, PacketType.Play.Server.ENTITY_EQUIPMENT, equipmentPacket);
                }
            }
        }
    }
}