/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.isis.viewer.wicket.ui.pages.login;

import de.agilecoders.wicket.core.markup.html.bootstrap.common.NotificationPanel;

import javax.inject.Inject;
import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.authroles.authentication.panel.SignInPanel;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.isis.applib.services.email.EmailService;
import org.apache.isis.applib.services.userreg.EmailNotificationService;
import org.apache.isis.applib.services.userreg.UserRegistrationService;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.viewer.wicket.model.models.PageType;
import org.apache.isis.viewer.wicket.ui.pages.PageClassRegistry;

/**
 * An extension of Wicket's default SignInPanel that provides
 * custom markup, based on Bootstrap, and uses
 * {@link de.agilecoders.wicket.core.markup.html.bootstrap.common.NotificationPanel}
 * to Bootstrap styled error messages
 */
public class IsisSignInPanel extends SignInPanel {

    private final boolean clearOriginalDestination;
    private final boolean passwordResetLink;

    /**
     * Constructor
     *
     * @param id
     *            the component id
     * @param rememberMe
     *            True if form should include a remember-me checkbox
     * @param passwordResetLink
     *            True if form should include the password reset link
     * @param continueToOriginalDestination
     *            A flag indicating whether to continue to the originally requested destination
     */
    public IsisSignInPanel(
            final String id,
            final boolean rememberMe,
            final boolean passwordResetLink,
            final boolean continueToOriginalDestination) {
        super(id, rememberMe);
        this.passwordResetLink = passwordResetLink;
        this.clearOriginalDestination = !continueToOriginalDestination;
    }

    @Override
    protected void onInitialize() {
        super.onInitialize();

        addPasswordResetLink("passwdResetLink");
        addSignUpLink("signUpLink");

        addNotificationPanel("feedback");
    }

    private void addPasswordResetLink(final String id) {
        final Class < ?extends Page> passwordResetPageClass = pageClassRegistry.getPageClass(PageType.PASSWORD_RESET);
        final BookmarkablePageLink<Void> passwordResetLink =
                new BookmarkablePageLink<>(id, passwordResetPageClass);

        setVisibilityAllowedBasedOnAvailableServices(passwordResetLink);

        // in addition, don't show password reset link has been suppressed.
        if(!this.passwordResetLink) {
            passwordResetLink.setVisibilityAllowed(false);
        }

        getSignInForm().addOrReplace(passwordResetLink);
    }

    private void addSignUpLink(final String id) {
        final Class<? extends Page> signUpPageClass = pageClassRegistry.getPageClass(PageType.SIGN_UP);
        final BookmarkablePageLink<Void> signUpLink = new BookmarkablePageLink<>(id, signUpPageClass);
        setVisibilityAllowedBasedOnAvailableServices(signUpLink);
        getSignInForm().addOrReplace(signUpLink);
    }

    private void addNotificationPanel(final String id) {
        addOrReplace(new NotificationPanel(id));
    }

    private void setVisibilityAllowedBasedOnAvailableServices(final Component component) {
        IsisContext.doInSession(new Runnable() {
            @Override
            public void run() {
                final UserRegistrationService userRegistrationService = lookupService(UserRegistrationService.class);
                final EmailNotificationService emailNotificationService = lookupService(EmailNotificationService.class);
                component.setVisibilityAllowed(userRegistrationService != null && emailNotificationService.isConfigured());
            }
        });
    }

    private MarkupContainer getSignInForm() {
        return (MarkupContainer) get("signInForm");
    }

    @Override
    protected void onSignInSucceeded() {

        if(clearOriginalDestination) {
            clearOriginalDestination();
        }
        super.onSignInSucceeded();
    }

    @Override
    protected void onSignInRemembered() {
        if(clearOriginalDestination) {
            clearOriginalDestination();
        }
        super.onSignInRemembered();
    }


    // //////////////////////////////////////

    private <T> T lookupService(final Class<T> serviceClass) {
        return IsisContext.getPersistenceSession().getServicesInjector().lookupService(serviceClass);
    }

    @Inject
    private PageClassRegistry pageClassRegistry;

    @Inject
    private EmailNotificationService emailNotificationService;

    @Inject
    private EmailService emailService;

}
