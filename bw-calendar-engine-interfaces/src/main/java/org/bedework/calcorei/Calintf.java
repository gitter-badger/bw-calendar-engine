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
package org.bedework.calcorei;

import org.bedework.calfacade.BwAlarm;
import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.BwDateTime;
import org.bedework.calfacade.BwEvent;
import org.bedework.calfacade.BwEventAnnotation;
import org.bedework.calfacade.BwEventProperty;
import org.bedework.calfacade.BwGroup;
import org.bedework.calfacade.svc.BwPreferences;
import org.bedework.calfacade.BwPrincipal;
import org.bedework.calfacade.BwResource;
import org.bedework.calfacade.BwStats;
import org.bedework.calfacade.BwStats.StatsEntry;
import org.bedework.calfacade.BwSystem;
import org.bedework.calfacade.base.BwDbentity;
import org.bedework.calfacade.base.BwShareableDbentity;
import org.bedework.calfacade.base.BwUnversionedDbentity;
import org.bedework.calfacade.configs.BasicSystemProperties;
import org.bedework.calfacade.configs.Configurations;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.indexing.BwIndexer;
import org.bedework.calfacade.svc.BwAdminGroup;
import org.bedework.calfacade.svc.BwCalSuite;
import org.bedework.calfacade.svc.PrincipalInfo;
import org.bedework.sysevents.events.SysEventBase;

import org.bedework.access.Ace;
import org.bedework.access.AceWho;
import org.bedework.access.Acl.CurrentAccess;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/** This is the low level interface to the calendar database.
 *
 * <p>This interface provides a view of the data as seen by the supplied user
 * id. This may or may not be the actual authenticated user of whatever
 * application is driving it.
 *
 * <p>This is of particular use for public events administration. A given
 * authenticated user may be the member of a number of groups, and this module
 * will be initialised with the id of one of those groups. At some point
 * the authenticated user may choose to switch identities to manage a different
 * group.
 *
 * <p>The UserAuth object returned by getUserAuth usually represents the
 * authenticated user and determines the rights that user has.
 *
 * @author Mike Douglass   douglm  rpi.edu
 */
public interface Calintf
        extends CoreCalendarsI, CoreEventsI, CoreFilterDefsI, CoreUserAuthI {
  /** Must be called to initialize the new object.
   *
   * @param logId for tracing
   * @param configs  so we can get at configurations
   * @param principalInfo    Required for access evaluation.
   * @param url         String url to which we are connecting
   * @param publicAdmin boolean true if this is a public events admin app
   * @param publicSubmission true for the submit app
   * @param sessionless true if this is a sessionless client
   * @throws CalFacadeException
   */
  void init(String logId,
            Configurations configs,
            PrincipalInfo principalInfo,
            String url,
            boolean publicAdmin,
            boolean publicSubmission,
            boolean sessionless) throws CalFacadeException;

  /**
   *
   * @return boolean true if super user
   */
  boolean getSuperUser();

  /**
   *
   * @return a label
   */
  String getLogId();

  /**
   *
   * @return an augmented label
   */
  String getTraceId();

  /** Updated every time state is changed. Not necessarily an
   * indication of idleness - it depends on state being updated,
   *
   * @return UTC time state was last changed.
   */
  String getLastStateTime();

  /**
   *
   * @param val a hopefully informative message
   */
  void setState(String val);

  /**
   *
   * @return a hopefully informative message
   */
  String getState();

  /** Get the current system (not db) stats
   *
   * @return BwStats object
   * @throws CalFacadeException if not admin
   */
  BwStats getStats() throws CalFacadeException;

  /** Enable/disable db statistics
   *
   * @param enable       boolean true to turn on db statistics collection
   * @throws CalFacadeException if not admin
   */
  void setDbStatsEnabled(boolean enable) throws CalFacadeException;

  /**
   *
   * @return boolean true if statistics collection enabled
   * @throws CalFacadeException if not admin
   */
  boolean getDbStatsEnabled() throws CalFacadeException;

  /** Dump db statistics
   *
   * @throws CalFacadeException if not admin
   */
  void dumpDbStats() throws CalFacadeException;

  /** Get db statistics
   *
   * @return Collection of BwStats.StatsEntry objects
   * @throws CalFacadeException if not admin
   */
  Collection<StatsEntry> getDbStats() throws CalFacadeException;

  /** Get information about this interface
   *
   * @return CalintfInfo
   * @throws CalFacadeException
   */
  CalintfInfo getInfo() throws CalFacadeException;

  /** Signal the start of a sequence of operations. These overlap transactions
   * in that there may be 0 to many transactions started and ended within an
   * open/close call and many open/close calls within a transaction.
   *
   * @param webMode  true for long-running multi request conversations.
   * @param forRestore true if this is for a system restore
   * @param indexRebuild  true if we are rebuilding the index.
   * @throws CalFacadeException
   */
  void open(boolean webMode,
            boolean forRestore,
            boolean indexRebuild) throws CalFacadeException;

  /** Call on the way out after handling a request..
   *
   * @throws CalFacadeException
   */
  void close() throws CalFacadeException;

  /** Start a (possibly long-running) transaction. In the web environment
   * this might do nothing. The endTransaction method should in some way
   * check version numbers to detect concurrent updates and fail with an
   * exception.
   *
   * @throws CalFacadeException
   */
  void beginTransaction() throws CalFacadeException;

  /** End a (possibly long-running) transaction. In the web environment
   * this should in some way check version numbers to detect concurrent updates
   * and fail with an exception.
   *
   * @throws CalFacadeException
   */
  void endTransaction() throws CalFacadeException;

  /** Call if there has been an error during an update process.
   *
   * @throws CalFacadeException
   */
  void rollbackTransaction() throws CalFacadeException;

  /**
   * @return boolean true if open and rolled back
   * @throws CalFacadeException
   */
  boolean isRolledback() throws CalFacadeException;

  /** Flush queued operations.
   *
   * @throws CalFacadeException
   */
  void flush() throws CalFacadeException;

  /**
   * Get the set of active transactions
   * @return set
   * @throws CalFacadeException
   */
  Collection<? extends Calintf> active() throws CalFacadeException;

  /**
   *
   * @return time in millis we started the transaction
   * @throws CalFacadeException
   */
  long getStartMillis() throws CalFacadeException;

  /** Only valid during a transaction.
   *
   * @return a timestamp from the db
   * @throws CalFacadeException
   */
  Timestamp getCurrentTimestamp() throws CalFacadeException;

  /** Call to reassociate an entity with the current database session
   *
   * @param val
   * @throws CalFacadeException
   */
  void reAttach(BwDbentity val) throws CalFacadeException;

  /** Get the current system pars
   *
   * @return SystemProperties object
   * @throws CalFacadeException if not admin
   */
  BasicSystemProperties getSyspars() throws CalFacadeException;

  /**
   * @return the indexer
   * @throws CalFacadeException
   */
  BwIndexer getPublicIndexer() throws CalFacadeException;

  /** Get a non-public indexer for a principal
   *
   * @param principal
   * @return the indexer
   * @throws CalFacadeException
   */
  BwIndexer getIndexer(BwPrincipal principal) throws CalFacadeException;

  /** Method which allows us to specify the index root.
   *
   * @param principal
   * @param indexRoot
   * @return the indexer
   * @throws CalFacadeException
   */
  BwIndexer getIndexer(BwPrincipal principal,
                       final String indexRoot) throws CalFacadeException;

  /* ====================================================================
   *                   Notifications
   * ==================================================================== */

  /** Called to notify container that an event occurred. This method should
   * queue up notifications until after transaction commit as consumers
   * should only receive notifications when the actual data has been written.
   *
   * @param ev
   * @throws CalFacadeException
   */
  void postNotification(final SysEventBase ev) throws CalFacadeException;

  /** Called to flush any queued notifications. Called by the commit
   * process.
   *
   * @throws CalFacadeException
   */
  void flushNotifications() throws CalFacadeException;

  /** Clear any queued notifications without posting. Called by the commit
   * process.
   *
   * @throws CalFacadeException
   */
  void clearNotifications() throws CalFacadeException;

  /* ====================================================================
   *                   Access
   * ==================================================================== */

  /** Change the access to the given calendar entity.
   *
   * @param ent      BwShareableDbentity
   * @param aces     Collection of ace
   * @param replaceAll true to replace the entire access list.
   * @throws CalFacadeException
   */
  void changeAccess(BwShareableDbentity ent,
                    Collection<Ace> aces,
                    boolean replaceAll) throws CalFacadeException;

  /** Remove any explicit access for the given who to the given calendar entity.
   *
   * @param ent      BwShareableDbentity
   * @param who      AceWho
   * @throws CalFacadeException
   */
  void defaultAccess(BwShareableDbentity ent,
                     AceWho who) throws CalFacadeException;

  /** Return a Collection of the objects after checking access
   *
   * @param ents          Collection of BwShareableDbentity
   * @param desiredAccess access we want
   * @param alwaysReturn boolean flag behaviour on no access
   * @return Collection   of checked objects
   * @throws CalFacadeException for no access or other failure
   */
  Collection<? extends BwShareableDbentity<? extends Object>>
  checkAccess(Collection<? extends BwShareableDbentity<? extends Object>> ents,
              int desiredAccess,
              boolean alwaysReturn)
          throws CalFacadeException;

  /** Check the access for the given entity. Returns the current access
   * or null or optionally throws a no access exception.
   *
   * @param ent
   * @param desiredAccess
   * @param returnResult
   * @return CurrentAccess
   * @throws CalFacadeException if returnResult false and no access
   */
  CurrentAccess checkAccess(BwShareableDbentity ent, int desiredAccess,
                            boolean returnResult) throws CalFacadeException;

  /* ====================================================================
   *                   Alarms
   * ==================================================================== */

  /** Return all unexpired alarms before a given time. If time is 0 all
   * unexpired alarms will be retrieved.
   *
   * <p>Any cancelled alarms will be excluded from the result.
   *
   * <p>Typically the system will call this with a time set into the near future
   * and then queue up alarms that are near to triggering.
   *
   * @param triggerTime
   * @return Collection of unexpired alarms.
   * @throws CalFacadeException
   */
  Collection<BwAlarm> getUnexpiredAlarms(long triggerTime)
          throws CalFacadeException;

  /** Given an alarm return the associated event(s)
   *
   * @param alarm
   * @return an event.
   * @throws CalFacadeException
   */
  Collection<BwEvent> getEventsByAlarm(BwAlarm alarm)
          throws CalFacadeException;

  /* ====================================================================
   *                   Free busy
   * ==================================================================== */

  /** Get the fee busy for calendars (if cal != null) or for a principal.
   *
   * @param cals
   * @param who
   * @param start
   * @param end
   * @param returnAll
   * @param ignoreTransparency
   * @return  BwFreeBusy object representing the calendar (or principal's)
   *          free/busy
   * @throws CalFacadeException
   */
  BwEvent getFreeBusy(Collection<BwCalendar> cals, BwPrincipal who,
                      BwDateTime start, BwDateTime end,
                      boolean returnAll,
                      boolean ignoreTransparency)
          throws CalFacadeException;

  /* ====================================================================
   *                       General db methods
   * ==================================================================== */

  /**
   * @param val
   * @throws CalFacadeException
   */
  void saveOrUpdate(final BwUnversionedDbentity val) throws CalFacadeException;

  /**
   * @param val
   * @throws CalFacadeException
   */
  void delete(final BwUnversionedDbentity val) throws CalFacadeException;

  /**
   * @param val
   * @return - merged entity
   * @throws CalFacadeException
   */
  BwUnversionedDbentity merge(final BwUnversionedDbentity val) throws CalFacadeException;

  /* ====================================================================
   *                       dump/restore methods
   *
   * These are used to handle the needs of dump/restore. Perhaps we
   * can eliminate the need at some point...
   * ==================================================================== */

  /**
   *
   * @param className of objects
   * @return collection
   * @throws CalFacadeException
   */
  Iterator getObjectIterator(final String className) throws CalFacadeException;

  /**
   * @return annotations - not recurrence overrides
   * @throws CalFacadeException
   */
  Iterator<BwEventAnnotation> getEventAnnotations() throws CalFacadeException;

  /**
   * @param ev the master
   * @return overrides for event
   * @throws CalFacadeException
   */
  Collection<BwEventAnnotation> getEventOverrides(final BwEvent ev) throws CalFacadeException;

  /* ====================================================================
   *                       principals + prefs
   * ==================================================================== */

  /** Find the principal with the given href.
   *
   * @param href          String principal hierarchy path
   * @return BwPrincipal  representing the principal or null if not there
   * @throws CalFacadeException
   */
  BwPrincipal getPrincipal(final String href) throws CalFacadeException;

  /** Get a partial list of principal hrefs.
   *
   * @param start         Position to start
   * @param count         Number we want
   * @return list of hrefs - null for no more
   * @throws CalFacadeException
   */
  List<String> getPrincipalHrefs(final int start,
                                 final int count) throws CalFacadeException;

  /** Fetch the preferences for the given principal.
   *
   * @param principalHref
   * @return the preferences for the principal
   * @throws CalFacadeException
   */
  BwPreferences getPreferences(final String principalHref) throws CalFacadeException;

  /* ====================================================================
   *                       adminprefs
   * ==================================================================== */

  /* XXX These should no be required - there was some issue with earlier hibernate (maybe).
   */

  /** Remove any refs to this object
   *
   * @param val
   * @throws CalFacadeException
   */
  void removeFromAllPrefs(final BwShareableDbentity val) throws CalFacadeException;

  /* ====================================================================
   *                       groups
   * ==================================================================== */

  /* XXX This should really be some sort of directory function - perhaps
   * via carddav
   */

  /** Find a group given its account name
   *
   * @param  account           String group name
   * @param admin          true for an admin group
   * @return BwGroup        group object
   * @exception CalFacadeException If there's a problem
   */
  BwGroup findGroup(final String account,
                    boolean admin) throws CalFacadeException;

  /**
   * @param group
   * @param admin          true for an admin group
   * @return Collection
   * @throws CalFacadeException
   */
  Collection<BwGroup> findGroupParents(final BwGroup group,
                                       boolean admin) throws CalFacadeException;

  /**
   * @param group
   * @param admin          true for an admin group
   * @throws CalFacadeException
   */
  void updateGroup(final BwGroup group,
                   boolean admin) throws CalFacadeException;

  /** Delete a group
   *
   * @param  group           BwGroup group object to delete
   * @param admin          true for an admin group
   * @exception CalFacadeException If there's a problem
   */
  void removeGroup(BwGroup group,
                   boolean admin) throws CalFacadeException;

  /** Add a member to a group
   *
   * @param group          a group principal
   * @param val             BwPrincipal new member
   * @param admin          true for an admin group
   * @exception CalFacadeException   For invalid usertype values.
   */
  void addMember(BwGroup group,
                 BwPrincipal val,
                 boolean admin) throws CalFacadeException;

  /** Remove a member from a group
   *
   * @param group          a group principal
   * @param val            BwPrincipal new member
   * @param admin          true for an admin group
   * @exception CalFacadeException   For invalid usertype values.
   */
  void removeMember(BwGroup group,
                    BwPrincipal val,
                    boolean admin) throws CalFacadeException;

  /** Get the direct members of the given group.
   *
   * @param  group           BwGroup group object to add
   * @param admin          true for an admin group
   * @return list of members
   * @throws CalFacadeException
   */
  Collection<BwPrincipal> getMembers(BwGroup group,
                                     boolean admin) throws CalFacadeException;

  /** Return all groups to which this user has some access. Never returns null.
   *
   * @param admin          true for an admin group
   * @return Collection    of BwGroup
   * @throws CalFacadeException
   */
  Collection<BwGroup> getAllGroups(boolean admin) throws CalFacadeException;

  /** Return all groups of which the given principal is a member. Never returns null.
   *
   * <p>Does not check the returned groups for membership of other groups.
   *
   * @param val            a principal
   * @param admin          true for an admin group
   * @return Collection    of BwGroup
   * @throws CalFacadeException
   */
  Collection<BwGroup> getGroups(BwPrincipal val,
                                boolean admin) throws CalFacadeException;

  /* ====================================================================
   *                       calendar suites
   * ==================================================================== */

  /** Get a detached instance of the calendar suite given the 'owning' group
   *
   * @param  group     BwAdminGroup
   * @return BwCalSuite null for unknown calendar suite
   * @throws CalFacadeException
   */
  BwCalSuite get(BwAdminGroup group) throws CalFacadeException;

  /** Get a (live) calendar suite given the name
   *
   * @param  name     String name of calendar suite
   * @return BwCalSuiteWrapper null for unknown calendar suite
   * @throws CalFacadeException
   */
  BwCalSuite getCalSuite(String name) throws CalFacadeException;

  /** Get calendar suites to which this user has access
   *
   * @return Collection     of BwCalSuiteWrapper
   * @throws CalFacadeException
   */
  Collection<BwCalSuite> getAllCalSuites() throws CalFacadeException;

   /* ====================================================================
    *                   Event Properties Factories
    * ==================================================================== */

  /** Return an event properties handler.
   *
   * @param cl
   * @return EventProperties
   * @throws CalFacadeException
   */
  <T extends BwEventProperty> CoreEventPropertiesI<T>  getEvPropsHandler(final Class<T> cl)
          throws CalFacadeException;

   /* ====================================================================
    *                       resources
    * ==================================================================== */

  /** Fetch a resource object.
   *
   * @param name
   * @param coll
   * @param desiredAccess
   * @return BwResource object or null
   * @throws CalFacadeException
   */
  BwResource getResource(final String name,
                         final BwCalendar coll,
                         final int desiredAccess) throws CalFacadeException;

  /** Get resource content given the resource. It will be set in the resource
   * object
   *
   * @param  val BwResource
   * @throws CalFacadeException
   */
  void getResourceContent(BwResource val) throws CalFacadeException;

  /** Get resources to which this user has access - content is not fetched.
   *
   * @param  path           String path to containing collection
   * @param forSynch
   * @param token
   * @return List     of BwResource
   * @throws CalFacadeException
   */
  List<BwResource> getAllResources(String path,
                                   final boolean forSynch,
                                   final String token) throws CalFacadeException;

   /* ====================================================================
    *                       system parameters
    * ==================================================================== */

  /** Get the system pars given name - will update cache object if the name is
   * the current system name.
   *
   * @param name
   * @return BwSystem object
   * @throws CalFacadeException if not admin
   */
  BwSystem getSyspars(String name) throws CalFacadeException;
}

