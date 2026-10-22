# Verifica della versione 1.4.0

Controlli completati il **22 ottobre 2026**.

| Controllo | Esito |
| --- | --- |
| `clean build` | PASS, compilazione e test da cartella build eliminata |
| `buildPlugin` | PASS — `PluginJson-1.4.0.zip` |
| `verifyPluginProjectConfiguration` | PASS |
| `verifyPluginStructure` | PASS |
| IntelliJ Plugin Verifier 1.411 | **Compatible** |
| IDE verificato | IntelliJ IDEA Community 2025.1.4.1, IC-251.27812.49 |
| Suite Gradle | **5 test, 0 fallimenti, 0 errori, 0 saltati** |
| Azioni registrate | Una sola: **Export Object to JSON** |

Comando finale completato con successo dalla cartella `ExportJson/`:

```text
gradle clean build buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
```

Su Windows si usa il wrapper incluso, ad esempio `.\gradlew.bat clean build buildPlugin verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin`.

## Test eseguiti

- `EditorExportTargetResolverTest`: PASS (4.15 s).
- `JavaTypesIntegrationTest`: PASS (3.187 s).
- `RuntimeScannerServiceTest`: PASS (3.889 s).
- `RuntimeValueResolverTest`: PASS (0.581 s).
- `SpringBeanLocatorTest`: PASS (40.616 s).

Le suite JDI avviano vere JVM separate, si fermano a breakpoint e controllano i valori effettivi senza simulare gli oggetti JDI:

- `RuntimeScannerServiceTest`: regressioni della serializzazione originale, tipi standard, contenitori, riferimenti, limiti e oscuramento dei nomi sensibili.
- `JavaTypesIntegrationTest`: tutti gli array primitivi, valori limite/precisione numerica, record/POJO, enum e collezioni/mappe concorrenti, tabelle sparse, subList con grandi offset, wrapper ciclici, Unicode, cache temporali incoerenti, layout sconosciuti e campi JDK nascosti da sottoclassi.
- `RuntimeValueResolverTest`: variabili/campi omonimi, campi privati ereditati/nascosti, ricevitore effettivo, catene di campi, campi statici anche in frame statici, conservazione dei null e selezioni non accessibili.
- `SpringBeanLocatorTest`: applicazione Spring Boot 3.4.5 / Spring Framework 6.2.6, bean/alias/tipi, risoluzione automatica, null, qualifier, FactoryBean e prodotti cache, proxy annidati/frozen/opaque/statici, array, contesti custom/chiusi, cache sparse, campi Spring nascosti e ambiguità. I contatori delle sorgenti proxy controllano che l'export non invochi il codice applicativo.
- `EditorExportTargetResolverTest`: parser e risoluzione simboli Java PSI reali dell'SDK IntelliJ, nomi costanti `@Bean`, FactoryBean automatico, qualifier, nomi binari annidati, campo vs locale, catene di campi, statici e componenti Spring.

Le prove sono state eseguite anche separatamente durante l'analisi. La build finale conferma l'esecuzione con il runner Gradle e le estensioni JUnit automatiche dell'SDK IntelliJ.

## Miglioramenti verificati

La revisione ha corretto perdita di elementi in set immutabili sparsi, offset di subList, chiavi null/duplicate a identità, booleani atomici esportati come interi, riferimenti ai payload Optional/AtomicReference, false letture da campi omonimi, cache Spring sparse e selezioni di oggetti errate per omonimia. Sono stati aggiunti budget di testo/valori e lettori per ulteriori tipi Java. Le ricerche Spring incomplete o ambigue non vengono trasformate in scelte arbitrarie.

La generazione degli indici delle impostazioni è disabilitata con l'opzione Gradle corretta per l'intera pipeline, perché il plugin espone azioni nell'editor. La build pulita ha verificato che nessuna task richieda una vecchia cartella generata. I file JSON vengono riservati atomicamente con nomi univoci per evitare sovrascritture tra esportazioni simultanee.

## Limiti della verifica

Compilazione con JDK 21 e test Gradle con JBR 21 dell'SDK IntelliJ. Spring è usato esclusivamente nei test; lo ZIP distribuito non contiene librerie Spring o JUnit. Il certificato/proxy necessari nell'ambiente cloud sono stati forniti ai processi di build senza inserire percorsi dell'ambiente nel progetto.

La compatibilità dichiarata parte da IDEA 2025.1; il controllo binario riguarda la versione riportata sopra, non tutte le versioni future. Non è stata eseguita una prova manuale interattiva nel progetto dell'utente. I test PSI verificano i simboli e il manifest verifica l'unica voce di menu; non sostituiscono una prova manuale completa dell'interfaccia.

Nessun test può dimostrare la conversione universale di ogni classe Java, layout JDK, estensione Spring o stato concorrente. `COPERTURA.md` descrive i casi supportati e quelli esclusi; non vengono applicati getter, Jackson, regole ORM o tutte le regole di selezione DI di Spring.

SHA-256 dello ZIP installabile:

```text
c7755f91790ccd658e2a923361d31ee5acf2955e5eb25458b166c5bac7a71f39
```

## Correzione della selezione JDK per Gradle

Il log Windows successivo alla prima pubblicazione mostra `IllegalArgumentException: 27.0.1` in `org.jetbrains.kotlin...JavaVersion.parse`, durante la valutazione degli script Kotlin e prima della compilazione del plugin. Il daemon era quindi avviato con Java 27, mentre questa build richiede JDK 21.

Aggiunto `ExportJson/gradle/gradle-daemon-jvm.properties` con `toolchainVersion=21`, senza percorsi legati a un PC né un vendor obbligatorio. JDK 21 deve essere installato localmente; non è configurato il download automatico di JDK. README e guida contengono i comandi PowerShell per selezionare JDK 21 anche nel launcher.

Verifica mirata della correzione: `gradle --version` riconosce il criterio Java 21; `gradle --offline --no-daemon help` termina con **BUILD SUCCESSFUL** e il log del daemon conferma `javaVersion=21`. Non sono stati modificati sorgenti o test del plugin e non è stata rieseguita la suite completa per questa modifica di avvio. Il problema Windows è stato diagnosticato dal log fornito; la verifica locale è avvenuta su Linux/JDK 21.
