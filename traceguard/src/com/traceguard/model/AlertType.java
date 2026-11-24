package com.traceguard.model;

/** 告警类型。 */
public enum AlertType {
    /** 已退出流通（售出/销毁）的药品重新进入流通环节 —— 典型的"回流药"。 */
    REFLOW_DRUG("回流药"),
    /** 同一追溯码被重复销售、异地复制或超量拆零 —— 疑似假码/一码多卖。 */
    CODE_REUSE("一码多卖/假码"),
    /** 退货路径与销售路径不符（非原销售点退货、超期退货、未售先退）。 */
    ABNORMAL_RETURN("异常退货"),
    /** 扫码顺序不符合正常流通链路（可能漏扫，也可能串货，需人工复核）。 */
    SEQUENCE_VIOLATION("流向异常");

    private final String label;

    AlertType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
