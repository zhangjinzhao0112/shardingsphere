/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sharding.algorithm.sharding.complex;

import com.google.common.base.Preconditions;
import groovy.lang.Closure;
import groovy.util.Expando;
import lombok.Getter;
import org.apache.shardingsphere.infra.util.exception.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.util.exception.external.sql.type.generic.UnsupportedSQLOperationException;
import org.apache.shardingsphere.infra.util.expr.InlineExpressionParser;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Complex inline sharding algorithm.
 */
public final class ComplexInlineShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {
    
    private static final String ALGORITHM_EXPRESSION_KEY = "algorithm-expression";
    
    private static final String SHARING_COLUMNS_KEY = "sharding-columns";
    
    private static final String ALLOW_RANGE_QUERY_KEY = "allow-range-query-with-inline-sharding";
    
    @Getter
    private Properties props;
    
    private String algorithmExpression;
    
    private Collection<String> shardingColumns;
    
    private boolean allowRangeQuery;
    
    @Override
    public void init(final Properties props) {
        this.props = props;
        algorithmExpression = getAlgorithmExpression(props);
        shardingColumns = getShardingColumns(props);
        allowRangeQuery = getAllowRangeQuery(props);
    }
    
    private String getAlgorithmExpression(final Properties props) {
        String algorithmExpression = props.getProperty(ALGORITHM_EXPRESSION_KEY);
        Preconditions.checkNotNull(algorithmExpression, "Inline sharding algorithm expression can not be null.");
        return InlineExpressionParser.handlePlaceHolder(algorithmExpression.trim());
    }
    
    private Collection<String> getShardingColumns(final Properties props) {
        String shardingColumns = props.getProperty(SHARING_COLUMNS_KEY, "");
        return shardingColumns.isEmpty() ? Collections.emptyList() : Arrays.asList(shardingColumns.split(","));
    }
    
    private boolean getAllowRangeQuery(final Properties props) {
        return Boolean.parseBoolean(props.getOrDefault(ALLOW_RANGE_QUERY_KEY, Boolean.FALSE.toString()).toString());
    }
    
    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames, final ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        if (!shardingValue.getColumnNameAndRangeValuesMap().isEmpty()) {
            ShardingSpherePreconditions.checkState(allowRangeQuery,
                    new UnsupportedSQLOperationException(String.format("Since the property of `%s` is false, inline sharding algorithm can not tackle with range query", ALLOW_RANGE_QUERY_KEY)));
            return availableTargetNames;
        }
        Map<String, Collection<Comparable<?>>> columnNameAndShardingValuesMap = shardingValue.getColumnNameAndShardingValuesMap();
        if (!shardingColumns.isEmpty() && shardingColumns.size() != columnNameAndShardingValuesMap.size()) {
            throw new IllegalArgumentException("Complex inline need " + shardingColumns.size() + " sharing columns, but only found " + columnNameAndShardingValuesMap.size());
        }
        Collection<Map<String, Comparable<?>>> combine = combine(columnNameAndShardingValuesMap);
        return combine.stream().map(this::doSharding).collect(Collectors.toList());
    }
    
    private String doSharding(final Map<String, Comparable<?>> shardingValues) {
        Closure<?> closure = createClosure();
        for (Entry<String, Comparable<?>> entry : shardingValues.entrySet()) {
            closure.setProperty(entry.getKey(), entry.getValue());
        }
        return closure.call().toString();
    }
    
    private static <K, V> Collection<Map<K, V>> combine(final Map<K, Collection<V>> map) {
        Collection<Map<K, V>> result = new LinkedList<>();
        for (Entry<K, Collection<V>> entry : map.entrySet()) {
            if (result.isEmpty()) {
                for (V value : entry.getValue()) {
                    Map<K, V> item = new HashMap<>();
                    item.put(entry.getKey(), value);
                    result.add(item);
                }
            } else {
                Collection<Map<K, V>> list = new LinkedList<>();
                for (Map<K, V> loop : result) {
                    for (V value : entry.getValue()) {
                        Map<K, V> item = new HashMap<>();
                        item.put(entry.getKey(), value);
                        item.putAll(loop);
                        list.add(item);
                    }
                }
                result = list;
            }
        }
        return result;
    }
    
    private Closure<?> createClosure() {
        Closure<?> result = new InlineExpressionParser(algorithmExpression).evaluateClosure().rehydrate(new Expando(), null, null);
        result.setResolveStrategy(Closure.DELEGATE_ONLY);
        return result;
    }
    
    @Override
    public String getType() {
        return "COMPLEX_INLINE";
    }
}
