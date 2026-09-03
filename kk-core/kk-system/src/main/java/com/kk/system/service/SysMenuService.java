package com.kk.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.system.entity.SysMenu;

import java.util.List;

public interface SysMenuService extends IService<SysMenu> {

    List<SysMenu> tree();

    List<SysMenu> treeByUserId(Long userId);

    void createMenu(SysMenu menu);

    void updateMenu(SysMenu menu);

    void deleteMenu(Long id);
}
