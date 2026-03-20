package com.eastcompany.eastsub.eastRecorder.core;

import com.eastcompany.eastsub.eastRecorder.command.RecordCommand;
import com.eastcompany.eastsub.eastRecorder.play.ReplaySession;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class EastRecorder extends JavaPlugin {

    private RecordManager recordManager;
    private static EastRecorder eastRecorder;

    public static EastRecorder getInstance() {
        return eastRecorder;
    }

    @Override
    public void onLoad() {
        // 1. PacketEventsの初期化 (onEnableより前に行う必要がある)

    }

    @Override
    public void onEnable() {

        eastRecorder = this;
        // 2. PacketEventsの開始

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                PacketEvents.getAPI().init();
                getLogger().info("PacketEvents deferred initialization successful.");
            } catch (Exception e) {
                getLogger().severe("Failed to init PacketEvents even after delay: " + e.getMessage());
            }
        }, 1L);
        // 3. RecordManagerの初期化と登録
        this.recordManager = new RecordManager(this);
        // 4. Lifecycle Eventを使用してコマンドを登録
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var commands = event.registrar();
            new RecordCommand(this).register(commands);
        });
        getLogger().info("EastRecorder has been enabled with Brigadier and PacketEvents!");

    }
    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        if(activeSession != null)activeSession.stop();
        getLogger().info("EastRecorder has been disabled.");
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    private ReplaySession activeSession = null;

    public ReplaySession getActiveSession() {
        return activeSession;
    }
    public void setActiveSession(ReplaySession activeSession) {
        this.activeSession = activeSession;
    }
}