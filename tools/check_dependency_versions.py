"""labs が指定している版と、JDKのsecurity baselineを、公開情報と突き合わせる（レビュー08-14の §8.4）。

tools/check-dependency-versions.sh から呼ばれる。

  python3 tools/check_dependency_versions.py           … 突き合わせて表示する
  python3 tools/check_dependency_versions.py --offline … 宣言だけ一覧する（通信しない）

## なぜ必要か

`ch46`〜`ch63` の製品章は、Spring Boot・Quarkus・Open Liberty・Jakarta EE・PostgreSQLの
実物を扱う。**製品の版は教材の外で動くので、放っておくと教材だけが古くなる**。
レビューの §8.4 は「少なくとも四半期またはJava CPU/製品minor更新時に再確認したい」と書いていたが、
手で確認する手順しか無かった。ここではそれを機械にする。

## 何と突き合わせるか

  Mavenの依存 … Maven Central の `maven-metadata.xml`（権威ある一覧。検索APIは古いことがある）
  JDKのbaseline … Adoptium API の最新GA（Oracleのroadmapページは 403 で取得できない）

## 判定

  ★実在しない … 宣言した版が Central に無い。**ビルドが落ちる**ので失敗にする
  ・系統内で古い … 同じminor系列に新しいpatchがある。上げるかは人が決める（警告）
  ・最新       … その系統では最新。ただし**別のlabがもっと新しい系統を使っていることは別に警告する**
                  （系統をまたぐ判定はしない。minorを上げるかは教材の都合で決めるため）

**版を上げたら必ずそのlabをビルドして確かめること。** この道具は「新しい版がある」ことしか言えず、
上げて動くかは別問題である（Docker・実DB・実サーバが要るlabは、環境のある場所で確かめる）。

このツールだけは**ネットワークを使う**（ほかの検査はすべてオフラインで数秒）。
通信できないときは宣言の一覧だけ出して、失敗にはしない。
"""
import glob
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

UA = {'User-Agent': 'java-learning-version-audit/1.0'}
CENTRAL = 'https://repo1.maven.org/maven2/{path}/maven-metadata.xml'
ADOPTIUM = 'https://api.adoptium.net/v3/assets/latest/{feature}/hotspot?vendor=eclipse'

# labs の pom で版を直書きしている依存・プラグインのうち、外の製品に追随すべきもの。
# groupId:artifactId → 表示名
WATCH = {
    'org.springframework.boot:spring-boot-starter-parent': 'Spring Boot',
    'io.quarkus.platform:quarkus-bom': 'Quarkus platform (LTS系)',
    'io.openliberty.tools:liberty-maven-plugin': 'Open Liberty Maven plugin',
    'jakarta.platform:jakarta.jakartaee-api': 'Jakarta EE API',
    'org.eclipse.microprofile.health:microprofile-health-api': 'MicroProfile Health',
    'org.postgresql:postgresql': 'PostgreSQL JDBCドライバ',
    'org.junit.jupiter:junit-jupiter': 'JUnit Jupiter',
    'org.apache.maven.plugins:maven-surefire-plugin': 'Maven Surefire',
    'org.apache.maven.plugins:maven-compiler-plugin': 'Maven Compiler',
    'org.apache.maven.plugins:maven-war-plugin': 'Maven WAR',
}
PRERELEASE = re.compile(r'CR|RC|-M\d|alpha|beta|SNAPSHOT', re.I)


def main():
    offline = '--offline' in sys.argv[1:]
    poms = sorted(glob.glob('labs/**/pom.xml', recursive=True))
    declared = collect_declared()
    baseline = collect_jdk_baseline()

    print(f'labs の pom {len(poms)}件を読み、追随すべき宣言 {len(declared)}件を見つけました'
          f'（同じ依存を違う版で宣言している場合は別に数えます）。')
    if offline:
        for d in sorted(declared, key=lambda x: (x['name'], as_numbers(x['version']))):
            print(f'  {d["name"]:34} {d["version"]:12} {" ".join(d["files"])}')
        print(f'\nJDKのsecurity baseline: {" / ".join(baseline)}（tools/build.sh）')
        return 0

    problems = []
    behind = []
    print()
    for d in sorted(declared, key=lambda x: (x['name'], as_numbers(x['version']))):
        versions = fetch_versions(d['coord'])
        if versions is None:
            print(f'  {d["name"]:34} {d["version"]:12} 取得できません（通信）')
            continue
        if d['version'] not in versions:
            problems.append(f'{d["name"]} が宣言する {d["version"]} は Maven Central にありません'
                            f'（{" ".join(d["files"])}）')
            print(f'  {d["name"]:34} {d["version"]:12} ★実在しない')
            continue
        newest = newest_in_line(versions, d['version'])
        if newest == d['version']:
            print(f'  {d["name"]:34} {d["version"]:12} 最新')
        else:
            behind.append((d, newest))
            print(f'  {d["name"]:34} {d["version"]:12} ・系統内で古い → {newest}'
                  f'  {" ".join(d["files"])}')

    print()
    for feature, want in zip((21, 25, 26), baseline):
        latest = fetch_jdk(feature)
        if latest is None:
            print(f'  JDK {feature} の最新GA: 取得できません（通信）')
        elif latest == want:
            print(f'  JDK {feature} の baseline {want:10} 最新GAと一致')
        else:
            behind.append(({'name': f'JDK {feature} baseline', 'version': want,
                            'files': ['tools/build.sh']}, latest))
            print(f'  JDK {feature} の baseline {want:10} ・古い → {latest}')

    drift = split_lines(declared)
    if drift:
        print(f'\nlab のあいだで系統がそろっていないもの {len(drift)}件。')
        print('  （それぞれの系統では最新なので上の表では「最新」と出ます。'
              'そろえるかは教材の都合で決めます）')
        for name, versions in drift:
            print(f'  {name}: ' + ' / '.join(versions))

    if behind:
        print(f'\n新しい版が出ているもの {len(behind)}件。上げるかは人が決めます。')
        print('**上げたら必ずそのlabをビルドして確かめてください**'
              '（この道具は動くかどうかは見ていません）。')
    if problems:
        print(f'\n直す箇所が{len(problems)}件あります。', file=sys.stderr)
        for line in problems:
            print(f'  {line}', file=sys.stderr)
        return 1
    if not behind:
        print('\nすべて系統内の最新です。')
    return 0


def split_lines(declared):
    """同じ依存を、違う minor 系統で宣言している lab を見つける。

    系統内の比較しかしないので、`5.11.4` は 5.11 系では最新でも、別の lab が `5.13.4` を
    使っていると気づけない。**「すべて最新」の表示がその食い違いを隠す**ため、別に出す。
    """
    by_name = {}
    for d in declared:
        by_name.setdefault(d['name'], set()).add(d['version'])
    out = []
    for name, versions in sorted(by_name.items()):
        lines = {tuple(as_numbers(v)[:2]) for v in versions}
        if len(lines) > 1:
            out.append((name, sorted(versions, key=as_numbers)))
    return out


def collect_declared():
    """labs の pom から、追随すべき版の宣言を集める。"""
    found = {}
    for path in sorted(glob.glob('labs/**/pom.xml', recursive=True)):
        text = pathlib.Path(path).read_text(encoding='utf-8')
        properties = properties_of(path)
        for group, artifact, version in re.findall(
                r'<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*'
                r'<version>([^<]+)</version>', text):
            record(found, path, resolve(group, properties), resolve(artifact, properties),
                   resolve(version, properties))
        # 改行やタグの並びが違う書き方も拾う
        for block in re.findall(r'<(?:dependency|plugin|parent)>(.*?)</(?:dependency|plugin|parent)>',
                                text, re.S):
            g = re.search(r'<groupId>([^<]+)</groupId>', block)
            a = re.search(r'<artifactId>([^<]+)</artifactId>', block)
            v = re.search(r'<version>([^<]+)</version>', block)
            if g and a and v:
                record(found, path, resolve(g.group(1), properties),
                       resolve(a.group(1), properties), resolve(v.group(1), properties))
    return list(found.values())


def record(found, path, group, artifact, version):
    """同じ依存を違う版で宣言している lab があるので、(依存, 版) ごとに1件持つ。"""
    if group is None or artifact is None:
        return
    coord = f'{group}:{artifact}'
    if coord not in WATCH or version is None or PRERELEASE.search(version):
        return
    found.setdefault((coord, version), {'coord': coord, 'name': WATCH[coord],
                                        'version': version, 'files': []})
    files = found[(coord, version)]['files']
    if path not in files:
        files.append(path)


PROPERTIES_BLOCK = re.compile(r'<properties>(.*?)</properties>', re.S)
PROPERTY = re.compile(r'<([\w.\-]+)>([^<]*)</\1>')


def properties_of(path):
    """その pom と、上の階層の pom で定義されているプロパティを集める。

    `${surefire-plugin.version}` のように**親で定義して子で使う**書き方があるため、
    同じファイルだけを見ると解決できない。`<properties>` の中は名前を絞らずに拾う——
    `${quarkus.platform.artifact-id}` のように**版以外もプロパティで書く** pom があり、
    `.version` で終わる名前だけを集めると宣言そのものを見落とす。
    """
    properties = {}
    current = pathlib.Path(path).parent
    chain = []
    while True:
        candidate = current / 'pom.xml'
        if candidate.exists():
            chain.append(candidate)
        if current == pathlib.Path('labs') or current.parent == current:
            break
        current = current.parent
    for pom in reversed(chain):          # 上の階層を先に入れ、近いほうで上書きする
        text = pom.read_text(encoding='utf-8')
        for block in PROPERTIES_BLOCK.findall(text):
            properties.update(dict(PROPERTY.findall(block)))
    return properties


def resolve(version, properties):
    """`${…}` を展開する。展開できない版は検査できないので None を返して対象から外す。"""
    m = re.fullmatch(r'\$\{([\w.\-]+)\}', version.strip())
    if not m:
        return version.strip()
    return properties.get(m.group(1))


def collect_jdk_baseline():
    text = pathlib.Path('tools/build.sh').read_text(encoding='utf-8')
    m = re.search(r'baselineは JDK ([\d.]+) / ([\d.]+) / ([\d.]+)', text)
    return list(m.groups()) if m else []


def fetch_versions(coord):
    group, artifact = coord.split(':')
    url = CENTRAL.format(path=f"{group.replace('.', '/')}/{artifact}")
    try:
        xml = urllib.request.urlopen(
            urllib.request.Request(url, headers=UA), timeout=25).read().decode('utf-8')
    except (urllib.error.URLError, TimeoutError, OSError):
        return None
    return re.findall(r'<version>([^<]+)</version>', xml)


def newest_in_line(versions, current):
    """同じ minor 系列（3.12.x など）の中でいちばん新しい版。

    `maven-metadata.xml` の並びは必ずしも昇順でなく、`3.33.3.1` のような4桁の版もあるので、
    数の列として比べる。
    """
    parts = current.split('.')
    line = '.'.join(parts[:2]) + '.' if len(parts) >= 3 else current
    same = [v for v in versions if v.startswith(line) and not PRERELEASE.search(v)]
    return max(same, key=as_numbers) if same else current


def as_numbers(version):
    """`3.33.3.1` を (3, 33, 3, 1) にする。数でない断片は0として扱う。"""
    out = []
    for part in version.split('.'):
        digits = re.match(r'\d+', part)
        out.append(int(digits.group()) if digits else 0)
    return tuple(out)


def fetch_jdk(feature):
    try:
        data = json.loads(urllib.request.urlopen(
            urllib.request.Request(ADOPTIUM.format(feature=feature), headers=UA),
            timeout=25).read().decode('utf-8'))
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return None
    names = {a.get('release_name', '') for a in data}
    versions = sorted(n.removeprefix('jdk-').split('+')[0] for n in names if n)
    return versions[-1] if versions else None


if __name__ == '__main__':
    sys.exit(main())
