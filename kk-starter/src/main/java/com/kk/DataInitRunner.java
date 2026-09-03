package com.kk;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.entity.FinPool;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements CommandLineRunner {

    private final SysUserService userService;
    private final FinanceService financeService;
    private final HrWalletService walletService;

    @Override
    public void run(String... args) {
        SysUser admin = userService.getByUsername("admin");
        if (admin != null && admin.getPassword() != null && !admin.getPassword().startsWith("$2")) {
            admin.setPassword(BCrypt.hashpw("admin123"));
            userService.updateById(admin);
            log.info("已将 admin 密码升级为 BCrypt");
        }
        if (financeService.count() == 0) {
            FinPool pool = new FinPool();
            pool.setName("公司主资金池");
            pool.setBalance(BigDecimal.ZERO);
            pool.setIsDefault(1);
            pool.setStatus(1);
            pool.setRemark("系统初始化");
            financeService.createPool(pool);
            log.info("已创建默认资金池");
        }
        if (admin != null) {
            walletService.getOrCreate(admin.getId());
        }
        log.info("KK 公司管理系统启动完成，默认账号 admin / admin123");
    }
}
