/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.client.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.livy.annotations.Private;

/**
 * Base class with common functionality for type-safe configuration objects.
 */
@Private
public abstract class ClientConf<T extends ClientConf>
  implements Iterable<Map.Entry<String, String>> {

  protected static final Logger LOG = LoggerFactory.getLogger(ClientConf.class);

  public static interface ConfEntry {

    /**
     * @return The key in the configuration file.
     */
    String key();

    /**
     * @return The default value, which also defines the type of the config. Supported types:
     *         Boolean, Integer, Long, String. <code>null</code> maps to String.
     */
    Object dflt();

  }

  private static final Map<String, TimeUnit> TIME_SUFFIXES;

  public static final boolean TEST_MODE = Boolean.parseBoolean(System.getenv("LIVY_TEST"));

  static {
    TIME_SUFFIXES = new HashMap<>();
    TIME_SUFFIXES.put("us", TimeUnit.MICROSECONDS);
    TIME_SUFFIXES.put("ms", TimeUnit.MILLISECONDS);
    TIME_SUFFIXES.put("s", TimeUnit.SECONDS);
    TIME_SUFFIXES.put("m", TimeUnit.MINUTES);
    TIME_SUFFIXES.put("min", TimeUnit.MINUTES);
    TIME_SUFFIXES.put("h", TimeUnit.HOURS);
    TIME_SUFFIXES.put("d", TimeUnit.DAYS);
  }

  protected final ConcurrentMap<String, String> config;

  protected ClientConf(Properties config) {
    this.config = new ConcurrentHashMap<>();
    if (config != null) {
      for (String key : config.stringPropertyNames()) {
        logDeprecationWarning(key);
        this.config.put(key, config.getProperty(key));
      }
    }
  }

  public String get(String key) {
    String val = config.get(key);
    if (val != null) {
      return val;
    }
    DeprecatedConf depConf = getConfigsWithAlternatives().get(key);
    if (depConf != null) {
      return config.get(depConf.key());
    } else {
      return val;
    }
  }

  @SuppressWarnings("unchecked")
  public T set(String key, String value) {
    logDeprecationWarning(key);
    config.put(key, value);
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public T setIfMissing(String key, String value) {
    if (config.putIfAbsent(key, value) == null) {
      logDeprecationWarning(key);
    }
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public T setAll(ClientConf<?> other) {
    for (Map.Entry<String, String> e : other) {
      set(e.getKey(), e.getValue());
    }
    return (T) this;
  }

  public String get(ConfEntry e) {
    Object value = get(e, String.class);
    return (String) (value != null ? value : e.dflt());
  }

  public boolean getBoolean(ConfEntry e) {
    String val = get(e, Boolean.class);
    if (val != null) {
      return Boolean.parseBoolean(val);
    } else {
      return (Boolean) e.dflt();
    }
  }

  public int getInt(ConfEntry e) {
    String val = get(e, Integer.class);
    if (val != null) {
      return Integer.parseInt(val);
    } else {
      return (Integer) e.dflt();
    }
  }

  public long getLong(ConfEntry e) {
    String val = get(e, Long.class);
    if (val != null) {
      return Long.parseLong(val);
    } else {
      return (Long) e.dflt();
    }
  }

  public long getTimeAsMs(ConfEntry e) {
    String time = get(e, String.class);
    if (time == null) {
      check(e.dflt() != null,
              "ConfEntry %s doesn't have a default value, cannot convert to time value.", e.key());
      time = (String) e.dflt();
    }
    return getTimeAsMs(time);
  }

  public static long getTimeAsMs(String time) {
    check(time != null && !time.trim().isEmpty(), "Invalid time string: %s", time);
    Matcher m = Pattern.compile("(-?[0-9]+)([a-z]+)?").matcher(time.trim().toLowerCase());
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid time string: " + time);
    }

    long val = Long.parseLong(m.group(1));
    String suffix = m.group(2);

    if (suffix != null && !TIME_SUFFIXES.containsKey(suffix)) {
      throw new IllegalArgumentException("Invalid suffix: \"" + suffix + "\"");
    }

    if (val < 0L) {
      throw new IllegalArgumentException("Invalid value: " + val);
    }

    return TimeUnit.MILLISECONDS.convert(val,
            suffix != null ? TIME_SUFFIXES.get(suffix) : TimeUnit.MILLISECONDS);
  }

  @SuppressWarnings("unchecked")
  public T set(ConfEntry e, Object value) {
    check(typesMatch(value, e.dflt()), "Value doesn't match configuration entry type for %s.",
      e.key());
    if (value == null) {
      config.remove(e.key());
    } else {
      logDeprecationWarning(e.key());
      config.put(e.key(), value.toString());
    }
    return (T) this;
  }

  @Override
  public Iterator<Map.Entry<String, String>> iterator() {
    return config.entrySet().iterator();
  }

  private String get(ConfEntry e, Class<?> requestedType) {
    check(getType(e.dflt()).equals(requestedType), "Invalid type conversion requested for %s.",
      e.key());
    return this.get(e.key());
  }

  private boolean typesMatch(Object test, Object expected) {
    return test == null || getType(test).equals(getType(expected));
  }

  private Class<?> getType(Object o) {
    return (o != null) ? o.getClass() : String.class;
  }

  private static void check(boolean test, String message, Object... args) {
    if (!test) {
      throw new IllegalArgumentException(String.format(message, args));
    }
  }

  /** Logs a warning message if the given config key is deprecated. */
  private void logDeprecationWarning(String key) {
    ConfPair altConf = allAlternativeKeys().get(key);
    if (altConf != null) {
      LOG.warn("The configuration key " + key + " has been deprecated as of Livy "
        + altConf.depConf.version() + " and may be removed in the future. Please use the new key "
        + altConf.newKey + " instead.");
      return;
    }

    DeprecatedConf depConfs = getDeprecatedConfigs().get(key);
    if (depConfs != null) {
      LOG.warn("The configuration key " + depConfs.key() + " has been deprecated as of Livy "
        + depConfs.version() + " and may be removed in the future. "
        + depConfs.deprecationMessage());
    }
  }

  /**
   * @return A Map from a valid key to a DeprecatedConf with the deprecated key.
   */
  protected abstract Map<String, DeprecatedConf> getConfigsWithAlternatives();

  /**
   * @return A Map from a deprecated key to a DeprecatedConf with the same key.
   */
  protected abstract Map<String, DeprecatedConf> getDeprecatedConfigs();

  private static class ConfPair {
    final String newKey;
    final DeprecatedConf depConf;

    ConfPair(String key, DeprecatedConf conf) {
      this.newKey = key;
      this.depConf = conf;
    }
  }
  private volatile Map<String, ConfPair> altToNewKeyMap = null;

  private Map<String, ConfPair> allAlternativeKeys() {
    if (altToNewKeyMap == null) {
      synchronized (this) {
        if (altToNewKeyMap == null) {
          Map<String, ConfPair> configs = new HashMap<>();
          for (String e : getConfigsWithAlternatives().keySet()) {
            DeprecatedConf depConf = getConfigsWithAlternatives().get(e);
            configs.put(depConf.key(), new ConfPair(e, depConf));
          }
          altToNewKeyMap = Collections.unmodifiableMap(configs);
        }
      }
    }
    return altToNewKeyMap;
  }

  public static interface DeprecatedConf {

    /**
     * @return The key in the configuration file.
     */
    String key();

    /**
     * @return The Livy version in which the key was deprecated.
     */
    String version();

    /**
     * @return Message to include in the deprecation warning for configs without alternatives
     */
    String deprecationMessage();
  }

}
