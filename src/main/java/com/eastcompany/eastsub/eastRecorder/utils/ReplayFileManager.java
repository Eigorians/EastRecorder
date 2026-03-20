package com.eastcompany.eastsub.eastRecorder.utils;

import com.eastcompany.eastsub.eastRecorder.frame.ReplayFrame;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ReplayFileManager {

    private final JavaPlugin plugin;

    public ReplayFileManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void saveToFile(String baseName, Queue<ReplayFrame> recordedFrames) {
        if (recordedFrames.isEmpty()) {
            plugin.getLogger().warning("録画データが空だったため、保存をスキップしました。");
            return;
        }

        List<ReplayFrame> framesToSave = new ArrayList<>(recordedFrames);
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        // 重複チェックを行い、baseName_1.erec のようなファイルを取得
        File file = getUniqueFile(dataFolder, baseName);

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileOutputStream fos = new FileOutputStream(file);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 GZIPOutputStream gzos = new GZIPOutputStream(bos);
                 DataOutputStream dos = new DataOutputStream(gzos)) {

                dos.writeInt(framesToSave.size());
                for (ReplayFrame frame : framesToSave) {
                    dos.writeLong(frame.timestamp());
                    UUID uuid = frame.targetUuid();
                    dos.writeLong(uuid.getMostSignificantBits());
                    dos.writeLong(uuid.getLeastSignificantBits());
                    dos.writeUTF(frame.packetType().getName());
                    dos.writeInt(frame.data().length);
                    dos.write(frame.data());
                }
                plugin.getLogger().info("リプレイを保存しました: " + file.getName() + " (" + framesToSave.size() + " frames)");
            } catch (IOException e) {
                plugin.getLogger().severe("リプレイの保存中にエラーが発生しました: " + e.getMessage());
            }
        });
    }

    public List<ReplayFrame> loadFromFile(String fileName) {
        List<ReplayFrame> frames = new ArrayList<>();
        // 拡張子が含まれていない場合を考慮して補完
        String fullFileName = fileName.endsWith(".erec") ? fileName : fileName + ".erec";
        File file = new File(plugin.getDataFolder(), fullFileName);

        if (!file.exists()) return frames;

        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzis = new GZIPInputStream(bis);
             DataInputStream dis = new DataInputStream(gzis)) {

            int frameCount = dis.readInt();
            for (int i = 0; i < frameCount; i++) {
                long timestamp = dis.readLong();
                UUID uuid = new UUID(dis.readLong(), dis.readLong());
                String typeName = dis.readUTF();
                int dataLen = dis.readInt();
                byte[] data = new byte[dataLen];
                dis.readFully(data);

                PacketType.Play.Server type = getPacketTypeByName(typeName);
                if (type != null) {
                    frames.add(new ReplayFrame(timestamp, uuid, type, data));
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("リプレイの読み込みに失敗: " + e.getMessage());
        }
        return frames;
    }

    private File getUniqueFile(File folder, String baseName) {
        File file = new File(folder, baseName + ".erec");

        // 初回で存在しなければそのまま返す
        if (!file.exists()) {
            return file;
        }

        // 存在する場合、baseName_1, baseName_2 ... と試行
        int count = 1;
        while (file.exists()) {
            file = new File(folder, baseName + "_" + count + ".erec");
            count++;
        }
        return file;
    }

    private PacketType.Play.Server getPacketTypeByName(String name) {
        for (PacketType.Play.Server type : PacketType.Play.Server.values()) {
            if (type.getName().equals(name)) return type;
        }
        return null;
    }

    public List<String> getReplayFileList() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) return List.of();

        File[] files = dataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".erec"));
        if (files == null) return List.of();

        List<String> fileNames = new ArrayList<>();
        for (File file : files) {
            fileNames.add(file.getName().substring(0, file.getName().length() - 5));
        }
        return fileNames;
    }
}