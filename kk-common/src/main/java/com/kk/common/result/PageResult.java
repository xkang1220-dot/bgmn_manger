package com.kk.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class PageResult<T> implements Serializable {

    private List<T> list;
    private Long total;
    private Long page;
    private Long pageSize;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setList(page.getRecords());
        result.setTotal(page.getTotal());
        result.setPage(page.getCurrent());
        result.setPageSize(page.getSize());
        return result;
    }

    public static <T> PageResult<T> of(List<T> list, Long total, Long page, Long pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    public static <T> PageResult<T> empty() {
        PageResult<T> result = new PageResult<>();
        result.setList(new ArrayList<>());
        result.setTotal(0L);
        result.setPage(1L);
        result.setPageSize(10L);
        return result;
    }
}
