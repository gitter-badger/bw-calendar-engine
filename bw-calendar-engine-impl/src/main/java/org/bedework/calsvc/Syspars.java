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

import org.bedework.calfacade.BwPrincipal;
import org.bedework.calfacade.BwSystem;
import org.bedework.calfacade.exc.CalFacadeAccessException;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calsvci.SysparsI;

import org.bedework.access.WhoDefs;

import java.util.ArrayList;
import java.util.Collection;

/** This acts as an interface to the database for system parameters.
 *
 * @author Mike Douglass       douglm - rpi.edu
 */
class Syspars extends CalSvcDb implements SysparsI {
  private static BwSystem syspars;

  private static boolean restoring;

  private static long lastRefresh;
  private final static long refreshInterval = 1000 * 60 * 5; // 5 mins

  private final static String sysparsName = "bedework";

  private Collection<String> rootUsers;

  Syspars(final CalSvc svci) {
    super(svci);
  }

  @Override
  public BwSystem get() throws CalFacadeException {
    if (restoring) {
      return syspars;
    }

    synchronized (this) {
      if (System.currentTimeMillis() > (lastRefresh + refreshInterval)) {
        syspars = null;
        lastRefresh = System.currentTimeMillis();
      }

      if (syspars == null) {
        syspars = get(sysparsName);
      }

      return syspars;
    }
  }

  @Override
  public BwSystem get(final String name) throws CalFacadeException {
    final BwSystem sys = getCal().getSyspars(name);

    if (sys == null) {
      throw new CalFacadeException("No system parameters with name " +
                                   name);
    }

    if (debug) {
      trace("Read system parameters: " + sys);
    }

    return sys;
  }

  @Override
  public void update(final BwSystem val) throws CalFacadeException {
    if (!isSuper()) {
      throw new CalFacadeAccessException();
    }

    getCal().saveOrUpdate(val);

    syspars = null; // Force refresh
    restoring = false;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SysparsI#getRootUsers()
   */
  @Override
  public Collection<String> getRootUsers() throws CalFacadeException {
    if (rootUsers != null) {
      return rootUsers;
    }

    rootUsers = new ArrayList<String>();

    final String rus = getSvc().getSystemProperties().getRootUsers();

    if (rus == null) {
      return rootUsers;
    }

    try {
      int pos = 0;

      while (pos < rus.length()) {
        final int nextPos = rus.indexOf(",", pos);
        if (nextPos < 0) {
          rootUsers.add(rus.substring(pos));
          break;
        }

        rootUsers.add(rus.substring(pos, nextPos));
        pos = nextPos + 1;
      }
    } catch (Throwable t) {
      throw new CalFacadeException(CalFacadeException.badRootUsersList,
                                   rus);
    }

    return rootUsers;
  }

  /* (non-Javadoc)
   * @see org.bedework.calsvci.SysparsI#isRootUser(org.bedework.calfacade.BwPrincipal)
   */
  @Override
  public boolean isRootUser(final BwPrincipal val) throws CalFacadeException {
    if ((val == null) || val.getUnauthenticated()) {
      return false;
    }

    if (val.getKind() != WhoDefs.whoTypeUser) {
      return false;
    }

    final Collection<String> rus = getRootUsers();

    return rus.contains(val.getAccount());
  }

  void setForRestore(final BwSystem val) {
    restoring = true;
    syspars = val;
  }

  @Override
  public boolean present() throws CalFacadeException {
    final BwSystem sys = getCal().getSyspars(sysparsName);

    return sys != null;
  }
}
