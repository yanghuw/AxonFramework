/*
 * Copyright (c) 2010-2014. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.common.jdbc;

import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wrapper for a ConnectionProvider that checks if a connection is already attached to the Unit of Work, favoring that
 * connection over creating a new one.
 *
 * @author Allard Buijze
 * @since 2.2
 */
public class UnitOfWorkAwareConnectionProviderWrapper implements ConnectionProvider {

    private static final String CONNECTION_RESOURCE_NAME = Connection.class.getName();

    private final ConnectionProvider delegate;
    private final boolean inherited;

    /**
     * Initializes a ConnectionProvider, using given <code>delegate</code> to create a new instance, when on is not
     * already attached to the Unit of Work. Nested Unit of Work will inherit the same connection.
     *
     * @param delegate The connection provider creating connections, when required
     */
    public UnitOfWorkAwareConnectionProviderWrapper(ConnectionProvider delegate) {
        this(delegate, true);
    }

    /**
     * Initializes a ConnectionProvider, using given <code>delegate</code> to create a new instance, when on is not
     * already attached to the Unit of Work. Given <code>attachAsInheritedResource</code> flag indicates whether
     * the resource should be inherited by nested Unit of Work.
     *
     * @param delegate                  The connection provider creating connections, when required
     * @param attachAsInheritedResource whether or not nested Units of Work should inherit connections
     */
    public UnitOfWorkAwareConnectionProviderWrapper(ConnectionProvider delegate, boolean attachAsInheritedResource) {
        this.delegate = delegate;
        this.inherited = attachAsInheritedResource;
    }


    @Override
    public Connection getConnection() throws SQLException {
        if (!CurrentUnitOfWork.isStarted()) {
            return delegate.getConnection();
        }

        UnitOfWork<?> uow = CurrentUnitOfWork.get();
        Connection connection = uow.root().getResource(CONNECTION_RESOURCE_NAME);
        if (connection == null || connection.isClosed()) {
            final Connection delegateConnection = delegate.getConnection();
            connection = ConnectionWrapperFactory.wrap(delegateConnection,
                                                       UoWAttachedConnection.class,
                                                       new UoWAttachedConnectionImpl(delegateConnection),
                                                       new ConnectionWrapperFactory.NoOpCloseHandler());
            uow.root().resources().put(CONNECTION_RESOURCE_NAME, connection);
            uow.onCommit(u -> {
                Connection cx = u.root().getResource(CONNECTION_RESOURCE_NAME);
                try {
                    if (!cx.getAutoCommit()) {
                        cx.commit();
                    }
                } catch (SQLException e) {
                    throw new JdbcTransactionException("Unable to commit transaction", e);
                }
            });
            uow.onCleanup(u -> {
                Connection cx = u.getResource(CONNECTION_RESOURCE_NAME);
                JdbcUtils.closeQuietly(cx);
                if (cx instanceof UoWAttachedConnection) {
                    ((UoWAttachedConnection) cx).forceClose();
                }
            });
            uow.onRollback(u -> {
                Connection cx = u.getResource(CONNECTION_RESOURCE_NAME);
                try {
                    if (!cx.isClosed() && !cx.getAutoCommit()) {
                        cx.rollback();
                    }
                } catch (SQLException ex) {
                    throw new JdbcTransactionException("Unable to rollback transaction", ex);
                }
            });
        }
        return connection;
    }

    private interface UoWAttachedConnection {

        void forceClose();
    }

    private static class UoWAttachedConnectionImpl implements UoWAttachedConnection {

        private final Connection delegateConnection;

        public UoWAttachedConnectionImpl(Connection delegateConnection) {
            this.delegateConnection = delegateConnection;
        }

        @Override
        public void forceClose() {
            JdbcUtils.closeQuietly(delegateConnection);
        }
    }
}
