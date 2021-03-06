/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api.management.stats;

import static java.lang.Boolean.getBoolean;
import static java.lang.System.currentTimeMillis;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_DISABLE_PAYLOAD_STATISTICS;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_ENABLE_STATISTICS;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mule.api.annotation.NoExtend;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.core.internal.management.stats.ApplicationStatistics;

/**
 * <code>AllStatistics</code> TODO
 */
@NoExtend
public class AllStatistics {

  private boolean isStatisticsEnabled = getBoolean(MULE_ENABLE_STATISTICS);
  private boolean payloadStatisticsDisabled = getBoolean(MULE_DISABLE_PAYLOAD_STATISTICS);

  private long startTime;
  private final ApplicationStatistics appStats;
  private final Map<String, FlowConstructStatistics> flowConstructStats = new HashMap<>();
  private final Map<String, PayloadStatistics> payloadStatistics = new ConcurrentHashMap<>();

  /**
   *
   */
  public AllStatistics() {
    clear();
    appStats = new ApplicationStatistics(this);
    appStats.setEnabled(isStatisticsEnabled);
    add(appStats);
  }

  public synchronized void clear() {
    for (FlowConstructStatistics statistics : getServiceStatistics()) {
      statistics.clear();
    }
    startTime = currentTimeMillis();
  }

  /**
   * Are statistics logged
   */
  public boolean isEnabled() {
    return isStatisticsEnabled;
  }

  /**
   * Enable statistics logs (this is a dynamic parameter)
   */
  public void setEnabled(boolean enable) {
    isStatisticsEnabled = enable;

    for (FlowConstructStatistics statistics : flowConstructStats.values()) {
      statistics.setEnabled(enable);
    }

    if (isPayloadStatisticsEnabled()) {
      enablePayloadStatistics(enable);
    }
  }

  public void enablePayloadStatistics(boolean b) {
    payloadStatisticsDisabled = !b;
    for (PayloadStatistics statistics : payloadStatistics.values()) {
      statistics.setEnabled(b);
    }
  }

  public synchronized long getStartTime() {
    return startTime;
  }

  public synchronized void setStartTime(long startTime) {
    this.startTime = startTime;
  }

  public synchronized void add(FlowConstructStatistics stat) {
    if (stat != null) {
      stat.setEnabled(isStatisticsEnabled);
      flowConstructStats.put(stat.getName(), stat);
    }
  }

  public synchronized void remove(FlowConstructStatistics stat) {
    if (stat != null) {
      flowConstructStats.remove(stat.getName());
    }
  }

  public synchronized Collection<FlowConstructStatistics> getServiceStatistics() {
    return flowConstructStats.values();
  }

  public FlowConstructStatistics getApplicationStatistics() {
    return appStats;
  }

  /**
   * @return the available payload statistics for all components.
   * @since 4.4, 4.3.1
   */
  public Collection<PayloadStatistics> getPayloadStatistics() {
    return payloadStatistics.values();
  }

  /**
   * @param component the component to get the statistics for.
   * @return the statistics for the provided {@code component}.
   * @since 4.4, 4.3.1
   */
  public PayloadStatistics computePayloadStatisticsIfAbsent(Component component) {
    return payloadStatistics.computeIfAbsent(component.getLocation().getLocation(),
                                             loc -> {
                                               final PayloadStatistics statistics =
                                                   new PayloadStatistics(loc, component.getIdentifier().toString());
                                               statistics.setEnabled(isPayloadStatisticsEnabled());
                                               return statistics;
                                             });
  }

  /**
   * @param componentLocation the location of the component to get the statistics for.
   * @return the statistics for the component with the provided {@code componentLocation}.
   * @since 4.4, 4.3.1
   */
  public PayloadStatistics getPayloadStatistics(String componentLocation) {
    return payloadStatistics.get(componentLocation);
  }

  /**
   * @return whether the payload statistics are enabled
   * @since 4.4, 4.3.1
   */
  public boolean isPayloadStatisticsEnabled() {
    return isEnabled() && !payloadStatisticsDisabled;
  }
}
