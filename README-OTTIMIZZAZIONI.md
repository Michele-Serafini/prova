# Ottimizzazioni e impostazioni — Runtime Object to JSON 1.5.0

La versione 1.5.0 riduce soprattutto le richieste alla JVM sospesa: ogni richiesta JDI/JDWP aggiunge latenza, anche quando il JSON finale è piccolo. Il confronto usa il commit `5b80db247543c0e4800321e69f9ab26a51f2281c` come riferimento.

## Modifiche al codice

- **Oggetti Java:** una lettura JDI raggruppata per i campi esportabili di un POJO; nomi, collisioni tra campi ereditati, metadati e gerarchie vengono calcolati una volta per tipo durante la scansione. I campi sensibili e sintetici sono esclusi dalle richieste dei valori.
- **Contenitori e numeri:** letture degli array in blocchi da 256, anche per bucket delle mappe sparse, strutture EnumSet e parole della magnitudine BigInteger. I campi di storage JDK vengono letti insieme e conservati in una piccola cache con dimensione limitata.
- **Budget:** interruzione della visita quando si esaurisce il limite dei valori, evitando altre letture e una sequenza inutile di marcatori. Le cache vengono svuotate al termine, anche in caso di errore; una nuova esportazione legge nuovamente i valori effettivi.
- **Spring:** letture raggruppate di contesti, cache e nodi; riuso dei metadati e dei target proxy nella singola operazione; arresto anticipato della ricerca per identità. I passaggi nome → verifica del tipo → fallback condividono i dati della stessa risoluzione. Le ricerche per nome e tipo continuano a verificare le ambiguità.
- **File e interfaccia:** Gson scrive direttamente su un writer UTF-8 con buffer, evitando una seconda copia completa del JSON formattato. Il refresh VFS è asincrono. Restano i nomi riservati atomicamente, senza sovrascrivere esportazioni simultanee.

Le cache non attraversano esportazioni o riprese del debugger. Restano la risoluzione dei campi secondo il simbolo selezionato, i riferimenti `$ref`, la redazione e i marcatori espliciti. Nessun getter, `toString()`, metodo Spring o altro codice dell'applicazione viene invocato per ricostruire i dati.

## Limiti configurabili

Aprire **Settings → Tools → Runtime Object to JSON**. I profili **Ordinary values** e **Spring beans** sono indipendenti. Premere **Apply** per salvare; le nuove impostazioni si applicano dall'esportazione successiva e persistono al riavvio dell'IDE.

| Impostazione | Ordinario iniziale | Spring iniziale |
| --- | ---: | ---: |
| Profondità massima | 40 | 16 |
| Elementi per array/collezione/mappa | 10.000 | 2.000 |
| Oggetti distinti per snapshot | 50.000 | 20.000 |
| Valori visitati per snapshot | 200.000 | 80.000 |
| Caratteri di testo per snapshot | 5.000.000 | 2.000.000 |
| Caratteri per stringa | 100.000 | 100.000 |
| Capacità delle tabelle sparse / nodi collegati ispezionati | 100.000 | 100.000 |

Il limite delle tabelle sparse riguarda i layout interni supportati; gli array compatti seguono il limite degli elementi. Il testo è misurato in unità UTF-16 e non coincide con la dimensione del file, che contiene anche struttura JSON, escaping, indentazione e marcatori.

La scheda **Spring lookup** configura contesti per classe (64), entry per cache (100.000), bucket per cache (1.048.576), profondità delle catene proxy durante la ricerca (32) e salti alias (1.024). La profondità di scansione dello snapshot si imposta nei profili separati.

**Restore Defaults** ripristina i valori nel modulo; Apply li salva. Valori fuori intervallo, testo non numerico e decimali vengono rifiutati senza applicare parzialmente le impostazioni. Una configurazione persistita danneggiata recupera i valori predefiniti per i soli campi invalidi. I massimi ammessi compaiono nei suggerimenti dei controlli; la profondità è limitata a 128 per contenere la ricorsione. Limiti più bassi riducono il lavoro, quelli più alti consentono snapshot più grandi con maggiore costo di tempo e memoria.

## Errori corretti durante la verifica

- Stringhe prodotte da UUID, date, caratteri e valori numerici non finiti rispettano ora i budget di testo e lunghezza.
- Le letture dei layout JDK escludono campi aggiunti da sottoclassi applicative; continuano a distinguere correttamente i campi nascosti.
- Il limite alias conta i salti effettivi: il valore 1 consente un alias e rifiuta una catena di due.
- Reset ripristina anche il testo invalido di uno spinner il cui valore numerico non era cambiato.
- L'inserimento di `1.5` viene rifiutato: l'editor Swing standard poteva convertirlo silenziosamente in `1`.
- I test mantengono le preferenze Java in una directory della build, senza dipendere da una home scrivibile.

## Misure riproducibili e verifiche

I risultati completi sono in **[ExportJson/tools/benchmark/RESULTS-2026-10-23.txt](ExportJson/tools/benchmark/RESULTS-2026-10-23.txt)**. Il benchmark usa vere JVM JDK 21 sospese tramite JDI, lo stesso oggetto per entrambe le implementazioni, due riscaldamenti e la mediana di cinque scansioni. Avvio JVM, rendering del JSON e tracciamento sono esclusi dal tempo misurato. Una scansione separata conta le richieste effettive JDWP. I quattro JSON devono coincidere esattamente e hanno un hash SHA-256; le ricerche Spring devono restituire lo stesso oggetto atteso.

Le richieste al debugger sono diminuite da **6.002 a 502** per 500 oggetti da 12 campi, da **1.921 a 21** per 20 oggetti da 96 campi, da **16.389 a 67** per una mappa sparsa di 16.384 bucket e da **1.026 a 5** per un BigInteger da 1.024 parole. Per la fixture Spring con 500 singleton, la ricerca per tipo passa da **1.029 a 519** richieste; quella per identità da **1.029 a 43**.

Le latenze dipendono dalla struttura, dal trasporto del debugger e dalla macchina: consultare il rapporto per i tempi della singola esecuzione. Il miglioramento dei casi misurati non è una garanzia di un fattore universale per qualsiasi progetto.

Nell'esecuzione finale, i 500 POJO passano da 204,261 a 16,698 ms e la mappa sparsa da 446,387 a 3,046 ms. La ricerca Spring per tipo misura 86,768 → 94,336 ms sotto carico condiviso, pur riducendo le richieste del 49,56%; il test non dimostra un'accelerazione temporale uniforme per questo percorso. Le riduzioni dei comandi JDWP sono risultate costanti tra le esecuzioni.

Per ripetere il confronto, dalla cartella `ExportJson`, fornire JDK 21 e i JAR delle dipendenze:

```sh
python3 tools/benchmark/run.py --java-home /percorso/jdk-21 \
  --classpath '/percorso/dipendenze/*' --spring
```

**[Istruzioni benchmark](ExportJson/tools/benchmark/README.md)** elenca dipendenze, comando PowerShell e metodo. I test di regressione aggiungono budget deterministici delle chiamate JDI, persistenza XML delle impostazioni, validazione/apply/reset Swing, scritture UTF-8 concorrenti e limiti Spring con ripresa della JVM.

La build completa, il pacchetto e la verifica binaria dell'IDE sono documentati in **[VERIFICA.md](VERIFICA.md)**. L'installabile è `ExportJson/build/distributions/PluginJson-1.5.0.zip`. Rimangono i limiti dei layout descritti in **[COPERTURA.md](COPERTURA.md)**; non è stata eseguita una prova interattiva nel progetto dell'utente.
