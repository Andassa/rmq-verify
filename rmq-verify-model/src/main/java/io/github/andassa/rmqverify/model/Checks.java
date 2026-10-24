package io.github.andassa.rmqverify.model;

import java.util.List;

/** Argument validation shared by the domain records. */
final class Checks {

  static final int MAX_NAME_LENGTH = 256;
  static final int MAX_TEXT_LENGTH = 4096;
  static final int MAX_LIST_SIZE = 64;

  private Checks() {}

  static void name(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (value.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(field + " exceeds " + MAX_NAME_LENGTH + " characters");
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw new IllegalArgumentException(field + " contains control characters");
      }
    }
  }

  static void text(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    if (value.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException(field + " exceeds " + MAX_TEXT_LENGTH + " characters");
    }
  }

  static void nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be >= 0 but was " + value);
    }
  }

  static void names(List<String> values, String field) {
    if (values == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    if (values.size() > MAX_LIST_SIZE) {
      throw new IllegalArgumentException(field + " has more than " + MAX_LIST_SIZE + " entries");
    }
    for (String value : values) {
      name(value, field + " entry");
    }
  }

  static <T> T notNull(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    return value;
  }
}
