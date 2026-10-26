package com.miaa.cas.notifications;

import java.util.Map;

/** Delivery port for an approved e-mail provider. No production implementation ships until one is approved (C-16). */
public interface EmailSender {

    void send(String recipient, String template, Map<String, Object> model) throws Exception;
}
