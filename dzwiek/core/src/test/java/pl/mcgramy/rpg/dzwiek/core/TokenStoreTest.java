package pl.mcgramy.rpg.dzwiek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final TokenStore store = new TokenStore(300_000, 1_800_000, now::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void linkDzialaRaz() {
        String token = store.issueLink(player);
        assertEquals(TokenStore.TOKEN_LENGTH, token.length());
        assertEquals(player, store.consume(token, TokenStore.Kind.LINK));
        assertNull(store.consume(token, TokenStore.Kind.LINK), "drugie użycie musi być odrzucone");
    }

    @Test
    void linkWygasa() {
        String token = store.issueLink(player);
        now.addAndGet(300_001);
        assertNull(store.consume(token, TokenStore.Kind.LINK));
        assertEquals(0, store.size(), "przeterminowany token też jest usuwany");
    }

    @Test
    void nowyLinkUniewaznniaStary() {
        String first = store.issueLink(player);
        String second = store.issueLink(player);
        assertNull(store.consume(first, TokenStore.Kind.LINK));
        assertEquals(player, store.consume(second, TokenStore.Kind.LINK));
    }

    @Test
    void zlyRodzajZuzywaToken() {
        String token = store.issueLink(player);
        assertNull(store.consume(token, TokenStore.Kind.RESUME));
        assertNull(store.consume(token, TokenStore.Kind.LINK));
    }

    @Test
    void odrzucaSmieci() {
        assertNull(store.consume(null, TokenStore.Kind.LINK));
        assertNull(store.consume("", TokenStore.Kind.LINK));
        assertNull(store.consume("a".repeat(5000), TokenStore.Kind.LINK));
        assertNull(store.consume("../../etc/passwd/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", TokenStore.Kind.LINK));
    }

    @Test
    void wznowienieWazneDopokiPolaczony() {
        String resume = store.issueResume(player);
        now.addAndGet(10L * 3_600_000); // 10 h połączenia
        store.cleanup();
        assertEquals(player, store.consume(resume, TokenStore.Kind.RESUME));
    }

    @Test
    void wznowienieWygasaPoRozlaczeniu() {
        String resume = store.issueResume(player);
        store.startResumeCountdown(player);
        now.addAndGet(1_800_001);
        assertNull(store.consume(resume, TokenStore.Kind.RESUME));
    }

    @Test
    void odliczanieNieDotyczyInnychGraczy() {
        UUID other = UUID.randomUUID();
        String mine = store.issueResume(player);
        String theirs = store.issueResume(other);
        store.startResumeCountdown(other);
        now.addAndGet(1_800_001);
        assertEquals(player, store.consume(mine, TokenStore.Kind.RESUME));
        assertNull(store.consume(theirs, TokenStore.Kind.RESUME));
    }

    @Test
    void wylogowanieUsuwaWszystko() {
        String link = store.issueLink(player);
        String resume = store.issueResume(player);
        store.revokeAll(player);
        assertNull(store.consume(link, TokenStore.Kind.LINK));
        assertNull(store.consume(resume, TokenStore.Kind.RESUME));
    }

    @Test
    void zapisIOdczytPoRestarcie(@TempDir Path dir) throws Exception {
        String resume = store.issueResume(player);
        String link = store.issueLink(player);
        Path file = dir.resolve("sesje.dat");
        store.saveResume(file);
        String content = Files.readString(file);
        assertFalse(content.contains(resume), "na dysku nie może być samego tokenu");
        assertFalse(content.contains(link));

        TokenStore after = new TokenStore(300_000, 1_800_000, now::get);
        assertEquals(1, after.loadResume(file));
        assertFalse(Files.exists(file), "plik jest jednorazowy");
        assertNull(after.consume(link, TokenStore.Kind.LINK), "linki nie przeżywają restartu");
        assertEquals(player, after.consume(resume, TokenStore.Kind.RESUME));
    }

    @Test
    void odczytOdrzucaZbytDlugaWaznosc(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sesje.dat");
        Files.writeString(file, "a".repeat(64) + ";" + player + ";" + Long.MAX_VALUE + "\nzepsute\n");
        assertEquals(0, store.loadResume(file));
    }
}
