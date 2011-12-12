/*
 * (C) Copyright 2006-2011 Nuxeo SA (http://nuxeo.com/) and contributors.
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
 *     Bogdan Stefanescu
 *     Julien Carsique
 *     Florent Guillaume
 */
package org.nuxeo.runtime.deployment.preprocessor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.launcher.config.ConfigurationGenerator;
import org.nuxeo.runtime.deployment.NuxeoStarter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Packs a Nuxeo Tomcat instance into a WAR file inside a ZIP.
 */
public class PackWar {

    private static Log log = LogFactory.getLog(PackWar.class);

    private static final List<String> MISSING_WEBINF_LIBS = Arrays.asList( //
            "mail", //
            "freemarker");

    private static final List<String> MISSING_LIBS = Arrays.asList( //
            // WSS
            "nuxeo-generic-wss-front", //
            // dependencies of above
            "log4j", //
            "commons-logging", //
            "commons-lang", //
            // JDBC drivers
            "derby", //
            "h2", //
            "ojdbc", //
            "postgresql", //
            "mysql-connector-java", //
            // JDBC
            "nuxeo-core-storage-sql-extensions", // for Derby/H2
            "lucene" // for H2
    );

    private static final List<String> ENDORSED_LIBS = Arrays.asList( //
            "jaxb-api", //
            "jaxws-api" //
    );

    private static final String ZIP_ENDORSED = "endorsed/";

    private static final String ZIP_LIB = "lib/";

    private static final String ZIP_WEBAPPS_NUXEO = "webapps/nuxeo/";

    private static final String ZIP_WEBINF = ZIP_WEBAPPS_NUXEO + "WEB-INF/";

    private static final String ZIP_WEBINF_LIB = ZIP_WEBINF + "lib/";

    private static final String ZIP_README = "README-NUXEO.txt";

    private static final String README_BEGIN = //
    "This ZIP must be uncompressed at the root of your Tomcat instance.\n" //
            + "\n" //
            + "In order for Nuxeo to run, the following Resource defining your JDBC datasource configuration\n" //
            + "must be added inside the <GlobalNamingResources> section of the file conf/server.xml\n" //
            + "\n  ";

    private static final String README_END = "\n\n" //
            + "Make sure that the 'url' attribute above is correct.\n" //
            + "Note that the following file also contains database configuration:\n" //
            + "\n" //
            + "  webapps/nuxeo/WEB-INF/default-repository-config.xml\n" //
            + "\n" //
            + "Also note that you should start Tomcat with more memory than its default, for instance:\n" //
            + "\n" //
            + "  JAVA_OPTS=\"-Xms512m -Xmx1024m -XX:MaxPermSize=512m\" bin/catalina.sh start\n" //
            + "\n" //
            + "";

    private static final String COMMAND_PREPROCESSING = "preprocessing";

    private static final String COMMAND_PACKAGING = "packaging";

    protected File nxserver;

    protected File tomcat;

    protected File zip;

    public PackWar(File nxserver, File zip) {
        if (!nxserver.isDirectory() || !nxserver.getName().equals("nxserver")) {
            fail("No nxserver found at " + nxserver);
        }
        if (zip.exists()) {
            fail("Target ZIP file " + zip + " already exists");
        }
        this.nxserver = nxserver;
        tomcat = nxserver.getParentFile();
        this.zip = zip;
    }

    public void execute(String command) throws Exception {
        boolean preprocessing = COMMAND_PREPROCESSING.equals(command)
                || StringUtils.isBlank(command);
        boolean packaging = COMMAND_PACKAGING.equals(command)
                || StringUtils.isBlank(command);
        if (!preprocessing && !packaging) {
            fail("Command parameter should be empty or "
                    + COMMAND_PREPROCESSING + " or " + COMMAND_PACKAGING);
        }
        if (preprocessing) {
            executePreprocessing();
        }
        if (packaging) {
            executePackaging();
        }
    }

    protected void executePreprocessing() throws Exception {
        runTemplatePreprocessor();
        runDeploymentPreprocessor();
    }

    protected void runTemplatePreprocessor() throws Exception {
        if (System.getProperty(ConfigurationGenerator.NUXEO_HOME) == null) {
            System.setProperty(ConfigurationGenerator.NUXEO_HOME,
                    tomcat.getAbsolutePath());
        }
        if (System.getProperty(ConfigurationGenerator.NUXEO_CONF) == null) {
            System.setProperty(ConfigurationGenerator.NUXEO_CONF, new File(
                    tomcat, "bin/nuxeo.conf").getPath());
        }
        new ConfigurationGenerator().run();
    }

    protected void runDeploymentPreprocessor() throws Exception {
        DeploymentPreprocessor processor = new DeploymentPreprocessor(nxserver);
        processor.init();
        processor.predeploy();
    }

    protected void executePackaging() throws IOException {
        OutputStream out = new FileOutputStream(zip);
        ZipOutputStream zout = new ZipOutputStream(out);
        try {

            // extract jdbc datasource from server.xml into README
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            bout.write(README_BEGIN.getBytes("UTF-8"));
            ServerXmlProcessor.INSTANCE.process(
                    newFile(tomcat, "conf/server.xml"), bout);
            bout.write(README_END.getBytes("UTF-8"));
            zipBytes(ZIP_README, bout.toByteArray(), zout);

            File nuxeoXml = newFile(tomcat, "conf/Catalina/localhost/nuxeo.xml");
            zipFile(ZIP_WEBAPPS_NUXEO + "META-INF/context.xml", nuxeoXml, zout,
                    NuxeoXmlProcessor.INSTANCE);
            zipTree(ZIP_WEBAPPS_NUXEO, new File(nxserver, "nuxeo.war"), false,
                    zout);
            zipTree(ZIP_WEBINF, new File(nxserver, "config"), false, zout);
            zipTree(ZIP_WEBINF_LIB, new File(nxserver, "bundles"), false, zout);
            zipTree(ZIP_WEBINF_LIB, new File(nxserver, "lib"), false, zout);
            zipLibs(ZIP_WEBINF_LIB, new File(tomcat, "lib"),
                    MISSING_WEBINF_LIBS, zout);
            zipLibs(ZIP_LIB, new File(tomcat, "lib"), MISSING_LIBS, zout);
            zipLibs(ZIP_ENDORSED, new File(tomcat, "endorsed"), ENDORSED_LIBS,
                    zout);
        } finally {
            zout.finish();
            zout.close();
        }
    }

    protected static File newFile(File base, String path) {
        return new File(base, path.replace("/", File.separator));
    }

    protected void zipLibs(String prefix, File dir, List<String> patterns,
            ZipOutputStream zout) throws IOException {
        for (String name : dir.list()) {
            for (String pat : patterns) {
                if ((name.startsWith(pat + '-') && name.endsWith(".jar"))
                        || name.equals(pat + ".jar")) {
                    zipFile(prefix + name, new File(dir, name), zout, null);
                    break;
                }
            }
        }
    }

    protected void zipDirectory(String entryName, ZipOutputStream zout)
            throws IOException {
        ZipEntry zentry = new ZipEntry(entryName);
        zout.putNextEntry(zentry);
        zout.closeEntry();
    }

    protected void zipFile(String entryName, File file, ZipOutputStream zout,
            FileProcessor processor) throws IOException {
        ZipEntry zentry = new ZipEntry(entryName);
        if (processor == null) {
            processor = CopyProcessor.INSTANCE;
            zentry.setTime(file.lastModified());
        }
        zout.putNextEntry(zentry);
        processor.process(file, zout);
        zout.closeEntry();
    }

    protected void zipBytes(String entryName, byte[] bytes, ZipOutputStream zout)
            throws IOException {
        ZipEntry zentry = new ZipEntry(entryName);
        zout.putNextEntry(zentry);
        zout.write(bytes);
        zout.closeEntry();
    }

    /** prefix ends with '/' */
    protected void zipTree(String prefix, File root, boolean includeRoot,
            ZipOutputStream zout) throws IOException {
        if (includeRoot) {
            prefix += root.getName() + '/';
            zipDirectory(prefix, zout);
        }
        for (String name : root.list()) {
            File file = new File(root, name);
            if (file.isDirectory()) {
                zipTree(prefix, file, true, zout);
            } else {
                if (name.endsWith("~") //
                        || name.endsWith("#") //
                        || name.endsWith(".bak") //
                        || name.equals("README.txt")) {
                    continue;
                }
                name = prefix + name;
                FileProcessor processor;
                if (name.equals(ZIP_WEBINF + "web.xml")) {
                    processor = WebXmlProcessor.INSTANCE;
                } else {
                    processor = null;
                }
                zipFile(name, file, zout, processor);
            }
        }
    }

    protected interface FileProcessor {
        void process(File file, OutputStream out) throws IOException;
    }

    protected static class CopyProcessor implements FileProcessor {

        public static CopyProcessor INSTANCE = new CopyProcessor();

        @Override
        public void process(File file, OutputStream out) throws IOException {
            FileInputStream in = new FileInputStream(file);
            try {
                IOUtils.copy(in, out);
            } finally {
                in.close();
            }
        }
    }

    protected static abstract class XmlProcessor implements FileProcessor {

        @Override
        public void process(File file, OutputStream out) throws IOException {
            DocumentBuilder parser;
            try {
                parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw (IOException) new IOException().initCause(e);
            }
            InputStream in = new FileInputStream(file);
            try {
                Document doc = parser.parse(in);
                doc.setStrictErrorChecking(false);
                process(doc);
                Transformer trans = TransformerFactory.newInstance().newTransformer();
                trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                trans.setOutputProperty(OutputKeys.INDENT, "yes");
                trans.transform(new DOMSource(doc), new StreamResult(out));
            } catch (SAXException e) {
                throw (IOException) new IOException().initCause(e);
            } catch (TransformerException e) {
                throw (IOException) new IOException().initCause(e);
            } finally {
                in.close();
            }
        }

        protected abstract void process(Document doc);
    }

    protected static class WebXmlProcessor extends XmlProcessor {

        public static WebXmlProcessor INSTANCE = new WebXmlProcessor();

        private static final String LISTENER = "listener";

        private static final String LISTENER_CLASS = "listener-class";

        @Override
        protected void process(Document doc) {
            Node n = doc.getDocumentElement().getFirstChild();
            while (n != null) {
                if (LISTENER.equals(n.getNodeName())) {
                    // insert initial listener
                    Element listener = doc.createElement(LISTENER);
                    n.insertBefore(listener, n);
                    listener.appendChild(doc.createElement(LISTENER_CLASS)).appendChild(
                            doc.createTextNode(NuxeoStarter.class.getName()));
                    break;
                }
                n = n.getNextSibling();
            }
        }
    }

    protected static class NuxeoXmlProcessor extends XmlProcessor {

        public static NuxeoXmlProcessor INSTANCE = new NuxeoXmlProcessor();

        private static final String DOCBASE = "docBase";

        private static final String LOADER = "Loader";

        private static final String LISTENER = "Listener";

        @Override
        protected void process(Document doc) {
            Element root = doc.getDocumentElement();
            root.removeAttribute(DOCBASE);
            Node n = root.getFirstChild();
            while (n != null) {
                Node next = n.getNextSibling();
                String name = n.getNodeName();
                if (LOADER.equals(name) || LISTENER.equals(name)) {
                    root.removeChild(n);
                }
                n = next;
            }
        }
    }

    protected static class ServerXmlProcessor implements FileProcessor {

        public static ServerXmlProcessor INSTANCE = new ServerXmlProcessor();

        private static final String GLOBAL_NAMING_RESOURCES = "GlobalNamingResources";

        private static final String RESOURCE = "Resource";

        private static final String NAME = "name";

        private static final String JDBC_NUXEO = "jdbc/nuxeo";

        public String resource;

        @Override
        public void process(File file, OutputStream out) throws IOException {
            DocumentBuilder parser;
            try {
                parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw (IOException) new IOException().initCause(e);
            }
            InputStream in = new FileInputStream(file);
            try {
                Document doc = parser.parse(in);
                doc.setStrictErrorChecking(false);
                Element root = doc.getDocumentElement();
                Node n = root.getFirstChild();
                Element resourceElement = null;
                while (n != null) {
                    Node next = n.getNextSibling();
                    String name = n.getNodeName();
                    if (GLOBAL_NAMING_RESOURCES.equals(name)) {
                        next = n.getFirstChild();
                    }
                    if (RESOURCE.equals(name)) {
                        if (((Element) n).getAttribute(NAME).equals(JDBC_NUXEO)) {
                            resourceElement = (Element) n;
                            break;
                        }
                    }
                    n = next;
                }
                Transformer trans = TransformerFactory.newInstance().newTransformer();
                trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                trans.setOutputProperty(OutputKeys.INDENT, "no");
                trans.transform(new DOMSource(resourceElement), // only resource
                        new StreamResult(out));
            } catch (SAXException e) {
                throw (IOException) new IOException().initCause(e);
            } catch (TransformerException e) {
                throw (IOException) new IOException().initCause(e);
            } finally {
                in.close();
            }
        }

    }

    public static void fail(String message) {
        fail(message, null);
    }

    public static void fail(String message, Throwable t) {
        log.error(message, t);
        System.exit(1);
    }

    public static void main(String[] args) {
        if (args.length < 2
                || args.length > 3
                || (args.length == 3 && !Arrays.asList(COMMAND_PREPROCESSING,
                        COMMAND_PACKAGING).contains(args[2]))) {
            fail(String.format(
                    "Usage: %s <nxserver_dir> <target_zip> [command]\n"
                            + "    command may be empty or '%s' or '%s'",
                    PackWar.class.getSimpleName(), COMMAND_PREPROCESSING,
                    COMMAND_PACKAGING));
        }

        File nxserver = new File(args[0]).getAbsoluteFile();
        File zip = new File(args[1]).getAbsoluteFile();
        String command = args.length == 3 ? args[2] : null;

        log.info("Packing nuxeo WAR at " + nxserver + " into " + zip);
        try {
            new PackWar(nxserver, zip).execute(command);
        } catch (Exception e) {
            fail("Pack failed", e);
        }
    }

}
