/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.Controller;

/**
 * The principle idea comes from guava. However, the calculation of guava is
 * rate-based, which means that we need to translate rate to qps.
 *
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java
 *
 * Requests arriving at the pulse may drag down long idle systems even though it
 * has a much larger handling capability in stable period. It usually happens in
 * scenarios that require extra time for initialization, for example, db
 * establishes a connection; connects to a remote service, and so on.
 *
 * That’s why we need “warm up”.
 * <pre>
 * 原理来源于guava.guava的算法是基于比率的，意味着我们要转换成QPS。
 * 
 * 峰值请求可能会拖累系统，即便是在稳定时系统拥有更大的处理能力。
 * 这种情况经常在初始化时发生,比如db完成连接，连接到远程服务这些。
 * 
 * 这就是为什么我们需要预热。
 * 
 * </pre>
 * Sentinel’s “warm up” implementation is based on Guava's algorithm. However,
 * unlike Guava's scenario, which is a “leaky bucket”, and is mainly used to
 * adjust the request interval, Sentinel is more focus on controlling the count
 * of incoming requests per second without calculating its interval.
 *
 * Sentinel's "warm-up" implementation is based on the guava-based algorithm.
 * However, Guava’s implementation focus on adjusting the request interval, in
 * other words, a Leaky bucket. Sentinel pays more attention to controlling the
 * count of incoming requests per second without calculating its interval, it is
 * more like a “Token bucket.”
 *
 *
 * The remaining tokens in the bucket is used to measure the system utility.
 * Suppose a system can handle b requests per second. Every second b tokens will
 * be added into the bucket until the bucket is full. And when system processes
 * a request, it takes a token from the bucket. The more tokens left in the
 * bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 *  
 *
 * Base on Guava’s theory, there is a linear equation we can write this in the
 * form y = m * x + b where y (a.k.a y(x)), or qps(q)), is our expected QPS
 * given a saturated period (e.g. 3 minutes in), m is the rate of change from
 * our cold (minimum) rate to our stable (maximum) rate, x (or q) is the
 * occupied token.
 * <pre>
 * Sentinel 的预热实现基于guava的算法。但是不像guava的场景，他是一个漏斗，主要用于调整请求间隔。
 * Sentinel 更加专注于控制每秒的入站请求数目，不依赖于间隔.她更像是令牌桶。
 * 
 * 桶中的剩余令牌用于测量系统能力。
 * 如果一个系统每秒可以处理b个请求。
 * 每秒中将会有b个token被放入桶中，直到桶满了。
 * 当系统处理一个请求时，它从桶里拿一个令牌出来。
 * 桶中的令牌越多，系统的附在越轻；
 * 当桶中的令牌剩余量高于一个阈值，我们成为饱和状态。
 * 
 * 基于guava 的理论，我们得到一个线性的公式:
 *  y = m * x + b
 *  当 y (也就是y(x)) 或者qps(q) ) 是我们给定时间(比如3分钟)内的期望的QPS ，
 *  m 是 从冷到热的 斜率
 *  x是占用的令牌
 * 
 * </pre>

 * @author jialiang.linjl
 */
public class WarmUpController implements Controller {

    private double count;
    private int coldFactor;
    private int warningToken = 0;
    private int maxToken;
    private double slope;

    private AtomicLong storedTokens = new AtomicLong(0);
    private AtomicLong lastFilledTime = new AtomicLong(0);

    public WarmUpController(double count, int warmupPeriodInSec, int coldFactor) {
        construct(count, warmupPeriodInSec, coldFactor);
    }

    public WarmUpController(double count, int warmUpPeriodInMic) {
        construct(count, warmUpPeriodInMic, 3);
    }

    private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

        if (coldFactor <= 1) {
            throw new IllegalArgumentException("Cold factor should be larger than 1");
        }

        this.count = count;

        this.coldFactor = coldFactor;

        // thresholdPermits = 0.5 * warmupPeriod / stableInterval.
        // warningToken = 100;
        warningToken = (int)(warmUpPeriodInSec * count) / (coldFactor - 1);
        // / maxPermits = thresholdPermits + 2 * warmupPeriod /
        // (stableInterval + coldInterval)
        // maxToken = 200
        maxToken = warningToken + (int)(2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

        // slope
        // slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits
        // - thresholdPermits);
        slope = (coldFactor - 1.0) / count / (maxToken - warningToken);

    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        long passQps = node.passQps();

        long previousQps = node.previousPassQps();
        syncToken(previousQps);

        // 开始计算它的斜率
        // 如果进入了警戒线，开始调整他的qps
        long restToken = storedTokens.get();
        if (restToken >= warningToken) {
            long aboveToken = restToken - warningToken;
            // 消耗的速度要比warning快，但是要比慢
            // current interval = restToken*slope+1/count
            double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            if (passQps + acquireCount <= warningQps) {
                return true;
            }
        } else {
            if (passQps + acquireCount <= count) {
                return true;
            }
        }

        return false;
    }

    private void syncToken(long passQps) {
        long currentTime = TimeUtil.currentTimeMillis();
        currentTime = currentTime - currentTime % 1000;
        long oldLastFillTime = lastFilledTime.get();
        if (currentTime <= oldLastFillTime) {
            return;
        }

        long oldValue = storedTokens.get();
        long newValue = coolDownTokens(currentTime, passQps);

        if (storedTokens.compareAndSet(oldValue, newValue)) {
            long currentValue = storedTokens.addAndGet(0 - passQps);
            if (currentValue < 0) {
                storedTokens.set(0L);
            }
            lastFilledTime.set(currentTime);
        }

    }

    private long coolDownTokens(long currentTime, long passQps) {
        long oldValue = storedTokens.get();
        long newValue = oldValue;

        // 添加令牌的判断前提条件:
        // 当令牌的消耗程度远远低于警戒线的时候
        if (oldValue < warningToken) {
            newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
        } else if (oldValue > warningToken) {
            if (passQps < (int)count / coldFactor) {
                newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
            }
        }
        return Math.min(newValue, maxToken);
    }

}
