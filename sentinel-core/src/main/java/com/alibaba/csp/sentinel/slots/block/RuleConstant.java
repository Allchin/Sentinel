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
package com.alibaba.csp.sentinel.slots.block;

/***
 * @author youji.zj
 * @author jialiang.linjl
 */
public class RuleConstant {

    /**
     * 按照线程个数限流
     */
    public static final int FLOW_GRADE_THREAD = 0;
    /**
     * 按照qps 来限流
     */
    public static final int FLOW_GRADE_QPS = 1;

    public static final int DEGRADE_GRADE_RT = 0;
    public static final int DEGRADE_GRADE_EXCEPTION = 1;

    /**
     * 白名单
     */
    public static final int WHILE = 0;
    /**
     * 黑名单
     */
    public static final int BLACK = 1;

    /**
     * 0为直接限流;1为关联限流;2为链路限流
     */
    public static final int STRATEGY_DIRECT = 0;
    /**
     * 1为关联限流;2为链路限流
     */
    public static final int STRATEGY_RELATE = 1;
    /**
     * 2为链路限流
     */
    public static final int STRATEGY_CHAIN = 2;

    /** 
     * 0 默认
     * 1,预热
     * 2频率限制
     */
    public static final int CONTROL_BEHAVIOR_DEFAULT = 0;
    /** 
 
     * 1,预热
     * 2频率限制
     */
    public static final int CONTROL_BEHAVIOR_WARM_UP = 1;
    /**
     * 2频率限制
     */
    public static final int CONTROL_BEHAVIOR_RATE_LIMITER = 2;

}
