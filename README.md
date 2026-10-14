# SkyCases Ultimate — Paper 1.21.4 (wersja do testów)

## Budowanie
Java 21 i Gradle 8.10+; `gradle shadowJar` (wymaga pobrania Paper API i SQLite JDBC). Wynik w `build/libs/`. Nie ma zweryfikowanego pliku JAR.

## Instalacja
1. Zbuduj JAR, umieść w `plugins/` Paper 1.21.4.
2. Osobny `SkyCases-1.21.4-TexturePack.zip` opublikuj jako server-resource-pack lub zainstaluj po stronie klienta.
3. Gracze muszą zaakceptować paczkę, żeby zobaczyć ozdobną ramkę GUI. Nie podmienia standardowych skrzynek.

## Szybki test
- `/case create vip`
- Trzymając nagrodę: `/case reward add vip miecz 25`
- `/case set vip` (spójrz na blok)
- `/case givekey <gracz> vip 3`
- LPM/PPM na przypisanym bloku, następnie slot 48 lub 50.
- `/case admin` — przełączniki; `/case edit vip` — edycja.
- `/case gui vip animated-slot 48` oraz `/case gui vip instant-slot 50`

## Ważne ograniczenia
To **nie jest potwierdzona wersja produkcyjna**: brak kompilacji/testów Paper w tym środowisku. Nieukończone: pełny edytor dowolnych slotów i tekstur, modele 3D, MySQL, fizyczne klucze, pity/multi-open oraz odporne na każdą awarię wydawanie nagród. Bez handlu kluczami i eventów, zgodnie z ustaleniem. Tekstura jest samodzielną pixel-artową interpretacją, nie wiernym eksportem wcześniejszej makiety AI.
