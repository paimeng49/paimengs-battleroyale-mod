package com.paimeng.battleroyale.client;

import com.paimeng.battleroyale.BattleRoyaleMod;
import com.paimeng.battleroyale.network.DownedSync;
import com.paimeng.battleroyale.network.DownedSyncPayload;
import com.paimeng.battleroyale.network.FlightSync;
import com.paimeng.battleroyale.network.LootPointsSync;
import com.paimeng.battleroyale.network.MapEditorSync;
import com.paimeng.battleroyale.network.MapEditorSyncPayload;
import com.paimeng.battleroyale.network.MapListSync;
import com.paimeng.battleroyale.network.MapListSyncPayload;
import com.paimeng.battleroyale.network.PingSync;
import com.paimeng.battleroyale.network.StatsSync;
import com.paimeng.battleroyale.network.TeamSync;
import com.paimeng.battleroyale.network.ZoneSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.EntityType;

@Environment(EnvType.CLIENT)
public final class BattleRoyaleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // [BR-MTR2] C-130 不再是实体：没有 EntityRendererRegistry，模型走
        // WorldRenderEvents.AFTER_ENTITIES 的数据驱动渲染（C130WorldRenderer）。
        ModKeyBindings.register();
        // [BR-ROLLBACK] END：按键判定 + 机上镜像坐标锁定（防抽搐核心）。
        // START_CLIENT_TICK 钩子连同 r89 的 sneakKey 槽位操作一起删除——本模组
        // 全生命周期不再触碰原版任何按键的绑定与槽位。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MapThumbnailGenerator.onClientTick();
            ModKeyBindings.tick(client);
            // [BR-ZONE] 模块六 3：本地维度保险——毒圈还挂着但世界已经切换
            // （被淘汰传送 / br leave 回大厅）就当场清空，不等服务端下一发 IDLE 包。
            ClientZoneState.expireIfNotIn(client.level == null ? null : client.level.dimension());
        });
        ClientPlayNetworking.registerGlobalReceiver(ZoneSync.CHANNEL_ID, (client, handler, buf, sender) -> ClientZoneState.apply(ZoneSync.decode(buf)));
        ClientPlayNetworking.registerGlobalReceiver(TeamSync.CHANNEL_ID, (client, handler, buf, sender) -> ClientTeamState.apply(TeamSync.decode(buf)));
        ClientPlayNetworking.registerGlobalReceiver(StatsSync.CHANNEL_ID, (client, handler, buf, sender) -> ClientStatsState.apply(StatsSync.decode(buf)));
        ClientPlayNetworking.registerGlobalReceiver(DownedSync.CHANNEL_ID, (client, handler, buf, sender) -> {
            DownedSyncPayload payload = DownedSync.decode(buf);
            if (payload != null && payload.isClear()) {
                ClientDownedState.clear(payload.playerId());
            } else {
                ClientDownedState.apply(payload);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MapEditorSync.OPEN_CHANNEL, (client, handler, buf, sender) -> {
            MapEditorSyncPayload payload = new MapEditorSyncPayload(MapEditorSyncPayload.read(buf));
            client.execute(() -> {
                if (client.screen instanceof MapEditorScreen screen) screen.setMap(payload.map());
                else client.setScreen(new MapEditorScreen(payload.map()));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MapEditorSync.ARCHIVES_CHANNEL, (client, handler, buf, sender) -> {
            var entries = MapEditorSyncPayload.readArchives(buf);
            client.execute(() -> {
                if (client.screen instanceof MapEditorScreen screen) {
                    screen.setArchives(entries);
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MapEditorSync.SELECTION_PAYLOAD, (client, handler, buf, sender) -> {
            boolean present = buf.readBoolean();
            int minX = 0, minZ = 0, maxX = 0, maxZ = 0;
            if (present) {
                minX = buf.readInt();
                minZ = buf.readInt();
                maxX = buf.readInt();
                maxZ = buf.readInt();
            }
            final int fMinX = minX, fMinZ = minZ, fMaxX = maxX, fMaxZ = maxZ;
            client.execute(() -> {
                if (client.screen instanceof MapEditorScreen screen) {
                    screen.setSelection(present, fMinX, fMinZ, fMaxX, fMaxZ);
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MapListSync.RESPONSE_CHANNEL, (client, handler, buf, sender) -> {
            var maps = MapListSyncPayload.read(buf);
            client.execute(() -> {
                if (client.screen instanceof MapEditorScreen screen) {
                    screen.setSavedMaps(maps);
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(PingSync.SHOW_CHANNEL_ID, (client, handler, buf, sender) -> ClientPingState.add(PingSync.decodeShow(buf)));
        ClientPlayNetworking.registerGlobalReceiver(LootPointsSync.CHANNEL_ID, (client, handler, buf, sender) -> {
            LootPointsSync.Payload payload = LootPointsSync.decode(buf);
            client.execute(() -> ClientLootPointsState.apply(payload));
        });
        // [BR-FLIGHT] 航线端点同步：收到后缓存到 ClientFlightState，地图每帧只读本地缓存。
        ClientPlayNetworking.registerGlobalReceiver(FlightSync.ROUTE_CHANNEL, (client, handler, buf, sender) -> {
            FlightSync.Payload payload = FlightSync.decode(buf);
            client.execute(() -> ClientFlightState.apply(payload));
        });
        // [BR-MTR2] 服务端每 tick 的权威进度：纯函数对表/软校正的唯一数据源
        // （替代实体追踪包）。回主线程取 world time 作为锚定时基，与渲染同一纪元。
        ClientPlayNetworking.registerGlobalReceiver(FlightSync.TICK_CHANNEL, (client, handler, buf, sender) -> {
            long serverTime = buf.readLong();
            int progressTicks = buf.readInt();
            client.execute(() -> ClientFlightState.applyTick(
                    serverTime, progressTicks, client.level == null ? 0L : client.level.getGameTime()));
        });
        // C2：小地图 Tile 缓存（S2C 接收 + 按需请求），本轮只存数据不参与渲染。
        ClientMapCache.registerClient();
        // [BR-MTR2] C-130：数据驱动世界渲染（实体管线已整体删除）。
        WorldRenderEvents.AFTER_ENTITIES.register(C130WorldRenderer::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ZoneWallRenderer::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PingRenderer::render);
        HudRenderCallback.EVENT.register(StatsHudRenderer::render);
        HudRenderCallback.EVENT.register(DownedHudRenderer::render);
        HudRenderCallback.EVENT.register(TeamHudRenderer::render);
        HudRenderCallback.EVENT.register(MapHudRenderer::render);
        // [BR-PARA] 跳伞降落伞：纯视觉，Fabric 原生 feature 注册回调。
        // 1.18.2 的 fabric-rendering-v1 自带 LivingEntityFeatureRendererRegistrationCallback，
        // 它在原版造好 PlayerRenderer 之后回调（反编译 MixinEntityRenderers
        // #createPlayerEntityRenderer 可见，EntityType.PLAYER 会触发），内部用的就是
        // 官方 LivingEntityRenderer features accessor —— 所以本工程一行渲染 Mixin 都不写，
        // 既不注入 PlayerRenderer#render，也不需要自己的 Accessor Mixin。
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
                (entityType, entityRenderer, registrationHelper, rendererContext) -> {
                    if (entityType == EntityType.PLAYER
                            && entityRenderer instanceof PlayerRenderer playerRenderer) {
                        registrationHelper.register(new ParachuteFeatureRenderer(playerRenderer));
                    }
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                ClientZoneState.clear();
                ClientTeamState.clear();
                ClientStatsState.clear();
                ClientDownedState.clear();
                ClientPingState.clear();
                ClientLootPointsState.clear();
                ClientFlightState.clear();
                MapThumbnailGenerator.clear();
                ClientMapCache.clear();
            } catch (Throwable t) {
                BattleRoyaleMod.LOGGER.warn("客户端断线时清理副本和 HUD 状态失败", t);
            }
        });
        PingClient.register();
        BattleRoyaleMod.LOGGER.info("[{}] 客户端地图编辑器与原有 HUD/同步已挂载", BattleRoyaleMod.MOD_ID);
    }
}
