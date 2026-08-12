/*
 * Java標準ライブラリの小さな辞書。
 *
 * コード補完（complete.js）が「このクラスにはどんなメンバがあるか」を引くための表。
 * JDK全部は入れていない。この教材（content/）に実際に出てくるものと、
 * 初心者がよく使うものだけに絞ってある。
 *
 * ── メンバの書き方 ─────────────────────────────────────────────────
 *     'println(String x):void|1行出力して改行する'
 *      ~~~~~~~ ~~~~~~~~~~ ~~~~ ~~~~~~~~~~~~~~~~~~
 *      名前    引数        戻り値 説明
 *
 *   メソッドは `()` を書く。フィールドは `out:PrintStream|標準出力` のように
 *   `()` を書かない。この違いで補完時に `()` を付けるかを決めている。
 *
 *   戻り値の型は補完の連鎖に使う。`list.get(0).` で String のメソッドを
 *   出せるのは `get(int index):E` と書いてあるから。
 *   `E` `K` `V` `T` `U` `R` は型引数で、`List<String>` の `String` に置き換わる。
 *
 *   説明に `:` と `|` は使えない（区切り文字なので）。
 *
 * ── クラスの書き方 ─────────────────────────────────────────────────
 *   doc … 一言説明。候補の下に出る
 *   tp  … 型引数の名前。`'E'` や `'K,V'`
 *   ext … 親。インスタンスメンバを引き継ぐ（書かなければ Object を引き継ぐ）
 *   s   … staticメンバ。`Math.max` のようにクラス名から呼ぶもの。継承しない
 *   i   … インスタンスメンバ。変数から呼ぶもの
 */
(function (global) {
  'use strict';

  // ── パッケージ ────────────────────────────────────────────────────
  // import文の補完に使う。ここに載っているクラス名だけが `import java.u…`
  // から出てくる。java.lang はimport不要だが、説明に出す都合で載せてある。
  var PACKAGES = {
    'java.lang': 'String StringBuilder Integer Long Double Float Boolean Character Byte Short ' +
      'Math System Object Class Thread Runnable Comparable Iterable Number Enum Record ' +
      'Exception RuntimeException Error Throwable IllegalArgumentException IllegalStateException ' +
      'NullPointerException NumberFormatException ArithmeticException IndexOutOfBoundsException ' +
      'ArrayIndexOutOfBoundsException StringIndexOutOfBoundsException UnsupportedOperationException ' +
      'ClassCastException ClassNotFoundException InterruptedException StackOverflowError OutOfMemoryError ' +
      'AutoCloseable CharSequence Override Deprecated FunctionalInterface SuppressWarnings',
    'java.lang.annotation': 'Retention RetentionPolicy Target ElementType Annotation Documented Inherited',
    'java.lang.reflect': 'Method Field Constructor Modifier InvocationTargetException',
    'java.util': 'Scanner List ArrayList LinkedList Map HashMap LinkedHashMap TreeMap Set HashSet ' +
      'LinkedHashSet TreeSet Deque ArrayDeque Queue PriorityQueue Collection Collections Arrays ' +
      'Iterator Comparator Objects Optional OptionalInt OptionalDouble Random UUID StringJoiner ' +
      'NoSuchElementException ConcurrentModificationException InputMismatchException Locale Currency',
    'java.util.function': 'Function BiFunction Supplier Consumer BiConsumer Predicate BiPredicate ' +
      'UnaryOperator BinaryOperator IntFunction ToIntFunction IntPredicate IntUnaryOperator',
    'java.util.stream': 'Stream IntStream LongStream DoubleStream Collectors Collector',
    'java.util.concurrent': 'ExecutorService Executors Future CompletableFuture Callable TimeUnit ' +
      'ConcurrentHashMap CopyOnWriteArrayList CountDownLatch Semaphore BlockingQueue ' +
      'LinkedBlockingQueue ArrayBlockingQueue ExecutionException TimeoutException StructuredTaskScope',
    'java.util.concurrent.atomic': 'AtomicInteger AtomicLong AtomicBoolean AtomicReference',
    'java.util.concurrent.locks': 'ReentrantLock ReentrantReadWriteLock Lock',
    'java.util.regex': 'Pattern Matcher',
    'java.io': 'IOException UncheckedIOException BufferedReader BufferedWriter InputStream ' +
      'OutputStream InputStreamReader PrintWriter File FileNotFoundException Serializable',
    'java.nio.file': 'Files Path Paths StandardOpenOption NoSuchFileException',
    'java.nio.charset': 'StandardCharsets Charset',
    'java.time': 'LocalDate LocalDateTime LocalTime Instant Duration Period ZonedDateTime ZoneId ' +
      'ZoneOffset DayOfWeek Month Year Clock OffsetDateTime',
    'java.time.format': 'DateTimeFormatter DateTimeParseException FormatStyle',
    'java.time.temporal': 'ChronoUnit TemporalAdjusters Temporal TemporalUnit',
    'java.math': 'BigDecimal BigInteger RoundingMode MathContext',
    'java.text': 'NumberFormat DecimalFormat',
    'java.sql': 'Connection PreparedStatement Statement ResultSet SQLException DriverManager Timestamp',
    'javax.sql': 'DataSource',
    'jakarta.servlet': 'ServletException ServletContext Filter FilterChain',
    'jakarta.servlet.http': 'HttpServlet HttpServletRequest HttpServletResponse HttpSession Cookie',
    'jakarta.servlet.annotation': 'WebServlet WebFilter WebListener',
    'jakarta.persistence': 'Entity Id GeneratedValue Column Table EntityManager EntityManagerFactory ' +
      'EntityTransaction Persistence TypedQuery PersistenceContext Transactional OneToMany ManyToOne',
    'jakarta.validation.constraints': 'NotNull NotBlank Size Min Max Email Positive',
    'jakarta.enterprise.context': 'ApplicationScoped RequestScoped SessionScoped',
    'jakarta.inject': 'Inject Named'
  };

  // ── クラスとメンバ ────────────────────────────────────────────────
  var CLASSES = {

    // ---- 入出力の入口 -----------------------------------------------------
    System: {
      doc: '画面への出力やキーボード入力の入口',
      s: [
        'out:PrintStream|標準出力（画面）',
        'err:PrintStream|エラー出力',
        'in:InputStream|標準入力（キーボード）',
        'currentTimeMillis():long|1970年からの経過ミリ秒',
        'nanoTime():long|時間の計測に使うナノ秒の値',
        'lineSeparator():String|環境ごとの改行文字',
        'exit(int status):void|プログラムを終了する',
        'getenv(String name):String|環境変数を読む',
        'getProperty(String key):String|システムプロパティを読む',
        'arraycopy(Object src, int srcPos, Object dest, int destPos, int length):void|配列の一部を写す'
      ],
      i: []
    },
    PrintStream: {
      doc: '文字を書き出す先',
      i: [
        'println(String x):void|1行出力して改行する',
        'println():void|空行を出力する',
        'print(String x):void|改行せずに出力する',
        'printf(String format, Object... args):PrintStream|書式を指定して出力する',
        'flush():void|溜まっている分を書き出す'
      ]
    },
    InputStream: {
      doc: 'バイトを読み込む元',
      i: ['read():int|1バイト読む', 'close():void|閉じる']
    },
    Scanner: {
      doc: 'キーボードやファイルから値を読み取る',
      i: [
        'nextInt():int|次の整数を1つ読む',
        'nextLong():long|次の整数（long）を1つ読む',
        'nextDouble():double|次の小数を1つ読む',
        'next():String|次の単語を1つ読む（空白まで）',
        'nextLine():String|次の1行を読む（改行まで）',
        'nextBoolean():boolean|次のtrue/falseを読む',
        'hasNext():boolean|まだ読むものがあるか',
        'hasNextInt():boolean|次が整数として読めるか',
        'hasNextLine():boolean|次の行があるか',
        'useDelimiter(String pattern):Scanner|区切り文字を変える',
        'close():void|閉じる'
      ]
    },

    // ---- 文字列 -----------------------------------------------------------
    String: {
      doc: '文字の並び',
      s: [
        'valueOf(Object obj):String|文字列に変える',
        'format(String format, Object... args):String|書式を当てはめた文字列を作る',
        'join(CharSequence delimiter, CharSequence... elements):String|区切り文字でつなぐ'
      ],
      i: [
        'length():int|文字数',
        'isEmpty():boolean|長さが0か',
        'isBlank():boolean|空白だけか',
        'charAt(int index):char|指定位置の1文字',
        'indexOf(String str):int|最初に現れる位置（なければ-1）',
        'lastIndexOf(String str):int|最後に現れる位置',
        'contains(CharSequence s):boolean|含まれているか',
        'startsWith(String prefix):boolean|その文字で始まるか',
        'endsWith(String suffix):boolean|その文字で終わるか',
        'equals(Object obj):boolean|中身が同じか（== ではなくこれを使う）',
        'equalsIgnoreCase(String other):boolean|大文字小文字を無視して同じか',
        'compareTo(String other):int|辞書順の比較',
        'substring(int beginIndex):String|指定位置から後ろを切り出す',
        'substring(int beginIndex, int endIndex):String|範囲を切り出す（終わりは含まない）',
        'replace(CharSequence target, CharSequence replacement):String|置き換えた文字列を返す',
        'toUpperCase():String|大文字にする',
        'toLowerCase():String|小文字にする',
        'trim():String|前後の空白を落とす',
        'strip():String|前後の空白を落とす（全角空白にも対応）',
        'split(String regex):String[]|区切って配列にする',
        'repeat(int count):String|繰り返した文字列を作る',
        'concat(String str):String|後ろにつなぐ',
        'toCharArray():char[]|1文字ずつの配列にする',
        'chars():IntStream|1文字ずつの流れにする',
        'lines():Stream<String>|行ごとに分けた流れにする',
        'matches(String regex):boolean|正規表現に丸ごと一致するか',
        'formatted(Object... args):String|自分を書式として当てはめる'
      ]
    },
    StringBuilder: {
      doc: '文字列を少しずつ組み立てる箱',
      i: [
        'append(Object x):StringBuilder|末尾に足す',
        'insert(int offset, Object x):StringBuilder|途中に差し込む',
        'delete(int start, int end):StringBuilder|範囲を消す',
        'deleteCharAt(int index):StringBuilder|1文字消す',
        'replace(int start, int end, String str):StringBuilder|範囲を置き換える',
        'reverse():StringBuilder|前後をひっくり返す',
        'length():int|今の文字数',
        'charAt(int index):char|指定位置の1文字',
        'setCharAt(int index, char ch):void|指定位置を書き換える',
        'indexOf(String str):int|最初に現れる位置',
        'setLength(int newLength):void|長さを変える（0で空にする）',
        'isEmpty():boolean|空か',
        'toString():String|Stringとして取り出す'
      ]
    },
    StringJoiner: {
      doc: '区切り文字を挟みながらつなぐ',
      i: ['add(CharSequence newElement):StringJoiner|1つ足す', 'length():int|文字数', 'toString():String|つないだ結果']
    },
    CharSequence: { doc: '文字の並びの共通の型', i: ['length():int|文字数', 'charAt(int index):char|指定位置の1文字', 'toString():String|文字列にする'] },

    // ---- 数と真偽 ---------------------------------------------------------
    Math: {
      doc: '数の計算をまとめた道具箱',
      s: [
        'abs(int a):int|絶対値',
        'max(int a, int b):int|大きいほう',
        'min(int a, int b):int|小さいほう',
        'pow(double a, double b):double|aのb乗',
        'sqrt(double a):double|平方根',
        'cbrt(double a):double|立方根',
        'round(double a):long|四捨五入',
        'floor(double a):double|切り捨て',
        'ceil(double a):double|切り上げ',
        'random():double|0以上1未満の乱数',
        'hypot(double x, double y):double|斜辺の長さ',
        'floorDiv(int x, int y):int|負の数でも下に丸める割り算',
        'floorMod(int x, int y):int|負の数でも正になる余り',
        'toRadians(double angdeg):double|度をラジアンに',
        'toDegrees(double angrad):double|ラジアンを度に',
        'PI:double|円周率',
        'E:double|自然対数の底'
      ],
      i: []
    },
    Integer: {
      doc: 'intを包むクラス',
      s: [
        'parseInt(String s):int|文字列を整数に変える',
        'valueOf(String s):Integer|文字列からIntegerを作る',
        'toString(int i):String|文字列にする',
        'toBinaryString(int i):String|2進数の文字列にする',
        'toHexString(int i):String|16進数の文字列にする',
        'compare(int x, int y):int|大小の比較',
        'sum(int a, int b):int|足し算',
        'max(int a, int b):int|大きいほう',
        'min(int a, int b):int|小さいほう',
        'MAX_VALUE:int|intの最大値',
        'MIN_VALUE:int|intの最小値'
      ],
      i: ['intValue():int|intとして取り出す', 'doubleValue():double|doubleとして取り出す', 'compareTo(Integer other):int|大小の比較']
    },
    Long: {
      doc: 'longを包むクラス',
      s: ['parseLong(String s):long|文字列をlongに変える', 'valueOf(String s):Long|文字列からLongを作る',
        'toString(long i):String|文字列にする', 'MAX_VALUE:long|longの最大値', 'MIN_VALUE:long|longの最小値'],
      i: ['longValue():long|longとして取り出す', 'intValue():int|intとして取り出す', 'compareTo(Long other):int|大小の比較']
    },
    Double: {
      doc: 'doubleを包むクラス',
      s: ['parseDouble(String s):double|文字列を小数に変える', 'valueOf(String s):Double|文字列からDoubleを作る',
        'toString(double d):String|文字列にする', 'compare(double a, double b):int|大小の比較',
        'isNaN(double v):boolean|数でない値か', 'MAX_VALUE:double|doubleの最大値', 'MIN_VALUE:double|0に近い最小の正の値'],
      i: ['doubleValue():double|doubleとして取り出す', 'intValue():int|intとして取り出す（小数は切り捨て）', 'compareTo(Double other):int|大小の比較']
    },
    Boolean: {
      doc: 'booleanを包むクラス',
      s: ['parseBoolean(String s):boolean|文字列をtrue/falseに変える', 'valueOf(String s):Boolean|文字列からBooleanを作る',
        'toString(boolean b):String|文字列にする'],
      i: ['booleanValue():boolean|booleanとして取り出す', 'compareTo(Boolean other):int|大小の比較']
    },
    Character: {
      doc: 'charを包むクラス。1文字の判定に使う',
      s: [
        'isDigit(char ch):boolean|数字か',
        'isLetter(char ch):boolean|文字か',
        'isLetterOrDigit(char ch):boolean|文字か数字か',
        'isUpperCase(char ch):boolean|大文字か',
        'isLowerCase(char ch):boolean|小文字か',
        'isWhitespace(char ch):boolean|空白か',
        'isAlphabetic(int codePoint):boolean|アルファベットか',
        'toUpperCase(char ch):char|大文字にする',
        'toLowerCase(char ch):char|小文字にする',
        'getNumericValue(char ch):int|数字を数値にする',
        'valueOf(char c):Character|Characterを作る'
      ],
      i: ['charValue():char|charとして取り出す', 'compareTo(Character other):int|大小の比較']
    },
    Number: { doc: '数を包むクラスの共通の型', i: ['intValue():int|intにする', 'longValue():long|longにする', 'doubleValue():double|doubleにする'] },
    BigDecimal: {
      doc: '誤差なく小数を扱う。お金の計算に使う',
      s: ['valueOf(double val):BigDecimal|doubleから作る', 'valueOf(long val):BigDecimal|整数から作る',
        'ZERO:BigDecimal|0', 'ONE:BigDecimal|1', 'TEN:BigDecimal|10'],
      i: [
        'add(BigDecimal augend):BigDecimal|足す',
        'subtract(BigDecimal subtrahend):BigDecimal|引く',
        'multiply(BigDecimal multiplicand):BigDecimal|かける',
        'divide(BigDecimal divisor, int scale, RoundingMode mode):BigDecimal|割る（丸め方を指定する）',
        'setScale(int newScale, RoundingMode mode):BigDecimal|小数点以下の桁をそろえる',
        'compareTo(BigDecimal val):int|大小の比較（equalsではなくこれ）',
        'negate():BigDecimal|符号を反転する',
        'abs():BigDecimal|絶対値',
        'stripTrailingZeros():BigDecimal|末尾の0を落とす',
        'doubleValue():double|doubleにする',
        'toPlainString():String|指数表記にしない文字列'
      ]
    },
    BigInteger: {
      doc: '桁数の制限なく整数を扱う',
      s: ['valueOf(long val):BigInteger|整数から作る', 'ZERO:BigInteger|0', 'ONE:BigInteger|1'],
      i: ['add(BigInteger val):BigInteger|足す', 'subtract(BigInteger val):BigInteger|引く',
        'multiply(BigInteger val):BigInteger|かける', 'divide(BigInteger val):BigInteger|割る',
        'mod(BigInteger m):BigInteger|余り', 'pow(int exponent):BigInteger|累乗', 'compareTo(BigInteger val):int|大小の比較']
    },
    RoundingMode: {
      doc: '丸め方の指定',
      s: ['HALF_UP:RoundingMode|四捨五入', 'HALF_EVEN:RoundingMode|銀行家の丸め', 'DOWN:RoundingMode|切り捨て',
        'UP:RoundingMode|切り上げ', 'FLOOR:RoundingMode|小さい側へ', 'CEILING:RoundingMode|大きい側へ']
    },
    Random: {
      doc: '乱数を作る',
      i: ['nextInt(int bound):int|0以上bound未満の整数', 'nextInt():int|intの範囲の整数',
        'nextDouble():double|0以上1未満の小数', 'nextBoolean():boolean|trueかfalse',
        'nextLong():long|longの範囲の整数', 'ints(long streamSize):IntStream|整数の流れ']
    },

    // ---- すべてのクラスの親 -----------------------------------------------
    Object: {
      doc: 'すべてのクラスの親',
      i: [
        'toString():String|文字列での表現',
        'equals(Object obj):boolean|中身が同じか',
        'hashCode():int|ハッシュ値',
        'getClass():Class|自分のクラス情報'
      ]
    },
    Class: {
      doc: 'クラスそのものの情報',
      i: [
        'getSimpleName():String|パッケージなしのクラス名',
        'getName():String|パッケージ付きのクラス名',
        'getDeclaredMethods():Method[]|宣言されたメソッド一覧',
        'getDeclaredFields():Field[]|宣言されたフィールド一覧',
        'getAnnotation(Class annotationClass):Object|付いている注釈を取る',
        'isAnnotationPresent(Class annotationClass):boolean|注釈が付いているか',
        'getSuperclass():Class|親クラス'
      ]
    },
    Comparable: { doc: '大小を比べられる印', tp: 'T', i: ['compareTo(T o):int|小さければ負、同じなら0、大きければ正'] },
    AutoCloseable: { doc: 'try-with-resourcesで自動的に閉じられる印', i: ['close():void|閉じる'] },

    // ---- コレクション -----------------------------------------------------
    Iterable: { doc: 'for-eachで回せる印', tp: 'T', i: ['iterator():Iterator<T>|要素を1つずつ取り出す道具', 'forEach(Consumer<T> action):void|1件ずつ処理する'] },
    Iterator: { doc: '要素を1つずつ取り出す道具', tp: 'E', i: ['hasNext():boolean|次があるか', 'next():E|次を取り出す', 'remove():void|今の要素を消す'] },
    Collection: {
      doc: '要素のまとまりの共通の型',
      tp: 'E', ext: 'Iterable',
      i: [
        'add(E e):boolean|1件足す',
        'remove(Object o):boolean|一致する1件を消す',
        'contains(Object o):boolean|含まれているか',
        'size():int|要素数',
        'isEmpty():boolean|空か',
        'clear():void|全部消す',
        'addAll(Collection<E> c):boolean|まとめて足す',
        'removeIf(Predicate<E> filter):boolean|条件に合う要素を消す',
        'stream():Stream<E>|流れにして加工する',
        'toArray():Object[]|配列にする'
      ]
    },
    List: {
      doc: '順番付きの並び。同じ値を何度でも入れられる',
      tp: 'E', ext: 'Collection',
      s: ['of(E... elements):List<E>|中身を書いて作る（変更できない）', 'copyOf(Collection<E> coll):List<E>|写して作る（変更できない）'],
      i: [
        'get(int index):E|index番目を取り出す（0から数える）',
        'set(int index, E element):E|index番目を書き換える',
        'add(int index, E element):void|index番目に差し込む',
        'remove(int index):E|index番目を消す',
        'indexOf(Object o):int|最初に現れる位置（なければ-1）',
        'lastIndexOf(Object o):int|最後に現れる位置',
        'subList(int fromIndex, int toIndex):List<E>|範囲を切り出す',
        'sort(Comparator<E> c):void|並べ替える',
        'getFirst():E|先頭',
        'getLast():E|末尾',
        'reversed():List<E>|逆順で見る'
      ]
    },
    ArrayList: { doc: 'Listの標準的な実装。要素の取り出しが速い', tp: 'E', ext: 'List', i: [] },
    LinkedList: {
      doc: '前後の出し入れが速いList',
      tp: 'E', ext: 'List',
      i: ['addFirst(E e):void|先頭に足す', 'addLast(E e):void|末尾に足す', 'removeFirst():E|先頭を取り出して消す',
        'removeLast():E|末尾を取り出して消す', 'peek():E|先頭を見る', 'poll():E|先頭を取り出す',
        'push(E e):void|先頭に積む', 'pop():E|先頭から降ろす']
    },
    Set: {
      doc: '重複しないまとまり',
      tp: 'E', ext: 'Collection',
      s: ['of(E... elements):Set<E>|中身を書いて作る（変更できない）', 'copyOf(Collection<E> coll):Set<E>|写して作る（変更できない）'],
      i: []
    },
    HashSet: { doc: 'Setの標準的な実装。順番は保証されない', tp: 'E', ext: 'Set', i: [] },
    LinkedHashSet: { doc: '入れた順を覚えているSet', tp: 'E', ext: 'Set', i: [] },
    TreeSet: {
      doc: '自動で並ぶSet',
      tp: 'E', ext: 'Set',
      i: ['first():E|いちばん小さい要素', 'last():E|いちばん大きい要素', 'floor(E e):E|e以下で最大の要素',
        'ceiling(E e):E|e以上で最小の要素', 'higher(E e):E|eより大きい最小の要素', 'lower(E e):E|eより小さい最大の要素',
        'headSet(E toElement):Set<E>|前半を切り出す', 'tailSet(E fromElement):Set<E>|後半を切り出す',
        'descendingSet():Set<E>|逆順で見る']
    },
    Map: {
      doc: 'キーと値の組を覚える。キーは重複しない',
      tp: 'K,V',
      s: ['of(K k1, V v1):Map<K,V>|中身を書いて作る（変更できない）', 'entry(K k, V v):Entry<K,V>|1組を作る',
        'copyOf(Map<K,V> map):Map<K,V>|写して作る（変更できない）'],
      i: [
        'put(K key, V value):V|キーに値を結びつける',
        'get(Object key):V|キーの値を取る（なければnull）',
        'getOrDefault(Object key, V defaultValue):V|なければ既定値を返す',
        'containsKey(Object key):boolean|そのキーがあるか',
        'containsValue(Object value):boolean|その値があるか',
        'remove(Object key):V|キーごと消す',
        'size():int|組の数',
        'isEmpty():boolean|空か',
        'clear():void|全部消す',
        'keySet():Set<K>|キーの一覧',
        'values():Collection<V>|値の一覧',
        'entrySet():Set<Entry<K,V>>|キーと値の組の一覧',
        'forEach(BiConsumer<K,V> action):void|1組ずつ処理する',
        'putIfAbsent(K key, V value):V|まだ無いときだけ入れる',
        'merge(K key, V value, BinaryOperator<V> f):V|あれば混ぜる、なければ入れる',
        'computeIfAbsent(K key, Function<K,V> f):V|無いときだけ作って入れる',
        'compute(K key, BiFunction<K,V,V> f):V|今の値から新しい値を作る',
        'replace(K key, V value):V|あるときだけ書き換える'
      ]
    },
    Entry: { doc: 'Mapのキーと値の組', tp: 'K,V', i: ['getKey():K|キー', 'getValue():V|値', 'setValue(V value):V|値を書き換える'] },
    HashMap: { doc: 'Mapの標準的な実装。順番は保証されない', tp: 'K,V', ext: 'Map', i: [] },
    LinkedHashMap: { doc: '入れた順を覚えているMap', tp: 'K,V', ext: 'Map', i: [] },
    TreeMap: {
      doc: 'キー順に自動で並ぶMap',
      tp: 'K,V', ext: 'Map',
      i: ['firstKey():K|いちばん小さいキー', 'lastKey():K|いちばん大きいキー',
        'firstEntry():Entry<K,V>|先頭の組', 'lastEntry():Entry<K,V>|末尾の組',
        'floorKey(K key):K|key以下で最大のキー', 'ceilingKey(K key):K|key以上で最小のキー',
        'higherKey(K key):K|keyより大きい最小のキー', 'lowerKey(K key):K|keyより小さい最大のキー',
        'headMap(K toKey):Map<K,V>|前半を切り出す', 'tailMap(K fromKey):Map<K,V>|後半を切り出す',
        'descendingMap():Map<K,V>|逆順で見る']
    },
    Queue: { doc: '入れた順に出す待ち行列', tp: 'E', ext: 'Collection', i: ['offer(E e):boolean|末尾に入れる', 'poll():E|先頭を取り出す', 'peek():E|先頭を見るだけ'] },
    Deque: {
      doc: '前からも後ろからも出し入れできる列',
      tp: 'E', ext: 'Queue',
      i: ['addFirst(E e):void|先頭に入れる', 'addLast(E e):void|末尾に入れる', 'pollFirst():E|先頭を取り出す',
        'pollLast():E|末尾を取り出す', 'peekFirst():E|先頭を見る', 'peekLast():E|末尾を見る',
        'push(E e):void|スタックとして積む', 'pop():E|スタックとして降ろす']
    },
    ArrayDeque: { doc: 'Dequeの標準的な実装。スタックにも待ち行列にも使える', tp: 'E', ext: 'Deque', i: [] },
    PriorityQueue: { doc: '小さいものから出てくる待ち行列', tp: 'E', ext: 'Queue', i: [] },
    Arrays: {
      doc: '配列を扱う道具箱',
      s: [
        'sort(int[] a):void|小さい順に並べ替える',
        'sort(Object[] a):void|小さい順に並べ替える',
        'toString(int[] a):String|中身を見える形にする',
        'deepToString(Object[] a):String|入れ子の配列も見える形にする',
        'asList(E... a):List<E>|配列をListとして見る',
        'fill(int[] a, int val):void|同じ値で埋める',
        'copyOf(int[] original, int newLength):int[]|長さを変えて写す',
        'copyOfRange(int[] original, int from, int to):int[]|範囲を写す',
        'equals(int[] a, int[] b):boolean|中身が同じか',
        'stream(int[] array):IntStream|流れにする',
        'binarySearch(int[] a, int key):int|並んだ配列を二分探索する'
      ]
    },
    Collections: {
      doc: 'コレクションを扱う道具箱',
      s: [
        'sort(List<E> list):void|小さい順に並べ替える',
        'sort(List<E> list, Comparator<E> c):void|決めた順に並べ替える',
        'reverse(List<E> list):void|前後をひっくり返す',
        'shuffle(List<E> list):void|順番をばらばらにする',
        'max(Collection<E> coll):E|いちばん大きい要素',
        'min(Collection<E> coll):E|いちばん小さい要素',
        'swap(List<E> list, int i, int j):void|2か所を入れ替える',
        'frequency(Collection<E> c, Object o):int|同じ要素の数を数える',
        'nCopies(int n, E o):List<E>|同じ要素をn個並べる',
        'unmodifiableList(List<E> list):List<E>|変更できない見せかけを作る',
        'emptyList():List<E>|空のList'
      ]
    },
    Objects: {
      doc: 'nullに強い小道具',
      s: [
        'requireNonNull(T obj):T|nullなら例外にする',
        'requireNonNullElse(T obj, T defaultObj):T|nullなら既定値にする',
        'equals(Object a, Object b):boolean|nullでも落ちない等値比較',
        'hash(Object... values):int|複数の値からハッシュ値を作る',
        'toString(Object o):String|nullでも落ちない文字列化',
        'isNull(Object obj):boolean|nullか',
        'nonNull(Object obj):boolean|nullでないか'
      ]
    },
    Optional: {
      doc: '値があるかないかを型で表す入れ物',
      tp: 'T',
      s: ['of(T value):Optional<T>|中身ありで作る', 'ofNullable(T value):Optional<T>|nullかもしれない値から作る', 'empty():Optional<T>|空で作る'],
      i: [
        'isPresent():boolean|中身があるか',
        'isEmpty():boolean|空か',
        'get():T|中身を取り出す（空だと例外）',
        'orElse(T other):T|空なら既定値を返す',
        'orElseGet(Supplier<T> supplier):T|空なら作って返す',
        'orElseThrow():T|空なら例外にする',
        'map(Function<T,R> mapper):Optional<R>|中身を変換する',
        'flatMap(Function<T,R> mapper):Optional<R>|Optionalを返す変換をつなぐ',
        'filter(Predicate<T> predicate):Optional<T>|条件に合わなければ空にする',
        'ifPresent(Consumer<T> action):void|中身があるときだけ処理する',
        'ifPresentOrElse(Consumer<T> action, Runnable emptyAction):void|ある時とない時で分ける'
      ]
    },
    OptionalInt: { doc: 'intがあるかないかを表す入れ物', i: ['isPresent():boolean|中身があるか', 'getAsInt():int|中身を取り出す', 'orElse(int other):int|空なら既定値'] },
    OptionalDouble: { doc: 'doubleがあるかないかを表す入れ物', i: ['isPresent():boolean|中身があるか', 'getAsDouble():double|中身を取り出す', 'orElse(double other):double|空なら既定値'] },
    Comparator: {
      doc: '並べ替えの順番を決める道具',
      tp: 'T',
      s: ['comparing(Function<T,U> keyExtractor):Comparator<T>|取り出した値の順に並べる',
        'comparingInt(ToIntFunction<T> keyExtractor):Comparator<T>|取り出した整数の順に並べる',
        'comparingDouble(ToIntFunction<T> keyExtractor):Comparator<T>|取り出した小数の順に並べる',
        'naturalOrder():Comparator<T>|自然な順',
        'reverseOrder():Comparator<T>|逆の順'],
      i: ['reversed():Comparator<T>|逆順にする', 'thenComparing(Function<T,U> keyExtractor):Comparator<T>|同点のときの第2の基準',
        'thenComparingInt(ToIntFunction<T> keyExtractor):Comparator<T>|同点のときの第2の基準（整数）',
        'compare(T o1, T o2):int|2つを比べる']
    },
    UUID: { doc: '重複しないIDを作る', s: ['randomUUID():UUID|ランダムなIDを作る'], i: ['toString():String|文字列にする'] },
    Locale: { doc: '国や言語の指定', s: ['getDefault():Locale|環境の既定', 'JAPAN:Locale|日本', 'US:Locale|アメリカ'], i: ['getLanguage():String|言語コード'] },

    // ---- ストリーム -------------------------------------------------------
    Stream: {
      doc: '要素の流れ。絞る・変える・集めるをつないで書く',
      tp: 'T',
      s: ['of(T... values):Stream<T>|並べて作る', 'iterate(T seed, UnaryOperator<T> f):Stream<T>|前の値から次を作り続ける',
        'generate(Supplier<T> s):Stream<T>|作り続ける', 'concat(Stream<T> a, Stream<T> b):Stream<T>|2つをつなぐ',
        'empty():Stream<T>|空の流れ'],
      i: [
        'filter(Predicate<T> predicate):Stream<T>|条件に合うものだけ残す',
        'map(Function<T,R> mapper):Stream<R>|1つずつ別のものに変える',
        'mapToInt(ToIntFunction<T> mapper):IntStream|intの流れに変える',
        'flatMap(Function<T,R> mapper):Stream<R>|入れ子をならす',
        'distinct():Stream<T>|重複を取り除く',
        'sorted():Stream<T>|小さい順に並べる',
        'sorted(Comparator<T> comparator):Stream<T>|決めた順に並べる',
        'limit(long maxSize):Stream<T>|先頭から数を絞る',
        'skip(long n):Stream<T>|先頭を飛ばす',
        'peek(Consumer<T> action):Stream<T>|途中を覗く（デバッグ用）',
        'forEach(Consumer<T> action):void|1件ずつ処理する',
        'toList():List<T>|Listにまとめる',
        'collect(Collector collector):Object|指定した形にまとめる',
        'count():long|件数',
        'anyMatch(Predicate<T> predicate):boolean|1つでも合うか',
        'allMatch(Predicate<T> predicate):boolean|全部合うか',
        'noneMatch(Predicate<T> predicate):boolean|1つも合わないか',
        'findFirst():Optional<T>|最初の1件',
        'findAny():Optional<T>|どれか1件',
        'reduce(T identity, BinaryOperator<T> accumulator):T|1つの値にまとめる',
        'min(Comparator<T> comparator):Optional<T>|いちばん小さい要素',
        'max(Comparator<T> comparator):Optional<T>|いちばん大きい要素',
        'toArray():Object[]|配列にする'
      ]
    },
    IntStream: {
      doc: 'intの流れ。合計や平均が直接出せる',
      s: ['range(int startInclusive, int endExclusive):IntStream|startからend未満まで',
        'rangeClosed(int startInclusive, int endInclusive):IntStream|startからendまで',
        'of(int... values):IntStream|並べて作る'],
      i: [
        'sum():int|合計',
        'average():OptionalDouble|平均',
        'max():OptionalInt|最大',
        'min():OptionalInt|最小',
        'count():long|件数',
        'boxed():Stream<Integer>|Integerの流れにする',
        'mapToObj(IntFunction mapper):Stream<Object>|別のものに変える',
        'filter(IntPredicate predicate):IntStream|条件に合うものだけ残す',
        'map(IntUnaryOperator mapper):IntStream|1つずつ変える',
        'sorted():IntStream|小さい順に並べる',
        'forEach(IntConsumer action):void|1件ずつ処理する',
        'toArray():int[]|配列にする'
      ]
    },
    Collectors: {
      doc: 'Streamのまとめ方をそろえた道具箱',
      s: [
        'toList():Collector|Listにまとめる',
        'toSet():Collector|Setにまとめる',
        'toMap(Function keyMapper, Function valueMapper):Collector|Mapにまとめる',
        'joining(CharSequence delimiter):Collector|区切り文字でつないだ文字列にする',
        'groupingBy(Function classifier):Collector|キーごとに分けてまとめる',
        'partitioningBy(Predicate predicate):Collector|条件でtrue/falseの2組に分ける',
        'counting():Collector|件数を数える',
        'summingInt(ToIntFunction mapper):Collector|合計を出す',
        'averagingInt(ToIntFunction mapper):Collector|平均を出す',
        'mapping(Function mapper, Collector downstream):Collector|変換してからまとめる',
        'toUnmodifiableList():Collector|変更できないListにまとめる'
      ]
    },

    // ---- 関数型インタフェース ---------------------------------------------
    Function: { doc: '1つ受け取って1つ返す処理', tp: 'T,R', i: ['apply(T t):R|処理を実行する', 'andThen(Function after):Function|後ろにつなぐ', 'compose(Function before):Function|前につなぐ'] },
    BiFunction: { doc: '2つ受け取って1つ返す処理', tp: 'T,U,R', i: ['apply(T t, U u):R|処理を実行する'] },
    Supplier: { doc: '受け取らずに1つ返す処理', tp: 'T', i: ['get():T|値を作る'] },
    Consumer: { doc: '1つ受け取って何も返さない処理', tp: 'T', i: ['accept(T t):void|処理を実行する', 'andThen(Consumer after):Consumer|後ろにつなぐ'] },
    BiConsumer: { doc: '2つ受け取って何も返さない処理', tp: 'T,U', i: ['accept(T t, U u):void|処理を実行する'] },
    Predicate: { doc: '条件を判定する処理', tp: 'T', i: ['test(T t):boolean|条件に合うか', 'negate():Predicate<T>|条件を反転する', 'and(Predicate other):Predicate<T>|かつ', 'or(Predicate other):Predicate<T>|または'] },
    UnaryOperator: { doc: '同じ型を受け取って同じ型を返す処理', tp: 'T', i: ['apply(T t):T|処理を実行する'] },
    BinaryOperator: { doc: '同じ型2つから同じ型を返す処理', tp: 'T', i: ['apply(T a, T b):T|処理を実行する'] },
    Runnable: { doc: '受け取らず返さない処理。スレッドに渡す', i: ['run():void|処理を実行する'] },
    Callable: { doc: '値を返す処理。例外を投げられる', tp: 'V', i: ['call():V|処理を実行する'] },

    // ---- 例外 -------------------------------------------------------------
    Throwable: {
      doc: '例外とエラーの親',
      i: ['getMessage():String|エラーの説明', 'getLocalizedMessage():String|地域化された説明',
        'printStackTrace():void|どこで起きたかを出力する', 'getCause():Throwable|元の原因',
        'getStackTrace():Object[]|呼び出しの経路', 'addSuppressed(Throwable exception):void|抑制された例外を足す']
    },
    Exception: { doc: '想定できる異常。catchするのが基本', ext: 'Throwable', i: [] },
    RuntimeException: { doc: 'プログラムの誤りから起きる例外。catchを強制されない', ext: 'Exception', i: [] },
    Error: { doc: '回復できない深刻な異常', ext: 'Throwable', i: [] },
    IllegalArgumentException: { doc: '引数の値がおかしいときの例外', ext: 'RuntimeException', i: [] },
    IllegalStateException: { doc: '今の状態では呼べないときの例外', ext: 'RuntimeException', i: [] },
    NullPointerException: { doc: 'nullを使ってしまったときの例外', ext: 'RuntimeException', i: [] },
    NumberFormatException: { doc: '数に変換できない文字列だったときの例外', ext: 'IllegalArgumentException', i: [] },
    ArithmeticException: { doc: '0で割ったときなどの例外', ext: 'RuntimeException', i: [] },
    IndexOutOfBoundsException: { doc: '範囲外の位置を指したときの例外', ext: 'RuntimeException', i: [] },
    ArrayIndexOutOfBoundsException: { doc: '配列の範囲外を指したときの例外', ext: 'IndexOutOfBoundsException', i: [] },
    StringIndexOutOfBoundsException: { doc: '文字列の範囲外を指したときの例外', ext: 'IndexOutOfBoundsException', i: [] },
    ClassCastException: { doc: '変換できない型にキャストしたときの例外', ext: 'RuntimeException', i: [] },
    UnsupportedOperationException: { doc: 'その操作に対応していないときの例外', ext: 'RuntimeException', i: [] },
    ConcurrentModificationException: { doc: '回している途中で中身を変えたときの例外', ext: 'RuntimeException', i: [] },
    NoSuchElementException: { doc: '取り出せる要素がないときの例外', ext: 'RuntimeException', i: [] },
    InputMismatchException: { doc: '読もうとした型と入力が合わないときの例外', ext: 'NoSuchElementException', i: [] },
    IOException: { doc: '入出力に失敗したときの例外', ext: 'Exception', i: [] },
    UncheckedIOException: { doc: 'catchを強制されない入出力の例外', ext: 'RuntimeException', i: [] },
    FileNotFoundException: { doc: 'ファイルが見つからないときの例外', ext: 'IOException', i: [] },
    NoSuchFileException: { doc: 'そのパスにファイルがないときの例外', ext: 'IOException', i: [] },
    InterruptedException: { doc: '待っている間に割り込まれたときの例外', ext: 'Exception', i: [] },
    ClassNotFoundException: { doc: 'クラスが見つからないときの例外', ext: 'Exception', i: [] },
    SQLException: { doc: 'データベース操作に失敗したときの例外', ext: 'Exception', i: [] },
    ServletException: { doc: 'サーブレットの処理に失敗したときの例外', ext: 'Exception', i: [] },
    DateTimeParseException: { doc: '日付として読めない文字列だったときの例外', ext: 'RuntimeException', i: [] },
    ExecutionException: { doc: '別スレッドの処理が例外で終わったときの例外', ext: 'Exception', i: [] },
    TimeoutException: { doc: '待ち時間を超えたときの例外', ext: 'Exception', i: [] },
    StackOverflowError: { doc: '再帰が深すぎたときのエラー', ext: 'Error', i: [] },
    OutOfMemoryError: { doc: 'メモリが足りなくなったときのエラー', ext: 'Error', i: [] },

    // ---- 日付と時刻 -------------------------------------------------------
    LocalDate: {
      doc: '時刻を含まない日付',
      s: ['now():LocalDate|今日', 'of(int year, int month, int dayOfMonth):LocalDate|年月日から作る',
        'parse(CharSequence text):LocalDate|2026-08-12 のような文字列から作る'],
      i: [
        'plusDays(long daysToAdd):LocalDate|日を足す',
        'plusWeeks(long weeksToAdd):LocalDate|週を足す',
        'plusMonths(long monthsToAdd):LocalDate|月を足す',
        'plusYears(long yearsToAdd):LocalDate|年を足す',
        'minusDays(long daysToSubtract):LocalDate|日を引く',
        'minusMonths(long monthsToSubtract):LocalDate|月を引く',
        'minusYears(long yearsToSubtract):LocalDate|年を引く',
        'getYear():int|年',
        'getMonthValue():int|月（1から12）',
        'getMonth():Month|月（列挙）',
        'getDayOfMonth():int|日',
        'getDayOfWeek():DayOfWeek|曜日',
        'getDayOfYear():int|その年の何日目か',
        'isBefore(LocalDate other):boolean|より前か',
        'isAfter(LocalDate other):boolean|より後か',
        'isEqual(LocalDate other):boolean|同じ日か',
        'withDayOfMonth(int dayOfMonth):LocalDate|日だけ差し替える',
        'lengthOfMonth():int|その月の日数',
        'isLeapYear():boolean|うるう年か',
        'atStartOfDay():LocalDateTime|その日の0時',
        'atTime(int hour, int minute):LocalDateTime|時刻を足して日時にする',
        'until(LocalDate endDateExclusive):Period|終わりまでの差',
        'format(DateTimeFormatter formatter):String|書式を当てて文字列にする'
      ]
    },
    LocalDateTime: {
      doc: '日付と時刻',
      s: ['now():LocalDateTime|今', 'of(int year, int month, int day, int hour, int minute):LocalDateTime|年月日時分から作る',
        'parse(CharSequence text):LocalDateTime|文字列から作る'],
      i: ['toLocalDate():LocalDate|日付の部分', 'toLocalTime():LocalTime|時刻の部分',
        'plusDays(long days):LocalDateTime|日を足す', 'plusHours(long hours):LocalDateTime|時間を足す',
        'plusMinutes(long minutes):LocalDateTime|分を足す', 'minusHours(long hours):LocalDateTime|時間を引く',
        'getHour():int|時', 'getMinute():int|分', 'getSecond():int|秒',
        'isBefore(LocalDateTime other):boolean|より前か', 'isAfter(LocalDateTime other):boolean|より後か',
        'atZone(ZoneId zone):ZonedDateTime|時間帯を付ける',
        'truncatedTo(TemporalUnit unit):LocalDateTime|単位より小さい部分を切り捨てる',
        'format(DateTimeFormatter formatter):String|書式を当てて文字列にする']
    },
    LocalTime: {
      doc: '日付を含まない時刻',
      s: ['now():LocalTime|今の時刻', 'of(int hour, int minute):LocalTime|時分から作る', 'parse(CharSequence text):LocalTime|文字列から作る'],
      i: ['getHour():int|時', 'getMinute():int|分', 'plusHours(long hours):LocalTime|時間を足す',
        'plusMinutes(long minutes):LocalTime|分を足す', 'isBefore(LocalTime other):boolean|より前か',
        'isAfter(LocalTime other):boolean|より後か', 'format(DateTimeFormatter formatter):String|書式を当てて文字列にする']
    },
    Instant: {
      doc: '世界共通の時刻の一点。機械が扱う時刻',
      s: ['now():Instant|今', 'ofEpochMilli(long epochMilli):Instant|ミリ秒から作る', 'parse(CharSequence text):Instant|文字列から作る'],
      i: ['toEpochMilli():long|1970年からのミリ秒', 'getEpochSecond():long|1970年からの秒',
        'plusSeconds(long secondsToAdd):Instant|秒を足す', 'minusSeconds(long secondsToSubtract):Instant|秒を引く',
        'isBefore(Instant other):boolean|より前か', 'isAfter(Instant other):boolean|より後か',
        'atZone(ZoneId zone):ZonedDateTime|時間帯を付けて読める形にする']
    },
    ZonedDateTime: {
      doc: '時間帯付きの日時',
      s: ['now(ZoneId zone):ZonedDateTime|その時間帯の今', 'parse(CharSequence text):ZonedDateTime|文字列から作る'],
      i: ['toLocalDate():LocalDate|日付の部分', 'toLocalDateTime():LocalDateTime|時間帯を外した日時',
        'toInstant():Instant|世界共通の時刻にする', 'getZone():ZoneId|時間帯',
        'withZoneSameInstant(ZoneId zone):ZonedDateTime|同じ瞬間を別の時間帯で見る',
        'format(DateTimeFormatter formatter):String|書式を当てて文字列にする']
    },
    ZoneId: { doc: '時間帯（タイムゾーン）', s: ['of(String zoneId):ZoneId|Asia/Tokyo のような名前から作る', 'systemDefault():ZoneId|環境の時間帯'], i: ['getId():String|名前'] },
    Duration: {
      doc: '時間の長さ（秒や分）',
      s: ['ofSeconds(long seconds):Duration|秒から作る', 'ofMinutes(long minutes):Duration|分から作る',
        'ofHours(long hours):Duration|時間から作る', 'ofMillis(long millis):Duration|ミリ秒から作る',
        'between(Temporal start, Temporal end):Duration|2つの時刻の差'],
      i: ['toMillis():long|ミリ秒にする', 'toSeconds():long|秒にする', 'toMinutes():long|分にする',
        'getSeconds():long|秒の部分', 'plus(Duration duration):Duration|足す',
        'isZero():boolean|0か', 'isNegative():boolean|負か']
    },
    Period: {
      doc: '日付の長さ（年月日）',
      s: ['of(int years, int months, int days):Period|年月日から作る', 'ofDays(int days):Period|日から作る',
        'between(LocalDate start, LocalDate end):Period|2つの日付の差'],
      i: ['getYears():int|年の部分', 'getMonths():int|月の部分', 'getDays():int|日の部分', 'toTotalMonths():long|合計の月数']
    },
    DateTimeFormatter: {
      doc: '日時と文字列を行き来する書式',
      s: ['ofPattern(String pattern):DateTimeFormatter|yyyy/MM/dd のような形を決める',
        'ISO_LOCAL_DATE:DateTimeFormatter|2026-08-12 の形', 'ISO_LOCAL_DATE_TIME:DateTimeFormatter|日付と時刻の標準形'],
      i: ['format(Object temporal):String|文字列にする', 'withLocale(Locale locale):DateTimeFormatter|地域を指定する']
    },
    ChronoUnit: {
      doc: '時間の単位',
      s: ['DAYS:ChronoUnit|日', 'MONTHS:ChronoUnit|月', 'YEARS:ChronoUnit|年', 'HOURS:ChronoUnit|時間',
        'MINUTES:ChronoUnit|分', 'SECONDS:ChronoUnit|秒', 'MILLIS:ChronoUnit|ミリ秒'],
      i: ['between(Object start, Object end):long|2つの差をこの単位で数える']
    },
    DayOfWeek: { doc: '曜日', s: ['MONDAY:DayOfWeek|月曜', 'SATURDAY:DayOfWeek|土曜', 'SUNDAY:DayOfWeek|日曜'], i: ['getValue():int|月曜を1とした番号', 'name():String|英語の名前'] },
    Month: { doc: '月', i: ['getValue():int|1から12の番号', 'name():String|英語の名前'] },
    Clock: { doc: '今の時刻の出どころ。テストで差し替えられる', s: ['systemDefaultZone():Clock|環境の時計', 'fixed(Instant fixedInstant, ZoneId zone):Clock|止まった時計'], i: ['instant():Instant|今'] },

    // ---- ファイル ---------------------------------------------------------
    Files: {
      doc: 'ファイルの読み書きをまとめた道具箱',
      s: [
        'readString(Path path):String|中身を丸ごと文字列で読む',
        'writeString(Path path, CharSequence csq):Path|文字列を書き出す',
        'readAllLines(Path path):List<String>|1行ずつのListで読む',
        'lines(Path path):Stream<String>|1行ずつの流れで読む',
        'write(Path path, byte[] bytes):Path|バイトを書き出す',
        'exists(Path path):boolean|あるか',
        'notExists(Path path):boolean|ないか',
        'createDirectories(Path dir):Path|途中の階層ごと作る',
        'createFile(Path path):Path|空のファイルを作る',
        'delete(Path path):void|消す',
        'deleteIfExists(Path path):boolean|あれば消す',
        'copy(Path source, Path target):Path|写す',
        'move(Path source, Path target):Path|移す',
        'size(Path path):long|バイト数',
        'list(Path dir):Stream<Path>|直下の一覧',
        'newBufferedReader(Path path):BufferedReader|少しずつ読む道具を作る'
      ]
    },
    Path: {
      doc: 'ファイルの場所',
      s: ['of(String first, String... more):Path|文字列からパスを作る'],
      i: ['getFileName():Path|ファイル名の部分', 'getParent():Path|親フォルダ',
        'resolve(String other):Path|下の階層をつなぐ', 'toAbsolutePath():Path|絶対パスにする',
        'getNameCount():int|階層の数', 'toString():String|文字列にする']
    },
    Paths: { doc: '古い書き方のパス生成。今は Path.of を使う', s: ['get(String first, String... more):Path|文字列からパスを作る'] },
    StandardOpenOption: { doc: 'ファイルを開くときの指定', s: ['APPEND:StandardOpenOption|末尾に追記する', 'CREATE:StandardOpenOption|なければ作る', 'TRUNCATE_EXISTING:StandardOpenOption|中身を空にする'] },
    StandardCharsets: { doc: '文字コードの指定', s: ['UTF_8:Charset|UTF-8'] },
    BufferedReader: { doc: '1行ずつ読む道具', i: ['readLine():String|1行読む（終わりならnull）', 'lines():Stream<String>|残りを行の流れにする', 'read():int|1文字読む', 'close():void|閉じる'] },
    BufferedWriter: { doc: 'まとめて書き出す道具', i: ['write(String str):void|書く', 'newLine():void|改行を書く', 'flush():void|書き出す', 'close():void|閉じる'] },
    PrintWriter: { doc: '文字を書き出す道具', i: ['println(String x):void|1行書いて改行する', 'print(String s):void|改行せず書く', 'printf(String format, Object... args):PrintWriter|書式を当てて書く', 'write(String s):void|書く', 'flush():void|書き出す', 'close():void|閉じる'] },
    File: { doc: '古い書き方のファイル。今は Path と Files を使う', i: ['getName():String|ファイル名', 'exists():boolean|あるか', 'length():long|バイト数', 'toPath():Path|Pathにする'] },
    Pattern: { doc: '正規表現をコンパイルしたもの', s: ['compile(String regex):Pattern|正規表現から作る', 'matches(String regex, CharSequence input):boolean|丸ごと一致するか'], i: ['matcher(CharSequence input):Matcher|照合する道具を作る', 'split(CharSequence input):String[]|区切って配列にする'] },
    Matcher: { doc: '正規表現の照合の途中の状態', i: ['find():boolean|次に一致する所を探す', 'matches():boolean|丸ごと一致するか', 'group():String|一致した文字列', 'group(int group):String|かっこで囲んだ部分', 'start():int|一致の開始位置', 'end():int|一致の終了位置', 'replaceAll(String replacement):String|全部置き換える'] },

    // ---- 並行処理 ---------------------------------------------------------
    Thread: {
      doc: '処理を並行して走らせる単位',
      s: ['sleep(long millis):void|指定ミリ秒だけ休む', 'currentThread():Thread|今動いているスレッド',
        'startVirtualThread(Runnable task):Thread|仮想スレッドで走らせる', 'ofVirtual():Object|仮想スレッドの作り手'],
      i: ['start():void|走り始める（runを直接呼ばない）', 'join():void|終わるまで待つ',
        'join(long millis):void|上限を決めて待つ', 'interrupt():void|割り込みをかける',
        'isAlive():boolean|まだ動いているか', 'isInterrupted():boolean|割り込まれたか',
        'setName(String name):void|名前を付ける', 'getName():String|名前',
        'setDaemon(boolean on):void|裏方スレッドにする', 'threadId():long|識別番号']
    },
    ExecutorService: {
      doc: 'スレッドを使い回して仕事を任せる窓口',
      i: ['submit(Callable task):Future|仕事を投げて結果の引換券を受け取る', 'execute(Runnable command):void|仕事を投げる',
        'invokeAll(Collection tasks):List|まとめて投げて全部待つ', 'shutdown():void|受付を締める',
        'awaitTermination(long timeout, TimeUnit unit):boolean|終わるまで待つ', 'close():void|締めて終わるまで待つ']
    },
    Executors: {
      doc: 'ExecutorServiceの作り方をそろえた道具箱',
      s: ['newFixedThreadPool(int nThreads):ExecutorService|決まった数のスレッドで回す',
        'newVirtualThreadPerTaskExecutor():ExecutorService|仕事ごとに仮想スレッドを作る',
        'newSingleThreadExecutor():ExecutorService|1本のスレッドで順番に回す']
    },
    Future: { doc: '後で結果を受け取る引換券', tp: 'V', i: ['get():V|結果を待って受け取る', 'isDone():boolean|終わったか', 'cancel(boolean mayInterruptIfRunning):boolean|取り消す'] },
    CompletableFuture: {
      doc: '完了したら次へつなげる引換券',
      tp: 'T',
      s: ['supplyAsync(Supplier<T> supplier):CompletableFuture<T>|別スレッドで作り始める', 'completedFuture(T value):CompletableFuture<T>|完了済みで作る'],
      i: ['thenApply(Function<T,R> fn):CompletableFuture<R>|結果を変換してつなぐ', 'thenAccept(Consumer<T> action):CompletableFuture<T>|結果を使ってつなぐ',
        'join():T|結果を待って受け取る', 'get():T|結果を待って受け取る']
    },
    TimeUnit: { doc: '待ち時間の単位', s: ['SECONDS:TimeUnit|秒', 'MILLISECONDS:TimeUnit|ミリ秒', 'MINUTES:TimeUnit|分'] },
    AtomicInteger: { doc: '複数スレッドから安全に増減できる整数', i: ['incrementAndGet():int|1増やしてから返す', 'getAndIncrement():int|返してから1増やす', 'addAndGet(int delta):int|足してから返す', 'get():int|今の値', 'set(int newValue):void|値を入れる', 'compareAndSet(int expect, int update):boolean|期待どおりなら書き換える'] },
    AtomicLong: { doc: '複数スレッドから安全に増減できるlong', i: ['incrementAndGet():long|1増やしてから返す', 'get():long|今の値', 'addAndGet(long delta):long|足してから返す'] },
    AtomicReference: { doc: '複数スレッドから安全に差し替えられる参照', tp: 'V', i: ['get():V|今の値', 'set(V newValue):void|入れ替える', 'compareAndSet(V expect, V update):boolean|期待どおりなら書き換える'] },
    ConcurrentHashMap: { doc: '複数スレッドから同時に使えるMap', tp: 'K,V', ext: 'Map', i: [] },
    CopyOnWriteArrayList: { doc: '読み取りが多いときに向く並行List', tp: 'E', ext: 'List', i: [] },
    BlockingQueue: { doc: '空なら待つ、満杯なら待つ待ち行列', tp: 'E', ext: 'Queue', i: ['put(E e):void|空きができるまで待って入れる', 'take():E|要素が来るまで待って取り出す'] },
    LinkedBlockingQueue: { doc: 'BlockingQueueの標準的な実装', tp: 'E', ext: 'BlockingQueue', i: [] },
    ArrayBlockingQueue: { doc: '容量を決めたBlockingQueue', tp: 'E', ext: 'BlockingQueue', i: [] },
    CountDownLatch: { doc: '決めた回数の完了を待つ関門', i: ['countDown():void|残りを1つ減らす', 'await():void|0になるまで待つ', 'getCount():long|残り'] },
    Semaphore: { doc: '同時に入れる数を制限する', i: ['acquire():void|1つ確保する（空くまで待つ）', 'release():void|返す', 'tryAcquire():boolean|空いていれば確保する'] },
    ReentrantLock: { doc: '明示的に掛け外しする鍵', i: ['lock():void|鍵を掛ける', 'unlock():void|鍵を外す（finallyで必ず）', 'tryLock():boolean|空いていれば掛ける'] },

    // ---- サーブレット -----------------------------------------------------
    HttpServlet: {
      doc: 'HTTPリクエストを受け取る土台',
      i: ['doGet(HttpServletRequest req, HttpServletResponse resp):void|GETを処理する',
        'doPost(HttpServletRequest req, HttpServletResponse resp):void|POSTを処理する',
        'doPut(HttpServletRequest req, HttpServletResponse resp):void|PUTを処理する',
        'doDelete(HttpServletRequest req, HttpServletResponse resp):void|DELETEを処理する',
        'init():void|最初の1回だけ呼ばれる']
    },
    HttpServletRequest: {
      doc: '届いたリクエスト',
      i: ['getParameter(String name):String|クエリやフォームの値を取る',
        'getParameterValues(String name):String[]|同じ名前の値をまとめて取る',
        'getSession():HttpSession|セッションを取る（無ければ作る）',
        'getSession(boolean create):HttpSession|作るかどうかを指定して取る',
        'getAttribute(String name):Object|このリクエストに預けた値',
        'setAttribute(String name, Object o):void|このリクエストに値を預ける',
        'getCookies():Cookie[]|届いたクッキー',
        'getMethod():String|GETやPOSTの種別',
        'getPathInfo():String|パスの残り',
        'getRequestURI():String|要求されたパス',
        'getHeader(String name):String|ヘッダの値',
        'getReader():BufferedReader|本文を読む道具',
        'getContentType():String|本文の種類']
    },
    HttpServletResponse: {
      doc: '返すレスポンス',
      s: ['SC_OK:int|200', 'SC_BAD_REQUEST:int|400', 'SC_NOT_FOUND:int|404', 'SC_INTERNAL_SERVER_ERROR:int|500'],
      i: ['getWriter():PrintWriter|本文を書く道具',
        'setStatus(int sc):void|ステータスコードを決める',
        'sendError(int sc, String msg):void|エラーとして返す',
        'sendRedirect(String location):void|別のURLへ飛ばす',
        'setContentType(String type):void|本文の種類を決める',
        'setCharacterEncoding(String charset):void|文字コードを決める',
        'setHeader(String name, String value):void|ヘッダを付ける',
        'addCookie(Cookie cookie):void|クッキーを付ける']
    },
    HttpSession: {
      doc: '同じ利用者のあいだ値を覚えておく場所',
      i: ['getAttribute(String name):Object|預けた値を取る', 'setAttribute(String name, Object value):void|値を預ける',
        'removeAttribute(String name):void|預けた値を消す', 'invalidate():void|セッションを捨てる',
        'getId():String|セッションの識別子', 'setMaxInactiveInterval(int interval):void|放置して切れるまでの秒数']
    },
    Cookie: {
      doc: 'ブラウザに預ける小さな値',
      i: ['getName():String|名前', 'getValue():String|値', 'setMaxAge(int expiry):void|保存する秒数',
        'setHttpOnly(boolean isHttpOnly):void|JavaScriptから読めなくする', 'setPath(String uri):void|送る対象のパス',
        'setSecure(boolean flag):void|HTTPSだけに送る']
    },

    // ---- データベース -----------------------------------------------------
    DriverManager: { doc: 'JDBCの接続を作る', s: ['getConnection(String url, String user, String password):Connection|データベースにつなぐ'] },
    Connection: {
      doc: 'データベースとの接続',
      i: ['prepareStatement(String sql):PreparedStatement|値を後で入れるSQLを用意する',
        'createStatement():Statement|そのまま実行するSQLを用意する',
        'setAutoCommit(boolean autoCommit):void|自動コミットを切り替える',
        'commit():void|変更を確定する', 'rollback():void|変更を取り消す', 'close():void|閉じる']
    },
    PreparedStatement: {
      doc: '値を後から入れるSQL。これを使うとSQLインジェクションを防げる',
      i: ['setInt(int parameterIndex, int x):void|?に整数を入れる', 'setString(int parameterIndex, String x):void|?に文字列を入れる',
        'setLong(int parameterIndex, long x):void|?にlongを入れる', 'setDouble(int parameterIndex, double x):void|?に小数を入れる',
        'setBoolean(int parameterIndex, boolean x):void|?にtrue/falseを入れる',
        'executeQuery():ResultSet|検索して結果を受け取る', 'executeUpdate():int|更新して件数を受け取る',
        'addBatch():void|まとめ実行に積む', 'executeBatch():int[]|積んだ分を実行する', 'close():void|閉じる']
    },
    Statement: { doc: 'そのまま実行するSQL', i: ['executeQuery(String sql):ResultSet|検索する', 'executeUpdate(String sql):int|更新する', 'close():void|閉じる'] },
    ResultSet: {
      doc: '検索結果の表。next()で1行ずつ進む',
      i: ['next():boolean|次の行へ進む（無ければfalse）', 'getInt(String columnLabel):int|列を整数で取る',
        'getString(String columnLabel):String|列を文字列で取る', 'getLong(String columnLabel):long|列をlongで取る',
        'getDouble(String columnLabel):double|列を小数で取る', 'getBoolean(String columnLabel):boolean|列をtrue/falseで取る',
        'close():void|閉じる']
    },
    DataSource: { doc: '接続を貸し出す窓口（コネクションプール）', i: ['getConnection():Connection|接続を借りる'] },
    EntityManager: {
      doc: 'オブジェクトとDBの行を行き来させる窓口',
      i: ['persist(Object entity):void|新しく保存する', 'find(Class entityClass, Object primaryKey):Object|主キーで1件取る',
        'merge(Object entity):Object|変更を反映する', 'remove(Object entity):void|消す',
        'createQuery(String qlString, Class resultClass):TypedQuery|JPQLで検索する',
        'getTransaction():EntityTransaction|トランザクションを取る', 'flush():void|溜まった変更をDBへ送る', 'close():void|閉じる']
    },
    TypedQuery: { doc: '型が決まったJPQLの検索', i: ['setParameter(String name, Object value):TypedQuery|名前付きの値を入れる', 'getResultList():List|結果を全部取る', 'getSingleResult():Object|1件だけ取る'] },
    EntityTransaction: { doc: 'JPAのトランザクション', i: ['begin():void|始める', 'commit():void|確定する', 'rollback():void|取り消す', 'isActive():boolean|進行中か'] }
  };

  // ---------------------------------------------------------------- 前処理

  var KEYWORDS = [
    'abstract', 'assert', 'break', 'case', 'catch', 'class', 'continue', 'default',
    'do', 'else', 'enum', 'extends', 'final', 'finally', 'for', 'if', 'implements',
    'import', 'instanceof', 'interface', 'native', 'new', 'package', 'private',
    'protected', 'public', 'record', 'return', 'sealed', 'static', 'strictfp',
    'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient',
    'try', 'var', 'volatile', 'while', 'yield'
  ];

  var PRIMITIVES = ['boolean', 'byte', 'char', 'double', 'float', 'int', 'long', 'short', 'void'];

  var LITERALS = ['true', 'false', 'null'];

  // クラス名 → パッケージ名。import補完と、候補の右側に出す説明に使う
  var PKG_OF = {};
  var PKG_NAMES = [];
  var CLASSES_IN_PKG = {};
  (function () {
    for (var pkg in PACKAGES) {
      if (!Object.prototype.hasOwnProperty.call(PACKAGES, pkg)) { continue; }
      var names = PACKAGES[pkg].split(' ');
      PKG_NAMES.push(pkg);
      CLASSES_IN_PKG[pkg] = names;
      for (var i = 0; i < names.length; i++) {
        // 同じ名前が複数のパッケージにある場合は先に書いたほうを採る
        if (!PKG_OF[names[i]]) { PKG_OF[names[i]] = pkg; }
      }
    }
  })();

  // メンバ1行を分解する。'substring(int begin):String|範囲を切り出す' → 部品
  var MEMBER_RE = /^([A-Za-z_$][\w$]*)(?:\(([^)]*)\))?(?::([^|]+))?(?:\|([\s\S]*))?$/;

  function parseMember(spec, owner, isStatic) {
    var m = MEMBER_RE.exec(spec);
    if (!m) { return null; }
    var hasParens = spec.indexOf('(') !== -1 && spec.indexOf('(') < spec.indexOf(')');
    return {
      name: m[1],
      kind: hasParens ? 'method' : 'field',
      params: m[2] || '',
      type: m[3] ? m[3].replace(/\s+/g, '') : '',
      doc: m[4] || '',
      owner: owner,
      isStatic: !!isStatic
    };
  }

  // 解析結果をクラスごとに覚えておく（毎回作り直さない）
  var cache = { instance: {}, statics: {} };

  /**
   * クラスのメンバ一覧を返す。
   *
   * インスタンスメンバは親クラスから引き継ぐ（`ext` を辿り、最後は Object）。
   * staticメンバは引き継がない（Javaのinterfaceのstaticメソッドは継承されないし、
   * `ArrayList.of` のような存在しない候補を出さないため）。
   *
   * 戻り値の各要素には depth（0が自分のクラス、1が親…）が入っている。
   * 候補の並び順で、自分のクラスのメンバを上に出すために使う。
   */
  function membersOf(className, wantStatic) {
    var store = wantStatic ? cache.statics : cache.instance;
    if (store[className]) { return store[className]; }

    var out = [];
    var seen = {};
    var depth = 0;
    var name = className;
    var guard = 0;

    while (name && guard++ < 12) {
      var def = CLASSES[name];
      if (!def) { break; }
      var specs = (wantStatic ? def.s : def.i) || [];
      for (var i = 0; i < specs.length; i++) {
        var mem = parseMember(specs[i], name, wantStatic);
        if (!mem) { continue; }
        // 同じ名前と引数の数のものが子クラスに既にあれば、そちらを優先する
        var key = mem.name + '/' + mem.kind + '/' + mem.params.split(',').length;
        if (seen[key]) { continue; }
        seen[key] = true;
        mem.depth = depth;
        out.push(mem);
      }
      if (wantStatic) { break; }              // staticは継承しない
      name = def.ext || (name === 'Object' ? '' : 'Object');
      depth++;
    }

    store[className] = out;
    return out;
  }

  /** 名前で1つだけ引く。連鎖補完（`list.get(0).` ）の型解決に使う。 */
  function memberByName(className, memberName, wantStatic) {
    var list = membersOf(className, wantStatic);
    for (var i = 0; i < list.length; i++) {
      if (list[i].name === memberName) { return list[i]; }
    }
    // staticで見つからなければインスタンス側も見る（`Integer.MAX_VALUE` のような
    // 表記ゆれや、教材のコードでstaticメソッドを変数から呼んでいる場合に効く）
    if (wantStatic) {
      list = membersOf(className, false);
      for (var j = 0; j < list.length; j++) {
        if (list[j].name === memberName) { return list[j]; }
      }
    }
    return null;
  }

  var CLASS_NAMES = (function () {
    var names = [];
    for (var k in CLASSES) {
      if (Object.prototype.hasOwnProperty.call(CLASSES, k)) { names.push(k); }
    }
    names.sort();
    return names;
  })();

  /** `'K,V'` → `['K', 'V']` */
  function typeParamsOf(className) {
    var def = CLASSES[className];
    if (!def || !def.tp) { return []; }
    return def.tp.split(',');
  }

  /** import補完用。`'java.u'` で始まるパッケージ名の、次の区切りまでを集める。 */
  function packagePrefixes(prefix) {
    var out = {};
    for (var i = 0; i < PKG_NAMES.length; i++) {
      var pkg = PKG_NAMES[i];
      if (pkg.indexOf(prefix) !== 0) { continue; }
      var rest = pkg.slice(prefix.length);
      var dot = rest.indexOf('.');
      out[prefix + (dot === -1 ? rest : rest.slice(0, dot))] = true;
    }
    var names = [];
    for (var k in out) {
      if (Object.prototype.hasOwnProperty.call(out, k)) { names.push(k); }
    }
    names.sort();
    return names;
  }

  global.JQJavaApi = {
    keywords: KEYWORDS,
    primitives: PRIMITIVES,
    literals: LITERALS,
    classNames: CLASS_NAMES,
    has: function (name) { return !!CLASSES[name]; },
    docOf: function (name) { return CLASSES[name] ? CLASSES[name].doc : ''; },
    packageOf: function (name) { return PKG_OF[name] || ''; },
    packageNames: PKG_NAMES,
    classesInPackage: function (pkg) { return CLASSES_IN_PKG[pkg] || []; },
    packagePrefixes: packagePrefixes,
    membersOf: membersOf,
    memberByName: memberByName,
    typeParamsOf: typeParamsOf
  };
})(window);
