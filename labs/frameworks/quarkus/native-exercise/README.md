# Quarkus Native Image optional exercise

これは章クリアや★の分母に含めない、任意の発展演習です。先にJVMモードのproject/runtime演習を完了し、
cold startまたは初期メモリが要件上の問題である場合に実行してください。

## 事前準備

Docker daemonに加え、次の公式Quarkus UBI 9 imageを手動で取得します。採点は自動pullしません。

```bash
docker pull quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21
docker pull quay.io/quarkus/ubi9-quarkus-micro-image:2.0
```

提出するとcontainer buildでLinux Native executableを作り、UBI 9 micro imageへ格納します。
そのcontainerを動的localhost portで起動し、REST、Health、停止を確認します。初回buildは数分かかり、
十分なCPU・メモリ・空き容量が必要です。JVM版とbuild時間、起動時間、RSS、定常性能を同じ条件で比較し、
Native採用を自動的な正解としないでください。
