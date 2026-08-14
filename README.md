# Bulk e-mail notification campaigns

[![CI](https://github.com/PeterXMR/notification-demo/actions/workflows/ci.yml/badge.svg)](https://github.com/PeterXMR/notification-demo/actions/workflows/ci.yml)

Backendová aplikácia na hromadné odosielanie e-mailových notifikácií registrovaným
používateľom. Odosielanie prebieha asynchrónne: klient dostane `campaignId` okamžite
a stav kampane priebežne zisťuje cez stavové API.

Návrhové dokumenty (podľa bodov zadania):

1. [Stručná analýza a popis zvoleného riešenia](docs/01-analyza-a-riesenie.md)
2. [Návrh API, dátového modelu a stavov](docs/02-api-datovy-model-stavy.md)
3. [Schéma toku spracovania](docs/03-schema-toku.md)

## Stack

- Java 25, Spring Boot 3.5, Gradle (wrapper)
- PostgreSQL 16 (Docker Compose), Flyway migrácie
- Asynchrónne spracovanie: DB-backed fronta + `@Async` dispatch + `@Scheduled` recovery poller
- Resilience4j circuit breaker okolo mail providera
- Testy: JUnit 5 + Testcontainers (reálny Postgres) + Awaitility

## Spustenie

### Celé v Dockeri

```bash
docker compose up --build
```

### Aplikácia z IDE / Gradle, databáza v Dockeri

```bash
docker compose up -d db
./gradlew bootRun
```

### Demo profil — odporúčané pre Postman

```bash
docker compose up -d db
./gradlew bootRun --args='--spring.profiles.active=demo'
```

Rovnaká logika, iba kratšie časy (retry backoff 2 s namiesto 30 s): scenár `temp-fail@`
skončí do ~7 s, vyčerpanie pokusov `always-fail@` do ~30 s.

Aplikácia beží na `http://localhost:8080`, health na `/actuator/health`.
Migrácie (schéma + seed používateľov) sa aplikujú automaticky pri štarte.

### Pripojenie k databáze (IntelliJ / psql)

Lokálne dev hodnoty (z `compose.yaml`; aplikácia ich prepisuje env premennými
`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`):

| | |
|---|---|
| URL | `jdbc:postgresql://localhost:5432/demo` |
| Používateľ / heslo | `demo` / `demo` |

Užitočný dotaz počas demo behu:

```sql
SELECT email, status, attempts, next_attempt_at, last_error
FROM campaign_recipient
ORDER BY updated_at DESC;
```

## API v skratke

Úplný kontrakt v [docs/02](docs/02-api-datovy-model-stavy.md).

### Vytvorenie kampane — `POST /api/campaigns`

```bash
curl -i -X POST http://localhost:8080/api/campaigns \
  -H 'Content-Type: application/json' \
  -d '{
    "subject": "Planned maintenance",
    "message": "The service will be temporarily unavailable.",
    "recipients": ["john@example.com", "jane@example.com", "temp-fail@example.com", "bounce@example.com", "slow@example.com"]
  }'
```

Odpoveď `202 Accepted` — okamžite, bez čakania na odoslanie:

```json
{"campaignId": "…", "status": "PROCESSING"}
```

| Kód | Situácia |
|---|---|
| `400` | prázdny predmet/text, predmet nad 255 znakov, prázdny zoznam, neplatný formát e-mailu, e-mail nad 320 znakov, viac než 100 príjemcov |
| `422` | príjemca neexistuje alebo nie je aktívny — kampaň sa odmietne **celá**, odpoveď vymenuje zoznamy `unknown` / `inactive` |

Duplicitné adresy sa ticho deduplikujú (case-insensitive); `total` = počet unikátnych
príjemcov a fyzicky sa odošle práve jeden e-mail.

### Stav kampane — `GET /api/campaigns/{campaignId}`

```bash
curl http://localhost:8080/api/campaigns/<campaignId>
```

```json
{"campaignId": "…", "status": "PROCESSING", "total": 5, "sent": 2, "failed": 1, "remaining": 2}
```

`status` je `PROCESSING`, kým existuje nedokončený príjemca; potom `COMPLETED`.
Stav sa odvodzuje agregáciou zo stavov príjemcov — nikde sa neukladá.

### Overenie doručení — `GET /api/campaigns/{campaignId}/deliveries`

Demo/verifikačný endpoint: čo mail provider pre každého príjemcu skutočne urobil.

```json
{
  "recipients": [
    {"email": "temp-fail@example.com", "status": "SENT", "attempts": 3,
     "providerCalls": 3, "delivered": 1, "duplicatesSuppressed": 0}
  ],
  "totalDelivered": 1,
  "totalDuplicatesSuppressed": 0
}
```

Worker odovzdáva providerovi `campaign_recipient.id` ako **idempotenčný kľúč**, takže
provider už doručenú notifikáciu druhýkrát nedoručí. Pre každého odoslaného príjemcu
preto platí `delivered == 1` aj pri `providerCalls > 1` — overiteľný dôkaz kritéria
„interné opakovanie nespôsobí opakované odoslanie tej istej notifikácie".

> **Poznámka k návrhu:** endpoint je súčasťou verejného API zámerne — funkčná ukážka
> cez Postman je požadovaný výstup zadania a agregované počty nerozlíšia jedno volanie
> providera od piatich. Samotná exactly-once garancia sa ním len *pozoruje*; vynucuje
> sa v stavových prechodoch workerov a nezávisle ju pokrývajú integračné testy.
> V produkčnom systéme by sa endpoint zredukoval na per-recipient stav
> (`status`, `attempts`, `lastError`), prípadne presunul za demo profil.

## Registrovaní používatelia (seed)

Migrácie zakladajú **101 používateľov, z toho 5 neaktívnych**:

| E-mail | Aktívny | Správanie simulátora |
|---|---|---|
| `john@ / jane@ / alice@ / bob@ / carol@example.com` | ✅ | úspech po ~800 ms |
| `temp-fail@example.com` | ✅ | 2× dočasná chyba (451), na 3. pokus úspech — demo retry s backoffom |
| `always-fail@example.com` | ✅ | dočasná chyba pri každom pokuse → `FAILED` po 5 pokusoch — demo **obmedzeného** retry |
| `bounce@example.com` | ✅ | trvalé odmietnutie (550) → `FAILED` bez opakovania |
| `slow@example.com` | ✅ | úspech po ~5 s — drží kampaň viditeľne v `PROCESSING` |
| `inactive@example.com` | ❌ | kampaň s touto adresou → `422` |
| `user10@…user96@example.com` | ✅ | bežní používatelia (úspech) — dosť na kampaň s plným limitom 100 |
| `user97@…user100@example.com` | ❌ | ďalší neaktívni → `422` |

Časovania a limity sú konfigurovateľné v `application.yml` (sekcia `notification`);
demo profil ich skracuje v `application-demo.yml`.

## Demo cez Postman

Importuj [postman/campaigns.postman_collection.json](postman/campaigns.postman_collection.json)
a spusti aplikáciu s demo profilom.
Kolekcia je členená podľa akceptačných kritérií do 4 priečinkov:

1. **Bulk kampaň** — všetkých 95 aktívnych používateľov naraz (vrátane `temp-fail@`,
   `bounce@`, `slow@`). `202` okamžite (test overuje `responseTime`), potom polluj stav:
   rastúce `sent`, `PROCESSING` → `COMPLETED`, `failed=1` pre `bounce@`. V Collection
   Runneri sa status polluje sám (`setNextRequest`).
2. **Správania simulátora izolovane** — samostatná kampaň pre každý scenár: úspešné
   odoslanie; dočasná chyba → retry → úspech (`providerCalls=3, delivered=1`); trvalé
   odmietnutie → `FAILED` po 1 pokuse bez zastavenia ostatných; obmedzené retry —
   `always-fail@` skončí `FAILED` po presne 5 pokusoch, žiadne nekonečné opakovanie.
3. **Validácie a konzistencia** — neplatný formát (`400`), prázdny predmet/text (`400`),
   101 príjemcov (`400`), neznámy príjemca (`422`, nič sa neuloží), neaktívny (`422`),
   neznámy + neaktívny naraz (jedna odpoveď vymenuje obe skupiny), bulk s neregistrovanými
   (all-or-nothing), neexistujúca kampaň (`404`).
4. **Exactly-once overenie** — duplicitná adresa 3× v žiadosti → `total=1`, `delivered=1`;
   nad bulk kampaňou: každý odoslaný príjemca má `delivered=1`, hoci `providerCalls`
   môže byť > 1.

**Test reštartu:** vytvor kampaň so `slow@example.com`, zabij aplikáciu počas
`PROCESSING`, spusti znova — recovery poller kampaň dokončí (nič sa nestratí,
nič sa neodošle duplicitne — over cez `/deliveries`).

**Výpadok providera** nie je súčasťou verejného API; scenár pokrýva
`ProviderOutageIntegrationTest` (prepínač priamo na beane simulátora). Stav obvodu
vidno na `/actuator/circuitbreakers`.

## Testy

```bash
./gradlew test
```

Testcontainers si stiahne a spustí vlastný Postgres — Docker musí bežať. Pokryté:

- okamžité prijatie + priebežné počty, dokončenie kampane
- retry s obmedzeným počtom pokusov vrátane **vyčerpania limitu** (`always-fail@` skončí
  `FAILED` po presne max-attempts pokusoch a ďalej sa neretryuje)
- trvalé zlyhanie izolované na jedného príjemcu
- limit 100 príjemcov (100 → OK, 101 → 400), validácie 400/422, deduplikácia
  (1 riadok + 1 fyzické doručenie), 404
- zotavenie pollerom po strate dispatchu aj zo zaseknutého `SENDING`
- circuit breaker: otvorenie pri výpadku, ignorovanie per-adresných chýb, half-open
  sonda, zachovanie retry rozpočtu počas výpadku
- **exactly-once doručenie**: súbežní workeri nad tým istým príjemcom, stale worker po
  re-claime, retry po stratenom zápise `SENT` — vždy `delivered == 1` (overené proti
  delivery logu simulátora, nie iba proti stavu v DB)

## Architektúra v skratke

1. `POST` validuje vstup a v jednej transakcii uloží kampaň + príjemcov v `PENDING`; vráti `202`.
2. Po commite (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`) sa príjemcovia zaradia do executora.
3. Worker si príjemcu privlastní podmieneným UPDATE (`PENDING → SENDING`) — CAS claim, žiadne duplicitné spracovanie.
4. Výsledok: `SENT`, návrat do `PENDING` s exponenciálnym backoffom (max 5 pokusov), alebo `FAILED`. Zápis je chránený fencing tokenom; provider navyše dostáva idempotenčný kľúč, takže už doručená notifikácia sa nikdy nedoručí druhýkrát.
5. `@Scheduled` recovery poller dokončí všetko, čo prežilo reštart alebo pád workera — garanciu dáva databáza, async dispatch je len optimalizácia latencie.
6. Circuit breaker okolo providera: výpadok otvorí obvod, príjemcovia sa vracajú do `PENDING` s vráteným pokusom, half-open sonda obvod zavrie a kampaň sa sama dokončí.

Detaily a diagramy v [docs/](docs/).
