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
package org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.persistence.spi;

import org.apache.isis.runtimes.dflt.objectstores.jdo.applib.AuditService;
import org.apache.isis.runtimes.dflt.runtime.persistence.objectstore.transaction.TransactionalResource;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.EnlistedObjectDirtying;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransaction;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransactionManager;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.MessageBroker;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.UpdateNotifier;

public class DataNucleusTransactionManager extends IsisTransactionManager {
    
    private final AuditService auditService;

    public DataNucleusTransactionManager(
            final EnlistedObjectDirtying objectPersistor, 
            final TransactionalResource objectStore, 
            final AuditService auditService) {
        super(objectPersistor, objectStore);
        this.auditService = auditService;
    }
    
    @Override
    protected IsisTransaction createTransaction(final MessageBroker messageBroker, final UpdateNotifier updateNotifier) {
        return new DataNucleusTransaction(this, messageBroker, updateNotifier, getTransactionalResource(), auditService);
    }

}
