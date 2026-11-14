#!/usr/bin/env python3
"""抽取 android UI 中不在 main 词典内的中文字面量（静态 + 插值模板）。"""
import re, glob, json, collections, sys

APP = '.'
core = json.load(open('app/src/main/assets/i18n/core/en.json'))

def tpl(s):
    s = re.sub(r'\$\{[^{}]*\}', '{v1}', s)
    s = re.sub(r'\$[A-Za-z_][A-Za-z0-9_]*', '{v1}', s)
    return s

def norm(s):
    return s.rstrip(':：…。. \t*')

norm_core = {norm(k) for k in core}
pat = re.compile(r'"((?:[^"\\]|\\.)*[\u4e00-\u9fff](?:[^"\\]|\\.)*)"')
interp = re.compile(r'\$\{|\$[A-Za-z_]')

static = collections.Counter()
inter = collections.Counter()
per_file = collections.defaultdict(lambda: {'static': collections.Counter(), 'inter': collections.Counter()})
for f in sorted(glob.glob('app/src/main/java/**/*.kt', recursive=True)):
    src = open(f, encoding='utf-8').read()
    name = f.split('/')[-1]
    for line_no, line in enumerate(src.splitlines(), 1):
        if re.match(r'\s*(//|import |package )', line): continue
        if re.search(r'\bLog\.[dewiv]\(', line): continue
        for m in pat.finditer(line):
            lit = m.group(1)
            if interp.search(lit):
                t = tpl(lit)
                if t in core: continue
                inter[t] += 1
                per_file[name]['inter'][t] += 1
            else:
                if lit in core or norm(lit) in norm_core: continue
                static[lit] += 1
                per_file[name]['static'][lit] += 1

out = {name: {'static': sorted(c), 'inter': sorted(c2)} for name, c in per_file.items() for c2 in [c['inter']]}
json.dump(out, open('tools/i18n_todo.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('static distinct:', len(static), 'interpolated distinct:', len(inter))
tot = sum(len(v['static'])+len(v['inter']) for v in out.values())
print('entries in todo:', tot)
