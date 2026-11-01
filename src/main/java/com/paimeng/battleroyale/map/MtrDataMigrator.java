package com.paimeng.battleroyale.map;

import com.paimeng.battleroyale.BattleRoyaleMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/** 在副本维度注册前，把 import 存档中的 MTR 维度目录复制到当前存档。 */
public final class MtrDataMigrator {
    private MtrDataMigrator() {}

    public static void migrateFor(MinecraftServer server, ArchiveEntry entry) {
        if (server == null || entry == null || entry.root() == null) {
            return;
        }

        Path source = entry.root().resolve("mtr").resolve("minecraft").resolve("overworld");
        if (!hasFiles(source)) {
            return;
        }

        try {
            BattleRoyaleMod.LOGGER.info("找到 MTR 源数据：{}", source);
            Path target = server.getWorldPath(LevelResource.ROOT)
                    .resolve("mtr")
                    .resolve(EditWorldManager.DIMENSION_NAMESPACE)
                    .resolve(EditWorldManager.dimensionId(entry.name()).getPath());

            if (hasFiles(target)) {
                BattleRoyaleMod.LOGGER.info("MTR 目标目录已有数据，直接复用：{}", target);
                return;
            }

            BattleRoyaleMod.LOGGER.info("复制 MTR 数据到：{}", target);
            int count = copyTree(source, target);
            BattleRoyaleMod.LOGGER.info("MTR 数据迁移完成，共 {} 个文件", count);
        } catch (IOException e) {
            BattleRoyaleMod.LOGGER.warn("MTR 数据迁移失败，跳过迁移并继续进入副本", e);
        } catch (RuntimeException e) {
            BattleRoyaleMod.LOGGER.warn("MTR 数据迁移失败，跳过迁移并继续进入副本", e);
        }
    }

    private static boolean hasFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private static int copyTree(Path source, Path target) throws IOException {
        int count = 0;
        try (Stream<Path> paths = Files.walk(source)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path destination = target.resolve(source.relativize(file));
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }
}
