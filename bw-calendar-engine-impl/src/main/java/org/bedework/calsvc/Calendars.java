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

import org.bedework.access.Acl.CurrentAccess;
import org.bedework.access.PrivilegeDefs;
import org.bedework.calcorei.Calintf;
import org.bedework.calcorei.CoreCalendarsI.GetSpecialCalendarResult;
import org.bedework.calfacade.svc.BwAuthUser;
import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.svc.BwPreferences;
import org.bedework.calfacade.BwPrincipal;
import org.bedework.calfacade.BwResource;
import org.bedework.calfacade.CalFacadeDefs;
import org.bedework.calfacade.base.BwShareableDbentity;
import org.bedework.calfacade.exc.CalFacadeAccessException;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.svc.EventInfo;
import org.bedework.calsvci.CalendarsI;
import org.bedework.calsvci.ResourcesI;
import org.bedework.calsvci.SynchI;
import org.bedework.util.misc.Util;

import net.fortuna.ical4j.model.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** This acts as an interface to the database for calendars.
 *
 * @author Mike Douglass       douglm - rpi.edu
 */
class Calendars extends CalSvcDb implements CalendarsI {
  private final String publicCalendarRootPath;
  //private String userCalendarRootPath;

  /** Constructor
   *
   * @param svci interface
   * @throws CalFacadeException
   */
  Calendars(final CalSvc svci) throws CalFacadeException {
    super(svci);

    publicCalendarRootPath = Util.buildPath(true, "/",
                                            getBasicSyspars()
                                                    .getPublicCalendarRoot());
    //userCalendarRootPath = "/" + getBasicSyspars().getUserCalendarRoot();
  }

  @Override
  public String getPublicCalendarsRootPath() throws CalFacadeException {
    return publicCalendarRootPath;
  }

  @Override
  public BwCalendar getPublicCalendars() throws CalFacadeException {
    return getCal().getCalendar(publicCalendarRootPath,
                                PrivilegeDefs.privRead, true);
  }

  @Override
  public String getHomePath() throws CalFacadeException {
    if (isGuest()) {
      return publicCalendarRootPath;
    }

    if (isPublicAdmin()) {
      if (!getSyspars().getWorkflowEnabled()) {
        return publicCalendarRootPath;
      }

      final BwAuthUser au = getSvc().getUserAuth().getUser(getPars().getAuthUser());
      final boolean isApprover = isSuper() || (au != null) && au.isApproverUser();

      // Do they have approver status?
      if (isApprover) {
        return publicCalendarRootPath;
      }

      return Util.buildPath(true, getSyspars().getWorkflowRoot()); // "/",
//                            getPrincipal().getAccountNoSlash());
    }

    return getSvc().getPrincipalInfo().getCalendarHomePath();
  }

  @Override
  public BwCalendar getHome() throws CalFacadeException {
    return getCal().getCalendar(getHomePath(), PrivilegeDefs.privRead, true);
  }

  @Override
  public BwCalendar getHome(final BwPrincipal principal,
                            final boolean freeBusy) throws CalFacadeException {
    final int priv;
    if (freeBusy) {
      priv = PrivilegeDefs.privReadFreeBusy;
    } else {
      priv = PrivilegeDefs.privRead;
    }
    return getCal().getCalendar(getSvc().getPrincipalInfo().getCalendarHomePath(principal),
                                         priv, true);
  }

  @Override
  public Collection<BwCalendar> decomposeVirtualPath(final String vpath) throws CalFacadeException {
    final Collection<BwCalendar> cols = new ArrayList<>();

    /* First see if the vpath is an actual path - generally the case for
     * personal calendar users.
     */

    BwCalendar curCol = get(vpath);

    if ((curCol != null) && !curCol.getAlias()) {
      cols.add(curCol);

      return cols;
    }

    final String[] pathEls = normalizeUri(vpath).split("/");

    if ((pathEls == null) || (pathEls.length == 0)) {
      return cols;
    }

    /* First, keep adding elements until we get a BwCalendar result.
     * This handles the user root not being accessible
     */

    curCol = null;
    String startPath = "";
    int pathi = 1;  // Element 0 is a zero length string

    while (pathi < pathEls.length) {
      startPath = Util.buildPath(true, startPath, "/", pathEls[pathi]);

      pathi++;

      try {
        curCol = get(startPath);
      } catch (final CalFacadeAccessException cfae) {
        curCol = null;
      }

      if (curCol != null) {
        // Found the start collection
        if (debug) {
          trace("Start vpath collection:" + curCol.getPath());
        }
        break;
      }
    }

    if (curCol == null) {
      // Bad vpath
      return null;
    }

    buildCollection:
    for (;;) {
      cols.add(curCol);

      if (debug) {
        trace("      vpath collection:" + curCol.getPath());
      }

      // Follow the chain of references for curCol until we reach a non-alias
      if (curCol.getInternalAlias()) {
        final BwCalendar nextCol = resolveAlias(curCol, false, false);

        if (nextCol == null) {
          // Bad vpath
          curCol.setDisabled(true);
          curCol.setLastRefreshStatus("400");
          return null;
        }

        curCol = nextCol;

        continue buildCollection;
      }

      /* Not an alias - do we have any more path elements
       */
      if (pathi >= pathEls.length) {
        break buildCollection;
      }

      /* Not an alias and we have more path elements.
       * It should be a collection. Look for the next path
       * element as a child name
       */

      if (curCol.getCalType() != BwCalendar.calTypeFolder) {
        // Bad vpath
        return null;
      }

      /*
      for (BwCalendar col: getChildren(curCol)) {
        if (col.getName().equals(pathEls[pathi])) {
          // Found our child
          pathi++;
          curCol = col;
          continue buildCollection;
        }
      }
      */
      final BwCalendar col = get(curCol.getPath() + "/" + pathEls[pathi]);

      if (col == null) {
        /* Child not found - bad vpath */
        return null;
      }

      pathi++;
      curCol = col;
    }

    return cols;
  }

  @Override
  public Collection<BwCalendar> getChildren(final BwCalendar col) throws CalFacadeException {
    if (col.getCalType() == BwCalendar.calTypeAlias) {
      resolveAlias(col, true, false);
    }
    return getCal().getCalendars(col.getAliasedEntity());
  }

  @Override
  public Set<BwCalendar> getAddContentCollections(final boolean includeAliases)
          throws CalFacadeException {
    final Set<BwCalendar> cals = new TreeSet<>();

    getAddContentCalendarCollections(includeAliases, getHome(), cals);

    return cals;
  }

  @Override
  public boolean isEmpty(final BwCalendar val) throws CalFacadeException {
    return getSvc().getCal().isEmpty(val);
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.CalendarsI#get(java.lang.String)
   */
  @Override
  public BwCalendar get(String path) throws CalFacadeException{
    if (path == null) {
      return null;
    }

    if ((path.length() > 1) &&
        (path.startsWith(CalFacadeDefs.bwUriPrefix))) {
      path = path.substring(CalFacadeDefs.bwUriPrefix.length());
    }

    return getCal().getCalendar(path, PrivilegeDefs.privAny, false);
  }

  @Override
  public BwCalendar getSpecial(final int calType,
                               final boolean create) throws CalFacadeException {
    return getSpecial(null, calType, create);
  }

  @Override
  public BwCalendar getSpecial(final String principal,
                               final int calType,
                               final boolean create) throws CalFacadeException {
    final BwPrincipal pr;

    if (principal == null) {
      pr = getPrincipal();
    } else {
      pr = getPrincipal(principal);
    }

    final Calintf.GetSpecialCalendarResult gscr =
            getSvc().getCal().getSpecialCalendar(
                             pr, calType, create,
                                       PrivilegeDefs.privAny);
    if (!gscr.noUserHome) {
      return gscr.cal;
    }

    getSvc().getUsersHandler().add(getPrincipal().getAccount());

    return getCal().getSpecialCalendar(pr, calType, create,
                                       PrivilegeDefs.privAny).cal;
  }

  @Override
  public void setPreferred(final BwCalendar  val) throws CalFacadeException {
    getSvc().getPrefsHandler().get().setDefaultCalendarPath(val.getPath());
  }

  @Override
  public String getPreferred(final String entityType) throws CalFacadeException {
    final int calType;

    switch (entityType) {
      case Component.VEVENT:
        final String path = getSvc().getPrefsHandler().get()
                .getDefaultCalendarPath();

        if (path != null) {
          return path;
        }

        calType = BwCalendar.calTypeCalendarCollection;
        break;
      case Component.VTODO:
        calType = BwCalendar.calTypeTasks;
        break;
      case Component.VPOLL:
        calType = BwCalendar.calTypePoll;
        break;
      default:
        return null;
    }

    final GetSpecialCalendarResult gscr =
            getCal().getSpecialCalendar(getPrincipal(),
                                        calType,
                                        true,
                                        PrivilegeDefs.privAny);

    return gscr.cal.getPath();
  }

  @Override
  public BwCalendar add(BwCalendar val,
                        final String parentPath) throws CalFacadeException {
    updateOK(val);

    setupSharableEntity(val, getPrincipal().getPrincipalRef());
    val.adjustCategories();

    if (val.getPwNeedsEncrypt()) {
      encryptPw(val);
    }

    val = getCal().add(val, parentPath);
    ((Preferences)getSvc().getPrefsHandler()).updateAdminPrefs(false,
                                                val,
                                                null,
                                                null,
                                                null);

    final SynchI synch = getSvc().getSynch();

    if (val.getExternalSub()) {
      if (!synch.subscribe(val)) {
        throw new CalFacadeException(CalFacadeException.subscriptionFailed);
      }
    }

    return val;
  }

  @Override
  public void rename(final BwCalendar val,
                     final String newName) throws CalFacadeException {
    getSvc().getCal().renameCalendar(val, newName);
  }

  @Override
  public void move(final BwCalendar val,
                   final BwCalendar newParent) throws CalFacadeException {
    getSvc().getCal().moveCalendar(val, newParent);
  }

  @Override
  public void update(final BwCalendar val) throws CalFacadeException {
    val.adjustCategories();

    /* Ensure it's not in admin prefs if it's a folder.
     * User may have switched from calendar to folder.
     */
    if (!val.getCalendarCollection() && isPublicAdmin()) {
      /* Remove from preferences */
      ((Preferences)getSvc().getPrefsHandler()).updateAdminPrefs(true,
                                                                 val,
                                                                 null,
                                                                 null,
                                                                 null);
    }

    if (val.getPwNeedsEncrypt()) {
      encryptPw(val);
    }

    getCal().updateCalendar(val);
  }

  @Override
  public boolean delete(final BwCalendar val,
                        final boolean emptyIt,
                        final boolean sendSchedulingMessage) throws CalFacadeException {
    return delete(val, emptyIt, false, sendSchedulingMessage);
  }

  @Override
  public boolean isUserRoot(final BwCalendar cal) throws CalFacadeException {
    if ((cal == null) || (cal.getPath() == null)) {
      return false;
    }

    final String[] ss = cal.getPath().split("/");
    final int pathLength = ss.length - 1;  // First element is empty string

    return (pathLength == 2) &&
           (ss[1].equals(getBasicSyspars().getUserCalendarRoot()));
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.CalendarsI#resolveAlias(org.bedework.calfacade.BwCalendar, boolean, boolean)
   */
  @Override
  public BwCalendar resolveAlias(final BwCalendar val,
                                 final boolean resolveSubAlias,
                                 final boolean freeBusy) throws CalFacadeException {
    return getCal().resolveAlias(val, resolveSubAlias, freeBusy);
  }

  @Override
  public SynchStatusResponse getSynchStatus(final String path) throws CalFacadeException {
    return getSvc().getSynch().getSynchStatus(get(path));
  }

  @Override
  public CheckSubscriptionResult checkSubscription(final String path) throws CalFacadeException {
    return getSvc().getSynch().checkSubscription(get(path));
  }

  @Override
  public String getSyncToken(final String path) throws CalFacadeException {
    return getCal().getSyncToken(path);
  }

  /* ====================================================================
   *                   package private methods
   * ==================================================================== */

  /**
   * @param val an href
   * @return list of any aliases for the current user pointing at the given href
   * @throws CalFacadeException
   */
  List<BwCalendar> findUserAlias(final String val) throws CalFacadeException {
    return getCal().findAlias(val);
  }

  Set<BwCalendar> getSynchCols(final String path,
                               final String lastmod) throws CalFacadeException {
    return getCal().getSynchCols(path, lastmod);
  }

  BwCalendar getSpecial(final BwPrincipal owner,
                        final int calType,
                        final boolean create,
                        final int access) throws CalFacadeException {
    final Calintf.GetSpecialCalendarResult gscr =
            getSvc().getCal().getSpecialCalendar(
                                      owner, calType, create,
                                                PrivilegeDefs.privAny);
    if (gscr.noUserHome) {
      getSvc().getUsersHandler().add(owner.getAccount());
    }

    return getSvc().getCal().getSpecialCalendar(owner, calType, create,
                                         PrivilegeDefs.privAny).cal;
  }

  /* ====================================================================
   *                   private methods
   * ==================================================================== */

  private boolean delete(final BwCalendar val,
                         final boolean emptyIt,
                         final boolean reallyDelete,
                         final boolean sendSchedulingMessage) throws CalFacadeException {
    if (!emptyIt) {
      /** Only allow delete if not in use
       */
      if (!getCal().isEmpty(val)) {
        throw new CalFacadeException(CalFacadeException.collectionNotEmpty);
      }
    }

    final BwPreferences prefs = getSvc().getPrefsHandler().get(
             getSvc().getUsersHandler().getPrincipal(val.getOwnerHref()));
    if (val.getPath().equals(prefs.getDefaultCalendarPath())) {
      throw new CalFacadeException(CalFacadeException.cannotDeleteDefaultCalendar);
    }

    /* Remove any sharing */
    if (val.getCanAlias()) {
      getSvc().getSharingHandler().delete(val);
    }

    getSvc().getSharingHandler().unsubscribe(val);

    /* Remove from preferences */
    ((Preferences)getSvc().getPrefsHandler()).updateAdminPrefs(true,
                                                               val,
                                                               null,
                                                               null,
                                                               null);

    /* If it' an alias we just delete it - otherwise we might need to empty it.
     */
    if (!val.getInternalAlias() && emptyIt) {
      if (val.getCalendarCollection()) {
        final Events events = ((Events)getSvc().getEventsHandler());
        for (final EventInfo ei: events.getSynchEvents(val.getPath(),
                                                       null)) {
          events.delete(ei,
                        false,
                        sendSchedulingMessage,
                        true);
        }
      }

      /* Remove resources */
      final ResourcesI resI = getSvc().getResourcesHandler();
      final Collection<BwResource> rs = resI.getAll(val.getPath());
      if (!Util.isEmpty(rs)) {
        for (final BwResource r: rs) {
          resI.delete(Util.buildPath(false, r.getColPath(), "/", r.getName()));
        }
      }

      for (final BwCalendar cal: getChildren(val)) {
        if (!delete(cal, true, true, sendSchedulingMessage)) {
          // Somebody else at it
          getSvc().rollbackTransaction();
          throw new CalFacadeException(CalFacadeException.collectionNotFound,
                                       cal.getPath());
        }
      }
    }

    getSvc().getSynch().unsubscribe(val);

    /* Attempt to tombstone it
     */
    return getSvc().getCal().deleteCalendar(val, false);
  }

  private void getAddContentCalendarCollections(final boolean includeAliases,
                                                final BwCalendar root,
                                                final Set<BwCalendar> cals)
        throws CalFacadeException {
    if (!includeAliases && root.getAlias()) {
      return;
    }

    final BwCalendar col = resolveAlias(root, true, false);
    if (col == null) {
      // No access or gone
      return;
    }

    if (col.getCalType() == BwCalendar.calTypeCalendarCollection) {
      /* We might want to add the busy time calendar here -
       * presumably we will want availability stored somewhere.
       * These might be implicit operations however.
       */
      final CurrentAccess ca = getSvc().checkAccess(col,
                                                    PrivilegeDefs.privWriteContent,
                                                    true);

      if (ca.getAccessAllowed()) {
        cals.add(root);  // Might be an alias, might not
      }
      return;
    }

    if (root.getCalendarCollection()) {
      // Leaf but cannot add here
      return;
    }

    for (final BwCalendar ch: getChildren(root)) {
      getAddContentCalendarCollections(includeAliases, ch, cals);
    }
  }

  private void encryptPw(final BwCalendar val) throws CalFacadeException {
    try {
      val.setRemotePw(getSvc().getEncrypter().encrypt(val.getRemotePw()));
    } catch (final Throwable t) {
      throw new CalFacadeException(t);
    }
  }

  /* This provides some limits to shareable entity updates for the
   * admin users. It is applied in addition to the normal access checks
   * applied at the lower levels.
   */
  private void updateOK(final Object o) throws CalFacadeException {
    if (isGuest()) {
      throw new CalFacadeAccessException();
    }

    if (isSuper()) {
      // Always ok
      return;
    }

    if (!(o instanceof BwShareableDbentity)) {
      throw new CalFacadeAccessException();
    }

    if (!isPublicAdmin()) {
      // Normal access checks apply
      return;
    }

    final BwShareableDbentity ent = (BwShareableDbentity)o;

    if (getPars().getAdminCanEditAllPublicContacts() ||
        ent.getCreatorHref().equals(getPrincipal().getPrincipalRef())) {
      return;
    }

    throw new CalFacadeAccessException();
  }

  private String normalizeUri(String uri) throws CalFacadeException {
    /*Remove all "." and ".." components */
    try {
      uri = new URI(null, null, uri, null).toString();

      uri = new URI(URLEncoder.encode(uri, "UTF-8")).normalize().getPath();

      uri = Util.buildPath(true, URLDecoder.decode(uri, "UTF-8"));

      if (debug) {
        trace("Normalized uri=" + uri);
      }

      return uri;
    } catch (final Throwable t) {
      if (debug) {
        error(t);
      }
      throw new CalFacadeException("Bad uri: " + uri);
    }
  }
}
