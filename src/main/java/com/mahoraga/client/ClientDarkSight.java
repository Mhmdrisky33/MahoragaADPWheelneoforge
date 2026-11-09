package com.mahoraga.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端这边的**黑暗透视名单** —— 服务端算好发过来，这里存着给渲染器用。
 *
 * 分成四类，各配一个颜色（见 {@link DarkSightRenderer}）。
 * 服务端只在名单**真的变了**的时候才发（包本身做了去重），所以这里
 * 平时就是静静地躺着一份数据，不会有额外的网络开销。
 */
public final class ClientDarkSight {
    private static final Set<Integer> hostile = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> passive = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> players = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> elite = ConcurrentHashMap.newKeySet();

    private ClientDarkSight() {
    }

    public static void set(List<Integer> hostileIds, List<Integer> passiveIds,
                           List<Integer> playerIds, List<Integer> eliteIds) {
        fill(hostile, hostileIds);
        fill(passive, passiveIds);
        fill(players, playerIds);
        fill(elite, eliteIds);
    }

    private static void fill(Set<Integer> target, List<Integer> ids) {
        target.clear();
        target.addAll(ids);
    }

    public static Set<Integer> hostile() {
        return hostile;
    }

    public static Set<Integer> passive() {
        return passive;
    }

    public static Set<Integer> players() {
        return players;
    }

    public static Set<Integer> elite() {
        return elite;
    }

    public static boolean isEmpty() {
        return hostile.isEmpty() && passive.isEmpty() && players.isEmpty() && elite.isEmpty();
    }

    /** 换维度 / 退出时清掉，免得残留一堆失效的实体 id。 */
    public static void clear() {
        hostile.clear();
        passive.clear();
        players.clear();
        elite.clear();
    }
}
