package com.opsai.repo;

import com.opsai.entity.SysLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 操作日志仓库。
 * 本地系统日志量不大，取最近 500 条在内存里过滤就够了，不必上复杂的分页查询。
 */
public interface SysLogRepository extends JpaRepository<SysLog, Long> {

    /** 取最近的日志，按时间倒序 */
    List<SysLog> findTop500ByOrderByCreateTimeDesc();

    /** 某个用户的最近日志 */
    List<SysLog> findTop200ByUsernameOrderByCreateTimeDesc(String username);

    long countByAction(String action);
}
