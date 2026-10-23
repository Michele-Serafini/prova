# Verifica della versione 1.5.0

Controlli completati il **23 ottobre 2026**.

| Controllo | Esito |
| --- | --- |
| `clean build` | PASS, directory build ricreata e suite eseguita |
| `buildPlugin` | PASS — `PluginJson-1.5.0.zip` |
| `verifyPluginProjectConfiguration` | PASS |
| `verifyPluginStructure` | PASS |
| `buildSearchableOptions` / `jarSearchableOptions` | PASS, indice della nuova pagina delle impostazioni incluso |
| IntelliJ Plugin Verifier 1.411 | **Compatible** |
| IDE verificato | IntelliJ IDEA Community 2025.1.4.1, IC-251.27812.49 |
| Suite Gradle | **16 test in 8 classi, 0 fallimenti, 0 errori, 0 saltati** |
| Benchmark su JVM JDI reali | PASS, 4 JSON identici al riferimento e 2 ricerche Spring corrette |
| `git diff --check` | PASS |

Comando completo eseguito dalla cartella `ExportJson/`:

```text
gradle clean build buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
```

Su Windows usare il wrapper incluso con JDK 21: `.\gradlew.bat clean build buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin`.

## Test eseguiti

| Classe | Test | Esito |
| --- | ---: | --- |
| EditorExportTargetResolverTest | 1 | PASS |
| JavaTypesIntegrationTest | 1 | PASS |
| JsonExporterServiceTest | 1 | PASS |
| RuntimeScannerPerformanceTest | 6 | PASS |
| RuntimeScannerServiceTest | 1 | PASS |
| RuntimeValueResolverTest | 1 | PASS |
| SpringBeanLocatorTest | 1 | PASS |
| ExportSettingsTest | 4 | PASS |

Le suite JDI avviano vere JVM separate, sospese su breakpoint. Controllano tipi Java, layout JDK, riferimenti/cicli, null, redazione, campi nascosti, ricevitori effettivi, Spring Boot 3.4.5 / Spring Framework 6.2.6, FactoryBean, proxy e ambiguità. I contatori nelle fixture verificano che non venga eseguito codice applicativo per esportare i dati.

Le nuove verifiche comprendono:

- Budget deterministici delle chiamate JDI per POJO, mappe sparse e BigInteger; nessuna lettura dei campi sensibili o sintetici; arresto delle letture al budget; rispetto dei limiti delle stringhe scalari; cache azzerate tra snapshot. Questi sei test usano mirror JDI controllati per contare precisamente le chiamate, affiancati alle suite e ai benchmark con vere JVM.
- Persistenza delle impostazioni tramite serializer XML IntelliJ, ripristino dei soli campi invalidi, limiti ammessi, Apply/Reset Swing e rifiuto atomico di valori invalidi o frazionari. La pagina viene anche istanziata dall'IDE durante la generazione dell'indice delle impostazioni.
- Dodici scritture concorrenti di JSON Unicode con null, percorsi univoci e contenuti verificati.
- Tutti e cinque i limiti della ricerca Spring, confine dei salti alias e riuso del locator dopo ripresa della JVM con sostituzione dei contesti.

## Prestazioni

Il confronto con il commit `5b80db2` è riproducibile mediante **[ExportJson/tools/benchmark/run.py](ExportJson/tools/benchmark/run.py)**. Il rapporto **[RESULTS-2026-10-23.txt](ExportJson/tools/benchmark/RESULTS-2026-10-23.txt)** conserva versioni, metodo, hash dei sorgenti misurati, hash dei JSON, tempi e conteggi JDWP effettivi.

| Fixture | Richieste prima | Richieste dopo | Riduzione |
| --- | ---: | ---: | ---: |
| 500 POJO, 12 campi | 6.002 | 502 | 91,64% |
| 20 POJO, 96 campi | 1.921 | 21 | 98,91% |
| HashMap, 16.384 bucket sparsi | 16.389 | 67 | 99,59% |
| BigInteger, 1.024 parole | 1.026 | 5 | 99,51% |
| Ricerca Spring per tipo, 500 singleton | 1.029 | 519 | 49,56% |
| Riconoscimento Spring per identità | 1.029 | 43 | 95,82% |

Le latenze sono mediane di cinque scansioni dopo riscaldamento, senza avvio JVM, rendering JSON o tracing. Il sistema era condiviso con la pipeline Gradle: la ricerca Spring per tipo misura 86,768 → 94,336 ms nell'esecuzione registrata, mentre gli altri casi migliorano. I conteggi JDWP sono rimasti invariati tra le esecuzioni. Questi risultati non dimostrano una velocità universale su ogni oggetto o debugger remoto.

## Ambiente e pacchetto

Build con Temurin JDK 21.0.8+9 e Gradle 8.14.3; test con JBR 21 dell'SDK IntelliJ. Il criterio Gradle continua a richiedere JDK 21 anche per il daemon. Nell'ambiente cloud, proxy, trust store, preferenze e cache scrivibili dei processi IDE/verifier sono stati configurati con uno script di inizializzazione esterno; nessun percorso dell'ambiente è necessario per compilare il progetto su un normale PC.

Lo ZIP contiene il JAR del plugin, Gson, le annotazioni necessarie e l'indice delle impostazioni. **Nessuna libreria Spring o JUnit è distribuita.** Le impostazioni sono persistenti a livello applicazione e i profili immutabili vengono acquisiti prima dell'esportazione.

SHA-256 dello ZIP verificato:

```text
835aa10c10acb2126ab878e59c2cd6d046411cda0bad67729f27c99dc6e65f3c
```

## Ambito della verifica

Compatibilità binaria verificata con l'IDE indicato, non con tutte le versioni future. I test Swing e l'indicizzazione verificano la pagina delle impostazioni; non è stata eseguita una prova manuale completa nel progetto dell'utente. La lettura di oggetti modificati da thread non sospesi può produrre snapshot incoerenti. **[COPERTURA.md](COPERTURA.md)** descrive layout supportati e marcatori; **[README-OTTIMIZZAZIONI.md](README-OTTIMIZZAZIONI.md)** riepiloga modifiche e impostazioni.

La precedente verifica 1.4.0 e la correzione del launcher Windows Java 27 rimangono disponibili nella storia Git al commit `5b80db2`. La selezione JDK 21 e le istruzioni PowerShell restano nel README e nella guida.
