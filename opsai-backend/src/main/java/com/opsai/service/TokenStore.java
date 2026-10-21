package com.opsai.service;

/**
 * 登录会话的存储方式。
 *
 * <p>为什么要抽象这一层：
 * <ul>
 *   <li><b>单机部署</b>（默认）：放进程内存就够，不依赖任何外部组件。</li>
 *   <li><b>多台后端做负载均衡</b>：必须放 Redis。
 *       否则用户在 A 机登录，下一个请求被负载均衡打到 B 机，B 机不认识这个 token，
 *       表现就是「刚登录就被踢出去」。</li>
 * </ul>
 *
 * <p>具体实现由 {@code opsai.auth.store} 决定，默认 {@code memory}。
 */
public interface TokenStore {

    /** 保存会话，ttlSeconds 秒后自动过期 */
    void put(String token, Session session, long ttlSeconds);

    /** 取会话；不存在或已过期返回 null（实现方负责清理过期数据） */
    Session get(String token);

    /** 删除会话（退出登录） */
    void remove(String token);

    /** 当前实现的名字，启动日志里打印，便于确认配对了没有 */
    String kind();
}
