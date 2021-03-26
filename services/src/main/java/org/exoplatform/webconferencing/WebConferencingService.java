/*
 * Copyright (C) 2003-2017 eXo Platform SAS.
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
package org.exoplatform.webconferencing;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.persistence.PersistenceException;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.lang.RandomStringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.picocontainer.Startable;

import org.exoplatform.commons.api.persistence.ExoTransactional;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.PropertiesParam;
import org.exoplatform.ecm.utils.permission.PermissionUtil;
import org.exoplatform.services.cms.link.LinkManager;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.access.PermissionType;
import org.exoplatform.services.jcr.core.ExtendedNode;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.Membership;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserStatus;
import org.exoplatform.services.security.Authenticator;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.IdentityRegistry;
import org.exoplatform.services.wcm.core.NodetypeConstant;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.service.LinkProvider;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;
import org.exoplatform.wcm.ext.component.document.service.ShareDocumentService;
import org.exoplatform.webconferencing.UserInfo.IMInfo;
import org.exoplatform.webconferencing.dao.CallDAO;
import org.exoplatform.webconferencing.dao.InviteDAO;
import org.exoplatform.webconferencing.dao.OriginDAO;
import org.exoplatform.webconferencing.dao.ParticipantDAO;
import org.exoplatform.webconferencing.dao.StorageException;
import org.exoplatform.webconferencing.domain.CallEntity;
import org.exoplatform.webconferencing.domain.InviteEntity;
import org.exoplatform.webconferencing.domain.OriginEntity;
import org.exoplatform.webconferencing.domain.ParticipantEntity;
import org.exoplatform.webconferencing.domain.ParticipantId;

/**
 * Created by The eXo Platform SAS.
 *
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: SkypeService.java 00000 Feb 22, 2017 pnedonosko $
 */
public class WebConferencingService implements Startable {

  /** The Constant ID_MAX_LENGTH. */
  public static final int       ID_MAX_LENGTH                = 255;

  /** The Constant TEXT_MAX_LENGTH. */
  public static final int       TEXT_MAX_LENGTH              = 255;

  /** The Constant ARG_MAX_LENGTH. */
  public static final int       ARG_MAX_LENGTH               = 32;

  /** The Constant DATA_MAX_LENGTH. */
  public static final int       DATA_MAX_LENGTH              = 2000;

  /** The Constant MAX_RESULT_SIZE. */
  public static final int       MAX_RESULT_SIZE              = 30;

  /** The Constant SESSION_TOKEN_COOKIE. */
  public static final String    SESSION_TOKEN_COOKIE         = "webconf_session_token".intern();

  /** The operation call added. */
  public static final String          OPERATION_CALL_ADDED         = "call-added";

  /** The operation call started. */
  public static final String          OPERATION_CALL_STARTED       = "call-started";

  /** The operation call joined. */
  public static final String          OPERATION_CALL_JOINED        = "call-joined";

  /** The operation call leaved. */
  public static final String          OPERATION_CALL_LEAVED        = "call-leaved";

  /** The operation call stopped. */
  public static final String          OPERATION_CALL_STOPPED       = "call-stopped";

  /** The operation call deleted. */
  public static final String          OPERATION_CALL_DELETED       = "call-deleted";

  /** The operation call recorded. */
  public static final String          OPERATION_CALL_RECORDED      = "call-recorded";

  /** The status ok. */
  public static final String          STATUS_OK                    = "ok";

  public static final String    EVENT_CALL_CREATED           = "exo.webconferencing.callCreated";

  public static final String    EVENT_CALL_STARTED           = "exo.webconferencing.callStarted";

  public static final String    EVENT_CALL_JOINDED           = "exo.webconferencing.callJoined";

  public static final String    EVENT_CALL_LEFT              = "exo.webconferencing.callLeft";

  public static final String    EVENT_CALL_STOPPED           = "exo.webconferencing.callStopped";

  public static final String    EVENT_CALL_RECORDED          = "exo.webconferencing.callRecorded";

  /** The Constant CALL_OWNER_SCOPE_NAME. */
  protected static final String CALL_OWNER_SCOPE_NAME        = "webconferencing.callOwner".intern();

  /** The Constant CALL_ID_SCOPE_NAME. */
  protected static final String CALL_ID_SCOPE_NAME           = "webconferencing.callId".intern();

  /** The Constant USER_CALLS_SCOPE_NAME. */
  protected static final String USER_CALLS_SCOPE_NAME        = "webconferencing.user.calls".intern();

  /** The Constant PROVIDER_SCOPE_NAME. */
  protected static final String PROVIDER_SCOPE_NAME          = "webconferencing.provider".intern();

  /** The Constant JWT_CONFIGURATION_PROPERTIES. */
  protected static final String JWT_CONFIGURATION_PROPERTIES = "jwt-configuration";

  /** The Constant SECRET_KEY. */
  protected static final String SECRET_KEY                   = "secret-key";

  /**
   * Represent Space in calls.
   */
  public class SpaceInfo extends GroupInfo {

    /** The space group id. */
    protected final String groupId;

    /**
     * Instantiates a new space info.
     *
     * @param socialSpace the social space
     */
    public SpaceInfo(Space socialSpace) {
      super(socialSpace.getPrettyName(), socialSpace.getDisplayName());
      this.groupId = socialSpace.getGroupId();
    }

    /**
     * Gets the group id of the space.
     *
     * @return the groupId
     */
    public String getGroupId() {
      return groupId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
      return OWNER_TYPE_SPACE;
    }
  }

  /**
   * Represent Space event call.
   */
  public class SpaceEventInfo extends GroupInfo {

    /** The space group id. */
    protected final String groupId;
    
    /**
     * Instantiates a new space event info.
     *
     * @param socialSpace the social space
     */
    public SpaceEventInfo(Space socialSpace) {
      super(socialSpace.getPrettyName(), socialSpace.getDisplayName());
      this.groupId = socialSpace.getGroupId();
    }

    /**
     * Gets the group id of the space.
     *
     * @return the groupId
     */
    public String getGroupId() {
      return groupId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
      return OWNER_TYPE_SPACEEVENT;
    }
  }

  /**
   * Represent Chat room in calls.
   */
  public class RoomInfo extends GroupInfo {

    /**
     * Instantiates a new room info.
     *
     * @param id the id
     * @param title the title
     */
    public RoomInfo(String id, String title) {
      super(id, title);
      this.profileLink = IdentityInfo.EMPTY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
      return OWNER_TYPE_CHATROOM;
    }
  }

  /** The Constant ALL_USERS. */
  public static final String                         ALL_USERS              = "*";

  /** The Constant GROUP. */
  public static final String                         GROUP                  = "group";

  /** The Constant USER. */
  public static final String                         USER                   = "user";

  /** The Constant OWNER_TYPE_USER. */
  public static final String                         OWNER_TYPE_USER       = "user";
  
  /** The Constant OWNER_TYPE_SPACE. */
  public static final String                         OWNER_TYPE_SPACE       = "space";
  
  /** The Constant OWNER_TYPE_SPACEEVENT. */
  public static final String                         OWNER_TYPE_SPACEEVENT       = "space_event";

  /** The Constant OWNER_TYPE_CHATROOM. */
  public static final String                         OWNER_TYPE_CHATROOM    = "chat_room";
  
  /** The Constant CMS_GROUPS_PATH. */
  public static final String                         CMS_GROUPS_PATH        = "groupsPath";

  /** The Constant EXO_TITLE_PROP. */
  public static final String                         EXO_TITLE_PROP         = "exo:title";

  /** The Constant NT_FILE. */
  public static final String                         NT_FILE                = "nt:file";

  /** The Constant JCR_CONTENT. */
  public static final String                         JCR_CONTENT            = "jcr:content";

  /** The Constant JCR_DATA. */
  public static final String                         JCR_DATA               = "jcr:data";

  /** The Constant JCR_MIME_TYPE. */
  public static final String                         JCR_MIME_TYPE          = "jcr:mimeType";

  /** The Constant JCR_LAST_MODIFIED_PROP. */
  public static final String                         JCR_LAST_MODIFIED_PROP = "jcr:lastModified";

  /** The Constant EXO_RSS_ENABLE_PROP. */
  public static final String                         EXO_RSS_ENABLE_PROP    = "exo:rss-enable";

  /** The Constant NT_RESOURCE. */
  public static final String                         NT_RESOURCE            = "nt:resource";

  /** The Constant MIX_PRIVILEGEABLE. */
  public static final String                         MIX_PRIVILEGEABLE      = "exo:privilegeable";

  /** The Constant LOG. */
  protected static final Log                         LOG                    = ExoLogger.getLogger(WebConferencingService.class);

  /** The secret key. */
  protected final String                             secretKey;

  /** The organization. */
  protected final OrganizationService                organization;

  /** The social identity manager. */
  protected final IdentityManager                    socialIdentityManager;

  /** The listener service. */
  protected final ListenerService                    listenerService;

  /** The settings service. */
  protected final SettingService                     settingService;

  /** The calls storage. */
  protected final CallDAO                            callStorage;

  /** The participants storage. */
  protected final ParticipantDAO                     participantsStorage;
  
  /** The call origins storage. */
  protected final OriginDAO                          originsStorage;

  /** The invite storage. */
  protected final InviteDAO                          inviteStorage;

  /** The providers. */
  protected final Map<String, CallProvider>          providers              = new ConcurrentHashMap<>();

  /** The space service. */
  protected SpaceService                             spaceService;

  /** The share service. */
  protected ShareDocumentService                     shareService;

  /** The user listeners. */
  protected final Map<String, Set<UserCallListener>> userListeners          = new ConcurrentHashMap<>();

  /** The upload service. */
  protected final UploadService                      uploadService;

  /** The repository service. */
  protected final RepositoryService                  repositoryService;

  /** The session providers. */
  protected final SessionProviderService             sessionProviders;

  /** The node creator. */
  protected final NodeHierarchyCreator               nodeCreator;

  /** The identity registry. */
  protected final IdentityRegistry                   identityRegistry;

  /** The authenticator. */
  protected final Authenticator                      authenticator;

  /** The Link manager. */
  protected final LinkManager                        linkManager;
  
  /**
   * Checks is ID valid (not null, not empty and not longer of {@value #ID_MAX_LENGTH} chars).
   *
   * @param id the id
   * @return true, if is valid id
   */
  public static boolean isValidId(String id) {
    return id != null && id.length() > 0 && id.length() <= ID_MAX_LENGTH;
  }

  /**
   * Checks if is valid text (null, not empty and not longer of {@value #TEXT_MAX_LENGTH} chars).
   *
   * @param text the text
   * @return true, if is valid text
   */
  public static boolean isValidText(String text) {
    return text == null || (text.length() > 0 && text.length() <= TEXT_MAX_LENGTH);
  }

  /**
   * Checks if argument valid (null or not empty and not longer of {@value #ARG_MAX_LENGTH} chars).
   *
   * @param arg the arg
   * @return true, if is valid arg
   */
  public static boolean isValidArg(String arg) {
    return arg == null || (arg.length() > 0 && arg.length() <= ARG_MAX_LENGTH);
  }

  /**
   * Checks if argument not null (also not empty and not longer of {@value #ARG_MAX_LENGTH} chars).
   *
   * @param arg the arg
   * @return true, if is not null arg
   */
  public static boolean isNotNullArg(String arg) {
    return arg != null && arg.length() > 0 && arg.length() <= ARG_MAX_LENGTH;
  }

  /**
   * Checks if data valid (null or not empty and not longer of {@value #DATA_MAX_LENGTH} bytes in UTF8
   * encoding).
   *
   * @param data the data
   * @return true, if is valid data
   * @throws UnsupportedEncodingException if UTF8 encoding not found in runtime
   */
  public static boolean isValidData(String data) throws UnsupportedEncodingException {
    return data == null || (data.length() > 0 && data.getBytes("UTF8").length <= DATA_MAX_LENGTH);
  }

  /**
   * Instantiates a new web conferencing service.
   *
   * @param organization the organization
   * @param socialIdentityManager the social identity manager
   * @param listenerService the listener service
   * @param settingService the setting service
   * @param callStorage the call storage
   * @param participantsStorage the participants storage
   * @param originsStorage the origins storage
   * @param inviteStorage the invite storage
   * @param uploadService the upload service
   * @param repositoryService the repository service
   * @param sessionProviders the session providers
   * @param nodeCreator the node creator
   * @param identityRegistry the identity registry
   * @param authenticator the authenticator
   * @param shareService the share service
   * @param initParams the initParams
   * @param linkManager the link manager
   */
  public WebConferencingService(OrganizationService organization,
                                IdentityManager socialIdentityManager,
                                ListenerService listenerService,
                                SettingService settingService,
                                CallDAO callStorage,
                                ParticipantDAO participantsStorage,
                                OriginDAO originsStorage,
                                InviteDAO inviteStorage,
                                UploadService uploadService,
                                RepositoryService repositoryService,
                                SessionProviderService sessionProviders,
                                NodeHierarchyCreator nodeCreator,
                                IdentityRegistry identityRegistry,
                                Authenticator authenticator,
                                ShareDocumentService shareService,
                                InitParams initParams,
                                LinkManager linkManager) {
    this.organization = organization;
    this.socialIdentityManager = socialIdentityManager;
    this.listenerService = listenerService;
    this.settingService = settingService;
    this.callStorage = callStorage;
    this.participantsStorage = participantsStorage;
    this.originsStorage = originsStorage;
    this.inviteStorage = inviteStorage;
    this.uploadService = uploadService;
    this.repositoryService = repositoryService;
    this.sessionProviders = sessionProviders;
    this.nodeCreator = nodeCreator;
    this.identityRegistry = identityRegistry;
    this.authenticator = authenticator;
    PropertiesParam jwtSecretParam = initParams.getPropertiesParam(JWT_CONFIGURATION_PROPERTIES);
    this.secretKey = jwtSecretParam.getProperty(SECRET_KEY);
    this.shareService = shareService;
    this.linkManager = linkManager;
  }

  protected UserInfo userInfo(String id) throws IdentityStateException {
    User user;
    try {
      user = organization.getUserHandler().findUserByName(id, UserStatus.ANY);
    } catch (Exception e) {
      throw new IdentityStateException("Error finding user in organization service", e);
    }
    if (user != null) {
      // Check if user not disabled
      if (user.isEnabled()) {
        @SuppressWarnings("deprecation")
        org.exoplatform.social.core.identity.model.Identity userIdentity =
                                                                         socialIdentityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME,
                                                                                                                   id,
                                                                                                                   true);
        if (userIdentity != null) {
          Profile socialProfile = userIdentity.getProfile();

          UserInfo info = new UserInfo(user.getUserName(), user.getFirstName(), user.getLastName());
          // Add IMs accounts
          getUserIMs(socialProfile).forEach(im -> info.addImAccount(im));
          info.setAvatarLink(socialProfile.getAvatarUrl());
          info.setProfileLink(LinkProvider.getUserProfileUri(id));
          return info;
        } else {
          LOG.warn("Social identity not found for " + user.getUserName() + " (" + user.getFirstName() + " " + user.getLastName()
              + ")");
        }
      } else if (LOG.isDebugEnabled()) {
        LOG.debug("Ignore disabled user (treat as not found): '" + id + "'");
      }
    } else if (LOG.isDebugEnabled()) {
      LOG.debug("User not found: '" + id + "'");
    }
    return null;
  }
  
  /**
   * Gets the user info.
   *
   * @param id the id
   * @return the user info
   * @throws IdentityStateException if error happened during searching the user in Organization Service
   */
  public UserInfo getUserInfo(String id) throws IdentityStateException {
    return userInfo(id);
  }

  /**
   * Gets the space info, it will return actual space members at the moment of invocation.
   *
   * @param spacePrettyName the space pretty name
   * @return the space info
   * @throws IdentityStateException if error reading space member in Organization Service
   * @throws StorageException if error reading saved group call ID associated with a space
   */
  public SpaceInfo getSpaceInfo(String spacePrettyName) throws IdentityStateException, StorageException {
    return spaceInfo(spacePrettyName, findSpaceCallId(spacePrettyName));
  }
  
  /**
   * Gets the space event info, it will return actual event participants at the
   * moment of invocation. Even participants will be resolved from the owner and
   * given spaces' members plus given participants IDs.
   *
   * @param spacePrettyName the space pretty name
   * @param partIds the participants IDs (attendees) added to the event directly
   * @param spacesPrettyName the Social spaces' pretty names for add its members as
   *          participants (attendees) to the event
   * @return the space event info
   * @throws IdentityStateException the identity state exception
   * @throws StorageException the storage exception
   */
  public SpaceEventInfo getSpaceEventInfo(String spacePrettyName, String[] partIds, String[] spacesPrettyName)
                                                                                                     throws IdentityStateException,
                                                                                                     StorageException {
    return spaceEventInfo(spacePrettyName, findLastSpaceEventCallId(spacePrettyName), partIds, spacesPrettyName);
  }

  /**
   * Gets the JWT secret key.
   *
   * @return the secret key
   */
  public String getSecretKey() {
    return this.secretKey;
  }
  
  /**
   * Checks for user permissions to use a given provider.
   *
   * @param userId the user id
   * @param providerType the provider type
   * @return true, if successful
   */
  public boolean hasUserPermissions(String userId, String providerType) {
    CallProviderConfiguration config = getProviderConfiguration(providerType, Locale.getDefault());
    if (config != null) {
      if (config.getPermissions().size() == 0 || ALL_USERS.equals(config.getPermissions().get(0))) {
        // If not particular permissions set or permitted to all (*)
        return true;
      }
      org.exoplatform.services.security.Identity userIdentity = userIdentity(userId);
      if (userIdentity != null) {
        for (String permission : config.getPermissions()) {
          int splitIndex = permission.indexOf(":");
          if (splitIndex < 0) {
            // user permission
            if (userId.equals(permission)) {
              return true;
            }
          } else if (splitIndex < permission.length() - 1) {
            // group (space etc)
            String membership = permission.substring(0, splitIndex);
            String groupId = permission.substring(splitIndex + 1);
            if (userIdentity.isMemberOf(groupId, membership)) {
              return true;
            }
          }
        }
      } // otherwise, we think no permissions this userId should have
    } // no provider config means not permitted
    return false;
  }

  /**
   * Space info.
   *
   * @param spacePrettyName the space pretty name
   * @param callId the call id
   * @return the space info
   * @throws IdentityStateException if error reading space member in Organization Service
   */
  protected SpaceInfo spaceInfo(String spacePrettyName, String callId) throws IdentityStateException {
    Space socialSpace = spaceService.getSpaceByPrettyName(spacePrettyName);
    SpaceInfo space = new SpaceInfo(socialSpace);
    for (String sm : socialSpace.getMembers()) {
      UserInfo user = userInfo(sm);
      if (user != null) {
        space.addMember(user);
      } else {
        LOG.warn("Skipped not found space member " + sm + " of " + spacePrettyName);
        // for space we have members from inside, thus if it is not found, we ignore him assuming space
        // should be consistent
      }
    }
    space.setProfileLink(socialSpace.getUrl());
    space.setAvatarLink(socialSpace.getAvatarUrl());
    space.setCallId(callId);
    return space;
  }
  
  /**
   * Space event info.
   *
   * @param spacePrettyName the space pretty name
   * @param callId the call id
   * @param participants the participants user names
   * @param spaces an array of space pretty names
   * @return the space info
   * @throws IdentityStateException the identity state exception
   */
  protected SpaceEventInfo spaceEventInfo(String spacePrettyName, String callId, String[] participants, String[] spaces) throws IdentityStateException {
    Space socialSpaceHost = spaceService.getSpaceByPrettyName(spacePrettyName);
    SpaceEventInfo spaceEvent = new SpaceEventInfo(socialSpaceHost);
    
    // Merge the host space, given spaces and participants.
    Set<String> allSpaces = new LinkedHashSet<>();
    allSpaces.add(spacePrettyName);
    allSpaces.addAll(Arrays.asList(spaces));
    // 1) host space & 2) invited spaces
    for (String s : allSpaces) {
      Space socialSpace = spaceService.getSpaceByPrettyName(s);
      if (socialSpace != null) {
        for (String sm : socialSpace.getMembers()) {
          UserInfo user = userInfo(sm);
          if (user != null) {
            spaceEvent.addMember(user);
          } else {
            LOG.warn("Skipped not found space member as participant " + sm + " for space event in " + spacePrettyName);
          }
        }
      } else {
        LOG.warn("Skipped not found space " + s + " for event in " + spacePrettyName);
      }
    }
    // 3) explicit parties go last to remain in the members with isDirect flag
    for (String p : participants) {
      UserInfo user = userInfo(p);
      if (user != null) {
        spaceEvent.addMember(user);
      } else {
        LOG.warn("Skipped not found participant " + p + " for space event in " + spacePrettyName);
      }
    }
    
    spaceEvent.setProfileLink(socialSpaceHost.getUrl());
    spaceEvent.setAvatarLink(socialSpaceHost.getAvatarUrl());
    spaceEvent.setCallId(callId);
    return spaceEvent;
  }

  /**
   * Gets the room info.
   *
   * @param id the id
   * @param title the title
   * @param members the room members
   * @return the room info
   * @throws IdentityStateException if error reading room member in Organization Service
   * @throws StorageException if error reading saved group call ID associated with a room
   */
  public RoomInfo getRoomInfo(String id, String title, String[] members) throws IdentityStateException, StorageException {
    return roomInfo(id, title, members, findChatRoomCallId(id));
  };

  /**
   * Room info.
   *
   * @param id the id
   * @param title the title
   * @param members the members
   * @param callId the call id
   * @return the room info
   * @throws IdentityStateException if user cannot be read
   */
  protected RoomInfo roomInfo(String id, String title, String[] members, String callId) throws IdentityStateException {
    RoomInfo room = new RoomInfo(id, title);
    for (String userName : members) {
      UserInfo user = userInfo(userName);
      if (user != null) {
        room.addMember(user);
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("External room member " + userName + " for '" + title + "'");
        }
        // for chat room we have members from outside, if not eXo user - add it as external participant
        room.addMember(new ParticipantInfo(userName));
      }
    }
    room.setProfileLink(IdentityInfo.EMPTY);
    room.setAvatarLink(LinkProvider.SPACE_DEFAULT_AVATAR_URL);
    room.setCallId(callId);
    return room;
  };

  /**
   * Adds the call to list of active and fires STARTED event.
   *
   * @param id the id of the call
   * @param ownerId the owner id of the call
   * @param ownerType the owner type
   * @param title the title of the call
   * @param providerType the provider type
   * @param partIds the participants IDs
   * @return the call info object of type {@link CallInfo}
   * @throws CallArgumentException if call argument has wrong value (failed validation)
   * @throws StorageException if error reading, adding or updating call information in persistent storage
   * @throws IdentityStateException if error reading room member in Organization Service
   * @throws CallConflictException if such call already exists and has active state (started and/or running)
   * @throws CallSettingsException if call entry has wrong settings (it's for Chat room call, the title -
   *           too long or has bad value)
   */
  public CallInfo addCall(String id,
                          String ownerId,
                          String ownerType,
                          String title,
                          String providerType,
                          Collection<String> partIds) throws CallArgumentException,
                                                    StorageException,
                                                    IdentityStateException,
                                                    CallConflictException,
                                                    CallSettingsException {
    return this.createCall(id, ownerId, ownerType, title, providerType, partIds, null, true, null, null);
  }
  
  /**
   * Create the call and add it to list of the existing in storage, optionally start the call and notify its participants.
   *
   * @param id the id of the call
   * @param ownerId the owner id of the call
   * @param ownerType the owner type
   * @param title the title of the call
   * @param providerType the provider type
   * @param partIds the original participants collection
   * @param spaces the original spaces (pretty names collection) with members allowed for participating the call
   * @param start the start flag, if <code>true</code> then the call will be started (listeners will be notified)
   * @return the call info object of type {@link CallInfo}
   * @throws CallArgumentException if call argument has wrong value (failed validation)
   * @throws StorageException if error reading, adding or updating call information in persistent storage
   * @throws IdentityStateException if error reading room member in Organization Service
   * @throws CallConflictException if such call already exists and has active state (started and/or running)
   * @throws CallSettingsException if call entry has wrong settings (it's for Chat room call, the title -
   *           too long or has bad value)
   */
  public CallInfo createCall(String id,
                             String ownerId,
                             String ownerType,
                             String title,
                             String providerType,
                             Collection<String> partIds,
                             Collection<String> spaces,
                             boolean start,
                             Date startDate,
                             Date endDate) throws CallArgumentException,
                                           StorageException,
                                           IdentityStateException,
                                           CallConflictException,
                                           CallSettingsException {
    final long opStart = System.currentTimeMillis();
    final String currentUserId = currentUserId();

    if (isValidId(id)) {
      if (isValidId(ownerId)) {
        if (isNotNullArg(ownerType)) {
          if (isNotNullArg(providerType)) {
            if (isValidText(title)) {

              final boolean isUser = OWNER_TYPE_USER.equals(ownerType);
              final boolean isSpace = OWNER_TYPE_SPACE.equals(ownerType);
              final boolean isSpaceEvent = OWNER_TYPE_SPACEEVENT.equals(ownerType);
              final boolean isRoom = OWNER_TYPE_CHATROOM.equals(ownerType);
              final boolean isGroup = isSpace || isRoom || isSpaceEvent;

              // TODO find a way to create a call in a single transaction (everything including
              // cleanup/deletion of an outdated). As for this moment, it fails to create a new call if some
              // call ID was deleted prior. See comment for clearing storage session below in this method.

              // Check if group doesn't have a call with another ID assigned
              // But space events can have many calls
              if (isGroup && !isSpaceEvent) {
                // it's group call
                String prevId = findGroupCallId(ownerId);
                if (prevId != null && !prevId.equals(id)) {
                  // XXX For a case when some client failed to delete an existing (but outdated etc.) call but
                  // already starting a new one.
                  // It's SfB usecase when browser client failed to delete outdated call (browser/plugin
                  // crashed in IE11) and then starts a new one.
                  deleteCall(prevId);
                  LOG.warn("Deleted outdated group call: " + prevId);
                }
              }

              // Ensure we can create this call
              invalidateCall(id, isGroup);

              IdentityInfo owner;
              if (start && isGroup) {
                // If call will start, we need the owner with resolved members (see below)
                GroupInfo group;
                if (isRoom) {
                  owner = group = roomInfo(ownerId, title, partIds.toArray(new String[partIds.size()]), id);
                } else if (isSpaceEvent) {
                  owner = group = spaceEventInfo(ownerId, id, partIds.toArray(new String[partIds.size()]), spaces.toArray(new String[spaces.size()]));
                } else if (isSpace) {
                  owner = group = spaceInfo(ownerId, id);
                } else {
                  throw new CallArgumentException("Unexpected call owner type: " + ownerType + " for " + ownerId);
                }
                // Group call starts with all parties LEAVED status
                for (UserInfo m : group.getMembers().values()) {
                  m.setState(UserState.LEAVED);
                }
              } else {
                // for just creating call, we use as it was - owner with empty members
                owner = createOwner(ownerId, ownerType, title, isUser, isSpace, isSpaceEvent, isRoom);
              }
              
              // Saving the call
              CallInfo call = new CallInfo(id,
                                           title,
                                           owner,
                                           providerType);
              // Track call's origins, but not participants
              call.addOrigins(createOrigins(providerType, partIds, spaces));
              
              call.setStartDate(startDate);
              call.setEndDate(endDate);

              if (start) {
                // Mark the call as started in DB 
                call.setState(CallState.STARTED);
                call.setLastDate(Calendar.getInstance().getTime());
                // XXX For starting 1-1 call add its participants from the beginning as it will not have such logic as
                // group calls have in syncMembersAndParticipants()
                if (isUser) {
                  call.addParticipants(createParticipants(providerType, partIds));
                }
              } else {
                // XXX we need set date for non started call as it's required by the DB schema
                call.setLastDate(Calendar.getInstance().getTime());
              }

              // Create the call in storage, handle conflicts if required
              createCall(call);
              
              if (start) {
                // Notify *actual* participants (about started call)
                if (isGroup) {
                  // When call starts we need the following (should be similar to startCall()): 
                  // 1) resolve origins (owner's members of space/room) to actual participants (this should be done in createCall())
                  // 2) send them notifications if applicable (not for event calls)
                  // fire group's user listener for incoming, except of the caller
                  for (UserInfo part : call.getParticipants()) {
                    if (UserInfo.TYPE_NAME.equals(part.getType())) {
                      if (!currentUserId.equals(part.getId())) {
                        fireUserCallStateChanged(part.getId(), id, providerType, CallState.STARTED, ownerId, ownerType);
                      }
                    }
                  }
                } else if (isUser) {
                  // It's P2P call
                  notifyUserCallStateChanged(call, currentUserId, CallState.STARTED);
                }
              }

              broacastCallEvent(EVENT_CALL_CREATED, call, currentUserId);

              // Log metrics - call created
              // service=notifications operation=send-push-notification
              // parameters="user:thomas,token:xxxxxxxxxxxDLu-,type:android,pluginId:RelationshipReceivedRequestPlugin" status=ok
              // duration_ms=298
              LOG.info(metricMessage(currentUserId,
                                     call,
                                     OPERATION_CALL_ADDED,
                                     STATUS_OK,
                                     System.currentTimeMillis() - opStart,
                                     null));
              return call;
            } else {
              throw new CallArgumentException("Wrong call title");
            }
          } else {
            throw new CallArgumentException("Wrong provider");
          }
        } else {
          throw new CallArgumentException("Wrong owner type");
        }
      } else {
        throw new CallArgumentException("Wrong owner ID value");
      }
    } else {
      throw new CallArgumentException("Wrong call ID value");
    }
  }

  /**
   * Updates the existing call information and original settings (initial participants and spaces). 
   * Note: this method will not update the call actual participants states (who is running the call right now or leaved it),
   * see {@link #updateParticipants(String, List)} method for such work.
   *
   * @param callId the call id
   * @param ownerId the owner id
   * @param newOwnerType the owner type
   * @param newTitle the title
   * @param newProviderType the provider type
   * @param partIds the participants
   * @param spaces the spaces
   * @param startDate the start date
   * @param endDate the end date
   * @return the call info
   * @throws InvalidCallException the invalid call exception
   * @throws CallArgumentException the call argument exception
   * @throws CallSettingsException the call settings exception
   * @throws StorageException the storage exception
   * @throws CallNotFoundException the call not found exception
   * @throws IdentityStateException the identity state exception
   * @see #updateParticipants(String, List)
   */
  public CallInfo updateCall(String callId,
                             String ownerId,
                             String newOwnerType,
                             String newTitle,
                             String newProviderType,
                             List<String> partIds,
                             List<String> spaces,
                             Date startDate,
                             Date endDate) throws InvalidCallException,
                                             CallArgumentException,
                                             CallSettingsException,
                                             StorageException,
                                             CallNotFoundException,
                                             IdentityStateException,
                                             ParticipantNotFoundException {
    if (isValidId(callId)) {
      CallInfo currentCall = getCall(callId);
      if (currentCall != null) {
        final String ownerType;
        if (isNotNullArg(newOwnerType)) {
          ownerType = newOwnerType;
        } else {
          ownerType = currentCall.getOwner().getType();
        }
        final boolean isUser = OWNER_TYPE_USER.equals(ownerType);
        final boolean isSpace = OWNER_TYPE_SPACE.equals(ownerType);
        final boolean isSpaceEvent = OWNER_TYPE_SPACEEVENT.equals(ownerType);
        final boolean isRoom = OWNER_TYPE_CHATROOM.equals(ownerType);
  
        final String title;
        if (isValidText(newTitle)) {
          title = newTitle;
        } else {
          title = currentCall.getTitle();
        }
  
        // Collecting the call data
        final IdentityInfo owner;
        if (isValidId(ownerId)) {
          owner = createOwner(ownerId, ownerType, title, isUser, isSpace, isSpaceEvent, isRoom);
        } else {
          owner = currentCall.getOwner();
        }
  
        final String providerType;
        if (isNotNullArg(newProviderType)) {
          providerType = newProviderType;
        } else {
          providerType = currentCall.getProviderType();
        }
  
        // Updated call instance
        // TODO why not reuse existing call object and update only actually passed to this method?
        CallInfo call = new CallInfo(callId, title, owner, providerType);
        call.setLastDate(currentCall.getLastDate());
        
        // We update origins not actual participants (they will be resolved on demand, e.g. in getCall())
        call.addOrigins(createOrigins(providerType, partIds, spaces));
        // We don't update actual participants here, see updateParticipants() method for such work.
        //call.addParticipants(currentCall.getParticipants()); 

        final Date newStartDate;
        if (startDate != null) {
          newStartDate = startDate;
        } else {
          newStartDate = currentCall.getStartDate();
        }
        call.setStartDate(newStartDate);

        final Date newEndDate;
        if (endDate != null) {
          newEndDate = endDate;
        } else {
          newEndDate = currentCall.getEndDate();
        }
        call.setEndDate(newEndDate);

        updateCallAndOrigins(call);
        return call;
      } else {
        throw new CallNotFoundException("Call not found " + callId);
      }
    } else {
      throw new CallArgumentException("Wrong call ID value");
    }
  }

  /**
   * Make call's direct participants from its IDs.
   *
   * @param providerType the provider type
   * @param partIds the participants IDs
   * @return the participants
   * @throws IdentityStateException the identity state exception
   * @throws CallArgumentException the call argument exception
   */
  protected Set<UserInfo> createParticipants(String providerType, Collection<String> partIds) throws IdentityStateException,
                                                                                                CallArgumentException {
    Set<UserInfo> participants = new LinkedHashSet<>();
    for (String pid : partIds) {
      if (isValidId(pid)) {
        UserInfo part = userInfo(pid);
        if (part != null) {
            // it's eXo user
          participants.add(part);
        } else {
          // external participant
          participants.add(part = new ParticipantInfo(providerType, pid));
        }
        // We start the call with all parts leaved, later call pages will update it to JOINED
        part.setState(UserState.LEAVED);
      } else {
        LOG.error("Cannot add call participant with too long ID: " + pid);
        throw new CallArgumentException("Wrong participant ID (" + pid + ")");
      }
    }
    return participants;
  }
  
  /**
   * Creates the origins.
   *
   * @param providerType the provider type
   * @param partIds the part ids
   * @param spacePrettyNames the spaces names
   * @return the sets of origins built from participants and spaces
   * @throws IdentityStateException the identity state exception
   * @throws CallArgumentException the call argument exception
   */
  protected Set<OriginInfo> createOrigins(String providerType, Collection<String> partIds, Collection<String> spacePrettyNames)
                                                                                                                          throws IdentityStateException,
                                                                                                                          CallArgumentException {
    Set<OriginInfo> origins = new LinkedHashSet<>();
    if (partIds != null) {
      for (String pid : partIds) {
        if (isValidId(pid)) {
          UserInfo userInfo = userInfo(pid);
          if (userInfo != null) {
            origins.add(new OriginInfo(pid, OWNER_TYPE_USER));
          } else {
            // if participant user not found, should we treat it as an external user in Chat?
            LOG.error("Cannot find call origin participant user: " + pid);
            throw new CallArgumentException("Wrong participant name (" + pid + ")");
          }
        } else {
          LOG.error("Cannot add call origin participant with too long name: " + pid);
          throw new CallArgumentException("Wrong participant name (" + pid + ")");
        }
      }
    }
    if (spacePrettyNames != null) {
      for (String gid : spacePrettyNames) {
        if (isValidId(gid)) {
          Space space = spaceService.getSpaceByPrettyName(gid);
          if (space != null) {
            origins.add(new OriginInfo(gid, OWNER_TYPE_SPACE));
          } else {
            LOG.error("Cannot find call origin space: " + gid);
            throw new CallArgumentException("Wrong space name (" + gid + ")");
          }
        } else {
          LOG.error("Cannot add call origin space with too long name: " + gid);
          throw new CallArgumentException("Wrong space name (" + gid + ")");
        }
      }
    }
    return origins;
  }

  /**
   * Creates call owner.
   *
   * @param ownerId the owner id
   * @param ownerType the owner type
   * @param title the title
   * @param isUser the is user
   * @param isSpace the is space
   * @param isSpaceEvent the is space event
   * @param isRoom the is room
   * @return the call owner
   * @throws CallArgumentException the call argument exception
   * @throws IdentityStateException the identity state exception
   */
  protected IdentityInfo createOwner(String ownerId,
                                     String ownerType,
                                     String title,
                                     boolean isUser,
                                     boolean isSpace,
                                     boolean isSpaceEvent,
                                     boolean isRoom) throws CallArgumentException, IdentityStateException {
    final IdentityInfo owner;
    if (isUser) {
      UserInfo userInfo = userInfo(ownerId);
      if (userInfo != null) {
        owner = userInfo;
        owner.setProfileLink(userInfo.getProfileLink());
        String avatar = userInfo.getAvatarLink();
        avatar = avatar != null ? avatar : LinkProvider.PROFILE_DEFAULT_AVATAR_URL;
        owner.setAvatarLink(avatar);
      } else {
        // if owner user not found, should we treat it as an external user in Chat, thus assume it is a chat room?
        LOG.error("Cannot find call's owner user: " + ownerId);
        //owner = new RoomInfo(ownerId, title);
        //owner.setAvatarLink(LinkProvider.PROFILE_DEFAULT_AVATAR_URL);
        // or for space/room as below
        //owner.setAvatarLink(LinkProvider.SPACE_DEFAULT_AVATAR_URL);
        throw new CallArgumentException("Wrong call owner (" + ownerId + ")");
      }
    } else if (isSpace) {
      Space space = spaceService.getSpaceByPrettyName(ownerId);
      if (space != null) {
        owner = new SpaceInfo(space);
        owner.setProfileLink(space.getUrl());
        String avatar = space.getAvatarUrl();
        avatar = avatar != null ? avatar : LinkProvider.SPACE_DEFAULT_AVATAR_URL;
        owner.setAvatarLink(avatar);
      } else {
        LOG.error("Cannot find call's owner space: " + ownerId);
        throw new CallArgumentException("Wrong call owner (" + ownerId + ")");
      }
    } else if (isSpaceEvent) {
      Space space = spaceService.getSpaceByPrettyName(ownerId);
      if (space != null) {
        owner = new SpaceEventInfo(space);
        owner.setProfileLink(space.getUrl());
        String avatar = space.getAvatarUrl();
        avatar = avatar != null ? avatar : LinkProvider.SPACE_DEFAULT_AVATAR_URL;
        owner.setAvatarLink(avatar);
      } else {
        LOG.error("Cannot find call's owner event space: " + ownerId);
        throw new CallArgumentException("Wrong call owner (" + ownerId + ")");
      }
    } else if (isRoom) {
      owner = new RoomInfo(ownerId, title);
      owner.setAvatarLink(LinkProvider.SPACE_DEFAULT_AVATAR_URL);
    } else {
      throw new CallArgumentException("Wrong call owner type: " + ownerType);
    }
    return owner;
  }

  /**
   * Gets an active call info.
   *
   * @param id the id
   * @return the call info or <code>null</code> if call not found
   * @throws InvalidCallException if call in erroneous state, see cause exception for details
   */
  public CallInfo getCall(String id) throws InvalidCallException {
    try {
      return findCallById(id, true);
    } catch (CallSettingsException | CallOwnerException | StorageException | IdentityStateException e) {
      throw new InvalidCallException("Error getting call: " + id, e);
    }
  }

  /**
   * Removes the call info from active and fires STOPPED event.
   *
   * @param callId the call id
   * @param remove the remove
   * @return the call info object of type {@link CallInfo}
   * @throws CallNotFoundException if call not found
   * @throws InvalidCallException if call in erroneous state and cannot be used, details are in caused
   *           exception
   */
  public CallInfo stopCall(String callId, boolean remove) throws CallNotFoundException, InvalidCallException {
    final long opStart = System.currentTimeMillis();
    CallInfo call = getCall(callId);
    if (call != null) {
      String userId = currentUserId();
      try {
        stopCall(call, userId, remove);
        broacastCallEvent(EVENT_CALL_STOPPED, call, userId);

        if (remove) {
          // Log metrics - call deleted
          LOG.info(metricMessage(userId, call, OPERATION_CALL_DELETED, STATUS_OK, System.currentTimeMillis() - opStart, null));
        } else {
          // Log metrics - call stopped
          LOG.info(metricMessage(userId, call, OPERATION_CALL_STOPPED, STATUS_OK, System.currentTimeMillis() - opStart, null));
        }
        return call;
      } catch (StorageException e) {
        throw new InvalidCallException("Error stopping call: " + callId, e);
      }
    } else {
      throw new CallNotFoundException("Call not found: " + callId);
    }
  }

  /**
   * Stop call.
   *
   * @param call the call
   * @param userId the user id
   * @param remove the remove
   * @throws StorageException if persistence exception happen
   */
  protected void stopCall(CallInfo call, String userId, boolean remove) throws StorageException {
    try {
      // Stop the call in DB
      txStopCall(call, remove);
      // Then notify users
      if (call.getOwner().isGroup()) {
        String callId = call.getId();
        for (UserInfo part : call.getParticipants()) {
          if (UserInfo.TYPE_NAME.equals(part.getType()) || GuestInfo.TYPE_NAME.equals(part.getType())) {
            // It's eXo user: fire user listener for stopped call: we notify to all participants.
            fireUserCallStateChanged(part.getId(),
                                     callId,
                                     call.getProviderType(),
                                     CallState.STOPPED,
                                     call.getOwner().getId(),
                                     call.getOwner().getType());
          }
        }
      } else {
        notifyUserCallStateChanged(call, userId, CallState.STOPPED);
      }
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error stopping call " + call.getId(), e);
    }
  }
  
  /**
   * Starts existing call and fires STARTED event. It's actual for group calls.
   *
   * @param callId the call id
   * @param clientId the client id
   * @return the call info object of type {@link CallInfo}
   * @throws CallNotFoundException if call not found
   * @throws InvalidCallException if call in erroneous state and cannot be used, details are in caused
   *           exception
   */
  public CallInfo startCall(String callId, String clientId) throws CallNotFoundException, InvalidCallException {
    final long opStart = System.currentTimeMillis();
    CallInfo call = getCall(callId);
    if (call != null) {
      try {
        // TODO use current user from the request (Comet) not an one system
        String userId = currentUserId();
        startCall(call, userId, clientId, true);

        broacastCallEvent(EVENT_CALL_STARTED, call, userId);

        // Log metrics - call started
        LOG.info(metricMessage(userId, call, OPERATION_CALL_STARTED, STATUS_OK, System.currentTimeMillis() - opStart, null));
        return call;
      } catch (StorageException | ParticipantNotFoundException | CallSettingsException e) {
        throw new InvalidCallException("Error starting call: " + callId, e);
      }
    } else {
      throw new CallNotFoundException("Call not found: " + callId);
    }
  }

  /**
   * Start existing call.
   *
   * @param call the call
   * @param partId the participant id who started the call
   * @param clientId the client id
   * @param notifyStarted if <code>true</code> then all participants will be notified about the started call
   * @throws ParticipantNotFoundException if call or its participants not found in storage
   * @throws CallSettingsException if call entry has wrong settings (room title, owner type etc)
   * @throws StorageException if storage exception happen
   * @throws CallNotFoundException if call not found in storage
   */
  protected void startCall(CallInfo call, String partId, String clientId, boolean notifyStarted) throws ParticipantNotFoundException,
                                                                          CallSettingsException,
                                                                          StorageException,
                                                                          CallNotFoundException {
    // TODO exception if user not a participant?
    String callId = call.getId();

    // We save call in a single tx, thus logic split on gathering the changes and saving them at the end
    call.setState(CallState.STARTED);
    call.setLastDate(Calendar.getInstance().getTime());

    // Sync call participants (actual for groups only) - this will happen in a dedicated DB tx
    syncMembersAndParticipants(call);

    // On call start we mark all parts LEAVED and then each of them will join and be marked as JOINED in joinCall()
    for (UserInfo part : call.getParticipants()) {
      if (UserInfo.TYPE_NAME.equals(part.getType()) && partId.equals(part.getId())) {
        part.setState(UserState.JOINED);
        part.setClientId(clientId);
      } else {
        part.setState(UserState.LEAVED);
        part.setClientId(null);
      }
    }
    updateCallAndParticipants(call);

    // Dec 3, 2020: Optionally notify the call started
    if (notifyStarted) {
      // Inform all group members about the call
      // For P2P call we need only inform another peer and this loop does the work perfectly
      Collection<UserInfo> parts = call.getOwner().isGroup() ? GroupInfo.class.cast(call.getOwner()).getMembers().values()
                                                             : call.getParticipants();
      for (UserInfo part : parts) {
        fireUserCallStateChanged(part.getId(),
                                 callId,
                                 call.getProviderType(),
                                 CallState.STARTED,
                                 call.getOwner().getId(),
                                 call.getOwner().getType());
      }
    }
  }

  /**
   * Update participants of the call.
   * Used to update current participants of call with provided user IDs. 
   * Use this method to sycn a group call associated with external apps like eXo Chat.
   *
   * @param callId the call id
   * @param partIds the participants ids
   * @return the call info
   * @throws CallNotFoundException the call not found exception
   * @throws InvalidCallException the invalid call exception
   * @throws StorageException the storage exception
   */
  public CallInfo updateParticipants(String callId, List<String> partIds) throws CallNotFoundException,
                                                                           InvalidCallException,
                                                                           StorageException {

    if (partIds == null || partIds.isEmpty()) {
      throw new IllegalArgumentException("Participants cannot be null or empty");
    }
    List<UserInfo> userInfos = new ArrayList<>();
    for (String userId : partIds) {
      try {
        UserInfo userInfo = userInfo(userId);
        if (userInfo != null) {
          userInfos.add(userInfo);
        }
      } catch (IdentityStateException e) {
        LOG.warn("Cannot get userInfo for " + userId, e);
      }
    }
    try {
      CallInfo call = findCallById(callId, false); // don't read participants and add all new below
      if (call != null) {
        try {
          txUpdateParticipants(call, userInfos);
        } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
          throw new StorageException("Error updating participants of the call " + callId, e);
        }
        return call;
      } else {
        throw new CallNotFoundException("Call not found: " + callId);
      }
    } catch (CallSettingsException | CallOwnerException | IdentityStateException e) {
      throw new StorageException("Error reading the call " + callId, e);
    }
  }
  
  /**
   * Adds the participant to existing call.
   *
   * @param callId the call id
   * @param partId the part id
   * @throws StorageException the storage exception
   * @throws CallNotFoundException the call not found exception
   * @throws InvalidCallException the invalid call exception
   * @throws IdentityStateException the identity state exception
   * @throws CallArgumentException if participant cannot be found
   */
  public void addParticipant(String callId, String partId) throws StorageException,
                                                           CallNotFoundException,
                                                           InvalidCallException,
                                                           IdentityStateException, 
                                                           CallArgumentException {
    UserInfo userInfo = userInfo(partId);
    if (userInfo != null) {
      try {
        txAddParticipant(callId, userInfo);
      } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
        throw new StorageException("Error adding participant to call " + callId, e);
      }
    } else {
      throw new CallArgumentException("Participant user cannot be found: " + partId);
    }
  }

  /**
   * Adds the guest to call.
   * Adds guest as a participant to the call.
   *
   * @param callIId the call id
   * @param guestId the guest id
   * @return the call info
   * @throws StorageException the storage exception
   * @throws CallNotFoundException the call not found exception
   * @throws InvalidCallException the invalid call exception
   * @throws IdentityStateException the identity state exception
   */
  public CallInfo addGuest(String callIId, String guestId) throws StorageException,
                                                      CallNotFoundException,
                                                      InvalidCallException,
                                                      IdentityStateException {
    UserInfo userInfo = userInfo(guestId);
    GuestInfo guestInfo = userInfo == null ? new GuestInfo(guestId) : new GuestInfo(userInfo);
    CallInfo call = getCall(callIId);
    if (call != null) {
      try {
        txAddParticipant(callIId, guestInfo);
      } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
        throw new StorageException("Error adding guest to call " + callIId, e);
      }
      call.addParticipant(guestInfo);
      return call;
    } else {
      throw new CallNotFoundException("Call not found: " + callIId);
    }
  }
  
  /**
   * Update invites.
   * Updates list of invited users/groups for the call that can join by invite link.
   *
   * @param callId the call id
   * @param identities the invites
   * @return the call info
   * @throws CallNotFoundException the call not found exception
   * @throws InvalidCallException the invalid call exception
   * @throws StorageException the storage exception
   */
  public CallInfo updateInvites(String callId, List<InvitedIdentity> identities) throws CallNotFoundException,
                                                                                 InvalidCallException,
                                                                                 StorageException {
    CallInfo call = getCall(callId);
    if (call != null) {
      try {
        txUpdateInvites(callId, identities);
        return call;
      } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
        throw new StorageException("Error updating invites of call " + callId, e);
      }
    } else {
      throw new CallNotFoundException("Call not found: " + callId);
    }
  }

  /**
   * Join a call if it is started or start already stopped one.
   *
   * @param callId the call id
   * @param partId the participant id
   * @param clientId the client id
   * @return the call info object of type {@link CallInfo}
   * @throws InvalidCallException if call in erroneous state and cannot be used, details are in caused
   *           exception
   * @throws CallNotFoundException if call not found
   * @throws IdentityStateException the identity state exception
   * @throws CallArgumentException if participant cannot be found
   */
  public CallInfo joinCall(String callId, String partId, String clientId) throws InvalidCallException, CallNotFoundException, IdentityStateException, CallArgumentException {
    final long opStart = System.currentTimeMillis();
    CallInfo call = getCall(callId);
    if (call != null) {
      try {
        if (CallState.STARTED.equals(call.getState())) {
          // Call already started - join the participant to it
          UserInfo joined = null;
          // save Joined first
          for (UserInfo part : call.getParticipants()) {
            if ((UserInfo.TYPE_NAME.equals(part.getType()) || GuestInfo.TYPE_NAME.equals(part.getType()))
                && partId.equals(part.getId())) {
              part.setState(UserState.JOINED);
              part.setClientId(clientId);
              joined = part;
              break;
            }
          }
          // then save if have the joined (it should but we preserve the logic)
          if (joined != null) {
            // First save the call with joined participant (in single tx)
            try {
              updateParticipant(callId, joined);
            } catch(ParticipantNotFoundException e) {
              // XXX check if this participant not from group's origins
              if (call.getOwner().isGroup() && GroupInfo.class.cast(call.getOwner()).getMembers().keySet().contains(partId)) {
                addParticipant(callId, partId);
              } else {
                throw new ParticipantNotFoundException("Cannot join the call with not allowed participant: " + partId 
                                                       + ", call: " + callId, e);
              }
            }
            // Then fire this user joined to all parts, including the user itself
            for (UserInfo part : call.getParticipants()) {
              fireUserCallJoined(callId,
                                 call.getProviderType(),
                                 call.getOwner().getId(),
                                 call.getOwner().getType(),
                                 partId,
                                 part.getId());
            }
            broacastCallEvent(EVENT_CALL_JOINDED, call, partId);

            // Log metrics - call joined
            LOG.info(metricMessage(partId, call, OPERATION_CALL_JOINED, STATUS_OK, System.currentTimeMillis() - opStart, null));
          } else {
            LOG.warn("Call join invoked but no participant was found for given user. Call ID: " + callId + ", participant: " + partId);
          }
        } else {
          // Auto-start logic here, if someone joins a not started call - we start the call, but do this without notification of its parties.
          // TODO check should we use partId instead of the current user for the start
          // the partId it's current exo user in the request (Comet)
          String userId = currentUserId();
          startCall(call, userId, clientId, false); // We will not notify parties about the auto-start

          broacastCallEvent(EVENT_CALL_JOINDED, call, userId);

          // Log metrics - call started
          LOG.info(metricMessage(userId, call, OPERATION_CALL_STARTED, STATUS_OK, System.currentTimeMillis() - opStart, null));
        }
      } catch (CallSettingsException | ParticipantNotFoundException | StorageException e) {
        throw new InvalidCallException("Error joining call: " + callId, e);
      }
      return call;
    } else {
      throw new CallNotFoundException("Call not found: " + callId);
    }
  }

  /**
   * If call started, then notify all its parties that given participant leaved. If call not found then
   * <code>null</code> will be returned.
   *
   * @param callId the call id
   * @param partId the participant id
   * @param clientId the client id
   * @return the call info or <code>null</code> if call not found
   * @throws InvalidCallException if call in erroneous state and cannot be used, details are in caused
   *           exception
   */
  public CallInfo leaveCall(String callId, String partId, String clientId) throws InvalidCallException {
    final long opStart = System.currentTimeMillis();
    CallInfo call = getCall(callId);
    if (call != null) {
      try {
        if (CallState.STARTED.equals(call.getState()) || CallState.PAUSED.equals(call.getState())) {
          UserInfo leaved = null;
          boolean isGuestLeaved = false;
          int leavedNum = 0;
          for (UserInfo part : call.getParticipants()) {
            boolean partIsGuest = GuestInfo.TYPE_NAME.equals(part.getType());
            if (UserInfo.TYPE_NAME.equals(part.getType()) || partIsGuest) {
              // Users will leave the call, but guests will go (removed) from participants
              if (partId.equals(part.getId())) {
                // Leave should not be called on a call session started after stopping an one previous of this call.
                part.setState(UserState.LEAVED);
                part.setClientId(null);
                leaved = part;
                if (partIsGuest) {
                  isGuestLeaved = true; 
                }
                leavedNum++;
              } else {
                // if null - user hasn't joined
                if (part.getState() == null || UserState.LEAVED.equals(part.getState())) {
                  leavedNum++;
                }
              }
            }
          }
          // then save if someone leaved
          if (leaved != null) {
            // First update the call with leaved participant
            if (isGuestLeaved) {
              removeParticipant(callId, leaved);
            } else {
              updateParticipant(callId, leaved);
            }
            // Fire user leaved to all parts, including the user itself
            for (UserInfo part : call.getParticipants()) {
              // Fire user leaved to all parts, including the user itself
              fireUserCallLeaved(callId,
                                 call.getProviderType(),
                                 call.getOwner().getId(),
                                 call.getOwner().getType(),
                                 partId,
                                 part.getId());
            }

            broacastCallEvent(EVENT_CALL_LEFT, call, partId);
            // Log metrics - call leaved
            LOG.info(metricMessage(partId, call, OPERATION_CALL_LEAVED, STATUS_OK, System.currentTimeMillis() - opStart, null));
            // Check if don't need stop the call if all parts leaved already
            if (call.getOwner().isGroup()) {
              if (leavedNum == call.getParticipants().size() || call.getParticipants().size() == 0
                  || call.getParticipants().stream().allMatch(p -> p.getState() == null || UserState.LEAVED.equals(p.getState()))
                  /*|| call.getParticipants().stream().allMatch(p -> p.getType() == GuestInfo.TYPE_NAME)*/) {
                // Stop when all group members leave the call
                // TODO it would be better UX when we let guest to run the call even without exo users,
                // but then need find a proper way of stopping the call if guests will not leave finally via API (network errors, server crashes etc).
                stopCall(call, partId, false);

                broacastCallEvent(EVENT_CALL_STOPPED, call, partId);
                // Log metrics - call stopped
                LOG.info(metricMessage(partId,
                                       call,
                                       OPERATION_CALL_STOPPED,
                                       STATUS_OK,
                                       System.currentTimeMillis() - opStart,
                                       null));
              }
            } else if (call.getParticipants().size() - leavedNum <= 1) {
              // For P2P we remove the call when one of parts stand alone
              stopCall(call, partId, true);

              broacastCallEvent(EVENT_CALL_STOPPED, call, partId);
              // Log metrics - call deleted
              LOG.info(metricMessage(partId,
                                     call,
                                     OPERATION_CALL_DELETED,
                                     STATUS_OK,
                                     System.currentTimeMillis() - opStart,
                                     null));
            }
          } // else, if no one leaved, we don't need any action (it may be leaved an user of already stopped
            // call, see comments above)
        } // It seems has no big sense to return error for already stopped call
      } catch (StorageException | ParticipantNotFoundException e) {
        throw new InvalidCallException("Error leaving call: " + callId, e);
      }
    } else {
      LOG.warn("Call " + callId + " not found to leave it " + partId);
    }
    return call;
  }

  /**
   * Gets the user calls.
   *
   * @param userId the user id
   * @return the user call states
   * @throws StorageException if persistence error happen
   */
  public CallState[] getUserCalls(String userId) throws StorageException {
    // TODO it's not efficient to read whole Call info when we need only its ID and state
    CallState[] states =
                       findUserGroupCalls(userId).stream()
                                                 .map(c -> new CallState(c.getId(),
                                                                         c.getState() != null ? c.getState() : CallState.STOPPED))
                                                 .toArray(size -> new CallState[size]);
    return states;
  }

  /**
   * Adds the user listener.
   *
   * @param listener the listener
   */
  public void addUserCallListener(UserCallListener listener) {
    final String userId = listener.getUserId();
    userListeners.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(listener);
  }

  /**
   * Removes the user listener.
   *
   * @param listener the listener
   */
  public void removeUserCallListener(UserCallListener listener) {
    final String userId = listener.getUserId();
    Set<UserCallListener> listeners = userListeners.get(userId);
    if (listeners != null) {
      listeners.remove(listener);
    }
  }

  /**
   * Fire user call state.
   *
   * @param userId the user id
   * @param callId the call id
   * @param providerType the provider type
   * @param callState the call state
   * @param ownerId the caller id
   * @param ownerType the caller type
   */
  protected void fireUserCallStateChanged(String userId,
                                          String callId,
                                          String providerType,
                                          String callState,
                                          String ownerId,
                                          String ownerType) {
    // Synchronize on userListeners to have a consistent list of listeners to fire
    Set<UserCallListener> listeners = userListeners.get(userId);
    if (listeners != null) {
      for (UserCallListener listener : listeners) {
        listener.onCallStateChanged(callId, providerType, callState, ownerId, ownerType);
      }
    }
  }

  /**
   * Fire user call joined a new part.
   *
   * @param callId the call id
   * @param providerType the provider type
   * @param ownerId the owner id
   * @param ownerType the owner type
   * @param partId the part id
   * @param userId the user id
   */
  protected void fireUserCallJoined(String callId,
                                    String providerType,
                                    String ownerId,
                                    String ownerType,
                                    String partId,
                                    String userId) {
    Set<UserCallListener> listeners = userListeners.get(userId);
    if (listeners != null) {
      for (UserCallListener listener : listeners) {
        listener.onPartJoined(callId, providerType, ownerId, ownerType, partId);
      }
    }
  }

  /**
   * Fire user call part leaved.
   *
   * @param callId the call id
   * @param providerType the provider type
   * @param ownerId the owner id
   * @param ownerType the owner type
   * @param partId the part id
   * @param userId the user id
   */
  protected void fireUserCallLeaved(String callId,
                                    String providerType,
                                    String ownerId,
                                    String ownerType,
                                    String partId,
                                    String userId) {
    Set<UserCallListener> listeners = userListeners.get(userId);
    if (listeners != null) {
      for (UserCallListener listener : listeners) {
        listener.onPartLeaved(callId, providerType, ownerId, ownerType, partId);
      }
    }
  }

  /**
   * Adds a provider plugin. This method is safe in runtime: if configured provider is not an instance of
   * {@link CallProvider} then it will log a warning and let server continue the start.
   *
   * @param plugin the plugin
   */
  public void addPlugin(ComponentPlugin plugin) {
    Class<CallProvider> pclass = CallProvider.class;
    if (pclass.isAssignableFrom(plugin.getClass())) {
      addProvider(pclass.cast(plugin));
    } else {
      LOG.warn("Web Conferencing provider plugin is not an instance of " + pclass.getName() + ". Skipped plugin: " + plugin);
    }
  }

  /**
   * Adds the provider.
   *
   * @param provider the provider
   */
  public void addProvider(CallProvider provider) {
    for (String type : provider.getSupportedTypes()) {
      CallProvider existing = providers.putIfAbsent(type, provider);
      if (existing != null) {
        LOG.warn("Web Conferencing provider type '" + existing.getType() + "' already registered. Skipped plugin: " + provider);
      }
    }
  }

  /**
   * Check invite.
   *
   * @param callId the call id
   * @param inviteId the invite id
   * @param identity the identity
   * @return true, if successful
   * @throws Exception the exception
   */
  public boolean checkInvite(String callId, String inviteId, String identity) throws Exception {
    for (InviteEntity invite : inviteStorage.findCallInvites(callId)) {
      if (invite.getInvitationId().equals(inviteId)) {
        if (invite.getIdentity().equals(ALL_USERS)) {
          return true;
        }
        if (USER.equals(invite.getIdentityType()) && invite.getIdentity().equals(identity)) {
          return true;
        }
        if (GROUP.equals(invite.getIdentityType())) {
          Collection<Membership> membersips = organization.getMembershipHandler()
                                                          .findMembershipsByUserAndGroup(identity, invite.getIdentity());
          return !membersips.isEmpty();
        }
      }
    }
    return false;
  }

  /**
   * Gets the provider.
   *
   * @param type the type
   * @return the provider
   */
  public CallProvider getProvider(String type) {
    CallProvider p = providers.get(type);
    if (p != null) {
      try {
        // Apply saved configurations (via Admin UI etc)
        CallProviderConfiguration conf = readProviderConfig(p.getType());
        if (conf != null) {
          p.setActive(conf.isActive());
        } else {
          p.setActive(true);
        }
      } catch (Exception e) {
        LOG.warn("Error reading provider configuration " + p.getType(), e);
        p.setActive(true);
      }
    }
    return p;
  }

  /**
   * Gets the provider configurations.
   *
   * @param locale the locale to apply to provider description, if <code>null</code> a default one will be
   *          used
   * @return the provider configurations
   */
  public Set<CallProviderConfiguration> getProviderConfigurations(Locale locale) {
    Set<CallProvider> allProviders = new LinkedHashSet<>();
    // Collect all registered providers via configuration
    for (CallProvider registeredProvider : providers.values()) {
      allProviders.add(registeredProvider);
    }
    // Read configurations saved in storage for each of them
    Set<CallProviderConfiguration> allConfs = new LinkedHashSet<>();
    for (Iterator<CallProvider> piter = allProviders.iterator(); piter.hasNext();) {
      CallProvider p = piter.next();
      boolean addDefault = false;
      try {
        CallProviderConfiguration conf = readProviderConfig(p.getType());
        if (conf != null) {
          conf.setTitle(p.getTitle());
          conf.setDescription(p.getDescription(locale));
          conf.setLogEnabled(p.isLogEnabled());
          allConfs.add(conf);
        } else {
          addDefault = true;
        }
      } catch (Exception e) {
        LOG.warn("Error reading providers configuration " + p.getType(), e);
        addDefault = true; // this way we let read and re-save the erroneous config
      }
      if (addDefault) {
        CallProviderConfiguration defaultConf = CallProviderConfiguration.fromProvider(p, locale);
        allConfs.add(defaultConf);
      }
    }
    return allConfs;
  }

  /**
   * Gets the provider configurations.
   *
   * @return the provider configurations
   */
  public Set<CallProviderConfiguration> getProviderConfigurations() {
    return this.getProviderConfigurations(Locale.getDefault());
  }

  /**
   * Gets the provider configuration.
   *
   * @param providerType the provider type
   * @param locale the locale to apply to provider description, if <code>null</code> a default one will be
   *          used
   * @return the provider configuration or <code>null</code> if provider not found
   */
  public CallProviderConfiguration getProviderConfiguration(String providerType, Locale locale) {
    CallProvider p = getProvider(providerType);
    if (p != null) {
      CallProviderConfiguration conf = readProviderConfig(p.getType());
      if (conf == null) {
        conf = CallProviderConfiguration.fromProvider(p, locale);
      } else {
        conf.setTitle(p.getTitle());
        conf.setDescription(p.getDescription(locale));
        conf.setLogEnabled(p.isLogEnabled());
      }
      return conf;
    }
    return null; // not found
  }

  /**
   * Save provider configuration.
   *
   * @param conf the configuration to save
   * @throws UnsupportedEncodingException if UTF8 not supported
   * @throws JSONException if cannot serialize to JSON
   */
  public void saveProviderConfiguration(CallProviderConfiguration conf) throws UnsupportedEncodingException, JSONException {
    final String initialGlobalId = Scope.GLOBAL.getId();
    try {
      JSONObject json = providerConfigToJson(conf);
      String safeType = URLEncoder.encode(conf.getType(), "UTF-8");
      settingService.set(Context.GLOBAL, Scope.GLOBAL.id(PROVIDER_SCOPE_NAME), safeType, SettingValue.create(json.toString()));
    } finally {
      Scope.GLOBAL.id(initialGlobalId);
    }
  }

  /**
   * Find groups and users.
   *
   * @param name the name
   * @return the list
   * @throws Exception the exception
   */
  public List<IdentityData> findGroupsAndUsers(String name) throws Exception {
    List<IdentityData> identitiesData = findUsers(name, MAX_RESULT_SIZE / 2);
    int remain = MAX_RESULT_SIZE - identitiesData.size();
    identitiesData.addAll(findGroups(name, remain));
    Collections.sort(identitiesData, new Comparator<IdentityData>() {
      public int compare(IdentityData s1, IdentityData s2) {
        return s1.getDisplayName().compareTo(s2.getDisplayName());
      }
    });
    return identitiesData;
  }

  /**
   * Find users.
   *
   * @param name the name
   * @param count the count
   * @return the list
   * @throws Exception the exception
   */
  protected List<IdentityData> findUsers(String name, int count) throws Exception {
    List<IdentityData> results = new ArrayList<>();
    ProfileFilter identityFilter = new ProfileFilter();
    identityFilter.setName(name);
    ListAccess<Identity> identitiesList = socialIdentityManager.getIdentitiesByProfileFilter(OrganizationIdentityProvider.NAME,
                                                                                             identityFilter,
                                                                                             false);
    int size = identitiesList.getSize() >= count ? count : identitiesList.getSize();
    if (size > 0) {
      Identity[] identities = identitiesList.load(0, size);
      for (Identity id : identities) {
        Profile profile = id.getProfile();
        String fullName = profile.getFullName();
        String userName = (String) profile.getProperty(Profile.USERNAME);
        String avatarUrl = profile.getAvatarUrl() != null ? profile.getAvatarUrl() : LinkProvider.PROFILE_DEFAULT_AVATAR_URL;
        results.add(new IdentityData(userName, fullName, USER, avatarUrl));
      }
    }
    return results;
  }

  /**
   * Find groups.
   *
   * @param name the name
   * @param count the count
   * @return the list
   * @throws Exception the exception
   */
  protected List<IdentityData> findGroups(String name, int count) throws Exception {
    List<IdentityData> results = new ArrayList<>();
    ListAccess<Group> groupsAccess = organization.getGroupHandler().findGroupsByKeyword(name);
    int size = groupsAccess.getSize() >= count ? count : groupsAccess.getSize();
    if (size > 0) {
      Group[] groups = groupsAccess.load(0, size);
      for (Group group : groups) {
        Space space = spaceService.getSpaceByGroupId(group.getId());
        if (space != null) {
          String avatarUrl = space.getAvatarUrl() != null ? space.getAvatarUrl() : LinkProvider.SPACE_DEFAULT_AVATAR_URL;
          results.add(new IdentityData(space.getGroupId(), space.getDisplayName(), GROUP, avatarUrl));
        } else {
          results.add(new IdentityData(group.getId(), group.getLabel(), GROUP, LinkProvider.SPACE_DEFAULT_AVATAR_URL));
        }
      }
    }
    return results;
  }

  /**
   * Upload recording of the call.
   *
   * @param uploadInfo the upload info
   * @param request the request
   * @throws UploadFileException the upload recording exception
   * @throws RepositoryException the repository exception
   */
  public void uploadFile(UploadFileInfo uploadInfo, HttpServletRequest request) throws UploadFileException, RepositoryException {
    final long opStart = System.currentTimeMillis();
    // final long opStart = System.currentTimeMillis();
    String uploadId = String.valueOf((long) (Math.random() * 100000L));
    try {
      uploadService.createUploadResource(uploadId, request);
    } catch (FileUploadException e) {
      LOG.error("Cannot create upload resource: " + e.getMessage());
      throw new UploadFileException("Cannot create upload resource", e);
    }
    UploadResource resource = uploadService.getUploadResource(uploadId);
    try {
      if (resource.getStatus() == UploadResource.UPLOADED_STATUS) {
        final String uploadingUser = uploadInfo.getUser();
        String owner = null;
        // Owner is user if it's not a space, otherwise use space identity
        if (!uploadInfo.getType().equals(OWNER_TYPE_SPACE) && !uploadInfo.getIdentity().equals(uploadingUser)) {
          owner = uploadingUser;
        } else {
          owner = uploadInfo.getIdentity();
        }
        Node rootNode = getRootFolderNode(owner, uploadInfo.getType());
        // If it's 1-1 or chat-room call, we pass participants to share the file.
        // Otherwise we just upload to the space docs
        if (uploadInfo.getType().equals(OWNER_TYPE_CHATROOM) || uploadInfo.getType().equals(USER)) {
          saveFile(rootNode, resource, uploadingUser, uploadInfo.getParticipants());
        } else {
          saveFile(rootNode, resource, uploadingUser, null);
        }
        try {
          CallInfo call = getCall(uploadInfo.getCallId());
          LOG.info(metricMessage(uploadingUser, call, OPERATION_CALL_RECORDED, STATUS_OK, System.currentTimeMillis() - opStart, null));
        } catch (InvalidCallException e) {
          LOG.warn("Failed to build metric for " + OPERATION_CALL_RECORDED, e);
        }
      } else {
        throw new UploadFileException("The file " + resource.getFileName() + " cannot be uploaded. Status: "
            + resource.getStatus());
      }
    } finally {
      uploadService.removeUploadResource(uploadId);
    }
  }

  /**
   * Save recording to JCR.
   *
   * @param parent the parent
   * @param resource the recording
   * @param user the user
   * @param shareToUsers the users to share file with
   * @throws RepositoryException the repository exception
   * @throws UploadFileException the upload file exception
   */
  private void saveFile(Node parent, UploadResource resource, String user, List<String> shareToUsers) throws RepositoryException,
                                                                                                      UploadFileException {
    if (parent == null) {
      throw new UploadFileException("Cannot save the file because parent node empty for user: " + user);
    }
    if (resource == null) {
      throw new UploadFileException("Cannot save the file because upload resources are empty for user: " + user);
    }
    if (user == null) {
      throw new UploadFileException("Cannot save the file because user is undefined");
    }
    ConversationState state = null;
    try {
      state = createState(user);
      ConversationState.setCurrent(state);
    } catch (Exception e) {
      LOG.error("Cannot set conversation state for user: " + user, e);
      throw new UploadFileException("Cannot set conversation state for user: " + user);
    }

    ManageableRepository repository = repositoryService.getCurrentRepository();
    SessionProvider userProvider = sessionProviders.getSessionProvider(state);
    sessionProviders.setSessionProvider(null, userProvider);
    Session session = userProvider.getSession(repository.getConfiguration().getDefaultWorkspaceName(), repository);
    // Get node under user session
    Node folder = (Node) session.getItem(parent.getPath());
    Node recordingsFolder = getRecordingsFolder(folder);

    if (recordingsFolder != null) {
      Node fileNode = recordingsFolder.addNode(resource.getFileName(), "nt:file");
      if (!fileNode.hasProperty(EXO_TITLE_PROP)) {
        fileNode.addMixin(EXO_RSS_ENABLE_PROP);
      }
      fileNode.setProperty(EXO_TITLE_PROP, resource.getFileName());
      Node content = fileNode.addNode(JCR_CONTENT, NT_RESOURCE);
      try (FileInputStream fis = new FileInputStream(resource.getStoreLocation())) {
        content.setProperty(JCR_DATA, fis);
      } catch (IOException e) {
        LOG.error("Cannot set JCR_DATA to created node.", e.getMessage());
        throw new UploadFileException("Cannot set JCR_DATA for file node " + resource.getFileName());
      }
      content.setProperty(JCR_MIME_TYPE, resource.getMimeType());
      content.setProperty(JCR_LAST_MODIFIED_PROP, new GregorianCalendar());
      folder.save();
      String perm = new StringBuilder(PermissionType.READ).append(",")
                                                          .append(PermissionType.ADD_NODE)
                                                          .append(",")
                                                          .append(PermissionType.SET_PROPERTY)
                                                          .toString();
      // Share file to other users
      if (shareToUsers != null) {
        for (String participant : shareToUsers) {
          if (!participant.equals(user)) {
            shareRecordToUser(participant, fileNode, perm);
          }
        }
      }
      cleanConversationState();
    } else {
      cleanConversationState();
      throw new UploadFileException("Cannot get the node for recorings folder to upload the record (" + resource.getFileName()
          + ") for user:" + user);
    }
  }

  /**
   * Gets recordings folder (create if it's absent).
   *
   * @param folder the folder
   * @return the recordings folder
   * @throws RepositoryException the repository exception
   */
  private Node getRecordingsFolder(Node folder) throws RepositoryException {
    Node recordingsFolder = null;
    if (!folder.hasNode("recordings")) {
      recordingsFolder = addRecordingsFolder(folder);
    } else {
      recordingsFolder = folder.getNode("recordings");
      if (recordingsFolder.isNodeType("nt:unstructured") || recordingsFolder.isNodeType("nt:folder")) {
        recordingsFolder.addMixin("exo:recordingsFolder");
      } else {
        /*
         * try to add "recordings" node if any other node exists with the same name but
         * wrong type and handle errors
         */
        try {
          recordingsFolder = addRecordingsFolder(folder);
        } catch (Exception e) {
          LOG.warn("An error occured while adding the recordings folder when the 'recordings' node already exists", e);
        }
      }
    }
    return recordingsFolder;
  }

  /**
   * Add recordings folder node.
   *
   * @param folder the folder
   * @return the node
   */
  private Node addRecordingsFolder(Node folder) throws RepositoryException {
    Node recordingsFolder = folder.addNode("recordings", "nt:folder");
    recordingsFolder.setProperty("exo:title", "Recordings");
    recordingsFolder.addMixin("exo:recordingsFolder");
    return recordingsFolder;
  }

  /**
   * Clean conversation state.
   */
  private void cleanConversationState() {
    try {
      ConversationState.setCurrent(null);
    } catch (Exception e) {
      LOG.warn("An error occured while cleaning the ConversationState", e);
    }
  }

  /**
   * Gets private user node.
   *
   * @param sessionProvider the session provider
   * @param user the user
   * @return the private user node
   * @throws Exception the exception
   * @throws PathNotFoundException the path not found exception
   * @throws RepositoryException the repository exception
   */
  private Node getPrivateUserNode(SessionProvider sessionProvider,
                                  String user) throws Exception, PathNotFoundException, RepositoryException {
    String privateRelativePath = nodeCreator.getJcrPath("userPrivate");
    Node userNode = nodeCreator.getUserNode(sessionProvider, user);
    return userNode.getNode(privateRelativePath);
  }

  /**
   * Share to user records.
   *
   * @param user the user
   * @param recordNode the record node
   * @param perm the perm
   */
  private void shareRecordToUser(String user, Node recordNode, String perm) {
    Node userPrivateNode = null;
    try {
      SessionProvider sessionProvider = sessionProviders.getSystemSessionProvider(null);
      ManageableRepository repository = repositoryService.getCurrentRepository();
      Session session = sessionProvider.getSession(repository.getConfiguration().getDefaultWorkspaceName(), repository);
      // add symlink to destination user
      userPrivateNode = getPrivateUserNode(sessionProvider, user);
      Node recordingsFolder = getRecordingsFolder(userPrivateNode);
      if (recordNode.isNodeType(NodetypeConstant.EXO_SYMLINK))
        recordNode = linkManager.getTarget(recordNode);
      // Update permission
      String tempPerms = perm.toString();// Avoid ref back to UIFormSelectBox options
      if (!tempPerms.equals(PermissionType.READ))
        tempPerms = PermissionType.READ + "," + PermissionType.ADD_NODE + "," + PermissionType.SET_PROPERTY + ","
            + PermissionType.REMOVE;
      if (PermissionUtil.canChangePermission(recordNode)) {
        setUserPermission(recordNode, user, tempPerms.split(","));
      } else if (PermissionUtil.canRead(recordNode)) {
        SessionProvider systemSessionProvider = SessionProvider.createSystemProvider();
        Session systemSession = systemSessionProvider.getSession(session.getWorkspace().getName(), repository);
        Node _node = (Node) systemSession.getItem(recordNode.getPath());
        setUserPermission(_node, user, tempPerms.split(","));
      }
      recordNode.getSession().save();
      Node link = linkManager.createLink(recordingsFolder, recordNode);
      String nodeMimeType = org.exoplatform.wcm.ext.component.activity.listener.Utils.getMimeType(recordNode);
      link.addMixin(NodetypeConstant.MIX_FILE_TYPE);
      link.setProperty(NodetypeConstant.EXO_FILE_TYPE, nodeMimeType);
      userPrivateNode.save();
    } catch (RepositoryException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(e.getMessage(), e);
      }
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(e.getMessage(), e);
      }
    }
  }

  /**
   * Grant view for parent folder when share a document We need grant assess right
   * for parent in case editing the shared documents
   * 
   * @param currentNode
   * @param username
   * @param permissions
   * @throws Exception
   */
  private void setUserPermission(Node currentNode, String username, String[] permissions) throws Exception {
    ExtendedNode node = (ExtendedNode) currentNode;
    if (node.canAddMixin(MIX_PRIVILEGEABLE)) {
      node.addMixin(MIX_PRIVILEGEABLE);
    }
    node.setPermission(username, permissions);
    node.save();
  }

  /**
   * Gets the root folder node.
   *
   * @param identity the identity
   * @param type the type
   * @return the root folder node
   * @throws RepositoryException the repository exception
   */
  private Node getRootFolderNode(String identity, String type) throws RepositoryException {
    Node folderNode = null;
    ManageableRepository repository = repositoryService.getCurrentRepository();
    // TODO use the user session, not a system session
    SessionProvider sessionProvider = sessionProviders.getSystemSessionProvider(null);
    Session session = sessionProvider.getSession(repository.getConfiguration().getDefaultWorkspaceName(), repository);
    if (OWNER_TYPE_SPACE.equals(type)) {
      Node rootSpace = null;
      rootSpace = (Node) session.getItem(nodeCreator.getJcrPath(CMS_GROUPS_PATH) + "/spaces/" + identity);
      folderNode = rootSpace.getNode("Documents");
    } else {
      String privateRelativePath = nodeCreator.getJcrPath("userPrivate");
      Node userNode = null;
      try {
        userNode = nodeCreator.getUserNode(sessionProvider, identity);
      } catch (Exception e) {
        LOG.error("Cannot find user node by id: " + identity, e.getMessage());
        throw new RepositoryException("Cannot find user node by id: " + identity, e);
      }
      folderNode = userNode.getNode(privateRelativePath);
    }
    return folderNode;
  }

  /**
   * Creates the state.
   *
   * @param userId the user id
   * @return the conversation state
   */
  private ConversationState createState(String userId) {
    org.exoplatform.services.security.Identity userIdentity = userIdentity(userId);
    if (userIdentity != null) {
      ConversationState state = new ConversationState(userIdentity);
      // Keep subject as attribute in ConversationState.
      state.setAttribute(ConversationState.SUBJECT, userIdentity.getSubject());
      return state;
    }
    LOG.warn("User identity not found " + userId + " for setting conversation state");
    return null;
  }

  /**
   * Find or create user identity.
   *
   * @param userId the user id
   * @return the identity can be null if not found and cannot be created via
   *         current authenticator
   */
  protected org.exoplatform.services.security.Identity userIdentity(String userId) {
    org.exoplatform.services.security.Identity userIdentity = identityRegistry.getIdentity(userId);
    if (userIdentity == null) {
      // We create user identity by authenticator, but not register it in the registry
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("User identity not registered, trying to create it for: " + userId);
        }
        userIdentity = authenticator.createIdentity(userId);
      } catch (Exception e) {
        LOG.warn("Failed to create user identity: " + userId, e);
      }
    }
    return userIdentity;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    // XXX we need reference SpaceService after the container start only, otherwise the servr startup fails
    this.spaceService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(SpaceService.class);

    // For a case when calls was active and server stopped, then calls wasn't marked as Stopped and need
    // remove them.
    LOG.info("Web Conferencing service started.");
    try {
      int cleaned = deleteAllUserCalls();
      if (cleaned > 0) {
        LOG.info("Cleaned " + cleaned + " expired user calls.");
      }
    } catch (Throwable e) {
      LOG.warn("Error cleaning calls from previous server execution", e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    // nothing
  }

  /**
   * Checks if is space member.
   *
   * @param userName the user name
   * @param spacePrettyName the space pretty name
   * @return true, if is space member
   */
  protected boolean isSpaceMember(String userName, String spacePrettyName) {
    return getSpaceMembers(spacePrettyName).contains(userName);
  }

  /**
   * Gets the space members.
   *
   * @param spacePrettyName the space pretty name
   * @return the space members
   */
  protected Set<String> getSpaceMembers(String spacePrettyName) {
    Space space = spaceService.getSpaceByPrettyName(spacePrettyName);
    Set<String> spaceMembers = new HashSet<String>();
    for (String sm : space.getMembers()) {
      spaceMembers.add(sm);
    }
    return spaceMembers;
  }

  /**
   * Json to provider config.
   *
   * @param json the json
   * @return the call provider configuration
   * @throws JSONException the JSON exception
   */
  protected CallProviderConfiguration jsonToProviderConfig(JSONObject json) throws JSONException {
    CallProviderConfiguration conf = new CallProviderConfiguration();
    String type = json.getString("type");
    boolean active = json.getBoolean("active");
    conf.setActive(active);
    conf.setType(type);
    JSONArray jsonPermissions = json.optJSONArray("permissions");
    List<String> permissions = new ArrayList<>();
    if (jsonPermissions != null) {
      for (int i = 0; i < jsonPermissions.length(); i++) {
        permissions.add(jsonPermissions.getString(i));
      }
    }
    if (permissions.isEmpty()) {
      permissions.add(ALL_USERS);
    }
    conf.setPermissions(permissions);
    return conf;
  }

  /**
   * Gets the user IM accounts.
   *
   * @param profile the profile
   * @return the user I ms
   */
  protected List<IMInfo> getUserIMs(Profile profile) {
    List<IMInfo> activeIMs = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<Map<String, String>> ims = (List<Map<String, String>>) profile.getProperty(Profile.CONTACT_IMS);
    if (ims != null) {
      for (Map<String, String> m : ims) {
        String imType = m.get("key");
        String imId = m.get("value");
        if (imId != null && imId.length() > 0) {
          CallProvider provider = getProvider(imType);
          // Here we take in account that provider may change its supported types in runtime
          if (provider != null && provider.isActive() && provider.isSupportedType(imType)) {
            try {
              IMInfo im = provider.getIMInfo(imId);
              if (im != null) {
                activeIMs.add(im);
              } // otherwise provider doesn't have an IM type at all
            } catch (CallProviderException e) {
              LOG.warn(e.getMessage());
            }
          }
        }
      }
    }
    return activeIMs;
  }

  /**
   * Provider config to json.
   *
   * @param conf the conf
   * @return the JSON object
   * @throws JSONException the JSON exception
   */
  protected JSONObject providerConfigToJson(CallProviderConfiguration conf) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("type", conf.getType());
    json.put("active", conf.isActive());
    json.put("permissions", conf.getPermissions());
    return json;
  }

  /**
   * Read saved call provider configuration.
   *
   * @param type the type
   * @return the configuration
   */
  protected CallProviderConfiguration readProviderConfig(String type) {
    final String initialGlobalId = Scope.GLOBAL.getId();
    try {
      String safeType = URLEncoder.encode(type, "UTF-8");
      SettingValue<?> val = settingService.get(Context.GLOBAL, Scope.GLOBAL.id(PROVIDER_SCOPE_NAME), safeType);
      if (val != null) {
        String str = String.valueOf(val.getValue());
        if (str.startsWith("{")) {
          // Assuming it's JSON
          CallProviderConfiguration conf = jsonToProviderConfig(new JSONObject(str));
          return conf;
        } else {
          LOG.warn("Cannot parse saved CallProviderConfiguration: " + str);
        }
      }
    } catch (UnsupportedEncodingException e) {
      LOG.warn("UTF8 encoding required to read provider config", e);
    } catch (JSONException e) {
      LOG.warn("Error reading provider config", e);
    } finally {
      Scope.GLOBAL.id(initialGlobalId);
    }
    return null;
  }

  /**
   * Current user id.
   *
   * @return the string
   */
  protected String currentUserId() {
    ConversationState contextState = ConversationState.getCurrent();
    if (contextState != null) {
      return contextState.getIdentity().getUserId();
    }
    return null; // IdentityConstants.ANONIM
  }

  /**
   * Notify user call state changed.
   *
   * @param call the call
   * @param initiatorId the initiator id
   * @param state the state
   */
  protected void notifyUserCallStateChanged(CallInfo call, String initiatorId, String state) {
    for (UserInfo part : call.getParticipants()) {
      if (UserInfo.TYPE_NAME.equals(part.getType()) || GuestInfo.TYPE_NAME.equals(part.getType())) {
        // We notify to other part, and in case of deletion including to one who may caused the update
        // for a case if several user clients listening.
        if (initiatorId == null || !initiatorId.equals(part.getId()) || CallState.STOPPED.equals(state)) {
          fireUserCallStateChanged(part.getId(),
                                   call.getId(),
                                   call.getProviderType(),
                                   state,
                                   call.getOwner().getId(),
                                   call.getOwner().getType());
        }
      }
    }
  }

  // ******* Call storage ******

  /**
   * Read call entity.
   *
   * @param savedCall the saved call
   * @param withParticipants the with participants
   * @return the call info
   * @throws CallSettingsException if call entry has wrong settings (Chat room call
   *           title too long or has bad value)
   * @throws IdentityStateException if error reading call owner or participant
   * @throws CallOwnerException if call owner is of wrong type or cannot be found (as an identity)
   */
  protected CallInfo readCallEntity(CallEntity savedCall, boolean withParticipants) throws CallSettingsException,
                                                                                    IdentityStateException,
                                                                                    CallOwnerException {
    if (savedCall != null) {
      String callId = savedCall.getId();
      IdentityInfo owner;
      String ownerId = savedCall.getOwnerId();
      List<ParticipantEntity> savedParticipants = null;
      if (OWNER_TYPE_CHATROOM.equals(savedCall.getOwnerType())) {
        String settings = savedCall.getSettings(); // we expect JSON here
        try {
          JSONObject json = new JSONObject(settings);
          String roomTitle = json.optString("roomTitle");
          if (roomTitle != null && roomTitle.length() > 0) {
            // XXX In case of chat room we read participants storage ahead (see below code on withParticipants=true)
            // This could be improved by reviewing and separating logic of participants/origins
            savedParticipants = participantsStorage.findCallParts(callId);
            // Filter only room members without guests which aren't actual room members here
            String[] members = savedParticipants.stream()
                                                  .filter(p -> !GuestInfo.TYPE_NAME.equals(p.getType()))
                                                  .map(p -> p.getId())
                                                  .toArray(String[]::new);
            owner = roomInfo(ownerId, roomTitle, members, callId);
          } else {
            LOG.warn("Saved call doesn't have room settings: '" + settings + "'");
            throw new CallSettingsException("Saved call doesn't have room settings");
          }
        } catch (JSONException e) {
          LOG.warn("Saved call has wrong room settings format (bad JSON syntax): '" + settings + "'", e);
          throw new CallSettingsException("Saved call has wrong room settings format", e);
        }
      } else if (OWNER_TYPE_SPACEEVENT.equals(savedCall.getOwnerType())) {
        // XXX We work with origins only for space events for the moment, but the origins would be useful for all types of calls.
        String[] eventParticipants = originsStorage.findCallOrigins(callId, OWNER_TYPE_USER)
            .stream()
            .map(p -> p.getId())
            .toArray(String[]::new);
        String[] eventSpaces = originsStorage.findCallOrigins(callId, OWNER_TYPE_SPACE)
            .stream()
            .map(p -> p.getId())
            .toArray(String[]::new);
        owner = spaceEventInfo(ownerId, callId, eventParticipants, eventSpaces);
      } else if (OWNER_TYPE_SPACE.equals(savedCall.getOwnerType())) {
        owner = spaceInfo(ownerId, callId);
      } else if (OWNER_TYPE_USER.equals(savedCall.getOwnerType())) {
        owner = userInfo(ownerId);
      } else {
        throw new CallOwnerException("Unexpected call owner type: " + savedCall.getOwnerType() + " for " + ownerId);
      }
      if (owner == null) {
        throw new CallOwnerException("Call owner cannot be found: " + ownerId);
      }

      CallInfo call = new CallInfo(callId, savedCall.getTitle(), owner, savedCall.getProviderType());
      call.setState(savedCall.getState());
      call.setLastDate(savedCall.getLastDate());
      call.setStartDate(savedCall.getStartDate());
      call.setEndDate(savedCall.getEndDate());
      try {
        String inviteId = getInviteId(callId);
        if (inviteId != null) {
          call.setInviteId(inviteId);
        } else {
          // This should not happen in normal circumstances as invite will be
          // created within the call or on call start, 
          // see also txCreateCall(), txUpdateCallAndParticipants()
          if (CallState.STARTED.equals(call.getState())) {
            LOG.warn("Cannot find inviteId for started call {}", callId);
          } else if (LOG.isDebugEnabled()) {
            LOG.debug("An inviteId not found for call {}", callId);
          }
        }
      } catch (StorageException e) {
        LOG.warn("Cannot get inviteId for call {} : {}", callId, e.getMessage());
      }

      if (withParticipants) {
        if (savedParticipants == null) {
          savedParticipants = participantsStorage.findCallParts(callId);
        } // otherwise reuse room's participants
        // 1) read actually added participants for call already or being running with their current states from DB
        // this way we add the ones who exist also in the origins with their actual state (e.g. JOINED for those who are already in the call).
        for (ParticipantEntity p : savedParticipants) {
          if (UserInfo.TYPE_NAME.equals(p.getType()) || GuestInfo.TYPE_NAME.equals(p.getType())) {
            UserInfo user = userInfo(p.getId());
            if (user == null) {
              // external guest or undefined participant
              user = GuestInfo.TYPE_NAME.equals(p.getType()) ? new GuestInfo(p.getId())
                                                             : new ParticipantInfo(savedCall.getProviderType(), p.getId());
            } else if (GuestInfo.TYPE_NAME.equals(user.getType())) {
              // eXo user as guest
              user = new GuestInfo(user);
            }
            user.setState(p.getState());
            user.setClientId(p.getClientId());
            call.addParticipant(user);
          } else {
            LOG.warn("Non user participant skipped for call " + savedCall.getId() + ": " + p.getId() + " (" + p.getType() + ")");
          }
        }
        // 2) resolve allowed participants from call origins (actual for space event calls)
        if (savedCall.isGroup()) {
          // Resolve actual participants from the owner members with LEAVED state (members may come from the origins above)
          Set<UserInfo> members = new HashSet<>(GroupInfo.class.cast(owner).getMembers().values());
          members.stream().forEach(m -> m.setState(UserState.LEAVED));
          call.addParticipants(members);
        } // Otherwise it's 1-1 call and its participants already added in createCall() if start parameter was set to true
      }
      return call;
    } else {
      return null;
    }
  }

  /**
   * Gets the invite id.
   *
   * @param callId the call id
   * @return the invite id
   * @throws StorageException the storage exception
   */
  protected String getInviteId(String callId) throws StorageException {
    try {
      List<InviteEntity> invites = inviteStorage.findCallInvites(callId);
      if (!invites.isEmpty()) {
        // Assume inviationId is the same for all invites in this call.
        return invites.get(0).getInvitationId();
      }
      return null;
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error getting invite Id for call " + callId, e);
    }
  }

  /**
   * Create invite with given identity, type and ID.
   *
   * @param callId the call ID
   * @param identity the identity
   * @param type the type
   * @param inviteId the invite ID
   * @return the invite ID
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  private void createInvite(String callId, String identity, String type, String inviteId) throws IllegalArgumentException,
                                                             IllegalStateException,
                                                             PersistenceException {
    inviteStorage.create(new InviteEntity(callId, identity, type, inviteId));
  }
  
  /**
   * Creates a new invite ID (of group type for all users) for the call.
   *
   * @param callId the call id
   * @return the string with new invite ID
   */
  private String createInvite(String callId) {
    String inviteId = RandomStringUtils.randomAlphabetic(12);
    createInvite(callId, ALL_USERS, GROUP, inviteId);
    return inviteId;
  }

  /**
   * Creates the call entity.
   *
   * @param call the call
   * @return the call entity
   * @throws CallSettingsException if call entry has wrong settings (chat room title)
   */
  protected CallEntity createCallEntity(CallInfo call) throws CallSettingsException {
    CallEntity entity = new CallEntity();
    // set ID (PK in DB) only for a new call entity
    entity.setId(call.getId());
    syncCallEntity(call, entity);
    return entity;
  }

  /**
   * Update call entity from given CallInfo instance.
   *
   * @param call the call
   * @param entity the entity
   * @throws CallSettingsException if call entry has wrong settings (room title)
   */
  protected void syncCallEntity(CallInfo call, CallEntity entity) throws CallSettingsException {
    if (call != null) {
      entity.setProviderType(call.getProviderType());
      entity.setTitle(call.getTitle());
      IdentityInfo owner = call.getOwner();
      entity.setOwnerId(owner.getId());
      entity.setOwnerType(owner.getType());
      entity.setState(call.getState());
      entity.setLastDate(call.getLastDate());
      entity.setStartDate(call.getStartDate());
      entity.setEndDate(call.getEndDate());

      if (OWNER_TYPE_CHATROOM.equals(owner.getType())) {
        entity.setIsGroup(true);
        entity.setIsUser(false);
        RoomInfo room = RoomInfo.class.cast(owner);
        try {
          JSONObject json = new JSONObject();
          json.put("roomTitle", room.getTitle());
          String settings = json.toString();
          if (isValidData(settings)) {
            entity.setSettings(settings);
          } else {
            LOG.warn("Call settings too long: '" + settings + "'. Max value is " + DATA_MAX_LENGTH + " bytes in UTF8 encoding.");
            throw new CallSettingsException("Call settings too long (room title)");
          }
        } catch (UnsupportedEncodingException e) {
          throw new CallSettingsException("Cannot save call settings (UTF8 encoding required)", e);
        } catch (JSONException e) {
          throw new CallSettingsException("Cannot save call settings (title should be a text)", e);
        }
      } else if (OWNER_TYPE_SPACEEVENT.equals(owner.getType())) {
        entity.setIsGroup(true);
        entity.setIsUser(false);
      } else if (OWNER_TYPE_SPACE.equals(owner.getType())) {
        entity.setIsGroup(true);
        entity.setIsUser(false);
      } else {
        entity.setIsGroup(false);
        entity.setIsUser(true);
      }
    } else {
      throw new NullPointerException("Call info is null");
    }
  }

  /**
   * Creates the participant entity.
   *
   * @param callId the call id
   * @param user the user
   * @return the participant entity
   */
  protected ParticipantEntity createParticipantEntity(String callId, UserInfo user) {
    ParticipantEntity part = new ParticipantEntity();
    part.setId(user.getId());
    part.setCallId(callId);
    part.setType(user.getType());
    part.setState(user.getState());
    part.setClientId(user.getClientId());
    return part;
  }
  
  /**
   * Creates the origin entity.
   *
   * @param callId the call id
   * @param origin the origin
   * @return the origin entity
   */
  protected OriginEntity createOriginEntity(String callId, OriginInfo origin) {
    OriginEntity orig = new OriginEntity();
    orig.setId(origin.getId());
    orig.setCallId(callId);
    orig.setType(origin.getType());
    //orig.setState(origin.getState()); // TODO we don't work with origin's state currently
    return orig;
  }

  /**
   * Find cause exception of given type.
   *
   * @param <C> the generic type
   * @param pe the pe
   * @param causeClass the cause class
   * @return the c
   */
  protected <C extends SQLException> C exceptionCause(PersistenceException pe, Class<C> causeClass) {
    Throwable e = pe;
    do {
      if (e != null && causeClass.isAssignableFrom(e.getClass())) {
        return causeClass.cast(e);
      } else {
        Throwable c = e.getCause();
        e = c != null && c != e ? c : null;
      }
    } while (e != null);

    return null;
  }

  // >>>>>>> Call storage: ExoTransactional managed

  /**
   * Creates the call in a single transaction.
   *
   * @param call the call
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException if persistence exception happen
   * @throws CallSettingsException if call entry has wrong settings (chat room title)
   * @see ExoTransactional
   */
  @ExoTransactional
  protected void txCreateCall(CallInfo call) throws IllegalArgumentException,
                                             IllegalStateException,
                                             PersistenceException,
                                             CallSettingsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txCreateCall: " + call.getId());
    }
    callStorage.create(createCallEntity(call));
    String callId = call.getId();
    if (call.getOwner().isGroup()) {
      // For starting (on creation) group call we need sync (and save) its
      // members and parties immediately
      if (CallState.STARTED.equals(call.getState())) {
        txSyncMembersAndParticipants(call);
      }
    } else {
      // For 1-1 call we save its parties from the beginning
      for (UserInfo p : call.getParticipants()) {
        participantsStorage.create(createParticipantEntity(callId, p));
      }
    }
    for (OriginInfo o : call.getOrigins()) {
      originsStorage.create(createOriginEntity(callId, o));
    }
    call.setInviteId(createInvite(callId));
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txCreateCall: " + call.getId());
    }
  }

  /**
   * Tx add participant.
   *
   * @param callId the call id
   * @param participant the participant
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected void txAddParticipant(String callId, UserInfo participant) throws IllegalArgumentException,
                                                                       IllegalStateException,
                                                                       PersistenceException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txAddParticipant: " + participant.getId() + "@" + callId);
    }
    addParticipant(callId, participant);
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txAddParticipant: " + participant.getId() + "@" + callId);
    }
  }
  
  private void addParticipant(String callId, UserInfo participant) throws IllegalArgumentException,
                                                                   IllegalStateException,
                                                                   PersistenceException {
    if (participantsStorage.find(new ParticipantId(participant.getId(), callId)) == null) {
      participantsStorage.create(createParticipantEntity(callId, participant));
    } else {
      LOG.warn("Cannot add participant with id {} for call {}. Participant already exists", participant.getId(), callId);
    }
  }

  /**
   * Save call. For use in {@link #updateCall(CallInfo)} and
   * {@link #updateCallAndParticipant(CallInfo, UserInfo)}.
   *
   * @param call the call
   * @throws CallNotFoundException if call not found
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws CallSettingsException if call entry has wrong settings (room call title)
   */
  private void saveCall(CallInfo call) throws CallNotFoundException,
                                       IllegalArgumentException,
                                       IllegalStateException,
                                       PersistenceException,
                                       CallSettingsException {
    CallEntity entity = callStorage.find(call.getId());
    if (entity != null) {
      syncCallEntity(call, entity);
      callStorage.update(entity);
    } else {
      throw new CallNotFoundException("Call not found: " + call.getId());
    }
  }
  
  /**
   * Tx stop call.
   *
   * @param call the call
   * @param remove the remove
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected void txStopCall(CallInfo call, boolean remove) throws IllegalArgumentException, IllegalStateException, PersistenceException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txStopCall: " + call.getId());
    }
    // Delete or update in single tx
    call.setState(CallState.STOPPED);
    if (remove) {
      // Delete the call with all its parties/origins/invitations
      txDeleteCall(call.getId());
      // Call removal cancels all invitation - reflect this 
      call.setInviteId(null);
    } else {
      // we don't update each participant with LEAVED state here as the call state already shows this,
      // see also startCall()
      try {
        saveCall(call);
        // Remove all invitations and existing guests at the end of call stop
        removeInvites(call.getId());
        call.setInviteId(null);
        removeGuests(call);
      } catch (CallNotFoundException | CallSettingsException e) {
        LOG.warn("Failed to save stopped call: " + call.getId(), e);
      }
    }
    call.setInviteId(null);
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txStopCall: " + call.getId());
    }
  }

  /**
   * Update existing call (mark it started, stopped etc) in a single transaction.
   *
   * @param call the call
   * @throws CallNotFoundException if call not found in storage
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws CallSettingsException if call entry has wrong settings (room call title)
   */
  @ExoTransactional
  protected void txUpdateCall(CallInfo call) throws CallNotFoundException,
                                             IllegalArgumentException,
                                             IllegalStateException,
                                             PersistenceException,
                                             CallSettingsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateCall: " + call.getId());
    }
    saveCall(call);
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateCall: " + call.getId());
    }
  }

  /**
   * Tx update invites.
   *
   * @param callId the id
   * @param identities the identities
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected void txUpdateInvites(String callId, List<InvitedIdentity> identities) throws IllegalArgumentException,
                                                                                  IllegalStateException,
                                                                                  PersistenceException {
    // INFO This method doesn't used as for Jan of 2021.
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateInvites: " + callId);
    }
    identities = identities.stream().distinct().collect(Collectors.toList());
    List<InviteEntity> invites = inviteStorage.findCallInvites(callId);
    String inviteId = !invites.isEmpty() ? invites.get(0).getInvitationId() : RandomStringUtils.randomAlphabetic(12);
    for (InviteEntity invite : invites) {
      inviteStorage.delete(invite);
    }
    for (InvitedIdentity identity : identities) {
      // TODO: add check GROUP/USER in org service
      createInvite(callId, identity.getIdentity(), identity.getType(), inviteId);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateInvites: " + callId);
    }
  }

  /**
   * Save participant, for use in {@link #updateParticipant(String, UserInfo)} and
   * {@link #updateCallAndParticipant(CallInfo, UserInfo)}.
   *
   * @param callId the call id
   * @param user the participant user
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws ParticipantNotFoundException if participant not found in storage
   */
  private void saveParticipant(String callId, UserInfo user) throws IllegalArgumentException,
                                                                    IllegalStateException,
                                                                    PersistenceException,
                                                                    ParticipantNotFoundException {
    // Update participant
    ParticipantEntity part = participantsStorage.find(new ParticipantId(user.getId(), callId));
    if (part != null) {
      part.setState(user.getState());
      part.setClientId(user.getClientId());
      participantsStorage.update(part);
    } else {
      throw new ParticipantNotFoundException("Call participant " + user.getId() + " not found for " + callId);
    }
  }

  /**
   * Update call participant (for joined or leaved state) in a single transaction.
   *
   * @param callId the call id
   * @param participant the participant
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws ParticipantNotFoundException if call participant not found in storage
   */
  @ExoTransactional
  protected void txUpdateParticipant(String callId, UserInfo participant) throws IllegalArgumentException,
                                                                          IllegalStateException,
                                                                          PersistenceException,
                                                                          ParticipantNotFoundException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateParticipant: " + participant.getId() + "@" + callId);
    }
    saveParticipant(callId, participant);
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateParticipant: " + participant.getId() + "@" + callId);
    }
  }
  
  /**
   * Sync group members and participants.
   *
   * @param call the call
   */
  @ExoTransactional
  protected void txSyncMembersAndParticipants(CallInfo call) {
    if (call.getOwner().isGroup()) {
      // When call starts we need the following (should be similar to startCall()): 
      // resolve origins (parts, owner's members and groups/spaces) to actual
      // participants - read the origins and use them all as call's new
      // participants (replace all existing in the DB).
      if (LOG.isDebugEnabled()) {
        LOG.debug(">> txSyncMembersAndParticipants: " + call.getId());
      }
      // Take a snapshot of current members of the group (an event call should include all related users already).
      Set<UserInfo> members = new LinkedHashSet<>(GroupInfo.class.cast(call.getOwner()).getMembers().values());
      Set<UserInfo> participants = call.getParticipants();
      // 1) Ensure participants contain only members (excluding existing external guests)
      Set<UserInfo> deleteParties = new LinkedHashSet<>(participants); // we need a copy of the set as it will be modified below
      // Filter current parties (remove users already in group members), to remove not members next
      deleteParties.removeAll(members);
      for (UserInfo p : deleteParties) {
        if (!GuestInfo.TYPE_NAME.equals(p.getType())) {
          // Remove this part as it's not a member of the group
          ParticipantEntity entity = participantsStorage.find(new ParticipantId(p.getId(), call.getId()));
          if (entity != null) {
            participantsStorage.delete(entity);
          }          
          call.removeParticipant(p);
        } // Otherwise, it's a guest - should be removed explicitly
      }
      // 2) Ensure all members are participants as well
      // Filter current members (remove ones not already in parties), to add members not yet participating the call
      members.removeAll(participants);
      for (UserInfo m : members) {
        // save only not already existing
        ParticipantEntity entity = participantsStorage.find(new ParticipantId(m.getId(), call.getId()));
        if (entity == null) {
          participantsStorage.create(createParticipantEntity(call.getId(), m));
        }
        call.addParticipant(m); // add also to the actual parties
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("<< txSyncMembersAndParticipants: " + call.getId());
      }
    }
  }

  /**
   * Remove all call guests.
   *
   * @param call the call
   */
  private void removeGuests(CallInfo call) {
    List<ParticipantEntity> participants = participantsStorage.findCallParts(call.getId());
    participants.stream().filter(part -> part.getType().equals(GuestInfo.TYPE_NAME)).forEach(g -> {
      participantsStorage.delete(g);
      call.removeParticipant(new GuestInfo(g.getId()));
    });
  }
  
  /**
   * Remove call participant.
   *
   * @param callId the call id
   * @param partId the participant id
   * @throws ParticipantNotFoundException if participant not found
   */
  @ExoTransactional
  protected void txRemoveParticipant(String callId, String partId) throws ParticipantNotFoundException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txRemoveParticipant: " + partId + "@" + callId);
    }
    ParticipantEntity entity = participantsStorage.find(new ParticipantId(partId, callId));
    if (entity != null) {
      participantsStorage.delete(entity);
    } else {
      throw new ParticipantNotFoundException("Call participant " + partId + " not found for " + callId);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txRemoveParticipant: " + partId + "@" + callId);
    }
  }

  /**
   * Removes the invites in DB.
   *
   * @param callId the call id
   */
  private void removeInvites(String callId) {
    List<InviteEntity> savedInvites = inviteStorage.findCallInvites(callId);
    for (InviteEntity i : savedInvites) {
      inviteStorage.delete(i);
    }
  }

  /**
   * Update call and all its participants in a single transaction.
   *
   * @param call the call
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws CallNotFoundException if call or its participants not found in storage
   * @throws CallSettingsException if call entry has wrong settings (room call title)
   * @throws ParticipantNotFoundException if call participant not found in storage
   */
  @ExoTransactional
  protected void txUpdateCallAndParticipants(CallInfo call) throws IllegalArgumentException,
                                                            IllegalStateException,
                                                            PersistenceException,
                                                            CallNotFoundException,
                                                            CallSettingsException,
                                                            ParticipantNotFoundException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateCallAndParticipants: " + call.getId());
    }
    // Update call
    saveCall(call);
    if (CallState.STARTED.equals(call.getState()) && call.getInviteId() == null) {
      // If it's started call - generate a new invite ID
      call.setInviteId(createInvite(call.getId()));
    }
    // Update participants
    String callId = call.getId();
    for (UserInfo p : call.getParticipants()) {
      try {
        saveParticipant(callId, p);
      } catch(ParticipantNotFoundException e) {
        // Check if this participant not from group's origins
        if (call.getOwner().isGroup() && GroupInfo.class.cast(call.getOwner()).getMembers().keySet().contains(p.getId())) {
          addParticipant(callId, p);
        } else {
          throw new ParticipantNotFoundException("Cannot update the call with not allowed participant: " + p.getId() 
                                                 + ", call: " + callId, e);
        }
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateCallAndParticipants: " + call.getId());
    }
  }

  /**
   * Tx update call and its origins.
   *
   * @param call the call
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   * @throws CallNotFoundException the call not found exception
   * @throws CallSettingsException the call settings exception
   * @throws ParticipantNotFoundException the participant not found exception
   */
  @ExoTransactional
  protected void txUpdateCallAndOrigins(CallInfo call) throws IllegalArgumentException,
                                                       IllegalStateException,
                                                       PersistenceException,
                                                       CallNotFoundException,
                                                       CallSettingsException,
                                                       ParticipantNotFoundException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateCallAndOrigins: " + call.getId());
    }
    // Update call
    saveCall(call);
    String callId = call.getId();
    // Update all origins of the call
    // Instead of deleting and adding the all, we figure out what actually
    // to add/remove to deal better with DB transactions and JPA caches state.
    List<OriginEntity> savedOrigins = Stream.concat(originsStorage.findCallOrigins(callId, OWNER_TYPE_USER).stream(),
                                                    originsStorage.findCallOrigins(callId, OWNER_TYPE_SPACE).stream())
                                            .collect(Collectors.toCollection(ArrayList::new));
    // Add newly added origins
    next: for (OriginInfo o : call.getOrigins()) {
      for (Iterator<OriginEntity> oiter = savedOrigins.iterator(); oiter.hasNext();) {
        OriginEntity oe = oiter.next();
        if (oe.getType().equals(o.getType()) && oe.getId().equals(o.getId())) {
          // This origin already exists - do nothing but remove from already
          // saved (see removal below)
          oiter.remove();
          continue next;
        }
      }
      originsStorage.create(createOriginEntity(callId, o));
    }
    // Remove not actual origins
    for (OriginEntity oe : savedOrigins) {
      originsStorage.delete(oe);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateCallAndOrigins: " + call.getId());
    }
  }

  /**
   * Delete call within a transaction.
   *
   * @param id the call id
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected void txDeleteCall(String id) throws IllegalArgumentException, IllegalStateException, PersistenceException {
    CallEntity entity = callStorage.find(id);
    if (entity != null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(">> txDeleteCall: " + id);
      }
      callStorage.delete(entity);
      // Cancel all invitations on the call removal
      // NOTE: already existing guests will be removed by the DB's FK cascade 
      removeInvites(id);
      if (LOG.isDebugEnabled()) {
        LOG.debug("<< txDeleteCall: " + id);
      }
    }
  }

  /**
   * Tx update participants.
   *
   * @param call the call
   * @param participants the participants
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected void txUpdateParticipants(CallInfo call, List<UserInfo> participants) throws IllegalArgumentException,
                                                                                  IllegalStateException,
                                                                                  PersistenceException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txUpdateParticipants: " + call.getId());
    }
    if (participants != null && !participants.isEmpty()) {
      // Instead of deleting and adding the all, we figure out whom actually 
      // to add/remove to deal better with DB transactions from other requests (e.g. from client via CometD/REST).
      Set<String> partIds = participants.stream().map(UserInfo::getId).collect(Collectors.toSet());
      List<ParticipantEntity> savedParts = participantsStorage.findCallParts(call.getId());
      Set<String> savedPartIds = savedParts.stream().map(ParticipantEntity::getId).collect(Collectors.toSet());
      for (ParticipantEntity p : savedParts) {
        if (!partIds.contains(p.getId())) {
          participantsStorage.delete(p);
          savedPartIds.remove(p.getId());
        }
      }
      for (UserInfo p : participants) {
        if (!savedPartIds.contains(p.getId())) {
          participantsStorage.create(createParticipantEntity(call.getId(), p));
        }
        call.addParticipant(p); // just add a party as this call instance has not parts fetched from the DB (see caller method)
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txUpdateParticipants: " + call.getId());
    }
  }

  /**
   * Delete all user calls (not group ones) within a single transaction.
   *
   * @return number of deleted calls
   * @throws IllegalArgumentException the illegal argument exception
   * @throws IllegalStateException the illegal state exception
   * @throws PersistenceException the persistence exception
   */
  @ExoTransactional
  protected int txDeleteAllUserCalls() throws IllegalArgumentException, IllegalStateException, PersistenceException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(">> txDeleteAllUserCalls");
    }
    int r = callStorage.deleteAllUsersCalls();
    if (LOG.isDebugEnabled()) {
      LOG.debug("<< txDeleteAllUserCalls: " + r);
    }
    return r;
  }
  
  
  /**
   * Clear JPA storage from stale/cached objects.
   *
   */
  @Deprecated
  protected void clearStorage() {
    //Clear storage session:
    // XXX It is REQUIRED stuff (actual when something were deleted above), otherwise
    // ExoTx/Hibernate will fail to enter into the transaction in createCall() with an exception:
    // javax.persistence.EntityExistsException: a different object with the same identifier value
    // was already associated with the session, e.g.:
    // [org.exoplatform.webconferencing.domain.ParticipantEntity#org.exoplatform.webconferencing.domain.ParticipantId@4aa63de3]
    try {
      //originsStorage.clear();
      //participantsStorage.clear();
      //inviteStorage.clear();
      callStorage.clear(); // it's single entity manager for all DAOs - clean it once 
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      LOG.warn("Call storage cleanup failed", e);
    }
  }

  /**
   * Delete call.
   *
   * @param id the id
   * @throws StorageException if storage error happens
   */
  protected void deleteCall(String id) throws StorageException {
    try {
      txDeleteCall(id);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error deleting call " + id, e);
    }
  }

  /**
   * Delete all user calls. This will not touch any group call. For use on server start to cleanup the
   * storage.
   *
   * @return number of deleted calls
   * @throws StorageException if storage error happens
   * 
   */
  protected int deleteAllUserCalls() throws StorageException {
    try {
      return txDeleteAllUserCalls();
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error deleting all user calls", e);
    }
  }

  /**
   * Update call and all its participants.
   *
   * @param call the call
   * @throws StorageException if storage exception happen
   * @throws ParticipantNotFoundException if call or its participants not found in storage
   * @throws CallSettingsException if call entry has wrong settings (room call title)
   * @throws CallNotFoundException if call not found in storage
   */
  protected void updateCallAndParticipants(CallInfo call) throws StorageException,
                                                          ParticipantNotFoundException,
                                                          CallSettingsException,
                                                          CallNotFoundException {
    try {
      txUpdateCallAndParticipants(call);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error updating call and participants: " + call.getId(), e);
    }
  }
  
  /**
   * Update call and its origins.
   *
   * @param call the call
   * @throws StorageException the storage exception
   * @throws ParticipantNotFoundException the participant not found exception
   * @throws CallSettingsException the call settings exception
   * @throws CallNotFoundException the call not found exception
   */
  protected void updateCallAndOrigins(CallInfo call) throws StorageException,
                                                          ParticipantNotFoundException,
                                                          CallSettingsException,
                                                          CallNotFoundException {
    try {
      txUpdateCallAndOrigins(call);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error updating call and origins: " + call.getId(), e);
    }
  }

  /**
   * Sync group members and participants.
   *
   * @param call the call
   * @throws StorageException the storage exception
   */
  protected void syncMembersAndParticipants(CallInfo call) throws StorageException {
    try {
      txSyncMembersAndParticipants(call);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error sync call members and participants: " + call.getId(), e);
    }
  }

  /**
   * Update call participant (for joined or leaved state) in a single transaction.
   *
   * @param callId the call id
   * @param participant the participant
   * @throws ParticipantNotFoundException if call participant not found in storage
   * @throws StorageException if storage exception happen
   */
  protected void updateParticipant(String callId, UserInfo participant) throws ParticipantNotFoundException, StorageException {
    try {
      txUpdateParticipant(callId, participant);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error updating participant " + participant.getId() + " of call " + callId, e);
    }
  }
  
  /**
   * Remove call participant (for leaved state) in a single transaction.
   *
   * @param callId the call id
   * @param participant the participant
   * @throws ParticipantNotFoundException if call participant not found in storage
   * @throws StorageException if storage exception happen
   */
  protected void removeParticipant(String callId, UserInfo participant) throws ParticipantNotFoundException, StorageException {
    try {
      txRemoveParticipant(callId, participant.getId());
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error removing participant " + participant.getId() + " from call " + callId, e);
    }
  }

  /**
   * Update existing call (mark it started, stopped etc).
   *
   * @param call the call
   * @throws CallNotFoundException if call not found in storage
   * @throws CallSettingsException if call entry has wrong settings (room call title)
   * @throws StorageException if storage exception happen
   */
  protected void updateCall(CallInfo call) throws CallNotFoundException, CallSettingsException, StorageException {
    // TODO this method not used currently but may be useful in future if need
    // update only a call entity (w/o its participants, origins or invites)
    try {
      txUpdateCall(call);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error updating call " + call.getId(), e);
    }
  }

  /**
   * Find call in storage by its ID.
   *
   * @param id the id
   * @param withParticipants also read participants from the DB
   * @return the call info
   * @throws IdentityStateException if error reading call owner or participant
   * @throws StorageException if persistent error happens
   * @throws CallSettingsException if call entry has wrong settings (Chat room call
   *           title too long or has bad value)
   * @throws CallOwnerException if call owner type of unknown type
   */
  protected CallInfo findCallById(String id, boolean withParticipants) throws IdentityStateException,
                                             StorageException,
                                             CallSettingsException,
                                             CallOwnerException {
    try {
      CallEntity savedCall = callStorage.find(id);
      return readCallEntity(savedCall, withParticipants);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading call " + id, e);
    }
  }

  /**
   * Find a call, owned by group with given ID, in calls storage.
   *
   * @param ownerId the owner id
   * @return the string or <code>null</code> if no call found
   * @throws StorageException the storage exception
   */
  protected String findGroupCallId(String ownerId) throws StorageException {
    // TODO it's not efficient read the whole entity when we need only an ID (or null)
    try {
      CallEntity savedCall = callStorage.findGroupCallByOwnerId(ownerId);
      if (savedCall != null) {
        return savedCall.getId();
      }
      return null;
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading owner group call ID by " + ownerId, e);
    }
  }
  
  /**
   * Find a call, owned by a space with given ID, in calls storage.
   *
   * @param spaceId the owner id
   * @return the string or <code>null</code> if no call found
   * @throws StorageException the storage exception
   */
  protected String findSpaceCallId(String spaceId) throws StorageException {
    // TODO it's not efficient read the whole entity when we need only an ID (or null)
    try {
      CallEntity savedCall = callStorage.findGroupCallByOwnerTypeId(spaceId, OWNER_TYPE_SPACE);
      if (savedCall != null) {
        return savedCall.getId();
      }
      return null;
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading space call ID by " + spaceId, e);
    }
  }
  
  /**
   * Find a call, owned by a space with given ID, in calls storage.
   *
   * @param roomId the room id
   * @return the string or <code>null</code> if no call found
   * @throws StorageException the storage exception
   */
  protected String findChatRoomCallId(String roomId) throws StorageException {
    // TODO it's not efficient read the whole entity when we need only an ID (or null)
    try {
      CallEntity savedCall = callStorage.findGroupCallByOwnerTypeId(roomId, OWNER_TYPE_CHATROOM);
      if (savedCall != null) {
        return savedCall.getId();
      }
      return null;
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading chat room call ID by " + roomId, e);
    }
  }
  
  /**
   * Find last space event call id.
   *
   * @param ownerId the owner id
   * @return the string
   * @throws StorageException the storage exception
   */
  protected String findLastSpaceEventCallId(String ownerId) throws StorageException {
    // TODO it's not efficient read the whole entity when we need only an ID (or null)
    try {
      CallEntity savedCall = callStorage.findGroupCallByOwnerTypeId(ownerId, OWNER_TYPE_SPACEEVENT);
      if (savedCall != null) {
        return savedCall.getId();
      }
      return null;
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading space event call ID by " + ownerId, e);
    }
  }

  /**
   * Find user group calls in calls storage.
   *
   * @param userId the user id
   * @return the collection
   * @throws StorageException if persistence error happen
   */
  protected Collection<CallInfo> findUserGroupCalls(String userId) throws StorageException {
    try {
      List<CallEntity> savedCalls = callStorage.findUserGroupCalls(userId);
      List<CallInfo> calls = new ArrayList<>();
      for (CallEntity c : savedCalls) {
        try {
          calls.add(readCallEntity(c, false));
        } catch (CallInfoException | IdentityStateException e) {
          // In this context we can skip erroneous calls abd let user to know only about valid ones
          // IdentityStateException if error reading call participant
          // CallInfoException if call has wrong settings (type or room settings)
          LOG.warn("Error reading user group call: " + c.getId(), e);
        }
      }
      return Collections.unmodifiableCollection(calls);
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      throw new StorageException("Error reading user group calls by " + userId, e);
    }
  }

  /**
   * Create the call in storage with all its participants. If such call (by ID) already exists a
   * {@link CallConflictException} will be raised.
   *
   * @param call the call info
   * @throws StorageException if storage exception happen
   * @throws CallConflictException the call conflicts with another (same ID) call
   * @throws CallSettingsException the call settings wrong (chat room title)
   */
  protected void createCall(CallInfo call) throws StorageException, CallConflictException, CallSettingsException {
    try {
      // Persist the call with all its participants
      txCreateCall(call);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new StorageException("Error creating call " + call.getId(), e);
    } catch (PersistenceException pe) {
      // Check if it's not already existing call, inform user accordingly
      SQLIntegrityConstraintViolationException constEx = exceptionCause(pe, SQLIntegrityConstraintViolationException.class);
      if (constEx != null && constEx.getMessage().indexOf("PK_WBC_CALLID") >= 0) {
        CallEntity conflictedCallEntity = callStorage.find(call.getId());
        if (conflictedCallEntity != null) {
          // We can fail from here or return this already created, in second case we may return not
          // exactly what was originally requested (by data and participants).
          // Taking in account a check for existence in invalidateCall(), that should be used before this
          // method, we raise an error to the caller with details.
          if (CallState.STARTED.equals(call.getState())) {
            for (UserInfo savedPart : call.getParticipants()) {
              Set<UserCallListener> ulisteners = userListeners.get(savedPart.getId());
              if (ulisteners != null) {
                for (UserCallListener ul : ulisteners) {
                  if (savedPart.hasSameClientId(ul.getClientId())) {
                    // this part already joined and runs in the call
                    if (LOG.isDebugEnabled()) {
                      LOG.debug("Call already started and running: " + call.getId(), pe);
                    }
                    throw new CallConflictException("Call already started and running");
                  }
                }
              }
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug("Call already started: " + call.getId(), pe);
            }
            throw new CallConflictException("Call already started");
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Call already created with state " + conflictedCallEntity.getState() + ": " + call.getId(), pe);
            }
            throw new CallConflictException("Call already created");
          }
        } else {
          LOG.warn("Call ID already found but cannot read the call: " + call.getId(), pe);
          throw new CallConflictException("Call ID already found", pe);
        }
      } else {
        // We cannot create this call
        LOG.error("Error creating call: " + call.getId(), pe);
        throw new StorageException("Error creating call", pe);
      }
    }
  }

  /**
   * Invalidate a call by an ID. If such call (by ID) already found and, it's P2P call but not active -
   * it will be removed, otherwise {@link CallConflictException} will be thrown. This method designed to be
   * called just before {@link #createCall(CallInfo)}.
   *
   * @param id the id
   * @param isGroup the is group
   * @throws CallConflictException if call conflict found and existing call cannot be removed
   * @throws StorageException if storage exception happen
   */
  protected void invalidateCall(String id, boolean isGroup) throws CallConflictException, StorageException {
    try {
      CallEntity existingCallEntity = callStorage.find(id);
      if (existingCallEntity != null) {
        if (isGroup) {
          // Group call: already exists, we return an error - it needs read/start existing call
          throw new CallConflictException("Call already created");
        } else {
          // P2P call: need check if it is STARTED and does someone already joined and actually
          // connected,
          // if have one - it's an error to return, otherwise we treat this call as outdated
          // (could be left not properly stopped on an error like server crash or network lose)
          // thus we try to delete it before creating a new one
          try {
            CallInfo existingCall = readCallEntity(existingCallEntity, true);
            if (CallState.STARTED.equals(existingCall.getState())) {
              for (UserInfo savedPart : existingCall.getParticipants()) {
                Set<UserCallListener> ulisteners = userListeners.get(savedPart.getId());
                if (ulisteners != null) {
                  for (UserCallListener ul : ulisteners) {
                    if (savedPart.hasSameClientId(ul.getClientId())) {
                      // this part already joined and runs in the call
                      throw new CallConflictException("Call already started");
                    }
                  }
                }
              }
              deleteCall(id);
              LOG.warn("Deleted not active call: " + id);
            } else {
              deleteCall(id);
              LOG.warn("Deleted outdated call: " + id);
            }
          } catch (CallInfoException | IdentityStateException e) {
            LOG.warn("Call in erroneous state: " + id, e);
            deleteCall(id);
            LOG.warn("Deleted erroneous call: " + id);
          }
        }
      }
    } catch (IllegalArgumentException | IllegalStateException | PersistenceException e) {
      LOG.warn("Error reading call by ID: " + id, e);
    }
  }
  
  /**
   * Metric message for reporting to the stats logger.
   *
   * @param userId the user id of the operation
   * @param call the call in the operation
   * @param operation the operation name
   * @param status the status of operation
   * @param duration the operation duration in millseconds (can be <code>null</code>), it's not a call duration
   * @param error the error if present (can be <code>null</code>)
   * @return the string combining all given parameters in specified format
   *         according
   *         https://community.exoplatform.com/portal/g/:spaces:exo_itop/exo_itop/wiki/SPEC_-_logging_for_monitoring
   */
  protected String metricMessage(String userId,
                                 CallInfo call,
                                 String operation,
                                 String status,
                                 Long duration,
                                 String error) {
    StringBuilder res = new StringBuilder("service=webconferencing");
    res.append(" operation=").append(operation);
    res.append(" status=").append(status);
    res.append(" parameters=");
    res.append("\"userId:").append(userId);
    res.append(", isGroup:").append(call.getOwner().isGroup());
    res.append(", owner:").append(call.getOwner().getId());
    res.append(", ownerType:").append(call.getOwner().getType());
    res.append(", provider:").append(call.getProviderType());
    res.append(", state:").append(call.getState());
    res.append(", participantsCount:").append(call.getParticipants().size());
    if (call.getLastDate() != null) {
      long callDurationSec = Math.round((System.currentTimeMillis() - call.getLastDate().getTime()) / 1000);
      res.append(", callDuration_sec:").append(callDurationSec);
      long callDurationMin = Math.round(callDurationSec / 60);
      res.append(", callDuration_min:").append(callDurationMin);
    }
    res.append("\"");
    if (error != null && error.length() > 0) {
      res.append(" error_msg=\"").append(error).append("\"");
    }
    res.append(" duration_ms=").append(duration != null ? duration : -1);
    return res.toString();
  }

  private void broacastCallEvent(String eventName, CallInfo call, String userId) {
    try {
      listenerService.broadcast(eventName, call, userId);
    } catch (Exception e) {
      LOG.error("Error while broadcasting event '{}' for user '{}'", eventName, userId, e);
    }
  }

  // <<<<<<< Call storage: wrappers to catch JPA exceptions

  /**
   * Gets provider permissions.
   *
   * @param type the type
   * @param locale the locale
   * @return the provider permissions
   */
  public List<PermissionData> getProviderPermissions(String type, Locale locale) throws WebConferencingException {
    CallProviderConfiguration conf = getProviderConfiguration(type, locale);
    if (conf != null) {
      List<PermissionData> permissions = new ArrayList<>(conf.getPermissions().size());
      for (String permission : conf.getPermissions()) {
        PermissionData permissionData = new PermissionData(permission);
        if (!ALL_USERS.equals(permission)) {
          int splitIndex = permission.indexOf(":");
          if (splitIndex < 0) {
            // user permission
            Identity identity = socialIdentityManager.getOrCreateIdentity(OrganizationIdentityProvider.NAME, permission);
            if (identity != null) {
              Profile profile = identity.getProfile();
              String avatarUrl = profile.getAvatarUrl() != null ? profile.getAvatarUrl() : LinkProvider.PROFILE_DEFAULT_AVATAR_URL;
              permissionData.setDisplayName(profile.getFullName());
              permissionData.setAvatarUrl(avatarUrl);
            }
          } else if (splitIndex < permission.length() - 1) {
            // group (space etc)
            String groupId = permission.substring(splitIndex + 1);
            Space space = spaceService.getSpaceByGroupId(groupId);
            if (space != null) {
              String displayName = space.getDisplayName();
              String avatarUrl = space != null && space.getAvatarUrl() != null ? space.getAvatarUrl()
                                                                               : LinkProvider.SPACE_DEFAULT_AVATAR_URL;
              permissionData.setDisplayName(displayName);
              permissionData.setAvatarUrl(avatarUrl);
            } else {
              // group
              Group group = null;
              try {
                group = organization.getGroupHandler().findGroupById(groupId);
              } catch (Exception e) {
                LOG.error("Cannot get group by id {}. {}", groupId, e.getMessage());
              }
              if (group != null) {
                String displayName = group.getLabel();
                String avatarUrl = LinkProvider.SPACE_DEFAULT_AVATAR_URL;
                permissionData.setDisplayName(displayName);
                permissionData.setAvatarUrl(avatarUrl);
              }
            }
          }
        }
        permissions.add(permissionData);
      }
      return permissions;
    }
    return  null;
  }
}
