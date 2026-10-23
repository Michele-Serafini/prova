# Obj2Json 1.4.0 — un solo comando, anche per Spring Boot

## Installazione e uso

In IntelliJ IDEA aprire **Settings → Plugins → ingranaggio → Install Plugin from Disk...**, selezionare `PluginJson-1.4.0.zip` e riavviare se richiesto.

1. Avviare l'applicazione in **Debug**.
2. Fermarsi a un breakpoint dopo l'inizializzazione dell'oggetto. Per cercare i bean dal contesto, attendere l'avvio di Spring.
3. Selezionare il nome di una variabile/campo oppure posizionare il cursore sul suo identificatore.
4. Fare clic destro → **Export Object to JSON**.
5. Il risultato viene salvato in `JSON/` nella cartella del progetto.

Esiste un'unica voce di menu. Non è necessario scegliere una modalità Spring.

## Come viene scelto l'oggetto

Il comando legge prima l'istanza effettiva dal frame selezionato: variabili locali, campi di `this`, campi ereditati/privati, campi statici e catene semplici come `ordine.cliente`. Il simbolo risolto nell'editor permette di distinguere `this.cliente` da una variabile locale omonima e i campi nascosti nelle superclassi.

Un riferimento che vale `null` viene esportato come JSON `null`. Non viene sostituito con un'altra istanza trovata in Spring. Se il ricevitore di una catena è null oppure richiede una chiamata a metodo, viene mostrato un messaggio: il plugin non esegue codice nell'applicazione sospesa.

Quando selezioni il nome di un metodo `@Bean`, il comando legge il singleton corrispondente dal contesto attivo. Ad esempio:

```java
@Configuration
class ExportConfiguration {
    static final String EXPORT_NAME = "datiCliente";

    @Bean(name = {EXPORT_NAME, "clienteAlias"})
    Cliente creaCliente() {
        return new Cliente("Mario", List.of("ADMIN", "USER"));
    }
}
```

Seleziona **creaCliente** e usa **Export Object to JSON**: viene risolto il nome `datiCliente`, comprese le costanti Java e gli alias. Senza nome esplicito viene usato il nome del metodo. Se il ritorno dichiarato implementa `FactoryBean`, il comando riconosce il caso ed esporta automaticamente la factory restituita; il prodotto già creato si può esportare dal suo riferimento effettivo.

Puoi anche selezionare il nome di una classe componente Spring, ad esempio una classe `@Service`: viene cercato un singleton compatibile con quel tipo. Quando un campo/parametro iniettato non è disponibile nel frame, il comando può usare il nome registrato di `@Qualifier`/`@Resource` oppure il tipo, se non ambiguo. I qualifier personalizzati basati su metadati diversi dal nome del bean non vengono ricostruiti; il riferimento già iniettato si può comunque esportare direttamente.

## Cosa fa con Spring

- Riconosce automaticamente bean già creati e proxy Spring, anche annidati.
- Cerca nei contesti attivi, inclusi quelli con nomi di classe personalizzati; ignora i contesti chiusi.
- Risolve nomi, alias e tipi compatibili; segnala le ambiguità invece di scegliere una delle istanze.
- Legge i prodotti dei `FactoryBean` soltanto se già nella cache dei singleton.
- Evita di attraversare l'intero ApplicationContext, BeanFactory o Environment, anche se incontrati dentro un normale oggetto.

Non chiama `getBean()`, `getObject()`, getter, `toString()` o serializer Jackson. Non inizializza bean lazy, prototype o scoped. I target proxy non leggibili senza eseguire codice sono indicati con `$skipped`.

## Impostazioni e prestazioni

Aprire **Settings → Tools → Runtime Object to JSON**. La scheda **Spring beans** permette di impostare i budget dell'esportazione Spring; **Ordinary values** mantiene valori indipendenti per gli altri oggetti. **Spring lookup** regola ricerca dei contesti, cache, profondità proxy e salti alias. Le modifiche si applicano con **Apply** dall'esportazione successiva e restano salvate dopo il riavvio. **Restore Defaults** ripristina i valori iniziali nel modulo; premere Apply per salvarli.

Ridurre elementi, profondità e valori visitati limita il lavoro del debugger. Aumentarli permette snapshot più ampi ma può richiedere più tempo e memoria. Le letture raggruppate e le cache della versione 1.5 sono descritte con misure riproducibili in **README-OTTIMIZZAZIONI.md**.

## Copertura e limiti

Non è possibile garantire una conversione completa per tutte le classi Java o per ogni estensione Spring. Il JSON rappresenta i campi reali in memoria e può differire dal JSON delle API REST dell'applicazione. Strutture JDK note ricevono una rappresentazione leggibile; layout sconosciuti e risorse come thread, connessioni, stream o stato nativo non equivalgono a normali dati JSON.

Vedere `COPERTURA.md` per i casi coperti e i limiti, e `VERIFICA.md` per gli esiti effettivi dei test. I cicli usano `$ref`; dati esclusi, errori o limiti sono segnalati esplicitamente. Restano attivi l'oscuramento dei nomi sensibili e i limiti sulle esportazioni.

Per compilare serve **JDK 21**. Il plugin dichiara **IntelliJ IDEA 2025.1+**; la versione effettivamente verificata è specificata in `VERIFICA.md`. Non serve il plugin Spring a pagamento di IntelliJ.

## Errore di build `27.0.1` su Windows

Questo errore, con `JavaVersion.parse` nello stack trace, indica che Gradle sta valutando gli script Kotlin con Java 27. Per questa versione del progetto usare **JDK 21**. La versione target dei sorgenti non seleziona la JVM con cui Gradle viene avviato.

Il repository vincola il daemon a JDK 21 con `ExportJson/gradle/gradle-daemon-jvm.properties`. JDK 21 deve essere installato localmente; il progetto non configura il suo download automatico.

Da PowerShell nella cartella `ExportJson`, sostituire il percorso d'esempio con la propria installazione di JDK 21:

```powershell
$env:JAVA_HOME = 'C:\percorso\del\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat --version
.\gradlew.bat clean build --stacktrace
```

`JAVA_HOME` deve indicare la cartella che contiene `bin\java.exe`. Controllare che `--version` riporti Java 21 per launcher e daemon. In IntelliJ selezionare anche JDK 21 come **Gradle JVM** nelle impostazioni Gradle. Per risolvere questo errore non serve cancellare le cache o modificare le opzioni di accesso nativo.
