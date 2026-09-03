package com.kk.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysMenu;
import com.kk.system.entity.SysRoleMenu;
import com.kk.system.mapper.SysMenuMapper;
import com.kk.system.mapper.SysRoleMenuMapper;
import com.kk.system.service.SysMenuService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserService userService;

    @Override
    public List<SysMenu> tree() {
        List<SysMenu> menus = list(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
        return buildTree(menus);
    }

    @Override
    public List<SysMenu> treeByUserId(Long userId) {
        List<SysMenu> menus;
        if (userService.isAdmin(userId)) {
            menus = list(new LambdaQueryWrapper<SysMenu>()
                    .eq(SysMenu::getStatus, 1)
                    .eq(SysMenu::getVisible, 1)
                    .in(SysMenu::getType, 1, 2)
                    .orderByAsc(SysMenu::getSort));
        } else {
            menus = baseMapper.selectMenusByUserId(userId).stream()
                    .filter(m -> m.getType() != null && m.getType() < 3)
                    .filter(m -> m.getVisible() == null || m.getVisible() == 1)
                    .toList();
        }
        return buildTree(menus);
    }

    @Override
    public void createMenu(SysMenu menu) {
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        }
        if (menu.getStatus() == null) {
            menu.setStatus(1);
        }
        if (menu.getVisible() == null) {
            menu.setVisible(1);
        }
        save(menu);
    }

    @Override
    public void updateMenu(SysMenu menu) {
        updateById(menu);
    }

    @Override
    public void deleteMenu(Long id) {
        long children = count(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        if (children > 0) {
            throw new BusinessException("请先删除子菜单");
        }
        removeById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
    }

    private List<SysMenu> buildTree(List<SysMenu> menus) {
        Map<Long, List<SysMenu>> grouped = menus.stream()
                .collect(Collectors.groupingBy(m -> m.getParentId() == null ? 0L : m.getParentId()));
        for (SysMenu menu : menus) {
            List<SysMenu> children = grouped.getOrDefault(menu.getId(), new ArrayList<>());
            children.sort(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo)));
            menu.setChildren(children);
        }
        return grouped.getOrDefault(0L, new ArrayList<>());
    }
}
