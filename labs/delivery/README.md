# CI・container・releaseラボ

`ci-maven.yml` は教材用のworkflow例なので、repository直下の `.github/workflows` には置いていません。
自分のprojectへ移すときはJDK distribution/version、Maven wrapper、権限、cache keyを確認します。

`ContainerApp.java` と `Dockerfile` は、非root、read-onlyを前提にした最小例です。

```bash
docker build -t java-cafe-container-lab .
docker run --rm --read-only --tmpfs /tmp --memory=256m --cpus=1 -p 8080:8080 java-cafe-container-lab
curl http://localhost:8080/health/ready
docker stop --time=10 <container-id>
```

観察するもの:

- SIGTERM後に新規受付を止めて期限内に終了するか
- memory limit内でheap、metaspace、thread、direct memoryが収まるか
- image scanとSBOMがbase image/JDK/application依存を列挙するか
- 同じimageを設定だけ変えてstageからproductionへ昇格できるか

