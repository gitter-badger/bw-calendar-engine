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
package org.bedework.dumprestore;

import org.bedework.calfacade.BwCalendar;
import org.bedework.calfacade.configs.DumpRestoreProperties;
import org.bedework.calfacade.exc.CalFacadeAccessException;
import org.bedework.calfacade.exc.CalFacadeException;
import org.bedework.calsvci.CalSvcFactoryDefault;
import org.bedework.calsvci.CalSvcI;
import org.bedework.calsvci.CalSvcIPars;
import org.bedework.calsvci.CalendarsI.CheckSubscriptionResult;
import org.bedework.calsvci.RestoreIntf.FixAliasResult;
import org.bedework.dumprestore.dump.Dump;
import org.bedework.dumprestore.restore.Restore;
import org.bedework.indexer.BwIndexCtlMBean;
import org.bedework.util.jmx.ConfBase;
import org.bedework.util.jmx.MBeanUtil;
import org.bedework.util.timezones.DateTimeUtil;
import org.bedework.util.xml.FromXml;
import org.bedework.util.xml.XmlUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author douglm
 *
 */
public class BwDumpRestore extends ConfBase<DumpRestorePropertiesImpl>
        implements BwDumpRestoreMBean {
  /* Name of the property holding the location of the config data */
  public static final String confuriPname = "org.bedework.bwengine.confuri";

  private List<AliasInfo> externalSubs;

  /* Collections marked as aliases. We may need to fix sharing
   */
  private Map<String, AliasEntry> aliasInfo = new HashMap<>();

  private boolean allowRestore;

  private boolean fixAliases;

  private String curSvciOwner;

  private CalSvcI svci;

  private class RestoreThread extends Thread {
    InfoLines infoLines = new InfoLines();

    RestoreThread() {
      super("RestoreData");
    }

    @Override
    public void run() {
      if (!allowRestore) {
        infoLines.add("***********************************\n");
        infoLines.add("********* Restores disabled *******\n");
        infoLines.add("***********************************\n");

        return;
      }

      boolean closed = true;
      Restore restorer = null;

      try {
        if (!disableIndexer()) {
          infoLines.add("***********************************\n");
          infoLines.add("********* Unable to disable indexer\n");
          infoLines.add("***********************************\n");
        }

        infoLines.addLn("Started restore of data");

        final long startTime = System.currentTimeMillis();

        restorer = new Restore();

        restorer.getConfigProperties();

        infoLines.addLn("Restore file: " + getDataIn());
        info("Restore file: " + getDataIn());

        restorer.setFilename(getDataIn());

        closed = false;
        restorer.open();

        restorer.doRestore(infoLines);

        externalSubs = restorer.getExternalSubs();
        aliasInfo = restorer.getAliasInfo();

        closed = true;
        restorer.close();

        restorer.stats(infoLines);

        final long millis = System.currentTimeMillis() - startTime;
        final long seconds = millis / 1000;
        final long minutes = seconds / 60;

        infoLines.addLn("Elapsed time: " + minutes + ":" +
                                Restore.twoDigits(seconds - (minutes * 60)));

        infoLines.add("Restore complete" + "\n");
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      } finally {
        if (!closed) {
          try {
            restorer.close();
          } catch (final Throwable ignored) {}
        }
        infoLines.addLn("Restore completed - about to start indexer");

        try {
          if (!reindex()) {
            infoLines.addLn("***********************************");
            infoLines.addLn("********* Unable to reindex");
            infoLines.addLn("***********************************");
          }
        } catch (final Throwable t) {
          error(t);
          infoLines.exceptionMsg(t);
        }
      }
    }
  }

  private RestoreThread restore;

  private class DumpThread extends Thread {
    InfoLines infoLines = new InfoLines();
    boolean dumpAll;

    DumpThread(final boolean dumpAll) {
      super("DumpData");

      this.dumpAll = dumpAll;
    }

    @Override
    public void run() {
      try {
        final long startTime = System.currentTimeMillis();

        final Dump d = new Dump(infoLines);

        d.getConfigProperties();

        if (dumpAll) {
          infoLines.addLn("Started dump of data");

          d.setFilename(makeFilename(getDataOutPrefix()));
          d.setAliasesFilename(makeFilename("aliases-" + getDataOutPrefix()));

          d.open(false);

          d.doDump();
        } else {
          infoLines.addLn("Started search for external subscriptions");
          d.open(true);

          d.doExtSubs();
        }

        externalSubs = d.getExternalSubs();
        aliasInfo = d.getAliasInfo();

        d.close();

        d.stats(infoLines);

        final long millis = System.currentTimeMillis() - startTime;
        final long seconds = millis / 1000;
        final long minutes = seconds / 60;

        infoLines.addLn("Elapsed time: " + minutes + ":" +
                                Restore.twoDigits(seconds - (minutes * 60)));

        infoLines.addLn("Complete");
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }

  private DumpThread dump;

  private class SubsThread extends Thread {
    InfoLines infoLines = new InfoLines();

    SubsThread() {
      super("Subs");
    }

    @Override
    public void run() {
      try {
        final boolean debug = getLogger().isDebugEnabled();

        if (externalSubs.isEmpty()) {
          infoLines.addLn("No external subscriptions");

          return;
        }

        /** Number for which no action was required */
        int okCt = 0;

        /** Number not found */
        int notFoundCt = 0;

        /** Number for which no action was required */
        int notExternalCt = 0;

        /** Number resubscribed */
        int resubscribedCt = 0;

        /** Number of failures */
        int failedCt = 0;

        if (debug) {
          debug("About to process " + externalSubs
                  .size() + " external subs");
        }

        int ct = 0;
        int accessErrorCt = 0;
        int errorCt = 0;

        resubscribe:
        for (final AliasInfo ai: externalSubs) {
          getSvci(ai);

          try {
            final CheckSubscriptionResult csr =
                    svci.getCalendarsHandler().checkSubscription(
                            ai.getPath());

            switch (csr) {
              case ok:
                okCt++;
                break;
              case notFound:
                notFoundCt++;
                break;
              case notExternal:
                notExternalCt++;
                break;
              case resubscribed:
                resubscribedCt++;
                break;
              case noSynchService:
                infoLines.addLn("Synch service is unavailable");
                info("Synch service is unavailable");
                break resubscribe;
              case failed:
                failedCt++;
                break;
            } // switch

            if ((csr != CheckSubscriptionResult.ok) &&
                    (csr != CheckSubscriptionResult.resubscribed)) {
              infoLines.addLn("Status: " + csr +
                                      " for " + ai.getPath() +
                                      " owner: " + ai.getOwner());
            }

            ct++;

            if ((ct % 100) == 0) {
              info("Checked " + ct + " collections");
            }
          } catch (final CalFacadeAccessException cae) {
            accessErrorCt++;

            if ((accessErrorCt % 100) == 0) {
              info("Had " + accessErrorCt + " access errors");
            }
          } catch (final Throwable t) {
            error(t);
            errorCt++;

            if ((errorCt % 100) == 0) {
              info("Had " + errorCt + " errors");
            }
          } finally {
            closeSvci();
          }
        } // resubscribe

        infoLines.addLn("Checked " + ct + " collections");
        infoLines.addLn("       errors: " + errorCt);
        infoLines.addLn("access errors: " + accessErrorCt);
        infoLines.addLn("           ok: " + okCt);
        infoLines.addLn("    not found: " + notFoundCt);
        infoLines.addLn("  notExternal: " + notExternalCt);
        infoLines.addLn(" resubscribed: " + resubscribedCt);
        infoLines.addLn("       failed: " + failedCt);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }

  private SubsThread subs;

  private class AliasesThread extends Thread {
    InfoLines infoLines = new InfoLines();

    AliasesThread() {
      super("Aliases");
    }

    @Override
    public void run() {
      try {
        final boolean debug = getLogger().isDebugEnabled();

        if (aliasInfo.isEmpty()) {
          infoLines.addLn("No aliases");

          return;
        }

        /** Number for which no action was required */
        int okCt = 0;

        /** Number of public aliases */
        int publicCt = 0;

        /** Number not found - broken links */
        int notFoundCt = 0;

        /** Number for which no access */
        int noAccessCt = 0;

        /** Number fixed */
        int fixedCt = 0;

        /** Number of failures */
        int failedCt = 0;

        if (debug) {
          debug("About to process " + aliasInfo.size() +
                        " aliases");
        }

        int ct = 0;
        int errorCt = 0;

        for (final String target: aliasInfo.keySet()) {
          final AliasEntry ae = aliasInfo.get(target);

          fix:
          for (final AliasInfo ai: ae.getAliases()) {
            final CalSvcI svci = getSvci(ai);

            if (ai.getPublick()) {
              publicCt++;
              continue fix;
            }

            /* Try to fetch as current user */
            final BwCalendar targetCol;
            try {
              targetCol = svci.getCalendarsHandler().get(target);
            } catch (final CalFacadeAccessException ignored) {
              ai.setNoAccess(true);
              noAccessCt++;
              continue fix;
            }

            if (targetCol == null) {
              ai.setNoAccess(true);
              noAccessCt++;
              continue fix;
            }

            if (targetCol.getPublick()) {
              publicCt++;
              continue fix;
            }

            try {
              final FixAliasResult far =
                      svci.getRestoreHandler().fixSharee(targetCol,
                                                         ai.getOwner());

              switch (far) {
                case ok:
                  okCt++;
                  break;
                case noAccess:
                  noAccessCt++;
                  break;
                case wrongAccess:
                  warn("Incompatible access for " + ai.getPath());
                  break;
                case notFound:
                  notFoundCt++;
                  break;
                case circular:
                  warn("Circular aliases for " + ai.getPath());
                  break;
                case broken:
                  notFoundCt++;
                  break;
                case reshared:
                  fixedCt++;
                  break;
                case failed:
                  failedCt++;
                  break;
              } // switch

              if ((far != FixAliasResult.ok) &&
                      (far != FixAliasResult.reshared)) {
                infoLines.addLn("Status: " + far + " for " + ai.getPath() +
                                        " owner: " + ai.getOwner());
              }

              ct++;

              if ((ct % 100) == 0) {
                info("Checked " + ct + " collections");
              }
            } catch (final CalFacadeAccessException ignored) {
              noAccessCt++;

              if ((noAccessCt % 100) == 0) {
                info("Had " + noAccessCt + " access errors");
              }
            } catch (final Throwable t) {
              error(t);
              errorCt++;

              if ((errorCt % 100) == 0) {
                info("Had " + errorCt + " errors");
              }
            } finally {
              closeSvci();
            }
          } // fix
        }

        infoLines.addLn("Checked " + ct + " collections");
        infoLines.addLn("       errors: " + errorCt);
        infoLines.addLn("access errors: " + noAccessCt);
        infoLines.addLn("           ok: " + okCt);
        infoLines.addLn("       public: " + publicCt);
        infoLines.addLn("    not found: " + notFoundCt);
        infoLines.addLn("        fixed: " + fixedCt);
        infoLines.addLn("       failed: " + failedCt);
      } catch (final Throwable t) {
        error(t);
        infoLines.exceptionMsg(t);
      }
    }
  }

  private AliasesThread aliases;

  private final static String nm = "dumprestore";

  /**
   */
  public BwDumpRestore() {
    super(getServiceName(nm));

    setConfigName(nm);

    setConfigPname(confuriPname);
  }

  /**
   * @param name of the service
   * @return object name value for the mbean with this name
   */
  public static String getServiceName(final String name) {
    return "org.bedework.bwengine:service=" + name;
  }

  /* ========================================================================
   * Attributes
   * ======================================================================== */

  @Override
  public void setAccount(final String val) {
    getConfig().setAccount(val);
  }

  @Override
  public String getAccount() {
    return getConfig().getAccount();
  }

  @Override
  public void setDataIn(final String val) {
    getConfig().setDataIn(val);
  }

  @Override
  public String getDataIn() {
    return getConfig().getDataIn();
  }

  @Override
  public void setDataOut(final String val) {
    getConfig().setDataOut(val);
  }

  @Override
  public String getDataOut() {
    return getConfig().getDataOut();
  }

  @Override
  public void setDataOutPrefix(final String val) {
    getConfig().setDataOutPrefix(val);
  }

  @Override
  public String getDataOutPrefix() {
    return getConfig().getDataOutPrefix();
  }

  @Override
  public DumpRestoreProperties cloneIt() {
    return getConfig().cloneIt();
  }

  @Override
  public String loadConfig() {
    return loadConfig(DumpRestorePropertiesImpl.class);
  }

  @Override
  public void setAllowRestore(final boolean val) {
    allowRestore = val;
  }

  @Override
  public boolean getAllowRestore() {
    return allowRestore;
  }

  @Override
  public void setFixAliases(final boolean val) {
    fixAliases = val;
  }

  @Override
  public boolean getFixAliases() {
    return fixAliases;
  }

  @Override
  public synchronized String restoreData() {
    try {
      restore = new RestoreThread();

      restore.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public synchronized List<String> restoreStatus() {
    if (restore == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Restore has not been started");

      return infoLines;
    }

    return restore.infoLines;
  }

  @Override
  public String loadAliasInfo(final String path) {
    try {
      final FromXml fxml = new FromXml();

      final Document doc = fxml.parseXml(new FileInputStream(path));

      final Element el = doc.getDocumentElement();

      if (!el.getTagName().equals(Defs.aliasInfoTag)) {
        return "Not an alias-info dump file - incorrect root element " +
                el;
      }

      externalSubs = new ArrayList<>();
      aliasInfo = new HashMap<>();

      for (final Element child: XmlUtil.getElementsArray(el)) {
        if (child.getTagName().equals(Defs.majorVersionTag)) {
          continue;
        }

        if (child.getTagName().equals(Defs.minorVersionTag)) {
          continue;
        }

        if (child.getTagName().equals(Defs.versionTag)) {
          continue;
        }

        if (child.getTagName().equals(Defs.dumpDateTag)) {
          continue;
        }

        if (child.getTagName().equals(Defs.extsubsTag)) {
          for (final Element extSubEl: XmlUtil.getElementsArray(child)) {
            externalSubs.add((AliasInfo)fxml.fromXml(extSubEl, AliasInfo.class));
          }

          continue;
        }

        if (child.getTagName().equals(Defs.aliasesTag)) {
          for (final Element aliasEl: XmlUtil.getElementsArray(child)) {
            final AliasEntry ae = (AliasEntry)fxml.fromXml(aliasEl,
                                                           AliasEntry.class);
            aliasInfo.put(ae.getTargetPath(), ae);
          }

          continue;
        }

        return "Not an alias-info dump file: unexpected element " + child;
      }
      return "Ok";
    } catch (final Throwable t) {
      error(t);
      return t.getMessage();
    }
  }

  @Override
  public String fetchExternalSubs() {
    try {
      dump = new DumpThread(false);

      dump.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public String checkExternalSubs() {
    if (externalSubs == null) {
      return "No external subscriptions - do dump or restore first";
    }

    try {
      subs = new SubsThread();

      subs.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public List<String> checkSubsStatus() {
    if (subs == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Subscriptions check has not been started");

      return infoLines;
    }

    return subs.infoLines;
  }

  @Override
  public String checkAliases() {
    if (aliasInfo.isEmpty()) {
      return "No alias info - do dump or restore first";
    }

    try {
      aliases = new AliasesThread();

      aliases.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public List<String> checkAliasStatus() {
    if (aliases == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Aliases check has not been started");

      return infoLines;
    }

    return aliases.infoLines;
  }

  @Override
  public String dumpData() {
    try {
      dump = new DumpThread(true);

      dump.start();

      return "OK";
    } catch (final Throwable t) {
      error(t);

      return "Exception: " + t.getLocalizedMessage();
    }
  }

  @Override
  public synchronized List<String> dumpStatus() {
    if (dump == null) {
      final InfoLines infoLines = new InfoLines();

      infoLines.addLn("Dump has not been started");

      return infoLines;
    }

    return dump.infoLines;
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  private BwIndexCtlMBean indexer;

  private boolean disableIndexer() throws CalFacadeException {
    try {
      if (indexer == null) {
        indexer = (BwIndexCtlMBean)MBeanUtil.getMBean(BwIndexCtlMBean.class,
                                                      "org.bedework.bwengine:service=indexing");
      }

      indexer.setDiscardMessages(true);
      return true;
    } catch (final Throwable t) {
      error(t);
      return false;
    }
  }

  private boolean reindex() throws CalFacadeException {
    try {
      if (indexer == null) {
        return false;
      }
      indexer.rebuildIndex();
      indexer.setDiscardMessages(false);
      return true;
    } catch (final Throwable t) {
      error(t);
      return false;
    }
  }

  private String makeFilename(final String val) throws Throwable {
    final StringBuilder fname = new StringBuilder(getDataOut());
    if (!getDataOut().endsWith("/")) {
      fname.append("/");
    }

    fname.append(val);

    /* append "yyyyMMddTHHmmss" */
    fname.append(DateTimeUtil.isoDateTime());
    fname.append(".xml");

    return fname.toString();
  }

  /** Get an svci object and return it. Also embed it in this object.
   *
   * @return svci object
   * @throws CalFacadeException
   */
  private CalSvcI getSvci(final AliasInfo ai) throws CalFacadeException {
    if ((svci != null) && svci.isOpen()) {
      return svci;
    }

    boolean publicAdmin = false;

    if ((curSvciOwner == null) || !curSvciOwner.equals(ai.getOwner())) {
      svci = null;

      curSvciOwner = ai.getOwner();
      publicAdmin = ai.getPublick();
    }

    if (svci == null) {
      final CalSvcIPars pars = CalSvcIPars.getIndexerPars(curSvciOwner,
                                                          publicAdmin);
      //CalSvcIPars pars = CalSvcIPars.getServicePars(curSvciOwner,
      //                                              publicAdmin,   // publicAdmin
      //                                              true);   // Allow super user
      svci = new CalSvcFactoryDefault().getSvc(pars);
    }

    svci.open();
    svci.beginTransaction();

    return svci;
  }

  /**
   * @throws CalFacadeException
   */
  public void closeSvci() throws CalFacadeException {
    if ((svci == null) || !svci.isOpen()) {
      return;
    }

    try {
      svci.endTransaction();
    } catch (final Throwable t) {
      error(t);
    }

    try {
      svci.close();
    } catch (final Throwable ignored) {
    }
  }
}
