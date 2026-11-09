package com.paimeng.battleroyale.map;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 区块文件体检器：在原版 {@code RegionFile} 真去读区块数据之前，先用只读通道核对
 * 扇区表与区块头，判断这个区块是不是坏数据（region 文件被截断、声明长度超出扇区容量、
 * 外部 {@code .mcc} 数据流缺失等）。
 *
 * <p>为什么要体检：原版 RegionFile 读坏区块时是自己打 ERROR
 * （{@code Chunk [x, z] stream is truncated} / {@code External chunk path c.x.z.mcc is not file}），
 * 异常并不抛给我们，外层 try-catch 拦不住那种刷屏。先体检、坏区块直接跳过，
 * 就只剩我们自己那条限频提示，扫描也绝不会中断。
 *
 * <p>只做读操作，绝不写盘；一个 region 文件一个实例，扇区表（头 4096 字节）一次性读进内存。
 */
public final class RegionHealthProbe implements AutoCloseable {

    private static final int SECTOR = 4096;
    private static final int CHUNKS_PER_REGION = 1024;
    /** 区块头版本字节的最高位：置位表示数据在外部 c.&lt;x&gt;.&lt;z&gt;.mcc 里。 */
    private static final int EXTERNAL_STREAM_FLAG = 0x80;

    private final FileChannel channel;
    private final long fileSize;
    private final int[] table = new int[CHUNKS_PER_REGION];

    private RegionHealthProbe(FileChannel channel, long fileSize) {
        this.channel = channel;
        this.fileSize = fileSize;
    }

    /** 打开 region 文件并读入扇区表；文件太小 / 读不出来直接抛 IOException（调用方按坏 region 处理）。 */
    public static RegionHealthProbe open(Path regionFile) throws IOException {
        FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ);
        try {
            long size = channel.size();
            if (size < SECTOR) {
                throw new IOException("region 文件过小（" + size + " 字节），扇区表都放不下");
            }
            RegionHealthProbe probe = new RegionHealthProbe(channel, size);
            ByteBuffer header = ByteBuffer.allocate(SECTOR);
            long position = 0L;
            while (header.hasRemaining()) {
                int read = channel.read(header, position);
                if (read <= 0) {
                    break;
                }
                position += read;
            }
            header.flip();
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                probe.table[i] = header.hasRemaining() ? header.getInt() : 0;
            }
            return probe;
        } catch (IOException | RuntimeException | Error e) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * 区块是否可读。
     *
     * @return {@code null} 表示没问题（区块不存在也算没问题，由调用方的 hasChunk 判断）；
     *         否则返回一句话损坏原因，调用方据此跳过该区块
     */
    public String problem(int chunkX, int chunkZ, Path regionDirectory) {
        int index = (chunkX & 31) + ((chunkZ & 31) * 32);
        int entry = table[index];
        int offset = entry >>> 8;
        int sectors = entry & 0xFF;
        if (offset == 0 || sectors == 0) {
            return null; // 这个区块从没写过数据
        }
        if (offset < 3) {
            return "扇区与 region 头部重叠（sector " + offset + "）";
        }
        long start = (long) offset * SECTOR;
        long end = start + (long) sectors * SECTOR;
        if (end > fileSize) {
            return "区块数据超出文件末尾（需要 " + end + " 字节，文件只有 " + fileSize + " 字节，region 被截断）";
        }
        ByteBuffer head = ByteBuffer.allocate(5);
        try {
            long read = channel.read(head, start);
            if (read < 5) {
                return "读不到完整区块头（只有 " + Math.max(read, 0) + " 字节）";
            }
        } catch (IOException e) {
            return "读取区块头失败：" + e;
        }
        head.flip();
        int declared = head.getInt();
        int version = head.get() & 0xFF;
        if (declared <= 0) {
            return "占用了扇区但声明长度是 " + declared + "（没有数据流）";
        }
        if ((version & EXTERNAL_STREAM_FLAG) != 0) {
            Path mcc = regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.isRegularFile(mcc)) {
                return "外部区块流文件缺失或不是普通文件：c." + chunkX + "." + chunkZ + ".mcc";
            }
            return null;
        }
        int streamVersion = version & 0x7F;
        if (streamVersion < 1 || streamVersion > 3) {
            return "区块压缩版本非法：" + streamVersion;
        }
        if (declared > (long) sectors * SECTOR - 5) {
            return "声明长度 " + declared + " 超过扇区容量（数据被截断）";
        }
        return null;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
