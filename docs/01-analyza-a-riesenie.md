# 1 · Stručná analýza a popis zvoleného riešenia

## Čo zadanie skutočne vyžaduje

Za jednotlivými bodmi zadania sú štyri systémové vlastnosti:

1. **Asynchrónnosť** — klient dostane `campaignId` okamžite, odosielanie beží na pozadí.
2. **Pozorovateľnosť** — stavové API musí kedykoľvek vrátiť pravdivé priebežné počty.
3. **Odolnosť** — reštart aplikácie nesmie kampaň stratiť; dočasné chyby sa opakujú
   obmedzene, trvalé sa neopakujú vôbec; zlyhanie jedného príjemcu nezastaví ostatných.
4. **Idempotencia** — interné opakovanie (retry, recovery, súbeh workerov) nikdy
   nespôsobí opakované odoslanie tej istej notifikácie.

Kľúčové pozorovanie: všetky štyri sa dajú splniť jedným návrhovým rozhodnutím —
**stav každého príjemcu je perzistentný riadok v PostgreSQL a databáza je zároveň
frontou úloh**.

## Prečo nie message broker

RabbitMQ/Kafka by pre tento rozsah nepriniesol hodnotu: stavové API aj tak vyžaduje
dopytovateľný perzistentný stav v databáze, takže broker by zápisy do DB neušetril —
iba by pridal infraštruktúrnu vrstvu a nutnosť riešiť konzistenciu DB ↔ broker
(outbox pattern). DB-backed fronta dáva rovnaké garancie s jednou technológiou.

## Zvolené technológie

| Oblasť | Voľba |
|---|---|
| Jazyk / framework | Java 25, Spring Boot 3.5 |
| Build | Gradle (wrapper) |
| Databáza | PostgreSQL 16 (Docker Compose), migrácie Flyway |
| Asynchrónne spracovanie | `@Async` dispatch + `@Scheduled` recovery poller nad DB frontou |
| Odolnosť voči výpadku providera | Resilience4j circuit breaker |
| E-mailová služba | deterministický simulátor s idempotenčným kľúčom |
| Testy | JUnit 5 + Testcontainers (reálny Postgres) + Awaitility |

## Ako riešenie funguje

1. `POST /api/campaigns` zvaliduje vstup a v **jednej transakcii** uloží kampaň +
   všetkých príjemcov v stave `PENDING`; klient okamžite dostane `202` s `campaignId`.
2. Odosielanie štartuje **až po commite** (`@TransactionalEventListener(AFTER_COMMIT)`
   + `@Async`) — stavové API vidí kampaň hneď a worker nikdy nečíta necommitnuté dáta.
3. Worker si príjemcu **privlastní podmieneným UPDATE** (CAS: `SET status='SENDING'
   WHERE status='PENDING'`) — z ľubovoľného počtu workerov pustí databáza presne
   jedného. Zápis výsledku je navyše **fencovaný** hodnotou `attempts` z claimu,
   takže oneskorený worker nikdy neprepíše novší stav.
4. Dočasná chyba vracia príjemcu do `PENDING` s exponenciálnym backoffom a limitom
   pokusov; trvalá chyba je `FAILED` okamžite. Všetko na úrovni jedného riadku —
   zlyhanie jedného príjemcu sa ostatných nedotkne.
5. `@Scheduled` **recovery poller** periodicky vyberá dozreté `PENDING` riadky a vracia
   do obehu „zaseknuté" `SENDING` (pád workera). Po reštarte tak kampaň dobehne sama —
   async dispatch je len optimalizácia latencie, garanciu dáva databáza.
6. Stav kampane (`PROCESSING`/`COMPLETED`) sa **nikam neukladá** — odvodzuje sa
   agregáciou zo stavov príjemcov, takže sa nemôže rozísť s realitou.

## Konzistentné správanie pri sporných vstupoch

- **Neexistujúci alebo neaktívny používateľ** → celá kampaň sa odmietne
  (`422 Unprocessable Entity`) a odpoveď vymenuje **všetky** problematické adresy
  naraz (zvlášť `unknown`, zvlášť `inactive`). All-or-nothing: čiastočné prijatie by
  robilo `total` nejednoznačným a skrývalo preklepy volajúceho; jedna odpoveď so
  všetkými chybami umožní opraviť žiadosť na jeden pokus.
- **Duplicitná adresa** → ticho sa deduplikuje (case-insensitive) pred uložením;
  `total` odráža unikátnych príjemcov a fyzicky sa odošle práve jeden e-mail.
  Poistkou je unikátny constraint `(campaign_id, user_id)`.
- **Neplatný formát e-mailu** (vrátane adries s medzerami) → `400` ešte pred
  akýmkoľvek prístupom do DB.

## Simulácia e-mailovej služby

Deterministická, riadená adresou príjemcu — demo je opakovateľné, nie závislé od náhody:

| Adresa | Správanie |
|---|---|
| bežná adresa | úspech po konfigurovateľnej latencii |
| `temp-fail@example.com` | 2× dočasná chyba (451), na 3. pokus úspech — demo retry s backoffom |
| `always-fail@example.com` | dočasná chyba pri **každom** pokuse → `FAILED` po vyčerpaní limitu — demo obmedzeného retry |
| `bounce@example.com` | trvalé odmietnutie (550) → `FAILED` bez opakovania |
| `slow@example.com` | vysoká latencia → dlhé okno `PROCESSING` na demo |

Simulátor navyše rešpektuje **idempotenčný kľúč** (`campaign_recipient.id`, stabilný
naprieč pokusmi aj reštartami): už doručenú notifikáciu odmietne doručiť druhýkrát,
potlačený duplikát iba zaznamená — rovnaký koncept ako deduplication/idempotency key
u reálnych providerov. Endpoint `GET /api/campaigns/{id}/deliveries` tieto počty
sprístupňuje (`providerCalls` vs `delivered`), takže tvrdenie „interné opakovanie
neodošle tú istú notifikáciu dvakrát" je **overiteľné cez API**, nie iba argumentom.

Ide o vedomý kompromis pre účely zadania: endpoint garanciu iba *pozoruje*
(vynucujú ju stavové prechody workerov, nezávisle pokryté integračnými testami)
a v produkčnom systéme by sa zredukoval na per-recipient stav bez interných
počítadiel providera, prípadne presunul za demo profil.

## Výpadok celého providera — circuit breaker

Retry s backoffom rieši zlyhanie *jednej adresy*; výpadok *celého providera* by však
míňal retry rozpočet všetkých príjemcov naraz. Preto je okolo providera circuit breaker:

- séria po sebe idúcich chýb providera obvod otvorí — ďalšie pokusy sa odmietajú
  okamžite a príjemcovia sa vracajú do `PENDING` **s vráteným pokusom** (release:
  `attempts - 1`), takže výpadok nikdy nevyčerpá retry rozpočet adries;
- **per-adresné chyby breaker ignoruje** — hard bounce (550) ani opakované 451 jednej
  schránky nevypovedajú nič o zdraví providera (a keby ju otvárali, adresa by nikdy
  nemohla vyčerpať svoj limit — každý pokus by sa jej vracal);
- po `wait-duration` prejde obvod do half-open a pustí **jeden skúšobný e-mail**;
  úspech obvod zavrie a kampaň dobehne sama. Zlyhaná sonda sa príjemcovi neúčtuje.

Výpadok nie je súčasťou verejného API — scenár pokrýva `ProviderOutageIntegrationTest`;
stav obvodu je viditeľný na `/actuator/circuitbreakers`.

## Garancie — poctivo

Aplikačná vrstva garantuje **at-least-once** spracovanie s deduplikáciou na úrovni DB
(CAS claim + fencing token). Teoretické okno: pád aplikácie *po* odoslaní mailu, ale
*pred* zápisom `SENT` — poller pokus zopakuje. Toto okno uzatvára **idempotenčný kľúč
na hranici providera**: zopakovaný pokus provider rozpozná a druhýkrát nedoručí.
Výsledok: at-least-once *volanie* providera, **exactly-once doručenie** v rámci
idempotenčného okna providera — rovnaký model, aký ponúkajú reálni provideri.

## Škálovanie (nad rámec zadania)

Pri stovkách až tisíckach príjemcov na kampaň je DB-backed fronta pohodová — úzkym
hrdlom je rate limit providera, nie databáza. Pri rádovo vyšších objemoch (~1M+) by sa
menila jednotka práce (dávky), pribudol by broker na horizontálne škálovanie workerov
a denormalizované počítadlá — perzistentný stav v DB však ostáva vo všetkých
variantoch, lebo ho vyžaduje stavové API a idempotencia.
