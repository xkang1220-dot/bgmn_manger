package com.kk;

import cn.hutool.crypto.digest.BCrypt;
import com.kk.biz.entity.FinPool;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements CommandLineRunner {

    private final SysUserService userService;
    private final SysDeptService deptService;
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
            List<SysDept> companies = deptService.listCompanies();
            if (companies.isEmpty()) {
                log.warn("无公司节点，跳过默认资金池初始化");
            } else {
                FinPool pool = new FinPool();
                pool.setName("公司主资金池");
                pool.setBalance(BigDecimal.ZERO);
                pool.setIsDefault(1);
                pool.setStatus(1);
                pool.setRemark("系统初始化");
                pool.setCompanyId(companies.get(0).getId());
                financeService.createPool(pool);
                log.info("已创建默认资金池，公司={}", companies.get(0).getName());
            }
        }
        if (admin != null) {
            walletService.getOrCreate(admin.getId());
        }
        log.info("KK 公司管理系统启动完成");
    }
}
