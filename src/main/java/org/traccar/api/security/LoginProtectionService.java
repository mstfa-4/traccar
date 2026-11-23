/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Protects the login endpoint against brute force and denial of service attacks.
 *
 * - Counts failed login attempts per account and per source IP address.
 * - After "login.maxAttempts" (default 5) failures the account is blocked permanently in the
 *   database (only an administrator can unblock it) and the source IP address is blocked for
 *   "login.ipBlockMinutes" (default 15 minutes).
 * - Limits the number of login requests per minute from a single IP address to
 *   "login.rateLimit" (default 30) to slow down high volume (DDoS) attacks.
 */
@Singleton
public class LoginProtectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginProtectionService.class);

    private static final long RATE_WINDOW_MILLIS = 60 * 1000;

    private final Storage storage;

    private final int maxAttempts;
    private final int ipBlockMinutes;
    private final int rateLimit;

    private final Map<String, Integer> accountFailures = new ConcurrentHashMap<>();
    private final Map<String, Integer> ipFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedIps = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> requestTimes = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor;

    @Inject
    public LoginProtectionService(Config config, Storage storage) {
        this.storage = storage;
        this.maxAttempts = config.getInteger(Keys.LOGIN_MAX_ATTEMPTS);
        this.ipBlockMinutes = config.getInteger(Keys.LOGIN_IP_BLOCK_MINUTES);
        this.rateLimit = config.getInteger(Keys.LOGIN_RATE_LIMIT);
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "login-protection-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Registers a login request from the given IP address and checks the per-IP rate limit.
     * @return false if the request should be rejected because the rate limit is exceeded.
     */
    public boolean checkRateLimit(String ip) {
        if (ip == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = requestTimes.computeIfAbsent(ip, key -> new ConcurrentLinkedDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() + RATE_WINDOW_MILLIS < now) {
                times.pollFirst();
            }
            if (rateLimit > 0 && times.size() >= rateLimit) {
                return false;
            }
            times.addLast(now);
        }
        return true;
    }

    /**
     * @return true if the given IP address is currently blocked due to repeated failed logins.
     */
    public boolean isIpBlocked(String ip) {
        if (ip == null) {
            return false;
        }
        Long blockedUntil = blockedIps.get(ip);
        if (blockedUntil != null) {
            if (blockedUntil > System.currentTimeMillis()) {
                return true;
            }
            blockedIps.remove(ip, blockedUntil);
        }
        return false;
    }

    /**
     * Records a failed login attempt for the given account (email / login) and IP address.
     * When the number of failures reaches the configured maximum, the account is blocked
     * in the database and the IP address is blocked temporarily.
     */
    public void recordFailure(String email, String ip) {
        if (email != null && !email.isEmpty()) {
            String key = normalize(email);
            int failures = accountFailures.merge(key, 1, Integer::sum);
            if (failures >= maxAttempts) {
                accountFailures.remove(key);
                blockAccount(key);
            }
        }
        if (ip != null) {
            int failures = ipFailures.merge(ip, 1, Integer::sum);
            if (failures >= maxAttempts) {
                ipFailures.remove(ip);
                blockedIps.put(ip, System.currentTimeMillis() + ipBlockMinutes * 60 * 1000L);
                LOGGER.info("IP address {} is blocked for {} minutes after {} failed login attempts",
                        ip, ipBlockMinutes, failures);
            }
        }
    }

    /**
     * Clears failure counters, e.g. after a successful login or when an administrator unblocks an account.
     */
    public void reset(String email, String ip) {
        if (email != null && !email.isEmpty()) {
            accountFailures.remove(normalize(email));
        }
        if (ip != null) {
            ipFailures.remove(ip);
        }
    }

    /**
     * Clears failure counters for the given account only.
     */
    public void resetAccount(String email) {
        if (email != null && !email.isEmpty()) {
            accountFailures.remove(normalize(email));
        }
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void blockAccount(String email) {
        try {
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(),
                    new Condition.Or(
                            new Condition.Equals("email", email),
                            new Condition.Equals("login", email))));
            // administrator accounts are never blocked automatically to avoid locking out the last admin
            if (user != null && !user.getBlocked() && !user.getAdministrator()) {
                user.setBlocked(true);
                storage.updateObject(user, new Request(
                        new Columns.Include("blocked"),
                        new Condition.Equals("id", user.getId())));
                LOGGER.info("Account {} is blocked after {} failed login attempts", email, maxAttempts);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to block account {}", email, e);
        }
    }

    private void cleanup() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : blockedIps.entrySet()) {
                if (entry.getValue() <= now) {
                    blockedIps.remove(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, Deque<Long>> entry : requestTimes.entrySet()) {
                Deque<Long> times = entry.getValue();
                synchronized (times) {
                    while (!times.isEmpty() && times.peekFirst() + RATE_WINDOW_MILLIS < now) {
                        times.pollFirst();
                    }
                    if (times.isEmpty()) {
                        requestTimes.remove(entry.getKey(), times);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Login protection cleanup failed", e);
        }
    }

}
