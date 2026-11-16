package pl.mcgramy.rpg.dzwiek.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MusicStackTest {

    @Test
    void wyzszyPriorytetWygrywaIPoZdjeciuWracaPoprzedni() {
        MusicStack stack = new MusicStack();
        assertTrue(stack.set("world", "dzicz", 0));
        assertTrue(stack.set("region", "miasto", 10));
        assertEquals("miasto", stack.current());
        assertTrue(stack.set("boss", "boss_walka", 100));
        assertFalse(stack.set("region", "las", 10), "zmiana pod bossem nie zmienia utworu");
        assertEquals("boss_walka", stack.current());
        assertTrue(stack.remove("boss"));
        assertEquals("las", stack.current());
    }

    @Test
    void ciszaJakoWarstwa() {
        MusicStack stack = new MusicStack();
        stack.set("world", "dzicz", 0);
        assertTrue(stack.set("region", null, 10));
        assertNull(stack.current());
        assertTrue(stack.remove("region"));
        assertEquals("dzicz", stack.current());
    }

    @Test
    void przyRemisieNajnowsza() {
        MusicStack stack = new MusicStack();
        stack.set("a", "x", 50);
        stack.set("b", "y", 50);
        assertEquals("y", stack.current());
        stack.remove("b");
        assertEquals("x", stack.current());
    }

    @Test
    void ponowneUstawienieTegoSamegoNicNieZmienia() {
        MusicStack stack = new MusicStack();
        stack.set("a", "x", 1);
        assertFalse(stack.set("a", "x", 1));
        assertFalse(stack.remove("brak"));
    }

    @Test
    void limiterOknaCzasowego() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(2, 1000, now::get);
        assertTrue(limiter.hit("ip"));
        assertTrue(limiter.hit("ip"));
        assertFalse(limiter.blocked("ip"));
        assertFalse(limiter.hit("ip"));
        assertTrue(limiter.blocked("ip"));
        assertFalse(limiter.blocked("inne"));
        now.addAndGet(1000);
        assertFalse(limiter.blocked("ip"));
        assertTrue(limiter.hit("ip"));
    }
}
