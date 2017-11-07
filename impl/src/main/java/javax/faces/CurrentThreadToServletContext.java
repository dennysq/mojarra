/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package javax.faces;

import static com.sun.faces.util.Util.coalesce;
import static com.sun.faces.util.Util.getContextClassLoader2;
import static com.sun.faces.util.Util.isAnyNull;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.identityHashCode;
import static java.util.logging.Level.WARNING;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;

final class CurrentThreadToServletContext {
    
    private static final Logger LOGGER = Logger.getLogger("javax.faces", "javax.faces.LogStrings");
    
    // Bug 20458755: This instance provides a method to look up the current FacesContext
    // that bypasses the additional check for the InitFacesContext introduced
    // by the fix for 20458755
    private final ServletContextFacesContextFactory servletContextFacesContextFactory = new ServletContextFacesContextFactory();
    
    ConcurrentMap<FactoryFinderCacheKey, FactoryFinderInstance> factoryFinderMap = new ConcurrentHashMap<>();
    private AtomicBoolean logNullFacesContext = new AtomicBoolean();
    private AtomicBoolean logNonNullFacesContext = new AtomicBoolean();


    
    // ------------------------------------------------------ Public Methods


    FactoryFinderInstance getFactoryFinder() {
        return getFactoryFinder(getContextClassLoader2(), true);
    }

    FactoryFinderInstance getFactoryFinder(boolean create) {
        return getFactoryFinder(getContextClassLoader2(), create);
    }

    private FactoryFinderInstance getFactoryFinder(ClassLoader classLoader, boolean create) {
       
        FacesContext facesContext = servletContextFacesContextFactory.getFacesContextWithoutServletContextLookup();
       
        boolean isSpecialInitializationCase = detectSpecialInitializationCase(facesContext);
        FactoryFinderCacheKey key = new FactoryFinderCacheKey(facesContext, classLoader, factoryFinderMap);
        FactoryFinderInstance factoryFinder = factoryFinderMap.get(key);
        FactoryFinderInstance toCopy = null;
        
        if (factoryFinder == null && create) {
            boolean createNewFactoryFinderInstance = false;
            if (isSpecialInitializationCase) {
                
                // We need to obtain a reference to the correct FactoryFinderInstance. 
                // Iterate through the data structure containing all FactoryFinderInstance instances for this VM.
                boolean classLoadersMatchButContextsDoNotMatch = false;
                boolean foundNoMatchInApplicationMap = true;
                
                for (Map.Entry<FactoryFinderCacheKey, FactoryFinderInstance> cur : factoryFinderMap.entrySet()) {
                    FactoryFinderCacheKey curKey = cur.getKey();

                    // If the current FactoryFinderInstance is for the same ClassLoader as the current ClassLoader...
                    if (curKey.getClassLoader().equals(classLoader)) {
                        foundNoMatchInApplicationMap = false;
                        
                        // Check the other discriminator for the key: the context.
                        // If the context objects of the keys are both non-null and non-equal, then *do*
                        // create a new FactoryFinderInstance instance.
                        
                        if (!isAnyNull(key.getContext(), curKey.getContext()) && !key.getContext().equals(curKey.getContext())) {
                            classLoadersMatchButContextsDoNotMatch = true;
                            toCopy = cur.getValue();
                        } else {
                            // Otherwise, use this FactoryFinderInstance instance.
                            factoryFinder = cur.getValue();
                        }
                        
                        break;
                    }
                }
                
                // We must create a new FactoryFinderInstance if there was no matchingKey
                // at all found in the applicationMap, or a matchingKey was found
                // and the matchingKey is safe to use in this web app
                createNewFactoryFinderInstance = foundNoMatchInApplicationMap || (factoryFinder == null && classLoadersMatchButContextsDoNotMatch);
            } else {
                createNewFactoryFinderInstance = true;
            }
            
            if (createNewFactoryFinderInstance) {
                FactoryFinderInstance newResult;
                if (toCopy != null) {
                    newResult = new FactoryFinderInstance(facesContext, toCopy);
                } else {
                    newResult = new FactoryFinderInstance(facesContext);
                }
                
                factoryFinder = coalesce(factoryFinderMap.putIfAbsent(key, newResult), newResult);
            }
        }
        
        return factoryFinder;
    }
    
    Object getFallbackFactory(FactoryFinderInstance brokenFactoryManager, String factoryName) {
        
        ClassLoader classLoader = getContextClassLoader2();
        for (Map.Entry<FactoryFinderCacheKey, FactoryFinderInstance> cur : factoryFinderMap.entrySet()) {
            if (cur.getKey().getClassLoader().equals(classLoader) && !cur.getValue().equals(brokenFactoryManager)) {
                Object factory = cur.getValue().getFactory(factoryName);
                if (factory != null) {
                    return factory;
                }
            }
        }
        
        return null;
    }

    /**
     * Uses the FactoryManagerCacheKey system to find the ServletContext associated with the current
     * ClassLoader, if any.
     */
    Object getServletContextForCurrentClassLoader() {
        return new FactoryFinderCacheKey(null, getContextClassLoader2(), factoryFinderMap).getContext();
    }

    /**
     * This method is used to detect the following special initialization case. IF no
     * FactoryFinderInstance can be found for key, AND this call to
     * getApplicationFactoryFinderInstance() *does* have a currentKeyrent FacesContext BUT a
     * previous call to getApplicationFactoryFinderInstance *did not* have a currentKeyrent
     * FacesContext
     *
     * @param facesContext the currentKeyrent FacesContext for this request
     * @return true if the currentKeyrent execution falls into the special initialization case.
     */
    private boolean detectSpecialInitializationCase(FacesContext facesContext) {
        if (facesContext == null) {
            logNullFacesContext.compareAndSet(false, true);
        } else {
            logNonNullFacesContext.compareAndSet(false, true);
        }
        
        return logNullFacesContext.get() && logNonNullFacesContext.get();
    }

    void removeFactoryFinder() {
        ClassLoader classLoader = getContextClassLoader2();
        FactoryFinderInstance factoryFinder = getFactoryFinder(classLoader, false);
        if (factoryFinder != null) {
            factoryFinder.clearInjectionProvider();
        }
        
        FacesContext facesContext = servletContextFacesContextFactory.getFacesContextWithoutServletContextLookup();
        boolean isSpecialInitializationCase = detectSpecialInitializationCase(facesContext);
        
        factoryFinderMap.remove(new FactoryFinderCacheKey(facesContext, classLoader, factoryFinderMap));
       
        if (isSpecialInitializationCase) {
            resetSpecialInitializationCaseFlags();
        }
    }

    void resetSpecialInitializationCaseFlags() {
        logNullFacesContext.set(false);
        logNonNullFacesContext.set(false);
    }

    private static final class FactoryFinderCacheKey {
        
        /**
         * The ClassLoader that is active the first time this key is created. 
         * At startup time, this is assumed to be the web app ClassLoader
         */
        private ClassLoader classLoader;
        
        /**
         * A marker that disambiguates the case when multiple web apps have the same web app
         * ClassLoader but different ServletContext instances.
         */
        private Long marker;
        
        /**
         * The ServletContext corresponding to this marker/ClassLoader pair.
         */
        private Object context;

        private static final String MARKER_KEY = FactoryFinder.class.getName() + "." + FactoryFinderCacheKey.class.getSimpleName();
        private static final String INIT_TIME_CL_KEY = MARKER_KEY + ".InitTimeCLKey";

        FactoryFinderCacheKey(FacesContext facesContext, ClassLoader classLoaderIn, Map<FactoryFinderCacheKey, FactoryFinderInstance> factoryMap) {
            ExternalContext extContext = facesContext != null ? facesContext.getExternalContext() : null;
            Object servletContext = extContext != null ? extContext.getContext() : null;

            if (isAnyNull(facesContext, extContext, servletContext)) {
                initFromFactoryMap(classLoaderIn, factoryMap);
            } else {
                initFromApplicationMap(extContext, classLoaderIn);
            }
        }

        private void initFromFactoryMap(ClassLoader classLoaderIn, Map<FactoryFinderCacheKey, FactoryFinderInstance> factoryFinderMap) {
            
            // We don't have a FacesContext.
            //
            // Our only recourse is to inspect the keys of the factoryFinderMap and see if any of them has a classloader
            // equal to our argument classLoader
            
            Set<FactoryFinderCacheKey> keys = factoryFinderMap.keySet();
            FactoryFinderCacheKey matchingKey = null;

            if (keys.isEmpty()) {
                classLoader = classLoaderIn;
                marker = currentTimeMillis();
            } else {

                // For each entry in the factoryMap's keySet...
                for (FactoryFinderCacheKey currentKey : keys) {
                    
                    ClassLoader matchingClassLoader = findMatchConsideringParentClassLoader(classLoaderIn, currentKey.classLoader);
                    
                    // If there is a match...
                    if (matchingClassLoader != null) {
                        // If the match was found on a previous iteration...
                        if (matchingKey != null) {
                            LOGGER.log(WARNING,
                                "Multiple JSF Applications found on same ClassLoader.  Unable to safely determine which FactoryFinder instance to use. Defaulting to first match.");
                            break;
                        }
                        matchingKey = currentKey;
                        classLoader = matchingClassLoader;
                    }
                }
                
                if (matchingKey != null) {
                    marker = matchingKey.marker;
                    context = matchingKey.context;
                }
            }
        }

        private ClassLoader findMatchConsideringParentClassLoader(ClassLoader argumentClassLoader, ClassLoader currentKeyCL) {
            ClassLoader currentClassLoader = argumentClassLoader;
            
            // For each ClassLoader in the hierarchy starting with the argument ClassLoader...
            while (currentClassLoader != null) {
                
                // If the ClassLoader at this level in the hierarchy is equal to the argument ClassLoader, 
                // consider it a matchingKey.
                if (currentClassLoader.equals(currentKeyCL)) {
                    return currentClassLoader;
                } else {
                    // If it's not a matchingKey, try the parent in the ClassLoader hierarchy.
                    currentClassLoader = currentClassLoader.getParent();
                }
            }
            
            return null;
        }

        private void initFromApplicationMap(ExternalContext extContext, ClassLoader classLoaderIn) {
            Map<String, Object> applicationMap = extContext.getApplicationMap();

            Long val = (Long) applicationMap.get(MARKER_KEY);
            if (val == null) {
                marker = currentTimeMillis();
                applicationMap.put(MARKER_KEY, marker);

                // If we needed to create a marker, assume that the argument CL is safe to treat as the web app
                // ClassLoader. 
                //
                // This assumption allows us to bypass the ClassLoader resolution algorithm in resolveToFirstTimeUsedClassLoader()
                // in all cases except when the TCCL has been replaced.
                
                applicationMap.put(INIT_TIME_CL_KEY, identityHashCode(classLoaderIn));

            } else {
                marker = val;
            }
            
            classLoader = resolveToFirstTimeUsedClassLoader(classLoaderIn, extContext);
            context = extContext.getContext();
        }

        /**
         * Resolve the argument ClassLoader to be the ClassLoader that was passed in to the ctor the
         * first time a FactoryManagerCacheKey was created for this web app.
         */
        private ClassLoader resolveToFirstTimeUsedClassLoader(ClassLoader classLoaderToResolve, ExternalContext extContext) {
            ClassLoader currentClassLoader = classLoaderToResolve;
            Map<String, Object> appMap = extContext.getApplicationMap();

            // See if the argument currentClassLoader already is the web app class loader
            Integer webAppCLHashCode = (Integer) appMap.get(INIT_TIME_CL_KEY);
            boolean found = false;
            if (webAppCLHashCode != null) {
                int toResolveHashCode = identityHashCode(currentClassLoader);
                while (!found && currentClassLoader != null) {
                    found = toResolveHashCode == webAppCLHashCode;
                    if (!found) {
                        currentClassLoader = currentClassLoader.getParent();
                        toResolveHashCode = identityHashCode(currentClassLoader);
                    }
                }
            }

            return found ? currentClassLoader : classLoaderToResolve;
        }

        ClassLoader getClassLoader() {
            return classLoader;
        }

        Object getContext() {
            return context;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            
            if (getClass() != obj.getClass()) {
                return false;
            }
            
            final FactoryFinderCacheKey other = (FactoryFinderCacheKey) obj;
            if (this.classLoader != other.classLoader && (this.classLoader == null || !this.classLoader.equals(other.classLoader))) {
                return false;
            }
            
            if (this.marker != other.marker && (this.marker == null || !this.marker.equals(other.marker))) {
                return false;
            }
            
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + (classLoader != null ? this.classLoader.hashCode() : 0);
            return 97 * hash + (marker != null ? marker.hashCode() : 0);
        }

    }

}
