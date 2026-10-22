# Copertura e limiti — versione 1.4.0

**Non è una conversione universale di tutte le classi Java né di ogni configurazione Spring.** Il plugin produce uno snapshot dei campi reali della JVM sospesa, senza chiamare metodi nell'applicazione. Le famiglie seguenti hanno lettori dedicati; le regressioni verificano esempi concreti su JDK/JBR 21. Implementazioni personalizzate, viste e versioni diverse possono avere layout differenti.

## Java

| Famiglia | Rappresentazione / copertura |
| --- | --- |
| Tutti gli 8 primitivi e i relativi wrapper | Valori JSON; `char` come stringa. `Float` mantiene la propria precisione lessicale. NaN e infinito sono stringhe, perché non sono numeri JSON validi. |
| `null` | JSON `null`, anche al livello radice. |
| Array di primitivi/oggetti, multidimensionali | Array JSON; cicli e riferimenti condivisi espliciti. |
| Stringhe, `StringBuilder`, `StringBuffer` | Testo Unicode; supporto ai layout compatti Latin1/UTF16. |
| Enum e `Class` | Nome della costante / nome del tipo. |
| POJO, record, oggetti annidati, campi privati/ereditati | Campi reali; i nomi nascosti nelle superclassi sono qualificati. Campi statici/sintetici non vengono espansi dentro i POJO. |
| `BigInteger`, `BigDecimal`, UUID, Currency | Numero JSON o stringa appropriata, senza passare da `double` per i numeri a precisione arbitraria. |
| `java.time` | LocalDate/Time/DateTime, Instant, OffsetDateTime/Time, ZonedDateTime, ZoneOffset/ZoneRegion, Duration, Period, Year, YearMonth, MonthDay. |
| `Date`, tipi SQL temporali | Snapshot temporale quando i campi sono coerenti. Una Date modificata con cache interna non normalizzata viene esclusa anziché usare un timestamp obsoleto. |
| URI, File, Pattern | Stringa letta dai campi noti; non si invocano i rispettivi `toString()`. |
| Optional e varianti primitive | Contenuto o null. Gli alias del contenuto puntano a un payload JSON effettivamente presente. |
| AtomicInteger/Long/Boolean/Reference | Contenuto; AtomicBoolean è un booleano. Un ciclo composto soltanto da wrapper trasparenti non ha payload JSON e viene marcato `$skipped`. |
| Liste e deque note | ArrayList, LinkedList, Vector/Stack, ArrayDeque, Arrays.asList, List.of e wrapper noti; supporto alle subList con offset effettivo, comprese quelle lontane dall'inizio. |
| Set noti | HashSet/LinkedHashSet, Set.of, EnumSet regolare e jumbo, wrapper noti, CopyOnWriteArraySet. Le tabelle sparse non vengono trattate come array compatti. |
| Mappe note | HashMap/LinkedHashMap, TreeMap, ConcurrentHashMap, Map.of, singleton e wrapper noti, EnumMap, IdentityHashMap, WeakHashMap, Properties. |
| Chiavi non stringa/null o mappa a identità | Rappresentazione `$entries` con key/value; IdentityHashMap mantiene entry distinte anche per String uguali ma con identità diverse. Properties rappresenta le proprie entry, senza eseguire `getProperty()` o applicare i defaults. |
| Collezioni concorrenti note | CopyOnWriteArrayList/Set, ConcurrentLinkedQueue/Deque, ArrayBlockingQueue, LinkedBlockingQueue/Deque e lettori per PriorityQueue/PriorityBlockingQueue. Le code di priorità rappresentano lo storage interno, senza promettere l'ordine di estrazione. |

Le regressioni includono array di tutti i primitivi, record, ereditarietà, cicli, alias, set immutabili sparsi, subList con offset elevato, chiavi null/duplicate per identità, code con nodi rimossi e buffer circolare, wrapper trasparenti, Unicode e limiti di esportazione. Una sottoclasse non può ingannare i lettori JDK semplicemente nascondendo campi come `table`, `elementData` o `name`.

Non tutti i tipi JDK hanno un lettore: stream, canali/I/O, stato nativo, thread, classloader, risorse, layout sconosciuti e molte viste di collezioni non sono dati JSON ordinari. Alcune viste come submappe/reversed views possono essere escluse. Una classe che implementa Map/Collection con un layout arbitrario non viene interpretata sulla base di un campo chiamato `table`.

## Unico comando e Spring

| Selezione / situazione | Comportamento |
| --- | --- |
| Variabile locale visibile | Legge quella variabile nel frame, senza cercare una diversa istanza per nome. |
| Campo di istanza, statico, privato o ereditato | Usa la dichiarazione risolta nell'editor; distingue campi nascosti e omonime variabili locali. |
| `ordine.cliente` / `this.ordine.cliente` | Legge i campi del ricevitore effettivo. Nessun getter o chiamata a metodo. |
| Riferimento null | Esporta null; non lo rimpiazza con un singleton Spring. Un ricevitore null prima dell'ultimo campo produce un errore esplicito. |
| Metodo `@Bean` | Risolve nome/alias, incluse costanti Java. Se il ritorno dichiarato implementa FactoryBean, dereferenzia automaticamente la registrazione per esportare la factory effettivamente restituita. |
| Classe componente Spring | Cerca un singleton già creato compatibile con il tipo. |
| Dipendenza iniettata non accessibile dal frame | Cerca un nome registrato Qualifier/Resource oppure un tipo univoco. Un qualifier esplicito non viene ignorato per scegliere un altro bean. |
| Proxy JDK e CGLIB Spring | Target già disponibile, anche annidato, incluse varianti frozen/opaque/statiche note. Nessuna chiamata al TargetSource. |
| Contesti | Famiglia AbstractApplicationContext e sottoclassi Boot/personalizzate, solo attive. Contesti chiusi ignorati; ambiguità di nomi/tipi/classloader segnalate. |
| FactoryBean | Prodotti soltanto se già nella cache; factory leggibile dal metodo @Bean con ritorno FactoryBean o da un riferimento effettivo. |
| Bean Spring che restituisce null | Il sentinel Spring NullBean diventa JSON null. Una ricerca per solo tipo non inventa il tipo dichiarato di un NullBean. |
| Lazy/prototype/scoped o target dinamico non disponibile | Non creati. Messaggio o `$skipped`, secondo il caso. |

Il test Spring usa una vera applicazione **Spring Boot 3.4.5 / Spring Framework 6.2.6** in una seconda JVM sospesa tramite JDI. Questo non equivale a verificare ogni estensione Spring, ogni contesto custom o ogni release Boot. Le implementazioni di ApplicationContext che non derivano da AbstractApplicationContext non hanno una ricerca heap universale; un riferimento locale/campo già accessibile resta leggibile.

Il fallback per tipo non ricostruisce tutte le regole della dependency injection, come `@Primary`, priorità e qualifier custom: richiede un'istanza effettivamente univoca. Il campo già iniettato viene invece letto direttamente e conserva la scelta fatta da Spring. In caso di ambiguità si può selezionare il riferimento effettivo oppure il metodo `@Bean` corrispondente.

Il JSON non applica getter calcolati, annotazioni Jackson, serializer custom, regole ORM o semantica REST. I qualifier personalizzati basati su metadati non equivalenti al nome registrato non vengono ricostruiti; si può leggere direttamente il riferimento già iniettato.

## Limiti e marcatori

| Limite per esportazione | Normale | Spring riconosciuto |
| --- | ---: | ---: |
| Profondità | 40 | 16 |
| Elementi per array/collection/map | 10.000 | 2.000 |
| Oggetti distinti | 50.000 | 20.000 |
| Valori visitati | 200.000 | 80.000 |
| Testo conservato, incluse chiavi/campi/numeri grandi | 5.000.000 caratteri | 2.000.000 caratteri |

Ogni stringa è inoltre limitata a 100.000 caratteri. I budget si azzerano a ogni export. Il budget di testo non è un limite rigoroso dei byte dell'intero file JSON, che contiene anche indentazione e marcatori. I lettori applicano anche limiti alla scansione delle tabelle interne; raggiungerli non deve trasformare una ricerca incompleta in un risultato certo.

- `$ref`: riferimento a un payload già emesso, con escaping JSON Pointer.
- `$truncated`: limite raggiunto e dati omessi.
- `$skipped`: layout o target non leggibile in modo sicuro senza eseguire codice.
- `$error`: errore di lettura circoscritto; gli altri campi possono restare esportabili.
- `$entries`: entry esplicite quando un semplice oggetto JSON perderebbe informazioni sulle chiavi.

Se altri thread dell'applicazione continuano a modificare gli oggetti mentre si esporta, uno snapshot può essere incoerente. I test usano breakpoint che sospendono tutti i thread della fixture.
