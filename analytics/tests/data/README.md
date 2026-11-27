# Sample files

`sample-trades.csv` and `sample-records.csv` were written by the venue's own tools from a journal of 300
commands (`generate-sample.jsh`). To regenerate them from the repository root:

```bash
./mvnw -q install -DskipTests
CP=engine-core/target/engine-core-0.1.0-SNAPSHOT.jar:engine-journal/target/engine-journal-0.1.0-SNAPSHOT.jar:post-trade/target/post-trade-0.1.0-SNAPSHOT.jar
rm -rf target/sample && jshell --class-path "$CP" analytics/tests/data/generate-sample.jsh
java -cp "$CP" io.github.ahmedberrada.lob.posttrade.PostTradeTool trades target/sample/AAPL --tick-size 0.01 \
    --fees maker=-0.2,taker=0.3,auction=0.1 > analytics/tests/data/sample-trades.csv
java -cp "$CP" io.github.ahmedberrada.lob.journal.RecordsExport target/sample/AAPL > analytics/tests/data/sample-records.csv
```
