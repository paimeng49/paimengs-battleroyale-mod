package com.paimeng.battleroyale.map;

import java.nio.file.Path;

/** 可导入 Minecraft 存档的服务端描述。 */
public record ArchiveEntry(String name, Path root, long sizeBytes, long modifiedTime, String status) {
    public ArchiveEntry(String name, Path root, long sizeBytes, long modifiedTime) {
        this(name, root, sizeBytes, modifiedTime, "已解压");
    }

    public String displayStatus() {
        return status == null || status.isBlank() ? "已解压" : status;
    }

    public String displaySize() {
        if (sizeBytes >= 1024L * 1024L * 1024L) return String.format("%.1f GB", sizeBytes / 1073741824.0);
        if (sizeBytes >= 1024L * 1024L) return String.format("%.1f MB", sizeBytes / 1048576.0);
        return Math.max(1L, sizeBytes / 1024L) + " KB";
    }
}
