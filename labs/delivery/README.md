# CI・container・releaseラボ

## 必要なもの

- JDK 21以降（`ContainerApp` を直接動かすだけならこれだけ）
- コンテナ部分は Docker または Podman
- `ci-maven.yml` を試すときは GitHub Actions が動くリポジトリ

> `ContainerApp` の直接起動は JDK 21.0.8 (IBM Semeru) / macOS 15 で動作確認。コンテナのビルドと実行はこの環境で未確認です。

`ci-maven.yml` は教材用のworkflow例なので、リポジトリ直下の `.github/workflows` には
置いていません。自分のプロジェクトへ移すときはJDKのdistribution/version、Maven wrapper、
権限、cache keyを確認します。

## まずコンテナなしで動かす

`ContainerApp` はJDKだけで起動します。コンテナの前に、アプリ単体の挙動を見ておきます。

```bash
javac --add-modules jdk.httpserver ContainerApp.java
java --add-modules jdk.httpserver ContainerApp
```

別のターミナルで確認します。

```bash
curl http://localhost:8080/health/ready
```

### 成功したらこう出る

```
READY
```

`Ctrl+C`（SIGTERM）を送ると、新規受付を止めてから終了します。

## コンテナで動かす

`ContainerApp.java` と `Dockerfile` は、非root、read-onlyを前提にした最小例です。
Podmanなら `docker` を `podman` に読み替えられます。

```bash
docker build -t java-cafe-container-lab .
docker run --rm --read-only --tmpfs /tmp --memory=256m --cpus=1 -p 8080:8080 java-cafe-container-lab
curl http://localhost:8080/health/ready
docker stop --time=10 <container-id>
```

`curl` が `READY` を返し、`docker stop` が10秒の猶予内に終わることが目安です。

## 確認すること

- SIGTERM後に新規受付を止めて期限内に終了するか（`docker stop --time=10` が
  タイムアウトせず終わるか）
- memory limit内でheap、metaspace、thread、direct memoryが収まるか
  （`--memory=256m` で `-Xmx256m` を指定すると落ちやすい。アプリ内のレッスン
  「コンテナとJVMの資源を合わせる」と同じ話です）
- image scanとSBOMがbase image/JDK/application依存を列挙するか
- 同じimageを設定だけ変えてstageからproductionへ昇格できるか
