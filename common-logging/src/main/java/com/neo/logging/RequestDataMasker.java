package com.neo.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RequestDataMasker {

  /** Compared case-insensitively, ignoring '-' and '_'. */
  static final Set<String> DEFAULT_FIELDS =
      Set.of(
          "password",
          "passwd",
          "pwd",
          "newpassword",
          "oldpassword",
          "confirmpassword",
          "secret",
          "clientsecret",
          "token",
          "accesstoken",
          "refreshtoken",
          "idtoken",
          "sessionstate",
          "authorization",
          "cookie",
          "credentials",
          "apikey",
          "xgatewaysecret",
          "otp",
          "pin",
          "cvv",
          "cvc",
          "cardnumber",
          "iban",
          "email",
          "phone",
          "phonenumber",
          "mobile");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Set<String> fields;
  private final int maxChars;
  private final Pattern looseKeyValue;

  public RequestDataMasker(RequestLoggingProperties properties) {
    Set<String> all = new HashSet<>(DEFAULT_FIELDS);
    properties.maskedFields().forEach(f -> all.add(normalize(f)));
    this.fields = Set.copyOf(all);
    this.maxChars = properties.maxBodyChars();
    // Fallback for invalid JSON; matches access_token, access-token and accesstoken
    String names =
        fields.stream()
            .sorted()
            .map(
                n ->
                    n.chars()
                        .mapToObj(c -> Pattern.quote(String.valueOf((char) c)))
                        .collect(Collectors.joining("[-_]?")))
            .collect(Collectors.joining("|"));
    this.looseKeyValue =
        Pattern.compile("(?i)(\"?\\b(?:" + names + ")\\b\"?\\s*[=:]\\s*\"?)([^\"&,\\s}\\]]*)");
  }

  public boolean isSensitive(String name) {
    return name != null && fields.contains(normalize(name));
  }

  public String maskQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (String pair : rawQuery.split("&")) {
      if (out.length() > 0) {
        out.append('&');
      }
      int eq = pair.indexOf('=');
      String name = eq < 0 ? pair : pair.substring(0, eq);
      if (eq >= 0 && isSensitive(URLDecoder.decode(name, StandardCharsets.UTF_8))) {
        out.append(name).append('=').append(SensitiveDataMasker.MASK);
      } else {
        out.append(SensitiveDataMasker.mask(pair));
      }
    }
    return truncate(out.toString(), false);
  }

  public String maskBody(String body, String contentType, boolean captureTruncated) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String masked;
    if (type.contains("json")) {
      masked = maskJson(body);
    } else if (type.startsWith("application/x-www-form-urlencoded")) {
      masked = maskQuery(body);
    } else {
      masked = maskLoose(body);
    }
    return truncate(masked, captureTruncated);
  }

  public static boolean isLoggableContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String type = contentType.toLowerCase(Locale.ROOT);
    return type.contains("json")
        || type.startsWith("text/")
        || type.startsWith("application/x-www-form-urlencoded")
        || type.contains("xml");
  }

  /** Never streams (SSE, NDJSON), which may never end. */
  public static boolean isLoggableResponseContentType(String contentType) {
    if (!isLoggableContentType(contentType)) {
      return false;
    }
    String type = contentType.toLowerCase(Locale.ROOT);
    return !type.startsWith("text/event-stream")
        && !type.contains("ndjson")
        && !type.contains("stream+json");
  }

  public static boolean isEncoded(String contentEncoding) {
    return contentEncoding != null
        && !contentEncoding.isBlank()
        && !contentEncoding.trim().equalsIgnoreCase("identity");
  }

  private String maskJson(String body) {
    try {
      JsonNode tree = MAPPER.readTree(body);
      maskNode(tree);
      return MAPPER.writeValueAsString(tree);
    } catch (Exception notCompleteJson) {
      return maskLoose(body);
    }
  }

  private void maskNode(JsonNode node) {
    if (node instanceof ObjectNode object) {
      Iterator<Map.Entry<String, JsonNode>> it = object.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        if (isSensitive(e.getKey())) {
          e.setValue(TextNode.valueOf(SensitiveDataMasker.MASK));
        } else if (e.getValue().isTextual()) {
          e.setValue(TextNode.valueOf(SensitiveDataMasker.mask(e.getValue().asText())));
        } else {
          maskNode(e.getValue());
        }
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        JsonNode child = array.get(i);
        if (child.isTextual()) {
          array.set(i, TextNode.valueOf(SensitiveDataMasker.mask(child.asText())));
        } else {
          maskNode(child);
        }
      }
    }
  }

  private String maskLoose(String text) {
    Matcher m = looseKeyValue.matcher(SensitiveDataMasker.mask(text));
    return m.replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + SensitiveDataMasker.MASK);
  }

  private String truncate(String text, boolean alreadyCut) {
    if (text.length() > maxChars) {
      return text.substring(0, maxChars) + "...(truncated)";
    }
    return alreadyCut ? text + "...(truncated)" : text;
  }

  private static String normalize(String name) {
    return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
  }
}
