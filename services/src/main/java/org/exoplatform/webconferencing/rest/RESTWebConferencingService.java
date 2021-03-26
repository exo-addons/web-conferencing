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
package org.exoplatform.webconferencing.rest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.webconferencing.CallProviderConfiguration;
import org.exoplatform.webconferencing.GroupInfo;
import org.exoplatform.webconferencing.IdentityStateException;
import org.exoplatform.webconferencing.PermissionData;
import org.exoplatform.webconferencing.UserInfo;
import org.exoplatform.webconferencing.WebConferencingService;
import org.exoplatform.webconferencing.client.ErrorInfo;
import org.exoplatform.webconferencing.dao.StorageException;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * Created by The eXo Platform SAS.
 *
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: RESTWebConferencingService.java 00000 Feb 22, 2017 pnedonosko $
 */
@Path("/webconferencing")
@Api(tags = "/webconferencing", value = "/webconferencing", description = "Operations on call providers and participant information")
@Produces(MediaType.APPLICATION_JSON)
public class RESTWebConferencingService implements ResourceContainer {

  /** The Constant EMPTY. */
  public static final String             EMPTY = "".intern();

  /** The Constant LOG. */
  protected static final Log             LOG   = ExoLogger.getLogger(RESTWebConferencingService.class);

  /** The web conferencing. */
  protected final WebConferencingService webConferencing;

  /** The cache control. */
  private final CacheControl             cacheControl;

  /**
   * Instantiates a new REST service for web conferencing.
   *
   * @param webConferencing the web conferencing
   */
  public RESTWebConferencingService(WebConferencingService webConferencing) {
    this.webConferencing = webConferencing;
    this.cacheControl = new CacheControl();
    cacheControl.setNoCache(true);
    cacheControl.setNoStore(true);
  }

  /**
   * Gets the provider config.
   *
   * @param uriInfo the uri info
   * @param request the request
   * @param type the type
   * @return the provider config
   */
  @GET
  @RolesAllowed("administrators")
  @Path("/provider/{type}/configuration") // TODO not used
  @ApiOperation(value = "Read a call provider configuration", httpMethod = "GET", response = CallProviderConfiguration.class, 
    notes = "Use this method to read a call provider configuration. This operation only avalable to Administrator user.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Provider configuration object returned.", response = CallProviderConfiguration.class),
    @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 404, message = "Provider not found. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
    @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response getProviderConfig(@Context UriInfo uriInfo,
                                    @Context HttpServletRequest request,
                                    @ApiParam(value = "Call provider type, ex: 'webrtc'", required = true) @PathParam("type") String type) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      try {
        CallProviderConfiguration conf = webConferencing.getProviderConfiguration(type, request.getLocale());
        if (conf != null) {
          return Response.ok().cacheControl(cacheControl).entity(conf).build();
        } else {
          return Response.status(Status.NOT_FOUND)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.notFoundError("Provider or configuration not found"))
                         .build();
        }
      } catch (Throwable e) {
        LOG.error("Error reading provider configuration for '" + type + "' by '" + currentUserName + "'", e);
        return Response.serverError()
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.serverError("Error reading provider configuration"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  /**
   * Post provider config.
   *
   * @param uriInfo the uri info
   * @param request the request
   * @param type the type
   * @param active the active
   * @return the response
   */
  @POST
  @RolesAllowed("administrators")
  @Path("/provider/{type}/configuration")
  @ApiOperation(value = "Updates a call provider activation status", httpMethod = "POST", response = CallProviderConfiguration.class, 
      notes = "Use this method to enable or disable a call provider. This operation only avalable to Administrator user.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Updated provider config returned.", response = CallProviderConfiguration.class),
      @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
      @ApiResponse(code = 404, message = "Provider not found. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
      @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response postProviderConfig(@Context UriInfo uriInfo,
                                     @Context HttpServletRequest request,
                                     @ApiParam(value = "Call provider type, ex: 'webrtc'", required = true) @PathParam("type") String type,
                                     @ApiParam(value = "Activation switch in form of boolean value (case insensitive), 'true' to enable, disable by any other value", required = true) @FormParam("active") String active) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      try {
        CallProviderConfiguration conf = webConferencing.getProviderConfiguration(type, request.getLocale());
        if (conf != null) {
          boolean activeVal = Boolean.valueOf(active);
          if (activeVal != conf.isActive()) {
            conf.setActive(activeVal);
            webConferencing.saveProviderConfiguration(conf);
          }
          return Response.ok().cacheControl(cacheControl).entity(conf).build();
        } else {
          return Response.status(Status.NOT_FOUND)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.notFoundError("Provider or configuration not found"))
                         .build();
        }
      } catch (Throwable e) {
        LOG.error("Error saving provider configuration for '" + type + "' by '" + currentUserName + "'", e);
        return Response.serverError()
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.serverError("Error saving provider configuration"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  @GET
  @RolesAllowed("administrators")
  @Path("/provider/{type}/permissions")
  @ApiOperation(value = "Read a call provider permissions", httpMethod = "GET", response = List.class, notes = "Use this method to read a call provider permissions. This operation only avalable to Administrator user.")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Request fulfilled. Provider permissions list returned.", response = List.class),
      @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR), 
      @ApiResponse(code = 404, message = "Provider not found. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
      @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR) })
  public Response getProviderPermissions(@Context UriInfo uriInfo,
                                         @Context HttpServletRequest request,
                                         @PathParam("type") String type) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      try {
        List<PermissionData> permissions = webConferencing.getProviderPermissions(type, request.getLocale());
        if (permissions == null) {
          return Response.status(Status.NOT_FOUND)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.notFoundError("Provider or configuration not found"))
                         .build();
        }
        return Response.ok().cacheControl(cacheControl).entity(permissions).build();
      } catch (Throwable e) {
        LOG.error("Error getting provider permissions for '" + type + "' by '" + currentUserName + "'", e);
        return Response.serverError()
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.serverError("Error getting provider permissions"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  @POST
  @RolesAllowed("administrators")
  @Path("/provider/{type}/permissions")
  @ApiOperation(value = "Update call provider permissions", httpMethod = "POST", response = CallProviderConfiguration.class, notes = "Use this method to update a call provider configuration's permissions. This operation only avalable to Administrator user.")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Request fulfilled. Provider configuration object returned.", response = CallProviderConfiguration.class),
      @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR), 
      @ApiResponse(code = 404, message = "Provider not found. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
      @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR) })
  public Response postProviderPermissions(@Context UriInfo uriInfo,
                                          @Context HttpServletRequest request,
                                          @PathParam("type") String type,
                                          @FormParam("permissions") String permissions) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      try {
        CallProviderConfiguration conf = webConferencing.getProviderConfiguration(type, request.getLocale());
        if (conf != null) {
          conf.setPermissions(Arrays.asList((permissions.split(" "))));
          webConferencing.saveProviderConfiguration(conf);
          return Response.ok().cacheControl(cacheControl).entity(conf).build();
        } else {
          return Response.status(Status.NOT_FOUND)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.notFoundError("Provider or configuration not found"))
                         .build();
        }
      } catch (Throwable e) {
        LOG.error("Error saving provider permissions for '" + type + "' by '" + currentUserName + "'", e);
        return Response.serverError()
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.serverError("Error saving provider permissions"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  /**
   * Gets the provider configs.
   *
   * @param uriInfo the uri info
   * @param request the request
   * @return the provider configs
   */
  @GET
  @RolesAllowed("administrators")
  @Path("/providers/configuration")
  @ApiOperation(value = "Read call providers configurations", httpMethod = "GET", response = Response.class, 
    notes = "Use this method to read all providers configuration. This operation only avalable to Administrator user.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Providers configurations returned.", response = Set.class),
    @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response getProviderConfigs(@Context UriInfo uriInfo, @Context HttpServletRequest request) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      try {
        Set<CallProviderConfiguration> confs = webConferencing.getProviderConfigurations(request.getLocale());
        return Response.ok().cacheControl(cacheControl).entity(confs).build();
      } catch (Throwable e) {
        LOG.error("Error reading providers configuration by '" + currentUserName + "'", e);
        return Response.serverError()
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.serverError("Error reading providers configuration"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  /**
   * Gets the user info.
   *
   * @param uriInfo
   *          the uri info
   * @param userName
   *          the id
   * @return the user info response
   */
  @GET
  @RolesAllowed("users")
  @Path("/user/{name}")
  @ApiOperation(value = "Return user information", httpMethod = "GET", response = UserInfo.class, 
    notes = "Use this method to read an user info used as call owner or participants. This operation is avalable to all Platform users.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. User info object returned.", response = UserInfo.class),
    @ApiResponse(code = 400, message = "Wrong request parameters: name. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),  
    @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 404, message = "User not found. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
    @ApiResponse(code = 500, message = "Internal server error due to data encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response getUserInfo(@Context UriInfo uriInfo, 
                              @ApiParam(value = "Call provider name, ex: 'webrtc'", required = true) @PathParam("name") String userName) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      if (userName != null) {
        try {
          UserInfo user = webConferencing.getUserInfo(userName);
          if (user != null) {
            return Response.ok().cacheControl(cacheControl).entity(user).build();
          } else {
            return Response.status(Status.NOT_FOUND)
                           .cacheControl(cacheControl)
                           .entity(ErrorInfo.notFoundError("User not found or not accessible"))
                           .build();
          }
        } catch (Throwable e) {
          LOG.error("Error reading user info of '" + userName + "' by '" + currentUserName + "'", e);
          return Response.serverError()
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.serverError("Error reading user " + userName))
                         .build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST)
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.clientError("Wrong request parameters: name"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  /**
   * Gets the space info.
   *
   * @param uriInfo
   *          the uri info
   * @param spaceName
   *          the space name
   * @return the space info response
   */
  @GET
  @RolesAllowed("users")
  @Path("/space/{spaceName}")
  @ApiOperation(value = "Return social space information", httpMethod = "GET", response = GroupInfo.class, 
    notes = "Use this method to read a Social space info used as call owner and origins. This operation is avalable to all Platform users.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Space info object returned.", response = GroupInfo.class),
    @ApiResponse(code = 400, message = "Wrong request parameters: spaceName. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
    @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 403, message = "Not space member. Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 404, message = "Space not found or not accessible. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
    @ApiResponse(code = 500, message = "Internal server error due to data reading from DB, its encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response getSpaceInfo(@Context UriInfo uriInfo, 
                               @ApiParam(value = "Space pretty name, ex: 'sales_team'", required = true) @PathParam("spaceName") String spaceName) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      if (spaceName != null && spaceName.length() > 0) {
        try {
          GroupInfo space = webConferencing.getSpaceInfo(spaceName);
          if (space != null) {
            if (space.getMembers().containsKey(currentUserName)) {
              return Response.ok().cacheControl(cacheControl).entity(space).build();
            } else {
              return Response.status(Status.FORBIDDEN)
                             .cacheControl(cacheControl)
                             .entity(ErrorInfo.accessError("Not space member"))
                             .build();
            }
          } else {
            return Response.status(Status.NOT_FOUND)
                           .cacheControl(cacheControl)
                           .entity(ErrorInfo.notFoundError("Space not found or not accessible"))
                           .build();
          }
        } catch (IdentityStateException e) {
          LOG.error("Error reading member of space '" + spaceName + "' by '" + currentUserName + "'", e);
          return Response.serverError()
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.serverError("Error reading member of space '" + spaceName + "'"))
                         .build();
        } catch (StorageException e) {
          LOG.error("Storage error for space info of '" + spaceName + "' by '" + currentUserName + "'", e);
          return Response.serverError()
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.serverError("Storage error for space '" + spaceName + "'"))
                         .build();
        } catch (Throwable e) {
          LOG.error("Error reading space info of '" + spaceName + "' by '" + currentUserName + "'", e);
          return Response.serverError()
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.serverError("Error reading space " + spaceName))
                         .build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST)
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.clientError("Wrong request parameters: spaceName"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }

  /**
   * Gets the space event info.
   *
   * @param uriInfo the uri info
   * @param spaceName the space name
   * @return the space event info response
   */
    @GET
    @RolesAllowed("users")
    @Path("/space-event/{spaceName}")
    @ApiOperation(value = "Return a Social space event information", httpMethod = "GET", response = GroupInfo.class, 
      notes = "Use this method to read a Social space event used as call origin. This operation is avalable to all Platform users.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Space event info object returned.", response = GroupInfo.class),
      @ApiResponse(code = 400, message = "Wrong request parameters: spaceName. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
      @ApiResponse(code = 400, message = "Wrong request parameters: participants. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
      @ApiResponse(code = 400, message = "Wrong request parameters: spaces. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
      @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
      @ApiResponse(code = 403, message = "Not space member. Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
      @ApiResponse(code = 404, message = "Space not found or not accessible. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
      @ApiResponse(code = 500, message = "Internal server error due to data reading from DB, its encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
    public Response getSpaceEventInfo(@Context UriInfo uriInfo,
                                      @ApiParam(value = "Space pretty name used as the event host, ex: 'sales_team'", required = true) @PathParam("spaceName") String spaceName,
                                      @ApiParam(value = "Participants directly invited to the event, a string of comma-separated names, ex: 'john,mary,james'", required = true) @QueryParam("participants") String participants,
                                      @ApiParam(value = "Space pretty names for inviting its participants to the event, a string of comma-separated names, ex: 'sales_team,acme_project,ux_pride'", required = true) @QueryParam("spaces") String spaces) {
      ConversationState convo = ConversationState.getCurrent();
      if (convo != null) {
        String currentUserName = convo.getIdentity().getUserId();
        if (spaceName != null && spaceName.length() > 0) {
          if (participants != null && participants.length() > 0) {
            if (spaces != null && spaces.length() > 0) {
              try {
                GroupInfo space = webConferencing.getSpaceEventInfo(spaceName,
                                                                    participants.trim().split(";"),
                                                                    spaces.trim().split(";"));
                if (space != null) {
                  if (space.getMembers().containsKey(currentUserName)) {
                    return Response.ok().cacheControl(cacheControl).entity(space).build();
                  } else {
                    return Response.status(Status.FORBIDDEN)
                                   .cacheControl(cacheControl)
                                   .entity(ErrorInfo.accessError("Not space member"))
                                   .build();
                  }
                } else {
                  return Response.status(Status.NOT_FOUND)
                                 .cacheControl(cacheControl)
                                 .entity(ErrorInfo.notFoundError("Space not found or not accessible"))
                                 .build();
                }
              } catch (IdentityStateException e) {
                LOG.error("Error reading member of space '" + spaceName + "' by '" + currentUserName + "'", e);
                return Response.serverError()
                               .cacheControl(cacheControl)
                               .entity(ErrorInfo.serverError("Error reading member of space '" + spaceName + "'"))
                               .build();
              } catch (StorageException e) {
                LOG.error("Storage error for space event info of '" + spaceName + "' by '" + currentUserName + "'", e);
                return Response.serverError()
                               .cacheControl(cacheControl)
                               .entity(ErrorInfo.serverError("Storage error for space '" + spaceName + "'"))
                               .build();
              } catch (Throwable e) {
                LOG.error("Error reading space event info of '" + spaceName + "' by '" + currentUserName + "'", e);
                return Response.serverError()
                               .cacheControl(cacheControl)
                               .entity(ErrorInfo.serverError("Error reading space " + spaceName))
                               .build();
              }
            } else {
              return Response.status(Status.BAD_REQUEST)
                             .cacheControl(cacheControl)
                             .entity(ErrorInfo.clientError("Wrong request parameters: spaces"))
                             .build();
            }
          } else {
            return Response.status(Status.BAD_REQUEST)
                           .cacheControl(cacheControl)
                           .entity(ErrorInfo.clientError("Wrong request parameters: participants"))
                           .build();
          }
        } else {
          return Response.status(Status.BAD_REQUEST)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.clientError("Wrong request parameters: spaceName"))
                         .build();
        }
      } else {
        return Response.status(Status.UNAUTHORIZED)
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.accessError("Unauthorized user"))
                       .build();
      }
    }

  /**
   * Gets the chat room info.
   *
   * @param uriInfo the uri info
   * @param roomId the room id
   * @param roomTitle the room title
   * @param roomMembers the room members
   * @return the room info response
   */
  @GET
  @RolesAllowed("users")
  @Path("/room/{id}")
  @ApiOperation(value = "Return chat room information", httpMethod = "GET", response = GroupInfo.class, 
    notes = "Use this method to chat room info used as call owner and origins. This operation is avalable to all Platform users.")
  @ApiResponses(value = { @ApiResponse(code = 200, message = "Request fulfilled. Chat room info object returned.", response = GroupInfo.class),
    @ApiResponse(code = 400, message = "Wrong request parameters: id. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
    @ApiResponse(code = 400, message = "Wrong request parameters: title. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
    @ApiResponse(code = 400, message = "Wrong request parameters: members. Error code: " + ErrorInfo.CODE_CLIENT_ERROR),
    @ApiResponse(code = 401, message = "Unauthorized user (conversation state not present). Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 403, message = "Not room member. Error code: " + ErrorInfo.CODE_ACCESS_ERROR),
    @ApiResponse(code = 404, message = "Room not found or not accessible. Error code: " + ErrorInfo.CODE_NOT_FOUND_ERROR),
    @ApiResponse(code = 500, message = "Internal server error due to data reading from DB, its encoding or formatting result to JSON. Error code: " + ErrorInfo.CODE_SERVER_ERROR)})
  public Response getRoomInfo(@Context UriInfo uriInfo,
                              @ApiParam(value = "Room ID, ex: 'team-ec5e257858734e40a98505475d8eedc4'", required = true) @PathParam("id") String roomId,
                              @ApiParam(value = "Room title, ex: 'ACME meetings'", required = true) @QueryParam("title") String roomTitle,
                              @ApiParam(value = "Room members (platform users or external Chat users), a string of comma-separated names, ex: 'john,james,julia'", required = true) @QueryParam("members") String roomMembers) {
    ConversationState convo = ConversationState.getCurrent();
    if (convo != null) {
      String currentUserName = convo.getIdentity().getUserId();
      if (roomId != null && roomId.length() > 0) {
        if (roomTitle != null && roomTitle.length() > 0) {
          if (roomMembers != null && roomMembers.length() > 0) {
            try {
              GroupInfo room = webConferencing.getRoomInfo(roomId, roomTitle, roomMembers.trim().split(";"));
              if (room != null) {
                if (room.getMembers().containsKey(currentUserName)) {
                  return Response.ok().cacheControl(cacheControl).entity(room).build();
                } else {
                  return Response.status(Status.FORBIDDEN)
                                 .cacheControl(cacheControl)
                                 .entity(ErrorInfo.accessError("Not room member"))
                                 .build();
                }
              } else {
                // FYI this will not happen until we don't request chat server database
                return Response.status(Status.NOT_FOUND)
                               .cacheControl(cacheControl)
                               .entity(ErrorInfo.notFoundError("Room not found or not accessible"))
                               .build();
              }
            } catch (IdentityStateException e) {
              LOG.error("Error reading member of room '" + roomTitle + "' by '" + currentUserName + "'", e);
              return Response.serverError()
                             .cacheControl(cacheControl)
                             .entity(ErrorInfo.serverError("Error reading member of room '" + roomTitle + "'"))
                             .build();
            } catch (StorageException e) {
              LOG.error("Storage error for room info of '" + roomTitle + "' by '" + currentUserName + "'", e);
              return Response.serverError()
                             .cacheControl(cacheControl)
                             .entity(ErrorInfo.serverError("Storage error for room '" + roomTitle + "'"))
                             .build();
            } catch (Throwable e) {
              LOG.error("Error reading room info of '" + roomTitle + "' by '" + currentUserName + "'", e);
              return Response.serverError()
                             .cacheControl(cacheControl)
                             .entity(ErrorInfo.serverError("Error reading room '" + roomTitle + "'"))
                             .build();
            }
          } else {
            return Response.status(Status.BAD_REQUEST)
                           .cacheControl(cacheControl)
                           .entity(ErrorInfo.clientError("Wrong request parameters: members"))
                           .build();
          }
        } else {
          return Response.status(Status.BAD_REQUEST)
                         .cacheControl(cacheControl)
                         .entity(ErrorInfo.clientError("Wrong request parameters: title"))
                         .build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST)
                       .cacheControl(cacheControl)
                       .entity(ErrorInfo.clientError("Wrong request parameters: id"))
                       .build();
      }
    } else {
      return Response.status(Status.UNAUTHORIZED)
                     .cacheControl(cacheControl)
                     .entity(ErrorInfo.accessError("Unauthorized user"))
                     .build();
    }
  }
}
