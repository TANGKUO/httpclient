/*
 * ====================================================================
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.conn.tsccm;

import java.lang.ref.ReferenceQueue;
import java.util.concurrent.TimeUnit;

import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.conn.OperatedClientConnection;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.conn.AbstractPoolEntry;

/**
 * Basic implementation of a connection pool entry.
 *
 * @since 4.0
 */
@NotThreadSafe
public class BasicPoolEntry extends AbstractPoolEntry {

    private final long created;
    
    private long updated;
    private long expiry;
    
    /**
     * @deprecated do not use
     */
    @Deprecated
    public BasicPoolEntry(ClientConnectionOperator op,
                          HttpRoute route,
                          ReferenceQueue<Object> queue) {
        super(op, route);
        if (route == null) {
            throw new IllegalArgumentException("HTTP route may not be null");
        }
        this.created = System.currentTimeMillis();
    }

    /**
     * Creates a new pool entry.
     *
     * @param op      the connection operator
     * @param route   the planned route for the connection
     */
    public BasicPoolEntry(ClientConnectionOperator op,
                          HttpRoute route) {
        super(op, route);
        if (route == null) {
            throw new IllegalArgumentException("HTTP route may not be null");
        }
        this.created = System.currentTimeMillis();
    }

    protected final OperatedClientConnection getConnection() {
        return super.connection;
    }

    protected final HttpRoute getPlannedRoute() {
        return super.route;
    }

    @Deprecated
    protected final BasicPoolEntryRef getWeakRef() {
        return null;
    }

    @Override
    protected void shutdownEntry() {
        super.shutdownEntry();
    }

    /**
     * @since 4.1
     */
    public long getCreated() {
        return this.created;
    }

    /**
     * @since 4.1
     */
    public long getUpdated() {
        return this.updated;
    }

    /**
     * @since 4.1
     */
    public long getExpiry() {
        return this.expiry;
    }

    /**
     * @since 4.1
     */
    public void updateExpiry(long time, TimeUnit timeunit) {
        this.updated = System.currentTimeMillis();
        if (time > 0) {
            this.expiry = this.updated + timeunit.toMillis(time);
        } else {
            this.expiry = Long.MAX_VALUE;
        }
    }

    /**
     * @since 4.1
     */
    public boolean isExpired(long now) {
        return now >= this.expiry;
    }
    
}

