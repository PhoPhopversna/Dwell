package com.neo.logging;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SensitiveDataMasker {

  public static final String MASK = "****";

  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "password",
          "passwd",
          "secret",
          "token",
          "accesstoken",
          "access_token",
          "refreshtoken",
          "refresh_token",
          "authorization",
          "cookie",
          "set-cookie",
          "clientsecret",
          "client_secret",
          "x-gateway-secret",
          "credentials",
          "apikey",
          "api_key");

  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");

  private static final Pattern AUTH_SCHEME =
      Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");

  private static final Pattern KEY_VALUE =
      Pattern.compile(
          "(?i)(\"?(?:password|passwd|secret|client_?secret|access_?token|refresh_?token|token|"
              + "authorization|api_?key|x-gateway-secret)\"?\\s*[=:]\\s*\"?)([^\",\\s)}\\]]+)");

  private SensitiveDataMasker() {}

  public static boolean isSensitiveKey(String key) {
    return key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
  }

  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = JWT.matcher(text).replaceAll(MASK);
    result = AUTH_SCHEME.matcher(result).replaceAll("$1 " + MASK);
    Matcher m = KEY_VALUE.matcher(result);
    return m.replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + MASK);
  }

  public static Object maskValue(String key, Object value) {
    if (isSensitiveKey(key)) {
      return MASK;
    }
    return value instanceof CharSequence cs ? mask(cs.toString()) : value;
  }
}
