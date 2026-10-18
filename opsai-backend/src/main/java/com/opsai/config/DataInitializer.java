package com.opsai.config;

import com.opsai.entity.SysUser;
import com.opsai.repo.SysUserRepository;
import com.opsai.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时补齐初始账号。
 * 好处：不需要手动执行 SQL 生成 BCrypt 密文，重装数据库后账号自动就位。
 * <p>
 * 初始账号（第一次演示用，正式使用请在系统里改密码）：
 *   admin    / admin123    管理员：全部权限
 *   engineer / engineer123 运维工程师：诊断、录知识
 *   viewer   / viewer123   只读
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserRepository userRepo;
    private final AuthService authService;

    @Override
    public void run(String... args) {
        seed("admin", "admin123", "系统管理员", "admin");
        seed("engineer", "engineer123", "运维工程师", "engineer");
        seed("viewer", "viewer123", "只读用户", "viewer");
    }

    private void seed(String username, String rawPassword, String realName, String role) {
        if (userRepo.findByUsername(username).isPresent()) return;

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(authService.hash(rawPassword));
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        userRepo.save(user);
        log.info("已创建初始账号：{} / {}（角色 {}）", username, rawPassword, role);
    }
}
