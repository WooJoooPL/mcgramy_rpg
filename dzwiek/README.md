# McRPGDzwiek: muzyka i dźwięki gry w przeglądarce

Prototyp własnego mostu dźwięku dla trybu McRPG. Gracz wpisuje `/muzyka`, klika link na czacie,
a strona w przeglądarce gra muzykę regionu (z płynnymi przejściami) i efekty wysyłane z gry
(bossy, NPC, zdarzenia). Dzięki temu muzyka nie siedzi w paczce zasobów (cel paczki ≤ 10 MB).

Stan na 16.11.2026: **prototyp**. Rdzeń i strona przetestowane lokalnie (testy jednostkowe
i test w Chromium). **Nie testowano jeszcze na serwerze Minecraft** ani za nginx z HTTPS.

## Dlaczego nie OpenAudioMc

Licencja OpenAudioMc odsyła do regulaminu (account.openaudiomc.net/terms): wolno używać ich
gotowego klienta z ich backendem. Własny lub zmieniony klient wymaga pisemnej zgody autorów,
a podstawianie ich serwerów jest zabronione. Ten kod jest napisany od zera, bez ich kodu i protokołu.

## Jak to działa

```
 Gracz w grze                 serwer „mcgramy - MMORPG” (Paper 26.3)
 /muzyka  ───────────────▶  McRPGDzwiek: token jednorazowy (5 min) → link na czacie
                             regiony, warstwy muzyki, API, komendy
                             wbudowany serwer WebSocket :8790 (tylko 127.0.0.1 hosta)
                                        ▲
                                        │ ws (wewnątrz VPS)
 Przeglądarka                           │
 https://mcgramy.pl/muzyka/#t=TOKEN ──▶ nginx (TLS, obecny certyfikat) ── /muzyka/ws
   strona: Web Audio, przejścia, efekty, suwaki głośności
   pliki audio: https://mcgramy.pl/muzyka/audio/… (statyczne, nginx)
```

- **Jedna maszyna**: plugin sam jest serwerem WebSocket (biblioteka Java-WebSocket, licencja MIT,
  przeniesiona do `pl.mcgramy.rpg.dzwiek.lib`). Bez Node, bez Redisa, bez osobnej usługi.
- **HTTPS**: strona i `wss://` idą przez nginx na `mcgramy.pl/muzyka/`, więc nie trzeba nowej
  subdomeny ani certyfikatu. Przeglądarka i tak wymaga jednego kliknięcia „Włącz dźwięk”
  (zasady autoodtwarzania), także po odświeżeniu strony.
- **Formaty**: Opus w `.ogg` (Chrome, Firefox, Edge) i AAC w `.m4a` (Safari). Strona bierze
  pierwszy plik, który przeglądarka obsłuży.

## Bezpieczeństwo tokenów

| Co | Jak |
|---|---|
| Link z czatu | 32 losowe bajty (`SecureRandom`), ważny 5 min, **jednorazowy**; nowy `/muzyka` unieważnia poprzedni |
| Gdzie jest token | po `#` w adresie, więc nie trafia do nginx, logów ani nagłówka Referer; strona od razu usuwa go z paska adresu |
| Wysyłka | pierwsza wiadomość WebSocket (nie adres URL), tylko przez `wss://` |
| Przechowywanie na serwerze | tylko skrót SHA-256 tokenu, nigdy sam token |
| Ponowne połączenie | po zalogowaniu karta dostaje token wznowienia (w `sessionStorage`, znika z zamknięciem karty); wymieniany przy każdym użyciu, ważny 30 min od rozłączenia |
| Restart serwera | tokeny wznowienia zapisywane jako skróty do `plugins/McRPGDzwiek/sesje.dat` i wczytywane raz po starcie, więc karty łączą się same |
| Jedna karta na gracza | nowa karta zamyka starą (kod 4002) i unieważnia jej token |
| Wylogowanie | `/muzyka rozlacz` unieważnia wszystkie tokeny gracza i zamyka kartę |
| Długo poza grą | po 15 min poza grą karta jest rozłączana (4005) |
| Ochrona serwera | sprawdzanie `Origin`, limit połączeń (500, 4 na IP), 10 s na zalogowanie, 1 próba logowania na połączenie, blokada IP po 10 nieudanych próbach na minutę, ramka do 4 KB, 20 wiadomości (także pingów) na 10 s |
| Safari | strona tworzy stałą pulę 3 elementów audio odblokowaną pierwszym kliknięciem, więc kolejne utwory grają bez klikania |
| Strona | CSP (tylko własne skrypty, audio z tego samego adresu), dane z serwera wstawiane jako tekst, ścieżki audio sprawdzane wyrażeniem regularnym, adres WebSocket nie da się podmienić linkiem (poza testami na localhost) |

Co może zrobić ktoś, kto przechwyci link przed graczem: słyszeć muzykę tego gracza (czyli
z grubsza wiedzieć, w jakim regionie jest) do czasu `/muzyka` albo `/muzyka rozlacz`.
Nie daje to dostępu do konta ani do gry.

## Komendy

| Komenda | Kto | Co robi |
|---|---|---|
| `/muzyka` (ang. `/audio`) | gracz (`mcrpg.dzwiek.use`, domyślnie wszyscy) | jednorazowy link do strony |
| `/muzyka rozlacz` | gracz | rozłącza kartę i unieważnia tokeny |
| `/rpgaudio reload` | administracja (`mcrpg.dzwiek.admin`, domyślnie OP) | przeładowanie config.yml |
| `/rpgaudio status` | administracja | stan serwera dźwięku, liczba kart, tokenów, dźwięków, regionów |
| `/rpgaudio where` | administracja | region, grany utwór i stan karty |
| `/rpgaudio play <gracze> <efekt> [głośność]` | administracja, konsola | efekt jednorazowy |
| `/rpgaudio music <gracze> <klucz> <utwór\|none> [priorytet]` | administracja, konsola | warstwa muzyki (domyślnie priorytet 50) |
| `/rpgaudio music <gracze> <klucz> stop` | administracja, konsola | zdjęcie warstwy |

`<gracze>` to nick albo selektor (`@a`, `@a[distance=..30]`).

### MythicMobs (bez zależności od pluginu)

```yaml
Skills:
  - command{c="rpgaudio music <target.name> boss:smok demo_boss 100"} @PlayersInRadius{r=40} ~onSpawn
  - command{c="rpgaudio play <target.name> demo_ryk"} @PlayersInRadius{r=30} ~onTimer:200
  - command{c="rpgaudio music <target.name> boss:smok stop"} @PlayersInRadius{r=60} ~onDeath
```

### API Java (dla przyszłych pluginów McRPG)

```java
DzwiekApi audio = DzwiekApi.get();
audio.setMusic(player, "boss:smok", "demo_boss", DzwiekApi.PRIORITY_BOSS);
audio.playSound(player, "demo_ryk", 1.0);
audio.clearMusic(player, "boss:smok"); // wraca muzyka regionu
```

Muzyka działa **warstwami**: świat (0) < region (10) < zdarzenie (50) < boss (100). Gra warstwa
o najwyższym priorytecie; po zdjęciu wraca poprzednia, więc boss nie musi pamiętać, co grało.

## Konfiguracja

Wszystko w `plugins/McRPGDzwiek/config.yml` (klucze po angielsku, opisy po polsku): adres strony,
port, dozwolone Origin, katalog dźwięków (`sounds`), muzyka światów (`worlds`), regiony
prostopadłościenne z priorytetem, muzyką i dźwiękiem wejścia (`regions`), teksty w MiniMessage.

## Pliki

| Ścieżka | Co |
|---|---|
| `core/` | rdzeń bez Bukkita: tokeny, limity, warstwy muzyki, serwer WebSocket, protokół (Java 21, testy JUnit) |
| `plugin/` | plugin Paper 26.3 (Java 25): config, regiony, komendy, API |
| `web/` | strona: `index.html`, `app.js`, `style.css`, `audio/` (syntezowane dźwięki demo, ok. 480 KB) |
| `nginx/muzyka.conf` | wzór bloków nginx (do wgrania dopiero po zgodzie Zielu) |
| `narzedzia/generuj_demo_audio.sh` | generator dźwięków demo (ffmpeg) |
| `narzedzia/test_przegladarka.js` | test strony w Chromium (Playwright) |

## Budowanie

GitHub Actions (`.github/workflows/dzwiek.yml`) buduje jar na Javie 25 przy każdym pushu
i udostępnia `McRPGDzwiek-0.1.0.jar` oraz `McRPGDzwiek-strona.zip` jako artefakt.
Lokalnie na starszej Javie da się zbudować i przetestować tylko rdzeń: `mvn -pl core test`.

## Test bez gry

```
mvn -q -pl core test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
```

```
cd core && java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" pl.mcgramy.rpg.dzwiek.core.DevServer ../web
```

```
node narzedzia/test_przegladarka.js
```

DevServer udaje grę na `127.0.0.1:8786` (strona) i `:8787` (WebSocket). Wynik 16.11: 25/25 sprawdzeń
(logowanie linkiem, przejścia muzyki, efekty, wyjście i powrót do gry, restart serwera,
odświeżenie strony, zużyty link, zastąpienie karty, wylogowanie, obcy Origin).

## Protokół

Każda wiadomość to obiekt JSON z polem `t`.

Strona → serwer (tylko jedna, pierwsza wiadomość): `{"t":"auth","token":"…"}` albo `{"t":"resume","token":"…"}`.

Serwer → strona:

| `t` | Pola | Kiedy |
|---|---|---|
| `session` | `resume` | po zalogowaniu: nowy token wznowienia |
| `hello` | `name`, `online`, `base`, `catalog` (`id → {k, src[], v, title}`) | po zalogowaniu i po przeładowaniu configu |
| `music` | `id` (albo `null` = cisza), `fade` (ms) | zmiana muzyki |
| `sfx` | `id`, `vol` (0–2) | efekt jednorazowy |
| `status` | `online` | gracz wszedł do gry albo wyszedł |

Kody zamknięcia: 4001 zły lub zużyty token, 4002 inna karta, 4003 brak logowania w czasie,
4004 zła wiadomość, 4005 długo poza grą, 4006 wylogowanie, 4008 limit; 1001 restart serwera
(strona łączy się sama).

## Do zrobienia po prototypie

- Test na serwerze „mcgramy - MMORPG” (potrzebny dodatkowy port i wpis w nginx, zob. wyżej).
- Prawdziwa muzyka i efekty zamiast dźwięków demo; kolor modułu „ᴍᴜᴢʏᴋᴀ” do rejestru kolorów.
- Regiony z edytora w grze (teraz tylko w configu) i podział na chunki przy setkach regionów.
- Natywna mechanika MythicMobs (np. `mcrpgmuzyka{…}`) zamiast komendy.
- Dźwięk przestrzenny (głośność zależna od odległości od źródła).
