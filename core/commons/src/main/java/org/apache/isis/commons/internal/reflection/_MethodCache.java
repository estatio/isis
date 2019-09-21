/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.isis.commons.internal.reflection;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.val;

public final class _MethodCache {

    public Method lookupMethod(Class<?> type, String name, Class<?>[] paramTypes) {
        
        if(!inspectedTypes.contains(type)) {
            for(val method : type.getDeclaredMethods()) {
                methodsByKey.put(Key.of(type, method), method);
            }
            inspectedTypes.add(type);
        }
        
        return methodsByKey.get(Key.of(type, name, nullify(paramTypes)));
    }

    public int size() {
        return methodsByKey.size();
    }

    // -- IMPLEMENATION DETAILS
    
    private Map<Key, Method> methodsByKey = new HashMap<>();
    private Set<Class<?>> inspectedTypes = new HashSet<>();
    
    @AllArgsConstructor(staticName = "of") @EqualsAndHashCode
    private final static class Key {
        private final Class<?> type;
        private final String name;
        private final Class<?>[] paramTypes;
        
        public static Key of(Class<?> type, Method method) {
            return Key.of(type, method.getName(), nullify(method.getParameterTypes()));
        }
        
        
    }
    
    private static Class<?>[] nullify(Class<?>[] x) {
        if(x!=null && x.length==0) {
            return null;
        }
        return x;
    }
    
    
    
}
