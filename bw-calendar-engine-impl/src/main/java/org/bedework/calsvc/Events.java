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

import org.bedework.access.AccessPrincipal;
import org.bedework.access.Acl.CurrentAccess;
import org.bedework.access.PrivilegeDefs;
import org.bedework.calcorei.CoreEventInfo;
import org.bedework.calcorei.CoreEventsI.InternalEventKey;
import org.bedework.calcorei.CoreEventsI.UpdateEventResult;
import org.bedework.caldav.util.filter.BooleanFilter;
import org.bedework.caldav.util.filter.FilterBase;
import org.bedework.calfacade.BwAlarm;
import org.bedework.calfacade.BwAttendee;
import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.BwCategory;
import org.bedework.calfacade.BwContact;
import org.bedework.calfacade.BwDateTime;
import org.bedework.calfacade.BwDuration;
import org.bedework.calfacade.BwEvent;
import org.bedework.calfacade.BwEventAnnotation;
import org.bedework.calfacade.BwEventProxy;
import org.bedework.calfacade.BwLocation;
import org.bedework.calfacade.BwOrganizer;
import org.bedework.calfacade.BwPrincipalInfo;
import org.bedework.calfacade.BwXproperty;
import org.bedework.calfacade.CalFacadeDefs;
import org.bedework.calfacade.RecurringRetrievalMode;
import org.bedework.calfacade.RecurringRetrievalMode.Rmode;
import org.bedework.calfacade.exc.CalFacadeAccessException;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.exc.CalFacadeForbidden;
import org.bedework.calfacade.ical.BwIcalPropertyInfo;
import org.bedework.calfacade.ical.BwIcalPropertyInfo.BwIcalPropertyInfoEntry;
import org.bedework.calfacade.ifs.Directories;
import org.bedework.calfacade.svc.BwPreferences;
import org.bedework.calfacade.svc.EventInfo;
import org.bedework.calfacade.svc.EventInfo.UpdateResult;
import org.bedework.calfacade.util.ChangeTable;
import org.bedework.calfacade.util.ChangeTableEntry;
import org.bedework.calsvc.scheduling.SchedulingIntf;
import org.bedework.calsvci.Categories;
import org.bedework.calsvci.EventProperties;
import org.bedework.calsvci.EventProperties.EnsureEntityExistsResult;
import org.bedework.calsvci.EventsI;
import org.bedework.icalendar.IcalTranslator;
import org.bedework.icalendar.IcalUtil;
import org.bedework.icalendar.Icalendar;
import org.bedework.icalendar.RecurUtil;
import org.bedework.icalendar.RecurUtil.Recurrence;
import org.bedework.sysevents.events.EntityFetchEvent;
import org.bedework.sysevents.events.SysEventBase.SysCode;
import org.bedework.util.calendar.IcalDefs;
import org.bedework.util.calendar.PropertyIndex.PropertyInfoIndex;
import org.bedework.util.misc.Util;
import org.bedework.util.xml.tagdefs.CaldavTags;
import org.bedework.util.xml.tagdefs.NamespaceAbbrevs;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VVoter;
import net.fortuna.ical4j.model.parameter.CuType;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Voter;

import java.io.StringReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;
import javax.xml.ws.Holder;

import static org.bedework.calcorei.CoreCalendarsI.GetSpecialCalendarResult;

/** This acts as an interface to the database for subscriptions.
 *
 * @author Mike Douglass       douglm - rpi.edu
 */
class Events extends CalSvcDb implements EventsI {
  Events(final CalSvc svci) {
    super(svci);
  }

  @Override
  public Collection<EventInfo> getByUid(final String colPath,
                                        final String guid,
                                        final String recurrenceId,
                                        final RecurringRetrievalMode recurRetrieval)
          throws CalFacadeException {
    Collection<EventInfo> res = postProcess(getCal().getEvent(colPath,
                                                              guid));

    int num = 0;

    if (res != null) {
      num = res.size();
    }

    if (num == 0) {
      return res;
    }

    getSvc().postNotification(new EntityFetchEvent(SysCode.ENTITY_FETCHED, num));

    if ((recurrenceId == null) &&
            ((recurRetrieval == null) ||
            (recurRetrieval.mode != Rmode.expanded))) {
      return res;
    }

    /* For an expansion replace the result with a set of expansions
     */
    if (recurrenceId == null) {
      return processExpanded(res, recurRetrieval);
    }

    if (num > 1) {
      throw new CalFacadeException("cannot return rid for multiple events");
    }

    final Collection<EventInfo> eis = new ArrayList<>();

    final EventInfo ei = makeInstance(res.iterator().next(), recurrenceId);

    if (ei != null) {
      eis.add(ei);
    }
    return eis;
  }

  private Collection<EventInfo> processExpanded(final Collection<EventInfo> events,
                                                final RecurringRetrievalMode recurRetrieval)
          throws CalFacadeException {
    Collection<EventInfo> res = new ArrayList<>();

    for (EventInfo ei: events) {
      BwEvent ev = ei.getEvent();

      if (!ev.getRecurring()) {
        res.add(ei);
        continue;
      }

      CurrentAccess ca = ei.getCurrentAccess();
      Set<EventInfo> oveis = ei.getOverrides();

      if (!Util.isEmpty(oveis)) {
        for (EventInfo oei: oveis) {
          if (oei.getEvent().inDateTimeRange(recurRetrieval.start.getDate(),
                                             recurRetrieval.end.getDate())) {
            oei.setRetrievedEvent(ei);
            res.add(oei);
          }
        }
      }

      /* Generate non-overridden instances. */
      Collection<Recurrence> instances =
              RecurUtil.getRecurrences(ei,
                                       getAuthpars().getMaxYears(),
                                       getAuthpars().getMaxInstances(),
                                       recurRetrieval.start.getDate(),
                                       recurRetrieval.end.getDate());

      for (Recurrence rec: instances) {
        if (rec.override != null) {
          continue;
        }

        BwEventAnnotation ann = new BwEventAnnotation();

        ann.setDtstart(rec.start);
        ann.setDtend(rec.end);
        ann.setRecurrenceId(rec.recurrenceId);
        ann.setOwnerHref(ev.getOwnerHref());
        ann.setOverride(true);  // Call it an override
        ann.setTombstoned(false);
        ann.setName(ev.getName());
        ann.setUid(ev.getUid());
        ann.setTarget(ev);
        ann.setMaster(ev);
        BwEvent proxy = new BwEventProxy(ann);
        EventInfo oei = new EventInfo(proxy);
        oei.setCurrentAccess(ei.getCurrentAccess());
        oei.setRetrievedEvent(ei);

        res.add(oei);
      }
    }

    return res;
  }

  private EventInfo makeInstance(final EventInfo ei,
                                 final String recurrenceId)
          throws CalFacadeException {
    final BwEvent ev = ei.getEvent();

    if (!ev.getRecurring()) {
      return ei;
    }

    /* See if it's in the overrides */

    if (!Util.isEmpty(ei.getOverrides())) {
      for (final EventInfo oei: ei.getOverrides()) {
        if (oei.getEvent().getRecurrenceId().equals(recurrenceId)) {
          oei.setRetrievedEvent(ei);
          return oei;
        }
      }
    }

    /* Not in the overrides - generate an instance */
    final BwDateTime rstart;
    final boolean dateOnly = ev.getDtstart().getDateType();

    if (dateOnly) {
      rstart = BwDateTime.makeBwDateTime(true,
                                         recurrenceId.substring(0, 8),
                                         null);
    } else {
      final String stzid = ev.getDtstart().getTzid();

      DateTime dt = null;
      try {
        dt = new DateTime(recurrenceId);
      } catch (ParseException pe) {
        throw new CalFacadeException(pe);
      }
      DtStart ds = ev.getDtstart().makeDtStart();
      dt.setTimeZone(ds.getTimeZone());

      rstart = BwDateTime.makeBwDateTime(dt);
    }

    BwDateTime rend = rstart.addDuration(
            BwDuration.makeDuration(ev.getDuration()));

    BwEventAnnotation ann = new BwEventAnnotation();

    ann.setDtstart(rstart);
    ann.setDtend(rend);
    ann.setRecurrenceId(recurrenceId);
    ann.setOwnerHref(ev.getOwnerHref());
    ann.setOverride(true);  // Call it an override
    ann.setTombstoned(false);
    ann.setName(ev.getName());
    ann.setUid(ev.getUid());
    ann.setTarget(ev);
    ann.setMaster(ev);
    BwEvent proxy = new BwEventProxy(ann);
    EventInfo oei = new EventInfo(proxy);
    oei.setCurrentAccess(ei.getCurrentAccess());

    oei.setRetrievedEvent(ei);
    return oei;
  }

  @Override
  public EventInfo get(final String colPath,
                       final String name) throws CalFacadeException {
    return get(colPath, name, null);
  }

  @Override
  public EventInfo get(final String colPath,
                       final String name,
                       final String recurrenceId)
          throws CalFacadeException {
    final EventInfo res =
            postProcess(getCal().getEvent(colPath,
                                          name,
                                          RecurringRetrievalMode.overrides));

    int num = 0;

    if (res != null) {
      num = 1;
    }
    getSvc().postNotification(new EntityFetchEvent(SysCode.ENTITY_FETCHED, num));

    if (res == null) {
      return null;
    }

    if (recurrenceId == null) {
      return res;
    }

    return makeInstance(res, recurrenceId);
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.EventsI#getEvents(org.bedework.calfacade.BwCalendar, org.bedework.caldav.util.filter.Filter, org.bedework.calfacade.BwDateTime, org.bedework.calfacade.BwDateTime, java.util.List, org.bedework.calfacade.RecurringRetrievalMode)
   */
  @Override
  public Collection<EventInfo> getEvents(final BwCalendar cal, final FilterBase filter,
                                         final BwDateTime startDate, final BwDateTime endDate,
                                         final List<BwIcalPropertyInfoEntry> retrieveList,
                                         final RecurringRetrievalMode recurRetrieval)
          throws CalFacadeException {
    Collection<BwCalendar> cals = null;

    if (cal != null) {
      cals = new ArrayList<BwCalendar>();
      cals.add(cal);
    }

    Collection<EventInfo> res =  getMatching(cals, filter, startDate, endDate,
                                             retrieveList,
                                             recurRetrieval, false);

    int num = 0;

    if (res != null) {
      num = res.size();
    }
    getSvc().postNotification(new EntityFetchEvent(SysCode.ENTITY_FETCHED, num));

    return res;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.EventsI#delete(org.bedework.calfacade.svc.EventInfo, boolean)
   */
  @Override
  public boolean delete(final EventInfo ei,
                        final boolean sendSchedulingMessage) throws CalFacadeException {
    return delete(ei, false, sendSchedulingMessage);
  }

  @Override
  public UpdateResult add(final EventInfo ei,
                          final boolean noInvites,
                          final boolean scheduling,
                          final boolean autoCreateCollection,
                          final boolean rollbackOnError) throws CalFacadeException {
    try {
      final UpdateResult updResult = ei.getUpdResult();
      updResult.adding = true;
      updResult.hasChanged = true;

      final BwEvent event = ei.getEvent();

      adjustEntities(ei);

      final BwPreferences prefs = getSvc().getPrefsHandler().get();
      if (prefs != null) {
        final Collection<BwCategory> cats = getSvc().getCategoriesHandler().
                get(prefs.getDefaultCategoryUids());

        for (final BwCategory cat: cats) {
          event.addCategory(cat);
        }
      }

      assignGuid(event); // Or just validate?

      updateEntities(updResult, event);

      BwCalendar cal = validate(event, autoCreateCollection);

      BwEventProxy proxy = null;
      BwEvent override = null;

      if (event instanceof BwEventProxy) {
        proxy = (BwEventProxy)event;
        override = proxy.getRef();
        setupSharableEntity(override, getPrincipal().getPrincipalRef());
      } else {
        setupSharableEntity(event, getPrincipal().getPrincipalRef());

        if (ei.getNumContainedItems() > 0) {
          for (final EventInfo aei: ei.getContainedItems()) {
            final BwEvent av = aei.getEvent();
            av.setParent(event);

            setupSharableEntity(av,
                                getPrincipal().getPrincipalRef());
          }
        }
      }

      final BwCalendar undereffedCal = cal;

      if (cal.getInternalAlias()) {
        /* Resolve the alias and put the event in it's proper place */

        //XXX This is probably OK for non-public admin
        final boolean setCats = getSvc().getPars().getPublicAdmin();

        if (!setCats) {
          cal = getCols().resolveAlias(cal, true, false);
        } else {
          while (true) {
            final Set<BwCategory> cats = cal.getCategories();

            for (final BwCategory cat: cats) {
              event.addCategory(cat);
            }

            if (!cal.getInternalAlias()) {
              break;
            }

            cal = getCols().resolveAlias(cal, false, false);
          }
        }

        event.setColPath(cal.getPath());
      }

      if (!cal.getCalendarCollection()) {
        throw new CalFacadeAccessException();
      }

      if (!event.getPublick() && Util.isEmpty(event.getAlarms())) {
        setDefaultAlarms(ei, undereffedCal);
      }

      boolean schedulingObject = false;

      if (cal.getCollectionInfo().scheduling &&
          (event.getOrganizerSchedulingObject() ||
           event.getAttendeeSchedulingObject())) {
        schedulingObject = true;
      }

      event.setDtstamps(getCurrentTimestamp());
      if (schedulingObject) {
        event.updateStag(getCurrentTimestamp());
      }

      /* All Overrides go in same calendar and have same name */

      Collection<BwEventProxy> overrides = ei.getOverrideProxies();
      if (overrides != null) {
        for (BwEventProxy ovei: overrides) {
          setScheduleState(ovei);

          ovei.setDtstamps(getCurrentTimestamp());

          if (cal.getCollectionInfo().scheduling &&
              (ovei.getOrganizerSchedulingObject() ||
               ovei.getAttendeeSchedulingObject())) {
            schedulingObject = true;
          }

          if (schedulingObject) {
            ovei.updateStag(getCurrentTimestamp());
          }

          BwEventAnnotation ann = ovei.getRef();
          ann.setColPath(event.getColPath());
          ann.setName(event.getName());
        }
      }

      if (event.getOrganizerSchedulingObject()) {
        // Set RSVP on all attendees with PARTSTAT = NEEDS_ACTION
        for (final BwAttendee att: event.getAttendees()) {
          if (att.getPartstat() == IcalDefs.partstatValNeedsAction) {
            att.setRsvp(true);
          }
        }
      }

      UpdateEventResult uer = getCal().addEvent(ei,
                                                scheduling,
                                                rollbackOnError);

      if (ei.getNumContainedItems() > 0) {
        for (final EventInfo oei: ei.getContainedItems()) {
          oei.getEvent().setName(event.getName());
          final UpdateEventResult auer =
                  getCal().addEvent(oei,
                                    scheduling, rollbackOnError);
          if (auer.errorCode != null) {
            //?
          }
        }
      }

      updResult.failedOverrides = uer.failedOverrides;

      if (!noInvites) {
        if (event.getAttendeeSchedulingObject()) {
          // Attendee replying?
          updResult.reply = true;
        }

        if (cal.getCollectionInfo().scheduling &&
            schedulingObject) {
          final SchedulingIntf sched = (SchedulingIntf)getSvc().getScheduler();

          sched.implicitSchedule(ei,
                                 false /*noInvites*/);

          /* We assume we don't need to update again to set attendee status
           * Trying to do an update results in duplicate key errors.
           *
           * If it turns out the scgedule status is not getting persisted in the
           * calendar entry then we need to find a way to set just that value in
           * already persisted entity.
           */
        }
      }

      return updResult;
    } catch (final Throwable t) {
      if (debug) {
        error(t);
      }
      getSvc().rollbackTransaction();
      if (t instanceof CalFacadeException) {
        throw (CalFacadeException)t;
      }

      throw new CalFacadeException(t);
    }
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.EventsI#update(org.bedework.calfacade.svc.EventInfo, boolean)
   */
  @Override
  public UpdateResult update(final EventInfo ei,
                             final boolean noInvites) throws CalFacadeException {
    return update(ei, noInvites, null);
  }

  @Override
  public UpdateResult update(final EventInfo ei,
                             final boolean noInvites,
                             final String fromAttUri) throws CalFacadeException {
    try {
      final BwEvent event = ei.getEvent();
      event.setDtstamps(getCurrentTimestamp());

      final UpdateResult updResult = ei.getUpdResult();

      updateEntities(updResult, event);

      final BwCalendar cal = validate(event,false);
      adjustEntities(ei);

      boolean organizerSchedulingObject = false;
      boolean attendeeSchedulingObject = false;

      if (cal.getCollectionInfo().scheduling) {
        organizerSchedulingObject = event.getOrganizerSchedulingObject();
        attendeeSchedulingObject = event.getAttendeeSchedulingObject();
      }

      boolean schedulingObject = organizerSchedulingObject ||
                                 attendeeSchedulingObject;

      if (event.getSignificantChange() && schedulingObject) {
        event.updateStag(getCurrentTimestamp());
      }

      boolean changed = checkChanges(ei,
                                     organizerSchedulingObject,
                                     attendeeSchedulingObject) ||
                        ei.getOverridesChanged();

      /* TODO - this is wrong.
         At the very least we should only reschedule the override that changed.
         However adding an override looks like a change for all the fields
         copied in. There should only be a change if the value is different
       */
      boolean doReschedule = ei.getUpdResult().doReschedule;

      if (ei.getNumOverrides() > 0) {
        for (final EventInfo oei: ei.getOverrides()) {
          setScheduleState(oei.getEvent());

          if (cal.getCollectionInfo().scheduling &&
               oei.getEvent().getAttendeeSchedulingObject()) {
            schedulingObject = true;
            attendeeSchedulingObject = true;
            // Shouldn't need to check organizer - it's set in the master even
            // if suppressed.
          }

          if (checkChanges(oei,
                           organizerSchedulingObject,
                           attendeeSchedulingObject)) {
            changed = true;
          }

          if (schedulingObject) {
            oei.getEvent().updateStag(getCurrentTimestamp());
          }

          doReschedule = doReschedule || oei.getUpdResult().doReschedule;
        }
      }

      if (!changed) {
        return ei.getUpdResult();
      }

      /* TODO - fix this */
//      if (doReschedule) {
  //      getSvc().getScheduler().setupReschedule(ei);
    //  }

      final UpdateEventResult uer = getCal().updateEvent(ei);

      updResult.addedInstances = uer.added;
      updResult.updatedInstances = uer.updated;
      updResult.deletedInstances = uer.deleted;

      updResult.fromAttUri = fromAttUri;

      if (!noInvites && schedulingObject) {
        if (organizerSchedulingObject) {
          // Set RSVP on all attendees with PARTSTAT = NEEDS_ACTION
          for (final BwAttendee att: event.getAttendees()) {
            if (att.getPartstat().equals(IcalDefs.partstatValNeedsAction)) {
              att.setRsvp(true);
            }
          }
        }

        boolean sendit = organizerSchedulingObject || updResult.reply;

        if (!sendit) {
          if (!Util.isEmpty(ei.getOverrides())) {
            for (final EventInfo oei: ei.getOverrides()) {
              if (oei.getUpdResult().reply) {
                sendit = true;
                break;
              }
            }
          }
        }

        if (sendit) {
          final SchedulingIntf sched = (SchedulingIntf)getSvc().getScheduler();

          sched.implicitSchedule(ei,
                                 false /*noInvites */);

          /* We assume we don't need to update again to set attendee status
           * Trying to do an update results in duplicate key errors.
           *
           * If it turns out the scgedule status is not getting persisted in the
           * calendar entry then we need to find a way to set just that value in
           * already persisted entity.
           */
        }
      }

      /*
      final boolean vpoll = event.getEntityType() == IcalDefs.entityTypeVpoll;

      if (vpoll && (updResult.pollWinner != null)) {
        // Add the winner and send it out
        final Map<Integer, Component> comps =
                IcalUtil.parseVpollCandidates(event);

        final Component comp = comps.get(updResult.pollWinner);

        if (comp != null) {
          final IcalTranslator trans =
                  new IcalTranslator(getSvc().getIcalCallback());
          final String colPath = getSvc().getCalendarsHandler().getPreferred(
                  comp.getName());
          final BwCalendar col = getSvc().getCalendarsHandler().get(colPath);
          final Icalendar ical = trans.fromComp(col, comp, true, true);

          add(ical.getEventInfo(), false, false, true, true);
        }
      } */

      return updResult;
    } catch (final Throwable t) {
      getSvc().rollbackTransaction();
      if (t instanceof CalFacadeException) {
        throw (CalFacadeException)t;
      }

      throw new CalFacadeException(t);
    }
  }

  @SuppressWarnings("unchecked")
  private boolean checkChanges(final EventInfo ei,
                               final boolean organizerSchedulingObject,
                               final boolean attendeeSchedulingObject) throws CalFacadeException {
    final UpdateResult updResult = ei.getUpdResult();

    if (ei.getChangeset(getPrincipalHref()).isEmpty()) {
      // Forced update?
      updResult.hasChanged = true;
      if (attendeeSchedulingObject) {
        // Attendee replying?
        /* XXX We should really check to see if the value changed here -
         */
        updResult.reply = true;
      }

      return true;
    }

    if (debug) {
      ei.getChangeset(getPrincipalHref()).dumpEntries();
    }

    final Collection<ChangeTableEntry> ctes =
            ei.getChangeset(getPrincipalHref()).getEntries();

    boolean sequenceChanged = false;

    for (final ChangeTableEntry cte: ctes) {
      if (!cte.getChanged()) {
        continue;
      }

      updResult.hasChanged = true;
      final PropertyInfoIndex pi = cte.getIndex();

      if (!organizerSchedulingObject &&
          pi.equals(PropertyInfoIndex.ORGANIZER)) {
        // Never valid
        throw new CalFacadeForbidden(CaldavTags.attendeeAllowed,
                                     "Cannot change organizer");
      }

      if (pi.equals(PropertyInfoIndex.ATTENDEE) ||
              pi.equals(PropertyInfoIndex.VOTER)) {
        updResult.addedAttendees = cte.getAddedValues();
        updResult.deletedAttendees = cte.getRemovedValues();

        if (attendeeSchedulingObject) {
          // Attendee replying?
          /* XXX We should really check to see if the value changed here -
           */
          updResult.reply = true;
        }
      }

      if (pi.equals(PropertyInfoIndex.POLL_WINNER)) {
        if (!attendeeSchedulingObject) {
          // Attendee replying?
          /* XXX We should really check to see if the value changed here -
           */
          updResult.pollWinner = ei.getEvent().getPollWinner();
        }
      }

      if (pi.equals(PropertyInfoIndex.POLL_ITEM)) {
        if (attendeeSchedulingObject) {
          // Attendee replying?
          /* XXX We should really check to see if the value changed here -
           */
          updResult.reply = true;
        }
      }

      if (organizerSchedulingObject) {
        if (pi.equals(PropertyInfoIndex.SEQUENCE)) {
          sequenceChanged = true;
        }

        final BwIcalPropertyInfoEntry pie =
                BwIcalPropertyInfo.getPinfo(cte.getIndex());
        if (pie.getReschedule()) {
          updResult.doReschedule = true;
        }
      }
    }

    BwEvent ev = ei.getEvent();

    if (!(ev instanceof BwEventProxy)) {
      if (organizerSchedulingObject && !sequenceChanged) {
        ev.setSequence(ev.getSequence() + 1);
      }
    }

    return updResult.hasChanged;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.EventsI#markDeleted(org.bedework.calfacade.BwEvent)
   */
  @Override
  public void markDeleted(final BwEvent event) throws CalFacadeException {
    /* Trash disabled
    if (getCal().checkAccess(event, PrivilegeDefs.privWrite, true).accessAllowed) {
      // Have write access - just set the flag and move it into the owners trash
      event.setDeleted(true);

      GetSpecialCalendarResult gscr = getCal().getSpecialCalendar(getUser(), //event.getOwner(),
                                          BwCalendar.calTypeTrash,
                                          true,
                                          PrivilegeDefs.privWriteContent);
      if (gscr.created) {
        getCal().flush();
      }
      event.setCalendar(gscr.cal);

      if (!event.getOwner().equals(getUser())) {
        // Claim ownership
        event.setOwner(getUser());
      }

      EventInfo ei = new EventInfo(event);

      /* Names have to be unique. Just keep extending the name out till it works. I guess
       * a better approach would be a random suffix.
       * /
      int limit = 100;
      for (int i = 0; i < limit; i++) {
        try {
          update(ei, false, null, null, null);
          break;
        } catch (CalFacadeDupNameException dup) {
          if ((i + 1) == limit) {
            throw dup;
          }
          event.setName("a" + event.getName());
        }
      }
      return;
    }
    */
    // Need to annotate it as deleted

    BwEventProxy proxy = BwEventProxy.makeAnnotation(event, event.getOwnerHref(),
                                                     false);

    // Where does the ref go? Not in the same calendar - we have no access

    BwCalendar cal = getCal().getSpecialCalendar(getPrincipal(),
                                     BwCalendar.calTypeDeleted,
                                     true, PrivilegeDefs.privRead).cal;
    proxy.setOwnerHref(getPrincipal().getPrincipalRef());
    proxy.setDeleted(true);
    proxy.setColPath(cal.getPath());
    add(new EventInfo(proxy), true, false, false, false);
  }

  @Override
  public CopyMoveStatus copyMoveNamed(final EventInfo fromEi,
                                      final BwCalendar to,
                                      String name,
                                      final boolean copy,
                                      final boolean overwrite,
                                      final boolean newGuidOK) throws CalFacadeException {
    BwEvent ev = fromEi.getEvent();
    String fromPath = ev.getColPath();

    boolean sameCal = fromPath.equals(to.getPath());

    if (name == null) {
      name = ev.getName();
    }

    if (sameCal && name.equals(ev.getName())) {
      // No-op
      return CopyMoveStatus.noop;
    }

    try {
      // Get the target
      final EventInfo destEi = get(to.getPath(), name);

      if (destEi != null) {
        if (!overwrite) {
          return CopyMoveStatus.destinationExists;
        }

        if (!destEi.getEvent().getUid().equals(ev.getUid())) {
          // Not allowed to change uid.
          return CopyMoveStatus.changedUid;
        }

        //deleteEvent(destEi.getEvent(), true);
      }

      if (!copy) {
        // Moving the event.

        if (!sameCal) {
          /* Not sure why I was doing a delete+add
          delete(from, false, false); // Delete unreffed

          if (destEi != null) {
            delete(destEi.getEvent(), false, false); // Delete unreffed
          }

          add(to, newEi, true);
          */

          BwCalendar from = getCols().get(fromPath);

          getCal().moveEvent(ev, from, to);

          getCal().touchCalendar(from);
        } else {
          // Just changing name
          ev.setName(name);
        }

        ev.updateStag(getCurrentTimestamp());
        update(fromEi, false, null);
      } else {
        // Copying the event.

        BwEvent newEvent = (BwEvent)ev.clone();
        newEvent.setName(name);

        // WebDAV ACL say's new event must not carry over access
        newEvent.setAccess(null);

        EventInfo newEi = new EventInfo(newEvent);

        if (fromEi.getOverrideProxies() != null) {
          for (BwEventProxy proxy: fromEi.getOverrideProxies()) {
            newEi.addOverride(new EventInfo(proxy.clone(newEvent, newEvent)));
          }
        }

        if (sameCal && newGuidOK) {
          // Assign a new guid
          newEvent.setUid(null);
          assignGuid(newEvent);
        }

        if (destEi != null) {
          delete(destEi, false);
        }

        newEvent.setColPath(to.getPath());
        newEvent.updateStag(getCurrentTimestamp());

        add(newEi, true, false, false, true);
      }

      if (destEi != null) {
        return CopyMoveStatus.ok;
      }

      return CopyMoveStatus.created;
    } catch (CalFacadeException cfe) {
      if (cfe.getMessage().equals(CalFacadeException.duplicateGuid)) {
        return CopyMoveStatus.duplicateUid;
      }

      throw cfe;
    }
  }

  @Override
  public void claim(final BwEvent ev) throws CalFacadeException {
    ev.setOwnerHref(null);
    ev.setCreatorHref(null);
    setupSharableEntity(ev, getPrincipal().getPrincipalRef());
  }

  /* ====================================================================
   *                   Package private methods
   * ==================================================================== */

  void updateEntities(final UpdateResult updResult,
                      final BwEvent event) throws CalFacadeException {

    BwContact ct = event.getContact();

    if (ct != null) {
      final EnsureEntityExistsResult<BwContact> eeers =
        getSvc().getContactsHandler().ensureExists(ct,
                                                   ct.getOwnerHref());

      if (eeers.added) {
        updResult.contactsAdded++;
      }

      // XXX only do this if we know it changed
      event.setContact(eeers.entity);
    }

    final BwLocation loc = event.getLocation();

    if (loc != null) {
      final EnsureEntityExistsResult<BwLocation> eeerl =
              getSvc().getLocationsHandler().ensureExists(loc,
                                                          loc.getOwnerHref());

      if (eeerl.added) {
        updResult.locationsAdded++;
      }

      // XXX only do this if we know it changed
      event.setLocation(eeerl.entity);
    }
  }

  /** Return all keys or all with a lastmod greater than or equal to that supplied.
   *
   * <p>Note the lastmod has a coarse granularity so it may need to be backed off
   * to ensure all events are covered if doing batches.
   *
   * @param lastmod allows us to redo the search after we have updated timezones
   *                 to find all events added after we made the last call.
   * @return collection of opaque key objects.
   * @throws CalFacadeException
   */
  Collection<? extends InternalEventKey> getEventKeysForTzupdate(final String lastmod)
          throws CalFacadeException {
    return getCal().getEventKeysForTzupdate(lastmod);
  }

  CoreEventInfo getEvent(final InternalEventKey key) throws CalFacadeException {
    return getCal().getEvent(key);
  }

  /** Method which allows us to flag it as a scheduling action
   *
   * @param cals
   * @param filter
   * @param startDate
   * @param endDate
   * @param retrieveList
   * @param recurRetrieval
   * @param freeBusy
   * @return Collection of matching events
   * @throws CalFacadeException
   */
  Collection<EventInfo> getMatching(final Collection<BwCalendar> cals,
                                    final FilterBase filter,
                                    final BwDateTime startDate, final BwDateTime endDate,
                                    final List<BwIcalPropertyInfoEntry> retrieveList,
                                    final RecurringRetrievalMode recurRetrieval,
                                    final boolean freeBusy) throws CalFacadeException {
    TreeSet<EventInfo> ts = new TreeSet<EventInfo>();

    if ((filter != null) && (filter.equals(BooleanFilter.falseFilter))) {
      return ts;
    }

    Collection<BwCalendar> calSet = null;

    if (cals != null) {
      /* Turn the calendar reference into a set of calendar collections
       */
      calSet = new ArrayList<BwCalendar>();

      for (BwCalendar cal:cals) {
        buildCalendarSet(calSet, cal, freeBusy);
      }
    }

    ts.addAll(postProcess(getCal().getEvents(calSet, filter,
                          startDate, endDate,
                          retrieveList,
                          recurRetrieval, freeBusy)));

    return ts;
  }

  Set<EventInfo> getSynchEvents(final String path,
                                final String lastmod) throws CalFacadeException {
    return postProcess(getCal().getSynchEvents(path, lastmod));
  }

  /** Method which allows us to flag it as a scheduling action
  *
   * @param ei
   * @param scheduling - true for the scheduling system deleting in/outbox events
   * @param sendSchedulingMessage
   * @return boolean
   * @throws CalFacadeException
   */
  public boolean delete(final EventInfo ei,
                        final boolean scheduling,
                        final boolean sendSchedulingMessage) throws CalFacadeException {
    return delete(ei, scheduling, sendSchedulingMessage, false);
  }

  boolean delete(final EventInfo ei,
                 final boolean scheduling,
                 final boolean sendSchedulingMessage,
                 final boolean reallyDelete) throws CalFacadeException {
    if (ei == null) {
      return false;
    }

    BwEvent event = ei.getEvent();

    /* Note we don't just return immediately if this is a no-op because of
     * tombstoning. We go through the actions to allow access checks to take place.
     */

    if (!event.getTombstoned()) {
      // Handle some scheduling stuff.

      BwCalendar cal = getCols().get(event.getColPath());

      if (sendSchedulingMessage &&
          event.getSchedulingObject() &&
          (cal.getCollectionInfo().scheduling)) {
        // Should we also only do this if it affects freebusy?

        /* According to CalDAV we're supposed to do this before we delete the
         * event. If it fails we now have no way to record that.
         *
         * However that also requires a way to forcibly delete it so we need to
         * ensure we have that first. (Just don't set sendSchedulingMessage
         */
        try {
          SchedulingIntf sched = (SchedulingIntf)getSvc().getScheduler();
          if (event.getAttendeeSchedulingObject()) {
            /* Send a declined message to the organizer
             */
            sched.sendReply(ei,
                            IcalDefs.partstatDeclined, null);
          } else if (event.getOrganizerSchedulingObject()) {
            // send a cancel
            UpdateResult uer = ei.getUpdResult();
            uer.deleting = true;

            sched.implicitSchedule(ei, false);
          }
        } catch (CalFacadeException cfe) {
          if (debug) {
            error(cfe);
          }
        }
      }
    }

    if (!getCal().deleteEvent(ei,
                              scheduling,
                              reallyDelete).eventDeleted) {
      getSvc().rollbackTransaction();
      return false;
    }

    if (event.getEntityType() != IcalDefs.entityTypeVavailability) {
      return true;
    }

    for (EventInfo aei: ei.getContainedItems()) {
      if (!getCal().deleteEvent(aei,
                                scheduling,
                                true).eventDeleted) {
        getSvc().rollbackTransaction();
        return false;
      }
    }

    return true;
  }

  @Override
  public void implantEntities(final Collection<EventInfo> events) throws CalFacadeException {
    if (Util.isEmpty(events)) {
      return;
    }

    Categories cats = getSvc().getCategoriesHandler();
    EventProperties<BwLocation> locs = getSvc().getLocationsHandler();

    for (EventInfo ei: events) {
      BwEvent ev = ei.getEvent();

      Set<String> catUids = ev.getCategoryUids();

      if (catUids != null) {
        for (String uid: catUids) {
          BwCategory cat = cats.get(uid);

          if (cat != null) {
            ev.addCategory(cat);
          }
        }
      }

      if (ev.getLocationUid() != null) {
        ev.setLocation(locs.get(ev.getLocationUid()));
      }
    }
  }

  /* ====================================================================
   *                   private methods
   * ==================================================================== */

  /** Ensure that all references to entities are up to date, for example, ensure
   * that the list of category uids matches the actual list of categories.
   *
   * @param event
   * @throws CalFacadeException
   */
  private void adjustEntities(final EventInfo event) throws CalFacadeException {
    if (event == null) {
      return;
    }

    BwEvent ev = event.getEvent();

    ev.adjustCategories();

    if (ev.getLocation() != null) {
      ev.setLocationUid(ev.getLocation().getUid());
    } else {
      ev.setLocationUid(null);
    }
  }

  private void implantEntities(final EventInfo event) throws CalFacadeException {
    if (event == null) {
      return;
    }

    Categories cats = getSvc().getCategoriesHandler();
    EventProperties<BwLocation> locs = getSvc().getLocationsHandler();

    BwEvent ev = event.getEvent();

    Set<String> catUids = ev.getCategoryUids();

    if (catUids != null) {
      for (String uid: catUids) {
        BwCategory cat = cats.get(uid);

        if (cat != null) {
          ev.addCategory(cat);
        }
      }
    }

    if (ev.getLocationUid() != null) {
      ev.setLocation(locs.get(ev.getLocationUid()));
    }
  }

  private void buildCalendarSet(final Collection<BwCalendar> cals,
                                BwCalendar calendar,
                                final boolean freeBusy) throws CalFacadeException {
    if (calendar == null) {
      return;
    }

    int desiredAccess = PrivilegeDefs.privRead;
    if (freeBusy) {
      desiredAccess = PrivilegeDefs.privReadFreeBusy;
    }

    calendar = getCols().get(calendar.getPath());
    if (calendar == null) {
      // No access presumably
      return;
    }

    if (!getSvc().checkAccess(calendar, desiredAccess, true).getAccessAllowed()) {
      return;
    }

    if (calendar.getInternalAlias()) {
      BwCalendar saveColl = calendar;
      getCols().resolveAlias(calendar, true, freeBusy);

      while (calendar.getInternalAlias()) {
        calendar = calendar.getAliasTarget();

        if (calendar == null) {
          // No access presumably
          saveColl.setLastRefreshStatus(String.valueOf(HttpServletResponse.SC_FORBIDDEN) +
          ": Forbidden");
          return;
        }
      }
    }

    if (calendar.getCalendarCollection() ||
        calendar.getExternalSub() ||
        (cals.isEmpty() && calendar.getSpecial())) {
      /* It's a calendar collection - add if not 'special' or we're adding all
       */

      cals.add(calendar);

      return;
    }

    if (calendar.getCalType() != BwCalendar.calTypeFolder) {
      return;
    }

    for (BwCalendar c: getCols().getChildren(calendar)) {
      buildCalendarSet(cals, c, freeBusy);
    }
  }

  private BwCalendar validate(final BwEvent ev,
                              final boolean autoCreateCollection) throws CalFacadeException {
    if (ev.getColPath() == null) {
      throw new CalFacadeException(CalFacadeException.noEventCalendar);
    }

    if (ev.getNoStart() == null) {
      throw new CalFacadeException(CalFacadeException.missingEventProperty,
                                   "noStart");
    }

    if (ev.getDtstart() == null) {
      throw new CalFacadeException(CalFacadeException.missingEventProperty,
                                   "dtstart");
    }

    if (ev.getDtend() == null) {
      throw new CalFacadeException(CalFacadeException.missingEventProperty,
                                   "dtend");
    }

    if (ev.getDuration() == null) {
      throw new CalFacadeException(CalFacadeException.missingEventProperty,
                                   "duration");
    }

    if (ev.getRecurring() == null) {
      throw new CalFacadeException(CalFacadeException.missingEventProperty,
                                   "recurring");
    }

    setScheduleState(ev);

    Preferences prefs = null;

    if (getPars().getPublicAdmin()) {
      prefs = (Preferences)getSvc().getPrefsHandler();

      Collection<BwCategory> evcats = ev.getCategories();

      if (evcats != null) {
        for (BwCategory cat: evcats) {
          prefs.updateAdminPrefs(false, null, cat, null, null);
        }
      }

      prefs.updateAdminPrefs(false,
                             null,
                             null,
                             ev.getLocation(),
                             ev.getContact());
    }

    BwCalendar col = getCols().get(ev.getColPath());
    if (col != null) {
      if (prefs != null) {
        prefs.updateAdminPrefs(false,
                               col,
                               null, null, null);
      }

      return col;
    }

    if (!autoCreateCollection) {
      throw new CalFacadeException(CalFacadeException.collectionNotFound);
    }

    // TODO - need a configurable default display name

    // TODO - this all needs a rework

    final String entityType = IcalDefs.entityTypeIcalNames[ev.getEntityType()];
    final int calType;

    switch (entityType) {
      case Component.VEVENT:
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
            getCal().getSpecialCalendar(getPrincipal(), calType,
                                        true,
                                        PrivilegeDefs.privAny);

    return gscr.cal;
  }

  /* Flag this as an attendee scheduling object or an organizer scheduling object
   */
  private void setScheduleState(final BwEvent ev) throws CalFacadeException {
    ev.setOrganizerSchedulingObject(false);
    ev.setAttendeeSchedulingObject(false);

    if ((ev.getEntityType() != IcalDefs.entityTypeEvent) &&
        (ev.getEntityType() != IcalDefs.entityTypeTodo) &&
        (ev.getEntityType() != IcalDefs.entityTypeVpoll)) {
      // Not a possible scheduling entity
      return;
    }

    final BwOrganizer org = ev.getOrganizer();

    final Set<BwAttendee> atts = ev.getAttendees();

    if (Util.isEmpty(atts) || (org == null)) {
      return;
    }

    final String curPrincipal = getSvc().getPrincipal().getPrincipalRef();
    final Directories dirs = getSvc().getDirectories();

    AccessPrincipal evPrincipal =
      dirs.caladdrToPrincipal(org.getOrganizerUri());

    if ((evPrincipal != null) &&
        (evPrincipal.getPrincipalRef().equals(curPrincipal))) {
      ev.setOrganizerSchedulingObject(true);

      /* If we are expanding groups do so here */

      final Set<BwAttendee> groups = new TreeSet<>();
      for (final BwAttendee att: atts) {
        if (CuType.GROUP.getValue().equals(att.getCuType())) {
          groups.add(att);
        }
      }

      ChangeTable chg = ev.getChangeset(getPrincipalHref());
      try {
        /* If this is a vpoll we need the vvoters as we are going to
           have to remove the group vvoter entry and clone it for the
           attendees we add.

           I think this will work for any poll mode - if not we may
           have to rethink this approach.
         */
        Map<String, VVoter> voters = null;
        final boolean vpoll;

        if (ev.getEntityType() == IcalDefs.entityTypeVpoll) {
          voters = IcalUtil.parseVpollVvoters(ev);
          ev.clearVvoters(); // We'll add them all back
          vpoll = true;
        } else {
          vpoll = false;
        }

        for (final BwAttendee att : groups) {
          /* If the group is in one of our domains we can try to expand it.
           * We should leave it if it's an external id.
           */

          final Holder<Boolean> trunc = new Holder<>();
          final List<BwPrincipalInfo> groupPis =
                  dirs.find(att.getAttendeeUri(),
                            att.getCuType(),
                            true,  // expand
                            trunc);

          if ((groupPis == null) || (groupPis.size() != 1)) {
            continue;
          }

          final BwPrincipalInfo pi = groupPis.get(0);

          if (pi.getMembers() == null) {
            continue;
          }

          VVoter groupVvoter = null;
          Voter groupVoter = null;
          PropertyList pl = null;

          if (vpoll) {
            groupVvoter = voters.get(att.getAttendeeUri());

            if (groupVvoter == null) {
              if (debug) {
                warn("No vvoter found for " + att.getAttendeeUri());
              }
              continue;
            }

            voters.remove(att.getAttendeeUri());
            groupVoter = groupVvoter.getVoter();
            pl = groupVvoter.getProperties();
          }

          ev.removeAttendee(att); // Remove the group

          chg.changed(PropertyInfoIndex.ATTENDEE, att, null);

          for (final BwPrincipalInfo mbrPi : pi.getMembers()) {
            if (mbrPi.getCaladruri() == null) {
              continue;
            }

            final BwAttendee mbrAtt = new BwAttendee();

            mbrAtt.setType(att.getType());
            mbrAtt.setAttendeeUri(mbrPi.getCaladruri());
            mbrAtt.setCn(mbrPi.getEmail());
            mbrAtt.setCuType(mbrPi.getKind());
            mbrAtt.setMember(att.getAttendeeUri());

            ev.addAttendee(mbrAtt);
            chg.addValue(PropertyInfoIndex.ATTENDEE, mbrAtt);

            if (vpoll) {
              pl.remove(groupVoter);

              groupVoter = IcalUtil.setVoter(mbrAtt);

              pl.add(groupVoter);

              ev.addVvoter(groupVvoter.toString());
            }
          }
        }

        if (vpoll) {
          // Add back any remaining vvoters
          for (VVoter vv: voters.values()) {
            ev.addVvoter(vv.toString());
          }
        }
      } catch (final CalFacadeException cfe) {
        throw cfe;
      } catch (final Throwable t) {
        throw new CalFacadeException(t);
      }

      if (ev instanceof BwEventProxy) {
        // Only add x-property to master
        return;
      }

      if (CalFacadeDefs.jasigSchedulingAssistant.equals(getPars().getClientId())) {
        ev.addXproperty(new BwXproperty(BwXproperty.bedeworkSchedAssist,
                                        null,
                                        "true"));
      }

      return;
    }

    for (final BwAttendee att: atts) {
      /* See if at least one attendee is us */

      evPrincipal = getSvc().getDirectories().caladdrToPrincipal(att.getAttendeeUri());
      if ((evPrincipal != null) &&
          (evPrincipal.getPrincipalRef().equals(curPrincipal))) {
        ev.setAttendeeSchedulingObject(true);

        break;
      }
    }
  }

  private EventInfo postProcess(final CoreEventInfo cei)
          throws CalFacadeException {
    if (cei == null) {
      return null;
    }

    //trace("ev: " + ev);

    /* If the event is an event reference (an alias) implant it in an event
     * proxy and return that object.
     */
    BwEvent ev = cei.getEvent();

    if (ev instanceof BwEventAnnotation) {
      ev = new BwEventProxy((BwEventAnnotation)ev);
    }

    final Set<EventInfo> overrides = new TreeSet<EventInfo>();
    if (cei.getOverrides() != null) {
      for (final CoreEventInfo ocei: cei.getOverrides()) {
        final BwEventProxy op = (BwEventProxy)ocei.getEvent();

        overrides.add(new EventInfo(op));
      }
    }

    final EventInfo ei = new EventInfo(ev, overrides);

    /* Reconstruct if any contained items. */
    if (cei.getNumContainedItems() > 0) {
      for (CoreEventInfo ccei: cei.getContainedItems()) {
        BwEvent cv = ccei.getEvent();

        ei.addContainedItem(new EventInfo(cv));
      }
    }

    ei.setCurrentAccess(cei.getCurrentAccess());

    implantEntities(ei);

    return ei;
  }

  private Set<EventInfo> postProcess(final Collection<CoreEventInfo> ceis)
          throws CalFacadeException {
    TreeSet<EventInfo> eis = new TreeSet<EventInfo>();

    for (CoreEventInfo cei: ceis) {
      eis.add(postProcess(cei));
    }

    implantEntities(eis);

    return eis;
  }

  private void setDefaultAlarms(final EventInfo ei,
                                final BwCalendar col) throws CalFacadeException {
    BwEvent event = ei.getEvent();

    boolean isEvent = event.getEntityType() == IcalDefs.entityTypeEvent;
    boolean isTask = event.getEntityType() == IcalDefs.entityTypeTodo;

    if (!isEvent && !isTask) {
      return;
    }

    /* This test was wrong - we need to test the alarm for compatability with
     * the task/event
     */
//    if (isTask && (event.getNoStart())) {
//      return;
//    }

    boolean isDate = event.getDtstart().getDateType();

    String al = getDefaultAlarmDef(col, isEvent, isDate);

    if (al == null) {
      // Get the user home and try that
      al = getDefaultAlarmDef(getCols().getHome(),
                              isEvent, isDate);
    }

    if ((al == null) || (al.length() == 0)) {
      return;
    }

    Set<BwAlarm> alarms = compileAlarms(al);

    if (alarms == null) {
      return;
    }

    for (BwAlarm alarm: alarms) {
      /* XXX At this point we should test to see if this alarm can be added -
       * e.g. we should not add an alarm triggered off start to a task with no
       * start
       */
      alarm.addXproperty(new BwXproperty(BwXproperty.appleDefaultAlarm,
                                         null, "TRUE"));
      event.addAlarm(alarm);

      ei.getChangeset(getPrincipalHref()).addValue(PropertyInfoIndex.VALARM, alarm);
    }
  }

  private String getDefaultAlarmDef(final BwCalendar col,
                                    final boolean isEvent,
                                    final boolean isDate) {
    if (col == null) {
      return null;
    }

    QName pname;

    if (isEvent) {
      if (isDate) {
        pname = CaldavTags.defaultAlarmVeventDate;
      } else {
        pname = CaldavTags.defaultAlarmVeventDatetime;
      }
    } else {
      if (isDate) {
        pname = CaldavTags.defaultAlarmVtodoDate;
      } else {
        pname = CaldavTags.defaultAlarmVtodoDatetime;
      }
    }

    return col.getProperty(NamespaceAbbrevs.prefixed(pname));
  }

  private static final String ValidateAlarmPrefix =
      "BEGIN:VCALENDAR\n" +
      "VERSION:2.0\n" +
      "PRODID:bedework-validate\n" +
      "BEGIN:VEVENT\n" +
      "DTSTART:20101231T230000\n" +
      "DTEND:20110101T010000\n" +
      "SUMMARY:Just checking\n" +
      "UID:1234\n" +
      "DTSTAMP:20101125T112600\n";

  private static final String ValidateAlarmSuffix =
      "END:VEVENT\n" +
      "END:VCALENDAR\n";

  /** Compile an alarm component
   *
   * @param val
   * @return alarms or null
   * @throws CalFacadeException
   */
  public Set<BwAlarm> compileAlarms(final String val) throws CalFacadeException {
    try {
      StringReader sr = new StringReader(ValidateAlarmPrefix +
                                         val +
                                         ValidateAlarmSuffix);
      IcalTranslator trans = new IcalTranslator(getSvc().getIcalCallback());
      Icalendar ic = trans.fromIcal(null, sr);

      if ((ic == null) ||
          (ic.getEventInfo() == null)) {
        if (debug) {
          trace("Not single event");
        }

        return null;
      }

      /* There should be alarms in the Calendar object
       */
      EventInfo ei = ic.getEventInfo();
      BwEvent ev = ei.getEvent();

      Set<BwAlarm> alarms = ev.getAlarms();

      if (Util.isEmpty(alarms)) {
        return null;
      }

      return alarms;
    } catch (CalFacadeException cfe) {
      if (debug) {
        error(cfe);
      }

      return null;
    }
  }

}
