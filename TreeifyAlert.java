package com.beer30.treeify;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * The server sends no message when Treeify procs, but the whole tree turns into bedrock.
 * So: every couple of ticks look at the blocks around the player, and if several blocks that
 * were NOT bedrock a moment ago are bedrock now, tell the player.
 */
public class TreeifyAlert implements ClientModInitializer {
    private static final int RADIUS = 10;          // blocks to each side of the player
    private static final int DOWN = 8;             // blocks below the player to check
    private static final int UP = 28;              // blocks above the player to check (tall trees)
    private static final int MIN_NEW_BEDROCK = 3;  // this many new bedrock blocks at once = a proc
    private static final int SCAN_EVERY = 2;       // ticks between scans
    private static final int COOLDOWN_TICKS = 40;  // do not alert twice within 2 seconds
    private static final int SETTLE_TICKS = 40;    // wait after joining a world / teleporting

    private ClientWorld lastWorld;
    private BlockPos lastPos;
    private Set<Long> prevBedrock = new HashSet<>();
    private boolean havePrev;
    private int pMinX, pMaxX, pMinY, pMaxY, pMinZ, pMaxZ;
    private int tickCount, cooldown, settle;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        CopyChat.init();   // shift + click a chat message to copy it
    }

    private void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            lastWorld = null;
            lastPos = null;
            havePrev = false;
            return;
        }
        if (world != lastWorld) {          // joined a world or changed dimension
            lastWorld = world;
            havePrev = false;
            settle = SETTLE_TICKS;
        }
        if (cooldown > 0) cooldown--;
        if (settle > 0) {
            settle--;
            havePrev = false;
            return;
        }
        if (++tickCount % SCAN_EVERY != 0) return;

        BlockPos p = client.player.getBlockPos();
        if (lastPos != null && lastPos.getSquaredDistance(p) > 100) {   // teleported
            havePrev = false;
            settle = SETTLE_TICKS;
            lastPos = p;
            return;
        }
        lastPos = p;

        int bottom = world.getBottomY();
        int top = bottom + world.getHeight() - 1;
        int minX = p.getX() - RADIUS, maxX = p.getX() + RADIUS;
        int minZ = p.getZ() - RADIUS, maxZ = p.getZ() + RADIUS;
        // skip the bottom few layers: the world floor is bedrock and would only add noise
        int minY = Math.max(p.getY() - DOWN, bottom + 6);
        int maxY = Math.min(p.getY() + UP, top);

        Set<Long> cur = new HashSet<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int fresh = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    if (!world.getBlockState(m).isOf(Blocks.BEDROCK)) continue;
                    long key = m.asLong();
                    cur.add(key);
                    // only count blocks that were inside last scan's box and were not bedrock then
                    if (havePrev && !prevBedrock.contains(key)
                            && x >= pMinX && x <= pMaxX && y >= pMinY && y <= pMaxY && z >= pMinZ && z <= pMaxZ) {
                        fresh++;
                    }
                }
            }
        }

        if (fresh >= MIN_NEW_BEDROCK && cooldown == 0) {
            alert(client, fresh);
            cooldown = COOLDOWN_TICKS;
        }

        prevBedrock = cur;
        pMinX = minX; pMaxX = maxX; pMinY = minY; pMaxY = maxY; pMinZ = minZ; pMaxZ = maxZ;
        havePrev = true;
    }

    private void alert(MinecraftClient client, int blocks) {
        Text chat = Text.literal("[Beer30] ").formatted(Formatting.GOLD)
                .append(Text.literal("Treeify procced! (" + blocks + " blocks)").formatted(Formatting.GREEN));
        client.player.sendMessage(chat, false);                                   // in chat
        client.player.sendMessage(Text.literal("Treeify procced!").formatted(Formatting.GREEN, Formatting.BOLD), true);  // pop-up above the hotbar
    }
}
