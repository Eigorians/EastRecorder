package com.eastcompany.eastsub.eastRecorder.core;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecordStatus {

    private final RecordManager recordManager;
    private final JavaPlugin plugin;

    // 装備のキャッシュ
    private final Map<Integer, Map<EquipmentSlot, ItemStack>> lastEquipmentMap = new HashMap<>();
    // メタデータのキャッシュ (EntityID -> 前回のメタデータリスト)
    private final Map<Integer, List<EntityData<?>>> lastMeta = new HashMap<>();

    public RecordStatus(RecordManager recordManager, JavaPlugin plugin) {
        this.recordManager = recordManager;
        this.plugin = plugin;
    }

    public void recordEntityEquipment(LivingEntity entity) {
        if (recordManager.isNotRecording() || entity == null || !entity.isValid()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (recordManager.isNotRecording() || !entity.isValid()) return;

            int entityId = entity.getEntityId();
            long elapsed = System.currentTimeMillis() - recordManager.getStartTime();

            // --- 1. メタデータの処理 ---
            List<EntityData<?>> currentMeta = SpigotConversionUtil.getEntityMetadata(entity);
            List<EntityData<?>> previousMeta = lastMeta.get(entityId);

            // 変化がある場合のみ保存
            if (previousMeta == null || !previousMeta.equals(currentMeta)) {
                lastMeta.put(entityId, new ArrayList<>(currentMeta));
                WrapperPlayServerEntityMetadata metaPacket = new WrapperPlayServerEntityMetadata(entityId, currentMeta);
                recordManager.saveFrame(elapsed, entityId, PacketType.Play.Server.ENTITY_METADATA, metaPacket);
            }

            // --- 2. 装備の処理 ---
            EntityEquipment bukkitEquip = entity.getEquipment();
            if (bukkitEquip == null) return;

            List<Equipment> changeList = new ArrayList<>();
            Map<EquipmentSlot, ItemStack> cache = lastEquipmentMap.computeIfAbsent(entityId, k -> new HashMap<>());

            checkAndAdd(changeList, cache, EquipmentSlot.MAIN_HAND, bukkitEquip.getItemInMainHand());
            checkAndAdd(changeList, cache, EquipmentSlot.OFF_HAND, bukkitEquip.getItemInOffHand());
            checkAndAdd(changeList, cache, EquipmentSlot.HELMET, bukkitEquip.getHelmet());
            checkAndAdd(changeList, cache, EquipmentSlot.CHEST_PLATE, bukkitEquip.getChestplate());
            checkAndAdd(changeList, cache, EquipmentSlot.LEGGINGS, bukkitEquip.getLeggings());
            checkAndAdd(changeList, cache, EquipmentSlot.BOOTS, bukkitEquip.getBoots());

            if (!changeList.isEmpty()) {
                // リプレイNPCに反映させるためのID設定。必要に応じて 0 に変更してください。
                WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(entityId, changeList);
                recordManager.saveFrame(elapsed, entityId, PacketType.Play.Server.ENTITY_EQUIPMENT, packet);
            }
        }, 1L);
    }

    private void checkAndAdd(List<Equipment> list, Map<EquipmentSlot, ItemStack> cache, EquipmentSlot slot, ItemStack current) {
        ItemStack currentItem = (current == null) ? new ItemStack(Material.AIR) : current;
        ItemStack lastItem = cache.getOrDefault(slot, new ItemStack(Material.AIR));

        if (isSameItem(lastItem, currentItem)) return;

        cache.put(slot, currentItem.clone());
        list.add(new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(currentItem)));
    }

    private boolean isSameItem(ItemStack i1, ItemStack i2) {
        if (i1 == null && i2 == null) return true;
        if (i1 == null || i2 == null) return false;
        // AIR同士もここで正しくtrueが返ります
        return i1.equals(i2);
    }

    public void updateCacheFromPacket(int entityId, List<Equipment> equipmentList) {
        if (equipmentList == null) return;
        Map<EquipmentSlot, ItemStack> cache = lastEquipmentMap.computeIfAbsent(entityId, k -> new HashMap<>());
        for (Equipment eq : equipmentList) {
            ItemStack item = SpigotConversionUtil.toBukkitItemStack(eq.getItem());
            cache.put(eq.getSlot(), item == null ? null : item.clone());
        }
    }

    public void updateMetaCache(int entityId, List<EntityData<?>> metaList) {
        lastMeta.put(entityId, new ArrayList<>(metaList));
    }

    public void removeCache(int entityId) {
        lastEquipmentMap.remove(entityId);
        lastMeta.remove(entityId);
    }

    public void clearAllCache() {
        lastEquipmentMap.clear();
        lastMeta.clear();
    }
}