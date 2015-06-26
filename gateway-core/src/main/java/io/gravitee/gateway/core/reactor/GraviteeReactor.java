/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.core.reactor;

import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.core.Reactor;
import io.gravitee.gateway.core.event.Event;
import io.gravitee.gateway.core.event.EventListener;
import io.gravitee.gateway.core.event.EventManager;
import io.gravitee.gateway.core.handler.ContextHandler;
import io.gravitee.gateway.core.handler.Handler;
import io.gravitee.gateway.core.handler.context.ApiHandlerConfiguration;
import io.gravitee.gateway.core.http.ServerResponse;
import io.gravitee.gateway.core.service.ApiLifecycleEvent;
import io.gravitee.gateway.core.service.ApiService;
import io.gravitee.model.Api;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public abstract class GraviteeReactor<T> implements Reactor<T>, EventListener<ApiLifecycleEvent, Api>, ApplicationContextAware {

    private final Logger LOGGER = LoggerFactory.getLogger(GraviteeReactor.class);

    @Autowired
    private EventManager eventManager;

    @Autowired
    private ApiService apiService;

    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("errorHandler")
    private Handler errorHandler;

    private final ConcurrentMap<String, ContextHandler> handlers = new ConcurrentHashMap();

    protected Handler getHandler(Request request) {
        String path = request.path();

        Set<ContextHandler> mapHandlers = handlers.entrySet().stream().filter(
                entry -> path.startsWith(entry.getKey())).map(Map.Entry::getValue).collect(Collectors.toSet());

        if (! mapHandlers.isEmpty()) {

            // Sort valid handlers and push handler with VirtualHost first
            ContextHandler[] sorted = new ContextHandler[mapHandlers.size()];
            int idx = 0;
            for (ContextHandler handler : mapHandlers) {
                if (handler.hasVirtualHost()) {
                    sorted[idx++] = handler;
                }
            }
            for (ContextHandler handler : mapHandlers) {
                if (!handler.hasVirtualHost()) {
                    sorted[idx++] = handler;
                }
            }

            String host = getHost(request);

            // Always pick-up the first which is corresponding
            for (ContextHandler handler : sorted) {
                if (host.equals(handler.getVirtualHost())) {
                    return handler;
                }
            }
        }

        return errorHandler;
    }

    public void clearHandlers() {
        handlers.forEach((s, contextHandler) -> removeHandler(s));
    }

    protected T handle(Request request) {
        return (T) getHandler(request).handle(request, new ServerResponse());
    }

    private String getHost(Request request) {
        String host = request.headers().get(HttpHeader.HOST.asString());
        if (host == null || host.isEmpty()) {
            return URI.create(request.uri()).getHost();
        } else {
            return host;
        }
    }

    @Override
    public void onEvent(Event<ApiLifecycleEvent, Api> event) {
        switch(event.type()) {
            case START:
                addHandler(event.content());
                break;
            case STOP:
                removeHandler(event.content());
                break;
        }
    }

    public void addHandler(Api api) {
        LOGGER.info("API {} has been enabled in reactor", api);

        AbstractApplicationContext childContext = buildApplicationContext(api);
        ContextHandler handler = childContext.getBean(ContextHandler.class);

        handlers.putIfAbsent(handler.getContextPath(), handler);
    }

    public void removeHandler(Api api) {
        LOGGER.info("API {} has been disabled (or removed) in reactor", api);
        removeHandler(api.getPublicURI().getPath());
    }

    public void removeHandler(String contextPath) {
        //TODO: Close application context relative to the handler
        handlers.remove(contextPath);
    }

    private AbstractApplicationContext buildApplicationContext(Api api) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setParent(this.applicationContext);
        context.getBeanFactory().registerSingleton("api", api);
        context.register(ApiHandlerConfiguration.class);
        context.refresh();

        return context;
    }

    @PostConstruct
    public void init() {
        eventManager.subscribeForEvents(this, ApiLifecycleEvent.class);
        //TODO: Not sure it's the best place to do the following...
        apiService.startAll();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
