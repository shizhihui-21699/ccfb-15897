package com.traceguard.model;

/** 追溯码的生命周期状态（由扫码记录回放推导）。 */
public enum LifecycleState {
    UNKNOWN("未激活"),
    PRODUCED("已生产出库"),
    IN_DISTRIBUTION("流通中(批发环节)"),
    AT_RETAIL("零售环节在架"),
    PARTIALLY_SOLD("拆零销售中"),
    SOLD("已售出"),
    DESTROYED("已销毁");

    private final String label;

    LifecycleState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
