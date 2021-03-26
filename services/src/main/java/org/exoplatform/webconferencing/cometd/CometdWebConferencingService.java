/*
 * Copyright (C) 2003-2021 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.webconferencing.cometd;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import static org.exoplatform.webconferencing.Utils.asJSON;
import static org.exoplatform.webconferencing.Utils.parseISODate;
import static org.exoplatform.webconferencing.WebConferencingService.isValidArg;
import static org.exoplatform.webconferencing.WebConferencingService.isValidId;
import static org.exoplatform.webconferencing.WebConferencingService.isValidText;
import static org.exoplatform.webconferencing.cometd.CometdWebConferencingService.EventProxy.CLOSE;
import static org.exoplatform.webconferencing.cometd.CometdWebConferencingService.EventProxy.INIT;
import static org.exoplatform.webconferencing.support.CallLog.DEBUG_LEVEL;
import static org.exoplatform.webconferencing.support.CallLog.ERROR_LEVEL;
import static org.exoplatform.webconferencing.support.CallLog.INFO_LEVEL;
import static org.exoplatform.webconferencing.support.CallLog.TRACE_LEVEL;
import static org.exoplatform.webconferencing.support.CallLog.WARN_LEVEL;
import static org.exoplatform.webconferencing.support.CallLog.validate;

import org.cometd.annotation.Param;
import org.cometd.annotation.RemoteCall;
import org.cometd.annotation.ServerAnnotationProcessor;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.ChannelListener;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerChannel.SubscriptionListener;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.LifeCycle;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;
import org.picocontainer.Startable;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.services.cache.CacheListener;
import org.exoplatform.services.cache.CacheListenerContext;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.security.Authenticator;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;
import org.exoplatform.services.security.IdentityRegistry;
import org.exoplatform.webconferencing.CallInfo;
import org.exoplatform.webconferencing.CallInfoException;
import org.exoplatform.webconferencing.CallNotFoundException;
import org.exoplatform.webconferencing.CallState;
import org.exoplatform.webconferencing.IdentityData;
import org.exoplatform.webconferencing.IdentityStateException;
import org.exoplatform.webconferencing.InvitedIdentity;
import org.exoplatform.webconferencing.UserCallListener;
import org.exoplatform.webconferencing.UserState;
import org.exoplatform.webconferencing.WebConferencingService;
import org.exoplatform.webconferencing.client.ErrorInfo;
import org.exoplatform.webconferencing.cometd.CometdWebConferencingService.CallService.CallChannelContext.CallClient;
import org.exoplatform.webconferencing.support.CallLog;
import org.exoplatform.webconferencing.support.CallLogService;

/**
 * Created by The eXo Platform SAS.
 *
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CometdWebConferencingService.java 00000 Aug 17, 2017 pnedonosko $
 */
public class CometdWebConferencingService implements Startable {

  /** The Constant CALLS_CHANNEL_NAME. */
  public static final String             CALLS_CHANNEL_NAME                    = "/webconferencing/calls";

  /** The Constant LOGS_CHANNEL_NAME. */
  public static final String             LOGS_CHANNEL_NAME                     = "/webconferencing/logs";

  /** The Constant CALL_SUBSCRIPTION_CHANNEL_NAME. */
  public static final String             CALL_SUBSCRIPTION_CHANNEL_NAME        = "/eXo/Application/WebConferencing/call";

  /** The Constant CALL_SUBSCRIPTION_CHANNEL_NAME_ALL. */
  public static final String             CALL_SUBSCRIPTION_CHANNEL_NAME_ALL    = CALL_SUBSCRIPTION_CHANNEL_NAME + "/**";

  /** The Constant CALL_SUBSCRIPTION_CHANNEL_NAME_PATTERN. */
  public static final String             CALL_SUBSCRIPTION_CHANNEL_NAME_PATTERN =
                                                                               "/eXo/Application/WebConferencing/call/{callType}/{callInfo}";

  /** The Constant USER_SUBSCRIPTION_CHANNEL_NAME. */
  public static final String             USER_SUBSCRIPTION_CHANNEL_NAME        = "/eXo/Application/WebConferencing/user";

  /** The Constant USER_SUBSCRIPTION_CHANNEL_PATTERN. */
  public static final String             USER_SUBSCRIPTION_CHANNEL_PATTERN     = USER_SUBSCRIPTION_CHANNEL_NAME + "/{userId}";
  
  /** The Constant USER_CACHE_NAME. */
  public static final String             USER_CACHE_NAME            = "webconferencing.cometd.Cache".intern();

  /** The Constant COMMAND_GET. */
  public static final String             COMMAND_GET                           = "get";

  /** The Constant COMMAND_CREATE. */
  public static final String             COMMAND_CREATE                        = "create";

  /** The Constant COMMAND_CREATE_ORIGINS. */
  public static final String             COMMAND_CREATE_ORIGINS                = "create_origins";

  /** The Constant COMMAND_UPDATE. */
  public static final String             COMMAND_UPDATE                        = "update";

  /** The Constant COMMAND_DELETE. */
  public static final String             COMMAND_DELETE                        = "delete";

  /** The Constant COMMAND_ADD_GUEST. */
  public static final String             COMMAND_ADD_GUEST                     = "add_guest";

  /** The Constant COMMAND_UPDATE_INVITES. */
  public static final String             COMMAND_UPDATE_INVITES                = "update_invites";

  /** The Constant COMMAND_CHECK_INVITE. */
  public static final String             COMMAND_CHECK_INVITE                  = "check_invite";

  /** The Constant COMMAND_GET_CALLS_STATE. */
  public static final String             COMMAND_GET_CALLS_STATE               = "get_calls_state";

  /** The Constant COMMAND_GET_ORG_IDENTITIES. */
  public static final String             COMMAND_GET_ORG_IDENTITIES            = "get_org_identities";
  
  /** The Constant COMMAND_HAS_USER_PERMISSIONS. */
  public static final String             COMMAND_HAS_USER_PERMISSIONS            = "has_user_permissions";
  
  /** The Constant EVENT_CALL_LEAVED. */
  public static final String             EVENT_CALL_LEAVED = "call_leaved";
  
  /** The Constant EVENT_CALL_JOINED. */
  public static final String             EVENT_CALL_JOINED = "call_joined";
  
  /** The Constant EVENT_CALL_STATE. */
  public static final String             EVENT_CALL_STATE = "call_state";

  /** The Constant LOG_OK. */
  public static final String             LOG_OK                                = "{}";

  /**
   * Base minimum number of threads for remote calls' thread executors.
   */
  public static final int                MIN_THREADS                           = 2;

  /**
   * Minimal number of threads maximum possible for remote calls' thread executors.
   */
  public static final int                MIN_MAX_THREADS                       = 4;

  /** Thread idle time for thread executors (in seconds). */
  public static final int                THREAD_IDLE_TIME                      = 120;

  /**
   * Maximum threads per CPU for thread executors of user call channel.
   */
  public static final int                CALL_MAX_FACTOR                       = 20;

  /**
   * Queue size per CPU for thread executors of user call channel.
   */
  public static final int                CALL_QUEUE_FACTOR                     = CALL_MAX_FACTOR * 2;

  /**
   * Thread name used for calls executor.
   */
  public static final String             CALL_THREAD_PREFIX                    = "webconferencing-call-thread-";

  /** The Constant LOG. */
  private static final Log               LOG                                   =
                                             ExoLogger.getLogger(CometdWebConferencingService.class);

  /** The web conferencing. */
  protected final WebConferencingService webConferencing;

  /** The exo bayeux. */
  protected final EXoContinuationBayeux  exoBayeux;

  /** The service. */
  protected final CallService            service;

  /** The organization. */
  protected final OrganizationService    organization;

  /** The identity registry. */
  protected final IdentityRegistry       identityRegistry;

  /** The call logs. */
  protected final CallLogService         callLogs;

  /** The call handlers. */
  protected final ExecutorService        callHandlers;
  
  /** Cache of connected users. */
  protected final ExoCache<String, UserCallProxy>  usersCache;

  /**
   * The Class CallEventProxy.
   */
  static public class EventProxy implements Externalizable {

    /**
     * The Constant INIT - means it's initial event type (not actually call change, but user channel connected).
     */
    public static final String INIT  = "__init";

    /**
     * The Constant CLOSE - means it's closing event type (user call change, but user channel closed).
     */
    public static final String CLOSE = "__close";

    /** The event type. */
    protected String           type;

    /**
     * Instantiates a new event proxy.
     *
     * @param type the type
     */
    EventProxy(String type) {
      this.type = type;
    }

    /**
     * Instantiates a new event proxy of initial type (for serialization).
     */
    public EventProxy() {
      this(INIT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return super.toString() + "[" + getType() + "]";
    }

    /**
     * Checks if is an event of initial type.
     *
     * @return true, if is initial (i.e. user call channel established)
     */
    boolean isInitial() {
      return INIT.equals(getType());
    }

    /**
     * Checks if is an event of closing type.
     *
     * @return true, if is closing (i.e. user call channel leaved)
     */
    boolean isClosed() {
      return CLOSE.equals(getType());
    }

    /**
     * Gets the event type.
     *
     * @return the type
     */
    String getType() {
      return type;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeUTF(type);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
      this.type = in.readUTF();
    }
  }

  /**
   * The Class CallEventProxy.
   */
  static public abstract class CallEventProxy extends EventProxy implements Externalizable {

    /** The call id. */
    protected String callId;

    /** The provider type. */
    protected String providerType;

    /** The owner id. */
    protected String ownerId;

    /** The owner type. */
    protected String ownerType;

    /**
     * Instantiates a new call event proxy (for serialization).
     */
    public CallEventProxy() {
      super();
    }

    /**
     * Instantiates a new call event proxy.
     *
     * @param eventType the event type
     * @param callId the call id
     * @param providerType the provider type
     * @param ownerId the owner id
     * @param ownerType the owner type
     */
    CallEventProxy(String eventType, String callId, String providerType, String ownerId, String ownerType) {
      super(eventType);
      this.callId = callId;
      this.providerType = providerType;
      this.ownerId = ownerId;
      this.ownerType = ownerType;
    }

    /**
     * Gets the call id.
     *
     * @return the callId
     */
    String getCallId() {
      return callId;
    }

    /**
     * Gets the provider type.
     *
     * @return the providerType
     */
    String getProviderType() {
      return providerType;
    }

    /**
     * Gets the owner id.
     *
     * @return the ownerId
     */
    String getOwnerId() {
      return ownerId;
    }

    /**
     * Gets the owner type.
     *
     * @return the ownerType
     */
    String getOwnerType() {
      return ownerType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      super.writeExternal(out);
      out.writeUTF(callId);
      out.writeUTF(providerType);
      out.writeUTF(ownerId);
      out.writeUTF(ownerType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
      super.readExternal(in);
      this.callId = in.readUTF();
      this.providerType = in.readUTF();
      this.ownerId = in.readUTF();
      this.ownerType = in.readUTF();
    }
  }

  /**
   * The Class CallParticipantEvent (joined and leaved participant).
   */
  static public class CallParticipantEvent extends CallEventProxy implements Externalizable {

    /** The part id. */
    private String partId;

    /**
     * Instantiates a new call participant event (for serialization).
     */
    public CallParticipantEvent() {
      super();
    }

    /**
     * Instantiates a new call participant event.
     *
     * @param eventType the event type
     * @param callId the call id
     * @param providerType the provider type
     * @param ownerId the owner id
     * @param ownerType the owner type
     * @param partId the part id
     */
    CallParticipantEvent(String eventType, String callId, String providerType, String ownerId, String ownerType, String partId) {
      super(eventType, callId, providerType, ownerId, ownerType);
      this.partId = partId;
    }

    /**
     * Gets the part id.
     *
     * @return the partId
     */
    String getPartId() {
      return partId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      super.writeExternal(out);
      out.writeUTF(partId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
      super.readExternal(in);
      this.partId = in.readUTF();
    }
  }

  /**
   * The Class CallStateEvent (started, stopped calls).
   */
  static public class CallStateEvent extends CallEventProxy implements Externalizable {

    /** The state. */
    private String state;

    /**
     * Instantiates a new call state event (for serialization).
     */
    public CallStateEvent() {
      super();
    }

    /**
     * Instantiates a new call participant event.
     *
     * @param callId the call id
     * @param providerType the provider type
     * @param ownerId the owner id
     * @param ownerType the owner type
     * @param state the call state
     */
    CallStateEvent(String callId, String providerType, String ownerId, String ownerType, String state) {
      super(EVENT_CALL_STATE, callId, providerType, ownerId, ownerType);
      this.state = state;
    }

    /**
     * Gets the state.
     *
     * @return the state
     */
    String getState() {
      return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      super.writeExternal(out);
      out.writeUTF(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
      super.readExternal(in);
      this.state = in.readUTF();
    }
  }

  /**
   * The Class UserCallProxy.
   */
  static public class UserCallProxy implements Externalizable {

    /** The user id. */
    private String     userId;

    /** The client id. */
    private String     clientId;

    /** The state - it's a payload of the call in caches. */
    private EventProxy state;

    /**
     * Instantiates a new user call proxy (for serialization).
     */
    public UserCallProxy() {
      this.state = new EventProxy(); // initial state by default
    }

    /**
     * Instantiates a new user call proxy.
     *
     * @param userId the user id
     * @param clientId the client id
     * @param state the state
     */
    UserCallProxy(String userId, String clientId, EventProxy state) {
      this.userId = userId;
      this.clientId = clientId;
      this.state = state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return super.toString() + "[" + getId() + ", " + state.getType() + "]";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((clientId == null) ? 0 : clientId.hashCode());
      result = prime * result + ((userId == null) ? 0 : userId.hashCode());
      return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      UserCallProxy other = (UserCallProxy) obj;
      if (clientId == null) {
        if (other.clientId != null)
          return false;
      } else if (!clientId.equals(other.clientId))
        return false;
      if (userId == null) {
        if (other.userId != null)
          return false;
      } else if (!userId.equals(other.userId))
        return false;
      return true;
    }

    /**
     * Gets user proxy id.
     *
     * @return the id
     */
    public String getId() {
      return new StringBuilder(this.userId).append('@').append(this.clientId).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeUTF(userId);
      out.writeUTF(clientId);
      out.writeObject(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      this.userId = in.readUTF();
      this.clientId = in.readUTF();
      this.state = (EventProxy) in.readObject();
    }

    /**
     * Gets the user id.
     *
     * @return the userId
     */
    public String getUserId() {
      return userId;
    }

    /**
     * Gets the client id.
     *
     * @return the clientId
     */
    public String getClientId() {
      return clientId;
    }

    /**
     * Gets the state.
     *
     * @return the state
     */
    EventProxy getState() {
      return state;
    }

    /**
     * Sets the state.
     *
     * @param state the state to set
     */
    void setState(EventProxy state) {
      this.state = state;
    }
  }
  
  /**
   * Command thread factory adapted from {@link Executors#DefaultThreadFactory}.
   */
  static class CommandThreadFactory implements ThreadFactory {

    /** The group. */
    final ThreadGroup   group;

    /** The thread number. */
    final AtomicInteger threadNumber = new AtomicInteger(1);

    /** The name prefix. */
    final String        namePrefix;

    /**
     * Instantiates a new command thread factory.
     *
     * @param namePrefix the name prefix
     */
    CommandThreadFactory(String namePrefix) {
      SecurityManager s = System.getSecurityManager();
      this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
      this.namePrefix = namePrefix;
    }

    /**
     * New thread.
     *
     * @param r the r
     * @return the thread
     */
    public Thread newThread(Runnable r) {
      Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0) {

        /**
         * {@inheritDoc}
         */
        @Override
        protected void finalize() throws Throwable {
          super.finalize();
          threadNumber.decrementAndGet();
        }

      };
      if (t.isDaemon()) {
        t.setDaemon(false);
      }
      if (t.getPriority() != Thread.NORM_PRIORITY) {
        t.setPriority(Thread.NORM_PRIORITY);
      }
      return t;
    }
  }

  /**
   * The Class CallService.
   */
  @Service("webconferencing")
  public class CallService {

    /**
     * The Class ContainerCommand.
     */
    abstract class ContainerCommand implements Runnable {

      /** The container name. */
      final String containerName;

      /**
       * Instantiates a new container command.
       *
       * @param containerName the container name
       */
      ContainerCommand(String containerName) {
        this.containerName = containerName;
      }

      /**
       * Execute actual work of the commend (in extending class).
       *
       * @param exoContainer the exo container
       */
      abstract void execute(ExoContainer exoContainer);

      /**
       * Callback to execute on container error.
       *
       * @param error the error
       */
      abstract void onContainerError(String error);

      /**
       * {@inheritDoc}
       */
      @Override
      public void run() {
        if (isValidId(containerName)) {
          // Do the work under eXo container context (for proper work of eXo apps and JPA storage)
          ExoContainer exoContainer = ExoContainerContext.getContainerByName(containerName);
          if (exoContainer != null) {
            ExoContainer contextContainer = ExoContainerContext.getCurrentContainerIfPresent();
            try {
              // Container context
              ExoContainerContext.setCurrentContainer(exoContainer);
              RequestLifeCycle.begin(exoContainer);
              // do the work here
              execute(exoContainer);
            } finally {
              // Restore context
              RequestLifeCycle.end();
              ExoContainerContext.setCurrentContainer(contextContainer);
            }
          } else {
            // LOG.warn("Container not found " + containerName + " for remote call " + contextName);
            onContainerError("Container not found");
          }
        } else {
          onContainerError("Container required");
        }
      }
    }

    /**
     * The Class UserChannelContext.
     */
    class UserChannelContext {

      /** The clients. */
      final Set<String>      clients = ConcurrentHashMap.newKeySet();

      /** The listener. */
      final UserCallListener listener;
      
      /**
       * Instantiates a new channel context.
       *
       * @param listener the listener
       */
      UserChannelContext(UserCallListener listener) {
        super();
        this.listener = listener;
      }

      /**
       * Gets the listener.
       *
       * @return the listener
       */
      UserCallListener getListener() {
        return listener;
      }

      /**
       * Checks for no clients.
       *
       * @return true, if successful
       */
      boolean hasNoClients() {
        return clients.isEmpty();
      }

      /**
       * Checks for client.
       *
       * @param sessionId the session id
       * @return true, if successful
       */
      boolean hasClient(String sessionId) {
        return clients.contains(sessionId);
      }

      /**
       * Removes the client.
       *
       * @param sessionId the session id
       * @return true, if successful
       */
      boolean removeClient(String sessionId) {
        boolean res = clients.remove(sessionId);
        if (clients.size() == 0) {
          webConferencing.removeUserCallListener(listener);
          UserCallProxy proxy = null;
          if (usersCache != null) {
            // Put closing call proxy into the cache (for clustering channel notifications)
            // Other nodes will unregister their listener stub in its WebConf service instance
            proxy = new UserCallProxy(listener.getUserId(), listener.getClientId(), new EventProxy(CLOSE));
            usersCache.put(proxy.getId(), proxy);
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< Removed user call listener for " + listener.getUserId() + ", session:" + sessionId + " proxy:"
                + proxy);
          }
        } else if (res) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< Removed user call session for " + listener.getUserId() + ", session:" + sessionId);
          }
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< User call session was not removed for " + listener.getUserId() + ", session:" + sessionId);
          }
        }
        return res;
      }

      /**
       * Adds the client.
       *
       * @param sessionId the session id
       * @return true, if successful
       */
      boolean addClient(String sessionId) {
        boolean wasEmpty = clients.size() == 0;
        boolean res = clients.add(sessionId);
        if (wasEmpty && res) {
          webConferencing.addUserCallListener(listener);
          UserCallProxy proxy = null;
          if (usersCache != null) {
            // Add initial call proxy to the cache (for clustering channel notifications)
            // Other nodes will listen on the cache onPut and register a client stub listener to their WebConf service instances
            proxy = new UserCallProxy(listener.getUserId(), listener.getClientId(), new EventProxy(INIT));
            usersCache.put(proxy.getId(), proxy);
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< Added first user call listener for " + listener.getUserId() + ", session:" + sessionId + " proxy:"
                + proxy);
          }
        } else if (res) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< Added user call session for " + listener.getUserId() + ", session:" + sessionId);
          }
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("<<< User call session was not added for " + listener.getUserId() + ", session:" + sessionId);
          }
        }
        return res;
      }
    }

    /**
     * The Class CallChannelContext.
     */
    class CallChannelContext {

      /**
       * The Class CallClient.
       */
      class CallClient {

        /** The user id. */
        final String userId;

        /** The client id. */
        final String clientId;

        /**
         * Instantiates a new call client.
         *
         * @param userId the user id
         * @param clientId the client id
         */
        CallClient(String userId, String clientId) {
          super();
          this.userId = userId;
          this.clientId = clientId;
        }

        /**
         * Gets the user id.
         *
         * @return the userId
         */
        String getUserId() {
          return userId;
        }

        /**
         * Gets the client id.
         *
         * @return the clientId
         */
        String getClientId() {
          return clientId;
        }
      }

      /** The container name. */
      final String                  containerName;

      /** The clients. */
      final Map<String, CallClient> clients = new ConcurrentHashMap<>();

      /**
       * Instantiates a new user channel context.
       *
       * @param containerName the container name
       */
      CallChannelContext(String containerName) {
        this.containerName = containerName;
      }

      /**
       * Adds the client user.
       *
       * @param sessionId the session id
       * @param userId the user id
       * @param exoClientId the exo client id
       */
      void addUser(String sessionId, String userId, String exoClientId) {
        clients.put(sessionId, new CallClient(userId, exoClientId));
      }

      /**
       * Gets the client user.
       *
       * @param clientId the client id
       * @return the client user
       */
      CallClient getUser(String clientId) {
        return clients.get(clientId);
      }

      /**
       * Removes the client user.
       *
       * @param clientId the client id
       * @return the call client
       */
      CallClient removeUser(String clientId) {
        return clients.remove(clientId);
      }

      /**
       * Gets the container name.
       *
       * @return the containerName
       */
      String getContainerName() {
        return containerName;
      }

      /**
       * Checks if the call channel has clients.
       *
       * @return true, if successful
       */
      boolean hasClients() {
        return clients.size() > 0;
      }
    }

    /**
     * The listener interface for receiving channelSubscription events.
     * The class that is interested in processing a channelSubscription
     * state implements this interface, and the object created
     * with that class is registered with a component using the
     * component's <code>addChannelSubscriptionListener<code> method. When
     * the channelSubscription state occurs, that object's appropriate
     * method is invoked.
     *
     * @see ChannelSubscriptionEvent
     */
    class ChannelSubscriptionListener implements SubscriptionListener {

      /**
       * {@inheritDoc}
       */
      @Override
      public void subscribed(ServerSession remote, ServerChannel channel, ServerMessage message) {
        // FYI message will be null for server-side subscription
        String sessionId = remote.getId();
        String channelId = channel.getId();
        String currentUserId = currentUserId(message);
        String exoClientId = asString(message.get("exoClientId"));
        String exoContainerName = asString(message.get("exoContainerName"));
        if (LOG.isDebugEnabled()) {
          LOG.debug(">> Subscribed: " + currentUserId + ", session:" + sessionId + " (" + exoContainerName + "@" + exoClientId
              + "), channel:" + channelId);
        }
        if (channelId.startsWith(USER_SUBSCRIPTION_CHANNEL_NAME)) {
          try {
            if (currentUserId != null) {
              String userId = channelUserId(channelId);
              if (currentUserId.equals(userId)) {
                // Tracking channel-to-clients mapping looks redundant, at first thought client-to-listener is
                // enough, but we keep channel's clients also for understanding consistency during development
                // and debug
                // Sep 8 2017: We need a single user listener per his channel, if add more then we'll have
                // multiple events for a single update

                userChannelContext.computeIfAbsent(userId, k -> {
                  // TODO exoClientId better use in addClient()
                  UserCallListener listener = new UserCallListener(userId, exoClientId) {
                    @Override
                    public void onPartLeaved(String callId,
                                             String providerType,
                                             String ownerId,
                                             String ownerType,
                                             String partId) {
                      StringBuilder data = new StringBuilder();
                      data.append('{');
                      data.append("\"eventType\": \"");
                      data.append(EVENT_CALL_LEAVED);
                      data.append("\",");
                      data.append("\"callId\": \"");
                      data.append(callId);
                      data.append("\",\"providerType\": \"");
                      data.append(providerType);
                      data.append("\",\"part\": {");
                      data.append("\"id\": \"");
                      data.append(partId);
                      data.append("\"},\"owner\": {");
                      data.append("\"id\": \"");
                      data.append(ownerId);
                      data.append("\",\"type\": \"");
                      data.append(ownerType);
                      data.append("\"}");
                      data.append('}');
                      bayeux.getChannel(channelId).publish(serverSession, data.toString());
                      if (LOG.isDebugEnabled()) {
                        LOG.debug(">>> Sent call leaved to " + channelId + " call: " + callId + "[" + partId + "] by " + currentUserId(null));
                      }
                    }

                    @Override
                    public void onPartJoined(String callId,
                                             String providerType,
                                             String ownerId,
                                             String ownerType,
                                             String partId) {
                      StringBuilder data = new StringBuilder();
                      data.append('{');
                      data.append("\"eventType\": \"");
                      data.append(EVENT_CALL_JOINED);
                      data.append("\",");
                      data.append("\"callId\": \"");
                      data.append(callId);
                      data.append("\",\"providerType\": \"");
                      data.append(providerType);
                      data.append("\",\"part\": {");
                      data.append("\"id\": \"");
                      data.append(partId);
                      data.append("\"},\"owner\": {");
                      data.append("\"id\": \"");
                      data.append(ownerId);
                      data.append("\",\"type\": \"");
                      data.append(ownerType);
                      data.append("\"}");
                      data.append('}');
                      bayeux.getChannel(channelId).publish(serverSession, data.toString());
                      if (LOG.isDebugEnabled()) {
                        LOG.debug(">>> Sent call joined to " + channelId + " call: " + callId + "[" + partId + "] by " + currentUserId(null));
                      }
                    }

                    @Override
                    public void onCallStateChanged(String callId,
                                                   String providerType,
                                                   String callState,
                                                   String ownerId,
                                                   String ownerType) {
                      StringBuilder data = new StringBuilder();
                      data.append('{');
                      data.append("\"eventType\": \"");
                      data.append(EVENT_CALL_STATE);
                      data.append("\",");
                      data.append("\"callId\": \"");
                      data.append(callId);
                      data.append("\",\"providerType\": \"");
                      data.append(providerType);
                      data.append("\",\"callState\": \"");
                      data.append(callState);
                      data.append("\",\"owner\": {");
                      data.append("\"id\": \"");
                      data.append(ownerId);
                      data.append("\",\"type\": \"");
                      data.append(ownerType);
                      data.append("\"}");
                      data.append('}');
                      bayeux.getChannel(channelId).publish(serverSession, data.toString());
                      if (LOG.isDebugEnabled()) {
                        LOG.debug(">>> Sent call state update to " + channelId + " by " + currentUserId(null));
                      }
                    }
                  };
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("<<< Created user channel context for " + userId + ", client:" + sessionId + ", channel:"
                        + channelId);
                  }
                  return new UserChannelContext(listener);
                }).addClient(sessionId);
              } else {
                LOG.warn("Subscribing to other user not possible, was user " + currentUserId + ", channel:" + channelId);
                remote.deliver(serverSession,
                               channelId,
                               ErrorInfo.clientError("Subscribing to other user not possible").asJSON());
                if (!channel.unsubscribe(remote)) {
                  LOG.warn("Unable to unsubscribe user " + currentUserId + " from channel " + channelId);
                }
              }
            } else {
              LOG.warn("Subscribing by unauthorized user not possible, was channel: " + channelId);
              remote.deliver(serverSession, channelId, ErrorInfo.accessError("Unauthorized user").asJSON());
              if (!channel.unsubscribe(remote)) {
                LOG.warn("Unable to unsubscribe unauthorized user from channel " + channelId);
              }
            }
          } catch (IndexOutOfBoundsException e) {
            // Ignore channel w/o a subpath (userId here) at the end
            if (LOG.isDebugEnabled()) {
              LOG.debug(">> Ignore user channel w/o user ID at the end: " + channelId, e);
            }
          }
        } else if (channelId.startsWith(CALL_SUBSCRIPTION_CHANNEL_NAME)) {
          if (exoContainerName != null) {
            callChannelContext.computeIfAbsent(channelId, k -> {
              if (LOG.isDebugEnabled()) {
                LOG.debug("<<< Created call channel context by " + currentUserId + ", client:" + sessionId + ", channel:"
                    + channelId);
              }
              return new CallChannelContext(exoContainerName);
            }).addUser(sessionId, currentUserId, exoClientId);
            if (LOG.isDebugEnabled()) {
              LOG.debug("<< Added call session for " + currentUserId + ", session:" + sessionId + " (" + exoContainerName + "@"
                  + exoClientId + "), channel:" + channelId);
            }
          } else {
            LOG.warn("eXo container not defined for " + currentUserId + ", client:" + sessionId + ", channel:" + channelId);
          }
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
        // FYI message will be null for server-side unsubscription
        String sessionId = session.getId();
        String channelId = channel.getId();
        String currentUserId = currentUserId(message);
        String exoClientId = null;
        String exoContainerName = null;
        if (message != null) {
          exoClientId = asString(message.get("exoClientId"));
          exoContainerName = asString(message.get("exoContainerName"));
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug(">> Unsubscribed: " + currentUserId + ", session:" + sessionId + " (" + exoContainerName + "@" + exoClientId
              + "), channel:" + channelId);
        }
        if (channelId.startsWith(USER_SUBSCRIPTION_CHANNEL_NAME)) {
          // cleanup session stuff, note that disconnected session already unsubscribed and has not channels
          String userId = channelUserId(channelId);
          UserChannelContext context = userChannelContext.get(userId);
          if (context != null) {
            context.removeClient(sessionId);
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("<<< User call channel context not found for session:" + sessionId + ", channel:" + channelId);
            }
          }
        } else if (channelId.startsWith(CALL_SUBSCRIPTION_CHANNEL_NAME)
            && channelId.length() > CALL_SUBSCRIPTION_CHANNEL_NAME.length()) {
          String callId = channelId.substring(CALL_SUBSCRIPTION_CHANNEL_NAME.length() + 1);
          CallChannelContext context = callChannelContext.get(channelId);
          if (context != null) {
            callHandlers.submit(new ContainerCommand(context.getContainerName()) {
              /**
               * {@inheritDoc}
               */
              @Override
              void execute(ExoContainer exoContainer) {
                try {
                  CallInfo call = webConferencing.getCall(callId);
                  if (call != null) {
                    CallClient client = context.removeUser(sessionId);
                    if (client != null) {
                      if (LOG.isDebugEnabled()) {
                        LOG.debug(">>> Removed call session for " + client.getUserId() + ", session:" + sessionId + " ("
                            + context.getContainerName() + "@" + client.getClientId() + "), channel:" + channelId);
                      }
                      webConferencing.leaveCall(callId, client.getUserId(), client.getClientId());
                    } else {
                      LOG.warn("Client not found for session " + sessionId + " of " + channelId);
                    }
                    // TODO remove mapping of the context if it has not clients, but how safe to make this
                    // when call exists?
                    // Client could be added at next moment, at the same time mapping will be
                    // cleaned when channel will be removed (but this not proven to happen according logs).
                    // If do that, then need do consistent for the mapping: above instead of
                    // callChannelContext.get() use callChannelContext.computeIfPresent() with all this code
                    // inside it (and return null from bi-function if context has not clients).
                  } else {
                    // Remove existing context(!) for not existing call - this can be considered as safe.
                    callChannelContext.remove(channelId, context);
                  }
                } catch (Exception e) {
                  LOG.error("Error reading call " + callId, e);
                }
              }

              /**
               * {@inheritDoc}
               */
              @Override
              void onContainerError(String error) {
                LOG.error("Container error: " + error + " (" + containerName + ") for channel removal " + channelId);
              }
            });
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("<<< Call context not found for " + callId);
            }
          }
        }
      }
    }

    /**
     * The listener interface for receiving client channel events.
     *
     * @see ClientChannelEvent
     */
    class ClientChannelListener implements ChannelListener {

      /**
       * {@inheritDoc}
       */
      @Override
      public void configureChannel(ConfigurableServerChannel channel) {
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void channelAdded(ServerChannel channel) {
        // Add sub/unsub listener to WebConferencing channel
        final String channelId = channel.getId();
        if (LOG.isDebugEnabled()) {
          LOG.debug("> Channel added: " + channelId);
        }
        if (channelId.startsWith(USER_SUBSCRIPTION_CHANNEL_NAME) || channelId.startsWith(CALL_SUBSCRIPTION_CHANNEL_NAME)) {
          channel.addListener(subscriptionListener);
          if (LOG.isDebugEnabled()) {
            LOG.debug(">> Added subscription listener for channel: " + channelId);
          }
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void channelRemoved(String channelId) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("< Channel removed: " + channelId);
        }
        if (channelId.startsWith(USER_SUBSCRIPTION_CHANNEL_NAME)) {
          // Channel already doesn't exist here, ensure all its client listeners
          // were unregistered
          String userId = channelUserId(channelId);
          UserChannelContext context = userChannelContext.remove(userId);
          if (context != null) {
            webConferencing.removeUserCallListener(context.getListener());
            if (LOG.isDebugEnabled()) {
              LOG.debug("<< Removed user call listener for channel: " + channelId);
            }
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("<< User call channel context not found for:" + channelId);
            }
          }
        } else if (channelId.startsWith(CALL_SUBSCRIPTION_CHANNEL_NAME) && channelId.length() > CALL_SUBSCRIPTION_CHANNEL_NAME
                                                                                                                              .length()) {
          String callId = channelId.substring(CALL_SUBSCRIPTION_CHANNEL_NAME.length() + 1);
          // This call channel communications ended, in a normal way or by
          // network failure - we assume all clients gone (all call parties that have been connected). 
          // And so, we stop the call and every party, including those who received incoming notification 
          // but not yet accepted/rejected it, will be notified that the call stopped/removed.
          CallChannelContext context = callChannelContext.remove(channelId);
          if (context != null) {
            callHandlers.submit(new ContainerCommand(context.getContainerName()) {
              /**
               * {@inheritDoc}
               */
              @Override
              void execute(ExoContainer exoContainer) {
                try {
                  CallInfo call = webConferencing.getCall(callId);
                  if (call != null) {
                    // may be need leave all them and let that logic to stop the
                    // call?
                    webConferencing.stopCall(callId, !call.getOwner().isGroup());
                  }
                } catch (Exception e) {
                  LOG.error("Error reading call " + callId, e);
                }
              }

              /**
               * {@inheritDoc}
               */
              @Override
              void onContainerError(String error) {
                LOG.error("Container error: " + error + " (" + containerName + ") for channel removal " + channelId);
              }
            });
          } else {
            LOG.warn("Call context not found for " + callId);
          }
        }
      }
    }

    /**
     * The listener interface for receiving usersCache events and reflect local
     * {@link WebConferencingService#addUserCallListener(UserCallListener)}
     * listeners.
     *
     * @see UsersCacheEvent
     */
    class UsersCacheListener implements CacheListener<String, UserCallProxy> {

      /**
       * The Class UserCallListenerStub.
       */
      class UserCallListenerStub extends UserCallListener {

        /** The cache id. */
        final String cacheId;

        /**
         * Instantiates a new user call listener stub.
         *
         * @param cacheId the cache id
         * @param userId the user id
         * @param clientId the client id
         */
        UserCallListenerStub(String cacheId, String userId, String clientId) {
          super(userId, clientId);
          this.cacheId = cacheId;
        }

        /**
         * Gets the cache id.
         *
         * @return the cacheId
         */
        String getCacheId() {
          return cacheId;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPartLeaved(String callId, String providerType, String ownerId, String ownerType, String partId) {
          UserCallProxy proxy = new UserCallProxy(userId,
                                                  clientId,
                                                  new CallParticipantEvent(EVENT_CALL_LEAVED,
                                                                           callId,
                                                                           providerType,
                                                                           ownerId,
                                                                           ownerType,
                                                                           partId));
          usersCache.put(proxy.getId(), proxy);
          if (LOG.isDebugEnabled()) {
            LOG.debug(">> Cached call participant leaved for " + callId + "[" + partId + "] to " + cacheId + " by "
                + currentUserId(null));
          }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPartJoined(String callId, String providerType, String ownerId, String ownerType, String partId) {
          UserCallProxy proxy = new UserCallProxy(userId,
                                                  clientId,
                                                  new CallParticipantEvent(EVENT_CALL_JOINED,
                                                                           callId,
                                                                           providerType,
                                                                           ownerId,
                                                                           ownerType,
                                                                           partId));
          usersCache.put(proxy.getId(), proxy);
          if (LOG.isDebugEnabled()) {
            LOG.debug(">> Cached call participant joined for " + callId + "[" + partId + "] to " + cacheId + " by "
                + currentUserId(null));
          }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onCallStateChanged(String callId, String providerType, String callState, String ownerId, String ownerType) {
          UserCallProxy proxy = new UserCallProxy(userId,
                                                  clientId,
                                                  new CallStateEvent(callId, providerType, ownerId, ownerType, callState));
          usersCache.put(proxy.getId(), proxy);
          if (LOG.isDebugEnabled()) {
            LOG.debug(">> Cached call state update for " + callId + " to " + cacheId + " by " + currentUserId(null));
          }
        }
      }

      /** The client stubs. */
      final Map<String, UserCallListenerStub> clientStubs = new ConcurrentHashMap<>();

      /**
       * Stop.
       */
      void stop() {
        for (Iterator<UserCallListenerStub> siter = clientStubs.values().iterator(); siter.hasNext();) {
          UserCallListenerStub listener = siter.next();
          webConferencing.removeUserCallListener(listener);
          siter.remove();
          // Remove in whole (distributed) cache as this node service stops and all its clients closed channels
          usersCache.remove(listener.getCacheId());
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void onClearCache(CacheListenerContext context) throws Exception {
        // We don't need unregister all user listeners as they actually may be connected
        if (LOG.isDebugEnabled()) {
          LOG.debug("< User calls cache cleanup initiated");
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void onExpire(CacheListenerContext context, String key, UserCallProxy obj) throws Exception {
        // We don't need unregister an user listener as it actually may be connected
        if (LOG.isDebugEnabled()) {
          LOG.debug("< User calls cache expiration was initiated for " + key);
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void onRemove(CacheListenerContext context, String key, UserCallProxy obj) throws Exception {
        // We don't need unregister an user listener as it actually may be connected
        if (LOG.isDebugEnabled()) {
          LOG.debug("< User calls cache removal was initiated for " + key);
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void onPut(CacheListenerContext context, String key, UserCallProxy obj) throws Exception {
        if (obj != null) {
          String userId = obj.getUserId();
          String clientId = obj.getClientId();
          EventProxy event = obj.getState();
          UserChannelContext userContext = userChannelContext.get(userId);
          if (event.isInitial()) {
            // This event happens on remote node where this node wants to register a stub listener
            if (userContext == null || !userContext.getListener().getClientId().equals(clientId)) {
              // It's an user connected to a channel somewhere (on another cluster node)
              // Register a stub listener in the WebConf to transfer call updates from this remote to the client one.
              UserCallListenerStub listener = new UserCallListenerStub(key, userId, clientId);
              webConferencing.addUserCallListener(listener);
              // it's enough to have a first one as addUserCallListener does the same
              clientStubs.putIfAbsent(key, listener);
              if (LOG.isDebugEnabled()) {
                LOG.debug("> Registered user listener stub for " + key);
              }
            } // ignore otherwise - it's local event
          } else if (event.isClosed()) {
            // This event happens on remote node where this node wants to unregister its stub listener
            // It's an user disconnected from its channel somewhere (on another cluster node)
            UserCallListenerStub listener = clientStubs.remove(key);
            if (listener != null) {
              // If it found on this node - unregister its stub listener in node's WebConf service.
              webConferencing.removeUserCallListener(listener);
              if (LOG.isDebugEnabled()) {
                LOG.debug("< Unregistered user listener stub for " + key);
              }
            }
          } else {
            // This event happens on local node to where its stub listeners (from remote nodes) send call events to be notified to
            // local users
            // It's a call event - check if it's from remote stub listener (another cluster node) to our local user client
            if (userContext != null && userContext.getListener().getClientId().equals(obj.getClientId())) {
              // Handle the remote event like it's an one occurred in local WebConf service
              if (LOG.isDebugEnabled()) {
                LOG.debug("> Received call event from user listener stub for " + key + " event:" + event.getType() + " by "
                    + currentUserId(null));
              }
              switch (event.getType()) {
              case EVENT_CALL_JOINED: {
                CallParticipantEvent partEvent = CallParticipantEvent.class.cast(event);
                userContext.getListener()
                           .onPartJoined(partEvent.getCallId(),
                                         partEvent.getProviderType(),
                                         partEvent.getOwnerId(),
                                         partEvent.getOwnerType(),
                                         partEvent.getPartId());
                break;
              }
              case EVENT_CALL_LEAVED: {
                CallParticipantEvent partEvent = CallParticipantEvent.class.cast(event);
                userContext.getListener()
                           .onPartLeaved(partEvent.getCallId(),
                                         partEvent.getProviderType(),
                                         partEvent.getOwnerId(),
                                         partEvent.getOwnerType(),
                                         partEvent.getPartId());
                break;
              }
              case EVENT_CALL_STATE: {
                CallStateEvent stateEvent = CallStateEvent.class.cast(event);
                userContext.getListener()
                           .onCallStateChanged(stateEvent.getCallId(),
                                               stateEvent.getProviderType(),
                                               stateEvent.getState(),
                                               stateEvent.getOwnerId(),
                                               stateEvent.getOwnerType());
                break;
              }
              default:
                LOG.warn("Unexpected call state in cache " + event.getType() + " for " + key + " by " + currentUserId(null));
                break;
              }
            } // ignore otherwise - it's an event for others
          }
        } else {
          LOG.warn("Unexpected null call state in cache for " + key + " by " + currentUserId(null));
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void onGet(CacheListenerContext context, String key, UserCallProxy obj) throws Exception {
        // Nothing
      }
    }

    /** The bayeux. */
    @Inject
    private BayeuxServer                          bayeux;

    /** The local session. */
    @Session
    private LocalSession                          localSession;

    /** The server session. */
    @Session
    private ServerSession                         serverSession;

    /** User channel context. */
    private final Map<String, UserChannelContext> userChannelContext   = new ConcurrentHashMap<>();

    /** Call channel context. */
    private final Map<String, CallChannelContext> callChannelContext   = new ConcurrentHashMap<>();

    /** The subscription listener. */
    private final ChannelSubscriptionListener     subscriptionListener = new ChannelSubscriptionListener();
    
    /** The user cache listener. */
    private final UsersCacheListener     usersCacheListener = new UsersCacheListener();

    /** The channel listener. */
    private final ClientChannelListener           channelListener      = new ClientChannelListener();

    /**
     * Post construct.
     */
    @PostConstruct
    public void postConstruct() {
      bayeux.addListener(channelListener);
      if (usersCache != null) {
        usersCache.addCacheListener(usersCacheListener);
      }
    }

    /**
     * Pre destroy.
     */
    @PreDestroy
    public void preDestroy() {
      // cleanup listeners
      if (usersCache != null) {
        // XXX we cannot remove users cache listener, but
        // we remove all client listener stubs - unregister in WebConf service
        usersCacheListener.stop();
      }
      bayeux.removeListener(channelListener);
      for (UserChannelContext context : userChannelContext.values()) {
        webConferencing.removeUserCallListener(context.getListener());
      }
      userChannelContext.clear();
    }

    /**
     * Subscribe calls. All data exchanged between peers of a call will go there (e.g. RPC media and
     * connectivity settings).
     *
     * @param message the message
     * @param callType the call type
     * @param callInfo the call info
     */
    @Subscription(CALL_SUBSCRIPTION_CHANNEL_NAME_PATTERN)
    public void subscribeCalls(Message message, @Param("callType") String callType, @Param("callInfo") String callInfo) {
      if (LOG.isDebugEnabled()) {
        String callId = callId(callType, callInfo);
        // Should log this to a dedicated diagnostic log!
        LOG.debug("> Call published in " + message.getChannel() + " callId: " + callId + " data: " + message.getJSON());
      }
    }

    /**
     * Subscribe user. All user updates about his state and call will go here.
     *
     * @param message the message
     * @param userId the user id
     */
    @Subscription(USER_SUBSCRIPTION_CHANNEL_PATTERN)
    public void subscribeUser(Message message, @Param("userId") String userId) {
      final String channelId = message.getChannel();
      if (LOG.isDebugEnabled()) {
        LOG.debug("> User published in " + channelId + " userId: " + userId + " data: " + message.getJSON());
      }
    }

    /**
     * Remote calls from clients to Web Conferencing services.
     *
     * @param caller the caller
     * @param data the data
     */
    @RemoteCall(CALLS_CHANNEL_NAME)
    public void rcCalls(final RemoteCall.Caller caller, final Object data) {
      final ServerSession session = caller.getServerSession();
      if (LOG.isDebugEnabled()) {
        LOG.debug("> RemoteCall session:" + session.getId() + " data:" + data);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> arguments = (Map<String, Object>) data;
      String containerName = asString(arguments.get("exoContainerName"));

      callHandlers.submit(new ContainerCommand(containerName) {
        /**
         * {@inheritDoc}
         */
        @Override
        void execute(ExoContainer exoContainer) {
          try {
            String currentUserId = asString(arguments.get("exoId"));
            if (isValidId(currentUserId)) {
              String exoClientId = asString(arguments.get("exoClientId"));
              if (isValidId(exoClientId)) {
                // Do all the job under actual (requester) user: set this user as current identity in eXo
                // We rely on EXoContinuationBayeux.EXoSecurityPolicy for user security here (exoId above)
                // Use services acquired from context container.
                IdentityRegistry identityRegistry = exoContainer.getComponentInstanceOfType(IdentityRegistry.class);
                WebConferencingService webConferencing = exoContainer.getComponentInstanceOfType(WebConferencingService.class);
                Identity userIdentity = identityRegistry.getIdentity(currentUserId);
                if (userIdentity == null) {
                  // We create user identity by authenticator, but not register it in the registry
                  try {
                    Authenticator authenticator = exoContainer.getComponentInstanceOfType(Authenticator.class);
                    if (LOG.isDebugEnabled()) {
                      LOG.debug(">> User identity not registered, trying to create it for: " + currentUserId);
                    }
                    userIdentity = authenticator.createIdentity(currentUserId);
                  } catch (Exception e) {
                    LOG.warn("Failed to create user identity: " + currentUserId, e);
                  }
                }
                if (userIdentity != null) {
                  ConversationState contextState = ConversationState.getCurrent();
                  try {
                    // User context (2)
                    ConversationState convState = new ConversationState(userIdentity);
                    convState.setAttribute(ConversationState.SUBJECT, userIdentity.getSubject());
                    ConversationState.setCurrent(convState);
                    // Process the request
                    String id = asString(arguments.get("id"));
                    if (isValidId(id)) {
                      String command = asString(arguments.get("command"));
                      if (isValidArg(command)) {
                        if (COMMAND_GET.equals(command)) {
                          try {
                            CallInfo call = webConferencing.getCall(id);
                            if (call != null) {
                              caller.result(asJSON(call));
                            } else {
                              caller.failure(ErrorInfo.notFoundError("Call not found").asJSON());
                            }
                          } catch (Throwable e) {
                            LOG.error("Error reading call '" + id + "' by '" + currentUserId + "'", e);
                            caller.failure(ErrorInfo.serverError("Error reading call").asJSON());
                          }
                        } else if (COMMAND_UPDATE.equals(command)) {
                          @SuppressWarnings("unchecked")
                          Map<String, Object> info = (Map<String, Object>) arguments.get("info");
                          if (info != null) {
                            String ownerId = asString(info.get("owner"));
                            String ownerType = asString(info.get("ownerType"));
                            String providerType = asString(info.get("provider"));
                            String title = asString(info.get("title"));
                            String pstr = asString(info.get("participants"));
                            String spacesstr = asString(info.get("spaces"));
                            String startDate = asString(info.get("startDate"));
                            Date startD = null;
                            try {
                              startD = parseISODate(startDate);
                            } catch (Exception e) {
                              LOG.warn("Error parsing call start date: " + startDate, e);
                              caller.failure(ErrorInfo.clientError("Wrong parameter format for call update: startDate").asJSON());
                            }
                            String endDate = asString(info.get("endDate"));
                            Date endD = null;
                            try {
                              endD = parseISODate(endDate);
                            } catch (Exception e) {
                              LOG.warn("Error parsing call end date: " + endDate, e);
                              caller.failure(ErrorInfo.clientError("Wrong parameter format for call update: endDate").asJSON());
                            }
                            if (pstr != null) { // we don't check max length here
                              List<String> partIds = Arrays.asList(pstr.split(";"));
                              List<String> spaceNames = spacesstr != null ? Arrays.asList(spacesstr.split(";")) : null;
                              try {
                                CallInfo call = webConferencing.updateCall(id,
                                                                           ownerId,
                                                                           ownerType,
                                                                           title,
                                                                           providerType,
                                                                           partIds,
                                                                           spaceNames,
                                                                           startD,
                                                                           endD);
                                caller.result(asJSON(call));
                              } catch (CallInfoException e) {
                                // aka BAD_REQUEST - user did bad input, need to retry or reuse existing call
                                caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                              } catch (Throwable e) {
                                LOG.error("Error updating call information for '" + id + "' by '" + currentUserId + "'", e);
                                caller.failure(ErrorInfo.serverError("Error updating call information").asJSON());
                              }
                            } else {
                              caller.failure(ErrorInfo.clientError("Wrong info parameters: participants").asJSON());
                            }
                          } else {
                            Object participantsJson = arguments.get("participants");
                            if (participantsJson != null) {
                              try {
                                List<String> participants = asList(participantsJson, String.class);
                                CallInfo call = webConferencing.updateParticipants(id, participants);
                                caller.result(asJSON(call));
                              } catch (CallNotFoundException e) {
                                caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                              } catch (Throwable e) {
                                LOG.error("Error updating call participants '" + id + "' by '" + currentUserId + "'", e);
                                caller.failure(ErrorInfo.serverError("Error updating call participants").asJSON());
                              }
                            } else {
                              String state = asString(arguments.get("state"));
                              if (isValidArg(state)) {
                                try {
                                  boolean stateRecognized = true;
                                  CallInfo call;
                                  if (CallState.STARTED.equals(state)) {
                                    call = webConferencing.startCall(id, exoClientId);
                                  } else if (CallState.STOPPED.equals(state)) {
                                    call = webConferencing.stopCall(id, false);
                                  } else if (UserState.JOINED.equals(state)) {
                                    call = webConferencing.joinCall(id, currentUserId, exoClientId);
                                  } else if (UserState.LEAVED.equals(state)) {
                                    call = webConferencing.leaveCall(id, currentUserId, exoClientId);
                                  } else {
                                    call = null;
                                    stateRecognized = false;
                                  }
                                  if (stateRecognized) {
                                    if (call != null) {
                                      caller.result(asJSON(call));
                                    } else {
                                      caller.failure(ErrorInfo.notFoundError("Call not found").asJSON());
                                    }
                                  } else {
                                    caller.failure(ErrorInfo.clientError("Wrong parameters: state not recognized")
                                                            .asJSON());
                                  }
                                } catch (CallNotFoundException e) { // aka BAD_REQUEST
                                  caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                                } catch (Throwable e) {
                                  LOG.error("Error updating call state '" + id + "' by '" + currentUserId + "'", e);
                                  caller.failure(ErrorInfo.serverError("Error updating call state").asJSON());
                                }
                              } else {
                                caller.failure(ErrorInfo.clientError("Wrong parameters: state").asJSON());
                              }
                            }                            
                          }
                        } else if (COMMAND_CREATE.equals(command)) {
                          String ownerId = asString(arguments.get("owner"));
                          String ownerType = asString(arguments.get("ownerType"));
                          String providerType = asString(arguments.get("provider"));
                          String title = asString(arguments.get("title"));
                          String pstr = asString(arguments.get("participants"));
                          String spacesstr = asString(arguments.get("spaces"));
                          boolean start = asBoolean(arguments.get("start"));
                          String startDate = asString(arguments.get("startDate"));
                          Date startD = null;
                          try {
                            startD = parseISODate(startDate);
                          } catch (Exception e) {
                            caller.failure(ErrorInfo.clientError("Wrong parameter format for call creation: startDate").asJSON());
                          }
                          String endDate = asString(arguments.get("endDate"));
                          Date endD = null;
                          try {
                            endD = parseISODate(endDate);
                          } catch (Exception e) {
                            caller.failure(ErrorInfo.clientError("Wrong parameter format for call creation: endDate").asJSON());
                          }
                          if (pstr != null) { // we don't check max length here
                            List<String> partIds = Arrays.asList(pstr.split(";"));
                            List<String> spaceNames = spacesstr != null ? Arrays.asList(spacesstr.split(";")) : null;
                            try {
                              CallInfo call = webConferencing.createCall(id,
                                                                         ownerId,
                                                                         ownerType,
                                                                         title,
                                                                         providerType,
                                                                         partIds,
                                                                         spaceNames,
                                                                         start,
                                                                         startD,
                                                                         endD);
                              caller.result(asJSON(call));
                            } catch (CallInfoException e) {
                              // aka BAD_REQUEST - user did bad input, need to retry or reuse existing call
                              caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                            } catch (Throwable e) {
                              LOG.error("Error creating call for '" + id + "' by '" + currentUserId + "'", e);
                              caller.failure(ErrorInfo.serverError("Error creating call").asJSON());
                            }
                          } else {
                            caller.failure(ErrorInfo.clientError("Wrong parameters for call creation: participants").asJSON());
                          }
                        } else if (COMMAND_DELETE.equals(command)) {
                          try {
                            CallInfo call = webConferencing.stopCall(id, true);
                            if (call != null) {
                              caller.result(asJSON(call));
                            } else {
                              caller.failure(ErrorInfo.notFoundError("Call not found").asJSON());
                            }
                          } catch (CallNotFoundException e) {
                            caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                          } catch (Throwable e) {
                            LOG.error("Error deleting call '" + id + "' by '" + currentUserId + "'", e);
                            caller.failure(ErrorInfo.serverError("Error deleting call record").asJSON());
                          }
                        } else if (COMMAND_ADD_GUEST.equals(command)) {
                          String guestId = asString(arguments.get("guestId"));
                          if (guestId != null) {
                            try {
                              CallInfo call = webConferencing.addGuest(id, guestId);
                              caller.result(asJSON(call));
                            } catch (CallNotFoundException | IdentityStateException e) {
                              caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                            } catch (Throwable e) {
                              LOG.error("Error adding guest to call '" + id + "' by '" + currentUserId + "'", e);
                              caller.failure(ErrorInfo.serverError("Error adding guest to call").asJSON());
                            }
                          }
                        } else if (COMMAND_UPDATE_INVITES.equals(command)) {
                          try {
                            List<InvitedIdentity> invites = invitedIdentitiesFromJson(arguments.get("invites"));
                            CallInfo call = webConferencing.updateInvites(id, invites);
                            caller.result(asJSON(call));
                          } catch (CallNotFoundException e) {
                            caller.failure(ErrorInfo.clientError(e.getMessage()).asJSON());
                          } catch (Throwable e) {
                            LOG.error("Error adding guest to call '" + id + "' by '" + currentUserId + "'", e);
                            caller.failure(ErrorInfo.serverError("Error adding guest to call").asJSON());
                          }
                        } else if (COMMAND_CHECK_INVITE.equals(command)) {
                          String inviteId = asString(arguments.get("inviteId"));
                          try {
                            boolean allowed = webConferencing.checkInvite(id, inviteId, currentUserId);
                            caller.result("{\"allowed\" : " + allowed + "}");
                          } catch (Throwable e) {
                            LOG.error("Error adding guest to call '" + id + "' by '" + currentUserId + "'", e);
                            caller.failure(ErrorInfo.serverError("Error adding guest to call").asJSON());
                          }
                        } else if (COMMAND_GET_ORG_IDENTITIES.equals(command)) {
                          String name = asString(arguments.get("name"));
                          try {
                            List<IdentityData> identities = webConferencing.findGroupsAndUsers(name);
                            caller.result(asJSON(identities.toArray()));
                          } catch (Throwable e) {
                            LOG.error("Error finding identities by '" + id + "' provider by '" + currentUserId + "'", e);
                            caller.failure(ErrorInfo.serverError("Error finding identities").asJSON());
                          }
                        } else if (COMMAND_GET_CALLS_STATE.equals(command)) {
                          if (id.equals(currentUserId)) { // id it's user name for this command
                            try {
                              CallState[] calls = webConferencing.getUserCalls(id);
                              caller.result(asJSON(calls));
                            } catch (Throwable e) {
                              LOG.error("Error reading users calls for '" + id + "'", e);
                              caller.failure(ErrorInfo.serverError("Error reading user calls").asJSON());
                            }
                          } else {
                            // Don't let read other user calls
                            caller.failure(ErrorInfo.clientError("Wrong request parameters: id (does not match)").asJSON());
                          }
                        } else if (COMMAND_HAS_USER_PERMISSIONS.equals(command)) {
                          // id it's a target user name to check permissions
                          String providerType = asString(arguments.get("provider"));
                          try {
                            boolean allowed = webConferencing.hasUserPermissions(id, providerType);
                            caller.result("{\"allowed\" : " + allowed + "}");
                          } catch (Throwable e) {
                            LOG.error("Error checking user permissions for user '" + id + "' and provider '" + providerType + "'", e);
                            caller.failure(ErrorInfo.serverError("Error checking user permissions").asJSON());
                          }
                        } else {
                          LOG.warn("Unknown call command " + command + " for '" + id + "' from '" + currentUserId + "'");
                          caller.failure(ErrorInfo.clientError("Unknown command").asJSON());
                        }
                      } else {
                        caller.failure(ErrorInfo.clientError("Wrong request parameters: command").asJSON());
                      }
                    } else {
                      caller.failure(ErrorInfo.clientError("Wrong request parameters: id").asJSON());
                    }
                  } finally {
                    // Restore context (2)
                    ConversationState.setCurrent(contextState);
                  }
                } else {
                  LOG.warn("User identity not found " + currentUserId + " for remote call of " + CALLS_CHANNEL_NAME);
                  caller.failure(ErrorInfo.clientError("User identity not found").asJSON());
                }
              } else {
                caller.failure(ErrorInfo.clientError("Bad client ID").asJSON());
              }
            } else {
              caller.failure(ErrorInfo.clientError("Unauthorized user").asJSON());
            }
          } catch (Throwable e) {
            LOG.error("Error processing call request from client " + session.getId() + " with data: " + data, e);
            caller.failure(ErrorInfo.serverError("Error processing call request: " + e.getMessage()).asJSON());
          }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        void onContainerError(String error) {
          LOG.warn("Container error: " + error + " (" + containerName + ") for remote call of " + CALLS_CHANNEL_NAME);
          caller.failure(ErrorInfo.clientError(error).asJSON());
        }
      });
    }

    /**
     * Remote calls from clients to Web Conferencing services.
     *
     * @param caller the caller
     * @param args the data
     */
    @RemoteCall(LOGS_CHANNEL_NAME)
    @SuppressWarnings("unchecked")
    public void rcLogs(final RemoteCall.Caller caller, final Object args) {
      ServerSession session = caller.getServerSession();
      Map<String, Object> params = (Map<String, Object>) args;
      try {
        // We will receive following entries:
        // exoId - string, eXo's username who logs the data
        // exoClientId - string, an ID generated by core web conferencing to distinguish running
        // clients
        // data - string or object with actual diagnostic info
        // level - string, it's a log level: one of "trace", "debug", "info", "warn", "error"
        // prefix - string, a log record prefix (e.g. provider type or app context)
        // timestamp - string, date with time and seconds fraction in ISO format and UTC timezone
        String currentUserId = asString(params.get("exoId"));
        if (isValidId(currentUserId)) {
          // TODO validate all log params on max length in like validate() method
          String clientId = asString(params.get("exoClientId"));
          if (isValidArg(clientId)) {
            String level = asString(params.get("level"));
            if (isValidArg(level)) {
              String timestamp = asString(params.get("timestamp"));
              if (isValidArg(timestamp)) {
                String provider = asString(params.get("provider")); // can be null or non empty
                if (isValidText(provider)) {
                  String prefix = asString(params.get("prefix")); // can be null or non empty
                  if (isValidText(prefix)) {
                    Map<String, Object> msgData;
                    Object msgObj = params.get("data");
                    if (msgObj != null) {
                      if (Map.class.isAssignableFrom(msgObj.getClass())) {

                        msgData = (Map<String, Object>) msgObj;
                      } else if (String.class.isAssignableFrom(msgObj.getClass())) {
                        msgData = new HashMap<>();
                        msgData.put("message", String.class.cast(msgObj));
                      } else {
                        LOG.warn("");
                        msgData = Collections.emptyMap();
                      }

                      String message = validate(asString(msgData.get("message")));

                      CallLog callLog = callLogs.getLog();
                      StringBuilder msgLine = new StringBuilder();
                      msgLine.append('[');
                      if (provider != null) {
                        msgLine.append(provider);
                      }
                      if (prefix != null) {
                        if (msgLine.length() > 1) {
                          msgLine.append('.');
                        }
                        msgLine.append(prefix);
                      }
                      if (msgLine.length() > 1) {
                        msgLine.append("] ");
                      } else {
                        msgLine.deleteCharAt(0); // remove [
                      }
                      msgLine.append(currentUserId);
                      msgLine.append('-');
                      msgLine.append(clientId);
                      msgLine.append(' ');
                      msgLine.append(message);
                      msgLine.append(" -- ");
                      msgLine.append(timestamp);

                      //
                      LocalDateTime msgTimestamp;
                      try {
                        msgTimestamp = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME);
                      } catch (DateTimeParseException e) {
                        LOG.warn("Error parsing log timestamp '" + timestamp + "'", e);
                        msgTimestamp = null;
                      }

                      if (ERROR_LEVEL.equals(level)) {
                        callLog.error(msgLine.toString(), msgTimestamp);
                      } else if (WARN_LEVEL.equals(level)) {
                        callLog.warn(msgLine.toString(), msgTimestamp);
                      } else if (INFO_LEVEL.equals(level)) {
                        callLog.info(msgLine.toString(), msgTimestamp);
                      } else if (DEBUG_LEVEL.equals(level)) {
                        callLog.debug(msgLine.toString(), msgTimestamp);
                      } else if (TRACE_LEVEL.equals(level)) {
                        callLog.trace(msgLine.toString(), msgTimestamp);
                      } else {
                        callLog.warn("Received not expected level: " + level);
                        caller.failure(ErrorInfo.clientError("Not expected request parameters: level").asJSON());
                      }

                      // Finally send OK response (empty JSON object here)
                      caller.result(LOG_OK);
                    } else {
                      caller.failure(ErrorInfo.clientError("Not found request parameters: data").asJSON());
                    }
                  } else {
                    caller.failure(ErrorInfo.clientError("Wrong request parameters: prefix").asJSON());
                  }
                } else {
                  caller.failure(ErrorInfo.clientError("Wrong request parameters: provider").asJSON());
                }
              } else {
                caller.failure(ErrorInfo.clientError("Wrong request parameters: timestamp").asJSON());
              }
            } else {
              caller.failure(ErrorInfo.clientError("Wrong request parameters: level").asJSON());
            }
          } else {
            caller.failure(ErrorInfo.clientError("Wrong request parameters: clientId").asJSON());
          }
        } else {
          caller.failure(ErrorInfo.clientError("Unauthorized user").asJSON());
        }
      } catch (Throwable e) {
        LOG.error("Error processing call request from client " + session.getId() + " with data: " + args, e);
        caller.failure(ErrorInfo.serverError("Error processing call request: " + e.getMessage()).asJSON());
      }
    }
  }

  /**
   * Instantiates a new CometD interaction service for WebConferencing.
   *
   * @param identityRegistry the identity registry
   * @param organization the organization
   * @param webConferencing the web conferencing
   * @param exoBayeux the exo bayeux
   * @param cacheService the cache service
   * @param callLogs the call logs
   */
  public CometdWebConferencingService(IdentityRegistry identityRegistry,
                                      OrganizationService organization,
                                      WebConferencingService webConferencing,
                                      EXoContinuationBayeux exoBayeux,
                                      CacheService cacheService,
                                      CallLogService callLogs) {
    this.identityRegistry = identityRegistry;
    this.organization = organization;
    this.webConferencing = webConferencing;
    this.exoBayeux = exoBayeux;
    this.callLogs = callLogs;
    this.usersCache = ExoContainer.hasProfile("cluster") ? cacheService.getCacheInstance(USER_CACHE_NAME) : null;
    this.service = new CallService();

    // Thread executors
    this.callHandlers = createThreadExecutor(CALL_THREAD_PREFIX, CALL_MAX_FACTOR, CALL_QUEUE_FACTOR);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    // instantiate processor after the eXo container start, to let start-dependent logic worked before us
    final AtomicReference<ServerAnnotationProcessor> processor = new AtomicReference<>();
    // need initiate process after Bayeux server starts
    exoBayeux.addLifeCycleListener(new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarted(LifeCycle event) {
        ServerAnnotationProcessor p = new ServerAnnotationProcessor(exoBayeux);
        processor.set(p);
        p.process(service);
      }

      @Override
      public void lifeCycleStopped(LifeCycle event) {
        ServerAnnotationProcessor p = processor.get();
        if (p != null) {
          p.deprocess(service);
        }
      }

      @Override
      public void lifeCycleStarting(LifeCycle event) {
        // Nothing
      }

      @Override
      public void lifeCycleFailure(LifeCycle event, Throwable cause) {
        // Nothing
      }

      @Override
      public void lifeCycleStopping(LifeCycle event) {
        // Nothing
      }
    });

    if (PropertyManager.isDevelopping()) {
      // This listener not required for work, just for info during development
      exoBayeux.addListener(new BayeuxServer.SessionListener() {
        @Override
        public void sessionRemoved(ServerSession session, boolean timedout) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("< sessionRemoved: " + session.getId() + " timedout:" + timedout + " channels: "
                + channelsAsString(session.getSubscriptions()));
          }
        }

        @Override
        public void sessionAdded(ServerSession session, ServerMessage message) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("> sessionAdded: " + session.getId() + " channels: " + channelsAsString(session.getSubscriptions()));
          }
        }
      });
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    //
  }

  /**
   * Gets the cometd server path.
   *
   * @return the cometd server path
   */
  public String getCometdServerPath() {
    return new StringBuilder("/").append(exoBayeux.getCometdContextName()).append("/cometd").toString();
  }

  /**
   * Gets the user token.
   *
   * @param userId the user id
   * @return the user token
   */
  public String getUserToken(String userId) {
    return exoBayeux.getUserToken(userId);
  }

  /**
   * Current user id.
   *
   * @param message the message
   * @return the string, if user cannot be defined it will be {@link IdentityConstants#ANONIM}
   */
  protected String currentUserId(ServerMessage message) {
    if (message != null) {
      return asString(message.get("exoId"));
    } else {
      // FIXME Should we rely at ConversationState current's here (it will not be set by something external in
      // Cometd thread) or only on message's exoId?
      ConversationState convo = ConversationState.getCurrent();
      if (convo != null) {
        return convo.getIdentity().getUserId();
      } else {
        return IdentityConstants.ANONIM;
      }
    }
  }

  /**
   * Channel user id. It's assumed an user channel {@link USER_SUBSCRIPTION_CHANNEL_NAME}.
   *
   * @param channelId the channel id
   * @return the string
   * @throws IndexOutOfBoundsException if channel id doesn't look like an user channel
   */
  protected String channelUserId(String channelId) throws IndexOutOfBoundsException {
    return channelId.substring(USER_SUBSCRIPTION_CHANNEL_NAME.length() + 1);
  }

  /**
   * Channels as string.
   *
   * @param channels the channels
   * @return the string
   */
  protected String channelsAsString(Set<ServerChannel> channels) {
    return channels.stream().map(c -> c.getId()).collect(Collectors.joining(", "));
  }

  /**
   * Call id.
   *
   * @param type the type
   * @param info the id
   * @return the string
   */
  protected String callId(String type, String info) {
    return new StringBuffer(type).append('/').append(info).toString();
  }

  /**
   * Return object if it's String instance or null if it is not.
   *
   * @param obj the obj
   * @return the string or null
   */
  protected String asString(Object obj) {
    if (obj != null && String.class.isAssignableFrom(obj.getClass())) {
      return String.class.cast(obj);
    }
    return null;
  }
  
  /**
   * Return true if it's Boolean instance of TRUE or false if it is not.
   *
   * @param obj the obj
   * @return the boolean
   */
  protected boolean asBoolean(Object obj) {
    if (obj != null) {
      return Boolean.valueOf(obj.toString());
    }
    return false;
  }

  /**
   * As list.
   *
   * @param <T> the generic type
   * @param obj the obj
   * @param type the type
   * @return the list
   */
  protected <T> List<T> asList(Object obj, Class<T> type) {
    List<T> list = new ArrayList<>();
    Object[] arr = (Object[]) obj;
    for (Object elem : arr) {
      if (type.isAssignableFrom(elem.getClass())) {
        T item = type.cast(elem);
        list.add(item);
      }
    }
    return list;
  }

  /**
   * Invited identities list.
   *
   * @param obj the obj
   * @return the list
   */
  protected List<InvitedIdentity> invitedIdentitiesFromJson(Object obj) {
    List<InvitedIdentity> list = new ArrayList<>();
    Object[] arr = (Object[]) obj;
    for (Object elem : arr) {
      if (Map.class.isAssignableFrom(elem.getClass())) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) elem;
        String id = asString(map.get("id"));
        String type = asString(map.get("type"));
        list.add(new InvitedIdentity(id, type));
      }
    }
    return list;
  }

  /**
   * Create a new thread executor service.
   *
   * @param threadNamePrefix the thread name prefix
   * @param maxFactor - max processes per CPU core
   * @param queueFactor - queue size per CPU core
   * @return the executor service
   */
  protected ExecutorService createThreadExecutor(String threadNamePrefix, int maxFactor, int queueFactor) {
    // Executor will queue all commands and run them in maximum set of threads. Minimum set of threads will be
    // maintained online even idle, other inactive will be stopped in two minutes.
    final int cpus = Runtime.getRuntime().availableProcessors();
    int poolThreads = cpus / 4;
    poolThreads = poolThreads < MIN_THREADS ? MIN_THREADS : poolThreads;
    int maxThreads = Math.round(cpus * 1f * maxFactor);
    maxThreads = maxThreads > 0 ? maxThreads : 1;
    maxThreads = maxThreads < MIN_MAX_THREADS ? MIN_MAX_THREADS : maxThreads;
    int queueSize = cpus * queueFactor;
    queueSize = queueSize < queueFactor ? queueFactor : queueSize;
    if (LOG.isDebugEnabled()) {
      LOG.debug("> Creating thread executor " + threadNamePrefix + "* for " + poolThreads + ".." + maxThreads
          + " threads, queue size " + queueSize);
    }
    return new ThreadPoolExecutor(poolThreads,
                                  maxThreads,
                                  THREAD_IDLE_TIME,
                                  TimeUnit.SECONDS,
                                  new LinkedBlockingQueue<Runnable>(queueSize),
                                  new CommandThreadFactory(threadNamePrefix),
                                  new ThreadPoolExecutor.CallerRunsPolicy());
  }

}
