/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.conn;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.ClientConnectionRequest;
import org.apache.http.conn.ManagedClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.params.HttpParams;


/**
 * A connection "manager" for a single connection.
 * This manager is good only for single-threaded use.
 * Allocation <i>always</i> returns the connection immediately,
 * even if it has not been released after the previous allocation.
 * In that case, a {@link #MISUSE_MESSAGE warning} is logged
 * and the previously issued connection is revoked.
 * <p>
 * This class is derived from <code>SimpleHttpConnectionManager</code>
 * in HttpClient 3. See there for original authors.
 * </p>
 *
 * @author <a href="mailto:rolandw at apache.org">Roland Weber</a>
 * @author <a href="mailto:becke@u.washington.edu">Michael Becke</a>
 *
 *
 * <!-- empty lines to avoid svn diff problems -->
 * @version   $Revision$
 *
 * @since 4.0
 */
public class SingleClientConnManager implements ClientConnectionManager {

    private static final Log LOG =
        LogFactory.getLog(SingleClientConnManager.class);

    /** The message to be logged on multiple allocation. */
    public final static String MISUSE_MESSAGE =
    "Invalid use of SingleClientConnManager: connection still allocated.\n" +
    "Make sure to release the connection before allocating another one.";


    /** The schemes supported by this connection manager. */
    protected SchemeRegistry schemeRegistry; 
    
    /** The operator for opening and updating connections. */
    protected ClientConnectionOperator connOperator;

    /** The one and only entry in this pool. */
    protected PoolEntry uniquePoolEntry;

    /** The currently issued managed connection, if any. */
    protected ConnAdapter managedConn;

    /** The time of the last connection release, or -1. */
    protected long lastReleaseTime;

    /** Whether the connection should be shut down  on release. */
    protected boolean alwaysShutDown;

    /** Indicates whether this connection manager is shut down. */
    protected volatile boolean isShutDown;




    /**
     * Creates a new simple connection manager.
     *
     * @param params    the parameters for this manager
     * @param schreg    the scheme registry, or
     *                  <code>null</code> for the default registry
     */
    public SingleClientConnManager(HttpParams params,
                                   SchemeRegistry schreg) {

        if (schreg == null) {
            throw new IllegalArgumentException
                ("Scheme registry must not be null.");
        }
        this.schemeRegistry  = schreg;
        this.connOperator    = createConnectionOperator(schreg);
        this.uniquePoolEntry = new PoolEntry();
        this.managedConn     = null;
        this.lastReleaseTime = -1L;
        this.alwaysShutDown  = false; //@@@ from params? as argument?
        this.isShutDown      = false;

    } // <constructor>


    @Override
    protected void finalize() throws Throwable {
        shutdown();
        super.finalize();
    }


    // non-javadoc, see interface ClientConnectionManager
    public SchemeRegistry getSchemeRegistry() {
        return this.schemeRegistry;
    }

    
    /**
     * Hook for creating the connection operator.
     * It is called by the constructor.
     * Derived classes can override this method to change the
     * instantiation of the operator.
     * The default implementation here instantiates
     * {@link DefaultClientConnectionOperator DefaultClientConnectionOperator}.
     *
     * @param schreg    the scheme registry to use, or <code>null</code>
     *
     * @return  the connection operator to use
     */
    protected ClientConnectionOperator
        createConnectionOperator(SchemeRegistry schreg) {

        return new DefaultClientConnectionOperator(schreg);
    }


    /**
     * Asserts that this manager is not shut down.
     *
     * @throws IllegalStateException    if this manager is shut down
     */
    protected final void assertStillUp()
        throws IllegalStateException {

        if (this.isShutDown)
            throw new IllegalStateException("Manager is shut down.");
    }


    public final ClientConnectionRequest requestConnection(
            final HttpRoute route,
            final Object state) {
        
        return new ClientConnectionRequest() {
            
            public void abortRequest() {
                // Nothing to abort, since requests are immediate.
            }
            
            public ManagedClientConnection getConnection(
                    long timeout, TimeUnit tunit) {
                return SingleClientConnManager.this.getConnection(
                        route, state);
            }
            
        };
    }


    /**
     * Obtains a connection.
     * This method does not block.
     *
     * @param route     where the connection should point to
     *
     * @return  a connection that can be used to communicate
     *          along the given route
     */
    public ManagedClientConnection getConnection(HttpRoute route, Object state) {

        if (route == null) {
            throw new IllegalArgumentException("Route may not be null.");
        }
        assertStillUp();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Get connection for route " + route);
        }

        if (managedConn != null)
            revokeConnection();

        // check re-usability of the connection
        if (uniquePoolEntry.connection.isOpen()) {
            final boolean shutdown =
                ((uniquePoolEntry.tracker == null) || // how could that happen?
                 !uniquePoolEntry.tracker.toRoute().equals(route));

            if (shutdown) {
                try {
                    uniquePoolEntry.shutdown();
                } catch (IOException iox) {
                    LOG.debug("Problem shutting down connection.", iox);
                    // create a new connection, just to be sure
                    uniquePoolEntry = new PoolEntry();
                }
            }
        }

        managedConn = new ConnAdapter(uniquePoolEntry, route);

        return managedConn;
    }


    // non-javadoc, see interface ClientConnectionManager
    public void releaseConnection(ManagedClientConnection conn) {
        assertStillUp();

        if (!(conn instanceof ConnAdapter)) {
            throw new IllegalArgumentException
                ("Connection class mismatch, " +
                 "connection not obtained from this manager.");
        }
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("Releasing connection " + conn);
        }

        ConnAdapter sca = (ConnAdapter) conn;
        if (sca.poolEntry == null)
            return; // already released
        ClientConnectionManager manager = sca.getManager();
        if (manager != null && manager != this) {
            throw new IllegalArgumentException
                ("Connection not obtained from this manager.");
        }

        try {
            // make sure that the response has been read completely
            if (sca.isOpen() && (this.alwaysShutDown ||
                                 !sca.isMarkedReusable())
                ) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug
                        ("Released connection open but not reusable.");
                }

                // make sure this connection will not be re-used
                // we might have gotten here because of a shutdown trigger
                // shutdown of the adapter also clears the tracked route
                sca.shutdown();
            }
        } catch (IOException iox) {
            //@@@ log as warning? let pass?
            if (LOG.isDebugEnabled())
                LOG.debug("Exception shutting down released connection.",
                          iox);
        } finally {
            sca.detach();
            managedConn = null;
            lastReleaseTime = System.currentTimeMillis();
        }
    } // releaseConnection


    // non-javadoc, see interface ClientConnectionManager
    public void closeIdleConnections(long idletime, TimeUnit tunit) {
        assertStillUp();

        // idletime can be 0 or negative, no problem there
        if (tunit == null) {
            throw new IllegalArgumentException("Time unit must not be null.");
        }

        if ((managedConn == null) && uniquePoolEntry.connection.isOpen()) {
            final long cutoff =
                System.currentTimeMillis() - tunit.toMillis(idletime);
            if (lastReleaseTime <= cutoff) {
                try {
                    uniquePoolEntry.close();
                } catch (IOException iox) {
                    // ignore
                    LOG.debug("Problem closing idle connection.", iox);
                }
            }
        }
    }


    // non-javadoc, see interface ClientConnectionManager
    public void shutdown() {

        this.isShutDown = true;

        if (managedConn != null)
            managedConn.detach();

        try {
            if (uniquePoolEntry != null) // and connection open?
                uniquePoolEntry.shutdown();
        } catch (IOException iox) {
            // ignore
            LOG.debug("Problem while shutting down manager.", iox);
        } finally {
            uniquePoolEntry = null;
        }
    }


    /**
     * Revokes the currently issued connection.
     * The adapter gets disconnected, the connection will be shut down.
     */
    protected void revokeConnection() {
        if (managedConn == null)
            return;

        LOG.warn(MISUSE_MESSAGE);

        managedConn.detach();

        try {
            uniquePoolEntry.shutdown();
        } catch (IOException iox) {
            // ignore
            LOG.debug("Problem while shutting down connection.", iox);
        }
    }

    
    /**
     * The pool entry for this connection manager.
     */
    protected class PoolEntry extends AbstractPoolEntry {

        /**
         * Creates a new pool entry.
         *
         */
        protected PoolEntry() {
            super(SingleClientConnManager.this.connOperator, null);
        }

        /**
         * Closes the connection in this pool entry.
         */
        protected void close()
            throws IOException {

            resetTrackedRoute();
            if (connection.isOpen())
                connection.close();
        }


        /**
         * Shuts down the connection in this pool entry.
         */
        protected void shutdown()
            throws IOException {

            resetTrackedRoute();
            if (connection.isOpen())
                connection.shutdown();
        }

    } // class PoolEntry



    /**
     * The connection adapter used by this manager.
     */
    protected class ConnAdapter extends AbstractPooledConnAdapter {

        /**
         * Creates a new connection adapter.
         *
         * @param entry   the pool entry for the connection being wrapped
         * @param route   the planned route for this connection
         */
        protected ConnAdapter(PoolEntry entry, HttpRoute route) {
            super(SingleClientConnManager.this, entry);
            markReusable();
            entry.route = route;
        }

    }


} // class SingleClientConnManager
