/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.ejb3.deployment.processors;

import static org.jboss.as.ejb3.logging.EjbLogger.ROOT_LOGGER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.jboss.as.controller.capability.CapabilityServiceSupport;
import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.ComponentConfiguration;
import org.jboss.as.ee.component.ComponentConfigurator;
import org.jboss.as.ee.component.ComponentDescription;
import org.jboss.as.ee.component.DependencyConfigurator;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ejb3.component.EJBComponentCreateService;
import org.jboss.as.ejb3.component.EJBComponentDescription;
import org.jboss.as.ejb3.deployment.EjbDeploymentAttachmentKeys;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.as.ejb3.subsystem.deployment.TimerServiceResource;
import org.jboss.as.ejb3.timerservice.NonFunctionalTimerServiceFactoryServiceConfigurator;
import org.jboss.as.ejb3.timerservice.TimedObjectInvokerFactoryImpl;
import org.jboss.as.ejb3.timerservice.TimerServiceFactoryServiceConfigurator;
import org.jboss.as.ejb3.timerservice.TimerServiceMetaData;
import org.jboss.as.ejb3.timerservice.TimerServiceRegistryImpl;
import org.jboss.as.ejb3.timerservice.composite.CompositeTimerServiceFactoryServiceConfigurator;
import org.jboss.as.ejb3.timerservice.distributable.DistributableTimerServiceFactoryServiceConfigurator;
import org.jboss.as.ejb3.timerservice.spi.ManagedTimerServiceConfiguration.TimerFilter;
import org.jboss.as.ejb3.timerservice.spi.ManagedTimerServiceFactory;
import org.jboss.as.ejb3.timerservice.spi.ManagedTimerServiceFactoryConfiguration;
import org.jboss.as.ejb3.timerservice.spi.TimedObjectInvokerFactory;
import org.jboss.as.ejb3.timerservice.spi.TimerListener;
import org.jboss.as.ejb3.timerservice.spi.TimerServiceRegistry;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.EjbDeploymentMarker;
import org.jboss.metadata.ejb.spec.EjbJarMetaData;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.wildfly.clustering.ejb.BeanConfiguration;
import org.wildfly.clustering.ejb.timer.LegacyTimerManagementProviderFactory;
import org.wildfly.clustering.ejb.timer.TimerManagementProvider;

/**
 * Deployment processor that sets up the timer service for singletons and stateless session beans
 *
 * NOTE: References in this document to Enterprise JavaBeans(EJB) refer to the Jakarta Enterprise Beans unless otherwise noted.
 *
 * @author Stuart Douglas
 */
public class TimerServiceDeploymentProcessor implements DeploymentUnitProcessor {

    private final String threadPoolName;
    private final String defaultTimerDataStore;
    private final TimerManagementProvider provider;

    public TimerServiceDeploymentProcessor(final String threadPoolName, final String defaultTimerDataStore) {
        this.threadPoolName = threadPoolName;
        this.defaultTimerDataStore = defaultTimerDataStore;

        this.provider = loadPersistentTimerManagementProvider();
    }

    private static TimerManagementProvider loadPersistentTimerManagementProvider() {
        for (LegacyTimerManagementProviderFactory factory : ServiceLoader.load(LegacyTimerManagementProviderFactory.class, LegacyTimerManagementProviderFactory.class.getClassLoader())) {
            return factory.createTimerManagementProvider();
        }
        return null;
    }

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        if (!EjbDeploymentMarker.isEjbDeployment(deploymentUnit)) return;

        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);

        final EjbJarMetaData ejbJarMetaData = deploymentUnit.getAttachment(EjbDeploymentAttachmentKeys.EJB_JAR_METADATA);

        // support for using capabilities to resolve service names
        CapabilityServiceSupport capabilityServiceSupport = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.CAPABILITY_SERVICE_SUPPORT);

        // if this is an EJB deployment then create an EJB module level TimerServiceRegistry which can be used by the timer services
        // of all EJB components that belong to this EJB module.
        final TimerServiceRegistry timerServiceRegistry = new TimerServiceRegistryImpl();

        Map<String, String> stores = new HashMap<>();
        stores.put(null, this.defaultTimerDataStore);

        // determine the per-EJB timer persistence service names required
        if (ejbJarMetaData != null && ejbJarMetaData.getAssemblyDescriptor() != null) {
            List<TimerServiceMetaData> timerService = ejbJarMetaData.getAssemblyDescriptor().getAny(TimerServiceMetaData.class);
            if (timerService != null) {
                for (TimerServiceMetaData data : timerService) {
                    String name = data.getEjbName();
                    stores.put(name.equals("*") ? null : name, data.getDataStoreName());
                }
            }
        }

        String threadPoolName = this.threadPoolName;
        String defaultStore = stores.get(null);

        StringBuilder deploymentNameBuilder = new StringBuilder();
        deploymentNameBuilder.append(moduleDescription.getApplicationName()).append('.').append(moduleDescription.getModuleName());
        String distinctName = moduleDescription.getDistinctName();
        if ((distinctName != null) && !distinctName.isEmpty()) {
            deploymentNameBuilder.append('.').append(distinctName);
        }
        String deploymentName = deploymentNameBuilder.toString();

        TimedObjectInvokerFactory invokerFactory = new TimedObjectInvokerFactoryImpl(module, deploymentName);
        TimerManagementProvider provider = this.provider;

        for (final ComponentDescription componentDescription : moduleDescription.getComponentDescriptions()) {

            // Install per-EJB timer service factories
            if (componentDescription.isTimerServiceApplicable()) {
                ServiceName serviceName = componentDescription.getServiceName().append("timer-service-factory");

                componentDescription.getConfigurators().add(new ComponentConfigurator() {
                    @Override
                    public void configure(DeploymentPhaseContext context, ComponentDescription description, ComponentConfiguration configuration) {
                        ROOT_LOGGER.debugf("Installing timer service factory for component %s", componentDescription.getComponentName());
                        EJBComponentDescription ejbComponentDescription = (EJBComponentDescription) description;
                        ServiceTarget target = context.getServiceTarget();
                        TimerServiceResource resource = new TimerServiceResource();
                        ManagedTimerServiceFactoryConfiguration factoryConfiguration = new ManagedTimerServiceFactoryConfiguration() {
                            @Override
                            public TimerServiceRegistry getTimerServiceRegistry() {
                                return timerServiceRegistry;
                            }

                            @Override
                            public TimerListener getTimerListener() {
                                return resource;
                            }

                            @Override
                            public TimedObjectInvokerFactory getInvokerFactory() {
                                return invokerFactory;
                            }
                        };

                        if (componentDescription.isTimerServiceRequired()) {
                            // the component has timeout methods, it needs a 'real' timer service

                            // Only register the TimerService resource if the component requires a TimerService.
                            ejbComponentDescription.setTimerServiceResource(resource);

                            String store = stores.getOrDefault(ejbComponentDescription.getEJBName(), defaultStore);

                            if (provider == null) {
                                new TimerServiceFactoryServiceConfigurator(serviceName, factoryConfiguration, threadPoolName, store).configure(capabilityServiceSupport).build(target).install();
                            } else {
                                ServiceName transientServiceName = TimerFilter.TRANSIENT.apply(serviceName);
                                ServiceName persistentServiceName = TimerFilter.PERSISTENT.apply(serviceName);

                                new TimerServiceFactoryServiceConfigurator(transientServiceName, factoryConfiguration, threadPoolName, null).filter(TimerFilter.TRANSIENT).configure(capabilityServiceSupport).build(target).install();

                                BeanConfiguration beanConfiguration = new BeanConfiguration() {
                                    @Override
                                    public String getName() {
                                        List<String> parts = new ArrayList<>(3);
                                        if (deploymentUnit.getParent() != null) {
                                            parts.add(deploymentUnit.getParent().getName());
                                        }
                                        parts.add(deploymentUnit.getName());
                                        parts.add(description.getComponentName());
                                        return String.join(".", parts);
                                    }

                                    @Override
                                    public ServiceName getDeploymentUnitServiceName() {
                                        return deploymentUnit.getServiceName();
                                    }

                                    @Override
                                    public Module getModule() {
                                        return module;
                                    }
                                };
                                new DistributableTimerServiceFactoryServiceConfigurator(persistentServiceName, factoryConfiguration, beanConfiguration, provider, TimerFilter.PERSISTENT).configure(capabilityServiceSupport).build(target).install();

                                new CompositeTimerServiceFactoryServiceConfigurator(serviceName, factoryConfiguration).build(target).install();
                            }
                        } else {
                            // the EJB is of a type that could have a timer service, but has no timer methods. just bind the non-functional timer service

                            String message = ejbComponentDescription.isStateful() ? EjbLogger.ROOT_LOGGER.timerServiceMethodNotAllowedForSFSB(ejbComponentDescription.getComponentName()) : EjbLogger.ROOT_LOGGER.ejbHasNoTimerMethods();
                            new NonFunctionalTimerServiceFactoryServiceConfigurator(serviceName, message, factoryConfiguration).build(target).install();
                        }

                        configuration.getCreateDependencies().add(new DependencyConfigurator<EJBComponentCreateService>() {
                            @Override
                            public void configureDependency(ServiceBuilder<?> builder, EJBComponentCreateService service) {
                                builder.addDependency(serviceName, ManagedTimerServiceFactory.class, service.getTimerServiceFactoryInjector());
                            }
                        });
                    }
                });
            }
        }
    }
}
