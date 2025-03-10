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

/**
 * Interface for tracking the source files dependencies, for the purpose of compiling out of date pages. This is used
 * for 1) files that are included by page directives 2) files that are included by include-prelude and include-coda in
 * jsp:config 3) files that are tag files and referenced 4) TLDs referenced
 */

public interface JspSourceDependent {

    /**
     * Returns a list of files names that the current page has a source dependency on.
     */
    java.util.List<String> getDependants();
}
