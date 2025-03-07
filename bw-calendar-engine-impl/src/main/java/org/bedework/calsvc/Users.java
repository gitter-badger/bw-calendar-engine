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

import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.BwGroup;
import org.bedework.calfacade.svc.BwPreferences;
import org.bedework.calfacade.BwPrincipal;
import org.bedework.calfacade.BwUser;
import org.bedework.calfacade.DirectoryInfo;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.svc.BwView;
import org.bedework.calsvci.CalSvcI;
import org.bedework.calsvci.UsersI;
import org.bedework.sysevents.NotificationException;
import org.bedework.sysevents.events.SysEvent;
import org.bedework.util.misc.Util;

import org.bedework.access.Access;
import org.bedework.access.PrivilegeDefs;
import org.bedework.access.WhoDefs;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** This acts as an interface to the database for user objects.
 *
 * @author Mike Douglass       douglm - rpi.edu
 */
class Users extends CalSvcDb implements UsersI {
  /* The account that owns public entities
   */
  private String publicUserAccount;

  private BwPrincipal publicUser;

  Users(final CalSvc svci) throws CalFacadeException {
    super(svci);
  }

  @Override
  public BwPrincipal getUser(final String account) throws CalFacadeException {
    if (account == null) {
      return null;
    }

    setRoots(getSvc());

    final String href = getSvc().getDirectories().makePrincipalUri(account,
                                                             WhoDefs.whoTypeUser);

    return getPrincipal(href);
  }

  @Override
  public BwPrincipal getAlways(final String account) throws CalFacadeException {
    if (account == null) {
      // Return guest user
      return BwPrincipal.makeUserPrincipal();
    }

    final BwPrincipal u = getUser(account);
    if (u == null) {
      add(account);
    }

    return getUser(account);
  }

  /* Make this session specific for the moment. We could make it static possibly
   * Also flush every few minutes
   */
  private final Map<String, BwPrincipal> principalMap = new HashMap<>();

  private long lastFlush = System.currentTimeMillis();
  private static final long flushInt = 1000 * 30 * 5; // 5 minutes

  private static String principalRoot;
  private static String userPrincipalRoot;
  private static String groupPrincipalRoot;

  //private static int principalRootLen;
//  private static int userPrincipalRootLen;
  private static int groupPrincipalRootLen;

  private static void setRoots(final CalSvcI svc) throws CalFacadeException {
    if (principalRoot != null) {
      return;
    }

    final DirectoryInfo di =  svc.getDirectories().getDirectoryInfo();
    principalRoot = di.getPrincipalRoot();
    userPrincipalRoot = di.getUserPrincipalRoot();
    groupPrincipalRoot = di.getGroupPrincipalRoot();

    //principalRootLen = principalRoot.length();
    //userPrincipalRootLen = userPrincipalRoot.length();
    groupPrincipalRootLen = groupPrincipalRoot.length();
  }

  private BwPrincipal mappedPrincipal(final String val) {
    final long now = System.currentTimeMillis();

    if ((now - lastFlush) > flushInt) {
      principalMap.clear();
      lastFlush = now;
      return null;
    }

    return principalMap.get(val);
  }

  @Override
  public BwPrincipal getPrincipal(final String val) throws CalFacadeException {
    if (val == null) {
      return null;
    }

    BwPrincipal p = mappedPrincipal(val);

    if (p != null) {
      return p;
    }

    setRoots(getSvc());

    if (!val.startsWith(principalRoot)) {
      return null;
    }

    if (val.startsWith(userPrincipalRoot)) {
      return getSvc().getPrincipal(val);
    }

    if (val.startsWith(groupPrincipalRoot)) {
      BwGroup g = getSvc().getDirectories().findGroup(val.substring(groupPrincipalRootLen));

      if (g != null) {
        principalMap.put(val, g);
      }

      return g;
    }

    return null;
  }

  @Override
  public void add(final String val) throws CalFacadeException {
    getSvc().addUser(val);
  }

  BwPrincipal initUserObject(final String val) throws CalFacadeException {
    String account = val;

    if (getSvc().getDirectories().isPrincipal(val)) {
      account = getSvc().getDirectories().accountFromPrincipal(val);
    }

    if (account == null) {
      throw new CalFacadeException("Bad user account " + val);
    }

    setRoots(getSvc());

    final BwPrincipal user = BwPrincipal.makeUserPrincipal();
    user.setAccount(account);

    user.setCategoryAccess(Access.getDefaultPersonalAccess());
    user.setLocationAccess(Access.getDefaultPersonalAccess());
    user.setContactAccess(Access.getDefaultPersonalAccess());

    ((BwUser)user).setQuota(getSvc().getAuthProperties().getDefaultUserQuota());

    user.setPrincipalRef(Util.buildPath(true, userPrincipalRoot, "/",
                                        account));

    return user;
  }

  void createUser(final String val) throws CalFacadeException {
    final BwPrincipal user = initUserObject(val);

    setRoots(getSvc());

    getCal().saveOrUpdate(user);

    getSvc().initPrincipal(user);
    initPrincipal(user, getSvc());

    getCal().getSpecialCalendar(user, BwCalendar.calTypeInbox,
                                true, PrivilegeDefs.privAny);

    getCal().getSpecialCalendar(user, BwCalendar.calTypePendingInbox,
                                true, PrivilegeDefs.privAny);

    getCal().getSpecialCalendar(user, BwCalendar.calTypeOutbox,
                                true, PrivilegeDefs.privAny);

    getCal().getSpecialCalendar(user, BwCalendar.calTypeNotifications,
                                true, PrivilegeDefs.privAny);

    getCal().getSpecialCalendar(user, BwCalendar.calTypePoll,
                                true, PrivilegeDefs.privAny);

    getCal().getSpecialCalendar(user, BwCalendar.calTypeTasks,
                                true, PrivilegeDefs.privAny);

    try {
      getSvc().postNotification(SysEvent.makePrincipalEvent(SysEvent.SysCode.NEW_USER,
                                                            user, 0));
    } catch (final NotificationException ne) {
      throw new CalFacadeException(ne);
    }

  }

  @Override
  public void update(final BwPrincipal principal) throws CalFacadeException {
    getCal().saveOrUpdate(principal);
  }

  @Override
  public void remove(final BwPrincipal pr) throws CalFacadeException {
    final String userRoot = getSvc().getPrincipalInfo().getCalendarHomePath(pr);

    /* views */

    final Collection<BwView> views = getSvc().getViewsHandler().getAll(pr);
    for (final BwView view: views) {
      getSvc().getViewsHandler().remove(view);
    }

    /* Set default calendar to null so we don't get blocked. */
    final BwPreferences prefs = getSvc().getPrefsHandler().get(pr);

    if (prefs != null) {
      prefs.setDefaultCalendarPath(null);
      getSvc().getPrefsHandler().update(prefs);
    }

    /* collections and user home */

    final BwCalendar home = getSvc().getCalendarsHandler().get(userRoot);
    if (home != null) {
      getSvc().getCalendarsHandler().delete(home, true, false);
    }

    /* Remove preferences */
    getSvc().getPrefsHandler().delete(prefs);

    getCal().delete(pr);
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.UsersI#logon(org.bedework.calfacade.BwPrincipal)
   */
  @Override
  public void logon(final BwPrincipal val) throws CalFacadeException {
    final Timestamp now = new Timestamp(System.currentTimeMillis());

    val.setLogon(now);
    val.setLastAccess(now);
    getCal().saveOrUpdate(val);

    /* Ensure we have a polls collection. */
    getSvc().getCal().getSpecialCalendar(val, BwCalendar.calTypePoll,
                                         true, PrivilegeDefs.privAny);
  }

  /*
  public void deleteUser(BwUser user) throws CalFacadeException {
    checkOpen();
    throw new CalFacadeException("Unimplemented");
  }*/

  /* (non-Javadoc)
   * @see org.bedework.calsvci.UsersI#initPrincipal(org.bedework.calfacade.BwPrincipal)
   */
  @Override
  public void initPrincipal(final BwPrincipal principal) throws CalFacadeException {
    initPrincipal(principal, getSvc());
  }

  private void initPrincipal(final BwPrincipal principal,
                             final CalSvc svc) throws CalFacadeException {
    // Add preferences
    final BwPreferences prefs = new BwPreferences();

    prefs.setOwnerHref(principal.getPrincipalRef());

    prefs.setDefaultCalendarPath(
      Util.buildPath(true,
                     getSvc().getPrincipalInfo().getCalendarHomePath(principal),
                     "/",
                     getBasicSyspars().getUserDefaultCalendar()));

    // Add a default view for the calendar home

    final BwView view = new BwView();

    view.setName(getAuthpars().getDefaultUserViewName());

    // Add default subscription to the user root.
    view.addCollectionPath(svc.getPrincipalInfo().getCalendarHomePath(principal));

    prefs.addView(view);
    prefs.setPreferredView(view.getName());

    prefs.setPreferredViewPeriod("week");
    prefs.setHour24(getAuthpars().getDefaultUserHour24());

    prefs.setScheduleAutoRespond(principal.getKind() == WhoDefs.whoTypeResource);

    svc.getPrefsHandler().update(prefs);
  }

  @Override
  public BwPrincipal getPublicUser() throws CalFacadeException {
    if (publicUser == null) {
      publicUser = getUser(getPublicUserAccount());
    }

    if (publicUser == null) {
      throw new CalFacadeException("No guest user proxy account - expected " + publicUserAccount);
    }

    return publicUser;
  }

  @Override
  public List<String> getPrincipalHrefs(final int start,
                                        final int count) throws CalFacadeException {
    return getCal().getPrincipalHrefs(start, count);
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  private String getPublicUserAccount() throws CalFacadeException {
    if (publicUserAccount == null) {
      publicUserAccount = getBasicSyspars().getPublicUser();
    }

    return publicUserAccount;
  }
}
