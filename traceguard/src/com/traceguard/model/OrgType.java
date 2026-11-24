package com.traceguard.model;

/** 扫码机构类型。 */
public enum OrgType {
    MANUFACTURER("生产企业"),
    DISTRIBUTOR("批发企业"),
    PHARMACY("零售药店"),
    HOSPITAL("医疗机构");

    private final String label;

    OrgType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
