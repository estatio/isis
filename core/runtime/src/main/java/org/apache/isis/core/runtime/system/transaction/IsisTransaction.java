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

package org.apache.isis.core.runtime.system.transaction;

import static org.apache.isis.core.commons.ensure.Ensure.ensureThatArg;
import static org.apache.isis.core.commons.ensure.Ensure.ensureThatState;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.isis.applib.annotation.PublishedAction;
import org.apache.isis.applib.annotation.PublishedObject;
import org.apache.isis.applib.clock.Clock;
import org.apache.isis.applib.services.audit.AuditingService;
import org.apache.isis.applib.services.publish.EventMetadata;
import org.apache.isis.core.commons.authentication.AuthenticationSession;
import org.apache.isis.core.commons.components.TransactionScopedComponent;
import org.apache.isis.core.commons.ensure.Ensure;
import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.commons.lang.ToString;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.ResolveState;
import org.apache.isis.core.metamodel.adapter.mgr.AdapterManager;
import org.apache.isis.core.metamodel.adapter.oid.OidMarshaller;
import org.apache.isis.core.metamodel.adapter.oid.RootOid;
import org.apache.isis.core.metamodel.facets.actions.invoke.ActionInvocationFacet;
import org.apache.isis.core.metamodel.facets.actions.invoke.ActionInvocationFacet.CurrentInvocation;
import org.apache.isis.core.metamodel.facets.actions.publish.PublishedActionFacet;
import org.apache.isis.core.metamodel.facets.object.audit.AuditableFacet;
import org.apache.isis.core.metamodel.facets.object.publish.PublishedObjectFacet;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociationFilters;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.CreateObjectCommand;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.DestroyObjectCommand;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.PersistenceCommand;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.PublishingServiceWithDefaultPayloadFactories;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.SaveObjectCommand;
import org.apache.isis.core.runtime.persistence.objectstore.transaction.TransactionalResource;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.log4j.Logger;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Used by the {@link IsisTransactionManager} to captures a set of changes to be
 * applied.
 * 
 * <p>
 * The protocol by which the {@link IsisTransactionManager} interacts and uses
 * the {@link IsisTransaction} is not API, because different approaches are
 * used. For the server-side <tt>ObjectStoreTransactionManager</tt>, each object
 * is wrapped in a command generated by the underlying <tt>ObjectStore</tt>. for
 * the client-side <tt>ClientSideTransactionManager</tt>, the transaction simply
 * holds a set of events.
 * 
 * <p>
 * Note that methods such as <tt>flush()</tt>, <tt>commit()</tt> and
 * <tt>abort()</tt> are not part of the API. The place to control transactions
 * is through the {@link IsisTransactionManager transaction manager}, because
 * some implementations may support nesting and such like. It is also the job of
 * the {@link IsisTransactionManager} to ensure that the underlying persistence
 * mechanism (for example, the <tt>ObjectAdapterStore</tt>) is also committed.
 */
public class IsisTransaction implements TransactionScopedComponent {

    public static enum State {
        /**
         * Started, still in progress.
         * 
         * <p>
         * May {@link IsisTransaction#flush() flush},
         * {@link IsisTransaction#commit() commit} or
         * {@link IsisTransaction#abort() abort}.
         */
        IN_PROGRESS(true, true, true, false),
        /**
         * Started, but has hit an exception.
         * 
         * <p>
         * May not {@link IsisTransaction#flush()} or
         * {@link IsisTransaction#commit() commit} (will throw an
         * {@link IllegalStateException}), but can only
         * {@link IsisTransaction#abort() abort}.
         * 
         * <p>
         * Similar to <tt>setRollbackOnly</tt> in EJBs.
         */
        MUST_ABORT(false, false, true, false),
        /**
         * Completed, having successfully committed.
         * 
         * <p>
         * May not {@link IsisTransaction#flush()} or
         * {@link IsisTransaction#abort() abort} or
         * {@link IsisTransaction#commit() commit} (will throw
         * {@link IllegalStateException}).
         */
        COMMITTED(false, false, false, true),
        /**
         * Completed, having aborted.
         * 
         * <p>
         * May not {@link IsisTransaction#flush()},
         * {@link IsisTransaction#commit() commit} or
         * {@link IsisTransaction#abort() abort} (will throw
         * {@link IllegalStateException}).
         */
        ABORTED(false, false, false, true);

        private final boolean canFlush;
        private final boolean canCommit;
        private final boolean canAbort;
        private final boolean isComplete;

        private State(final boolean canFlush, final boolean canCommit, final boolean canAbort, final boolean isComplete) {
            this.canFlush = canFlush;
            this.canCommit = canCommit;
            this.canAbort = canAbort;
            this.isComplete = isComplete;
        }

        /**
         * Whether it is valid to {@link IsisTransaction#flush() flush} this
         * {@link IsisTransaction transaction}.
         */
        public boolean canFlush() {
            return canFlush;
        }

        /**
         * Whether it is valid to {@link IsisTransaction#commit() commit} this
         * {@link IsisTransaction transaction}.
         */
        public boolean canCommit() {
            return canCommit;
        }

        /**
         * Whether it is valid to {@link IsisTransaction#abort() abort} this
         * {@link IsisTransaction transaction}.
         */
        public boolean canAbort() {
            return canAbort;
        }

        /**
         * Whether the {@link IsisTransaction transaction} is complete (and so a
         * new one can be started).
         */
        public boolean isComplete() {
            return isComplete;
        }
    }


    private static final Logger LOG = Logger.getLogger(IsisTransaction.class);


    private final TransactionalResource objectStore;
    private final List<PersistenceCommand> commands = Lists.newArrayList();
    private final IsisTransactionManager transactionManager;
    private final org.apache.isis.core.commons.authentication.MessageBroker messageBroker;
    private final UpdateNotifier updateNotifier;
    private final List<IsisException> exceptions = Lists.newArrayList();

    /**
     * could be null if none has been registered
     */
    private final AuditingService auditingService;
    /**
     * could be null if none has been registered
     */
    private final PublishingServiceWithDefaultPayloadFactories publishingService;

    private State state;

    private RuntimeException cause;
    
    private final UUID guid;

    public IsisTransaction(final IsisTransactionManager transactionManager, final org.apache.isis.core.commons.authentication.MessageBroker messageBroker2, final UpdateNotifier updateNotifier, final TransactionalResource objectStore, final AuditingService auditingService, PublishingServiceWithDefaultPayloadFactories publishingService) {
        
        ensureThatArg(transactionManager, is(not(nullValue())), "transaction manager is required");
        ensureThatArg(messageBroker2, is(not(nullValue())), "message broker is required");
        ensureThatArg(updateNotifier, is(not(nullValue())), "update notifier is required");

        this.transactionManager = transactionManager;
        this.messageBroker = messageBroker2;
        this.updateNotifier = updateNotifier;
        this.auditingService = auditingService;
        this.publishingService = publishingService;
        
        this.guid = UUID.randomUUID();

        this.state = State.IN_PROGRESS;

        this.objectStore = objectStore;
        if (LOG.isDebugEnabled()) {
            LOG.debug("new transaction " + this);
        }
    }

    // ////////////////////////////////////////////////////////////////
    // GUID
    // ////////////////////////////////////////////////////////////////

    public final UUID getGuid() {
        return guid;
    }
    
    // ////////////////////////////////////////////////////////////////
    // State
    // ////////////////////////////////////////////////////////////////

    public State getState() {
        return state;
    }

    private void setState(final State state) {
        this.state = state;
    }

    
    // //////////////////////////////////////////////////////////
    // Commands
    // //////////////////////////////////////////////////////////

    /**
     * Add the non-null command to the list of commands to execute at the end of
     * the transaction.
     */
    public void addCommand(final PersistenceCommand command) {
        if (command == null) {
            return;
        }

        final ObjectAdapter onObject = command.onAdapter();

        // Saves are ignored when preceded by another save, or a delete
        if (command instanceof SaveObjectCommand) {
            if (alreadyHasCreate(onObject) || alreadyHasSave(onObject)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ignored command as object already created/saved" + command);
                }
                return;
            }

            if (alreadyHasDestroy(onObject)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ignored command " + command + " as object no longer exists");
                }
                return;
            }
        }

        // Destroys are ignored when preceded by a create, or another destroy
        if (command instanceof DestroyObjectCommand) {
            if (alreadyHasCreate(onObject)) {
                removeCreate(onObject);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ignored both create and destroy command " + command);
                }
                return;
            }

            if (alreadyHasSave(onObject)) {
                removeSave(onObject);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("removed prior save command " + command);
                }
            }

            if (alreadyHasDestroy(onObject)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ignored command " + command + " as command already recorded");
                }
                return;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("add command " + command);
        }
        commands.add(command);
    }



    /////////////////////////////////////////////////////////////////////////
    // for worm-hole handling of exceptions
    /////////////////////////////////////////////////////////////////////////

    public void ensureExceptionsListIsEmpty() {
        Ensure.ensureThatArg(exceptions.isEmpty(), is(true), "exceptions list is not empty");
    }

    public void addException(IsisException exception) {
        exceptions.add(exception);
    }
    
    public List<IsisException> getExceptionsIfAny() {
        return Collections.unmodifiableList(exceptions);
    }

    

    // ////////////////////////////////////////////////////////////////
    // flush
    // ////////////////////////////////////////////////////////////////

    public synchronized final void flush() {
        if(commands.isEmpty()) {
            // nothing to do
            return;
        }
        ensureThatState(getState().canFlush(), is(true), "state is: " + getState());
        if (LOG.isDebugEnabled()) {
            LOG.debug("flush transaction " + this);
        }

        try {
            doFlush();
        } catch (final RuntimeException ex) {
            setState(State.MUST_ABORT);
            setAbortCause(ex);
            throw ex;
        }
    }

    /**
     * Mandatory hook method for subclasses to persist all pending changes.
     * 
     * <p>
     * Called by both {@link #commit()} and by {@link #flush()}:
     * <table>
     * <tr>
     * <th>called from</th>
     * <th>next {@link #getState() state} if ok</th>
     * <th>next {@link #getState() state} if exception</th>
     * </tr>
     * <tr>
     * <td>{@link #commit()}</td>
     * <td>{@link State#COMMITTED}</td>
     * <td>{@link State#ABORTED}</td>
     * </tr>
     * <tr>
     * <td>{@link #flush()}</td>
     * <td>{@link State#IN_PROGRESS}</td>
     * <td>{@link State#MUST_ABORT}</td>
     * </tr>
     * </table>
     */
    private void doFlush() {
        
        try {
            
            objectStore.execute(Collections.unmodifiableList(commands));
            
            for (final PersistenceCommand command : commands) {
                if (command instanceof DestroyObjectCommand) {
                    final ObjectAdapter adapter = command.onAdapter();
                    adapter.setVersion(null);
                    adapter.changeState(ResolveState.DESTROYED);
                }
            }
        } finally {
            // even if there's an exception, we want to clear the commands
            // this is because the Wicket viewer uses an implementation of IsisContext 
            // whereby there are several threads which could be sharing the same context
            // if the first fails, we don't want the others to pick up the same command list
            // and try again
            commands.clear();
        }
    }

    
    protected void doAudit(final Set<Entry<AdapterAndProperty, PreAndPostValues>> changedObjectProperties) {
        if(auditingService == null) {
            return;
        }
        
        // else
        final String currentUser = getTransactionManager().getAuthenticationSession().getUserName();
        final long currentTimestampEpoch = currentTimestampEpoch();
        for (Entry<AdapterAndProperty, PreAndPostValues> auditEntry : changedObjectProperties) {
            auditChangedProperty(currentUser, currentTimestampEpoch, auditEntry);
        }
    }

    protected void doPublish(final Set<ObjectAdapter> changedAdapters) {
        if(publishingService == null) {
            return;
        }

        // else
        final String currentUser = getTransactionManager().getAuthenticationSession().getUserName();
        final long currentTimestampEpoch = currentTimestampEpoch();
        
        publishActionIfRequired(currentUser, currentTimestampEpoch);
        publishedChangedObjects(changedAdapters, currentUser, currentTimestampEpoch);
    }

    protected void publishActionIfRequired(final String currentUser, final long currentTimestampEpoch) {
        // TODO: need some transaction handling here
        
        try {
            final CurrentInvocation currentInvocation = ActionInvocationFacet.currentInvocation.get();
            if(currentInvocation == null) {
                return;
            } 
            final PublishedActionFacet publishedActionFacet = currentInvocation.getAction().getFacet(PublishedActionFacet.class);
            if(publishedActionFacet == null) {
                return;
            } 
            final PublishedAction.PayloadFactory payloadFactory = publishedActionFacet.value();
            final EventMetadata metadata = new EventMetadata(getGuid(), currentUser, currentTimestampEpoch);
            publishingService.publishAction(payloadFactory, metadata, currentInvocation);
        } finally {
            ActionInvocationFacet.currentInvocation.set(null);
        }
    }

    protected void publishedChangedObjects(final Set<ObjectAdapter> changedAdapters, final String currentUser, final long currentTimestampEpoch) {
        for (final ObjectAdapter changedAdapter : changedAdapters) {
            final PublishedObjectFacet publishedObjectFacet = changedAdapter.getSpecification().getFacet(PublishedObjectFacet.class);
            if(publishedObjectFacet == null) {
                continue;
            }
            final PublishedObject.PayloadFactory payloadFactory = publishedObjectFacet.value();
            final EventMetadata metadata = new EventMetadata(getGuid(), currentUser, currentTimestampEpoch);

            publishingService.publishObject(payloadFactory, metadata, changedAdapter);
        }
    }

    private static long currentTimestampEpoch() {
        return Clock.getTime();
    }

    private void auditChangedProperty(final String currentUser, final long currentTimestampEpoch, final Entry<AdapterAndProperty, PreAndPostValues> auditEntry) {
        final AdapterAndProperty aap = auditEntry.getKey();
        final ObjectAdapter adapter = aap.getAdapter();
        if(!adapter.getSpecification().containsFacet(AuditableFacet.class)) {
            return;
        }
        final RootOid oid = (RootOid) adapter.getOid();
        final String objectType = oid.getObjectSpecId().asString();
        final String identifier = oid.getIdentifier();
        final PreAndPostValues papv = auditEntry.getValue();
        final String preValue = asString(papv.getPre());
        final String postValue = asString(papv.getPost());
        auditingService.audit(currentUser, currentTimestampEpoch, objectType, identifier, preValue, postValue);
    }

    private static String asString(Object object) {
        return object != null? object.toString(): null;
    }


    protected AuthenticationSession getAuthenticationSession() {
        return IsisContext.getAuthenticationSession();
    }


    
    // ////////////////////////////////////////////////////////////////
    // commit
    // ////////////////////////////////////////////////////////////////

    public synchronized final void commit() {

        ensureThatState(getState().canCommit(), is(true), "state is: " + getState());
        ensureThatState(exceptions.isEmpty(), is(true), "cannot commit: " + exceptions.size() + " exceptions have been raised");

        if (LOG.isDebugEnabled()) {
            LOG.debug("commit transaction " + this);
        }

        if (getState() == State.COMMITTED) {
            if (LOG.isInfoEnabled()) {
                LOG.info("already committed; ignoring");
            }
            return;
        }
        
        try {
            doAudit(getChangedObjectProperties());
            doFlush();
            setState(State.COMMITTED);
            doPublish(getChangedObjects());
        } catch (final RuntimeException ex) {
            setAbortCause(ex);
            throw ex;
        }
    }

    



    // ////////////////////////////////////////////////////////////////
    // abort
    // ////////////////////////////////////////////////////////////////

    public synchronized final void abort() {
        ensureThatState(getState().canAbort(), is(true), "state is: " + getState());
        if (LOG.isInfoEnabled()) {
            LOG.info("abort transaction " + this);
        }

        setState(State.ABORTED);
    }

    

    private void setAbortCause(final RuntimeException cause) {
        this.cause = cause;
    }

    /**
     * The cause (if any) for the transaction being aborted.
     * 
     * <p>
     * Will be set if an exception is thrown while {@link #flush() flush}ing,
     * {@link #commit() commit}ting or {@link #abort() abort}ing.
     */
    public RuntimeException getAbortCause() {
        return cause;
    }

    
    
    // //////////////////////////////////////////////////////////
    // Helpers
    // //////////////////////////////////////////////////////////

    private boolean alreadyHasCommand(final Class<?> commandClass, final ObjectAdapter onObject) {
        return getCommand(commandClass, onObject) != null;
    }

    private boolean alreadyHasCreate(final ObjectAdapter onObject) {
        return alreadyHasCommand(CreateObjectCommand.class, onObject);
    }

    private boolean alreadyHasDestroy(final ObjectAdapter onObject) {
        return alreadyHasCommand(DestroyObjectCommand.class, onObject);
    }

    private boolean alreadyHasSave(final ObjectAdapter onObject) {
        return alreadyHasCommand(SaveObjectCommand.class, onObject);
    }

    private PersistenceCommand getCommand(final Class<?> commandClass, final ObjectAdapter onObject) {
        for (final PersistenceCommand command : commands) {
            if (command.onAdapter().equals(onObject)) {
                if (commandClass.isAssignableFrom(command.getClass())) {
                    return command;
                }
            }
        }
        return null;
    }

    private void removeCommand(final Class<?> commandClass, final ObjectAdapter onObject) {
        final PersistenceCommand toDelete = getCommand(commandClass, onObject);
        commands.remove(toDelete);
    }

    private void removeCreate(final ObjectAdapter onObject) {
        removeCommand(CreateObjectCommand.class, onObject);
    }

    private void removeSave(final ObjectAdapter onObject) {
        removeCommand(SaveObjectCommand.class, onObject);
    }

    // ////////////////////////////////////////////////////////////////
    // toString
    // ////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return appendTo(new ToString(this)).toString();
    }

    protected ToString appendTo(final ToString str) {
        str.append("state", state);
        str.append("commands", commands.size());
        return str;
    }


    // ////////////////////////////////////////////////////////////////
    // Depenendencies (from constructor)
    // ////////////////////////////////////////////////////////////////

    /**
     * The owning {@link IsisTransactionManager transaction manager}.
     * 
     * <p>
     * Injected in constructor
     */
    public IsisTransactionManager getTransactionManager() {
        return transactionManager;
    }

    /**
     * The {@link MessageBroker} for this transaction.
     * 
     * <p>
     * Injected in constructor
     *
     * @deprecated - obtain the {@link org.apache.isis.core.commons.authentication.MessageBroker} instead from the {@link AuthenticationSession}.
     */
    @Deprecated
    public MessageBroker getMessageBroker() {
        return (MessageBroker) messageBroker;
    }

    /**
     * The {@link UpdateNotifier} for this transaction.
     * 
     * <p>
     * Injected in constructor
     */
    public UpdateNotifier getUpdateNotifier() {
        return updateNotifier;
    }

    public static class AdapterAndProperty {
        
        private final ObjectAdapter objectAdapter;
        private final ObjectAssociation property;
        
        public static AdapterAndProperty of(ObjectAdapter adapter, ObjectAssociation property) {
            return new AdapterAndProperty(adapter, property);
        }

        private AdapterAndProperty(ObjectAdapter adapter, ObjectAssociation property) {
            this.objectAdapter = adapter;
            this.property = property;
        }
        
        public ObjectAdapter getAdapter() {
            return objectAdapter;
        }
        public ObjectAssociation getProperty() {
            return property;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((objectAdapter == null) ? 0 : objectAdapter.hashCode());
            result = prime * result + ((property == null) ? 0 : property.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            AdapterAndProperty other = (AdapterAndProperty) obj;
            if (objectAdapter == null) {
                if (other.objectAdapter != null)
                    return false;
            } else if (!objectAdapter.equals(other.objectAdapter))
                return false;
            if (property == null) {
                if (other.property != null)
                    return false;
            } else if (!property.equals(other.property))
                return false;
            return true;
        }
        
        @Override
        public String toString() {
            return getAdapter().getOid().enStringNoVersion(getMarshaller()) + " , " + getProperty().getId();
        }

        protected OidMarshaller getMarshaller() {
            return new OidMarshaller();
        }

        private Object getPropertyValue() {
            ObjectAdapter referencedAdapter = property.get(objectAdapter);
            return referencedAdapter == null ? null : referencedAdapter.getObject();
        }
    }
   
    
    ////////////////////////////////////////////////////////////////////////
    // Auditing/Publishing object tracking
    ////////////////////////////////////////////////////////////////////////

    public static class PreAndPostValues {
        
        private final static Predicate<Entry<?, PreAndPostValues>> CHANGED = new Predicate<Entry<?, PreAndPostValues>>(){
            @Override
            public boolean apply(Entry<?, PreAndPostValues> input) {
                final PreAndPostValues papv = input.getValue();
                return papv.differ();
            }};
            
        private final Object pre;
        private Object post;
        
        public static PreAndPostValues pre(Object preValue) {
            return new PreAndPostValues(preValue, null);
        }

        private PreAndPostValues(Object pre, Object post) {
            this.pre = pre;
            this.post = post;
        }
        public Object getPre() {
            return pre;
        }
        
        public Object getPost() {
            return post;
        }
        
        public void setPost(Object post) {
            this.post = post;
        }
        
        @Override
        public String toString() {
            return getPre() + " -> " + getPost();
        }

        public boolean differ() {
            return !Objects.equal(getPre(), getPost());
        }
    }
    
   
    private final Map<AdapterAndProperty, PreAndPostValues> changedObjectProperties = Maps.newLinkedHashMap();
    private final Set<ObjectAdapter> changedObjects = Sets.newLinkedHashSet();
    

    /**
     * For object stores to record the current values of an {@link ObjectAdapter} that has enlisted
     * into the transaction, prior to updating its values.
     * 
     * <p>
     * The values of the {@link ObjectAdapter} after being updated are captured when the
     * audit entries are requested, in {@link #getChangedObjectProperties()}.
     * 
     * <p>
     * Supported by the JDO object store; check documentation for support in other objectstores.
     */
    public void auditDirty(ObjectAdapter adapter) {
        for (ObjectAssociation property : adapter.getSpecification().getAssociations(ObjectAssociationFilters.PROPERTIES)) {
            changedObjectProperty(adapter, property);
        }
    }
    
    private void changedObjectProperty(ObjectAdapter adapter, ObjectAssociation property) {
        final AdapterAndProperty aap = AdapterAndProperty.of(adapter, property);
        PreAndPostValues papv = PreAndPostValues.pre(aap.getPropertyValue());
        changedObjectProperties.put(aap, papv);
        changedObjects.add(adapter);
    }


    /**
     * Returns the pre- and post-values of all {@link ObjectAdapter}s that were enlisted and dirtied
     * in this transaction.
     * 
     * <p>
     * This requires that the object store called {@link #auditDirty(ObjectAdapter)} for each object being
     * enlisted.
     * 
     * <p>
     * Supported by the JDO object store (since it calls {@link #auditDirty(ObjectAdapter)}); 
     * check documentation for support in other object stores.
     */
    public Set<Entry<AdapterAndProperty, PreAndPostValues>> getChangedObjectProperties() {
        updatePostValues(changedObjectProperties.entrySet());

        return Collections.unmodifiableSet(Sets.filter(changedObjectProperties.entrySet(), PreAndPostValues.CHANGED));
    }

    private static void updatePostValues(Set<Entry<AdapterAndProperty, PreAndPostValues>> entrySet) {
        for (Entry<AdapterAndProperty, PreAndPostValues> entry : entrySet) {
            final AdapterAndProperty aap = entry.getKey();
            final PreAndPostValues papv = entry.getValue();
            
            papv.setPost(aap.getPropertyValue());
        }
    }

    private Set<ObjectAdapter> getChangedObjects() {
        return changedObjects;
    }

    
    ////////////////////////////////////////////////////////////////////////
    // Dependencies (from context)
    ////////////////////////////////////////////////////////////////////////

    protected AdapterManager getAdapterManager() {
        return IsisContext.getPersistenceSession().getAdapterManager();
    }
    
}
