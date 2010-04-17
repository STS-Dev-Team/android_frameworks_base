/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

/**
 * This test exercises the
 * {@link com.android.server.AccessibilityManagerService} by mocking the
 * {@link android.view.accessibility.AccessibilityManager} which talks to to the
 * service. The service itself is interacting with the platform. Note: Testing
 * the service in full isolation would require significant amount of work for
 * mocking all system interactions. It would also require a lot of mocking code.
 */
public class AccessibilityManagerServiceTest extends AndroidTestCase {

    /**
     * Timeout required for pending Binder calls or event processing to
     * complete.
     */
    private static final long TIMEOUT_BINDER_CALL = 100;

    /**
     * Timeout used for testing that a service is notified only upon a
     * notification timeout.
     */
    private static final long TIMEOUT_TEST_NOTIFICATION_TIMEOUT = 300;

    /**
     * The package name.
     */
    private static String sPackageName;

    /**
     * The interface used to talk to the tested service.
     */
    private IAccessibilityManager mManagerService;

    @Override
    public void setContext(Context context) {
        super.setContext(context);
        if (sPackageName == null) {
            sPackageName = context.getPackageName();
        }
    }

    /**
     * Creates a new instance.
     */
    public AccessibilityManagerServiceTest() {
        IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
        mManagerService = IAccessibilityManager.Stub.asInterface(iBinder);
    }

    @LargeTest
    public void testAddClient_AccessibilityDisabledThenEnabled() throws Exception {
        // make sure accessibility is disabled
        ensureAccessibilityEnabled(mContext, false);

        // create a client mock instance
        MyMockAccessibilityManagerClient mockClient = new MyMockAccessibilityManagerClient();

        // invoke the method under test
        boolean enabledAccessibilityDisabled = mManagerService.addClient(mockClient);

        // check expected result
        assertFalse("The client must be disabled since accessibility is disabled.",
                enabledAccessibilityDisabled);

        // enable accessibility
        ensureAccessibilityEnabled(mContext, true);

        // invoke the method under test
        boolean enabledAccessibilityEnabled = mManagerService.addClient(mockClient);

        // check expected result
        assertTrue("The client must be enabled since accessibility is enabled.",
                enabledAccessibilityEnabled);
    }

    @LargeTest
    public void testAddClient_AccessibilityEnabledThenDisabled() throws Exception {
        // enable accessibility before registering the client
        ensureAccessibilityEnabled(mContext, true);

        // create a client mock instance
        MyMockAccessibilityManagerClient mockClient = new MyMockAccessibilityManagerClient();

        // invoke the method under test
        boolean enabledAccessibilityEnabled = mManagerService.addClient(mockClient);

        // check expected result
        assertTrue("The client must be enabled since accessibility is enabled.",
                enabledAccessibilityEnabled);

        // disable accessibility
        ensureAccessibilityEnabled(mContext, false);

        // invoke the method under test
        boolean enabledAccessibilityDisabled = mManagerService.addClient(mockClient);

        // check expected result
        assertFalse("The client must be disabled since accessibility is disabled.",
                enabledAccessibilityDisabled);
    }

    @LargeTest
    public void testGetAccessibilityServicesList() throws Exception {
        boolean firstMockServiceInstalled = false;
        boolean secondMockServiceInstalled = false;

        String packageName = getContext().getPackageName();
        String firstMockServiceClassName = MyFirstMockAccessibilityService.class.getName();
        String secondMockServiceClassName = MySecondMockAccessibilityService.class.getName();

        // look for the two mock services
        for (ServiceInfo serviceInfo : mManagerService.getAccessibilityServiceList()) {
            if (packageName.equals(serviceInfo.packageName)) {
                if (firstMockServiceClassName.equals(serviceInfo.name)) {
                    firstMockServiceInstalled = true;
                } else if (secondMockServiceClassName.equals(serviceInfo.name)) {
                    secondMockServiceInstalled = true;
                }
            }
        }

        // check expected result
        assertTrue("First mock service must be installed", firstMockServiceInstalled);
        assertTrue("Second mock service must be installed", secondMockServiceInstalled);
    }

    @LargeTest
    public void testSendAccessibilityEvent_OneService_MatchingPackageAndEventType()
            throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility service
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName);

        // configure the mock service
        MockAccessibilityService service = MyFirstMockAccessibilityService.sInstance;
        service.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // wait for the binder call to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);

        // set expectations
        service.expectEvent(sentEvent);
        service.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(service);
    }

    @LargeTest
    public void testSendAccessibilityEvent_OneService_NotMatchingPackage() throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility service
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName);

        // configure the mock service
        MockAccessibilityService service = MyFirstMockAccessibilityService.sInstance;
        service.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // wait for the binder call to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);
        sentEvent.setPackageName("no.service.registered.for.this.package");

        // set expectations
        service.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(service);
    }

    @LargeTest
    public void testSendAccessibilityEvent_OneService_NotMatchingEventType() throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility service
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName);

        // configure the mock service
        MockAccessibilityService service = MyFirstMockAccessibilityService.sInstance;
        service.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // wait for the binder call to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);
        sentEvent.setEventType(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);

        // set expectations
        service.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(service);
    }

    @LargeTest
    public void testSendAccessibilityEvent_OneService_NotifivationAfterTimeout() throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility service
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName);

        // configure the mock service
        MockAccessibilityService service = MyFirstMockAccessibilityService.sInstance;
        AccessibilityServiceInfo info = MockAccessibilityService.createDefaultInfo();
        info.notificationTimeout = TIMEOUT_TEST_NOTIFICATION_TIMEOUT;
        service.setServiceInfo(info);

        // wait for the binder call to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate the first event to be sent
        AccessibilityEvent firstEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(firstEvent);

        // create and populate the second event to be sent
        AccessibilityEvent secondEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(secondEvent);

        // set expectations
        service.expectEvent(secondEvent);
        service.replay();

        // send the events
        mManagerService.sendAccessibilityEvent(firstEvent);
        mManagerService.sendAccessibilityEvent(secondEvent);

        // wait for #sendAccessibilityEvent to reach the backing service
        Thread.sleep(TIMEOUT_BINDER_CALL);

        try {
            service.verify();
            fail("No events must be dispatched before the expiration of the notification timeout.");
        } catch (IllegalStateException ise) {
            /* expected */
        }

        // wait for the configured notification timeout to expire
        Thread.sleep(TIMEOUT_TEST_NOTIFICATION_TIMEOUT);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(service);
    }

    @LargeTest
    public void testSendAccessibilityEvent_TwoServices_MatchingPackageAndEventType_DiffFeedback()
            throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility services
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName,
                MySecondMockAccessibilityService.sComponentName);

        // configure the first mock service
        MockAccessibilityService firstService = MyFirstMockAccessibilityService.sInstance;
        AccessibilityServiceInfo firstInfo = MockAccessibilityService.createDefaultInfo();
        firstInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_AUDIBLE;
        firstService.setServiceInfo(firstInfo);

        // configure the second mock service
        MockAccessibilityService secondService = MySecondMockAccessibilityService.sInstance;
        AccessibilityServiceInfo secondInfo = MockAccessibilityService.createDefaultInfo();
        secondInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_HAPTIC;
        secondService.setServiceInfo(secondInfo);

        // wait for the binder calls to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);

        // set expectations for the first mock service
        firstService.expectEvent(sentEvent);
        firstService.replay();

        // set expectations for the second mock service
        secondService.expectEvent(sentEvent);
        secondService.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(firstService);
        assertMockServiceVerifiedWithinTimeout(secondService);
    }

    @LargeTest
    public void testSendAccessibilityEvent_TwoServices_MatchingPackageAndEventType()
            throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility services
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName,
                MySecondMockAccessibilityService.sComponentName);

        // configure the first mock service
        MockAccessibilityService firstService = MyFirstMockAccessibilityService.sInstance;
        firstService.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // configure the second mock service
        MockAccessibilityService secondService = MySecondMockAccessibilityService.sInstance;
        secondService.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // wait for the binder calls to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);

        // set expectations for the first mock service
        firstService.expectEvent(sentEvent);
        firstService.replay();

        // set expectations for the second mock service
        secondService.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(firstService);
        assertMockServiceVerifiedWithinTimeout(secondService);
    }

    @LargeTest
    public void testSendAccessibilityEvent_TwoServices_MatchingPackageAndEventType_OneDefault()
            throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility services
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName,
                MySecondMockAccessibilityService.sComponentName);

        // configure the first mock service
        MockAccessibilityService firstService = MyFirstMockAccessibilityService.sInstance;
        AccessibilityServiceInfo firstInfo = MyFirstMockAccessibilityService.createDefaultInfo();
        firstInfo.flags = AccessibilityServiceInfo.DEFAULT;
        firstService.setServiceInfo(firstInfo);

        // configure the second mock service
        MockAccessibilityService secondService = MySecondMockAccessibilityService.sInstance;
        secondService.setServiceInfo(MySecondMockAccessibilityService.createDefaultInfo());

        // wait for the binder calls to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);

        // set expectations for the first mock service
        firstService.replay();

        // set expectations for the second mock service
        secondService.expectEvent(sentEvent);
        secondService.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(firstService);
        assertMockServiceVerifiedWithinTimeout(secondService);
    }

    @LargeTest
    public void testSendAccessibilityEvent_TwoServices_MatchingPackageAndEventType_TwoDefault()
            throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility services
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName,
                MySecondMockAccessibilityService.sComponentName);

        // configure the first mock service
        MockAccessibilityService firstService = MyFirstMockAccessibilityService.sInstance;
        AccessibilityServiceInfo firstInfo = MyFirstMockAccessibilityService.createDefaultInfo();
        firstInfo.flags = AccessibilityServiceInfo.DEFAULT;
        firstService.setServiceInfo(firstInfo);

        // configure the second mock service
        MockAccessibilityService secondService = MySecondMockAccessibilityService.sInstance;
        AccessibilityServiceInfo secondInfo = MyFirstMockAccessibilityService.createDefaultInfo();
        secondInfo.flags = AccessibilityServiceInfo.DEFAULT;
        secondService.setServiceInfo(firstInfo);

        // wait for the binder calls to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // create and populate an event to be sent
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();
        fullyPopulateDefaultAccessibilityEvent(sentEvent);

        // set expectations for the first mock service
        firstService.expectEvent(sentEvent);
        firstService.replay();

        // set expectations for the second mock service
        secondService.replay();

        // send the event
        mManagerService.sendAccessibilityEvent(sentEvent);

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(firstService);
        assertMockServiceVerifiedWithinTimeout(secondService);
    }

    @LargeTest
    public void testInterrupt() throws Exception {
        // set the accessibility setting value
        ensureAccessibilityEnabled(mContext, true);

        // enable the mock accessibility services
        ensureOnlyMockServicesEnabled(mContext, MyFirstMockAccessibilityService.sComponentName,
                MySecondMockAccessibilityService.sComponentName);

        // configure the first mock service
        MockAccessibilityService firstService = MyFirstMockAccessibilityService.sInstance;
        firstService.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // configure the second mock service
        MockAccessibilityService secondService = MySecondMockAccessibilityService.sInstance;
        secondService.setServiceInfo(MockAccessibilityService.createDefaultInfo());

        // wait for the binder calls to #setService to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // set expectations for the first mock service
        firstService.expectInterrupt();
        firstService.replay();

        // set expectations for the second mock service
        secondService.expectInterrupt();
        secondService.replay();

        // call the method under test
        mManagerService.interrupt();

        // verify if all expected methods have been called
        assertMockServiceVerifiedWithinTimeout(firstService);
        assertMockServiceVerifiedWithinTimeout(secondService);
    }

    /**
     * Fully populates the {@link AccessibilityEvent} to marshal.
     *
     * @param sentEvent The event to populate.
     */
    private void fullyPopulateDefaultAccessibilityEvent(AccessibilityEvent sentEvent) {
        sentEvent.setAddedCount(1);
        sentEvent.setBeforeText("BeforeText");
        sentEvent.setChecked(true);
        sentEvent.setClassName("foo.bar.baz.Class");
        sentEvent.setContentDescription("ContentDescription");
        sentEvent.setCurrentItemIndex(1);
        sentEvent.setEnabled(true);
        sentEvent.setEventType(AccessibilityEvent.TYPE_VIEW_CLICKED);
        sentEvent.setEventTime(1000);
        sentEvent.setFromIndex(1);
        sentEvent.setFullScreen(true);
        sentEvent.setItemCount(1);
        sentEvent.setPackageName("foo.bar.baz");
        sentEvent.setParcelableData(Message.obtain(null, 1, null));
        sentEvent.setPassword(true);
        sentEvent.setRemovedCount(1);
    }

    /**
     * This class is a mock {@link IAccessibilityManagerClient}.
     */
    public class MyMockAccessibilityManagerClient extends IAccessibilityManagerClient.Stub {
        boolean mIsEnabled;

        public void setEnabled(boolean enabled) {
            mIsEnabled = enabled;
        }
    }

    /**
     * Ensures accessibility is in a given state by writing the state to the
     * settings and waiting until the accessibility manager service pick it up.
     *
     * @param context A context handle to access the settings.
     * @param enabled The accessibility state to write to the settings.
     * @throws Exception If any error occurs.
     */
    private void ensureAccessibilityEnabled(Context context, boolean enabled) throws Exception {
        boolean isEnabled = (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 ? true : false);

        if (isEnabled == enabled) {
            return;
        }

        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED,
                enabled ? 1 : 0);

        // wait the accessibility manager service to pick the change up
        Thread.sleep(TIMEOUT_BINDER_CALL);
    }

    /**
     * Ensures the only {@link MockAccessibilityService}s with given component
     * names are enabled by writing to the system settings and waiting until the
     * accessibility manager service picks that up.
     *
     * @param context A context handle to access the settings.
     * @param componentNames The string representation of the
     *            {@link ComponentName}s to enable.
     * @throws Exception Exception If any error occurs.
     */
    private void ensureOnlyMockServicesEnabled(Context context, String... componentNames)
            throws Exception {
        String enabledServices = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        StringBuilder servicesToEnable = new StringBuilder();
        for (String componentName : componentNames) {
            servicesToEnable.append(componentName).append(":");
        }

        if (servicesToEnable.equals(enabledServices)) {
            return;
        }

        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesToEnable.toString());

        // wait the system to perform asynchronous processing
        Thread.sleep(TIMEOUT_BINDER_CALL);
    }

    /**
     * Asserts the the mock accessibility service has been successfully verified
     * (which is it has received the expected method calls with expected
     * arguments) within the {@link #TIMEOUT_BINDER_CALL}. The verified state is
     * checked by polling upon small intervals.
     *
     * @param service The service to verify.
     * @throws Exception If the verification has failed with exception after the
     *             {@link #TIMEOUT_BINDER_CALL}.
     */
    private void assertMockServiceVerifiedWithinTimeout(MockAccessibilityService service)
            throws Exception {
        Exception lastVerifyException = null;
        long beginTime = SystemClock.uptimeMillis();
        long pollTmeout = TIMEOUT_BINDER_CALL / 5;

        // poll until the timeout has elapsed
        while (SystemClock.uptimeMillis() - beginTime < TIMEOUT_BINDER_CALL) {
            // sleep first since immediate call will always fail
            try {
                Thread.sleep(pollTmeout);
            } catch (InterruptedException ie) {
                /* ignore */
            }
            // poll for verification and if this fails save the exception and
            // keep polling
            try {
                service.verify();
                // reset so it does not accept more events
                service.reset();
                return;
            } catch (Exception e) {
                lastVerifyException = e;
            }
        }

        // reset, we have already failed
        service.reset();

        // always not null
        throw lastVerifyException;
    }

    /**
     * This class is the first mock {@link AccessibilityService}.
     */
    public static class MyFirstMockAccessibilityService extends MockAccessibilityService {

        /**
         * The service {@link ComponentName} flattened as a string.
         */
        static final String sComponentName = new ComponentName(
                sPackageName,
                MyFirstMockAccessibilityService.class.getName()
                ).flattenToShortString();

        /**
         * Handle to the service instance.
         */
        static MyFirstMockAccessibilityService sInstance;

        /**
         * Creates a new instance.
         */
        public MyFirstMockAccessibilityService() {
            sInstance = this;
        }
    }

    /**
     * This class is the first mock {@link AccessibilityService}.
     */
    public static class MySecondMockAccessibilityService extends MockAccessibilityService {

        /**
         * The service {@link ComponentName} flattened as a string.
         */
        static final String sComponentName = new ComponentName(
                sPackageName,
                MySecondMockAccessibilityService.class.getName()
                ).flattenToShortString();

        /**
         * Handle to the service instance.
         */
        static MySecondMockAccessibilityService sInstance;

        /**
         * Creates a new instance.
         */
        public MySecondMockAccessibilityService() {
            sInstance = this;
        }
    }
}
