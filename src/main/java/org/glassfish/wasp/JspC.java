/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.wasp;

import java.io.BufferedReader;
import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
// END GlassFish 750
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.StringTokenizer;
// START GlassFish 750
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.wasp.compiler.Compiler;
import org.glassfish.wasp.compiler.JspConfig;
import org.glassfish.wasp.compiler.JspRuntimeContext;
import org.glassfish.wasp.compiler.Localizer;
import org.glassfish.wasp.compiler.PageInfo;
import org.glassfish.wasp.compiler.TagPluginManager;
import org.glassfish.wasp.runtime.TldScanner;
import org.glassfish.wasp.servlet.JspCServletContext;
import org.glassfish.wasp.xmlparser.ParserUtils;

// START GlassFish 750
import jakarta.servlet.jsp.tagext.TagLibraryInfo;


/**
 * Shell for the jspc compiler. Handles all options associated with the command line and creates compilation contexts
 * which it then compiles according to the specified options.
 *
 * This version can process files from a _single_ webapp at once, i.e. a single docbase can be specified.
 *
 * It can be used as an Ant task using:
 *
 * <pre>
 *   &lt;taskdef classname="org.glassfish.wasp.JspC" name="wasp2" &gt;
 *      &lt;classpath&gt;
 *          &lt;pathelement location="${java.home}/../lib/tools.jar"/&gt;
 *          &lt;fileset dir="${ENV.CATALINA_HOME}/server/lib"&gt;
 *              &lt;include name="*.jar"/&gt;
 *          &lt;/fileset&gt;
 *          &lt;fileset dir="${ENV.CATALINA_HOME}/common/lib"&gt;
 *              &lt;include name="*.jar"/&gt;
 *          &lt;/fileset&gt;
 *          &lt;path refid="myjars"/&gt;
 *       &lt;/classpath&gt;
 *  &lt;/taskdef&gt;
 *
 *  &lt;wasp2 verbose="0"
 *           package="my.package"
 *           uriroot="${webapps.dir}/${webapp.name}"
 *           webXmlFragment="${build.dir}/generated_web.xml"
 *           outputDir="${webapp.dir}/${webapp.name}/WEB-INF/src/my/package" /&gt;
 * </pre>
 *
 * @author Danno Ferrin
 * @author Pierre Delisle
 * @author Costin Manolache
 */
public class JspC implements Options {

    public static final String DEFAULT_IE_CLASS_ID = "clsid:8AD9C840-044E-11D1-B3E9-00805F499D93";

    private static final String JAVA_1_1 = "1.1";
    private static final String JAVA_1_2 = "1.2";
    private static final String JAVA_1_3 = "1.3";
    private static final String JAVA_1_4 = "1.4";
    private static final String JAVA_1_5 = "1.5";
    private static final String JAVA_1_6 = "1.6";
    private static final String JAVA_1_7 = "1.7";
    private static final String JAVA_1_8 = "1.8";
    private static final String JAVA_5 = "5";
    private static final String JAVA_6 = "6";
    private static final String JAVA_7 = "7";
    private static final String JAVA_8 = "8";
    // END SJSAS 6402545

    // Logger
    private static Logger log = Logger.getLogger(JspC.class.getName());

    private static final String SWITCH_VERBOSE = "-v";
    private static final String SWITCH_HELP = "-help";
    private static final String SWITCH_OUTPUT_DIR = "-d";
    private static final String SWITCH_PACKAGE_NAME = "-p";
    private static final String SWITCH_CLASS_NAME = "-c";
    private static final String SWITCH_FULL_STOP = "--";
    private static final String SWITCH_COMPILE = "-compile";
    private static final String SWITCH_SOURCE = "-compilerSourceVM";
    private static final String SWITCH_TARGET = "-compilerTargetVM";
    private static final String SWITCH_URI_BASE = "-uribase";
    private static final String SWITCH_URI_ROOT = "-uriroot";
    private static final String SWITCH_FILE_WEBAPP = "-webapp";
    private static final String SWITCH_WEBAPP_INC = "-webinc";
    private static final String SWITCH_WEBAPP_XML = "-webxml";
    private static final String SWITCH_MAPPED = "-mapped";
    private static final String SWITCH_XPOWERED_BY = "-xpoweredBy";
    private static final String SWITCH_TRIM_SPACES = "-trimSpaces";
    private static final String SWITCH_CLASSPATH = "-classpath";
    private static final String SWITCH_SYSCLASSPATH = "-sysClasspath";
    private static final String SWITCH_DIE = "-die";
    private static final String SWITCH_SMAP = "-smap";
    private static final String SWITCH_DUMP_SMAP = "-dumpsmap";
    private static final String SWITCH_SCHEMAS_PREFIX = "-schemas";
    private static final String SWITCH_DTDS_PREFIX = "-dtds";
    private static final String SWITCH_GENERATE_CLASSES = "-genclass";
    private static final String SWITCH_VALIDATE = "-validate";
    private static final String SWITCH_IGNORE_JSP_FRAGMENTS = "-ignoreJspFragmentErrors";
    private static final String SWITCH_DISABLE_POOLING = "-disablePooling";

    private static final String SHOW_SUCCESS = "-s";
    private static final String LIST_ERRORS = "-l";
    private static final int INC_WEBXML = 10;
    private static final int ALL_WEBXML = 20;
    private static final int DEFAULT_DIE_LEVEL = 1;
    private static final int NO_DIE_LEVEL = 0;

    private static final String[] insertBefore = { "</web-app>", "<servlet-mapping>", "<session-config>", "<mime-mapping>", "<welcome-file-list>",
            "<error-page>", "<taglib>", "<resource-env-ref>", "<resource-ref>", "<security-constraint>", "<login-config>", "<security-role>", "<env-entry>",
            "<ejb-ref>", "<ejb-local-ref>" };

    private int dieLevel;
    private String classPath;
    private String sysClassPath;
    private URLClassLoader loader;
    private boolean trimSpaces;
    private boolean genStringAsCharArray;
    private boolean genStringAsByteArray = true;
    private boolean defaultBufferNone;
    private boolean xpoweredBy;
    private boolean mappedFile;
    private boolean poolingEnabled = true;
    private File scratchDir;
    private String ieClassId = DEFAULT_IE_CLASS_ID;
    private String targetPackage;
    private String targetClassName;
    private String uriBase;
    private String uriRoot;
    private boolean helpNeeded;
    private boolean compile;
    private boolean smapSuppressed = true;
    private boolean smapDumped;

    private String compiler;

    private String compilerTargetVM = JAVA_1_5;
    private String compilerSourceVM = JAVA_1_5;

    private boolean classDebugInfo = true;

    /**
     * Throw an exception if there's a compilation error, or swallow it. Default is true to preserve old behavior.
     */
    private boolean failOnError = true;

    private ArrayList<String> extensions;
    private ArrayList<String> pages = new ArrayList<>();
    private boolean errorOnUseBeanInvalidClassAttribute = false;

    /**
     * The java file encoding. Default is UTF-8. Added per bugzilla 19622.
     */
    private String javaEncoding = "UTF-8";

    // Generation of web.xml fragments
    private String webxmlFile;
    private int webxmlLevel;
    private boolean addWebXmlMappings = false;

    private Writer mapout;
    private CharArrayWriter servletout;
    private CharArrayWriter mappingout;

    private JspCServletContext context;

    // Maintain a dummy JspRuntimeContext for compiling tag files
    private JspRuntimeContext rctxt;

    /**
     * Cache for the TLD locations
     */
    private TldScanner tldScanner;

    private JspConfig jspConfig;
    private TagPluginManager tagPluginManager;

    private boolean listErrors;
    private boolean showSuccess;
    private int argPos;
    private boolean fullstop;
    private String args[];

    private boolean isValidationEnabled;

    private HashMap<String, WaspException> jspErrors = new HashMap<>();

    private static String myJavaVersion = System.getProperty("java.specification.version");

    private boolean ignoreJspFragmentErrors = false;
    private Set<String> dependents = new HashSet<>();

    private ConcurrentHashMap<String, TagLibraryInfo> taglibs;
    private ConcurrentHashMap<String, URL> tagFileJarUrls;

    public static void main(String arg[]) {
        if (arg.length == 0) {
            System.out.println(Localizer.getMessage("jspc.usage"));
        } else {
            JspC jspc = new JspC();
            try {
                jspc.setArgs(arg);
                if (jspc.helpNeeded) {
                    System.out.println(Localizer.getMessage("jspc.usage"));
                } else {
                    jspc.execute();
                }
            } catch (WaspException je) {
                System.err.println(je);
                // System.err.println(je.getMessage());
                if (jspc.getDieLevel() != NO_DIE_LEVEL) {
                    System.exit(jspc.getDieLevel());
                }
            }
        }
    }

    public void setArgs(String[] arg) throws WaspException {
        args = arg;
        String tok;

        dieLevel = NO_DIE_LEVEL;

        while ((tok = nextArg()) != null) {
            if (tok.equals(SWITCH_VERBOSE)) {
                showSuccess = true;
                listErrors = true;
            } else if (tok.equals(SWITCH_OUTPUT_DIR)) {
                tok = nextArg();
                setOutputDir(tok);
            } else if (tok.equals(SWITCH_PACKAGE_NAME)) {
                targetPackage = nextArg();
            } else if (tok.equals(SWITCH_COMPILE)) {
                compile = true;
            } else if (tok.equals(SWITCH_CLASS_NAME)) {
                targetClassName = nextArg();
            } else if (tok.equals(SWITCH_URI_BASE)) {
                uriBase = nextArg();
            } else if (tok.equals(SWITCH_URI_ROOT)) {
                setUriroot(nextArg());
                // START IASRI 4660687
            } else if (tok.equals(SWITCH_GENERATE_CLASSES)) {
                compile = true;
                // END IASRI 4660687
            } else if (tok.equals(SWITCH_FILE_WEBAPP)) {
                setUriroot(nextArg());
            } else if (tok.equals(SHOW_SUCCESS)) {
                showSuccess = true;
            } else if (tok.equals(LIST_ERRORS)) {
                listErrors = true;
            } else if (tok.equals(SWITCH_WEBAPP_INC)) {
                webxmlFile = nextArg();
                if (webxmlFile != null) {
                    webxmlLevel = INC_WEBXML;
                }
            } else if (tok.equals(SWITCH_WEBAPP_XML)) {
                webxmlFile = nextArg();
                if (webxmlFile != null) {
                    webxmlLevel = ALL_WEBXML;
                }
            } else if (tok.equals(SWITCH_MAPPED)) {
                mappedFile = true;
            } else if (tok.equals(SWITCH_XPOWERED_BY)) {
                xpoweredBy = true;
            } else if (tok.equals(SWITCH_TRIM_SPACES)) {
                setTrimSpaces(true);
            } else if (tok.equals(SWITCH_CLASSPATH)) {
                setClassPath(nextArg());
            } else if (tok.equals(SWITCH_SYSCLASSPATH)) {
                setSystemClassPath(nextArg());
            } else if (tok.startsWith(SWITCH_DIE)) {
                try {
                    dieLevel = Integer.parseInt(tok.substring(SWITCH_DIE.length()));
                } catch (NumberFormatException nfe) {
                    dieLevel = DEFAULT_DIE_LEVEL;
                }
            } else if (tok.equals(SWITCH_HELP)) {
                helpNeeded = true;
            } else if (tok.equals(SWITCH_SOURCE)) {
                setCompilerSourceVM(nextArg());
            } else if (tok.equals(SWITCH_TARGET)) {
                setCompilerTargetVM(nextArg());
            } else if (tok.equals(SWITCH_SMAP)) {
                smapSuppressed = false;
            } else if (tok.equals(SWITCH_DUMP_SMAP)) {
                smapDumped = true;
                smapSuppressed = false;
                // START PWC 6386258
            } else if (tok.equals(SWITCH_SCHEMAS_PREFIX)) {
                setSchemaResourcePrefix(nextArg());
            } else if (tok.equals(SWITCH_DTDS_PREFIX)) {
                setDtdResourcePrefix(nextArg());
                // END PWC 6386258
                // START PWC 6385018
            } else if (tok.equals(SWITCH_VALIDATE)) {
                setValidateXml(true);
                // END PWC 6385018
                // START SJSAS 6393940
            } else if (tok.equals(SWITCH_IGNORE_JSP_FRAGMENTS)) {
                setIgnoreJspFragmentErrors(true);
                // END SJSAS 6393940
            } else if (tok.equals(SWITCH_DISABLE_POOLING)) {
                setPoolingEnabled(false);
            } else {
                if (tok.startsWith("-")) {
                    throw new WaspException("Unrecognized option: " + tok + ".  Use -help for help.");
                }
                if (!fullstop) {
                    argPos--;
                }
                // Start treating the rest as JSP Pages
                break;
            }
        }

        // Add all extra arguments to the list of files
        while (true) {
            String file = nextFile();
            if (file == null) {
                break;
            }
            pages.add(file);
        }
    }

    public int getDieLevel() {
        return dieLevel;
    }

    @Override
    public boolean getKeepGenerated() {
        // isn't this why we are running jspc?
        return true;
    }

    @Override
    public boolean getSaveBytecode() {
        return true;
    }

    @Override
    public boolean getTrimSpaces() {
        return trimSpaces;
    }

    public void setTrimSpaces(boolean ts) {
        this.trimSpaces = ts;
    }

    @Override
    public boolean isPoolingEnabled() {
        return poolingEnabled;
    }

    public void setPoolingEnabled(boolean poolingEnabled) {
        this.poolingEnabled = poolingEnabled;
    }

    @Override
    public boolean isXpoweredBy() {
        return xpoweredBy;
    }

    public void setXpoweredBy(boolean xpoweredBy) {
        this.xpoweredBy = xpoweredBy;
    }

    @Override
    public boolean getErrorOnUseBeanInvalidClassAttribute() {
        return errorOnUseBeanInvalidClassAttribute;
    }

    public void setErrorOnUseBeanInvalidClassAttribute(boolean b) {
        errorOnUseBeanInvalidClassAttribute = b;
    }

    public int getTagPoolSize() {
        return Constants.MAX_POOL_SIZE;
    }

    // START SJSWS
    /**
     * Gets initial capacity of HashMap which maps JSPs to their corresponding servlets.
     */
    @Override
    public int getInitialCapacity() {
        return Constants.DEFAULT_INITIAL_CAPACITY;
    }
    // END SJSWS

    /**
     * Are we supporting HTML mapped servlets?
     */
    @Override
    public boolean getMappedFile() {
        return mappedFile;
    }

    // Off-line compiler, no need for security manager
    public Object getProtectionDomain() {
        return null;
    }

    @Override
    public boolean getSendErrorToClient() {
        // implied send to System.err
        return true;
    }

    public void setClassDebugInfo(boolean b) {
        classDebugInfo = b;
    }

    @Override
    public boolean getClassDebugInfo() {
        // compile with debug info
        return classDebugInfo;
    }

    /**
     * Background compilation check intervals in seconds
     */
    @Override
    public int getCheckInterval() {
        return 0;
    }

    /**
     * Modification test interval.
     */
    @Override
    public int getModificationTestInterval() {
        return 0;
    }

    /**
     * Is Wasp being used in development mode?
     */
    @Override
    public boolean getDevelopment() {
        return false;
    }

    @Override
    public boolean getUsePrecompiled() {
        return false;
    }

    /**
     * Is the generation of SMAP info for JSR45 debugging suppressed?
     */
    @Override
    public boolean isSmapSuppressed() {
        return smapSuppressed;
    }

    /**
     * Set smapSuppressed flag.
     */
    public void setSmapSuppressed(boolean smapSuppressed) {
        this.smapSuppressed = smapSuppressed;
    }

    /**
     * Should SMAP info for JSR45 debugging be dumped to a file?
     */
    @Override
    public boolean isSmapDumped() {
        return smapDumped;
    }

    /**
     * Set smapSuppressed flag.
     */
    public void setSmapDumped(boolean smapDumped) {
        this.smapDumped = smapDumped;
    }

    /**
     * Determines whether text strings are to be generated as char arrays, which improves performance in some cases.
     *
     * @param genStringAsCharArray true if text strings are to be generated as char arrays, false otherwise
     */
    public void setGenStringAsCharArray(boolean genStringAsCharArray) {
        this.genStringAsCharArray = genStringAsCharArray;
    }

    /**
     * Indicates whether text strings are to be generated as char arrays.
     *
     * @return true if text strings are to be generated as char arrays, false otherwise
     */
    @Override
    public boolean genStringAsCharArray() {
        return genStringAsCharArray;
    }

    public void setGenStringAsByteArray(boolean genStringAsByteArray) {
        this.genStringAsByteArray = genStringAsByteArray;
    }

    @Override
    public boolean genStringAsByteArray() {
        return genStringAsByteArray;
    }

    @Override
    public boolean isDefaultBufferNone() {
        return defaultBufferNone;
    }

    public void setDefaultBufferNone(boolean defaultBufferNone) {
        this.defaultBufferNone = defaultBufferNone;
    }

    /**
     * Sets the class-id value to be sent to Internet Explorer when using <code>&lt;jsp:plugin&gt;</code> tags.
     *
     * @param ieClassId Class-id value
     */
    public void setIeClassId(String ieClassId) {
        this.ieClassId = ieClassId;
    }

    /**
     * Gets the class-id value that is sent to Internet Explorer when using <code>&lt;jsp:plugin&gt;</code> tags.
     *
     * @return Class-id value
     */
    @Override
    public String getIeClassId() {
        return ieClassId;
    }

    @Override
    public File getScratchDir() {
        return scratchDir;
    }

    public Class getJspCompilerPlugin() {
        // we don't compile, so this is meaningless
        return null;
    }

    public String getJspCompilerPath() {
        // we don't compile, so this is meaningless
        return null;
    }

    /**
     * Compiler to use.
     */
    @Override
    public String getCompiler() {
        return compiler;
    }

    public void setCompiler(String c) {
        compiler = c;
    }

    /**
     * @see Options#getCompilerTargetVM
     */
    @Override
    public String getCompilerTargetVM() {
        return compilerTargetVM;
    }

    public void setCompilerTargetVM(String vm) {
        // START SJSAS 6402545
        String tvm = vm;
        if (JAVA_5.equals(vm)) {
            vm = JAVA_1_5;
        } else if (JAVA_6.equals(vm)) {
            vm = JAVA_1_6;
        } else if (JAVA_7.equals(vm)) {
            vm = JAVA_1_7;
        } else if (JAVA_8.equals(vm)) {
            vm = JAVA_1_8;
        }
        if (!JAVA_1_1.equals(vm) && !JAVA_1_2.equals(vm) && !JAVA_1_3.equals(vm) && !JAVA_1_4.equals(vm) && !JAVA_1_5.equals(vm) && !JAVA_1_6.equals(vm)
                && !JAVA_1_7.equals(vm) && !JAVA_1_8.equals(vm)) {
            throw new IllegalArgumentException(Localizer.getMessage("jspc.illegalCompilerTargetVM", tvm));
        }
        // END SJSAS 6402545
        // START SJSAS 6403017
        Double targetVersion = Double.valueOf(vm);
        if (targetVersion.compareTo(Double.valueOf(myJavaVersion)) > 0) {
            throw new IllegalArgumentException(Localizer.getMessage("jspc.compilerTargetVMTooHigh", vm));
        }
        // END SJSAS 6403017
        compilerTargetVM = vm;
    }

    /**
     * @see Options#getCompilerSourceVM
     */
    @Override
    public String getCompilerSourceVM() {
        return compilerSourceVM;
    }

    /**
     * @see Options#getCompilerSourceVM
     */
    public void setCompilerSourceVM(String vm) {
        // START SJSAS 6402545
        if (!JAVA_1_3.equals(vm) && !JAVA_1_4.equals(vm) && !JAVA_1_5.equals(vm) && !JAVA_5.equals(vm) && !JAVA_1_6.equals(vm) && !JAVA_6.equals(vm)
                && !JAVA_1_7.equals(vm) && !JAVA_7.equals(vm) && !JAVA_1_8.equals(vm) && !JAVA_8.equals(vm)) {
            throw new IllegalArgumentException(Localizer.getMessage("jspc.illegalCompilerSourceVM", vm));
        }
        // END SJSAS 6402545
        compilerSourceVM = vm;
    }

    /**
     * @see Options#getCompilerClassName
     */
    @Override
    public String getCompilerClassName() {
        return null;
    }

    @Override
    public TldScanner getTldScanner() {
        return tldScanner;
    }

    /**
     * Returns the encoding to use for java files. The default is UTF-8.
     *
     * @return String The encoding
     */
    @Override
    public String getJavaEncoding() {
        return javaEncoding;
    }

    /**
     * Sets the encoding to use for java files.
     *
     * @param encodingName The name, e.g. "UTF-8"
     */
    public void setJavaEncoding(String encodingName) {
        javaEncoding = encodingName;
    }

    @Override
    public boolean getFork() {
        return false;
    }

    @Override
    public String getClassPath() {
        if (classPath != null) {
            return classPath;
        }
        /*
         * PWC 1.2 6311155 return System.getProperty("java.class.path");
         */
        // START PWC 1.2 6311155
        return "";
        // END PWC 1.2 6311155
    }

    public void setClassPath(String s) {
        classPath = s;
    }

    // START PWC 1.2 6311155
    /**
     * Gets the system class path.
     *
     * @return The system class path
     */
    @Override
    public String getSystemClassPath() {
        if (sysClassPath != null) {
            return sysClassPath;
        } else {
            return System.getProperty("java.class.path");
        }
    }

    /**
     * Sets the system class path.
     *
     * @param s The system class path to use
     */
    public void setSystemClassPath(String s) {
        sysClassPath = s;
    }
    // END PWC 1.2 6311155

    /**
     * Base dir for the webapp. Used to generate class names and resolve includes
     */
    public void setUriroot(String s) {
        uriRoot = s;
        if (s != null) {
            try {
                uriRoot = new File(s).getCanonicalPath();
            } catch (Exception ex) {
                uriRoot = s;
            }
        }
    }

    // START PWC 6386258
    /**
     * Sets the path prefix for .xsd resources
     */
    public static void setSchemaResourcePrefix(String prefix) {
        ParserUtils.setSchemaResourcePrefix(prefix);
    }

    /**
     * Sets the path prefix for .dtd resources
     */
    public static void setDtdResourcePrefix(String prefix) {
        ParserUtils.setDtdResourcePrefix(prefix);
    }
    // END PWC 6386258

    /*
     * Parses comma-separated list of JSP files to be processed.
     *
     * <p>Each file is interpreted relative to uriroot, unless it is absolute, in which case it must start with uriroot.
     *
     * @param jspFiles Comma-separated list of JSP files to be processed
     */
    public void setJspFiles(String jspFiles) {
        StringTokenizer tok = new StringTokenizer(jspFiles, " ,");
        while (tok.hasMoreTokens()) {
            pages.add(tok.nextToken());
        }
    }

    public void setCompile(boolean b) {
        compile = b;
    }

    public void setVerbose(int level) {
        if (level > 0) {
            showSuccess = true;
            listErrors = true;
        }
    }

    public void setValidateXml(boolean b) {
        /*
         * SJSAS 6384538 org.glassfish.wasp.xmlparser.ParserUtils.validating=b;
         */
        // START SJSAS 6384538
        setIsValidationEnabled(b);
        // END SJSAS 6384538
    }

    // START SJSAS 6384538
    public void setIsValidationEnabled(boolean b) {
        isValidationEnabled = b;
    }

    @Override
    public boolean isValidationEnabled() {
        return isValidationEnabled;
    }
    // END SJSAS 6384538

    public void setListErrors(boolean b) {
        listErrors = b;
    }

    public void setOutputDir(String s) {
        if (s != null) {
            scratchDir = new File(s).getAbsoluteFile();
        } else {
            scratchDir = null;
        }
    }

    public void setPackage(String p) {
        targetPackage = p;
    }

    /**
     * Class name of the generated file ( without package ). Can only be used if a single file is converted. XXX Do we need
     * this feature ?
     */
    public void setClassName(String p) {
        targetClassName = p;
    }

    /**
     * File where we generate a web.xml fragment with the class definitions.
     */
    public void setWebXmlFragment(String s) {
        webxmlFile = s;
        webxmlLevel = INC_WEBXML;
    }

    /**
     * File where we generate a complete web.xml with the class definitions.
     */
    public void setWebXml(String s) {
        webxmlFile = s;
        webxmlLevel = ALL_WEBXML;
    }

    public void setAddWebXmlMappings(boolean b) {
        addWebXmlMappings = b;
    }

    /**
     * Set the option that throws an exception in case of a compilation error.
     */
    public void setFailOnError(final boolean b) {
        failOnError = b;
    }

    public boolean getFailOnError() {
        return failOnError;
    }

    // START SJSAS 6393940
    public void setIgnoreJspFragmentErrors(boolean ignore) {
        ignoreJspFragmentErrors = ignore;
    }
    // END SJSAS 6393940

    /**
     * Obtain JSP configuration informantion specified in web.xml.
     */
    @Override
    public JspConfig getJspConfig() {
        return jspConfig;
    }

    @Override
    public TagPluginManager getTagPluginManager() {
        return tagPluginManager;
    }

    public void generateWebMapping(String file, JspCompilationContext clctxt) throws IOException {
        String className = clctxt.getServletClassName();
        String packageName = clctxt.getServletPackageName();

        String thisServletName;
        if ("".equals(packageName)) {
            thisServletName = className;
        } else {
            thisServletName = packageName + '.' + className;
        }

        if (servletout != null) {
            servletout.write("\n    <servlet>\n        <servlet-name>");
            servletout.write(thisServletName);
            servletout.write("</servlet-name>\n        <servlet-class>");
            servletout.write(thisServletName);
            servletout.write("</servlet-class>\n    </servlet>\n");
        }
        if (mappingout != null) {
            mappingout.write("\n    <servlet-mapping>\n        <servlet-name>");
            mappingout.write(thisServletName);
            mappingout.write("</servlet-name>\n        <url-pattern>");
            mappingout.write(file.replace('\\', '/'));
            mappingout.write("</url-pattern>\n    </servlet-mapping>\n");

        }
    }

    // START SJSAS 6329723
    /**
     * Gets the list of JSP compilation errors caught during the most recent invocation of this instance's
     * <code>execute</code> method when failOnError has been set to FALSE.
     *
     * Each error error in the list is represented by an instance of org.glassfish.wasp.WaspException.
     *
     * @return List of JSP compilation errors caught during most recent invocation of this instance's <code>execute</code>
     * method, or an empty list if no errors were encountered or this instance's failOnError property was set to TRUE
     */
    public List<WaspException> getJSPCompilationErrors() {

        ArrayList<WaspException> ret = null;

        Collection<WaspException> c = jspErrors.values();
        if (c != null) {
            ret = new ArrayList<>();
            Iterator<WaspException> it = c.iterator();
            while (it.hasNext()) {
                ret.add(it.next());
            }
        }

        return ret;
    }
    // END SJSAS 6329723

    /**
     * Include the generated web.xml inside the webapp's web.xml.
     */
    protected void mergeIntoWebXml() throws IOException {

        File webappBase = new File(uriRoot);
        File webXml = new File(webappBase, "WEB-INF/web.xml");
        File webXml2 = new File(webappBase, "WEB-INF/web2.xml");
        String insertStartMarker = Localizer.getMessage("jspc.webinc.insertStart");
        String insertEndMarker = Localizer.getMessage("jspc.webinc.insertEnd");

        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(webXml), "UTF-8"));
        BufferedReader fragmentReader = new BufferedReader(new InputStreamReader(new FileInputStream(webxmlFile), "UTF-8"));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(webXml2), "UTF-8"));

        // Insert the <servlet> and <servlet-mapping> declarations
        int pos = -1;
        String line = null;
        while (true) {
            line = reader.readLine();
            if (line == null) {
                break;
            }
            // Skip anything previously generated by JSPC
            if (line.indexOf(insertStartMarker) >= 0) {
                while (true) {
                    line = reader.readLine();
                    if (line == null) {
                        return;
                    }
                    if (line.indexOf(insertEndMarker) >= 0) {
                        line = reader.readLine();
                        if (line == null) {
                            return;
                        }
                        break;
                    }
                }
            }
            for (int i = 0; i < insertBefore.length; i++) {
                pos = line.indexOf(insertBefore[i]);
                if (pos >= 0) {
                    break;
                }
            }
            if (pos >= 0) {
                writer.println(line.substring(0, pos));
                break;
            } else {
                writer.println(line);
            }
        }

        writer.println(insertStartMarker);
        while (true) {
            String line2 = fragmentReader.readLine();
            if (line2 == null) {
                writer.println();
                break;
            }
            writer.println(line2);
        }
        writer.println(insertEndMarker);
        writer.println();

        for (int i = 0; i < pos; i++) {
            writer.print(" ");
        }
        if (line != null) {
            writer.println(line.substring(pos));
        }

        while (true) {
            line = reader.readLine();
            if (line == null) {
                break;
            }
            writer.println(line);
        }
        writer.close();

        reader.close();
        fragmentReader.close();

        FileInputStream fis = new FileInputStream(webXml2);
        FileOutputStream fos = new FileOutputStream(webXml);

        byte buf[] = new byte[512];

        try {
            while (true) {
                int n = fis.read(buf);
                if (n < 0) {
                    break;
                }
                fos.write(buf, 0, n);
            }
        } finally {
            if (fis != null) {
                fis.close();
            }
            if (fos != null) {
                fos.close();
            }
        }

        webXml2.delete();
        new File(webxmlFile).delete();

    }

    private void processFile(String file) throws WaspException {
        ClassLoader originalClassLoader = null;
        String jspUri = file.replace('\\', '/');

        try {
            // set up a scratch/output dir if none is provided
            if (scratchDir == null) {
                String temp = System.getProperty("java.io.tmpdir");
                if (temp == null) {
                    temp = "";
                }
                scratchDir = new File(new File(temp).getAbsolutePath());
            }

            JspCompilationContext clctxt = new JspCompilationContext(jspUri, false, this, context, null, rctxt);

            /* Override the defaults */
            if (targetClassName != null && targetClassName.length() > 0) {
                clctxt.setServletClassName(targetClassName);
                targetClassName = null;
            }
            if (targetPackage != null) {
                clctxt.setServletPackageName(targetPackage);
            }

            originalClassLoader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                initClassLoader(clctxt);
            }
            Thread.currentThread().setContextClassLoader(loader);

            clctxt.setClassLoader(loader);
            clctxt.setClassPath(classPath);

            Compiler clc = clctxt.createCompiler(true);

            // If compile is set, generate both .java and .class, if
            // .jsp file is newer than .class file;
            // Otherwise only generate .java, if .jsp file is newer than
            // the .java file
            if (clc.isOutDated(compile)) {
                clc.compile(compile);
            }

            // START SJSAS 6393940
            if (ignoreJspFragmentErrors) {
                PageInfo pi = clc.getPageInfo();
                if (pi != null) {
                    List<String> deps = pi.getDependants();
                    if (deps != null) {
                        Iterator<String> it = deps.iterator();
                        if (it != null) {
                            while (it.hasNext()) {
                                dependents.add(it.next());
                            }
                        }
                    }
                    clc.setPageInfo(null);
                }
            }
            // END SJSAS 6393940

            // Generate mapping
            generateWebMapping(file, clctxt);
            if (showSuccess) {
                log.info("Built File: " + file);
            }

        } catch (WaspException je) {
            Throwable rootCause = je;
            while (rootCause instanceof WaspException && ((WaspException) rootCause).getRootCause() != null) {
                rootCause = ((WaspException) rootCause).getRootCause();
            }
            if (listErrors && rootCause != je) {
                log.log(Level.SEVERE, Localizer.getMessage("jspc.error.generalException", file), rootCause);
            }

            // Bugzilla 35114.
            if (getFailOnError() && !ignoreJspFragmentErrors) {
                throw je;
            } else {
                if (listErrors && !ignoreJspFragmentErrors) {
                    log.severe(je.getMessage());
                }
                // START SJAS 6329723
                jspErrors.put(jspUri, je);
                // END SJSAS 6329723
            }

        } catch (Exception e) {
            if (e instanceof FileNotFoundException && log.isLoggable(Level.WARNING)) {
                log.warning(Localizer.getMessage("jspc.error.fileDoesNotExist", e.getMessage()));
            }
            throw new WaspException(e);
        } finally {
            if (originalClassLoader != null) {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }

    }

    /**
     * Locate all jsp files in the webapp. Used if no explicit jsps are specified.
     */
    public void scanFiles(File base) throws WaspException {
        Stack<String> dirs = new Stack<>();
        dirs.push(base.toString());
        if (extensions == null) {
            extensions = new ArrayList<>();
            extensions.add("jsp");
            extensions.add("jspx");
        }
        while (!dirs.isEmpty()) {
            String s = dirs.pop();
            File f = new File(s);
            if (f.exists() && f.isDirectory()) {
                String[] files = f.list();
                String ext;
                for (int i = 0; files != null && i < files.length; i++) {
                    File f2 = new File(s, files[i]);
                    if (f2.isDirectory()) {
                        dirs.push(f2.getPath());
                    } else {
                        String path = f2.getPath();
                        String uri = path.substring(uriRoot.length());
                        ext = files[i].substring(files[i].lastIndexOf('.') + 1);
                        if (extensions.contains(ext) || jspConfig.isJspPage(uri)) {
                            pages.add(path);
                        }
                    }
                }
            }
        }
    }

    public void execute() throws WaspException {

        // START SJSAS 6329723
        jspErrors.clear();
        // END SJSAS 6329723
        // START SJSAS 6393940
        dependents.clear();
        // END SJSAS 6393940

        try {
            if (uriRoot == null) {
                if (pages.size() == 0) {
                    throw new WaspException(Localizer.getMessage("jsp.error.jspc.missingTarget"));
                }
                String firstJsp = pages.get(0);
                File firstJspF = new File(firstJsp);
                if (!firstJspF.exists()) {
                    throw new WaspException(Localizer.getMessage("jspc.error.fileDoesNotExist", firstJsp));
                }
                locateUriRoot(firstJspF);
            }

            if (uriRoot == null) {
                throw new WaspException(Localizer.getMessage("jsp.error.jspc.no_uriroot"));
            }

            if (context == null) {
                initServletContext();
            }

            // No explicit pages, we'll process all .jsp in the webapp
            if (pages.size() == 0) {
                scanFiles(new File(uriRoot));
            }

            File uriRootF = new File(uriRoot);
            if (!uriRootF.exists() || !uriRootF.isDirectory()) {
                throw new WaspException(Localizer.getMessage("jsp.error.jspc.uriroot_not_dir"));
            }

            initWebXml();

            for (String nextjsp : pages) {
                File fjsp = new File(nextjsp);
                if (!fjsp.isAbsolute()) {
                    fjsp = new File(uriRootF, nextjsp);
                }
                if (!fjsp.exists()) {
                    if (log.isLoggable(Level.WARNING)) {
                        log.warning(Localizer.getMessage("jspc.error.fileDoesNotExist", fjsp.toString()));
                    }
                    continue;
                }
                String s = fjsp.getAbsolutePath();
                if (s.startsWith(uriRoot)) {
                    nextjsp = s.substring(uriRoot.length());
                }
                if (nextjsp.startsWith("." + File.separatorChar)) {
                    nextjsp = nextjsp.substring(2);
                }
                processFile(nextjsp);
            }

            // START SJSAS 6393940
            if (ignoreJspFragmentErrors) {
                purgeJspFragmentErrors();
            }
            if (getFailOnError() && !jspErrors.isEmpty()) {
                throw jspErrors.values().iterator().next();
            }
            // END SJJAS 6393940

            completeWebXml();

            if (addWebXmlMappings) {
                mergeIntoWebXml();
            }

        } catch (IOException ioe) {
            throw new WaspException(ioe);

        } catch (WaspException je) {
            Throwable rootCause = je;
            while (rootCause instanceof WaspException && ((WaspException) rootCause).getRootCause() != null) {
                rootCause = ((WaspException) rootCause).getRootCause();
            }
            if (rootCause != je) {
                rootCause.printStackTrace();
            }
            throw je;
        } finally {
            // START S1AS 5032338
            if (loader != null) {
                // XXX APACHE-COMMONS-LOGGING-PATCH
                // LogFactory.release(loader);
                // START SJSAS 6258619
                // ClassLoaderUtil.releaseLoader(loader);
                // END SJSAS 6258619
            }
            // END S1AS 5032338
            // START SJSAS 6356052
            if (rctxt != null) {
                rctxt.destroy();
            }
            // END SJSAS 6356052

            // START GlassFish 750
            if (taglibs != null) {
                taglibs.clear();
            }
            if (tagFileJarUrls != null) {
                tagFileJarUrls.clear();
            }
            // END GlassFish 750
        }
    }

    // ==================== Private utility methods ====================

    private String nextArg() {
        if (argPos >= args.length || (fullstop = SWITCH_FULL_STOP.equals(args[argPos]))) {
            return null;
        } else {
            return args[argPos++];
        }
    }

    private String nextFile() {
        if (fullstop) {
            argPos++;
        }
        if (argPos >= args.length) {
            return null;
        } else {
            return args[argPos++];
        }
    }

    private void initWebXml() {
        try {
            if (webxmlLevel >= INC_WEBXML) {
                File fmapings = new File(webxmlFile);
                mapout = new OutputStreamWriter(new FileOutputStream(fmapings), "UTF-8");
                servletout = new CharArrayWriter();
                mappingout = new CharArrayWriter();
            } else {
                mapout = null;
                servletout = null;
                mappingout = null;
            }
            if (webxmlLevel >= ALL_WEBXML) {
                mapout.write(Localizer.getMessage("jspc.webxml.header"));
                mapout.flush();
            } else if (webxmlLevel >= INC_WEBXML && !addWebXmlMappings) {
                mapout.write(Localizer.getMessage("jspc.webinc.header"));
                mapout.flush();
            }
        } catch (IOException ioe) {
            mapout = null;
            servletout = null;
            mappingout = null;
        }
    }

    private void completeWebXml() {
        if (mapout != null) {
            try {
                servletout.writeTo(mapout);
                mappingout.writeTo(mapout);
                if (webxmlLevel >= ALL_WEBXML) {
                    mapout.write(Localizer.getMessage("jspc.webxml.footer"));
                } else if (webxmlLevel >= INC_WEBXML && !addWebXmlMappings) {
                    mapout.write(Localizer.getMessage("jspc.webinc.footer"));
                }
                mapout.close();
            } catch (IOException ioe) {
                // noting to do if it fails since we are done with it
            }
        }
    }

    private void initServletContext() {
        try {
            context = new JspCServletContext(new PrintWriter(new OutputStreamWriter(System.out, "UTF-8")),

                    new URL("file:" + uriRoot.replace('\\', '/') + '/'));
            tldScanner = new TldScanner(context, isValidationEnabled);

            // START GlassFish 750
            taglibs = new ConcurrentHashMap<>();
            context.setAttribute(Constants.JSP_TAGLIBRARY_CACHE, taglibs);

            tagFileJarUrls = new ConcurrentHashMap<>();
            context.setAttribute(Constants.JSP_TAGFILE_JAR_URLS_CACHE, tagFileJarUrls);
            // END GlassFish 750
        } catch (MalformedURLException me) {
            System.out.println("**" + me);
        } catch (UnsupportedEncodingException ex) {
        }
        rctxt = new JspRuntimeContext(context, this);
        jspConfig = new JspConfig(context);
        tagPluginManager = new TagPluginManager(context);
    }

    /**
     * Initializes the classloader as/if needed for the given compilation context.
     *
     * @param clctxt The compilation context
     * @throws IOException If an error occurs
     */
    private void initClassLoader(JspCompilationContext clctxt) throws IOException {

        classPath = getClassPath();

        getClass().getClassLoader();

        // Turn the classPath into URLs
        ArrayList<URL> urls = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(classPath, File.pathSeparator);
        while (tokenizer.hasMoreTokens()) {
            String path = tokenizer.nextToken();
            try {
                File libFile = new File(path);
                urls.add(libFile.toURL());
            } catch (IOException ioe) {
                // Failing a toCanonicalPath on a file that
                // exists() should be a JVM regression test,
                // therefore we have permission to freak uot
                throw new RuntimeException(ioe.toString());
            }
        }

        File webappBase = new File(uriRoot);
        if (webappBase.exists()) {
            File classes = new File(webappBase, "/WEB-INF/classes");
            try {
                if (classes.exists()) {
                    classPath = classPath + File.pathSeparator + classes.getCanonicalPath();
                    urls.add(classes.getCanonicalFile().toURL());
                }
            } catch (IOException ioe) {
                // failing a toCanonicalPath on a file that
                // exists() should be a JVM regression test,
                // therefore we have permission to freak out
                throw new RuntimeException(ioe.toString());
            }
            File lib = new File(webappBase, "/WEB-INF/lib");
            if (lib.exists() && lib.isDirectory()) {
                String[] libs = lib.list();
                for (int i = 0; i < libs.length; i++) {
                    if (libs[i].length() < 5) {
                        continue;
                    }
                    String ext = libs[i].substring(libs[i].length() - 4);
                    if (!".jar".equalsIgnoreCase(ext)) {
                        if (".tld".equalsIgnoreCase(ext)) {
                            log.warning("TLD files should not be placed in /WEB-INF/lib");
                        }
                        continue;
                    }
                    try {
                        File libFile = new File(lib, libs[i]);
                        classPath = classPath + File.pathSeparator + libFile.getCanonicalPath();
                        urls.add(libFile.getCanonicalFile().toURL());
                    } catch (IOException ioe) {
                        // failing a toCanonicalPath on a file that
                        // exists() should be a JVM regression test,
                        // therefore we have permission to freak out
                        throw new RuntimeException(ioe.toString());
                    }
                }
            }
        }

        // What is this ??
        urls.add(new File(clctxt.getRealPath("/")).getCanonicalFile().toURL());

        URL urlsA[] = new URL[urls.size()];
        urls.toArray(urlsA);

        /*
         * SJSAS 6327357 loader = new URLClassLoader(urlsA, this.getClass().getClassLoader());
         */
        // START SJSAS 6327357
        ClassLoader sysClassLoader = initSystemClassLoader();
        if (sysClassLoader != null) {
            loader = new URLClassLoader(urlsA, sysClassLoader);
        } else {
            loader = new URLClassLoader(urlsA, this.getClass().getClassLoader());
        }
        // END SJSAS 6327357
    }

    /**
     * Find the WEB-INF dir by looking up in the directory tree. This is used if no explicit docbase is set, but only files.
     * XXX Maybe we should require the docbase.
     */
    private void locateUriRoot(File f) {
        String tUriBase = uriBase;
        if (tUriBase == null) {
            tUriBase = "/";
        }
        try {
            if (f.exists()) {
                f = new File(f.getCanonicalPath());
                while (f != null) {
                    File g = new File(f, "WEB-INF");
                    if (g.exists() && g.isDirectory()) {
                        uriRoot = f.getCanonicalPath();
                        uriBase = tUriBase;
                        if (log.isLoggable(Level.INFO)) {
                            log.info(Localizer.getMessage("jspc.implicit.uriRoot", uriRoot));
                        }
                        break;
                    }
                    if (f.exists() && f.isDirectory()) {
                        tUriBase = "/" + f.getName() + "/" + tUriBase;
                    }

                    String fParent = f.getParent();
                    if (fParent == null) {
                        break;
                    } else {
                        f = new File(fParent);
                    }

                    // If there is no acceptible candidate, uriRoot will
                    // remain null to indicate to the CompilerContext to
                    // use the current working/user dir.
                }

                if (uriRoot != null) {
                    File froot = new File(uriRoot);
                    uriRoot = froot.getCanonicalPath();
                }
            }
        } catch (IOException ioe) {
            // since this is an optional default and a null value
            // for uriRoot has a non-error meaning, we can just
            // pass straight through
        }
    }

    // START SJSAS 6327357
    private ClassLoader initSystemClassLoader() throws IOException {

        String sysClassPath = getSystemClassPath();
        if (sysClassPath == null) {
            return null;
        }

        ArrayList<URL> urls = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(sysClassPath, File.pathSeparator);
        while (tokenizer.hasMoreTokens()) {
            urls.add(new File(tokenizer.nextToken()).toURL());
        }

        if (urls.size() == 0) {
            return null;
        }

        URL urlsArray[] = new URL[urls.size()];
        urls.toArray(urlsArray);

        return new URLClassLoader(urlsArray, this.getClass().getClassLoader());
    }
    // END SJAS 6327357

    // START SJSAS 6393940
    /*
     * Purges all compilation errors related to JSP fragments.
     */
    private void purgeJspFragmentErrors() {
        Iterator<String> it = dependents.iterator();
        if (it != null) {
            while (it.hasNext()) {
                jspErrors.remove(it.next());
            }
        }
    }
    // END SJSAS 6393940
}
