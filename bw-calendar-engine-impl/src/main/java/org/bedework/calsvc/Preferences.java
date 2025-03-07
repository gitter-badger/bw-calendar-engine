/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.bedework.calsvc;

import org.bedework.calfacade.svc.BwAuthUser;
import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.BwCategory;
import org.bedework.calfacade.BwContact;
import org.bedework.calfacade.BwEventProperty;
import org.bedework.calfacade.BwLocation;
import org.bedework.calfacade.svc.BwPreferences;
import org.bedework.calfacade.BwPrincipal;
import org.bedework.calfacade.BwPrincipalInfo;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.svc.prefs.BwCommonUserPrefs;
import org.bedework.calfacade.svc.prefs.CalendarPref;
import org.bedework.calfacade.svc.prefs.CategoryPref;
import org.bedework.calfacade.svc.prefs.ContactPref;
import org.bedework.calfacade.svc.prefs.LocationPref;
import org.bedework.calsvci.PreferencesI;
import org.bedework.util.misc.Util;

/** This acts as an interface to the database for user preferences.
 *
 * @author Mike Douglass       douglm - bedework.edu
 */
class Preferences extends CalSvcDb implements PreferencesI {
  private BwPreferences prefs;

  /**
   * @param svci
   */
  Preferences(final CalSvc svci) {
    super(svci);
  }

  /** Call at svci open
   *
   */
  @Override
  public void open() {
    super.open();
    prefs = null;
  }

  /** Call at svci close
   *
   */
  @Override
  public void close() {
    super.close();
    prefs = null;
  }

  /** Get the preferences for the current user
   *
   * @return the preferences for the current user
   * @throws CalFacadeException
   */
  @Override
  public BwPreferences get() throws CalFacadeException {
    if (prefs != null) {
      if (prefs.getOwnerHref() == null) {
        if (getPrincipal().getUnauthenticated()) {
          return prefs;
        }
      } else if (prefs.getOwnerHref().equals(getPrincipal().getPrincipalRef())) {
        return prefs;
      }
    }

    prefs = fetch();

    if (prefs == null) {
      // An uninitialised user?

      if (getPrincipal().getUnauthenticated()) {
        prefs = new BwPreferences();
        return prefs;
      }

      getSvc().getUsersHandler().initPrincipal(getPrincipal());

      prefs = fetch();
    }

    if (prefs == null) {
      throw new CalFacadeException("org.bedework.unable.to.initialise",
                                   getPrincipal().getAccount());
    }

    return prefs;
  }

  @Override
  public BwPreferences get(final BwPrincipal principal) throws CalFacadeException {
    return fetch(principal);
  }

  /** Update a preferences object
   *
   * @param val
   * @throws CalFacadeException
   */
  @Override
  public void update(final BwPreferences val) throws CalFacadeException {
    getCal().saveOrUpdate(val);
  }

  /** delete a preferences object
   *
   * @param val
   * @throws CalFacadeException
   */
  @Override
  public void delete(final BwPreferences val) throws CalFacadeException {
    getCal().delete(val);
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.PreferencesI#updateAdminPrefs(boolean, org.bedework.calfacade.BwEventProperty)
   */
  public void updateAdminPrefs(final boolean remove,
                               final BwEventProperty ent) throws CalFacadeException {
    if (ent instanceof BwCategory) {
      updateAdminPrefs(remove, null, (BwCategory)ent, null, null);
    } else if (ent instanceof BwLocation) {
      updateAdminPrefs(remove, null, null, (BwLocation)ent, null);
    } else if (ent instanceof BwContact) {
      updateAdminPrefs(remove, null, null, null, (BwContact)ent);
    }
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.PreferencesI#updateAdminPrefs(boolean, org.bedework.calfacade.BwCalendar, org.bedework.calfacade.BwCategory, org.bedework.calfacade.BwLocation, org.bedework.calfacade.BwContact)
   */
  public void updateAdminPrefs(final boolean remove,
                               final BwCalendar cal,
                               final BwCategory cat,
                               final BwLocation loc,
                               final BwContact ctct) throws CalFacadeException {
    BwCommonUserPrefs prefs = null;
    boolean update = false;
    BwAuthUser au = null;

    if (getPars().getPublicAdmin()) {
      au = getSvc().getUserAuth().getUser(getPars().getAuthUser());

      if (au != null) {
        prefs = au.getPrefs();
      }
    }

    if (prefs == null) {
      // XXX until we get non admin user preferred calendars etc
      return;
    }

    if (cal != null) {
      if (!remove) {
        CalendarPref p = prefs.getCalendarPrefs();
        if (p.getAutoAdd() && p.add(cal)) {
          update = true;
        }
      } else {
        getSvc().removeFromAllPrefs(cal);
      }
    }

    if (cat != null) {
      if (!remove) {
        CategoryPref p = prefs.getCategoryPrefs();
        if (p.getAutoAdd() && p.add(cat.getUid())) {
          update = true;
        }
      } else {
        getSvc().removeFromAllPrefs(cat);
      }
    }

    if (loc != null) {
      if (!remove) {
        LocationPref p = prefs.getLocationPrefs();
        if (p.getAutoAdd() && p.add(loc)) {
          update = true;
        }
      } else {
        getSvc().removeFromAllPrefs(loc);
      }
    }

    if (ctct != null) {
      if (!remove) {
        ContactPref p = prefs.getContactPrefs();
        if (p.getAutoAdd() && p.add(ctct)) {
          update = true;
        }
      } else {
        getSvc().removeFromAllPrefs(ctct);
      }
    }

    if (update) {
      if (getPars().getPublicAdmin()) {
        getSvc().getUserAuth().updateUser(au);
      }
    }
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.PreferencesI#getAttachmentsPath()
   */
  @Override
  public String getAttachmentsPath() throws CalFacadeException {
    String path = get().getAttachmentsPath();

    if (path == null) {
      path = Util.buildPath(true,
                            getSvc().getCalendarsHandler().getHome()
                                    .getPath(),
                            "/",
                            "attachments");
      get().setAttachmentsPath(path);
      update(get());
    }

    return path;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.PreferencesI#setAttachmentsPath(java.lang.String)
   */
  @Override
  public void setAttachmentsPath(final String val) throws CalFacadeException {
    if (val == null) {
      return;
    }

    get().setAttachmentsPath(val);
    update(get());
  }

  /* ====================================================================
   *                    Private methods
   * ==================================================================== */

  /** Fetch the preferences for the current user from the db
   *
   * @return the preferences for the current user
   * @throws CalFacadeException
   */
  private BwPreferences fetch() throws CalFacadeException {
    return fetch(getPrincipal());
  }

  /** Fetch the preferences for the given principal from the db
   *
   * @param principal
   * @return the preferences for the current user
   * @throws CalFacadeException
   */
  private BwPreferences fetch(final BwPrincipal principal) throws CalFacadeException {
    BwPreferences prefs = getSvc().getPreferences(principal.getPrincipalRef());

    BwPrincipalInfo pinfo = principal.getPrincipalInfo();
    if (pinfo == null) {
      return prefs;
    }

    if (getSvc().getDirectories().mergePreferences(prefs, pinfo)) {
      update(prefs);
    }

    return prefs;
  }
}

