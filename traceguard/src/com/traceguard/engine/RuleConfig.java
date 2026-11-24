package com.traceguard.engine;

/**
 * 规则参数。实际部署时按省医保局的监管口径配置。
 *
 * @param returnWindowDays        患者退货合理窗口（天），超过视为可疑退货
 * @param impossibleTravelMinutes 同一追溯码在两地先后出现的最小合理间隔（分钟），小于它判定为码被复制
 * @param defaultUnitsPerBox      拆零场景下每盒最小单位数的缺省值（事件未携带时）
 */
public record RuleConfig(int returnWindowDays, int impossibleTravelMinutes, int defaultUnitsPerBox) {

    public static RuleConfig defaults() {
        return new RuleConfig(30, 60, 1);
    }
}
