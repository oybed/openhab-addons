/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.samsungtv.internal.service;

import static org.openhab.binding.samsungtv.internal.SamsungTvBindingConstants.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.eclipse.smarthome.io.transport.upnp.UpnpIOParticipant;
import org.eclipse.smarthome.io.transport.upnp.UpnpIOService;
import org.openhab.binding.samsungtv.internal.service.api.EventListener;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The {@link MainTVServerService} is responsible for handling MainTVServer
 * commands.
 *
 * @author Pauli Anttila - Initial contribution
 */
@NonNullByDefault
public class MainTVServerService implements UpnpIOParticipant, SamsungTvService {

    public static final String SERVICE_NAME = "MainTVServer2";
    private static final List<String> SUPPORTEDCOMMANDS = Arrays.asList(SOURCE_NAME, BROWSER_URL, STOP_BROWSER);

    private final Logger logger = LoggerFactory.getLogger(MainTVServerService.class);

    private UpnpIOService service;

    private ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> pollingJob;

    private String udn;
    private int pollingInterval;

    private Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<String, String>());

    private Set<EventListener> listeners = new CopyOnWriteArraySet<>();

    public MainTVServerService(UpnpIOService upnpIOService, String udn, int pollingInterval) {
        logger.debug("Creating a Samsung TV MainTVServer service");

        service = upnpIOService;

        this.udn = udn;
        this.pollingInterval = pollingInterval;

        scheduler = Executors.newScheduledThreadPool(1);
    }

    @Override
    public List<String> getSupportedChannelNames() {
        return SUPPORTEDCOMMANDS;
    }

    @Override
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEventListener(EventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void start() {
        if (pollingJob == null || pollingJob.isCancelled()) {
            logger.debug("Start refresh task, interval={}", pollingInterval);
            pollingJob = scheduler.scheduleWithFixedDelay(pollingRunnable, 0, pollingInterval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if (pollingJob != null && !pollingJob.isCancelled()) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public void clearCache() {
        stateMap.clear();
    }

    @Override
    public boolean isUpnp() {
        return true;
    }

    private Runnable pollingRunnable = () -> {
        if (isRegistered()) {
            updateResourceState("MainTVAgent2", "GetCurrentMainTVChannel", null);
            updateResourceState("MainTVAgent2", "GetCurrentExternalSource", null);
            updateResourceState("MainTVAgent2", "GetCurrentContentRecognition", null);
            updateResourceState("MainTVAgent2", "GetCurrentBrowserURL", null);
        }
    };

    @Override
    public void handleCommand(String channel, Command command) {
        logger.debug("Received channel: {}, command: {}", channel, command);

        switch (channel) {
            case SOURCE_NAME:
                setSourceName(command);
                // Clear value on cache to force update
                stateMap.put("CurrentExternalSource", "");
                break;
            case BROWSER_URL:
                setBrowserUrl(command);
                // Clear value on cache to force update
                stateMap.put("BrowserURL", "");
                break;
            case STOP_BROWSER:
                stopBrowser(command);
                break;
            default:
                logger.warn("Samsung TV doesn't support transmitting for channel '{}'", channel);
        }
    }

    private boolean isRegistered() {
        return service.isRegistered(this);
    }

    @Override
    public String getUDN() {
        return udn;
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null) {
            return;
        }

        String oldValue = stateMap.get(variable);
        if ((value == null && oldValue == null) || (value != null && value.equals(oldValue))) {
            logger.trace("Value '{}' for {} hasn't changed, ignoring update", value, variable);
            return;
        }

        stateMap.put(variable, (value != null) ? value : "");

        for (EventListener listener : listeners) {

            switch (variable) {
                case "ProgramTitle":
                    listener.valueReceived(PROGRAM_TITLE, (value != null) ? new StringType(value) : UnDefType.UNDEF);
                    break;
                case "ChannelName":
                    listener.valueReceived(CHANNEL_NAME, (value != null) ? new StringType(value) : UnDefType.UNDEF);
                    break;
                case "CurrentExternalSource":
                    listener.valueReceived(SOURCE_NAME, (value != null) ? new StringType(value) : UnDefType.UNDEF);
                    break;
                case "CurrentChannel":
                    String currentChannel = (value != null) ? parseCurrentChannel(value) : null;
                    listener.valueReceived(CHANNEL,
                            currentChannel != null ? new DecimalType(currentChannel) : UnDefType.UNDEF);
                    break;
                case "ID":
                    listener.valueReceived(SOURCE_ID, (value != null) ? new DecimalType(value) : UnDefType.UNDEF);
                    break;
                case "BrowserURL":
                    listener.valueReceived(BROWSER_URL, (value != null) ? new StringType(value) : UnDefType.UNDEF);
                    break;
            }
        }
    }

    protected Map<String, String> updateResourceState(String serviceId, String actionId,
            @Nullable Map<String, String> inputs) {
        Map<String, String> result = service.invokeAction(this, serviceId, actionId, inputs);

        for (String variable : result.keySet()) {
            onValueReceived(variable, result.get(variable), serviceId);
        }

        return result;
    }

    private void setSourceName(Command command) {
        Map<String, String> result = updateResourceState("MainTVAgent2", "GetSourceList", null);

        String source = command.toString();
        String id = null;

        if (result.get("Result").equals("OK")) {
            String xml = result.get("SourceList");

            Map<String, String> list = parseSourceList(xml);
            if (list != null) {
                id = list.get(source);
            }
        } else {
            logger.warn("Source list query failed, result='{}'", result.get("Result"));
        }

        if (source != null && id != null) {
            result = updateResourceState("MainTVAgent2", "SetMainTVSource",
                    SamsungTvUtils.buildHashMap("Source", source, "ID", id, "UiID", "0"));

            if (result.get("Result").equals("OK")) {
                logger.debug("Command successfully executed");
            } else {
                logger.warn("Command execution failed, result='{}'", result.get("Result"));
            }
        } else {
            logger.warn("Source id for '{}' couldn't be found", command.toString());
        }
    }

    private void setBrowserUrl(Command command) {
        Map<String, String> result = updateResourceState("MainTVAgent2", "RunBrowser",
                SamsungTvUtils.buildHashMap("BrowserURL", command.toString()));

        if (result.get("Result").equals("OK")) {
            logger.debug("Command successfully executed");
        } else {
            logger.warn("Command execution failed, result='{}'", result.get("Result"));
        }
    }

    private void stopBrowser(Command command) {
        Map<String, String> result = updateResourceState("MainTVAgent2", "StopBrowser", null);

        if (result.get("Result").equals("OK")) {
            logger.debug("Command successfully executed");
        } else {
            logger.warn("Command execution failed, result='{}'", result.get("Result"));
        }
    }

    private @Nullable String parseCurrentChannel(@Nullable String xml) {
        String majorCh = null;

        if (xml != null) {
            Document dom = SamsungTvUtils.loadXMLFromString(xml);

            if (dom != null) {
                NodeList nodeList = dom.getDocumentElement().getElementsByTagName("MajorCh");

                if (nodeList != null) {
                    majorCh = nodeList.item(0).getFirstChild().getNodeValue();
                }
            }
        }

        return majorCh;
    }

    private Map<String, String> parseSourceList(String xml) {
        Map<String, String> list = new HashMap<String, String>();

        Document dom = SamsungTvUtils.loadXMLFromString(xml);

        if (dom != null) {
            NodeList nodeList = dom.getDocumentElement().getElementsByTagName("Source");

            if (nodeList != null) {
                for (int i = 0; i < nodeList.getLength(); i++) {

                    String sourceType = null;
                    String id = null;

                    Element element = (Element) nodeList.item(i);
                    NodeList l = element.getElementsByTagName("SourceType");
                    if (l != null && l.getLength() > 0) {
                        sourceType = l.item(0).getFirstChild().getNodeValue();
                    }
                    l = element.getElementsByTagName("ID");
                    if (l != null && l.getLength() > 0) {
                        id = l.item(0).getFirstChild().getNodeValue();
                    }

                    if (sourceType != null && id != null) {
                        list.put(sourceType, id);
                    }
                }
            }
        }

        return list;
    }

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("onStatusChanged: status={}", status);
    }

    private void reportError(String message, Throwable e) {
        for (EventListener listener : listeners) {
            listener.reportError(ThingStatusDetail.COMMUNICATION_ERROR, message, e);
        }
    }
}
