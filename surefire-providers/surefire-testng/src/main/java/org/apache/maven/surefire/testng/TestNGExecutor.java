package org.apache.maven.surefire.testng;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.surefire.booter.ProviderParameterNames;
import org.apache.maven.surefire.report.RunListener;
import org.apache.maven.surefire.testng.conf.Configurator;
import org.apache.maven.surefire.testset.TestSetFailedException;
import org.apache.maven.surefire.util.ReflectionUtils;
import org.apache.maven.surefire.util.internal.StringUtils;
import org.testng.TestNG;
import org.testng.annotations.Test;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlMethodSelector;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contains utility methods for executing TestNG.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @author <a href='mailto:the[dot]mindstorm[at]gmail[dot]com'>Alex Popescu</a>
 */
public class TestNGExecutor
{
    /** The default name for a suite launched from the maven surefire plugin */
    public static final String DEFAULT_SUREFIRE_SUITE_NAME = "Surefire suite";

    /** The default name for a test launched from the maven surefire plugin */
    public static final String DEFAULT_SUREFIRE_TEST_NAME = "Surefire test";

    private static final boolean HAS_TEST_ANNOTATION_ON_CLASSPATH =
        null != ReflectionUtils.tryLoadClass( TestNGExecutor.class.getClassLoader(), "org.testng.annotations.Test" );

    private TestNGExecutor()
    {
        // noop
    }

    public static void run( Class[] testClasses, String testSourceDirectory, Map options, RunListener reportManager,
                            TestNgTestSuite suite, File reportsDirectory, final String methodNamePattern )
        throws TestSetFailedException
    {
        TestNG testng = new TestNG( true );

        Configurator configurator = getConfigurator( (String) options.get( "testng.configurator" ) );
        System.out.println( "Configuring TestNG with: " + configurator.getClass().getSimpleName() );

        XmlMethodSelector groupMatchingSelector = getGroupMatchingSelector( options );
        XmlMethodSelector methodNameFilteringSelector = getMethodNameFilteringSelector( methodNamePattern );

        Map<String, SuiteAndNamedTests> suitesNames = new HashMap<String, SuiteAndNamedTests>();

        List<XmlSuite> xmlSuites = new ArrayList<XmlSuite>();
        for ( Class testClass : testClasses )
        {
            TestMetadata metadata = findTestMetadata( testClass );

            SuiteAndNamedTests suiteAndNamedTests = suitesNames.get( metadata.suiteName );
            if ( suiteAndNamedTests == null )
            {
                suiteAndNamedTests = new SuiteAndNamedTests();
                suiteAndNamedTests.xmlSuite.setName( metadata.suiteName );
                configurator.configure( suiteAndNamedTests.xmlSuite, options );
                xmlSuites.add( suiteAndNamedTests.xmlSuite );

                suitesNames.put( metadata.suiteName, suiteAndNamedTests );
            }

            XmlTest xmlTest = suiteAndNamedTests.testNameToTest.get( metadata.testName );
            if ( xmlTest == null )
            {
                xmlTest = new XmlTest( suiteAndNamedTests.xmlSuite );
                xmlTest.setName( metadata.testName );
                addSelector( xmlTest, groupMatchingSelector );
                addSelector( xmlTest, methodNameFilteringSelector );
                xmlTest.setXmlClasses( new ArrayList<XmlClass>() );

                suiteAndNamedTests.testNameToTest.put( metadata.testName, xmlTest );
            }

            xmlTest.getXmlClasses().add( new XmlClass( testClass.getName() ) );
        }

        testng.setXmlSuites( xmlSuites );
        configurator.configure( testng, options );
        postConfigure( testng, testSourceDirectory, reportManager, suite, reportsDirectory );
        testng.run();
    }

    private static TestMetadata findTestMetadata( Class testClass )
    {
        TestMetadata result = new TestMetadata();
        if ( HAS_TEST_ANNOTATION_ON_CLASSPATH )
        {
            Test testAnnotation = findAnnotation( testClass, Test.class );
            if ( null != testAnnotation )
            {
                if ( !StringUtils.isBlank( testAnnotation.suiteName() ) )
                {
                    result.suiteName = testAnnotation.suiteName();
                }

                if ( !StringUtils.isBlank( testAnnotation.testName() ) )
                {
                    result.testName = testAnnotation.testName();
                }
            }
        }
        return result;
    }

    private static <T extends Annotation> T findAnnotation( Class<?> clazz, Class<T> annotationType )
    {
        if ( clazz == null )
        {
            return null;
        }

        T result = clazz.getAnnotation( annotationType );
        if ( result != null )
        {
            return result;
        }

        return findAnnotation( clazz.getSuperclass(), annotationType );
    }

    private static class TestMetadata
    {
        private String testName = DEFAULT_SUREFIRE_TEST_NAME;

        private String suiteName = DEFAULT_SUREFIRE_SUITE_NAME;
    }

    private static class SuiteAndNamedTests
    {
        private XmlSuite xmlSuite = new XmlSuite();

        private Map<String, XmlTest> testNameToTest = new HashMap<String, XmlTest>();
    }

    private static void addSelector( XmlTest xmlTest, XmlMethodSelector selector )
    {
        if ( selector != null )
        {
            xmlTest.getMethodSelectors().add( selector );
        }
    }

    private static XmlMethodSelector getMethodNameFilteringSelector( String methodNamePattern )
        throws TestSetFailedException
    {
        if ( StringUtils.isBlank( methodNamePattern ) )
        {
            return null;
        }

        // the class is available in the testClassPath
        String clazzName = "org.apache.maven.surefire.testng.utils.MethodSelector";
        try
        {
            Class clazz = Class.forName( clazzName );

            Method method = clazz.getMethod( "setMethodName", new Class[] { String.class } );
            method.invoke( null, methodNamePattern );
        }
        catch ( ClassNotFoundException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( SecurityException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( NoSuchMethodException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( IllegalArgumentException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( IllegalAccessException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }

        XmlMethodSelector xms = new XmlMethodSelector();

        xms.setName( clazzName );
        // looks to need a high value
        xms.setPriority( 10000 );

        return xms;
    }

    private static XmlMethodSelector getGroupMatchingSelector( Map options )
        throws TestSetFailedException
    {
        String groups = (String) options.get( ProviderParameterNames.TESTNG_GROUPS_PROP );
        String excludedGroups = (String) options.get( ProviderParameterNames.TESTNG_EXCLUDEDGROUPS_PROP );

        if ( groups == null && excludedGroups == null )
        {
            return null;
        }

        // the class is available in the testClassPath
        String clazzName = "org.apache.maven.surefire.testng.utils.GroupMatcherMethodSelector";
        try
        {
            Class clazz = Class.forName( clazzName );

            // HORRIBLE hack, but TNG doesn't allow us to setup a method selector instance directly.
            Method method = clazz.getMethod( "setGroups", new Class[] { String.class, String.class } );
            method.invoke( null, groups, excludedGroups );
        }
        catch ( ClassNotFoundException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( SecurityException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( NoSuchMethodException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( IllegalArgumentException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( IllegalAccessException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new TestSetFailedException( e.getMessage(), e );
        }

        XmlMethodSelector xms = new XmlMethodSelector();

        xms.setName( clazzName );
        // looks to need a high value
        xms.setPriority( 9999 );

        return xms;
    }

    public static void run( List<String> suiteFiles, String testSourceDirectory, Map options,
                            RunListener reportManager, TestNgTestSuite suite, File reportsDirectory )
        throws TestSetFailedException
    {
        TestNG testng = new TestNG( true );
        Configurator configurator = getConfigurator( (String) options.get( "testng.configurator" ) );
        configurator.configure( testng, options );
        postConfigure( testng, testSourceDirectory, reportManager, suite, reportsDirectory );
        testng.setTestSuites( suiteFiles );
        testng.run();
    }

    private static Configurator getConfigurator( String className )
    {
        try
        {
            return (Configurator) Class.forName( className ).newInstance();
        }
        catch ( InstantiationException e )
        {
            throw new RuntimeException( e );
        }
        catch ( IllegalAccessException e )
        {
            throw new RuntimeException( e );
        }
        catch ( ClassNotFoundException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static void postConfigure( TestNG testNG, String sourcePath, RunListener reportManager,
                                       TestNgTestSuite suite, File reportsDirectory )
        throws TestSetFailedException
    {
        // turn off all TestNG output
        testNG.setVerbose( 0 );

        TestNGReporter reporter = createTestNGReporter( reportManager, suite );
        testNG.addListener( (Object) reporter );

        // FIXME: use classifier to decide if we need to pass along the source dir (onyl for JDK14)
        if ( sourcePath != null )
        {
            testNG.setSourcePath( sourcePath );
        }

        testNG.setOutputDirectory( reportsDirectory.getAbsolutePath() );
    }

    // If we have access to IResultListener, return a ConfigurationAwareTestNGReporter
    // But don't cause NoClassDefFoundErrors if it isn't available; just return a regular TestNGReporter instead
    private static TestNGReporter createTestNGReporter( RunListener reportManager, TestNgTestSuite suite )
    {
        try
        {
            Class.forName( "org.testng.internal.IResultListener" );
            Class c = Class.forName( "org.apache.maven.surefire.testng.ConfigurationAwareTestNGReporter" );
            try
            {
                Constructor ctor = c.getConstructor( new Class[] { RunListener.class, TestNgTestSuite.class } );
                return (TestNGReporter) ctor.newInstance( reportManager, suite );
            }
            catch ( Exception e )
            {
                throw new RuntimeException( "Bug in ConfigurationAwareTestNGReporter", e );
            }
        }
        catch ( ClassNotFoundException e )
        {
            return new TestNGReporter( reportManager );
        }
    }

}
