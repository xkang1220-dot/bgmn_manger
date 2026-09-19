package com.kk.biz.enums;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 固定资产物品类别（与折旧类别 fa_depr_category 区分）。
 */
public enum FaItemType {

    ELECTRONICS("电子设备"),
    OFFICE_EQUIPMENT("办公设备"),
    FURNITURE("办公家具"),
    COMMUNICATION("通讯设备"),
    VEHICLE("交通工具"),
    TOOLS("工具器械"),
    OTHER("其他");

    private final String label;

    FaItemType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FaItemType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (FaItemType t : values()) {
            if (t.name().equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        return null;
    }

    public static String labelOf(String code) {
        FaItemType t = fromCode(code);
        return t == null ? code : t.getLabel();
    }

    public static Map<String, String> options() {
        Map<String, String> map = new LinkedHashMap<>();
        Arrays.stream(values()).forEach(t -> map.put(t.name(), t.getLabel()));
        return map;
    }

    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }
}
