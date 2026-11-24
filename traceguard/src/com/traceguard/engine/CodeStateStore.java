package com.traceguard.engine;

/** 追溯码状态存储。单机用内存实现；生产实现见 README（Flink keyed state / Redis）。 */
public interface CodeStateStore {

    TraceCodeState getOrCreate(String traceCode);

    void put(TraceCodeState state);
}
