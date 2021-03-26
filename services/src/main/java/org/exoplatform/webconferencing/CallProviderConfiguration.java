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

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Provider configuration object for saving (in Admin UI) and
 * 
 * Created by The eXo Platform SAS.
 *
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: CallProviderConfiguration.java 00000 Nov 24, 2017 pnedonosko $
 */
public class CallProviderConfiguration {

  /** The Constant ALL_PERMISSIONS - all users permitted by default. */
  public static final List<String> ALL_PERMISSIONS = Collections.singletonList(WebConferencingService.ALL_USERS);
  
  /**
   * From provider.
   *
   * @param provider the provider
   * @param locale the locale for provider description, can be <code>null</code>
   * @return the call provider configuration
   */
  static CallProviderConfiguration fromProvider(CallProvider provider, Locale locale) {
    CallProviderConfiguration conf = new CallProviderConfiguration();
    conf.setActive(true);
    conf.setTitle(provider.getTitle());
    conf.setDescription(provider.getDescription(locale));
    conf.setType(provider.getType());
    conf.setLogEnabled(provider.isLogEnabled());
    conf.setPermissions(ALL_PERMISSIONS);
    return conf;
  }

  /** The type. */
  protected String  type;

  /** The title. */
  protected String  title;

  /** The description. */
  protected String  description;

  /** The permissions. */
  protected List<String> permissions;

  /** The active. */
  protected boolean active;

  /** The log enabled. */
  protected boolean logEnabled;

  /**
   * Checks if is log enabled.
   *
   * @return the logEnabled
   */
  public boolean isLogEnabled() {
    return logEnabled;
  }

  /**
   * Sets the log enabled.
   *
   * @param logEnabled the logEnabled to set
   */
  public void setLogEnabled(boolean logEnabled) {
    this.logEnabled = logEnabled;
  }

  /**
   * Checks if is active.
   *
   * @return true, if is active
   */
  public boolean isActive() {
    return active;
  }

  /**
   * Sets the active.
   *
   * @param active the new active
   */
  public void setActive(boolean active) {
    this.active = active;
  }

  /**
   * Gets the type.
   *
   * @return the type
   */
  public String getType() {
    return type;
  }

  /**
   * Sets the type.
   *
   * @param providerType the new type
   */
  public void setType(String providerType) {
    this.type = providerType;
  }

  /**
   * Gets the title.
   *
   * @return the title
   */
  public String getTitle() {
    return title;
  }

  /**
   * Sets the title.
   *
   * @param title the new title
   */
  public void setTitle(String title) {
    this.title = title;
  }

  /**
   * Gets the description.
   *
   * @return the description
   */
  public String getDescription() {
    return description;
  }

  /**
   * Sets the description.
   *
   * @param description the new description
   */
  public void setDescription(String description) {
    this.description = description;
  }

  /**
   * Gets permissions.
   *
   * @return the permissions
   */
  public List<String> getPermissions() {
    return permissions;
  }

  /**
   * Sets permissions.
   *
   * @param permissions the permissions
   */
  public void setPermissions(List<String> permissions) {
      this.permissions = permissions;
  }
}
