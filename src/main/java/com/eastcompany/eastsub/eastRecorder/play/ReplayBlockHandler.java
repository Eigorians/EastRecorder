package com.eastcompany.eastsub.eastRecorder.play;

import com.eastcompany.eastsub.eastRecorder.frame.ReplayFrame;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReplayBlockHandler {

    // リプレイ中に「パケットを送って書き換えた場所」を記憶する
    private final Set<Location> affectedLocations = new HashSet<>();

    /**
     * フレームからブロック情報を読み取って全員に送信する
     */
    public void process(ReplayFrame frame) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(frame.data());
             DataInputStream dis = new DataInputStream(bis)) {

            String worldName = dis.readUTF();
            World world = Bukkit.getWorld(worldName);
            if (world == null) return;

            double x = dis.readDouble();
            double y = dis.readDouble();
            double z = dis.readDouble();
            dis.readFloat(); // yaw
            dis.readFloat(); // pitch
            String blockDataString = dis.readUTF();

            Location loc = new Location(world, x, y, z);
            BlockData blockData = Bukkit.createBlockData(blockDataString);

            // この場所を記録
            affectedLocations.add(loc);

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendBlockChange(loc, blockData);
            }

        } catch (Exception e) {
            Bukkit.getLogger().severe("[Replay] ブロックデコードエラー: " + e.getMessage());
        }
    }

    /**
     * 影響を与えたすべてのブロックを、サーバー上の「真実の状態」に戻す
     */
    public void restoreAll() {
        if (affectedLocations.isEmpty()) return;

        for (Location loc : affectedLocations) {
            // パケット上の嘘の状態ではなく、サーバーにある「本物の BlockData」を取得
            BlockData realData = loc.getBlock().getBlockData();

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendBlockChange(loc, realData);
            }
        }

        affectedLocations.clear();
    }
}