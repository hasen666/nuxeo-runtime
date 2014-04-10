package org.nuxeo.runtime.datasource.geronimo;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.resource.ResourceException;
import javax.resource.spi.InvalidPropertyException;
import javax.resource.spi.ManagedConnectionFactory;
import javax.sql.XADataSource;

import org.nuxeo.runtime.datasource.PooledDataSourceRegistry;
import org.nuxeo.runtime.datasource.PooledDataSourceRegistry.PooledDataSource;
import org.nuxeo.runtime.jtajca.NuxeoConnectionManagerConfiguration;
import org.nuxeo.runtime.jtajca.NuxeoConnectionManagerFactory;
import org.nuxeo.runtime.jtajca.NuxeoContainer;
import org.nuxeo.runtime.jtajca.NuxeoContainer.ConnectionManagerWrapper;
import org.tranql.connector.jdbc.JDBCDriverMCF;
import org.tranql.connector.jdbc.TranqlDataSource;
import org.tranql.connector.jdbc.XADataSourceWrapper;

public class PooledDataSourceFactory implements
        PooledDataSourceRegistry.Factory {

    protected static class DataSource extends TranqlDataSource implements
            PooledDataSource {

        protected ConnectionManagerWrapper wrapper;

        public DataSource(ManagedConnectionFactory mcf,
                ConnectionManagerWrapper wrapper) {
            super(mcf, wrapper);
            this.wrapper = wrapper;
        }

        @Override
        public void dispose() throws Exception {
            wrapper.dispose();
        }

        @Override
        public Connection getConnection(boolean noSharing) throws SQLException {
            if (!noSharing) {
                return getConnection();
            }
            wrapper.enterNoSharing();
            try {
                return getConnection();
            } finally {
                wrapper.exitNoSharing();
            }
        }
    }

    @Override
    public Object getObjectInstance(Object obj, Name name, Context ctx,
            Hashtable<?, ?> environment) throws Exception {
        Reference ref = (Reference) obj;
        ManagedConnectionFactory mcf = createFactory(ref, ctx);
        ConnectionManagerWrapper cm = createManager(ref, ctx);
        return new DataSource(mcf, cm);
    }

    protected ConnectionManagerWrapper createManager(Reference ref, Context ctx)
            throws ResourceException {
        NuxeoConnectionManagerConfiguration config = NuxeoConnectionManagerFactory.getConfig(ref);
        String className = ref.getClassName();
        config.setXAMode(XADataSource.class.getName().equals(className));
        return NuxeoContainer.initConnectionManager(config);
    }

    protected ManagedConnectionFactory createFactory(Reference ref, Context ctx)
            throws NamingException, InvalidPropertyException {
        String className = ref.getClassName();
        String user = refAttribute(ref, "user", "");
        String password = refAttribute(ref, "password", "");
        if (XADataSource.class.getName().equals(className)) {
            String name = refAttribute(ref, "dataSourceJNDI", null);
            XADataSource ds = (XADataSource) new InitialContext().lookup(name);
            XADataSourceWrapper wrapper = new XADataSourceWrapper(ds);
            wrapper.setUserName(user);
            wrapper.setPassword(password);
            return wrapper;
        }
        if (javax.sql.DataSource.class.getName().equals(className)) {
            String name = refAttribute(ref, "driverClassName", null);
            String url = refAttribute(ref, "url", null);
            JDBCDriverMCF factory = new JDBCDriverMCF();
            factory.setDriver(name);
            factory.setUserName(user);
            factory.setPassword(password);
            factory.setConnectionURL(url);
            return factory;
        }
        throw new IllegalArgumentException("unsupported class " + className);
    }

    protected String refAttribute(Reference ref, String key, String defvalue) {
        RefAddr addr = ref.get(key);
        if (addr == null) {
            if (defvalue == null) {
                throw new IllegalArgumentException(key
                        + " address is mandatory");
            }
            return defvalue;
        }
        return (String) addr.getContent();
    }

}