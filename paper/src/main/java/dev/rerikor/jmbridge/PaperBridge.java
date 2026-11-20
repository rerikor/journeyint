package dev.rerikor.jmbridge;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DeflaterOutputStream;

/** Sends an already-generated chunk to one authorized player, one ACK at a time.
 *  Never generates missing chunks. Each packet has a bounded payload size. */
public final class PaperBridge extends JavaPlugin implements PluginMessageListener {
    private static final String CHANNEL = "jmbridge:data";
    private static final int MAGIC = 0x4a4d4232;
    private static final int WINDOW = 12;
    private static final int STARTS_PER_TICK = 4;
    private static final long ACK_TIMEOUT_MS = 30000;
    private final Map<UUID, Scan> scans = new HashMap<>();

    @Override public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }
    @Override public void onDisable() { scans.clear(); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Run this command in-game"); return true; }
        if (!p.hasPermission("jmbridge.admin")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            scans.remove(p.getUniqueId()); p.sendMessage("[JMBridge] Stopped"); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Scan s = scans.get(p.getUniqueId());
            p.sendMessage(s == null ? "[JMBridge] Idle" : "[JMBridge] " + s.acked + "/" + s.total + " mapped, " + s.missing + " skipped, " + s.pending.size() + " in flight"); return true;
        }
        if (args.length < 2 || args.length > 4 || !args[0].equalsIgnoreCase("start")) {
            p.sendMessage("/jmbridge start <radius> [centerX centerZ] | stop | status"); return true;
        }
        try {
            int radius = Integer.parseInt(args[1]);
            if (radius < 16 || radius > 10000) { p.sendMessage("Radius: 16..10000 blocks"); return true; }
            if (args.length == 3) { p.sendMessage("Specify both centerX and centerZ"); return true; }
            int x = args.length == 4 ? Integer.parseInt(args[2]) : p.getLocation().getBlockX();
            int z = args.length == 4 ? Integer.parseInt(args[3]) : p.getLocation().getBlockZ();
            Scan s = new Scan(p.getWorld(), Math.floorDiv(x-radius,16), Math.floorDiv(x+radius-1,16),
                    Math.floorDiv(z-radius,16), Math.floorDiv(z+radius-1,16));
            scans.put(p.getUniqueId(),s);
            p.sendMessage("[JMBridge] Start " + s.total + " chunks. Don't disconnect; /jmbridge status");
        } catch (NumberFormatException ex) { p.sendMessage("[JMBridge] Invalid number"); }
        return true;
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!CHANNEL.equals(channel) || bytes.length != 13) return;
        Scan s = scans.get(p.getUniqueId()); if (s == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != 2) return;
            int cx = in.readInt(), cz = in.readInt();
            long key = key(cx, cz);
            if (s.pending.remove(key) != null) {
                s.acked++;
                if (s.acked % 256 == 0) p.sendMessage("[JMBridge] " + s.acked + "/" + s.total);
            }
        } catch (IOException ignored) {}
    }

    private static long key(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID,Scan>> it = scans.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Scan s = e.getValue();
            if (p == null || !p.isOnline() || !p.getWorld().equals(s.world)) {
                it.remove();
                continue;
            }
            if (s.pending.values().stream().anyMatch(sent -> sent > 0 && now - sent > ACK_TIMEOUT_MS)) {
                p.sendMessage("[JMBridge] ACK timeout; stopped. Check client log.");
                it.remove();
                continue;
            }
            int started = 0;
            while (s.pending.size() < WINDOW && started < STARTS_PER_TICK && s.z <= s.maxZ) {
                int x = s.x, z = s.z;
                if (++s.x > s.maxX) { s.x = s.minX; s.z++; }
                if (!s.world.isChunkGenerated(x,z)) { s.missing++; continue; }
                long chunkKey = key(x,z);
                s.pending.put(chunkKey, 0L); // reserve slot before async load
                started++;
                s.world.getChunkAtAsync(x,z,false).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (scans.get(p.getUniqueId()) != s || !p.isOnline()) return;
                        if (!s.pending.containsKey(chunkKey)) return;
                        if (chunk == null || error != null) {
                            s.pending.remove(chunkKey);
                            s.missing++;
                            return;
                        }
                        try {
                            byte[] data = encode(chunk);
                            if (data.length > 30000) {
                                s.pending.remove(chunkKey);
                                s.missing++;
                                getLogger().warning("Too large chunk " + x + "," + z);
                                return;
                            }
                            s.pending.put(chunkKey, System.currentTimeMillis());
                            p.sendPluginMessage(this, CHANNEL, data);
                        } catch (Exception ex) {
                            s.pending.remove(chunkKey);
                            s.missing++;
                            getLogger().warning("Encoding " + x + "," + z + ": " + ex);
                        }
                    })
                );
            }
            if (s.z > s.maxZ && s.pending.isEmpty()) {
                p.sendMessage("[JMBridge] Done: " + s.acked + " mapped, " + s.missing + " skipped");
                it.remove();
            }
        }
    }

    private static byte[] encode(Chunk chunk) throws IOException {
        // Compact block-state palette, then one index for each of five exposed layers.
        // Surface-only mode: caves and subterranean layers are intentionally not transmitted.
        Map<String,Short> palette = new LinkedHashMap<>();
        short[][] layers = new short[256][5];
        short[] heights = new short[256];
        String[] biomes = new String[256];
        World world = chunk.getWorld(); int minY = world.getMinHeight(); int maxY = world.getMaxHeight()-1;
        for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
            int col = z*16+x;
            int worldX = (chunk.getX()<<4)+x, worldZ = (chunk.getZ()<<4)+z;
            int top = Math.max(minY,Math.min(maxY,world.getHighestBlockYAt(worldX,worldZ)));
            heights[col] = (short)top;
            for (int depth=0; depth<5; depth++) {
                int y=Math.max(minY,top-depth);
                Block b=chunk.getBlock(x,y,z);
                String key=b.getBlockData().getAsString();
                Short index=palette.get(key);
                if (index==null) { index=(short)palette.size(); palette.put(key,index); }
                layers[col][depth]=index;
            }
            biomes[col] = chunk.getBlock(x,top,z).getBiome().getKey().toString();
        }
        ByteArrayOutputStream plain=new ByteArrayOutputStream();
        DataOutputStream d=new DataOutputStream(plain);
        d.writeInt(MAGIC); d.writeByte(1);
        d.writeInt(chunk.getX()); d.writeInt(chunk.getZ());
        d.writeShort(minY); d.writeShort(maxY+1);
        d.writeShort(palette.size());
        for (String k:palette.keySet()) d.writeUTF(k);
        for (int i=0;i<256;i++) {
            d.writeShort(heights[i]);
            for(int depth=0;depth<5;depth++) d.writeShort(layers[i][depth]);
            d.writeUTF(biomes[i]);
        }
        d.flush();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(plain.toByteArray()); }
        return out.toByteArray();
    }

    private static final class Scan {
        final World world; final int minX,maxX,maxZ; final long total;
        int x,z,acked,missing;
        final Map<Long,Long> pending = new HashMap<>();
        Scan(World world,int minX,int maxX,int minZ,int maxZ) {
            this.world=world;this.minX=minX;this.maxX=maxX;this.maxZ=maxZ;this.x=minX;this.z=minZ;
            this.total=(long)(maxX-minX+1)*(maxZ-minZ+1);
        }
    }
}
