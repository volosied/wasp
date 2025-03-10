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

package org.glassfish.wasp.runtime;

import java.io.IOException;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Logger;

import org.glassfish.wasp.Constants;
import org.glassfish.wasp.compiler.Localizer;
import org.glassfish.wasp.security.SecurityUtil;

import jakarta.el.ArrayELResolver;
import jakarta.el.BeanELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.FunctionMapper;
import jakarta.el.ListELResolver;
import jakarta.el.MapELResolver;
import jakarta.el.MethodExpression;
import jakarta.el.ResourceBundleELResolver;
import jakarta.el.StaticFieldELResolver;
import jakarta.el.ValueExpression;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.el.ExpressionEvaluator;
import jakarta.servlet.jsp.el.ImplicitObjectELResolver;
import jakarta.servlet.jsp.el.NotFoundELResolver;
import jakarta.servlet.jsp.el.ScopedAttributeELResolver;
import jakarta.servlet.jsp.el.VariableResolver;
import jakarta.servlet.jsp.tagext.BodyContent;

/**
 * Implementation of the PageContext class from the JSP spec.
 *
 * @author Anil K. Vijendran
 * @author Larry Cable
 * @author Hans Bergsten
 * @author Pierre Delisle
 * @author Mark Roth
 * @author Jan Luehe
 * @author Kin-man Chung
 */
public class PageContextImpl extends PageContext {

    // Logger
    private static Logger log = Logger.getLogger(PageContextImpl.class.getName());

    // per-servlet state
    private BodyContentImpl[] outs;
    private int depth;
    private Servlet servlet;
    private ServletConfig config;
    private ServletContext context;
    private String errorPageURL;
    private JspApplicationContextImpl jspApplicationContext;
    private ELResolver elResolver;
    private ELContext elContext;

    // page-scope attributes
    private HashMap<String, Object> attributes;
    private boolean isNametableInitialized;

    // per-request state
    private ServletRequest request;
    private ServletResponse response;
    private HttpSession session;

    // initial output stream
    private JspWriter out;
    private JspWriterImpl baseOut;

    /*
     * Constructor.
     */
    PageContextImpl(JspFactory factory) {
        this.outs = new BodyContentImpl[0];
        this.attributes = new HashMap<>(16);
        this.depth = -1;
    }

    @Override
    public void initialize(Servlet servlet, ServletRequest request, ServletResponse response, String errorPageURL, boolean needsSession, int bufferSize,
            boolean autoFlush) throws IOException {

        _initialize(servlet, request, response, errorPageURL, needsSession, bufferSize, autoFlush);
    }

    private void _initialize(Servlet servlet, ServletRequest request, ServletResponse response, String errorPageURL, boolean needsSession, int bufferSize,
            boolean autoFlush) throws IOException {

        // initialize state
        this.servlet = servlet;
        this.config = servlet.getServletConfig();
        this.context = config.getServletContext();
        this.errorPageURL = errorPageURL;
        this.request = request;
        this.response = response;

        // Setup session (if required)
        if (request instanceof HttpServletRequest && needsSession) {
            this.session = ((HttpServletRequest) request).getSession();
        }
        if (needsSession && session == null) {
            throw new IllegalStateException("Page needs a session and none is available");
        }

        // initialize the initial out ...
        depth = -1;
        if (this.baseOut == null) {
            this.baseOut = new JspWriterImpl(response, bufferSize, autoFlush);
        } else {
            this.baseOut.init(response, bufferSize, autoFlush);
        }
        this.out = baseOut;

        this.isNametableInitialized = false;
        setAttribute(Constants.FIRST_REQUEST_SEEN, "true", APPLICATION_SCOPE);

    }

    private void initializePageScopeNameTable() {
        isNametableInitialized = true;
        // register names/values as per spec
        setAttribute(OUT, this.out);
        setAttribute(REQUEST, request);
        setAttribute(RESPONSE, response);

        if (session != null) {
            setAttribute(SESSION, session);
        }

        setAttribute(PAGE, servlet);
        setAttribute(CONFIG, config);
        setAttribute(PAGECONTEXT, this);
        setAttribute(APPLICATION, context);
    }

    @Override
    public void release() {
        out = baseOut;
        try {
            // Do not flush the buffer even if we're not included (i.e.
            // we are the main page. The servlet will flush it and close
            // the stream.
            ((JspWriterImpl) out).flushBuffer();
        } catch (IOException ex) {
            log.warning("Internal error flushing the buffer in release()");
        }

        servlet = null;
        config = null;
        context = null;
        elContext = null;
        elResolver = null;
        jspApplicationContext = null;
        errorPageURL = null;
        request = null;
        response = null;
        depth = -1;
        baseOut.recycle();
        session = null;

        attributes.clear();
    }

    @Override
    public Object getAttribute(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged((PrivilegedAction<Object>) () -> doGetAttribute(name));
        } else {
            return doGetAttribute(name);
        }

    }

    private Object doGetAttribute(String name) {
        if (!isNametableInitialized) {
            initializePageScopeNameTable();
        }
        return attributes.get(name);
    }

    @Override
    public Object getAttribute(final String name, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged((PrivilegedAction<Object>) () -> doGetAttribute(name, scope));
        } else {
            return doGetAttribute(name, scope);
        }

    }

    private Object doGetAttribute(String name, int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            if (!isNametableInitialized) {
                initializePageScopeNameTable();
            }
            return attributes.get(name);

        case REQUEST_SCOPE:
            return request.getAttribute(name);

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer.getMessage("jsp.error.page.noSession"));
            }
            return session.getAttribute(name);

        case APPLICATION_SCOPE:
            return context.getAttribute(name);

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public void setAttribute(final String name, final Object attribute) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                doSetAttribute(name, attribute);
                return null;
            });
        } else {
            doSetAttribute(name, attribute);
        }
    }

    private void doSetAttribute(String name, Object attribute) {
        if (attribute != null) {
            if (!isNametableInitialized) {
                initializePageScopeNameTable();
            }
            attributes.put(name, attribute);
        } else {
            removeAttribute(name, PAGE_SCOPE);
        }
    }

    @Override
    public void setAttribute(final String name, final Object o, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                doSetAttribute(name, o, scope);
                return null;
            });
        } else {
            doSetAttribute(name, o, scope);
        }

    }

    private void doSetAttribute(String name, Object o, int scope) {
        if (o != null) {
            switch (scope) {
            case PAGE_SCOPE:
                if (!isNametableInitialized) {
                    initializePageScopeNameTable();
                }
                attributes.put(name, o);
                break;

            case REQUEST_SCOPE:
                request.setAttribute(name, o);
                break;

            case SESSION_SCOPE:
                if (session == null) {
                    throw new IllegalStateException(Localizer.getMessage("jsp.error.page.noSession"));
                }
                session.setAttribute(name, o);
                break;

            case APPLICATION_SCOPE:
                context.setAttribute(name, o);
                break;

            default:
                throw new IllegalArgumentException("Invalid scope");
            }
        } else {
            removeAttribute(name, scope);
        }
    }

    @Override
    public void removeAttribute(final String name, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }
        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                doRemoveAttribute(name, scope);
                return null;
            });
        } else {
            doRemoveAttribute(name, scope);
        }
    }

    private void doRemoveAttribute(String name, int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            if (!isNametableInitialized) {
                initializePageScopeNameTable();
            }
            attributes.remove(name);
            break;

        case REQUEST_SCOPE:
            request.removeAttribute(name);
            break;

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer.getMessage("jsp.error.page.noSession"));
            }
            session.removeAttribute(name);
            break;

        case APPLICATION_SCOPE:
            context.removeAttribute(name);
            break;

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public int getAttributesScope(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged((PrivilegedAction<Integer>) () -> doGetAttributeScope(name));
        } else {
            return doGetAttributeScope(name);
        }
    }

    private int doGetAttributeScope(String name) {

        if (!isNametableInitialized) {
            initializePageScopeNameTable();
        }

        if (attributes.get(name) != null) {
            return PAGE_SCOPE;
        }

        if (request.getAttribute(name) != null) {
            return REQUEST_SCOPE;
        }

        if (session != null) {
            try {
                if (session.getAttribute(name) != null) {
                    return SESSION_SCOPE;
                }
            } catch (IllegalStateException ex) {
                // Session has been invalidated.
                // Ignore and fall through to application scope.
            }
        }

        if (context.getAttribute(name) != null) {
            return APPLICATION_SCOPE;
        }

        return 0;
    }

    @Override
    public Object findAttribute(final String name) {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                if (name == null) {
                    throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
                }

                return doFindAttribute(name);
            });
        } else {
            if (name == null) {
                throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
            }

            return doFindAttribute(name);
        }
    }

    private Object doFindAttribute(String name) {

        if (!isNametableInitialized) {
            initializePageScopeNameTable();
        }

        Object o = attributes.get(name);
        if (o != null) {
            return o;
        }

        o = request.getAttribute(name);
        if (o != null) {
            return o;
        }

        if (session != null) {
            try {
                o = session.getAttribute(name);
            } catch (IllegalStateException ex) {
                // Session has been invalidated.
                // Ignore and fall through to application scope.
            }

            if (o != null) {
                return o;
            }
        }

        return context.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(final int scope) {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged((PrivilegedAction<Enumeration<String>>) () -> doGetAttributeNamesInScope(scope));
        } else {
            return doGetAttributeNamesInScope(scope);
        }
    }

    private Enumeration<String> doGetAttributeNamesInScope(int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            if (!isNametableInitialized) {
                initializePageScopeNameTable();
            }
            return Collections.enumeration(attributes.keySet());

        case REQUEST_SCOPE:
            return request.getAttributeNames();

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer.getMessage("jsp.error.page.noSession"));
            }
            return session.getAttributeNames();

        case APPLICATION_SCOPE:
            return context.getAttributeNames();

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public void removeAttribute(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer.getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                doRemoveAttribute(name);
                return null;
            });
        } else {
            doRemoveAttribute(name);
        }
    }

    private void doRemoveAttribute(String name) {
        removeAttribute(name, PAGE_SCOPE);
        removeAttribute(name, REQUEST_SCOPE);
        if (session != null) {
            try {
                removeAttribute(name, SESSION_SCOPE);
            } catch (IllegalStateException ex) {
                // Session has been invalidated.
                // Ignore and fall through to application scope.
            }
        }
        removeAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public JspWriter getOut() {
        return out;
    }

    @Override
    public HttpSession getSession() {
        return session;
    }

    public Servlet getServlet() {
        return servlet;
    }

    @Override
    public ServletConfig getServletConfig() {
        return config;
    }

    @Override
    public ServletContext getServletContext() {
        return config.getServletContext();
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    /**
     * Returns the exception associated with this page context, if any.
     *
     * Added wrapping for Throwables to avoid ClassCastException (see Bugzilla 31171 for details).
     *
     * @return The Exception associated with this page context, if any.
     */
    @Override
    public Exception getException() {

        Throwable t = JspRuntimeLibrary.getThrowable(request);

        // Only wrap if needed
        if (t != null && !(t instanceof Exception)) {
            t = new JspException(t);
        }

        return (Exception) t;
    }

    @Override
    public Object getPage() {
        return servlet;
    }

    private final String getAbsolutePathRelativeToContext(String relativeUrlPath) {
        String path = relativeUrlPath;

        if (!path.startsWith("/")) {
            String uri = (String) request.getAttribute("jakarta.servlet.include.servlet_path");
            if (uri == null) {
                uri = ((HttpServletRequest) request).getServletPath();
            }
            String baseURI = uri.substring(0, uri.lastIndexOf('/'));
            path = baseURI + '/' + path;
        }

        return path;
    }

    @Override
    public void include(String relativeUrlPath) throws ServletException, IOException {
        JspRuntimeLibrary.include(request, response, relativeUrlPath, out, true);
    }

    /*
     * No need to execute include inside a privileged block, since it calls ApplicationDispatcher include, which in turn is
     * executed inside a privileged block
     */
    @Override
    public void include(String relativeUrlPath, boolean flush) throws ServletException, IOException {
        JspRuntimeLibrary.include(request, response, relativeUrlPath, out, flush);
    }

    @Override
    public VariableResolver getVariableResolver() {
        return new VariableResolverImpl(this);
    }

    private ELResolver getELResolver() {

        if (elResolver == null) {
            // Create a CompositeELResolver
            CompositeELResolver celResolver = new CompositeELResolver();

            celResolver.add(new ImplicitObjectELResolver());
            // Add ELResolvers registered in JspApplicationContext
            JspApplicationContextImpl jaContext = getJspApplicationContext();
            Iterator<ELResolver> it = jaContext.getELResolvers();
            while (it.hasNext()) {
                celResolver.add(it.next());
            }
            ELResolver streamELResolver = getExpressionFactory(this).getStreamELResolver();
            if (streamELResolver != null) {
                celResolver.add(streamELResolver);
            }
            celResolver.add(new StaticFieldELResolver());
            celResolver.add(new MapELResolver());
            celResolver.add(new ResourceBundleELResolver());
            celResolver.add(new ListELResolver());
            celResolver.add(new ArrayELResolver());
            celResolver.add(new BeanELResolver());
            celResolver.add(new ScopedAttributeELResolver());
            elResolver = celResolver;
        }
        return elResolver;
    }

    @Override
    public ELContext getELContext() {
        if (elContext == null) {
            elContext = getJspApplicationContext().createELContext(getELResolver());
            elContext.putContext(jakarta.servlet.jsp.JspContext.class, this);
            ((ELContextImpl) elContext).setVariableMapper(new VariableMapperImpl());
            if (servlet instanceof JspSourceDirectives) {
                if (((JspSourceDirectives) servlet).getErrorOnELNotFound()) {
                    elContext.putContext(NotFoundELResolver.class, Boolean.TRUE);
                }
            }
        }
        return elContext;
    }

    JspApplicationContextImpl getJspApplicationContext() {
        if (jspApplicationContext == null) {
            jspApplicationContext = JspApplicationContextImpl.findJspApplicationContext(context);
        }
        return jspApplicationContext;
    }

    /*
     * No need to execute forward inside a privileged block, since it calls ApplicationDispatcher forward, which in turn is
     * executed inside a privileged block
     */
    @Override
    public void forward(String relativeUrlPath) throws ServletException, IOException {

        // JSP.4.5 If the buffer was flushed, throw IllegalStateException
        try {
            out.clear();
        } catch (IOException ex) {
            IllegalStateException ise = new IllegalStateException(Localizer.getMessage("jsp.error.attempt_to_clear_flushed_buffer"));
            ise.initCause(ex);
            throw ise;
        }

        // Make sure that the response object is not the wrapper for include
        while (response instanceof ServletResponseWrapperInclude) {
            response = ((ServletResponseWrapperInclude) response).getResponse();
        }

        final String path = getAbsolutePathRelativeToContext(relativeUrlPath);
        String includeUri = (String) request.getAttribute(Constants.INC_SERVLET_PATH);

        if (includeUri != null) {
            request.removeAttribute(Constants.INC_SERVLET_PATH);
        }
        try {
            context.getRequestDispatcher(path).forward(request, response);
        } finally {
            if (includeUri != null) {
                request.setAttribute(Constants.INC_SERVLET_PATH, includeUri);
            }
            request.setAttribute(Constants.FORWARD_SEEN, "true");
        }
    }

    @Override
    public BodyContent pushBody() {
        return (BodyContent) pushBody(null);
    }

    // Note that there is a potential memory leak with way BodyContentImpl
    // are pooled. The "outs" array is extended in pushBody, but not shrinked
    // in popBody; and BodyContentImpl.cb can gets arbritarily large.
    // See https://glassfish.dev.java.net/issues/show_bug.cgi?id=8601
    // Setting FactoryImpl.USE_POOL to false eliminates most of the leak,
    // but not all -- kchung 6/29/2009
    @Override
    public JspWriter pushBody(Writer writer) {
        depth++;
        if (depth >= outs.length) {
            BodyContentImpl[] newOuts = new BodyContentImpl[depth + 1];
            for (int i = 0; i < outs.length; i++) {
                newOuts[i] = outs[i];
            }
            newOuts[depth] = new BodyContentImpl(out);
            outs = newOuts;
        }

        outs[depth].setWriter(writer);
        out = outs[depth];

        // Update the value of the "out" attribute in the page scope
        // attribute namespace of this PageContext
        setAttribute(OUT, out);

        return outs[depth];
    }

    @Override
    public JspWriter popBody() {
        depth--;
        if (depth >= 0) {
            out = outs[depth];
        } else {
            out = baseOut;
        }

        // Update the value of the "out" attribute in the page scope
        // attribute namespace of this PageContext
        setAttribute(OUT, out);

        return out;
    }

    /**
     * Provides programmatic access to the ExpressionEvaluator. The JSP Container must return a valid instance of an
     * ExpressionEvaluator that can parse EL expressions.
     */
    @Override
    public ExpressionEvaluator getExpressionEvaluator() {
        return new ExpressionEvaluatorImpl(this);
    }

    @Override
    public void handlePageException(Exception ex) throws IOException, ServletException {
        // Should never be called since handleException() called with a
        // Throwable in the generated servlet.
        handlePageException((Throwable) ex);
    }

    @Override
    public void handlePageException(final Throwable t) throws IOException, ServletException {
        if (t == null) {
            throw new NullPointerException("null Throwable");
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
                    doHandlePageException(t);
                    return null;
                });
            } catch (PrivilegedActionException e) {
                Exception ex = e.getException();
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                } else {
                    throw (ServletException) ex;
                }
            }
        } else {
            doHandlePageException(t);
        }

    }

    private void doHandlePageException(Throwable t) throws IOException, ServletException {

        if (errorPageURL != null && !errorPageURL.equals("")) {

            /*
             * Set request attributes. Do not set the jakarta.servlet.error.exception attribute here (instead, set in the generated
             * servlet code for the error page) in order to prevent the ErrorReportValve, which is invoked as part of forwarding the
             * request to the error page, from throwing it if the response has not been committed (the response will have been
             * committed if the error page is a JSP page).
             */
            request.setAttribute("jakarta.servlet.jsp.jspException", t);
            request.setAttribute("jakarta.servlet.error.status_code", Integer.valueOf(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
            request.setAttribute("jakarta.servlet.error.request_uri", ((HttpServletRequest) request).getRequestURI());
            request.setAttribute("jakarta.servlet.error.servlet_name", config.getServletName());
            try {
                forward(errorPageURL);
            } catch (IllegalStateException ise) {
                include(errorPageURL);
            }

            // The error page could be inside an include.

            Object newException = request.getAttribute("jakarta.servlet.error.exception");

            // t==null means the attribute was not set.
            if (newException != null && newException == t) {
                request.removeAttribute("jakarta.servlet.error.exception");
                request.setAttribute(Constants.JSP_ERROR_HANDLED, Boolean.TRUE);
            }

            // now clear the error code - to prevent double handling.
            request.removeAttribute("jakarta.servlet.error.status_code");
            request.removeAttribute("jakarta.servlet.error.request_uri");
            request.removeAttribute("jakarta.servlet.jsp.jspException");

        } else {

            // Otherwise throw the exception wrapped inside a ServletException.
            // Set the exception as the root cause in the ServletException
            // to get a stack trace for the real problem
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            if (t instanceof ServletException) {
                throw (ServletException) t;
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }

            Throwable rootCause = null;
            if (t instanceof JspException) {
                rootCause = ((JspException) t).getRootCause();
            } else if (t instanceof ELException) {
                rootCause = t.getCause();
            }

            if (rootCause != null) {
                throw new ServletException(t.getMessage(), rootCause);
            }

            throw new ServletException(t);
        }
    }

    private static ExpressionFactory getExpressionFactory(PageContext pageContext) {

        PageContextImpl pc = (PageContextImpl) JspContextWrapper.getRootPageContext(pageContext);
        return pc.getJspApplicationContext().getExpressionFactory();
    }

    /**
     * Evaluates an EL expression
     *
     * @param expression The expression to be evaluated
     * @param expectedType The expected resulting type
     * @param pageContext The page context
     * @param functionMap Maps prefix and name to Method
     * @return The result of the evaluation
     */
    public static Object evaluateExpression(final String expression, final Class expectedType, final PageContext pageContext,
            final ProtectedFunctionMapper functionMap) throws ELException {
        Object retValue;
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                retValue = AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
                    ELContextImpl elContext = (ELContextImpl) pageContext.getELContext();
                    elContext.setFunctionMapper(functionMap);
                    ExpressionFactory expFactory = getExpressionFactory(pageContext);
                    ValueExpression expr = expFactory.createValueExpression(elContext, expression, expectedType);
                    return expr.getValue(elContext);
                });
            } catch (PrivilegedActionException ex) {
                Exception realEx = ex.getException();
                if (realEx instanceof ELException) {
                    throw (ELException) realEx;
                } else {
                    throw new ELException(realEx);
                }
            }
        } else {
            ELContextImpl elContext = (ELContextImpl) pageContext.getELContext();
            elContext.setFunctionMapper(functionMap);
            ExpressionFactory expFactory = getExpressionFactory(pageContext);
            ValueExpression expr = expFactory.createValueExpression(elContext, expression, expectedType);
            retValue = expr.getValue(elContext);
        }
        return retValue;
    }

    public static ValueExpression getValueExpression(String expression, PageContext pageContext, Class expectedType, FunctionMapper functionMap) {
        // ELResolvers are not used in createValueExpression
        ELContextImpl elctxt = (ELContextImpl) pageContext.getELContext();
        elctxt.setFunctionMapper(functionMap);
        ExpressionFactory expFactory = getExpressionFactory(pageContext);
        return expFactory.createValueExpression(elctxt, expression, expectedType);
    }

    public static MethodExpression getMethodExpression(String expression, PageContext pageContext, FunctionMapper functionMap, Class expectedType,
            Class[] paramTypes) {

        ELContextImpl elctxt = (ELContextImpl) pageContext.getELContext();
        elctxt.setFunctionMapper(functionMap);
        ExpressionFactory expFactory = getExpressionFactory(pageContext);
        return expFactory.createMethodExpression(elctxt, expression, expectedType, paramTypes);
    }

    public static void setValueVariable(PageContext pageContext, String variable, ValueExpression expression) {
        ELContextImpl elctxt = (ELContextImpl) pageContext.getELContext();
        elctxt.getVariableMapper().setVariable(variable, expression);
    }

    public static void setMethodVariable(PageContext pageContext, String variable, MethodExpression expression) {
        ExpressionFactory expFactory = getExpressionFactory(pageContext);
        ValueExpression exp = expFactory.createValueExpression(expression, Object.class);
        setValueVariable(pageContext, variable, exp);
    }
}
