/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.apache.logging.log4j.core.osgi;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.util.Constants;
import org.apache.logging.log4j.core.util.ContextDataProvider;
import org.apache.logging.log4j.plugins.di.InjectorCallback;
import org.apache.logging.log4j.plugins.processor.PluginService;
import org.apache.logging.log4j.spi.Provider;
import org.apache.logging.log4j.util.PropertiesUtil;
import org.apache.logging.log4j.util.ServiceRegistry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;

import java.util.concurrent.atomic.AtomicReference;

/**
 * OSGi BundleActivator.
 */
public final class Activator implements BundleActivator {
    private final AtomicReference<BundleContext> contextRef = new AtomicReference<>();

    @Override
    public void start(final BundleContext context) throws Exception {
        final ServiceRegistry registry = ServiceRegistry.getInstance();
        final Bundle bundle = context.getBundle();
        final long bundleId = bundle.getBundleId();
        final ClassLoader classLoader = bundle.adapt(BundleWiring.class).getClassLoader();
        registry.loadServicesFromBundle(PluginService.class, bundleId, classLoader);
        registry.loadServicesFromBundle(Provider.class, bundleId, classLoader);
        registry.loadServicesFromBundle(ContextDataProvider.class, bundleId, classLoader);
        registry.loadServicesFromBundle(InjectorCallback.class, bundleId, classLoader);
        // allow the user to override the default ContextSelector (e.g., by using BasicContextSelector for a global cfg)
        if (PropertiesUtil.getProperties().getStringProperty(Constants.LOG4J_CONTEXT_SELECTOR) == null) {
            System.setProperty(Constants.LOG4J_CONTEXT_SELECTOR, BundleContextSelector.class.getName());
        }
        contextRef.compareAndSet(null, context);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        ServiceRegistry.getInstance().unregisterBundleServices(context.getBundle().getBundleId());
        this.contextRef.compareAndSet(context, null);
        LogManager.shutdown(false, true);
    }
}
