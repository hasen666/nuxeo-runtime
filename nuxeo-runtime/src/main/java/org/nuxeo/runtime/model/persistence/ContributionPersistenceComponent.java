/*
 * (C) Copyright 2006-2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bstefanescu
 */
package org.nuxeo.runtime.model.persistence;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.RegistrationInfo;
import org.nuxeo.runtime.model.RuntimeContext;
import org.nuxeo.runtime.model.persistence.fs.FileSystemStorage;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 * 
 */
public class ContributionPersistenceComponent extends DefaultComponent
        implements ContributionPersistenceManager, FrameworkListener {

    private static final Log log = LogFactory.getLog(ContributionPersistenceComponent.class);

    public static final String STORAGE_XP = "storage";

    protected ContributionStorage storage;

    protected RuntimeContext ctx;

    public static String getComponentName(String contribName) {
        return "config:" + contribName;
    }

    @Override
    public void activate(ComponentContext context) throws Exception {
        super.activate(context);
        this.ctx = context.getRuntimeContext();
        storage = new FileSystemStorage();
        ctx.getBundle().getBundleContext().addFrameworkListener(this);
    }

    @Override
    public void deactivate(ComponentContext context) throws Exception {
        super.deactivate(context);
        ctx = null;
        storage = null;
    }

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        // This extension point is a singleton. It supports only one
        // contribution!
        // I am not using a runtime property to specify the implementation class
        // because
        // of possible problems caused by class loaders in real OSGI frameworks.
        ContributionStorageDescriptor c = (ContributionStorageDescriptor) contribution;
        storage = (ContributionStorage) c.clazz.newInstance();
    }

    @Override
    public void unregisterContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        storage = null;
    }

    public List<Contribution> getContributions() throws Exception {
        return storage.getContributions();
    }

    public Contribution getContribution(String name) throws Exception {
        return storage.getContribution(name);
    }

    public Contribution addContribution(Contribution contrib) throws Exception {
        return storage.addContribution(contrib);
    }

    public Contribution addAndInstallContribution(Contribution contrib)
            throws Exception {
        Contribution c = storage.addContribution(contrib);
        if (c == null) {
            return null;
        }
        installContribution(contrib);
        return c;
    }

    public boolean removeContribution(Contribution contrib) throws Exception {
        return storage.removeContribution(contrib);
    }

    public boolean removeAndUninstallContribution(Contribution contrib)
            throws Exception {
        uninstallContribution(contrib);
        removeContribution(contrib);
        return true;
    }

    public boolean isInstalled(Contribution contrib) throws Exception {
        return ctx.isDeployed(contrib);
    }

    public synchronized boolean installContribution(Contribution contrib)
            throws Exception {
        RegistrationInfo ri = ctx.deploy(contrib);
        if (ri == null) {
            return false;
        }
        ri.setPersistent(true);
        return true;
    }

    public boolean uninstallContribution(Contribution contrib) throws Exception {
        ctx.undeploy(contrib);
        return true;
    }

    public Contribution updateContribution(Contribution contribution)
            throws Exception {
        return storage.updateContribution(contribution);
    }

    public boolean isPersisted(String name) throws Exception {
        return storage.getContribution(name) != null;
    }

    public void start() throws Exception {
        for (Contribution c : storage.getContributions()) {
            if (!c.isDisabled()) {
                installContribution(c);
            }
        }
    }

    public void stop() throws Exception {
        for (Contribution c : storage.getContributions()) {
            if (!c.isDisabled()) {
                uninstallContribution(c);
            }
        }
    }

    public void frameworkEvent(FrameworkEvent event) {
        if (event.getType() == FrameworkEvent.STARTED) {
            if (storage == null) {
                storage = new FileSystemStorage();
                try {
                    start();
                } catch (Exception e) {
                    log.error(
                            "Failed to start contribution persistence service",
                            e);
                }
            }
        }
    }
}