package com.zhiguang.be.social.service;

import com.zhiguang.be.social.kafka.CounterEvent;

public interface CounterAggregationOperations {

    void acceptAggregateEvent(CounterEvent event);

    void flushAggregateBucketsNow();
}
