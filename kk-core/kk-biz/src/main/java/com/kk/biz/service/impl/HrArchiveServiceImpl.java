package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrPayMethod;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.mapper.HrPayMethodMapper;
import com.kk.biz.service.HrArchiveService;
import com.kk.biz.service.HrWalletService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HrArchiveServiceImpl extends ServiceImpl<HrArchiveMapper, HrArchive> implements HrArchiveService {

    private static final Set<String> METHOD_TYPES = Set.of("BANK", "ALIPAY");

    private final SysUserService userService;
    private final SysDeptService deptService;
    private final HrWalletService walletService;
    private final HrPayMethodMapper payMethodMapper;

    @Override
    public Page<HrArchive> pageArchives(long page, long pageSize, String realName, String employeeNo) {
        Page<HrArchive> result = page(new Page<>(page, pageSize), new LambdaQueryWrapper<HrArchive>()
                .like(StringUtils.hasText(realName), HrArchive::getRealName, realName)
                .like(StringUtils.hasText(employeeNo), HrArchive::getEmployeeNo, employeeNo)
                .orderByDesc(HrArchive::getId));
        result.getRecords().forEach(this::fillUser);
        return result;
    }

    @Override
    public HrArchive getDetail(Long id) {
        HrArchive archive = getById(id);
        if (archive == null) {
            throw new BusinessException("档案不存在");
        }
        fillUser(archive);
        archive.setPayMethods(listMethods(id));
        return archive;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createArchive(HrArchive archive) {
        if (archive.getUserId() == null) {
            throw new BusinessException("必须绑定系统账号");
        }
        if (getByUserId(archive.getUserId()) != null) {
            throw new BusinessException("该账号已有档案");
        }
        save(archive);
        walletService.getOrCreate(archive.getUserId());
        syncPayMethods(archive.getId(), archive.getPayMethods());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateArchive(HrArchive archive) {
        if (archive.getId() == null) {
            throw new BusinessException("档案 ID 不能为空");
        }
        if (getById(archive.getId()) == null) {
            throw new BusinessException("档案不存在");
        }
        updateById(archive);
        if (archive.getPayMethods() != null) {
            syncPayMethods(archive.getId(), archive.getPayMethods());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteArchive(Long id) {
        removeById(id);
        payMethodMapper.delete(new LambdaQueryWrapper<HrPayMethod>().eq(HrPayMethod::getArchiveId, id));
    }

    @Override
    public HrArchive getByUserId(Long userId) {
        return getOne(new LambdaQueryWrapper<HrArchive>().eq(HrArchive::getUserId, userId).last("LIMIT 1"));
    }

    @Override
    public HrArchive getMine(Long userId) {
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        HrArchive existing = getByUserId(userId);
        if (existing != null) {
            return getDetail(existing.getId());
        }
        HrArchive empty = new HrArchive();
        empty.setUserId(userId);
        empty.setPayMethods(List.of());
        SysUser user = userService.getById(userId);
        if (user != null) {
            empty.setUsername(user.getUsername());
            empty.setNickname(user.getNickname());
            empty.setPhone(user.getPhone());
            if (StringUtils.hasText(user.getNickname())) {
                empty.setRealName(user.getNickname());
            } else if (StringUtils.hasText(user.getUsername())) {
                empty.setRealName(user.getUsername());
            }
            if (user.getDeptId() != null) {
                SysDept dept = deptService.getById(user.getDeptId());
                if (dept != null) {
                    empty.setDeptName(dept.getName());
                }
            }
        }
        return empty;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMine(Long userId, HrArchive archive) {
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        if (archive == null) {
            throw new BusinessException("档案内容不能为空");
        }
        if (!StringUtils.hasText(archive.getRealName())) {
            throw new BusinessException("请填写姓名");
        }
        String realName = archive.getRealName().trim();
        List<HrPayMethod> payMethods = archive.getPayMethods();

        HrArchive existing = getByUserId(userId);
        if (existing == null) {
            HrArchive created = new HrArchive();
            created.setUserId(userId);
            created.setRealName(realName);
            created.setEmployeeNo(trimToNull(archive.getEmployeeNo()));
            created.setPosition(trimToNull(archive.getPosition()));
            created.setEducation(trimToNull(archive.getEducation()));
            created.setEntryDate(archive.getEntryDate());
            created.setIdCard(trimToNull(archive.getIdCard()));
            created.setAddress(trimToNull(archive.getAddress()));
            created.setEmergencyContact(trimToNull(archive.getEmergencyContact()));
            created.setEmergencyPhone(trimToNull(archive.getEmergencyPhone()));
            created.setRemark(trimToNull(archive.getRemark()));
            created.setPayMethods(payMethods == null ? List.of() : payMethods);
            createArchive(created);
            return;
        }

        existing.setRealName(realName);
        existing.setEmployeeNo(trimToEmpty(archive.getEmployeeNo()));
        existing.setPosition(trimToEmpty(archive.getPosition()));
        existing.setEducation(trimToEmpty(archive.getEducation()));
        existing.setIdCard(trimToEmpty(archive.getIdCard()));
        existing.setAddress(trimToEmpty(archive.getAddress()));
        existing.setEmergencyContact(trimToEmpty(archive.getEmergencyContact()));
        existing.setEmergencyPhone(trimToEmpty(archive.getEmergencyPhone()));
        existing.setRemark(trimToEmpty(archive.getRemark()));
        existing.setEntryDate(archive.getEntryDate());
        updateById(existing);
        // updateById 不会写 null，入职日清空需单独处理
        if (archive.getEntryDate() == null) {
            lambdaUpdate()
                    .eq(HrArchive::getId, existing.getId())
                    .setSql("entry_date = NULL")
                    .update();
        }
        if (payMethods != null) {
            syncPayMethods(existing.getId(), payMethods);
        }
    }

    private static String trimToNull(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        return v.trim();
    }

    private static String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    @Override
    public List<HrPayMethod> listMyPayMethods(Long userId) {
        if (userId == null) {
            return List.of();
        }
        HrArchive archive = getByUserId(userId);
        if (archive == null) {
            return List.of();
        }
        return listMethods(archive.getId());
    }

    @Override
    public HrPayMethod getOwnedMethod(Long userId, Long methodId) {
        if (userId == null || methodId == null) {
            return null;
        }
        HrArchive archive = getByUserId(userId);
        if (archive == null) {
            return null;
        }
        HrPayMethod method = payMethodMapper.selectById(methodId);
        if (method == null || !Objects.equals(method.getArchiveId(), archive.getId())) {
            return null;
        }
        fillMethodLabel(method);
        return method;
    }

    private List<HrPayMethod> listMethods(Long archiveId) {
        List<HrPayMethod> list = payMethodMapper.selectList(new LambdaQueryWrapper<HrPayMethod>()
                .eq(HrPayMethod::getArchiveId, archiveId)
                .orderByDesc(HrPayMethod::getIsDefault)
                .orderByAsc(HrPayMethod::getSort)
                .orderByAsc(HrPayMethod::getId));
        list.forEach(this::fillMethodLabel);
        return list;
    }

    private void syncPayMethods(Long archiveId, List<HrPayMethod> methods) {
        if (archiveId == null) {
            return;
        }
        List<HrPayMethod> incoming = methods == null ? List.of() : methods;
        validateMethods(incoming);

        List<HrPayMethod> existing = payMethodMapper.selectList(new LambdaQueryWrapper<HrPayMethod>()
                .eq(HrPayMethod::getArchiveId, archiveId));
        Set<Long> keepIds = new HashSet<>();
        int sort = 0;
        boolean defaultSeen = false;
        for (HrPayMethod item : incoming) {
            String type = item.getMethodType().trim().toUpperCase();
            item.setMethodType(type);
            item.setArchiveId(archiveId);
            item.setSort(item.getSort() == null ? sort : item.getSort());
            sort++;
            if (!"BANK".equals(type)) {
                // 避免银行卡改支付宝/微信后开户行残留（updateById 默认忽略 null）
                item.setBankName("");
            }
            if (isDefaultFlag(item.getIsDefault()) && !defaultSeen) {
                defaultSeen = true;
                item.setIsDefault(1);
            } else {
                item.setIsDefault(0);
            }
            if (item.getId() != null && existing.stream().anyMatch(e -> Objects.equals(e.getId(), item.getId()))) {
                payMethodMapper.updateById(item);
                keepIds.add(item.getId());
            } else {
                item.setId(null);
                payMethodMapper.insert(item);
                keepIds.add(item.getId());
            }
        }
        for (HrPayMethod old : existing) {
            if (!keepIds.contains(old.getId())) {
                payMethodMapper.deleteById(old.getId());
            }
        }
        if (!incoming.isEmpty() && !defaultSeen) {
            List<HrPayMethod> ordered = listMethods(archiveId);
            if (!ordered.isEmpty()) {
                HrPayMethod prefer = ordered.get(0);
                prefer.setIsDefault(1);
                payMethodMapper.updateById(prefer);
            }
        }
    }

    private void validateMethods(List<HrPayMethod> methods) {
        int defaults = 0;
        for (HrPayMethod item : methods) {
            if (item == null) {
                throw new BusinessException("收款方式无效");
            }
            if (!StringUtils.hasText(item.getMethodType())
                    || !METHOD_TYPES.contains(item.getMethodType().trim().toUpperCase())) {
                throw new BusinessException("收款方式类型仅支持银行卡/支付宝");
            }
            if (!StringUtils.hasText(item.getAccountNo())) {
                throw new BusinessException("请填写收款账号");
            }
            String type = item.getMethodType().trim().toUpperCase();
            if ("BANK".equals(type) && !StringUtils.hasText(item.getBankName())) {
                throw new BusinessException("银行卡请填写开户行");
            }
            if (isDefaultFlag(item.getIsDefault())) {
                defaults++;
            }
        }
        if (defaults > 1) {
            throw new BusinessException("默认收款方式只能有一条");
        }
    }

    private static boolean isDefaultFlag(Integer value) {
        return Objects.equals(value, 1);
    }

    private void fillMethodLabel(HrPayMethod method) {
        if (method == null) {
            return;
        }
        method.setMethodTypeLabel(switch (String.valueOf(method.getMethodType())) {
            case "BANK" -> "银行卡";
            case "ALIPAY" -> "支付宝";
            case "WECHAT" -> "微信";
            default -> method.getMethodType();
        });
    }

    private void fillUser(HrArchive archive) {
        if (archive.getUserId() == null) {
            return;
        }
        SysUser user = userService.getById(archive.getUserId());
        if (user == null) {
            return;
        }
        archive.setUsername(user.getUsername());
        archive.setNickname(user.getNickname());
        archive.setPhone(user.getPhone());
        if (user.getDeptId() != null) {
            SysDept dept = deptService.getById(user.getDeptId());
            if (dept != null) {
                archive.setDeptName(dept.getName());
            }
        }
    }
}
