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
package org.bedework.calsvc.scheduling;

import org.bedework.access.PrivilegeDefs;
import org.bedework.calfacade.BwAttendee;
import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.BwEvent;
import org.bedework.calfacade.BwEventObj;
import org.bedework.calfacade.BwEventProxy;
import org.bedework.calfacade.BwOrganizer;
import org.bedework.calfacade.BwRequestStatus;
import org.bedework.calfacade.BwString;
import org.bedework.calfacade.ScheduleResult;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calfacade.svc.EventInfo;
import org.bedework.calsvc.CalSvc;
import org.bedework.icalendar.IcalUtil;
import org.bedework.icalendar.Icalendar;
import org.bedework.util.calendar.IcalDefs;
import org.bedework.util.calendar.ScheduleMethods;
import org.bedework.util.misc.Util;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.property.PollItemId;
import net.fortuna.ical4j.model.property.Voter;

import net.fortuna.ical4j.model.component.VVoter;

import java.util.Map;

/** Rather than have a single class steering calls to a number of smaller classes
 * we will build up a full implementation by progressively implementing abstract
 * classes.
 *
 * <p>That allows us to split up some rather complex code into appropriate pieces.
 *
 * <p>This piece handles the attendee to organizer methods from the attendee end.
 *
 * @author Mike Douglass
 *
 */
public abstract class AttendeeSchedulingHandler extends OrganizerSchedulingHandler {
  AttendeeSchedulingHandler(final CalSvc svci) {
    super(svci);
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SchedulingI#requestRefresh(org.bedework.calfacade.BwEvent, java.lang.String)
   */
  @Override
  public ScheduleResult requestRefresh(final EventInfo ei,
                                       final String comment) throws CalFacadeException {
    ScheduleResult sr = new ScheduleResult();
    BwEvent ev = ei.getEvent();

    if (ev.getScheduleMethod() != ScheduleMethods.methodTypeRequest) {
      sr.errorCode = CalFacadeException.schedulingBadMethod;
      return sr;
    }

    BwAttendee att = findUserAttendee(ev);

    if (att == null) {
      throw new CalFacadeException(CalFacadeException.schedulingNotAttendee);
    }

    BwEvent outEv = new BwEventObj();
    EventInfo outEi = new EventInfo(outEv);

    outEv.setScheduleMethod(ScheduleMethods.methodTypeRefresh);

    outEv.addRecipient(ev.getOrganizer().getOrganizerUri());
    outEv.setOriginator(att.getAttendeeUri());
    outEv.updateDtstamp();
    outEv.setOrganizer((BwOrganizer)ev.getOrganizer().clone());
    outEv.getOrganizer().setDtstamp(outEv.getDtstamp());
    outEv.addAttendee((BwAttendee)att.clone());
    outEv.setUid(ev.getUid());
    outEv.setRecurrenceId(ev.getRecurrenceId());

    outEv.setDtstart(ev.getDtstart());
    outEv.setDtend(ev.getDtend());
    outEv.setDuration(ev.getDuration());
    outEv.setNoStart(ev.getNoStart());

    outEv.setRecurring(false);

    if (comment != null) {
      outEv.addComment(new BwString(null, comment));
    }

    sr = scheduleResponse(outEi);
    outEv.setScheduleState(BwEvent.scheduleStateProcessed);

    return sr;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SchedulingI#attendeeRespond(org.bedework.calfacade.svc.EventInfo)
   */
  @Override
  public ScheduleResult attendeeRespond(final EventInfo ei,
                                        final int method) throws CalFacadeException {
    ScheduleResult sr = new ScheduleResult();
    final BwEvent ev = ei.getEvent();

    check: {
      /* Check that the current user is actually the only attendee of the event.
       * Note we may have a suppressed master and/or multiple overrides
       */
      BwAttendee att = null;

      if (!ev.getSuppressed()) {
        att = findUserAttendee(ev);

        if (att == null) {
          sr.errorCode = CalFacadeException.schedulingNotAttendee;
          break check;
        }
      }

      if (ei.getNumOverrides() > 0) {
        for (final EventInfo oei: ei.getOverrides()) {
          att = findUserAttendee(oei.getEvent());

          if (att == null) {
            sr.errorCode = CalFacadeException.schedulingNotAttendee;
            break check;
          }
        }
      }

      if (ev.getOriginator() == null) {
        sr.errorCode = CalFacadeException.schedulingNoOriginator;
        break check;
      }

      //EventInfo outEi = makeReplyEventInfo(ei, getUser().getPrincipalRef());
      final EventInfo outEi = copyEventInfo(ei, getPrincipal());
      final BwEvent outEv = outEi.getEvent();

      if (!Util.isEmpty(outEv.getRecipients())) {
        outEv.getRecipients().clear();
      }

      if (!Util.isEmpty(outEv.getAttendees())) {
        outEv.getAttendees().clear();
      }

      // XXX we should get a comment from non db field in event
      //if (comment != null) {
      //  // Just add for the moment
      //  outEv.addComment(null, comment);
      //}

      outEv.addRecipient(outEv.getOrganizer().getOrganizerUri());
      outEv.setOriginator(att.getAttendeeUri());
      outEv.updateDtstamp();
      outEv.getOrganizer().setDtstamp(outEv.getDtstamp());

      String delegate = att.getDelegatedTo();
      if (delegate != null) {
        /* RFC 2446 4.2.5 - Delegating an event
         *
         * When delegating an event request to another "Calendar User", the
         * "Delegator" must both update the "Organizer" with a "REPLY" and send
         * a request to the "Delegate". There is currently no protocol
         * limitation to delegation depth. It is possible for the original
         * delegate to delegate the meeting to someone else, and so on. When a
         * request is delegated from one CUA to another there are a number of
         * responsibilities required of the "Delegator". The "Delegator" MUST:
         *
         *   .  Send a "REPLY" to the "Organizer" with the following updates:
         *   .  The "Delegator's" "ATTENDEE" property "partstat" parameter set
         *      to "delegated" and the "delegated-to" parameter is set to the
         *      address of the "Delegate"
         *   .  Add an additional "ATTENDEE" property for the "Delegate" with
         *      the "delegated-from" property parameter set to the "Delegator"
         *   .  Indicate whether they want to continue to receive updates when
         *      the "Organizer" sends out updated versions of the event.
         *      Setting the "rsvp" property parameter to "TRUE" will cause the
         *      updates to be sent, setting it to "FALSE" causes no further
         *      updates to be sent. Note that in either case, if the "Delegate"
         *      declines the invitation the "Delegator" will be notified.
         *   .  The "Delegator" MUST also send a copy of the original "REQUEST"
         *      method to the "Delegate".
         */

        // outEv is the reply
        outEv.setScheduleMethod(ScheduleMethods.methodTypeReply);

        // Additional attendee
        BwAttendee delAtt = new BwAttendee();
        delAtt.setAttendeeUri(delegate);
        delAtt.setDelegatedFrom(att.getAttendeeUri());
        delAtt.setPartstat(IcalDefs.partstatValNeedsAction);
        delAtt.setRsvp(true);
        delAtt.setRole(att.getRole());
        outEv.addAttendee(delAtt);

        // ei is 'original "REQUEST"'. */
        EventInfo delegateEi = copyEventInfo(ei, getPrincipal());
        BwEvent delegateEv = delegateEi.getEvent();

        delegateEv.addRecipient(delegate);
        delegateEv.addAttendee((BwAttendee)delAtt.clone()); // Not in RFC
        delegateEv.setScheduleMethod(ScheduleMethods.methodTypeRequest);

        att.setPartstat(IcalDefs.partstatValDelegated);
        att.setRsvp(false);
        att.setDelegatedTo(delegate);

        // XXX Not sure if this is correct
        schedule(delegateEi, ScheduleMethods.methodTypeRequest, null, null, false);
      } else if (method == ScheduleMethods.methodTypeReply) {
        // Only attendee should be us

        setOnlyAttendee(outEi, ei, att.getAttendeeUri());

        if (ev.getEntityType() == IcalDefs.entityTypeVpoll) {
          setPollResponse(outEi, ei, att.getAttendeeUri());
        }

        outEv.setScheduleMethod(ScheduleMethods.methodTypeReply);
      } else if (method == ScheduleMethods.methodTypeCounter) {
        // Only attendee should be us

        setOnlyAttendee(outEi, ei, att.getAttendeeUri());

        /* Not sure how much we can change - at least times of the meeting.
         */
        outEv.setScheduleMethod(ScheduleMethods.methodTypeCounter);
      } else {
        throw new RuntimeException("Never get here");
      }

      outEv.addRequestStatus(new BwRequestStatus(IcalDefs.requestStatusSuccess.getCode(),
                                                 IcalDefs.requestStatusSuccess.getDescription()));
      sr = scheduleResponse(outEi);
      outEv.setScheduleState(BwEvent.scheduleStateProcessed);
      ev.getOrganizer().setScheduleStatus(IcalDefs.deliveryStatusDelivered);
    }

    return sr;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SchedulingI#processCancel(org.bedework.calfacade.svc.EventInfo, org.bedework.calfacade.svc.EventInfo)
   * /
  public ScheduleResult processCancel(final EventInfo ei) throws CalFacadeException {
    /* We, as an attendee, received a CANCEL from the organizer.
     *
     * /

    ScheduleResult sr = new ScheduleResult();
    BwEvent ev = ei.getEvent();
    BwCalendar inbox = getSvc().getCalendarsHandler().get(ev.getColPath());

    boolean forceDelete = true;

    check: {
      if (inbox.getCalType() != BwCalendar.calTypeInbox) {
        sr.errorCode = CalFacadeException.schedulingBadSourceCalendar;
        break check;
      }

      if (ev.getOriginator() == null) {
        sr.errorCode = CalFacadeException.schedulingNoOriginator;
        break check;
      }

      BwPreferences prefs = getSvc().getPrefsHandler().get();
      EventInfo colEi = getStoredMeeting(ei.getEvent());

      if (colEi == null) {
        break check;
      }

      BwEvent colEv = colEi.getEvent();

      if (prefs.getScheduleAutoCancelAction() ==
        BwPreferences.scheduleAutoCancelSetStatus) {
        if (colEv.getSuppressed()) {
          if (colEi.getOverrides() != null) {
            for (EventInfo oei: colEi.getOverrides()) {
              oei.getEvent().setStatus(BwEvent.statusCancelled);
            }
          }
        } else {
          colEv.setStatus(BwEvent.statusCancelled);
        }
        getSvc().getEventsHandler().update(colEi, true, null);
      } else {
        getSvc().getEventsHandler().delete(colEi, false);
      }

      forceDelete = false;
    }

    updateInbox(ei, inbox.getOwnerHref(),
                false,           // attendeeAccepting
                forceDelete);  // forceDelete

    return sr;
  }
  */

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SchedulingI#scheduleResponse(org.bedework.calfacade.BwEvent)
   */
  @Override
  public ScheduleResult scheduleResponse(final EventInfo ei) throws CalFacadeException {
    /* As an attendee, respond to a scheduling request.
     *
     *    Copy event
     *    remove all attendees and readd this user
     *    Add to organizers inbox if internal
     *    Put in outbox if external.
     */
    ScheduleResult sr = new ScheduleResult();

    try {
      int smethod = ei.getEvent().getScheduleMethod();

      if (!Icalendar.itipReplyMethodType(smethod)) {
        sr.errorCode = CalFacadeException.schedulingBadMethod;
        return sr;
      }

      /* For each recipient within this system add the event to their inbox.
       *
       * If there are any external users add it to the outbox and it will be
       * mailed to the recipients.
       */

      int outAccess = PrivilegeDefs.privScheduleReply;

      /* There should only be one attendee for a reply */
      if (!ei.getEvent().getSuppressed()) {
        BwEvent ev = ei.getEvent();
        if (ev.getNumAttendees() != 1) {
          sr.errorCode = CalFacadeException.schedulingBadAttendees;
          return sr;
        }
      }

      if (ei.getNumOverrides() > 0) {
        for (EventInfo oei: ei.getOverrides()) {
          BwEvent ev = oei.getEvent();
          if (ev.getNumAttendees() != 1) {
            sr.errorCode = CalFacadeException.schedulingBadAttendees;
            return sr;
          }
        }
      }

      if (!initScheduleEvent(ei, true, false)) {
        return sr;
      }

      /* Do this here to check we have access. We might need the outbox later
       */
      BwCalendar outBox = getSpecialCalendar(getPrincipal(),
                                             BwCalendar.calTypeOutbox,
                                             true, outAccess);

      sendSchedule(sr, ei, null, null, false);

      if (sr.ignored) {
        return sr;
      }

      if (!sr.externalRcs.isEmpty()) {
        sr.errorCode = addToOutBox(ei, outBox, sr.externalRcs);
      }

      return sr;
    } catch (Throwable t) {
      getSvc().rollbackTransaction();
      if (t instanceof CalFacadeException) {
        throw (CalFacadeException)t;
      }
      throw new CalFacadeException(t);
    }
  }

  protected EventInfo makeReplyEventInfo(final EventInfo ei,
                                         final String owner) throws CalFacadeException {
    BwEvent newEv = makeReplyEvent(ei.getEvent(), owner);
    EventInfo newEi = new EventInfo(newEv);

    /* I think for a reply type message this is sufficient. iTip only
     * requires that we either duplicate or omit most properties.
     *
     * However, it's possible a client could send an update that simultaneously
     * changed the PARTSTAT differently on different overrrides. Can't deal with
     * that this way.
     */

    return newEi;
  }

  protected BwEvent makeReplyEvent(final BwEvent origEv,
                                   final String ownerHref) throws CalFacadeException {
    BwEvent newEv = new BwEventObj();

    if (origEv instanceof BwEventProxy) {
      getSvc().reAttach(((BwEventProxy)origEv).getRef());

      /* we are being asked to copy an instance of a recurring event - rather than
       * a complete recurring event + all overrides - clone the master
       */
    } else {
      getSvc().reAttach(origEv);
    }

    newEv.setUid(origEv.getUid());
    newEv.setOrganizer(origEv.getOrganizer());
    newEv.setRecurrenceId(origEv.getRecurrenceId());
    newEv.setSequence(origEv.getSequence());

    // Attendee and DTSTAMP set by caller?

    newEv.setOwnerHref(ownerHref);
    newEv.setCreatorHref(ownerHref);
    newEv.setDtstamps(getCurrentTimestamp());

    // These to get past validation
    newEv.setDtstart(origEv.getDtstart());
    newEv.setDtend(origEv.getDtend());
    newEv.setEndType(origEv.getEndType());
    newEv.setDuration(origEv.getDuration());
    newEv.setNoStart(origEv.getNoStart());
    newEv.setRecurring(false);

    // XXX Temp set summary so we have something to display - this may not be
    // the case for incoming events from outside
    newEv.setSummary(origEv.getSummary());

    return newEv;
  }

  /** Set the attendee in the output event from the corresponding component in
   * the calendar event.
   *
   * @param outEi - destined for somebodies inbox
   * @param ei
   * @param attUri
   * @throws CalFacadeException
   */
  private void setOnlyAttendee(final EventInfo outEi,
                               final EventInfo ei,
                               final String attUri) throws CalFacadeException {
    if (!ei.getEvent().getSuppressed()) {
      BwEvent ev = ei.getEvent();
      BwEvent outEv = outEi.getEvent();

      if (!Util.isEmpty(outEv.getAttendees())) {
        outEv.getAttendees().clear();
      }
      final BwAttendee att = ev.findAttendee(attUri);
      outEv.addAttendee((BwAttendee)att.clone());
    }

    if (ei.getNumOverrides() > 0) {
      for (final EventInfo oei: ei.getOverrides()) {
        final BwEvent ev = oei.getEvent();
        final EventInfo oOutEi = outEi.findOverride(ev.getRecurrenceId());
        final BwEvent outEv = oOutEi.getEvent();

        if (!Util.isEmpty(outEv.getAttendees())) {
          outEv.getAttendees().clear();
        }
        final BwAttendee att = ev.findAttendee(attUri);
        outEv.addAttendee((BwAttendee)att.clone());
      }
    }
  }

  /** Set the poll response for the given voter in the output event
   * from the voting state in the incoming event. The output event
   * should only have one VVOTER sub-component for this voter with
   * its state set by the current voters state.
   *
   * @param outEi - destined for somebodies inbox
   * @param ei - the voters copy
   * @param attUri - uri of the voter
   * @throws CalFacadeException
   */
  private void setPollResponse(final EventInfo outEi,
                               final EventInfo ei,
                               final String attUri) throws CalFacadeException {
    /* This requires us to parse out the VVOTER components - find our voter
       and add a poll item id property in the output.

       Note that this is implementing poll mode basic.
     */

    final BwEvent ev = ei.getEvent();
    final BwEvent outEv = outEi.getEvent();

    try {
      final Map<String, VVoter> voters = IcalUtil.parseVpollVvoters(ev);
      outEv.clearVvoters();

      final VVoter vv = voters.get(attUri);
      if (vv == null) {
        warn("No Vvoter element for " + attUri);
        return;
      }

      outEv.addVvoter(vv.toString());
    } catch (final Throwable t) {
      throw new CalFacadeException(t);
    }
  }
}
