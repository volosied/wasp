/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.wasp.servlet;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.glassfish.wasp.WaspException;
import org.glassfish.wasp.xmlparser.ParserUtils;
import org.glassfish.wasp.xmlparser.TreeNode;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;
import jakarta.servlet.descriptor.TaglibDescriptor;

/**
 * Simple <code>ServletContext</code> implementation without HTTP-specific methods.
 *
 * @author Peter Rossbach (pr@webapp.de)
 */
public class JspCServletContext implements ServletContext {

    // ----------------------------------------------------- Instance Variables

    /**
     * Servlet context attributes.
     */
    protected Hashtable<String, Object> myAttributes;

    /**
     * The log writer we will write log messages to.
     */
    protected PrintWriter myLogWriter;

    /**
     * The base URL (document root) for this context.
     */
    protected URL myResourceBaseURL;

    private JspConfigDescriptor jspConfigDescriptor;

    // ----------------------------------------------------------- Constructors

    /**
     * Create a new instance of this ServletContext implementation.
     *
     * @param aLogWriter PrintWriter which is used for <code>log()</code> calls
     * @param aResourceBaseURL Resource base URL
     */
    public JspCServletContext(PrintWriter aLogWriter, URL aResourceBaseURL) {
        myAttributes = new Hashtable<>();
        myLogWriter = aLogWriter;
        myResourceBaseURL = aResourceBaseURL;

        parseWebDotXml();
    }

    // --------------------------------------------------------- Public Methods

    /**
     * Return the specified context attribute, if any.
     *
     * @param name Name of the requested attribute
     */
    @Override
    public Object getAttribute(String name) {
        return myAttributes.get(name);

    }

    /**
     * Return an enumeration of context attribute names.
     */
    @Override
    public Enumeration<String> getAttributeNames() {
        return myAttributes.keys();
    }

    /**
     * Returns the context path of the web application.
     */
    @Override
    public String getContextPath() {
        return null;
    }

    /**
     * Return the servlet context for the specified path.
     *
     * @param uripath Server-relative path starting with '/'
     */
    @Override
    public ServletContext getContext(String uripath) {
        return null;
    }

    /**
     * Return the specified context initialization parameter.
     *
     * @param name Name of the requested parameter
     */
    @Override
    public String getInitParameter(String name) {
        return null;
    }

    /**
     * Return an enumeration of the names of context initialization parameters.
     */
    @Override
    public Enumeration<String> getInitParameterNames() {
        return new Vector<String>().elements();
    }

    /**
     * Return the Servlet API major version number.
     */
    @Override
    public int getMajorVersion() {
        return 6;
    }

    /**
     * Return the MIME type for the specified filename.
     *
     * @param file Filename whose MIME type is requested
     */
    @Override
    public String getMimeType(String file) {
        return null;
    }

    /**
     * Return the Servlet API minor version number.
     */
    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        // TODO: get it from web.xml
        return 6;
    }

    @Override
    public int getEffectiveMinorVersion() {
        // TODO: get it from web.xml
        return 0;
    }

    /**
     * Return a request dispatcher for the specified servlet name.
     *
     * @param name Name of the requested servlet
     */
    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        return null;
    }

    /**
     * Return the real path for the specified context-relative virtual path.
     *
     * @param path The context-relative virtual path to resolve
     */
    @Override
    public String getRealPath(String path) {
        if (!myResourceBaseURL.getProtocol().equals("file")) {
            return null;
        }

        if (!path.startsWith("/")) {
            return null;
        }

        try {
            return getResource(path).getFile().replace('/', File.separatorChar);
        } catch (Throwable t) {
            return null;
        }

    }

    /**
     * Return a request dispatcher for the specified context-relative path.
     *
     * @param path Context-relative path for which to acquire a dispatcher
     */
    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    /**
     * Return a URL object of a resource that is mapped to the specified context-relative path.
     *
     * @param path Context-relative path of the desired resource
     *
     * @exception MalformedURLException if the resource path is not properly formed
     */
    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (!path.startsWith("/")) {
            throw new MalformedURLException("Path '" + path + "' does not start with '/'");
        }

        URL url = new URL(myResourceBaseURL, path.substring(1));
        InputStream is = null;
        try {
            is = url.openStream();
        } catch (Throwable t) {
            url = null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable t2) {
                    // Ignore
                }
            }
        }

        return url;
    }

    /**
     * Return an InputStream allowing access to the resource at the specified context-relative path.
     *
     * @param path Context-relative path of the desired resource
     */
    @Override
    public InputStream getResourceAsStream(String path) {
        try {
            return getResource(path).openStream();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Return the set of resource paths for the "directory" at the specified context path.
     *
     * @param path Context-relative base path
     */
    @Override
    public Set<String> getResourcePaths(String path) {
        Set<String> thePaths = new HashSet<>();
        if (!path.endsWith("/")) {
            path += "/";
        }
        String basePath = getRealPath(path);
        if (basePath == null) {
            return thePaths;
        }

        File theBaseDir = new File(basePath);
        if (!theBaseDir.exists() || !theBaseDir.isDirectory()) {
            return thePaths;
        }

        String theFiles[] = theBaseDir.list();
        for (int i = 0; i < theFiles.length; i++) {
            File testFile = new File(basePath + File.separator + theFiles[i]);
            if (testFile.isFile()) {
                thePaths.add(path + theFiles[i]);
            } else if (testFile.isDirectory()) {
                thePaths.add(path + theFiles[i] + "/");
            }
        }

        return thePaths;
    }

    /**
     * Return descriptive information about this server.
     */
    @Override
    public String getServerInfo() {
        return "JspCServletContext/1.0";
    }

    /**
     * Return the name of this servlet context.
     */
    @Override
    public String getServletContextName() {
        return getServerInfo();
    }

    /**
     * Log the specified message.
     *
     * @param message The message to be logged
     */
    @Override
    public void log(String message) {
        myLogWriter.println(message);
    }

    /**
     * Log the specified message and exception.
     *
     * @param message The message to be logged
     * @param exception The exception to be logged
     */
    @Override
    public void log(String message, Throwable exception) {
        myLogWriter.println(message);
        exception.printStackTrace(myLogWriter);
    }

    /**
     * Remove the specified context attribute.
     *
     * @param name Name of the attribute to remove
     */
    @Override
    public void removeAttribute(String name) {
        myAttributes.remove(name);
    }

    /**
     * Set or replace the specified context attribute.
     *
     * @param name Name of the context attribute to set
     * @param value Corresponding attribute value
     */
    @Override
    public void setAttribute(String name, Object value) {
        myAttributes.put(name, value);
    }

    /*
     * Adds the servlet with the given name, description, class name, init parameters, and loadOnStartup, to this servlet
     * context.
     */
    public void addServlet(String servletName, String description, String className, Map<String, String> initParameters, int loadOnStartup) {
    }

    /**
     * Adds servlet mappings from the given url patterns to the servlet with the given servlet name to this servlet context.
     */
    public void addServletMapping(String servletName, String[] urlPatterns) {
    }

    /**
     * Adds the filter with the given name, description, and class name to this servlet context.
     */
    public void addFilter(String filterName, String description, String className, Map<String, String> initParameters) {
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String s, String s1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> c) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ServletRegistration> getServletRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, FilterRegistration> getFilterRegistrations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return jspConfigDescriptor;
    }

    @Override
    public ClassLoader getClassLoader() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void declareRoles(String... roleNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVirtualServerName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSessionTimeout() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTimeout(int i) {
    }

    @Override
    public String getRequestCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRequestCharacterEncoding(String s) {
    }

    @Override
    public String getResponseCharacterEncoding() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResponseCharacterEncoding(String s) {
    }

    private static final String WEB_XML = "/WEB-INF/web.xml";

    private void parseWebDotXml() {
        InputStream is = getResourceAsStream(WEB_XML);
        if (is == null) {
            return;
        }

        TreeNode web = null;
        try {
            web = new ParserUtils().parseXMLDocument(WEB_XML, is);
        } catch (WaspException ex) {
            log("Error in parsing web.xml", ex);
            return;
        }

        TreeNode jspConfig = web.findChild("jsp-config");
        if (jspConfig == null) {
            return;
        }

        ArrayList<TaglibDescriptor> taglibs = new ArrayList<>();
        ArrayList<JspPropertyGroupDescriptor> jspPropertyGroups = new ArrayList<>();

        Iterator<TreeNode> children = jspConfig.findChildren("taglib");
        while (children.hasNext()) {
            TreeNode taglib = children.next();
            String tagUri = null;
            String tagLoc = null;
            TreeNode child = taglib.findChild("taglib-uri");
            if (child != null) {
                tagUri = child.getBody();
            }
            child = taglib.findChild("taglib-location");
            if (child != null) {
                tagLoc = child.getBody();
            }
            if (tagUri == null || tagLoc == null) {
                return;
            }
            taglibs.add(new TaglibDescriptorImpl(tagUri, tagLoc));
        }

        children = jspConfig.findChildren("jsp-property-group");
        while (children.hasNext()) {

            ArrayList<String> urlPatterns = new ArrayList<>();
            String pageEncoding = null;
            String scriptingInvalid = null;
            String elIgnored = null;
            String errorOnELNotFound = null;
            String isXml = null;
            ArrayList<String> includePrelude = new ArrayList<>();
            ArrayList<String> includeCoda = new ArrayList<>();
            String trimSpaces = null;
            String poundAllowed = null;
            String buffer = null;
            String defaultContentType = null;
            String errorOnUndeclaredNamespace = null;

            TreeNode pgroup = children.next();
            Iterator<TreeNode> properties = pgroup.findChildren();
            while (properties.hasNext()) {
                TreeNode element = properties.next();
                String tname = element.getName();

                // url-patterns, preludes, and codas and accumulative, other
                // properties keep last.

                if ("url-pattern".equals(tname)) {
                    urlPatterns.add(element.getBody());
                } else if ("page-encoding".equals(tname)) {
                    pageEncoding = element.getBody();
                } else if ("is-xml".equals(tname)) {
                    isXml = element.getBody();
                } else if ("el-ignored".equals(tname)) {
                    elIgnored = element.getBody();
                } else if ("error-on-el-not-found".equals(tname)) {
                    errorOnELNotFound = element.getBody();
                }else if ("scripting-invalid".equals(tname)) {
                    scriptingInvalid = element.getBody();
                } else if ("include-prelude".equals(tname)) {
                    includePrelude.add(element.getBody());
                } else if ("include-coda".equals(tname)) {
                    includeCoda.add(element.getBody());
                } else if ("trim-directive-whitespaces".equals(tname)) {
                    trimSpaces = element.getBody();
                } else if ("deferred-syntax-allowed-as-literal".equals(tname)) {
                    poundAllowed = element.getBody();
                } else if ("default-content-type".equals(tname)) {
                    defaultContentType = element.getBody();
                } else if ("buffer".equals(tname)) {
                    buffer = element.getBody();
                } else if ("error-on-undeclared-namespace".equals(tname)) {
                    errorOnUndeclaredNamespace = element.getBody();
                }
            }
            jspPropertyGroups.add(new JspPropertyGroupDescriptorImpl(urlPatterns, isXml, elIgnored, errorOnELNotFound, scriptingInvalid, trimSpaces, poundAllowed, pageEncoding,
                    includePrelude, includeCoda, defaultContentType, buffer, errorOnUndeclaredNamespace));
        }

        jspConfigDescriptor = new JspConfigDescriptorImpl(taglibs, jspPropertyGroups);
    }

    static class JspPropertyGroupDescriptorImpl implements JspPropertyGroupDescriptor {
        Collection<String> urlPatterns;
        String isXml;
        String elIgnored;
        String errorOnELNotFound;
        String scriptingInvalid;
        String trimSpaces;
        String poundAllowed;
        String pageEncoding;
        Collection<String> includePrelude;
        Collection<String> includeCoda;
        String defaultContentType;
        String buffer;
        String errorOnUndeclaredNamespace;

        JspPropertyGroupDescriptorImpl(Collection<String> urlPatterns, String isXml, String elIgnored, String errorOnELNotFound, String scriptingInvalid, String trimSpaces,
                String poundAllowed, String pageEncoding, Collection<String> includePrelude, Collection<String> includeCoda, String defaultContentType,
                String buffer, String errorOnUndeclaredNamespace) {
            this.urlPatterns = urlPatterns;
            this.isXml = isXml;
            this.elIgnored = elIgnored;
            this.errorOnELNotFound = errorOnELNotFound;
            this.scriptingInvalid = scriptingInvalid;
            this.trimSpaces = trimSpaces;
            this.poundAllowed = poundAllowed;
            this.pageEncoding = pageEncoding;
            this.includePrelude = includePrelude;
            this.includeCoda = includeCoda;
            this.defaultContentType = defaultContentType;
            this.buffer = buffer;
            this.errorOnUndeclaredNamespace = errorOnUndeclaredNamespace;
        }

        @Override
        public Collection<String> getUrlPatterns() {
            return urlPatterns;
        }

        @Override
        public String getElIgnored() {
            return elIgnored;
        }

        @Override
        public String getErrorOnELNotFound() {
            return errorOnELNotFound;
        }

        @Override
        public String getPageEncoding() {
            return pageEncoding;
        }

        @Override
        public String getScriptingInvalid() {
            return scriptingInvalid;
        }

        @Override
        public String getIsXml() {
            return isXml;
        }

        @Override
        public Collection<String> getIncludePreludes() {
            return includePrelude;
        }

        @Override
        public Collection<String> getIncludeCodas() {
            return includeCoda;
        }

        @Override
        public String getDeferredSyntaxAllowedAsLiteral() {
            return poundAllowed;
        }

        @Override
        public String getTrimDirectiveWhitespaces() {
            return trimSpaces;
        }

        @Override
        public String getDefaultContentType() {
            return defaultContentType;
        }

        @Override
        public String getBuffer() {
            return buffer;
        }

        @Override
        public String getErrorOnUndeclaredNamespace() {
            return errorOnUndeclaredNamespace;
        }
    }

    static class TaglibDescriptorImpl implements TaglibDescriptor {
        String uri, loc;

        public TaglibDescriptorImpl(String uri, String loc) {
            this.uri = uri;
            this.loc = loc;

        }

        @Override
        public String getTaglibURI() {
            return uri;
        }

        @Override
        public String getTaglibLocation() {
            return loc;
        }
    }

    static class JspConfigDescriptorImpl implements JspConfigDescriptor {
        Collection<TaglibDescriptor> taglibs;
        Collection<JspPropertyGroupDescriptor> jspPropertyGroups;

        public JspConfigDescriptorImpl(Collection<TaglibDescriptor> taglibs, Collection<JspPropertyGroupDescriptor> jspPropertyGroups) {
            this.taglibs = taglibs;
            this.jspPropertyGroups = jspPropertyGroups;
        }

        @Override
        public Collection<TaglibDescriptor> getTaglibs() {
            return this.taglibs;
        }

        @Override
        public Collection<JspPropertyGroupDescriptor> getJspPropertyGroups() {
            return this.jspPropertyGroups;
        }
    }
}
