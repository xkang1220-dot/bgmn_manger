package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.FaDeprCategory;

import java.util.List;

public interface FaDeprCategoryService extends IService<FaDeprCategory> {

    Page<FaDeprCategory> pageCategories(long page, long pageSize, Long companyId, String name);

    List<FaDeprCategory> listEnabled(Long companyId);

    void createCategory(FaDeprCategory category);

    void updateCategory(FaDeprCategory category);

    void deleteCategory(Long id);
}
