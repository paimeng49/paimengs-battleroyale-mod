package com.paimeng.battleroyale.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 自定义事件总线：只依赖纯 Java，不引入任何 Minecraft / Fabric 类，
 * 升级到高版本时这个文件原样可用。
 *
 * <p>用法：
 * <pre>{@code
 * EventBus.INSTANCE.subscribe(GameStateChangedEvent.class, e -> { ... });
 * EventBus.INSTANCE.publish(new GameStateChangedEvent(...));
 * }</pre>
 *
 * <p>约定：publish 在调用线程上同步回调所有监听器。目前所有事件都从
 * 服务端主线程发布（指令、ServerTickEvents），监听器里可以直接操作世界。
 */
public final class EventBus {

    /** 全局唯一总线。模组内所有模块共用这一个实例。 */
    public static final EventBus INSTANCE = new EventBus();

    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    private EventBus() {
    }

    /** 订阅某类事件。同一个监听器注册两次会收到两次，请自行避免。 */
    public <T> void subscribe(Class<T> eventType, Consumer<? super T> listener) {
        listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 取消订阅；找不到就静默忽略。 */
    public <T> void unsubscribe(Class<T> eventType, Consumer<? super T> listener) {
        List<Consumer<?>> list = listeners.get(eventType);
        if (list != null) {
            list.remove(listener);
        }
    }

    /** 发布事件，按注册顺序在当前线程同步回调。 */
    public void publish(Object event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list == null) {
            return;
        }
        for (Consumer<?> consumer : list) {
            @SuppressWarnings("unchecked")
            Consumer<Object> listener = (Consumer<Object>) consumer;
            listener.accept(event);
        }
    }

    /** 当前订阅总数（调试用）。 */
    public int listenerCount() {
        return listeners.values().stream().mapToInt(List::size).sum();
    }
}
