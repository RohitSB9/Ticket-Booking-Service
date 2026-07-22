package com.rohit.reservationservice.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// Distributed mutex per seat: SETNX seat:{id} -> ownerToken, TTL as a
// crash safety net (a caller that dies mid-reservation stops holding the
// lock forever). This is what closes the race that step 2 left open: two
// concurrent createReservation calls for the same seat now serialize on
// this lock instead of both racing Event Service's check-then-act.
@Component
public class SeatLockService {

    private static final String KEY_PREFIX = "seat:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    // Compare-and-delete so a caller can only release a lock it still
    // owns — without this, a caller whose TTL already expired (and whose
    // key some other owner has since acquired) would delete that other
    // owner's lock out from under it.
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public SeatLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryLock(UUID seatId, String ownerToken) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key(seatId), ownerToken, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(UUID seatId, String ownerToken) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(key(seatId)), ownerToken);
    }

    private String key(UUID seatId) {
        return KEY_PREFIX + seatId;
    }
}
