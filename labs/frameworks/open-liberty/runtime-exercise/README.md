# Open Liberty runtime exercise

`server.xml`、Jakarta REST Resource、MicroProfile Readinessを直し、実WARをOpen Libertyへ配備する演習です。
固定スクリプトが動的localhostポートでサーバーを起動し、Feature起動ログ、REST、Validation、Health、停止を検証します。

`RuntimeProbe.java`と`run-runtime-lab.sh`は採点手順を固定する参照専用ファイルです。
