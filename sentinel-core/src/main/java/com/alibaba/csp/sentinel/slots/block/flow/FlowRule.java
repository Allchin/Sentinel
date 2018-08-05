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
package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/***
 * <p>
 *     Each flow rule is mainly composed of three factors: <strong>grade</strong>,
 * <strong>strategy</strong> and <strong>controlBehavior</strong>.
 * </p>
 * <ul>
 *     <li>The {@link #grade} represents the threshold type of flow control (by QPS or thread count).</li>
 *     <li>The {@link #strategy} represents the strategy based on invocation relation.</li>
 *     <li>The {@link #controlBehavior} represents the QPS shaping behavior (actions on incoming request when QPS
 *     exceeds the threshold).</li>
 * </ul>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 * 
 * <pre>
 * 每个限流规则主要考虑三个因素：分级，策略，控制行为
 * 分级代表了流控制的阈值类型，(按照qps,还是线程计数)
 * 策略代表基于调用关系的策略。
 * 控制行为代表QPS分片行为(当入站请求QPS到达阈值时的动作)
 * 
 * 
 * </pre>
 */
public class FlowRule extends AbstractRule {

    public static final String LIMIT_APP_DEFAULT = "default";
    public static final String LIMIT_APP_OTHER = "other";

    public FlowRule(){
        super();
        setLimitApp(LIMIT_APP_DEFAULT);
    }

    /**
     * 分级：是线程数目还是qps
     * 
     * The threshold type of flow control (0: thread count, 1: QPS).
     */
    private int grade = RuleConstant.FLOW_GRADE_QPS;

    private double count;

    /**
     * 0为直接限流;1为关联限流;2为链路限流
     */
    private int strategy = RuleConstant.STRATEGY_DIRECT;

    private String refResource;

    /**
     * <pre>
     * 频率限制器，限制行为
     * 0 默认
     * 1,预热
     * 2频率限制
     * </pre>
     * 
     * Rate limiter control behavior.
     * 0. default, 1. warm up, 2. rate limiter
     */
    private int controlBehavior = RuleConstant.CONTROL_BEHAVIOR_DEFAULT;

    private int warmUpPeriodSec = 10;

    /**
     * Max queueing time in rate limiter behavior.
     */
    private int maxQueueingTimeMs = 500;

    private Controller controller;

    public int getControlBehavior() {
        return controlBehavior;
    }

    public FlowRule setControlBehavior(int controlBehavior) {
        this.controlBehavior = controlBehavior;
        return this;
    }

    public int getMaxQueueingTimeMs() {
        return maxQueueingTimeMs;
    }

    public FlowRule setMaxQueueingTimeMs(int maxQueueingTimeMs) {
        this.maxQueueingTimeMs = maxQueueingTimeMs;
        return this;
    }

    public FlowRule setRater(Controller rater) {
        this.controller = rater;
        return this;
    }

    public int getWarmUpPeriodSec() {
        return warmUpPeriodSec;
    }

    public FlowRule setWarmUpPeriodSec(int warmUpPeriodSec) {
        this.warmUpPeriodSec = warmUpPeriodSec;
        return this;
    }

    public int getGrade() {
        return grade;
    }

    public FlowRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    public double getCount() {
        return count;
    }

    public FlowRule setCount(double count) {
        this.count = count;
        return this;
    }

    public int getStrategy() {
        return strategy;
    }

    public FlowRule setStrategy(int strategy) {
        this.strategy = strategy;
        return this;
    }

    public String getRefResource() {
        return refResource;
    }

    public FlowRule setRefResource(String refResource) {
        this.refResource = refResource;
        return this;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        String limitApp = this.getLimitApp();
        if (limitApp == null) {
            return true;
        }

        String origin = context.getOrigin();
        /**
         * 如果能找到一个节点，就放行
         * */
        Node selectedNode = selectNodeByRequesterAndStrategy(origin, context, node);
        if (selectedNode == null) {
            return true;
        }

        /**
         * 找不到就让控制器决策
         * */
        return controller.canPass(selectedNode, acquireCount);
    }

    /**
     * 根据请求者和策略
     * 选择节点
     * @param origin
     * @param context
     * @param node
     * @return
     */
    private Node selectNodeByRequesterAndStrategy(String origin, Context context, DefaultNode node) {
        // The limit app should not be empty.
        String limitApp = this.getLimitApp();

        if (limitApp.equals(origin)) {
        	//应用需要被限流
        	//策略是直接限流
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                return context.getOriginNode();
            }

            String refResource = this.getRefResource();
            if (StringUtil.isEmpty(refResource)) {
                return null;
            }
            /**
             * 关联策略 ,获取资源的集群节点
             * */
            if (strategy == RuleConstant.STRATEGY_RELATE) {
                return ClusterBuilderSlot.getClusterNode(refResource);
            }
            
            /**
             * 查看附加则策略
             * */
            if (strategy == RuleConstant.STRATEGY_CHAIN) {
                if (!refResource.equals(context.getName())) {
                    return null;
                }
                return node;
            }

        } else if (LIMIT_APP_DEFAULT.equals(limitApp)) {
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                return node.getClusterNode();
            }
            String refResource = this.getRefResource();
            if (StringUtil.isEmpty(refResource)) {
                return null;
            }

            if (strategy == RuleConstant.STRATEGY_RELATE) {
                return ClusterBuilderSlot.getClusterNode(refResource);
            }

            if (strategy == RuleConstant.STRATEGY_CHAIN) {
                if (!refResource.equals(context.getName())) {
                    return null;
                }
                return node;
            }

        } else if (LIMIT_APP_OTHER.equals(limitApp) && FlowRuleManager.isOtherOrigin(origin, getResource())) {
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                return context.getOriginNode();
            }

            String refResource = this.getRefResource();
            if (StringUtil.isEmpty(refResource)) {
                return null;
            }
            if (strategy == RuleConstant.STRATEGY_RELATE) {
                return ClusterBuilderSlot.getClusterNode(refResource);
            }

            if (strategy == RuleConstant.STRATEGY_CHAIN) {
                if (!refResource.equals(context.getName())) {
                    return null;
                }
                if (node != null) {
                    return node;
                }
            }
        }

        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlowRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        FlowRule flowRule = (FlowRule)o;

        if (grade != flowRule.grade) {
            return false;
        }
        if (Double.compare(flowRule.count, count) != 0) {
            return false;
        }
        if (strategy != flowRule.strategy) {
            return false;
        }
        if (refResource != null ? !refResource.equals(flowRule.refResource) : flowRule.refResource != null) {
            return false;
        }
        if (this.controlBehavior != flowRule.controlBehavior) {
            return false;
        }

        if (warmUpPeriodSec != flowRule.warmUpPeriodSec) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        long temp;
        result = 31 * result + grade;
        temp = Double.doubleToLongBits(count);
        result = 31 * result + (int)(temp ^ (temp >>> 32));
        result = 31 * result + strategy;
        result = 31 * result + (refResource != null ? refResource.hashCode() : 0);
        result = 31 * result + (int)(temp ^ (temp >>> 32));
        result = 31 * result + warmUpPeriodSec;
        result = 31 * result + controlBehavior;
        return result;
    }

    @Override
    public String toString() {
        return "FlowRule{" +
            "resource=" + getResource() +
            ", limitApp=" + getLimitApp() +
            ", grade=" + grade +
            ", count=" + count +
            ", strategy=" + strategy +
            ", refResource=" + refResource +
            ", controlBehavior=" + controlBehavior +
            ", warmUpPeriodSec=" + warmUpPeriodSec +
            ", maxQueueingTimeMs=" + maxQueueingTimeMs +
            ", controller=" + controller +
            "}";
    }
}
