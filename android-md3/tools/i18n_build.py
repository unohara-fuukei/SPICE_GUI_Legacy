#!/usr/bin/env python3
"""i18n 资产构建（1:1 移植配套）。

core/<lang>.json:  main 的 paraglide 词典 + 8 个工具 messages.json 合并
                  （zh 原文 → 目标语言），逐字搬运，冲突时 paraglide 优先、
                  首键胜出（README 披露）。
extras/<lang>.json: android 分支自写 UI 文案的补译（人工维护）。

用法：
  python3 i18n_build.py <SPICE_main 路径> <android-md3 路径>
"""
import json, glob, os, sys

MAIN = sys.argv[1]
APP = sys.argv[2]
LANGS = ['en', 'ja', 'ko', 'de', 'nl', 'sv']

def core_dict(lang):
    zh = json.load(open(f'{MAIN}/project.inlang/messages/zh.json'))
    target = json.load(open(f'{MAIN}/project.inlang/messages/{lang}.json'))
    out = {}
    for k, v in zh.items():          # paraglide 优先
        out.setdefault(v, target[k])
    for f in sorted(glob.glob(f'{MAIN}/src/lib/tools/*/messages.json')):
        d = json.load(open(f))
        for k, v in d['zh'].items():
            out.setdefault(v, d[lang][k])
    return out

os.makedirs(f'{APP}/app/src/main/assets/i18n/core', exist_ok=True)
os.makedirs(f'{APP}/app/src/main/assets/i18n/extras', exist_ok=True)
for lang in LANGS:
    d = core_dict(lang)
    with open(f'{APP}/app/src/main/assets/i18n/core/{lang}.json', 'w', encoding='utf-8') as f:
        json.dump(d, f, ensure_ascii=False, indent=0, sort_keys=True)
    print(lang, len(d))
