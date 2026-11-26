/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.fragments;

import org.thoughtcrime.securesms.BuildConfig;

public final class RegistrationConstants {

  private RegistrationConstants() {
  }

  public static final String TERMS_AND_CONDITIONS_URL = BuildConfig.TERMS_AND_PRIVACY_URL;
  public static final String SIGNAL_CAPTCHA_SCHEME    = "signalcaptcha://";

}
