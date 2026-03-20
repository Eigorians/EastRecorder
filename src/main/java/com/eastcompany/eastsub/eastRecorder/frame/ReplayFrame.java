package com.eastcompany.eastsub.eastRecorder.frame;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;

import java.util.UUID;

public record ReplayFrame(
        long timestamp,
        UUID targetUuid, // ここが targetUuid になっている
        PacketType.Play.Server packetType,
        byte[] data
) {}