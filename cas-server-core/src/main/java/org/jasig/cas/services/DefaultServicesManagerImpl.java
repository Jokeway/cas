/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.cas.services;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.jasig.cas.server.authentication.Service;
import org.jasig.cas.server.login.ServiceAccessRequest;
import org.jasig.cas.server.session.Access;
import org.jasig.cas.server.session.RegisteredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;

/**
 * Default implementation of the {@link org.jasig.cas.server.session.ServicesManager} interface. If there are
 * no services registered with the server, it considers the ServicesManager
 * disabled and will not prevent any service from using CAS.
 * 
 * @author Scott Battaglia
 * @version $Revision$ $Date$
 * @since 3.1
 */
//@Named("servicesManager")
//@Singleton
public /*final*/ class DefaultServicesManagerImpl implements ReloadableServicesManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Instance of ServiceRegistryDao. */
    @NotNull
    private ServiceRegistryDao serviceRegistryDao;

    /** Map to store all services. */
    private ConcurrentHashMap<Long, RegisteredService> services = new ConcurrentHashMap<Long, RegisteredService>();

    /** Default service to return if none have been registered. */
    private RegisteredService disabledRegisteredService;

    @Inject
    public DefaultServicesManagerImpl(final ServiceRegistryDao serviceRegistryDao) {
        this(serviceRegistryDao, new ArrayList<String>());
    }
    
    /**
     * Constructs an instance of the {@link DefaultServicesManagerImpl} where the default RegisteredService
     * can include a set of default attributes to use if no services are defined in the registry.
     * 
     * @param serviceRegistryDao the Service Registry Dao.
     * @param defaultAttributes the list of default attributes to use.
     */
    public DefaultServicesManagerImpl(final ServiceRegistryDao serviceRegistryDao, final List<String> defaultAttributes) {
        this.serviceRegistryDao = serviceRegistryDao;
        this.disabledRegisteredService = constructDefaultRegisteredService(defaultAttributes);
        
        load();
    }

    @Transactional(readOnly = false)
    public synchronized RegisteredService delete(final long id) {
        final RegisteredService r = findServiceBy(id);
        if (r == null) {
            return null;
        }
        
        this.serviceRegistryDao.delete(r);
        this.services.remove(id);
        
        return r;
    }

    /**
     * Note, if the repository is empty, this implementation will return a default service to grant all access.
     * <p>
     * This preserves default CAS behavior.
     */
    public RegisteredService findServiceBy(final Access service) {
        final Collection<RegisteredService> c = convertToTreeSet();
        
        if (c.isEmpty()) {
            return this.disabledRegisteredService;
        }

        for (final RegisteredService r : c) {
            if (r.matches(service)) {
                return r;
            }
        }

        return null;
    }

    public RegisteredService findServiceBy(final long id) {
        final RegisteredService r = this.services.get(id);
        
        try {
            return r == null ? null : (RegisteredService) r.clone();
        } catch (final CloneNotSupportedException e) {
            return r;
        }
    }

    public RegisteredService findServiceBy(final ServiceAccessRequest access) {
        final Collection<RegisteredService> c = convertToTreeSet();

        if (c.isEmpty()) {
            return this.disabledRegisteredService;
        }

        for (final RegisteredService r : c) {
            if (r.matches(access.getServiceId())) {
                return r;
            }
        }

        return null;
    }

    protected TreeSet<RegisteredService> convertToTreeSet() {
        return new TreeSet<RegisteredService>(this.services.values());
    }

    public Collection<RegisteredService> getAllServices() {
        return Collections.unmodifiableCollection(this.services.values());
    }

    public boolean matchesExistingService(final Access service) {
        return findServiceBy(service) != null;
    }

    @Transactional(readOnly = false)
    public synchronized void save(final RegisteredService registeredService) {
        final RegisteredService r = this.serviceRegistryDao.save(registeredService);
        this.services.put(r.getId(), r);
    }

    @Scheduled(fixedDelay = 120000)
    public void reload() {
        log.info("Reloading registered services.");
        load();
    }

    public boolean matchesExistingService(final ServiceAccessRequest serviceAccessRequest) {
        return serviceAccessRequest != null && findServiceBy(serviceAccessRequest) != null;
    }

    private void load() {
        final ConcurrentHashMap<Long, RegisteredService> localServices = new ConcurrentHashMap<Long, RegisteredService>();
                
        for (final RegisteredService r : this.serviceRegistryDao.load()) {
            log.debug("Adding registered service " + r.getServiceId());
            localServices.put(r.getId(), r);
        }
        
        this.services = localServices;
        log.info(String.format("Loaded %s services.", this.services.size()));
    }
    
    private RegisteredService constructDefaultRegisteredService(final List<String> attributes) {
        final RegisteredServiceImpl r = new RegisteredServiceImpl();
        r.setAllowedToProxy(true);
        r.setAnonymousAccess(false);
        r.setEnabled(true);
        r.setSsoEnabled(true);
        r.setAllowedAttributes(attributes);
        
        if (attributes == null || attributes.isEmpty()) {
            r.setIgnoreAttributes(true);
        }

        return r;
    }
}
