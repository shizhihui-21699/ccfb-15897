package com.traceguard.model;

/** 扫码事件类型：一盒药在流通链路中可能被扫的各个环节。 */
public enum EventType {
    PRODUCE_SHIP("生产出库"),
    DISTRIBUTOR_IN("批发入库"),
    DISTRIBUTOR_OUT("批发出库"),
    RETAIL_IN("药店/医院入库"),
    SALE("整盒销售"),
    SPLIT_SALE("拆零销售"),
    PATIENT_RETURN("患者退货"),
    RETURN_TO_SUPPLIER("药店退货给供应商"),
    DESTROY("销毁");

    private final String label;

    EventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
