"""模範解答を「意味を変えない別の書き方」へ変形する。

tools/check_source_alternatives.py が使う。ここで作った変形を提出して、
出力は全部通るのに sourceChecks で落ちるものを探す。

変形が本当に等価かどうかは**ここでは保証しない**。呼び出し側が全テストケースで採点し、
出力が変わったものは捨てる。だからここは「よくある別の書き方」を広めに作ればよい。

kind() が返す種類の名前は、check_source_alternatives.py の ALLOWED の鍵になる。
"""
import re

IDENT = r'[A-Za-z_$][A-Za-z0-9_$]*'
ATOM = r'(?:[A-Za-z_$][A-Za-z0-9_$.]*(?:\s*\([^()]*\))?(?:\s*\[[^\[\]]*\])?|\d+(?:\.\d+)?[LlFfDd]?)'
RECEIVER = r'(?:' + IDENT + r'(?:\s*\.\s*' + IDENT + r'\s*\(\s*\))*|"(?:[^"\\]|\\.)*")'
FLIP = {'<': '>', '>': '<', '<=': '>=', '>=': '<='}
KEYWORDS = {'if', 'for', 'while', 'return', 'new', 'else', 'switch', 'case', 'do',
            'try', 'catch', 'throw', 'this', 'super', 'null', 'true', 'false', 'int',
            'long', 'double', 'boolean', 'char', 'String', 'var', 'void', 'static'}


def kind(label):
    """変形のラベルから種類だけを取り出す（ALLOWED の鍵）。"""
    return label.split(' ', 1)[0]


# ── 複合代入 ─────────────────────────────────────────────────────────

def _top_level_operators(text):
    """かっこと文字列の外にある二項演算子を返す。"""
    found, depth, i = [], 0, 0
    while i < len(text):
        c = text[i]
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c in '"\'':
            quote, i = c, i + 1
            while i < len(text) and text[i] != quote:
                i += 2 if text[i] == '\\' else 1
        elif depth == 0 and c in '+-*/%':
            before = text[:i].rstrip()
            if before and before[-1] not in '+-*/%(,=<>!&|?:':
                found.append(c)
        i += 1
    return found


def _foldable(operator, rest):
    """x = x OP rest を x OP= rest へ畳んでも同じ値になるか。"""
    operators = _top_level_operators(rest)
    if operator == '+':
        return True
    if operator in '-*':
        return not any(o in '+-' for o in operators)
    return not operators


def _compound(code):
    """x = x OP e;  →  x OP= e;"""
    pattern = re.compile(r'(?m)^([ \t]*)(' + IDENT + r')\s*=\s*\2\s*([-+*/%])\s*([^;]+);')
    for m in pattern.finditer(code):
        if not _foldable(m.group(3), m.group(4)):
            continue
        line = f'{m.group(1)}{m.group(2)} {m.group(3)}= {m.group(4).strip()};'
        yield (f'複合代入 {m.group(2)} {m.group(3)}=', code[:m.start()] + line + code[m.end():])


def _decompound(code):
    """x OP= e;  →  x = x OP e;"""
    pattern = re.compile(r'(?m)^([ \t]*)(' + IDENT + r')\s*([-+*/%])=\s*([^;]+);')
    for m in pattern.finditer(code):
        rest, operator = m.group(4).strip(), m.group(3)
        body = rest if _foldable(operator, rest) else f'({rest})'
        line = f'{m.group(1)}{m.group(2)} = {m.group(2)} {operator} {body};'
        yield (f'複合代入 {m.group(2)} {operator}= をほどく',
               code[:m.start()] + line + code[m.end():])


# ── 増減 ─────────────────────────────────────────────────────────────

def _increments(code):
    return re.finditer(r'(' + IDENT + r')(\+\+|--)(\s*[;)])', code)


def _incr_compound(code):
    """x++;  →  x += 1;"""
    for m in _increments(code):
        operator = '+=' if m.group(2) == '++' else '-='
        text = f'{m.group(1)} {operator} 1{m.group(3)}'
        yield (f'増減 {m.group(1)}{m.group(2)} を {operator} 1 に',
               code[:m.start()] + text + code[m.end():])


def _incr_spelled_out(code):
    """x++;  →  x = x + 1;"""
    for m in _increments(code):
        operator = '+' if m.group(2) == '++' else '-'
        text = f'{m.group(1)} = {m.group(1)} {operator} 1{m.group(3)}'
        yield (f'増減 {m.group(1)}{m.group(2)} を {m.group(1)} {operator} 1 に',
               code[:m.start()] + text + code[m.end():])


def _incr_prefix(code):
    """x++;  →  ++x;"""
    for m in _increments(code):
        yield (f'増減 {m.group(1)}{m.group(2)} を前置に',
               code[:m.start()] + f'{m.group(2)}{m.group(1)}{m.group(3)}' + code[m.end():])


def _incr_postfix(code):
    """++x;  →  x++;"""
    for m in re.finditer(r'(\+\+|--)(' + IDENT + r')(\s*[;)])', code):
        yield (f'増減 {m.group(1)}{m.group(2)} を後置に',
               code[:m.start()] + f'{m.group(2)}{m.group(1)}{m.group(3)}' + code[m.end():])


# ── 交換法則・かっこ・比較の向き ──────────────────────────────────────

def _swap_multiply(code):
    """a * b  →  b * a"""
    pattern = re.compile(r'(?<![\w.$])(' + ATOM + r')(\s*\*\s*)(' + ATOM + r')(?![\w.$])')
    for m in pattern.finditer(code):
        yield (f'交換法則 {m.group(1)}*{m.group(3)} の順を入れ替え',
               code[:m.start()] + f'{m.group(3)}{m.group(2)}{m.group(1)}' + code[m.end():])


def _swap_add(code):
    """a + b  →  b + a（文字列連結の行は避ける）"""
    pattern = re.compile(r'(?<![\w.$])(' + ATOM + r')(\s*\+\s*)(' + ATOM + r')(?![\w.$])')
    for m in pattern.finditer(code):
        start = code.rfind('\n', 0, m.start()) + 1
        end = code.find('\n', m.end())
        if any(q in code[start:len(code) if end < 0 else end] for q in '"\''):
            continue
        yield (f'交換法則 {m.group(1)}+{m.group(3)} の順を入れ替え',
               code[:m.start()] + f'{m.group(3)}{m.group(2)}{m.group(1)}' + code[m.end():])


def _parenthesize(code):
    """a + b * c  →  a + (b * c)（優先順位をかっこで明示する）"""
    pattern = re.compile(r'(?<![\w.$])(' + ATOM + r')(\s*[*/%]\s*)(' + ATOM + r')(?![\w.$])')
    for m in pattern.finditer(code):
        before, after = code[:m.start()].rstrip(), code[m.end():].lstrip()
        if not (before.endswith(('+', '-')) or after.startswith(('+', '-'))):
            continue
        if after[:1] in ('+', '-') and after[1:2] in ('+', '-', '='):
            continue
        yield (f'かっこ {m.group(1)}{m.group(2).strip()}{m.group(3)} を囲む',
               code[:m.start()] + f'({m.group(1)}{m.group(2)}{m.group(3)})' + code[m.end():])


def _flip_compare(code):
    """a < b  →  b > a"""
    pattern = re.compile(r'(?<![\w.$])(' + ATOM + r')\s*(<=|>=|<|>)\s*(' + ATOM + r')(?![\w.$])')
    for m in pattern.finditer(code):
        yield (f'比較の向き {m.group(1)}{m.group(2)}{m.group(3)} を入れ替え',
               code[:m.start()] + f'{m.group(3)} {FLIP[m.group(2)]} {m.group(1)}' + code[m.end():])


# ── this. ・equals の向き・ラムダの引数名 ────────────────────────────

def _this_prefix(code):
    """フィールド参照へ this. を付ける（インスタンスメソッドの中では常に書ける）"""
    fields = re.findall(r'(?m)^\s{4}(?:(?:private|protected|public|final)\s+)*'
                        r'(?!static\b|return\b|class\b|record\b|new\b)'
                        r'[A-Za-z_$][\w<>\[\],.$?\s]*?\s(' + IDENT + r')\s*(?:;|=[^=])', code)
    for name in sorted(set(fields) - KEYWORDS):
        mutated = re.sub(r'(?<![\w.$])' + re.escape(name) + r'(?![\w$\s]*[=(])',
                         'this.' + name, code)
        mutated = re.sub(r'(?m)^(\s*(?:private|protected|public|final|static|[\w<>\[\], ?]*?)\s*)'
                         r'this\.' + re.escape(name), r'\1' + name, mutated)
        if mutated != code and 'this.this' not in mutated:
            yield (f'this. {name} に this. を付ける', mutated)


def _swap_equals(code):
    """a.equals(b)  →  b.equals(a)"""
    pattern = re.compile(r'(?<![\w.$])(' + RECEIVER + r')\s*\.\s*equals\s*\(\s*('
                         + RECEIVER + r')\s*\)')
    for m in pattern.finditer(code):
        left, right = m.group(1).strip(), m.group(2).strip()
        if left.startswith('"') and right.startswith('"'):
            continue
        yield (f'equalsの向き {right}.equals({left}) にする',
               code[:m.start()] + f'{right}.equals({left})' + code[m.end():])


def _closing(code, index):
    """code[index] の開きかっこに対応する閉じかっこの位置を返す。"""
    depth = 0
    while index < len(code):
        c = code[index]
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                return index
        elif c == '"':
            index += 1
            while index < len(code) and code[index] != '"':
                index += 2 if code[index] == '\\' else 1
        index += 1
    return -1


def _lambda_parameter(code):
    """x -> ... のラムダ引数名を変える（学習者が自由に決める名前）"""
    for m in re.finditer(r'(?<![\w.$])(' + IDENT + r')(\s*->\s*)', code):
        name = m.group(1)
        before = code[:m.start()].rstrip()
        if name in KEYWORDS or not before or before[-1] not in '(,':
            continue
        opened = code.rfind('(', 0, m.start())
        closed = _closing(code, opened)
        if closed < 0:
            continue
        body = code[m.end():closed]
        renamed = re.sub(r'(?<![\w.$])' + re.escape(name) + r'(?![\w$])', 'q9', body)
        if renamed == body:
            continue
        yield (f'ラムダの引数名 {name} を変える',
               code[:m.start()] + 'q9' + m.group(2) + renamed + code[closed:])


MUTATORS = (_compound, _decompound, _incr_compound, _incr_spelled_out, _incr_prefix,
            _incr_postfix, _swap_multiply, _swap_add, _parenthesize, _flip_compare,
            _this_prefix, _swap_equals, _lambda_parameter)


def variants(code):
    """同じ意味になりそうな書き換えを、重複を除いて返す。"""
    seen, out = {code}, []
    for mutate in MUTATORS:
        for label, mutated in mutate(code):
            if mutated in seen:
                continue
            seen.add(mutated)
            out.append((label, mutated))
    return out
