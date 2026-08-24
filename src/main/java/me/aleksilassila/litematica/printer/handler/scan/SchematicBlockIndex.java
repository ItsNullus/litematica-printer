package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 预提取原理图世界中所有非空气方块的坐标索引,避免对巨大交互盒进行逐方块空间扫描.
 *
 * 针对稀疏原理图(如世吞)优化:原理图包围盒可能跨越数百个区块,
 * 但实际需要放置的方块不到一万,直接索引非空气位置可将扫描量从数亿降至数万.
 *
 * 索引按区块分桶存储,便于按玩家距离排序遍历.
 */
public final class SchematicBlockIndex {
    public static final SchematicBlockIndex INSTANCE = new SchematicBlockIndex();

    private static final int SECTION_SIZE = 16;

    private final LongOpenHashSet nonAirPositions = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> chunkBuckets = new Long2ObjectOpenHashMap<>();
    private int totalNonAir;
    private int indexedChunkCount;
    private State state = State.STALE;
    private WorldSchematic indexedSchematic;

    private SchematicBlockIndex() {
    }

    public enum State {
        STALE,
        BUILDING,
        READY
    }

    /**
     * 标记索引过期,下次访问时会重建.
     */
    public void invalidate() {
        this.state = State.STALE;
    }

    public boolean isReady() {
        return this.state == State.READY;
    }

    public int getTotalNonAir() {
        return this.totalNonAir;
    }

    /**
     * 确保索引已针对给定原理图世界构建完成.
     * 若索引已就绪且原理图实例和已加载区块数均未变,则直接返回;否则触发重建.
     */
    public void ensureBuilt(WorldSchematic schematic) {
        if (schematic == null) {
            this.state = State.STALE;
            this.indexedSchematic = null;
            return;
        }
        if (this.state == State.READY && this.indexedSchematic == schematic) {
            // 检测已加载区块数量是否变化(新原理图加载、区块异步加载等)
            int currentChunkCount = getLoadedChunkCount(schematic);
            if (currentChunkCount == this.indexedChunkCount) {
                return;
            }
        }
        if (this.state == State.BUILDING) {
            return;
        }
        this.build(schematic);
    }

    /**
     * 获取当前原理图世界已加载的区块数量.
     */
    private static int getLoadedChunkCount(WorldSchematic schematic) {
        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        //#if MC >= 12111
        return chunkManager.getLoadedValueSet().size();
        //#else
        //$$ return chunkManager.getLoadedChunks().size();
        //#endif
    }

    private void build(WorldSchematic schematic) {
        this.state = State.BUILDING;
        this.nonAirPositions.clear();
        this.chunkBuckets.clear();
        this.totalNonAir = 0;
        this.indexedSchematic = schematic;

        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        int bottomY = schematic.getMinY();
        int processedChunks = 0;

        //#if MC >= 12111
        for (ChunkSchematic chunk : chunkManager.getLoadedValueSet()) {
        //#else
        //$$ for (ChunkSchematic chunk : chunkManager.getLoadedChunks().values()) {
        //#endif
            if (chunk == null) {
                continue;
            }
            processedChunks++;
            ChunkPos pos = chunk.getPos();
            //#if MC >= 260100
            int chunkX = pos.x();
            int chunkZ = pos.z();
            long chunkKey = ChunkPos.pack(chunkX, chunkZ);
            //#else
            //$$ int chunkX = pos.x;
            //$$ int chunkZ = pos.z;
            //$$ long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            //#endif

            LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                int sectionBaseY = bottomY + (sectionIndex << 4);
                LongOpenHashSet bucket = this.chunkBuckets.computeIfAbsent(chunkKey, k -> new LongOpenHashSet());

                for (int y = 0; y < SECTION_SIZE; y++) {
                    for (int z = 0; z < SECTION_SIZE; z++) {
                        for (int x = 0; x < SECTION_SIZE; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (!state.isAir()) {
                                int worldX = (chunkX << 4) + x;
                                int worldY = sectionBaseY + y;
                                int worldZ = (chunkZ << 4) + z;
                                long posKey = BlockPos.asLong(worldX, worldY, worldZ);
                                if (this.nonAirPositions.add(posKey)) {
                                    bucket.add(posKey);
                                    this.totalNonAir++;
                                }
                            }
                        }
                    }
                }
            }
        }

        this.indexedChunkCount = processedChunks;
        this.state = State.READY;
    }

    /**
     * 返回所有非空气方块位置(以 BlockPos.asLong 编码).
     */
    public LongOpenHashSet getAllNonAirPositions() {
        return this.nonAirPositions;
    }

    /**
     * 返回按区块分桶的非空气位置映射.
     * 键为 ChunkPos.asLong,值为该区块内的非空气位置集合.
     */
    public Long2ObjectOpenHashMap<LongOpenHashSet> getChunkBuckets() {
        return this.chunkBuckets;
    }

    /**
     * 从索引中移除指定位置(放置成功后调用,避免重复检查).
     */
    public boolean removePosition(long posKey) {
        if (!this.nonAirPositions.remove(posKey)) {
            return false;
        }
        int x = BlockPos.getX(posKey);
        int z = BlockPos.getZ(posKey);
        //#if MC >= 260100
        long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
        //#else
        //$$ long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
        //#endif
        LongOpenHashSet bucket = this.chunkBuckets.get(chunkKey);
        if (bucket != null) {
            bucket.remove(posKey);
            if (bucket.isEmpty()) {
                this.chunkBuckets.remove(chunkKey);
            }
        }
        this.totalNonAir--;
        return true;
    }

    /**
     * 清空索引,释放内存.
     */
    public void clear() {
        this.nonAirPositions.clear();
        this.chunkBuckets.clear();
        this.totalNonAir = 0;
        this.indexedChunkCount = 0;
        this.state = State.STALE;
        this.indexedSchematic = null;
    }
}
