#!/usr/bin/env python3
"""Kotlin 中文字面量接线器：把函数体内的展示用中文字面量包装为 I18n.t()/tf()。

安全规则（对齐 main 语义——zh 为源语言，切换即时生效）：
 - 只在函数体内包装：顶层/枚举/对象属性在声明期求值会冻结语言，跳过并报告。
 - remember {}/rememberSaveable {} 块内为组合缓存，同样跳过。
 - when 分支键（"…" ->）、比较（==/!=）、正则/解析/JSON/prefs 行跳过。
 - 插值模板：整个字面量转为 I18n.tf("…{v1}{v2}…", expr1, expr2)。
用法：python3 i18n_wrap.py [--dry]
"""
import re, sys, glob, json, collections

DRY = '--dry' in sys.argv
APP = '.'
zh = re.compile(r'[\u4e00-\u9fff]')
FQ = 'com.spice.nativeapp.core.I18n'

EXCLUDE_LINE = re.compile(
    r'Regex|toRegex|\.matches\(|\.contains\(|\.split\(|\.replace\(|\.startsWith\(|'
    r'\.endsWith\(|\.indexOf\(|\.equals\(|JSONObject|JSONArray|putString\(|'
    r'getString\(|SharedPreferences|Log\.[dewiv]\(')
SKIP_PREFIX = re.compile(r'^\s*(//|\*|import |package |@)')

def scan_literals(line, start=0):
    """返回 [(lit_start, lit_end, literal)]，正确处理 \${} 内嵌套引号。"""
    out = []
    i = start
    n = len(line)
    while i < n:
        c = line[i]
        if c == '"':
            j = i + 1
            depth_stack = []  # brace depth inside ${}
            buf = []
            closed = False
            while j < n:
                d = line[j]
                if depth_stack:
                    if d == '"':  # nested string inside ${}
                        k = j + 1
                        while k < n and line[k] == '\\':
                            k += 2
                        j = k
                    elif d == '{':
                        depth_stack.append(j)
                    elif d == '}':
                        depth_stack.pop()
                    j += 1
                    continue
                if d == '\\':
                    j += 2
                    continue
                if d == '$' and j + 1 < n and line[j + 1] == '{':
                    depth_stack.append(j)
                    j += 2
                    continue
                if d == '"':
                    closed = True
                    break
                j += 1
            if closed:
                out.append((i, j + 1, line[i:j + 1]))
                i = j + 1
            else:
                break
        else:
            i += 1
    return out

def split_template(literal):
    """把 Kotlin 字符串模板拆为 (template_with_{vN}, [expr...])。"""
    inner = literal[1:-1]
    exprs = []
    tpl = []
    i = 0
    n = len(inner)
    while i < n:
        if inner[i] == '\\' and i + 1 < n:
            tpl.append(inner[i:i + 2]); i += 2; continue
        if inner[i] == '$' and i + 1 < n and inner[i + 1] == '{':
            depth = 1
            j = i + 2
            while j < n and depth:
                c = inner[j]
                if c == '"':  # 跳过 ${} 内的嵌套字符串
                    j += 1
                    while j < n and inner[j] != '"':
                        if inner[j] == '\\': j += 1
                        j += 1
                elif c == '{': depth += 1
                elif c == '}': depth -= 1
                j += 1
            # j 为闭合 '}' 之后一位
            exprs.append(inner[i + 2:j - 1])
            tpl.append('{v%d}' % (len(exprs)))
            i = j
            continue
        if inner[i] == '$' and i + 1 < n and (inner[i+1].isalpha() or inner[i+1] == '_'):
            j = i + 1
            while j < n and (inner[j].isalnum() or inner[j] == '_'): j += 1
            exprs.append(inner[i + 1:j])
            tpl.append('{v%d}' % (len(exprs)))
            i = j
            continue
        tpl.append(inner[i]); i += 1
    return ''.join(tpl), exprs

def function_ranges(src):
    """粗扫函数体范围 [(start_line, end_line)]（行号 0 基）。"""
    ranges = []
    lines = src.split('\n')
    fun_re = re.compile(r'^\s*(?:@\w+\s+|private\s+|internal\s+|public\s+|suspend\s+|inline\s+)*fun\s')
    i = 0
    while i < len(lines):
        m = fun_re.match(lines[i])
        if m:
            depth = 0
            started = False
            j = i
            # 函数头可能跨行，找首个 { 后配对
            while j < len(lines):
                for ch in lines[j]:
                    if ch == '{':
                        depth += 1; started = True
                    elif ch == '}':
                        depth -= 1
                if started and depth <= 0:
                    break
                j += 1
            ranges.append((i, min(j, len(lines) - 1)))
            i = j + 1
        else:
            i += 1
    return ranges

def wrap_file(path):
    src = open(path, encoding='utf-8').read()
    if not zh.search(src):
        return None
    lines = src.split('\n')
    ranges = function_ranges(src)
    wrapped = []      # (line_no, template_or_key, is_tf, exprs)
    edits = []        # (line_no, new_line)
    for (a, b) in ranges:
        # remember 冻结区：粗略跳过 remember { .. } 行区间
        frozen = []
        depth = 0
        for ln in range(a, b + 1):
            line = lines[ln]
            if depth == 0 and re.search(r'remember(Saveable)?\s*[({]', line) and '{' in line:
                # 从 remember 行起跟踪到该块闭合
                depth = line.count('{') - line.count('}')
                frozen.append(ln)
                continue
            if depth > 0:
                frozen.append(ln)
                depth += line.count('{') - line.count('}')
            if depth < 0: depth = 0
        frozen = set(frozen)
        for ln in range(a, b + 1):
            if ln in frozen: continue
            line = lines[ln]
            if SKIP_PREFIX.match(line): continue
            if EXCLUDE_LINE.search(line): continue
            if 'I18n.t(' in line or 'I18n.tf(' in line: continue
            lits = scan_literals(line)
            if not lits: continue
            new = line
            changed = False
            for (s, e, lit) in reversed(lits):
                if not zh.search(lit): continue
                # when 键 / 比较
                after = line[e:e + 3]
                before = line[max(0, s - 3):s]
                if after.startswith(' ->'): continue
                if before.endswith('== ') or before.endswith('!= '): continue
                # 仅当整个字面量即完整串（首尾都是引号——scan 已保证）
                if '${' in lit or re.search(r'\$[A-Za-z_]', lit):
                    tpl, exprs = split_template(lit)
                    if not zh.search(tpl): continue
                    repl = f'{FQ}.tf("{tpl}"' + ''.join(f', {x}' for x in exprs) + ')'
                    new = new[:s] + repl + new[e:]
                    wrapped.append((tpl, True, exprs))
                else:
                    repl = f'{FQ}.t("{lit[1:-1]}")'
                    new = new[:s] + repl + new[e:]
                    wrapped.append((lit[1:-1], False, []))
                changed = True
            if changed:
                edits.append((ln, new))
    if edits and not DRY:
        for ln, new in edits:
            lines[ln] = new
        open(path, 'w', encoding='utf-8').write('\n'.join(lines))
    return wrapped, edits

CORE_FILES = ['CloningMethods', 'CloneValidation', 'Calculators', 'CloningPlus', 'SpdLibrary',
              'SangerValidate', 'Cloning', 'PrimerAnalysis', 'ProteinBiophysics', 'ModelStore',
              'SpiceOnnx', 'GenomicsTools', 'SeqIO', 'CodeSign', 'Structure']
targets = sorted(glob.glob('app/src/main/java/com/spice/nativeapp/ui/**/*.kt', recursive=True) +
                 glob.glob('app/src/main/java/com/spice/nativeapp/tools/**/*.kt', recursive=True) +
                 ['app/src/main/java/com/spice/nativeapp/MainActivity.kt'] +
                 [f'app/src/main/java/com/spice/nativeapp/core/{n}.kt' for n in CORE_FILES])

all_wrapped = []
edit_total = 0
for f in targets:
    r = wrap_file(f)
    if not r: continue
    wrapped, edits = r
    all_wrapped.extend((f, *w) for w in wrapped)
    edit_total += len(edits)
    print(f"{f.split('/')[-1]:35s} wraps={len(wrapped):4d} edits={len(edits):4d}")

keys = collections.Counter()
for f, k, is_tf, e in all_wrapped:
    keys[k] += 1
json.dump({'keys': sorted(keys), 'count': len(keys)},
          open('tools/i18n_wrapped.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('---')
print('total wraps:', len(all_wrapped), 'distinct keys:', len(keys), 'lines edited:', edit_total)
