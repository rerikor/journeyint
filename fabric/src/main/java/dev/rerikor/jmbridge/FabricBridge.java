package dev.rerikor.jmbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import journeymap.client.JourneymapClient;
import journeymap.client.io.FileHandler;
import journeymap.client.model.map.MapType;
import journeymap.client.model.region.RegionCoord;
import journeymap.client.model.region.RegionImageCache;
import journeymap.client.model.chunk.SnapshotChunkMD;
import journeymap.common.chunk.JMChunkSnapshot;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Client-only receiver of externally loaded surface columns.
 * Produces ordinary JourneyMap region images using JourneyMap's own renderer.
 * No player movement or teleportation occurs. */
public final class FabricBridge implements ClientModInitializer {
    private static final int MAGIC = 0x4a4d4232;
    private static int handled = 0;

    public record Surface(byte[] bytes) implements CustomPacketPayload {
        public static final Type<Surface> TYPE = new Type<>(Identifier.parse("jmbridge:data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Surface> CODEC = new StreamCodec<>() {
            @Override public Surface decode(RegistryFriendlyByteBuf buf) {
                byte[] b = new byte[buf.readableBytes()]; buf.readBytes(b); return new Surface(b);
            }
            @Override public void encode(RegistryFriendlyByteBuf buf, Surface value) { buf.writeBytes(value.bytes()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @Override public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(Surface.TYPE, Surface.CODEC);
        // The acknowledgement is a small byte payload on the same registered channel.
        // Because this is a Paper plugin-message receiver, the ACK packet uses the
        // same payload identifier, not a Fabric networking-specific channel.
        PayloadTypeRegistry.serverboundPlay().register(Surface.TYPE, Surface.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Surface.TYPE, (payload, context) ->
            context.client().execute(() -> {
                try {
                    int[] pos = render(context.client(), payload.bytes());
                    if (pos != null) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        DataOutputStream d = new DataOutputStream(b);
                        d.writeInt(MAGIC); d.writeByte(2); d.writeInt(pos[0]); d.writeInt(pos[1]);
                        ClientPlayNetworking.send(new Surface(b.toByteArray()));
                    }
                } catch (Throwable ex) {
                    System.err.println("[JMBridge] Chunk render failed: " + ex);
                    ex.printStackTrace();
                }
            }));
    }

    private static int[] render(Minecraft mc, byte[] packed) throws Exception {
        if (mc.level == null || mc.player == null || !JourneymapClient.getInstance().isMapping()) return null;
        // Limit decompression size so a malformed server message cannot allocate unlimited memory.
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(packed))) {
            byte[] buffer = new byte[8192]; int n;
            while ((n=input.read(buffer)) != -1) {
                if (uncompressed.size()+n > 128000) throw new IOException("Oversized chunk packet");
                uncompressed.write(buffer,0,n);
            }
        }
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(uncompressed.toByteArray()));
        if (in.readInt()!=MAGIC || in.readUnsignedByte()!=1) throw new IOException("Bad packet header");
        int cx=in.readInt(), cz=in.readInt();
        int minY=in.readShort(), maxY=in.readShort();
        int count=in.readUnsignedShort();
        if(count<1 || count>8192 || minY>=maxY) throw new IOException("Bad palette");
        BlockState[] palette=new BlockState[count];
        for (int i=0;i<count;i++) palette[i]=parseBlock(in.readUTF());
        LevelChunk fake=new LevelChunk(mc.level,new ChunkPos(cx,cz));
        for(int index=0; index<256; index++) {
            int top=in.readShort();
            int x=index&15, z=index>>4;
            for(int depth=0;depth<5;depth++) {
                int stateIdx=in.readUnsignedShort();
                if(stateIdx>=count) throw new IOException("Invalid palette index");
                int y=top-depth;
                if(y>=mc.level.getMinY() && y<mc.level.getMaxY()) {
                    BlockState state=palette[stateIdx];
                    if(!state.isAir()) setFakeBlock(fake,new BlockPos(cx*16+x,y,cz*16+z),state);
                }
            }
            String biome = in.readUTF();
            // The biome name is retained in the wire format, but Minecraft 26.3
            // no longer exposes LevelChunkSection#setBiome. Do not mutate the
            // section biome palette here; default client biome is used for tint.

        }
        JMChunkSnapshot snap=JMChunkSnapshot.fromLevelChunk(fake,null);
        SnapshotChunkMD md=new SnapshotChunkMD(mc.level,snap,true) {
            // Chunk transport sends surface samples, not light sections.
            // Daytime map tiles should not become black because block-light data is absent.
            @Override public int getSavedLightValue(int x,int y,int z) { return 15; }
        };
        var jm=JourneymapClient.getInstance();
        var dimension=mc.level.dimension();
        var map=MapType.day(dimension);
        var directory=FileHandler.getJMWorldDir(mc);
        if(directory==null) throw new IOException("JourneyMap world directory not initialized");
        RegionCoord rc=RegionCoord.fromChunkPos(directory,map,cx,cz);
        if(!jm.getChunkRenderController().renderChunk(rc,map,md)) throw new IOException("JourneyMap declined chunk " + cx + "," + cz);
        handled++;
        if(handled%64==0) RegionImageCache.INSTANCE.flushToDiskAsync(true);
        return new int[]{cx,cz};
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState parseBlock(String descriptor) {
        try {
            int bracket=descriptor.indexOf('[');
            String name=bracket>=0 ? descriptor.substring(0,bracket) : descriptor;
            Block block=BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
            if(block==null) return Blocks.STONE.defaultBlockState();
            BlockState state=block.defaultBlockState();
            if(bracket>=0 && descriptor.endsWith("]")) {
                String properties=descriptor.substring(bracket+1,descriptor.length()-1);
                for(String token:properties.split(",")) {
                    String[] pair=token.split("=",2);
                    if(pair.length!=2) continue;
                    Property property=state.getBlock().getStateDefinition().getProperty(pair[0]);
                    if(property==null) continue;
                    var value=property.getValue(pair[1]);
                    if(value.isPresent()) state=state.setValue(property,(Comparable)value.get());
                }
            }
            return state;
        } catch(Exception ignored) { return Blocks.STONE.defaultBlockState(); }
    }

    private static volatile Method setter;
    /** Handles mapped API changes in the LevelChunk setBlockState overload. */
    private static void setFakeBlock(LevelChunk chunk,BlockPos pos,BlockState block) throws Exception {
        Method m=setter;
        if(m==null) {
            for(Method candidate:LevelChunk.class.getMethods()) {
                if(!candidate.getName().equals("setBlockState")) continue;
                Class<?>[] params=candidate.getParameterTypes();
                if(params.length==3 && params[0]==BlockPos.class && params[1]==BlockState.class && params[2]==int.class) { m=candidate; break; }
                if(params.length==2 && params[0]==BlockPos.class && params[1]==BlockState.class) { m=candidate; break; }
                if(params.length==3 && params[0]==BlockPos.class && params[1]==BlockState.class && params[2]==boolean.class) { m=candidate; break; }
            }
            if(m==null) throw new NoSuchMethodException("LevelChunk.setBlockState");
            setter=m;
        }
        if(m.getParameterCount()==2) m.invoke(chunk,pos,block);
        else if(m.getParameterTypes()[2]==boolean.class) m.invoke(chunk,pos,block,false);
        else m.invoke(chunk,pos,block,0);
    }
}
